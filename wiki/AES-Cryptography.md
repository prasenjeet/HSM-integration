# AES Cryptography

`AesCrypto` provides AES symmetric encryption and decryption using an HSM-resident secret key. Two modes are supported:

| Mode | Class | Authenticated? | Recommended |
|---|---|---|---|
| AES-GCM | `encryptGcm` / `decryptGcm` | Yes | Default choice |
| AES-CBC | `encryptCbc` / `decryptCbc` | No | Legacy interoperability |

---

## Instantiation

```java
try (HsmManager hsm = HsmManager.open(HsmConfig.load())) {
    AesCrypto aes = new AesCrypto(hsm);
    KeyManager km  = new KeyManager(hsm);
    SecretKey  key = km.getSecretKey("my-aes-key");
    // ... use aes
}
```

---

## AES-GCM (recommended)

AES-GCM (Galois/Counter Mode) is an **authenticated encryption** scheme. It simultaneously provides:
- **Confidentiality** – ciphertext is computationally indistinguishable from random bytes.
- **Integrity** – any modification to the ciphertext or AAD is detected and rejected.

### Output format

```
┌──────────────┬────────────────────────┬─────────────┐
│  IV (12 B)   │  ciphertext (n bytes)  │  tag (16 B) │
└──────────────┴────────────────────────┴─────────────┘
```

The `encryptGcm` method generates a fresh random IV for every call and prepends it to the output. The caller does not need to manage IVs separately.

### Encrypt

```java
byte[] plaintext  = "sensitive data".getBytes(StandardCharsets.UTF_8);
byte[] ciphertext = aes.encryptGcm(plaintext, key);
// ciphertext = [12-byte IV][encrypted data][16-byte auth tag]
```

### Decrypt

```java
byte[] recovered = aes.decryptGcm(ciphertext, key);
// Throws AesCrypto.CryptoException if the auth tag does not match
```

### Additional Authenticated Data (AAD)

AAD is metadata that is authenticated but **not** encrypted. Use it to bind a ciphertext to a specific context (e.g., a record ID or request header) so the ciphertext cannot be replayed in a different context.

```java
byte[] aad = "record-id:42".getBytes(StandardCharsets.UTF_8);

byte[] ct        = aes.encryptGcm(plaintext, key, aad);
byte[] recovered = aes.decryptGcm(ct, key, aad);          // OK

// Wrong AAD → CryptoException (tag mismatch)
aes.decryptGcm(ct, key, "record-id:99".getBytes());       // throws
```

---

## AES-CBC

AES-CBC (Cipher Block Chaining) provides confidentiality only. There is no built-in authentication; if you need tamper detection with CBC, add a separate HMAC-SHA-256.

### Output format

```
┌──────────────┬─────────────────────────┐
│  IV (16 B)   │  ciphertext (n bytes)   │
└──────────────┴─────────────────────────┘
```

### Encrypt

```java
byte[] ciphertext = aes.encryptCbc(plaintext, key);
```

### Decrypt

```java
byte[] recovered = aes.decryptCbc(ciphertext, key);
```

---

## GCM parameters

| Parameter | Value | Configurable? |
|---|---|---|
| IV length | 12 bytes | No (NIST recommended for GCM) |
| Auth tag length | 128 bits | No (maximum, most secure) |
| AAD | empty `byte[]` by default | Yes |

---

## Key sizes

```java
// 256-bit key (recommended)
SecretKey aes256 = km.generateAesKey("key-256", 256);

// 128-bit key (minimum recommended; use for legacy systems)
SecretKey aes128 = km.generateAesKey("key-128", 128);

// 192-bit key
SecretKey aes192 = km.generateAesKey("key-192", 192);
```

---

## Common patterns

### Encrypt-then-store (database field encryption)

```java
String recordId  = "user:42";
byte[] aad       = recordId.getBytes(StandardCharsets.UTF_8);
byte[] plaintext = sensitiveField.getBytes(StandardCharsets.UTF_8);

byte[] ct = aes.encryptGcm(plaintext, key, aad);
// Store ct in database alongside recordId
```

```java
// Retrieve and decrypt
byte[] ct       = fetchFromDb(recordId);
byte[] aad      = recordId.getBytes(StandardCharsets.UTF_8);
byte[] plain    = aes.decryptGcm(ct, key, aad);
String value    = new String(plain, StandardCharsets.UTF_8);
```

### Envelope encryption (HSM wraps a data key)

```java
// 1. Generate a one-time data encryption key (DEK) in software
KeyGenerator kg = KeyGenerator.getInstance("AES");
kg.init(256);
SecretKey dek = kg.generateKey();

// 2. Encrypt the data with the DEK
byte[] encryptedData = aes.encryptGcm(bulkData, dek);

// 3. Wrap the DEK with the HSM key encryption key (KEK)
//    Use RSA-OAEP wrapping or AES Key Wrap (CKM_AES_KEY_WRAP)
byte[] wrappedDek = rsa.encrypt(dek.getEncoded(), km.getPublicKey("kek-rsa"));

// Store: wrappedDek + encryptedData
```

---

## Security notes

- **Never reuse an IV** with the same key in GCM mode. `AesCrypto` generates a new random IV for every `encryptGcm` call, making IV reuse practically impossible.
- **AES-GCM is not length-hiding.** The ciphertext is the same length as the plaintext (plus IV and tag). If payload length is sensitive, add padding before encrypting.
- **AES-CBC requires careful padding.** PKCS#5 padding is applied automatically, but CBC is vulnerable to padding-oracle attacks if the decryption result is used to drive application logic. Prefer GCM.
- The `SecretKey` objects returned by `KeyManager` are HSM references. Calling `key.getEncoded()` on them returns `null` (keys are non-extractable).

---

## Error handling

Both encrypt and decrypt methods throw `AesCrypto.CryptoException` (unchecked) on failure.

The most common cause for `decryptGcm` failure is a **mismatched authentication tag**, which means:
- The ciphertext was tampered with.
- The wrong key was used.
- The AAD does not match what was provided during encryption.
