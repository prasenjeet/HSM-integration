# RSA Cryptography

`RsaCrypto` provides RSA signing/verification and encryption/decryption using HSM-resident private keys. All private-key operations execute **inside the HSM**; only signature bytes or ciphertext cross the boundary.

---

## Instantiation

```java
try (HsmManager hsm = HsmManager.open(HsmConfig.load())) {
    RsaCrypto rsa = new RsaCrypto(hsm);
    KeyManager km  = new KeyManager(hsm);
    // ... use rsa
}
```

---

## Signing

### RSA-PSS (recommended)

RSA-PSS (Probabilistic Signature Scheme) is the modern standard with a tighter security proof than PKCS#1v1.5.

```java
byte[] data = "payload to sign".getBytes(StandardCharsets.UTF_8);

// Default: SHA-256 digest, PSS padding
byte[] signature = rsa.sign(data, km.getPrivateKey("my-rsa-key"));
```

With an explicit algorithm:

```java
byte[] signature = rsa.sign(
    data,
    km.getPrivateKey("my-rsa-key"),
    RsaCrypto.ALG_SIGN_PSS    // "SHA256withRSA/PSS"
);
```

### PKCS#1 v1.5 (legacy / interoperability)

```java
byte[] signature = rsa.sign(
    data,
    km.getPrivateKey("my-rsa-key"),
    RsaCrypto.ALG_SIGN_PKCS1  // "SHA256withRSA"
);
```

Use PKCS#1v1.5 only when interoperating with systems that do not support PSS.

---

## Verification

```java
boolean valid = rsa.verify(data, signature, km.getPublicKey("my-rsa-key"));

if (!valid) {
    throw new SecurityException("Signature verification failed");
}
```

Explicit algorithm (must match what was used for signing):

```java
boolean valid = rsa.verify(
    data,
    signature,
    km.getPublicKey("my-rsa-key"),
    RsaCrypto.ALG_SIGN_PKCS1
);
```

Verification can use either an HSM-resident public key handle or a plain JCE `RSAPublicKey` — both work because the `SunPKCS11` provider handles both.

---

## Encryption / decryption

RSA encryption is suited for **small payloads only** (typically symmetric keys or tokens ≤ 190 bytes for a 2048-bit key with OAEP-SHA-256). For bulk data, use RSA to wrap an AES key and AES-GCM to encrypt the data.

### RSA-OAEP (recommended)

```java
// Encrypt with the recipient's public key
byte[] ciphertext = rsa.encrypt(symmetricKey, km.getPublicKey("recipient-key"));

// Decrypt inside the HSM with the private key
byte[] plaintext  = rsa.decrypt(ciphertext, km.getPrivateKey("recipient-key"));
```

Each encryption call uses a fresh random seed, so two encryptions of the same plaintext produce different ciphertexts.

### PKCS#1 v1.5 encryption (legacy)

```java
byte[] ciphertext = rsa.encrypt(
    plaintext,
    km.getPublicKey("recipient-key"),
    RsaCrypto.ALG_ENCRYPT_PKCS1
);

byte[] plaintext = rsa.decrypt(
    ciphertext,
    km.getPrivateKey("recipient-key"),
    RsaCrypto.ALG_ENCRYPT_PKCS1
);
```

> PKCS#1v1.5 encryption is vulnerable to padding-oracle attacks (ROBOT / Bleichenbacher). Prefer OAEP for new systems.

---

## Algorithm constants

| Constant | String value | Use |
|---|---|---|
| `ALG_SIGN_PSS` | `SHA256withRSA/PSS` | Default signing algorithm |
| `ALG_SIGN_PKCS1` | `SHA256withRSA` | Legacy signing |
| `ALG_ENCRYPT_OAEP` | `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` | Default encryption |
| `ALG_ENCRYPT_PKCS1` | `RSA/ECB/PKCS1Padding` | Legacy encryption |

---

## Hybrid encryption pattern (recommended for bulk data)

```java
// 1. Generate a one-time AES key (in software or HSM)
KeyGenerator kg = KeyGenerator.getInstance("AES");
kg.init(256);
SecretKey aesKey = kg.generateKey();

// 2. Encrypt the data with AES-GCM
AesCrypto aesCrypto = new AesCrypto(hsm);
byte[] encryptedData = aesCrypto.encryptGcm(largePayload, aesKey);

// 3. Wrap the AES key with RSA-OAEP (HSM private-key op)
byte[] wrappedKey = rsa.encrypt(aesKey.getEncoded(), km.getPublicKey("recipient-rsa-key"));

// Send: wrappedKey + encryptedData

// --- Recipient side ---
// 4. Unwrap the AES key inside the HSM
byte[] rawAesKey  = rsa.decrypt(wrappedKey, km.getPrivateKey("recipient-rsa-key"));
SecretKey aesKey2 = new SecretKeySpec(rawAesKey, "AES");

// 5. Decrypt the data
byte[] recovered = aesCrypto.decryptGcm(encryptedData, aesKey2);
```

---

## Maximum plaintext sizes for RSA encryption

| Key size | OAEP-SHA-256 max plaintext | PKCS#1v1.5 max plaintext |
|---|---|---|
| 2048-bit | 190 bytes | 245 bytes |
| 3072-bit | 318 bytes | 373 bytes |
| 4096-bit | 446 bytes | 501 bytes |

Attempting to encrypt more than the limit throws `CryptoException`.

---

## Error handling

All methods throw `RsaCrypto.CryptoException` (unchecked) on failure.

| Cause | Common reason |
|---|---|
| Sign fails | HSM session expired, wrong key type |
| Verify throws | Corrupted signature bytes passed in |
| Encrypt fails | Plaintext too large for the key size |
| Decrypt fails | Ciphertext corrupted, wrong key, algorithm mismatch |
