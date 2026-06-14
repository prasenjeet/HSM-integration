# Key Management

`KeyManager` provides all HSM key lifecycle operations: generation, retrieval, listing, and deletion.  Keys generated through `KeyManager` are stored as **token objects** – they are persistent across JVM restarts and are never extractable in cleartext.

---

## Instantiation

```java
try (HsmManager hsm = HsmManager.open(HsmConfig.load())) {
    KeyManager km = new KeyManager(hsm);
    // ... use km
}
```

---

## Generating keys

### RSA key pair

```java
// 2048-bit RSA key pair stored in the HSM under alias "signing-key"
KeyPair kp = km.generateRsaKeyPair("signing-key", 2048);
```

Supported key sizes: `2048`, `3072`, `4096`.  
The private key returned is an HSM **handle** – the raw bytes never leave the HSM.  
The public key is a plain JCE `RSAPublicKey` and can be freely exported.

### EC key pair

```java
// P-256 key pair
KeyPair kp256 = km.generateEcKeyPair("ec-key-p256", "secp256r1");

// P-384 key pair
KeyPair kp384 = km.generateEcKeyPair("ec-key-p384", "secp384r1");

// P-521 key pair
KeyPair kp521 = km.generateEcKeyPair("ec-key-p521", "secp521r1");
```

Supported curve names (ANSI / SEC / NIST):

| Alias | Also known as |
|---|---|
| `secp256r1` | P-256, prime256v1 |
| `secp384r1` | P-384 |
| `secp521r1` | P-521 |

### AES secret key

```java
SecretKey aes256 = km.generateAesKey("data-encryption-key", 256);
SecretKey aes128 = km.generateAesKey("legacy-key", 128);
```

Supported key sizes: `128`, `192`, `256`.

---

## Retrieving existing keys

Keys are addressed by the alias string used when they were created.

```java
// Private key (HSM reference)
PrivateKey priv = km.getPrivateKey("signing-key");

// Public key (from the certificate entry; plain JCE object)
PublicKey pub = km.getPublicKey("signing-key");

// AES secret key (HSM reference)
SecretKey sk = km.getSecretKey("data-encryption-key");
```

Attempting to retrieve a key that does not exist throws `KeyManagementException`.

---

## Listing keys

```java
List<String> aliases = km.listAliases();
// e.g. [signing-key, data-encryption-key, ec-key-p256]
```

### Check if a key exists

```java
if (km.exists("signing-key")) {
    // reuse existing key
} else {
    km.generateRsaKeyPair("signing-key", 2048);
}
```

---

## Deleting keys

> **Warning:** key deletion is permanent and irreversible. There is no undo.

```java
km.deleteKey("old-key");
```

Best practice – check first:

```java
if (km.exists("old-key")) {
    km.deleteKey("old-key");
}
```

---

## Key attributes set by default

The PKCS#11 configuration (`src/main/resources/pkcs11.cfg`) applies these attributes to every generated key:

| Attribute | Value | Meaning |
|---|---|---|
| `CKA_TOKEN` | `true` | Key is a persistent token object (survives session close) |
| `CKA_SENSITIVE` | `true` | Key value cannot be read in cleartext via `C_GetAttributeValue` |
| `CKA_EXTRACTABLE` | `false` | Key cannot be wrapped/exported to an application |

These attributes enforce the HSM security boundary: all cryptographic operations using a private or secret key happen **inside** the HSM.

---

## Common patterns

### Generate-or-reuse

```java
private KeyPair getOrCreateRsaKey(KeyManager km, String alias) {
    if (!km.exists(alias)) {
        return km.generateRsaKeyPair(alias, 2048);
    }
    // Return a synthetic KeyPair from the stored handles
    PrivateKey priv = km.getPrivateKey(alias);
    PublicKey  pub  = km.getPublicKey(alias);
    return new KeyPair(pub, priv);
}
```

### Key rotation

```java
String newAlias = "signing-key-v2";
km.generateRsaKeyPair(newAlias, 3072);

// ... update application config to use newAlias ...

// After all active signing operations have drained:
km.deleteKey("signing-key-v1");
```

### List and audit keys

```java
List<String> aliases = km.listAliases();
System.out.println("Keys on HSM token:");
for (String alias : aliases) {
    System.out.printf("  %s%n", alias);
}
```

---

## Error handling

All `KeyManager` methods throw `KeyManager.KeyManagementException` (unchecked) on failure. Common causes:

| Exception message | Likely cause |
|---|---|
| `RSA key generation failed` | HSM not connected, wrong provider, insufficient token storage |
| `No private key found for alias` | Alias does not exist on the token |
| `No certificate/public key found` | Key was generated in a different session or slot |
| `Failed to delete key` | Key does not exist or HSM session expired |
