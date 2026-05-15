FROM eclipse-temurin:17-jdk-jammy

LABEL description="SoftHSM2 + Java 17 for HSM integration testing"

# Install SoftHSM2
RUN apt-get update && \
    apt-get install -y --no-install-recommends softhsm2 && \
    rm -rf /var/lib/apt/lists/*

# Create SoftHSM2 token directory
RUN mkdir -p /var/lib/softhsm/tokens && \
    chmod 777 /var/lib/softhsm/tokens

# Write SoftHSM2 config
RUN echo "directories.tokendir = /var/lib/softhsm/tokens/" > /etc/softhsm2.conf && \
    echo "objectstore.backend = file"                       >> /etc/softhsm2.conf && \
    echo "log.level = ERROR"                               >> /etc/softhsm2.conf

ENV SOFTHSM2_CONF=/etc/softhsm2.conf
ENV HSM_LIB_PATH=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
ENV HSM_SLOT_PIN=1234
ENV HSM_SLOT_INDEX=0

WORKDIR /app

COPY setup.sh /app/setup.sh
RUN chmod +x /app/setup.sh

# Initialise the SoftHSM2 token at build time
RUN /app/setup.sh

COPY target/hsm-integration-*.jar /app/hsm-integration.jar

ENTRYPOINT ["java", \
  "-Dhsm.lib.path=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so", \
  "-Dhsm.slot.pin=1234", \
  "-Dhsm.slot.index=0", \
  "-jar", "/app/hsm-integration.jar"]
