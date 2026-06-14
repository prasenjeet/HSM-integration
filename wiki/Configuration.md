# Configuration

All parameters are resolved at startup using the following priority chain (first match wins):

```
System property (-Dkey=value)
        ↓
Environment variable (UPPER_SNAKE_CASE)
        ↓
src/main/resources/hsm.properties
        ↓
Hard-coded default
```

---

## Parameter reference

| System property | Environment variable | Default (Linux) | Description |
|---|---|---|---|
| `hsm.lib.path` | `HSM_LIB_PATH` | `/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so` | Absolute path to the PKCS#11 shared library |
| `hsm.slot.pin` | `HSM_SLOT_PIN` | `1234` | User PIN for the HSM token slot |
| `hsm.slot.index` | `HSM_SLOT_INDEX` | `0` | Slot list index (0 = first initialised token) |
| `hsm.provider.name` | `HSM_PROVIDER_NAME` | `SoftHSM2` | Name registered with the JCA provider framework |

---

## Common library paths

| OS / Install method | Library path |
|---|---|
| Ubuntu / Debian (apt) x86-64 | `/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so` |
| Ubuntu / Debian (apt) ARM64 | `/usr/lib/aarch64-linux-gnu/softhsm/libsofthsm2.so` |
| RHEL / Fedora / CentOS | `/usr/lib64/softhsm/libsofthsm2.so` |
| macOS (Homebrew, Apple Silicon) | `/opt/homebrew/lib/softhsm/libsofthsm2.so` |
| macOS (Homebrew, Intel) | `/usr/local/lib/softhsm/libsofthsm2.so` |
| Docker image in this repo | `/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so` |

The `HsmConfig` class auto-detects macOS vs. Linux and picks the appropriate default, so you only need to override when the library is in a non-standard location.

---

## Ways to supply configuration

### System properties (recommended for development)

```bash
java \
  -Dhsm.lib.path=/opt/homebrew/lib/softhsm/libsofthsm2.so \
  -Dhsm.slot.pin=1234 \
  -jar target/hsm-integration-*.jar
```

### Environment variables (recommended for CI/CD and containers)

```bash
export HSM_LIB_PATH=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
export HSM_SLOT_PIN=1234
java -jar target/hsm-integration-*.jar
```

### `hsm.properties` (classpath file)

Create `src/main/resources/hsm.properties`:

```properties
hsm.lib.path=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
hsm.slot.pin=1234
hsm.slot.index=0
hsm.provider.name=SoftHSM2
```

This file is bundled into the jar and serves as project-wide defaults. System properties and env vars still take precedence.

### Programmatic configuration

```java
// Explicit: bypass the auto-resolution chain entirely
HsmConfig config = HsmConfig.of(
    "/path/to/libsofthsm2.so",
    "1234",
    0
);

HsmManager hsm = HsmManager.open(config);
```

---

## Multiple tokens / slots

When multiple SoftHSM2 tokens are initialised, each occupies a distinct slot. Use `softhsm2-util --show-slots` to find the slot index:

```
Slot 0
    Token info: Label: TokenA
Slot 1
    Token info: Label: TokenB
```

To target `TokenB`:

```bash
java -Dhsm.slot.index=1 -Dhsm.slot.pin=<pinB> -jar hsm-integration-*.jar
```

---

## Logging

Logging uses SLF4J + Logback. The default configuration (`src/main/resources/logback.xml`) writes `DEBUG`-level messages from `com.example.hsm` to the console and a rolling file under `logs/`.

To reduce verbosity:

```xml
<!-- logback.xml -->
<logger name="com.example.hsm" level="WARN"/>
```

To enable all PKCS#11 tracing (very verbose – for troubleshooting only):

```xml
<logger name="sun.security.pkcs11" level="DEBUG"/>
```

---

## Connecting to a physical HSM

The only change needed to connect to a real HSM (Luna, Thales, etc.) is to replace the library path with the vendor-supplied PKCS#11 library:

```bash
java \
  -Dhsm.lib.path=/usr/lib/libCryptoki2_64.so \
  -Dhsm.slot.pin=<partition-pin> \
  -Dhsm.slot.index=<slot> \
  -jar hsm-integration-*.jar
```

All cryptographic code is provider-agnostic and will work without modification.
