#!/usr/bin/env bash
# Initialises a SoftHSM2 token.
# Safe to run multiple times; skips if the token already exists.

set -euo pipefail

TOKEN_LABEL="${HSM_TOKEN_LABEL:-JavaHSMDemo}"
SO_PIN="${HSM_SO_PIN:-12345678}"
USER_PIN="${HSM_SLOT_PIN:-1234}"

SOFTHSM2_CONF="${SOFTHSM2_CONF:-/etc/softhsm2.conf}"
export SOFTHSM2_CONF

if softhsm2-util --show-slots 2>/dev/null | grep -q "Token Label: *${TOKEN_LABEL}"; then
    echo "Token '${TOKEN_LABEL}' already exists – skipping initialisation."
    exit 0
fi

echo "Initialising SoftHSM2 token '${TOKEN_LABEL}' …"
softhsm2-util --init-token --free \
    --label  "${TOKEN_LABEL}" \
    --so-pin "${SO_PIN}" \
    --pin    "${USER_PIN}"

echo "Token initialised successfully."
softhsm2-util --show-slots
