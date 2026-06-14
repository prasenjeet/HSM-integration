# Installation & Setup

This page walks through installing SoftHSM2, initialising an HSM token, building the project, and running the demo application.

---

## 1. Prerequisites

| Requirement | Minimum version | Notes |
|---|---|---|
| Java (JDK) | 17 | `SunPKCS11` is built in; no extras needed |
| Maven | 3.8 | Build and dependency management |
| SoftHSM2 | 2.6 | Only required for demo and integration tests |

---

## 2. Install SoftHSM2

### Ubuntu / Debian

```bash
sudo apt-get update
sudo apt-get install softhsm2
```

The shared library will be at:
```
/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
```

### macOS (Homebrew)

```bash
brew install softhsm
```

Library paths:
- Apple Silicon: `/opt/homebrew/lib/softhsm/libsofthsm2.so`
- Intel: `/usr/local/lib/softhsm/libsofthsm2.so`

### RHEL / CentOS / Fedora

```bash
sudo dnf install softhsm
```

Library path:
```
/usr/lib64/softhsm/libsofthsm2.so
```

### Docker

See [Docker & CI/CD](Docker-and-CICD.md) for a Docker image that includes SoftHSM2 and an auto-initialised token.

---

## 3. Initialise a SoftHSM2 token

Run the provided script:

```bash
./scripts/init-softhsm.sh
```

What the script does:
1. Detects or creates a `softhsm2.conf` configuration file.
2. Calls `softhsm2-util --init-token` to create a new token labelled `JavaHSMDemo`.
3. Sets the Security Officer (SO) PIN and the User PIN.
4. Skips silently if the token already exists (idempotent).

### Custom token settings

```bash
HSM_TOKEN_LABEL=MyToken \
HSM_SO_PIN=adminpin \
HSM_SLOT_PIN=userpin \
./scripts/init-softhsm.sh
```

### Manual initialisation (without the script)

```bash
softhsm2-util --init-token --free \
  --label  JavaHSMDemo \
  --so-pin 12345678 \
  --pin    1234
```

### Verify the token exists

```bash
softhsm2-util --show-slots
```

Expected output (slot number may vary):
```
Available slots:
Slot 0
    Slot info:
        Description:      SoftHSM slot ID 0x...
        ...
    Token info:
        Manufacturer ID:  SoftHSM project
        Model:            SoftHSM v2
        Hardware version: 2.6
        Firmware version: 2.6
        Flags:            CKF_RNG|CKF_LOGIN_REQUIRED|CKF_USER_PIN_INITIALIZED|CKF_TOKEN_INITIALIZED
        Label:            JavaHSMDemo
```

---

## 4. Build the project

```bash
mvn package -DskipTests
```

This produces `target/hsm-integration-1.0.0-SNAPSHOT.jar` — a self-contained fat jar with all dependencies shaded in.

To also run the unit tests (no HSM required):

```bash
mvn package
```

---

## 5. Run the demo application

```bash
java -Dhsm.slot.pin=1234 -jar target/hsm-integration-*.jar
```

On first run, the application generates RSA, EC, and AES keys in the token and exercises each algorithm. On subsequent runs it reuses the existing keys.

### Override the library path

```bash
java \
  -Dhsm.lib.path=/opt/homebrew/lib/softhsm/libsofthsm2.so \
  -Dhsm.slot.pin=1234 \
  -jar target/hsm-integration-*.jar
```

### Sample output

```
21:04:03.812 INFO  HsmManager - Opening HSM connection: library=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
21:04:03.941 INFO  HsmManager - HSM ready – provider=SoftHSM2
21:04:03.945 INFO  Main - HSM token inventory (0 keys): []
21:04:03.946 INFO  Main - --- RSA Demo ---
21:04:06.120 INFO  KeyManager - RSA key pair 'demo-rsa-2048' generated and stored in HSM
21:04:06.221 INFO  Main - RSA-PSS signature: 6a3fd2c091e85f4f…(256 bytes total)
21:04:06.226 INFO  Main - RSA-PSS verify:    OK
21:04:06.226 INFO  Main - Tamper detection:  OK (rejected)
21:04:06.379 INFO  Main - RSA-OAEP roundtrip:OK
21:04:06.379 INFO  Main - --- ECDSA Demo ---
...
21:04:06.530 INFO  Main - === Demo complete ===
```

---

## 6. Reset / clean up

To delete the demo token and start fresh:

```bash
softhsm2-util --delete-token --token JavaHSMDemo
./scripts/init-softhsm.sh
```

To delete individual keys from the token without reinitialising:

```java
KeyManager km = new KeyManager(hsm);
km.deleteKey("demo-rsa-2048");
```
