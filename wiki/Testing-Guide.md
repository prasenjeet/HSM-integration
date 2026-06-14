# Testing Guide

The project ships with two test categories:

| Category | Requires HSM? | Run with |
|---|---|---|
| Unit tests | No | `mvn test` |
| Integration tests | Yes (SoftHSM2) | `mvn verify -Pintegration` |

---

## Unit tests

Unit tests use [BouncyCastle](https://www.bouncycastle.org/) as a software JCA provider instead of a real HSM. The `FakeHsmManager` stub injects the BC provider into the crypto classes so the algorithm logic can be verified without any SoftHSM2 installation.

### Run all unit tests

```bash
mvn test
```

### Test classes

| Test class | What it covers |
|---|---|
| `RsaCryptoTest` | RSA-PSS sign/verify, PKCS#1 sign/verify, OAEP encrypt/decrypt, tamper detection |
| `AesCryptoTest` | AES-GCM roundtrip, wrong-AAD rejection, tamper detection, AES-CBC roundtrip, IV randomness |
| `EcCryptoTest` | ECDSA sign/verify for P-256/P-384/P-521, tamper detection, cross-key rejection |
| `HsmUtilsTest` | Hex encode/decode, SHA-256/384/512, constant-time comparison, truncated hex |

### Test count

```
Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
```

---

## How `FakeHsmManager` works

```java
// FakeHsmManager injects BouncyCastle as the JCA provider
class FakeHsmManager extends HsmManager {
    FakeHsmManager(Provider provider) {
        super(provider, null, null);
    }

    @Override
    public Provider getProvider() { return softwareProvider; }
}

// Usage in a test
@BeforeAll
static void setup() {
    bcProvider = new BouncyCastleProvider();
    Security.addProvider(bcProvider);
    rsaCrypto = new RsaCrypto(new FakeHsmManager(bcProvider));
}
```

The crypto classes (`RsaCrypto`, `AesCrypto`, `EcCrypto`) call `hsm.getProvider()` to get the JCA provider for every operation. Substituting a software provider leaves all algorithm logic intact while removing the HSM dependency.

---

## Integration tests

Integration tests (`HsmIntegrationTest`) exercise the full stack: `HsmManager` → `SunPKCS11` → `libsofthsm2.so` → SoftHSM2 token on disk.

### Prerequisites

1. Install SoftHSM2 and initialise a token:
   ```bash
   sudo apt-get install softhsm2
   ./scripts/init-softhsm.sh
   ```
2. Note the library path (use `find / -name "libsofthsm2.so" 2>/dev/null`).

### Run integration tests

```bash
mvn verify -Pintegration
```

With a custom library path or PIN:

```bash
mvn verify -Pintegration \
  -Dhsm.lib.path=/opt/homebrew/lib/softhsm/libsofthsm2.so \
  -Dhsm.slot.pin=1234
```

### Integration test coverage

| Test | What it verifies |
|---|---|
| `rsaKeyPairGeneratedInHsm` | RSA key pair is stored on the token |
| `ecKeyPairGeneratedInHsm` | EC key pair is stored on the token |
| `aesKeyGeneratedInHsm` | AES key is stored on the token |
| `rsaSignVerify_pss` | Sign with HSM private key, verify with public key |
| `rsaEncryptDecrypt_oaep` | OAEP encrypt with public key, decrypt inside HSM |
| `ecdsaSignVerify` | ECDSA sign inside HSM, verify with public key |
| `aesGcm_roundtrip` | AES-GCM encrypt/decrypt with HSM secret key |
| `keyListingContainsAllGeneratedKeys` | All three generated keys appear in the alias list |
| `keyDeletion_removesFromToken` | Deleted key no longer exists on the token |

---

## Writing new tests

### Unit test pattern

```java
class MyOperationTest {

    private static Provider bcProvider;
    private MyOperation operation;

    @BeforeAll
    static void setup() {
        bcProvider = new BouncyCastleProvider();
        Security.addProvider(bcProvider);
    }

    @AfterAll
    static void teardown() {
        Security.removeProvider(bcProvider.getName());
    }

    @BeforeEach
    void init() {
        operation = new MyOperation(new FakeHsmManager(bcProvider));
    }

    @Test
    void myTest() {
        // ... assertions
    }
}
```

### Integration test pattern

Integration tests must:
1. Be named `*IntegrationTest.java` (excluded by `maven-surefire-plugin`).
2. Use `HsmManager.open(HsmConfig.load())` in a `@BeforeAll`/`@AfterAll` pair.
3. Clean up any keys they create in `@AfterAll`.

---

## CI/CD integration

See [Docker & CI/CD](Docker-and-CICD.md) for a complete GitHub Actions workflow.

Quick reference:

```yaml
- name: Install SoftHSM2
  run: sudo apt-get install -y softhsm2

- name: Initialise token
  run: ./scripts/init-softhsm.sh

- name: Unit tests
  run: mvn test

- name: Integration tests
  run: mvn verify -Pintegration -Dhsm.slot.pin=1234
```

---

## Debugging failing tests

### Provider not found

```
HsmException: SunPKCS11 provider not found – requires JDK 9+
```

Ensure you are running JDK 9 or later: `java -version`.

### Library path wrong

```
HsmException: Failed to write PKCS#11 config file
```
or
```
java.io.IOException: No such file or directory
```

Override the path: `-Dhsm.lib.path=/correct/path/to/libsofthsm2.so`.

### Token not initialised

```
HsmException: Failed to load PKCS#11 KeyStore – check PIN and slot
```

Run `./scripts/init-softhsm.sh` and verify with `softhsm2-util --show-slots`.

### Wrong PIN

```
javax.security.auth.login.LoginException: CKR_PIN_INCORRECT
```

Check the PIN: `-Dhsm.slot.pin=<correct-pin>`.
