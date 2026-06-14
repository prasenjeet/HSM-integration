# Docker & CI/CD

This page covers the Docker image bundled with the project and how to wire HSM integration tests into a CI/CD pipeline.

---

## Docker

The `docker/softhsm2/` directory provides a Docker image that:
- Installs SoftHSM2 inside an `eclipse-temurin:17-jdk-jammy` base image.
- Initialises a token at **build time** so the container is ready to run the demo immediately.
- Accepts PIN, token label, and library path via environment variables.

### Build

```bash
# Build the fat jar first
mvn package -DskipTests

# Copy the jar into the docker directory
cp target/hsm-integration-*.jar docker/softhsm2/

# Build the image
cd docker/softhsm2
docker build -t hsm-integration-demo .
```

Or in one line from the project root:

```bash
mvn package -DskipTests && \
cp target/hsm-integration-*.jar docker/softhsm2/ && \
docker build -t hsm-integration-demo docker/softhsm2/
```

### Run the demo

```bash
docker run --rm hsm-integration-demo
```

### Override PIN and token label

```bash
docker run --rm \
  -e HSM_SLOT_PIN=s3cr3t \
  -e HSM_TOKEN_LABEL=ProdToken \
  hsm-integration-demo
```

### Run integration tests inside Docker

```bash
docker run --rm hsm-integration-demo \
  mvn verify -Pintegration -Dhsm.slot.pin=s3cr3t
```

### Dockerfile overview

```dockerfile
FROM eclipse-temurin:17-jdk-jammy

RUN apt-get install -y softhsm2

ENV SOFTHSM2_CONF=/etc/softhsm2.conf
ENV HSM_LIB_PATH=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
ENV HSM_SLOT_PIN=1234

COPY setup.sh /app/setup.sh
RUN /app/setup.sh           # initialises the token at build time

COPY target/hsm-integration-*.jar /app/hsm-integration.jar

ENTRYPOINT ["java", "-Dhsm.slot.pin=1234", "-jar", "/app/hsm-integration.jar"]
```

---

## GitHub Actions

### Unit-test workflow (no HSM)

```yaml
# .github/workflows/unit-tests.yml
name: Unit Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Run unit tests
        run: mvn test
```

### Full workflow with integration tests

```yaml
# .github/workflows/ci.yml
name: CI

on: [push, pull_request]

jobs:
  unit-tests:
    name: Unit Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven
      - run: mvn test

  integration-tests:
    name: Integration Tests (SoftHSM2)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Install SoftHSM2
        run: sudo apt-get install -y softhsm2

      - name: Initialise HSM token
        run: ./scripts/init-softhsm.sh
        env:
          HSM_TOKEN_LABEL: CIToken
          HSM_SLOT_PIN: ${{ secrets.HSM_SLOT_PIN }}

      - name: Build
        run: mvn package -DskipTests

      - name: Run integration tests
        run: |
          mvn verify -Pintegration \
            -Dhsm.slot.pin=${{ secrets.HSM_SLOT_PIN }}
        env:
          HSM_LIB_PATH: /usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
```

Set `HSM_SLOT_PIN` in **Settings → Secrets → Actions** on your GitHub repository.

---

## GitLab CI

```yaml
# .gitlab-ci.yml
stages:
  - test

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"
  HSM_LIB_PATH: "/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so"

unit-tests:
  stage: test
  image: eclipse-temurin:17-jdk-jammy
  script:
    - mvn test
  cache:
    paths:
      - .m2/

integration-tests:
  stage: test
  image: eclipse-temurin:17-jdk-jammy
  before_script:
    - apt-get update && apt-get install -y softhsm2
    - HSM_SLOT_PIN=$HSM_PIN ./scripts/init-softhsm.sh
  script:
    - mvn package -DskipTests
    - mvn verify -Pintegration -Dhsm.slot.pin=$HSM_PIN
  variables:
    HSM_PIN: $HSM_SLOT_PIN   # set in GitLab CI/CD variables
  cache:
    paths:
      - .m2/
```

---

## Docker Compose (local development)

```yaml
# docker-compose.yml
services:
  hsm-demo:
    build:
      context: .
      dockerfile: docker/softhsm2/Dockerfile
    environment:
      HSM_SLOT_PIN: "1234"
    volumes:
      # Persist the token directory so keys survive container restarts
      - softhsm-data:/var/lib/softhsm/tokens

volumes:
  softhsm-data:
```

```bash
docker compose up --build
```

---

## Production considerations

When moving from SoftHSM2 to a physical HSM in production:

1. **Replace the library path** – set `HSM_LIB_PATH` (or `-Dhsm.lib.path`) to the vendor's PKCS#11 library.
2. **Manage the PIN as a secret** – never hard-code pins; use your platform's secret management (AWS Secrets Manager, HashiCorp Vault, Kubernetes secrets, etc.).
3. **HA / clustering** – most vendor HSMs support clustering; SoftHSM2 does not. For HA with SoftHSM2, replicate the token directory and mount it read-only on worker nodes (signing only).
4. **Audit logging** – physical HSMs emit tamper-evident audit logs via syslog; configure the vendor driver accordingly.
5. **Key backup** – physical HSMs support encrypted key export to a backup HSM via vendor-specific mechanisms (not via `CKA_EXTRACTABLE`).
