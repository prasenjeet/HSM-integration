# Java HSM Integration with SoftHSM2

A sample Java project demonstrating cryptographic key operations inside a Hardware Security Module (HSM) using [SoftHSM2](https://github.com/opendnssec/SoftHSMv2) as the open-source HSM backend and Java's built-in `SunPKCS11` provider as the integration layer.

## Features

- **RSA** – key-pair generation (2048/3072/4096-bit), PSS and PKCS#1v1.5 signing/verification, OAEP encryption/decryption
- **EC** – key-pair generation (P-256/P-384/P-521), ECDSA signing/verification (SHA-256/384/512)
- **AES** – secret-key generation (128/192/256-bit), GCM authenticated encryption, CBC encryption
- **Key management** – persistent token storage, listing, and deletion by alias
- **Zero native bindings** – uses the `SunPKCS11` provider built into JDK 9+
- **43 unit tests** that run without any HSM installed (BouncyCastle software provider)
- **Integration tests** against a real SoftHSM2 token

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Your Application                  │
├──────────┬──────────┬──────────┬────────────────────┤
│ RsaCrypto│ AesCrypto│ EcCrypto │    KeyManager      │
├──────────┴──────────┴──────────┴────────────────────┤
│                    HsmManager                       │
│          (provider lifecycle + KeyStore)            │
├─────────────────────────────────────────────────────┤
│              Java SunPKCS11 Provider                │
│               (built into JDK 9+)                   │
├─────────────────────────────────────────────────────┤
│           libsofthsm2.so  (PKCS#11 C API)           │
├─────────────────────────────────────────────────────┤
│              SoftHSM2 Token (on disk)               │
└─────────────────────────────────────────────────────┘
```

### Key classes

| Class | Responsibility |
|---|---|
| `HsmConfig` | Resolves library path / PIN from system properties, environment variables, classpath, or defaults |
| `HsmManager` | Registers `SunPKCS11`, logs in to the HSM token, exposes a `KeyStore`. `AutoCloseable`. |
| `KeyManager` | Generates and retrieves RSA, EC, AES keys; lists and deletes by alias |
| `RsaCrypto` | RSA sign, verify, encrypt, decrypt |
| `AesCrypto` | AES-GCM (authenticated) and AES-CBC encrypt/decrypt |
| `EcCrypto` | ECDSA sign and verify |
| `HsmUtils` | Hex codec, SHA-2 digests, constant-time comparison |
| `Main` | End-to-end demo of all operations |

## Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | 17+ |
| Maven | 3.8+ |
| SoftHSM2 | 2.6+ (only for the demo and integration tests) |

### Install SoftHSM2

**Ubuntu / Debian**
```bash
sudo apt-get install softhsm2
```

**macOS (Homebrew)**
```bash
brew install softhsm
```

**RHEL / CentOS / Fedora**
```bash
sudo dnf install softhsm
```

**Docker** – see [docker/softhsm2/](docker/softhsm2/) for a ready-to-use image.

## Quick start

### 1. Initialise the SoftHSM2 token

```bash
./scripts/init-softhsm.sh
```

This creates a token labelled `JavaHSMDemo` with the default PIN `1234`. Re-running the script is safe (it is idempotent).

Custom values:
```bash
HSM_TOKEN_LABEL=MyToken HSM_SLOT_PIN=s3cr3t ./scripts/init-softhsm.sh
```

### 2. Build

```bash
mvn package -DskipTests
```

### 3. Run the demo

```bash
java -Dhsm.slot.pin=1234 -jar target/hsm-integration-*.jar
```

The demo generates RSA, EC, and AES keys in the token (reusing existing ones on repeat runs) and exercises sign/verify and encrypt/decrypt for each algorithm.

## Configuration

All parameters can be set as a system property (`-D`), environment variable, or in `src/main/resources/hsm.properties`.

| System property | Environment variable | Default (Linux) | Description |
|---|---|---|---|
| `hsm.lib.path` | `HSM_LIB_PATH` | `/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so` | Path to the PKCS#11 shared library |
| `hsm.slot.pin` | `HSM_SLOT_PIN` | `1234` | User PIN for the HSM token slot |
| `hsm.slot.index` | `HSM_SLOT_INDEX` | `0` | Slot list index (0 = first initialised token) |
| `hsm.provider.name` | `HSM_PROVIDER_NAME` | `SoftHSM2` | JCA provider name |

macOS library path: `/opt/homebrew/lib/softhsm/libsofthsm2.so` (Apple Silicon) or `/usr/local/lib/softhsm/libsofthsm2.so` (Intel).

## Usage examples

### HSM lifecycle

```java
HsmConfig config = HsmConfig.load();           // reads sys-props / env

try (HsmManager hsm = HsmManager.open(config)) {
    // HSM is open and authenticated for this block
    KeyManager km = new KeyManager(hsm);
    // ... perform operations ...
}   // automatically logs out and deregisters the provider
```

### Generate keys

```java
KeyManager km = new KeyManager(hsm);

// RSA 2048-bit key pair stored persistently on the token
KeyPair rsaKeys = km.generateRsaKeyPair("my-rsa-key", 2048);

// EC P-256 key pair
KeyPair ecKeys = km.generateEcKeyPair("my-ec-key", "secp256r1");

// AES-256 secret key
SecretKey aesKey = km.generateAesKey("my-aes-key", 256);
```

### RSA sign and verify

```java
RsaCrypto rsa = new RsaCrypto(hsm);
byte[] data = "payload".getBytes(UTF_8);

// Sign (private key stays inside the HSM)
byte[] signature = rsa.sign(data, km.getPrivateKey("my-rsa-key"));

// Verify
boolean ok = rsa.verify(data, signature, km.getPublicKey("my-rsa-key"));
```

### RSA encrypt and decrypt

```java
// Encrypt a small payload or symmetric key with RSA-OAEP
byte[] ciphertext = rsa.encrypt(plaintext, km.getPublicKey("my-rsa-key"));
byte[] plaintext  = rsa.decrypt(ciphertext, km.getPrivateKey("my-rsa-key"));
```

### ECDSA sign and verify

```java
EcCrypto ec = new EcCrypto(hsm);

byte[] sig = ec.sign(data, km.getPrivateKey("my-ec-key"));
boolean ok = ec.verify(data, sig, km.getPublicKey("my-ec-key"));

// SHA-384 variant
byte[] sig384 = ec.sign(data, km.getPrivateKey("my-ec-p384"), EcCrypto.ALG_ECDSA_SHA384);
```

### AES-GCM authenticated encryption

```java
AesCrypto aes = new AesCrypto(hsm);
SecretKey key = km.getSecretKey("my-aes-key");

byte[] aad        = "request-id:abc123".getBytes(UTF_8);
byte[] ciphertext = aes.encryptGcm(plaintext, key, aad);   // iv || ciphertext || tag
byte[] recovered  = aes.decryptGcm(ciphertext, key, aad);  // throws if tag invalid
```

### Key management

```java
// List all keys on the token
List<String> aliases = km.listAliases();

// Check existence
boolean exists = km.exists("my-rsa-key");

// Delete (irreversible)
km.deleteKey("old-key");
```

## Testing

### Unit tests (no HSM required)

```bash
mvn test
```

Uses BouncyCastle as a software JCA provider — no SoftHSM2 installation needed. 43 tests covering all crypto operations.

### Integration tests (requires SoftHSM2)

```bash
# Initialise the token first
./scripts/init-softhsm.sh

# Run all tests including integration tests
mvn verify -Pintegration -Dhsm.slot.pin=1234
```

Override the library path when it differs from the Linux default:

```bash
mvn verify -Pintegration \
  -Dhsm.lib.path=/opt/homebrew/lib/softhsm/libsofthsm2.so \
  -Dhsm.slot.pin=1234
```

## Docker

A ready-to-use Docker image bundles SoftHSM2, initialises a token at build time, and runs the demo application.

```bash
# Build
cd docker/softhsm2
docker build -t hsm-integration-demo .

# Run demo
docker run --rm hsm-integration-demo

# Run with a custom PIN
docker run --rm -e HSM_SLOT_PIN=s3cr3t hsm-integration-demo
```

## Project structure

```
hsm-integration/
├── pom.xml
├── scripts/
│   └── init-softhsm.sh          # one-shot token initialisation
├── docker/softhsm2/
│   ├── Dockerfile
│   └── setup.sh
└── src/
    ├── main/
    │   ├── java/com/example/hsm/
    │   │   ├── HsmManager.java
    │   │   ├── Main.java
    │   │   ├── config/HsmConfig.java
    │   │   ├── crypto/
    │   │   │   ├── AesCrypto.java
    │   │   │   ├── EcCrypto.java
    │   │   │   └── RsaCrypto.java
    │   │   ├── keys/KeyManager.java
    │   │   └── util/HsmUtils.java
    │   └── resources/
    │       ├── pkcs11.cfg        # PKCS#11 provider config template
    │       └── logback.xml
    └── test/
        └── java/com/example/hsm/
            ├── HsmIntegrationTest.java
            └── crypto/
                ├── AesCryptoTest.java
                ├── EcCryptoTest.java
                ├── FakeHsmManager.java
                └── RsaCryptoTest.java
```

## Security notes

- Private keys and AES secret keys are generated with `CKA_SENSITIVE=true` and `CKA_EXTRACTABLE=false` — they never leave the HSM in cleartext.
- The PIN is read at startup and held in memory as a `char[]` (not `String`) to allow clearing after use.
- AES-GCM is preferred over CBC — it provides authenticated encryption and detects ciphertext tampering.
- RSA-PSS is preferred over PKCS#1v1.5 for signatures — it has a tighter security proof.
- Signature verification uses constant-time operations inside the JCA engine; `HsmUtils.constantTimeEquals` is available for application-level comparisons.

## Dependencies

| Library | Purpose |
|---|---|
| `bcprov-jdk18on` | BouncyCastle JCA provider (algorithm support + unit-test software HSM) |
| `bcpkix-jdk18on` | BouncyCastle PKIX (X.509 certificate support) |
| `slf4j-api` + `logback-classic` | Logging |
| `junit-jupiter` | Unit and integration testing |
| `mockito-junit-jupiter` | Test doubles |

## License

This project is released as sample/reference code. Adapt it freely for your own HSM integration needs.
