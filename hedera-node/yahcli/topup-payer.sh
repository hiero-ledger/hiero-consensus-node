#!/usr/bin/env bash
#
# topup-payer.sh — refill the node-payer account (0.0.3 by default) from the
# genesis treasury (0.0.2). Use whenever ClprSubmitBundle starts rejecting with
# INSUFFICIENT_PAYER_BALANCE — the inbound sync pipeline burns gas from this
# account on every verifier dispatch, and a long-running relay drains it fast.
#
# Usage:
#   topup-payer.sh                                    # 1B hbar to 0.0.3 on localhost
#   AMOUNT=500000000 topup-payer.sh                   # 500M hbar
#   NET=localhost2 RECIPIENT=0.0.3 topup-payer.sh     # other network / account
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

DEFAULT_JDK25="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
if [[ -x "${DEFAULT_JDK25}/bin/java" ]]; then
    export JAVA_HOME="${JAVA_HOME:-${DEFAULT_JDK25}}"
fi

NET="${NET:-localhost}"
PAYER="${PAYER:-2}"
RECIPIENT="${RECIPIENT:-0.0.3}"
AMOUNT="${AMOUNT:-1000000000}"   # 1 billion hbar

echo "Topping up ${RECIPIENT} with ${AMOUNT} hbar on ${NET} (paid by 0.0.${PAYER})..."
./yahcli -n "${NET}" -p "${PAYER}" accounts send --to "${RECIPIENT}" "${AMOUNT}" -d hbar
echo ""
echo "Post-topup balance:"
./yahcli -n "${NET}" -p "${PAYER}" accounts balance "${RECIPIENT}" 2>&1 | grep -E "^\.i\..*\|" | tail -1
