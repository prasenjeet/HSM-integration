# Java HSM Integration – Wiki

Welcome to the documentation wiki for the **Java HSM Integration** project.  
This project demonstrates production-grade cryptographic key management using [SoftHSM2](https://github.com/opendnssec/SoftHSMv2) as an open-source Hardware Security Module (HSM) backend, integrated with Java through the standard PKCS#11 interface.

---

## Pages

| Page | Description |
|---|---|
| [Installation & Setup](Installation-and-Setup.md) | Install SoftHSM2, initialise a token, build and run the project |
| [Architecture](Architecture.md) | Component overview, class responsibilities, data flow |
| [Configuration](Configuration.md) | All tuneable parameters – system properties, env vars, and defaults |
| [Key Management](Key-Management.md) | Generating, retrieving, listing, and deleting HSM keys |
| [RSA Cryptography](RSA-Cryptography.md) | RSA key pairs, PSS/PKCS#1 signing, OAEP encryption |
| [AES Cryptography](AES-Cryptography.md) | AES-GCM authenticated encryption and AES-CBC |
| [EC Cryptography](EC-Cryptography.md) | ECDSA key pairs and digital signatures |
| [Testing Guide](Testing-Guide.md) | Unit tests, integration tests, and CI setup |
| [Docker & CI/CD](Docker-and-CICD.md) | Docker image usage and CI/CD pipeline integration |

---

## At a glance

```
Your Application
      │
      ├── KeyManager    – generate / list / delete RSA, EC, AES keys
      ├── RsaCrypto     – sign, verify, encrypt, decrypt (RSA)
      ├── AesCrypto     – AES-GCM and AES-CBC encrypt/decrypt
      └── EcCrypto      – ECDSA sign and verify
            │
       HsmManager       – provider lifecycle + KeyStore login
            │
     SunPKCS11 Provider – built into JDK 9+
            │
     libsofthsm2.so     – PKCS#11 C API
            │
     SoftHSM2 Token     – keys stored on disk (never extractable in cleartext)
```

## Quick start

```bash
# 1. Install SoftHSM2
sudo apt-get install softhsm2        # Ubuntu/Debian
brew install softhsm                 # macOS

# 2. Initialise a token
./scripts/init-softhsm.sh

# 3. Build and run
mvn package -DskipTests
java -Dhsm.slot.pin=1234 -jar target/hsm-integration-*.jar
```

## Key design decisions

- **No native bindings** – the `SunPKCS11` provider ships with every JDK 9+ release; no third-party PKCS#11 wrapper is needed.
- **Non-extractable keys** – all generated keys have `CKA_SENSITIVE=true` and `CKA_EXTRACTABLE=false`; private and secret key material never leaves the HSM.
- **AES-GCM over CBC** – authenticated encryption is the default; the IV and authentication tag are bundled into the ciphertext output automatically.
- **RSA-PSS over PKCS#1v1.5** – the probabilistic signature scheme has a tighter security proof and is the recommended default.
- **`AutoCloseable` lifecycle** – `HsmManager` implements `AutoCloseable`; using it in a try-with-resources block guarantees the provider is deregistered and the session is closed.
