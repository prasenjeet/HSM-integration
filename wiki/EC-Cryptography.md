# EC Cryptography

`EcCrypto` provides Elliptic Curve Digital Signature Algorithm (ECDSA) signing and verification using HSM-resident EC private keys.

EC keys are smaller than RSA keys of equivalent security level and produce shorter signatures, making ECDSA well-suited for resource-constrained environments and high-throughput signing services.

---

## Security level comparison

| EC curve | RSA equivalent | Signature size | Key size |
|---|---|---|---|
| P-256 (secp256r1) | RSA-3072 | ~71 bytes | 32 bytes |
| P-384 (secp384r1) | RSA-7680 | ~103 bytes | 48 bytes |
| P-521 (secp521r1) | RSA-15360 | ~139 bytes | 66 bytes |

---

## Instantiation

```java
try (HsmManager hsm = HsmManager.open(HsmConfig.load())) {
    EcCrypto   ec = new EcCrypto(hsm);
    KeyManager km = new KeyManager(hsm);
    // ... use ec
}
```

---

## Signing

### Default (SHA-256, P-256)

```java
byte[] data      = "payload".getBytes(StandardCharsets.UTF_8);
byte[] signature = ec.sign(data, km.getPrivateKey("my-ec-key"));
```

The signing operation executes entirely inside the HSM. Only the DER-encoded signature bytes are returned.

### With explicit algorithm

```java
// SHA-384 with P-384 key
byte[] sig384 = ec.sign(
    data,
    km.getPrivateKey("my-ec-p384"),
    EcCrypto.ALG_ECDSA_SHA384
);

// SHA-512 with P-521 key
byte[] sig512 = ec.sign(
    data,
    km.getPrivateKey("my-ec-p521"),
    EcCrypto.ALG_ECDSA_SHA512
);
```

---

## Verification

```java
boolean valid = ec.verify(data, signature, km.getPublicKey("my-ec-key"));

if (!valid) {
    throw new SecurityException("ECDSA signature invalid");
}
```

With explicit algorithm:

```java
boolean valid = ec.verify(
    data,
    sig384,
    km.getPublicKey("my-ec-p384"),
    EcCrypto.ALG_ECDSA_SHA384
);
```

Verification can use an HSM-resident public key reference or a plain JCE `ECPublicKey` — both work with the `SunPKCS11` provider.

---

## Algorithm constants

| Constant | String value | Matched curve(s) |
|---|---|---|
| `ALG_ECDSA_SHA256` | `SHA256withECDSA` | P-256 |
| `ALG_ECDSA_SHA384` | `SHA384withECDSA` | P-384 |
| `ALG_ECDSA_SHA512` | `SHA512withECDSA` | P-521 |

There is no strict enforcement that algorithm and curve match; the HSM will reject combinations that are mechanically invalid. Follow the NIST recommendation of matching digest size to curve size.

---

## Signature output format

ECDSA signatures are **DER-encoded** (distinguished encoding rules):

```
SEQUENCE {
    INTEGER r,    -- first component
    INTEGER s     -- second component
}
```

To convert to a fixed-length (r‖s) format used by some protocols (e.g., JWS / JWT):

```java
import org.bouncycastle.asn1.*;

byte[] derSig = ec.sign(data, privateKey);

// Parse DER
ASN1Sequence seq = ASN1Sequence.getInstance(derSig);
byte[] r = ((ASN1Integer) seq.getObjectAt(0)).getValue().toByteArray();
byte[] s = ((ASN1Integer) seq.getObjectAt(1)).getValue().toByteArray();

// Pad to curve size (32 bytes for P-256)
byte[] rawSig = new byte[64];
// copy r and s into rawSig with left-padding...
```

---

## Common patterns

### Generate-then-sign

```java
// One-time setup
km.generateEcKeyPair("jwt-signing-key", "secp256r1");

// Per-request signing
byte[] payload   = buildJwtPayload(...);
byte[] signature = ec.sign(payload, km.getPrivateKey("jwt-signing-key"));
```

### Verify an externally received signature

```java
// Obtain the public key (from certificate, JWK, etc.)
PublicKey pubKey = loadPublicKey(...);

byte[] data      = receivedMessage.getPayloadBytes();
byte[] signature = receivedMessage.getSignatureBytes();

boolean trusted = ec.verify(data, signature, pubKey);
```

### Multiple key tiers (root / intermediate)

```java
// Generate a root CA EC key (offline, rarely used)
km.generateEcKeyPair("root-ca-key", "secp384r1");

// Generate an intermediate key (online, daily use)
km.generateEcKeyPair("intermediate-key", "secp256r1");

// Sign the intermediate key's CSR with the root key
byte[] csr       = generateCsr(km.getPublicKey("intermediate-key"));
byte[] rootSig   = ec.sign(csr, km.getPrivateKey("root-ca-key"), EcCrypto.ALG_ECDSA_SHA384);
```

---

## ECDH (not included)

ECDH (Elliptic Curve Diffie-Hellman) key agreement is intentionally excluded from this sample because:

1. The raw shared secret returned by `C_DeriveKey` must be immediately passed through a key-derivation function (HKDF, ANSI X9.63 KDF) before use as an encryption key.
2. SunPKCS11 support for ECDH varies between JDK versions and HSM vendors.

For ECDH-based key exchange, use BouncyCastle's `ECDHBasicAgreement` with the public keys derived from HSM-stored EC key pairs, or implement `CKM_ECDH1_DERIVE` directly via the PKCS#11 C API.

---

## Error handling

All methods throw `EcCrypto.CryptoException` (unchecked) on failure.

| Cause | Common reason |
|---|---|
| Sign fails | HSM session expired; private key not found |
| Verify throws | Corrupted DER encoding or wrong curve |
| `verify` returns false | Signature was produced by a different key or the data was altered |
