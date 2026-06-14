# Architecture

This page describes the component structure, class responsibilities, and how data flows through the HSM integration stack.

---

## Layer diagram

```
┌──────────────────────────────────────────────────────────┐
│                    Your Application                      │
│                                                          │
│   ┌────────────┐  ┌────────────┐  ┌─────────────────┐   │
│   │ RsaCrypto  │  │ AesCrypto  │  │   EcCrypto      │   │
│   └────────────┘  └────────────┘  └─────────────────┘   │
│         │               │                 │              │
│         └───────────────┼─────────────────┘              │
│                         │                                │
│              ┌──────────▼──────────┐                     │
│              │     KeyManager      │                     │
│              └──────────┬──────────┘                     │
│                         │                                │
│              ┌──────────▼──────────┐                     │
│              │     HsmManager      │  ← HsmConfig        │
│              └──────────┬──────────┘                     │
└─────────────────────────┼────────────────────────────────┘
                          │  JCA Provider API
          ┌───────────────▼───────────────┐
          │      SunPKCS11 Provider       │
          │       (built into JDK 9+)     │
          └───────────────┬───────────────┘
                          │  PKCS#11 C API (JNI bridge)
          ┌───────────────▼───────────────┐
          │         libsofthsm2.so        │
          └───────────────┬───────────────┘
                          │  File I/O
          ┌───────────────▼───────────────┐
          │     SoftHSM2 Token (disk)     │
          │  ~/.local/share/softhsm/...   │
          └───────────────────────────────┘
```

---

## Class responsibilities

### `HsmConfig`  (`config/HsmConfig.java`)

Resolves all tunable parameters using a priority chain:

```
System property (-D)  →  Environment variable  →  hsm.properties  →  hard-coded default
```

Generates a temporary PKCS#11 configuration file (required by `SunPKCS11.configure()`) at runtime so the library path and slot index are always up to date.

### `HsmManager`  (`HsmManager.java`)

The single point of contact with the HSM session:

1. Calls `SunPKCS11.configure(cfgFile)` to instantiate the provider.
2. Calls `Security.addProvider(p)` to register it in the JVM.
3. Opens a `KeyStore("PKCS11")` and supplies the user PIN, which triggers `C_Login`.
4. On `close()`, deregisters the provider and finalises the session.

Implements `AutoCloseable` – always use it in a **try-with-resources** block.

### `KeyManager`  (`keys/KeyManager.java`)

Provides algorithm-specific key generation that routes through the HSM provider, then stores keys in the PKCS#11 KeyStore:

| Method | PKCS#11 mechanism |
|---|---|
| `generateRsaKeyPair(alias, bits)` | `CKM_RSA_PKCS_KEY_PAIR_GEN` |
| `generateEcKeyPair(alias, curve)` | `CKM_EC_KEY_PAIR_GEN` |
| `generateAesKey(alias, bits)` | `CKM_AES_KEY_GEN` |

Key retrieval methods return PKCS#11 **key references** (opaque handles), not the raw key bytes.

### `RsaCrypto`  (`crypto/RsaCrypto.java`)

| Operation | JCA algorithm string | PKCS#11 mechanism |
|---|---|---|
| PSS signing | `SHA256withRSA/PSS` | `CKM_SHA256_RSA_PKCS_PSS` |
| PKCS#1 signing | `SHA256withRSA` | `CKM_SHA256_RSA_PKCS` |
| OAEP encryption | `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` | `CKM_RSA_PKCS_OAEP` |
| PKCS#1 encryption | `RSA/ECB/PKCS1Padding` | `CKM_RSA_PKCS` |

Private-key operations (sign, decrypt) are executed **inside the HSM**; only the result bytes cross the boundary.

### `AesCrypto`  (`crypto/AesCrypto.java`)

| Operation | Algorithm | Notes |
|---|---|---|
| `encryptGcm` | `AES/GCM/NoPadding` | 12-byte random IV prepended; 128-bit auth tag appended |
| `decryptGcm` | `AES/GCM/NoPadding` | Throws `CryptoException` if tag check fails |
| `encryptCbc` | `AES/CBC/PKCS5Padding` | 16-byte random IV prepended; no authentication |
| `decryptCbc` | `AES/CBC/PKCS5Padding` | |

Output format for GCM: `[12-byte IV][ciphertext][16-byte auth tag]`  
Output format for CBC: `[16-byte IV][ciphertext]`

### `EcCrypto`  (`crypto/EcCrypto.java`)

| Operation | JCA algorithm string |
|---|---|
| Sign (default) | `SHA256withECDSA` |
| Sign (384) | `SHA384withECDSA` |
| Sign (512) | `SHA512withECDSA` |

Output is DER-encoded (ASN.1 SEQUENCE of two INTEGERs r and s).

### `HsmUtils`  (`util/HsmUtils.java`)

Stateless helpers: hex encoding/decoding, SHA-2 digests, constant-time byte comparison, truncated hex preview for logging.

---

## Data flow: RSA signing

```
Application
    │
    │  data (byte[])
    ▼
RsaCrypto.sign(data, privateKeyRef)
    │
    │  Signature.getInstance("SHA256withRSA/PSS", hsmProvider)
    │  sig.initSign(privateKeyRef)   ← handle, not raw key
    │  sig.update(data)
    │  sig.sign()
    ▼
SunPKCS11 provider
    │
    │  C_SignInit(hSession, CKM_SHA256_RSA_PKCS_PSS, hPrivKey)
    │  C_Sign(hSession, data, dataLen, pSignature, &sigLen)
    ▼
libsofthsm2.so
    │  (computes SHA-256 hash, PSS padding, RSA modular exponentiation
    │   using the stored key – key bytes never leave this layer)
    ▼
SoftHSM2 token (disk)
    │
    ◄─── signature bytes returned up the stack ───────────────────
```

---

## Security boundary

```
 ┌─────────────────────────────────────────────────┐
 │  HSM Boundary (libsofthsm2.so + token on disk)  │
 │                                                 │
 │  • Private keys  (CKA_SENSITIVE=true)           │
 │  • Secret keys   (CKA_EXTRACTABLE=false)        │
 │                                                 │
 │  Operations that cross the boundary:            │
 │    ← ciphertext, signatures, public keys        │
 │    → plaintext to sign/encrypt, public keys     │
 └─────────────────────────────────────────────────┘
```

Only public keys, ciphertext, and signature bytes are visible outside the HSM boundary. Private and secret key material is never serialised to the JVM heap.

---

## Threading model

`HsmManager` is safe to share across threads once opened. `KeyStore`, `Cipher`, and `Signature` instances are **not** thread-safe and must not be shared; each thread (or each operation) should obtain its own `Cipher` / `Signature` instance via `getInstance()`.

`KeyManager`, `RsaCrypto`, `AesCrypto`, and `EcCrypto` each create fresh JCA instances per call and are therefore safe to call concurrently.
