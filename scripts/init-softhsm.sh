#!/usr/bin/env bash
# Initialises a local SoftHSM2 token for development / integration testing.
#
# Usage:
#   ./scripts/init-softhsm.sh
#
# Environment overrides:
#   HSM_TOKEN_LABEL   (default: JavaHSMDemo)
#   HSM_SO_PIN        (default: 12345678)
#   HSM_SLOT_PIN      (default: 1234)
#   SOFTHSM2_CONF     (default: /etc/softhsm2.conf or ~/.config/softhsm2/softhsm2.conf)

set -euo pipefail

TOKEN_LABEL="${HSM_TOKEN_LABEL:-JavaHSMDemo}"
SO_PIN="${HSM_SO_PIN:-12345678}"
USER_PIN="${HSM_SLOT_PIN:-1234}"

# ---- locate softhsm2-util -----------------------------------------------
if ! command -v softhsm2-util &>/dev/null; then
    echo "ERROR: softhsm2-util not found."
    echo "Install SoftHSM2:"
    echo "  Ubuntu/Debian:  sudo apt-get install softhsm2"
    echo "  macOS (brew):   brew install softhsm"
    exit 1
fi

# ---- locate / create softhsm2.conf --------------------------------------
if [[ -z "${SOFTHSM2_CONF:-}" ]]; then
    # Try well-known locations
    for candidate in \
        /etc/softhsm2.conf \
        /usr/local/etc/softhsm2.conf \
        "${HOME}/.config/softhsm2/softhsm2.conf" \
        "${HOME}/Library/Preferences/softhsm2.conf"
    do
        if [[ -f "${candidate}" ]]; then
            export SOFTHSM2_CONF="${candidate}"
            break
        fi
    done
fi

if [[ -z "${SOFTHSM2_CONF:-}" ]]; then
    # Create a user-local config
    USER_CONF="${HOME}/.config/softhsm2/softhsm2.conf"
    TOKEN_DIR="${HOME}/.local/share/softhsm/tokens"
    mkdir -p "$(dirname "${USER_CONF}")" "${TOKEN_DIR}"
    cat > "${USER_CONF}" <<EOF
directories.tokendir = ${TOKEN_DIR}
objectstore.backend = file
log.level = ERROR
EOF
    export SOFTHSM2_CONF="${USER_CONF}"
    echo "Created SoftHSM2 config at ${USER_CONF}"
fi

echo "Using SOFTHSM2_CONF=${SOFTHSM2_CONF}"

# ---- skip if token already exists ---------------------------------------
if softhsm2-util --show-slots 2>/dev/null | grep -q "Token Label:.*${TOKEN_LABEL}"; then
    echo "Token '${TOKEN_LABEL}' already exists. Nothing to do."
    echo ""
    echo "To reset:  softhsm2-util --delete-token --token '${TOKEN_LABEL}'"
    exit 0
fi

# ---- initialise the token -----------------------------------------------
echo "Initialising SoftHSM2 token '${TOKEN_LABEL}' …"
softhsm2-util --init-token --free \
    --label  "${TOKEN_LABEL}" \
    --so-pin "${SO_PIN}" \
    --pin    "${USER_PIN}"

echo ""
echo "Token initialised successfully!"
echo ""
echo "Run the demo:"
echo "  mvn package -DskipTests"
echo "  java -Dhsm.slot.pin=${USER_PIN} -jar target/hsm-integration-*.jar"
echo ""
echo "Run integration tests:"
echo "  mvn verify -Pintegration -Dhsm.slot.pin=${USER_PIN}"
