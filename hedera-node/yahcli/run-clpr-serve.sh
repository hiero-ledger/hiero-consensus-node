#!/usr/bin/env bash
#
# run-clpr-serve.sh
#
# Invokes PingPong.serve(bytes32,bytes32,bytes,bytes) on alice's or bob's
# PingPong contract, kicking off a single cross-ledger message via the
# CLPR precompile at 0x16e.
#
# Required env vars:
#   PARTY            "alice" or "bob" — which network to invoke against
#   CHANNEL_ID    hex (no 0x) — 32-byte channel id
#   CONNECTOR_ID     hex (no 0x) — 32-byte connector id
#   CONTRACT_ID      shard.realm.num — PingPong on the PARTY network
#                                       (the contract whose serve() is called)
#
# Optional env vars:
#   TARGET_CONTRACT_ID  shard.realm.num — PingPong on the peer network (default = CONTRACT_ID,
#                                          assuming both networks deployed at the same num)
#   MESSAGE             string — application payload (default "Hello world")
#   PAYER               yahcli payer (default 2)
#   GAS                 contract-call gas limit (default 300000)
#
# Usage:
#   PARTY=alice CHANNEL_ID=<hex> CONNECTOR_ID=<hex> CONTRACT_ID=0.0.1004 \
#       ./run-clpr-serve.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# yahcli.jar is built with class file 69.0 (JDK 25). Force JAVA_HOME if it
# points at an older JDK — the launcher (./yahcli) honours $JAVA_HOME.
DEFAULT_JDK25="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
if [[ -x "${DEFAULT_JDK25}/bin/java" ]]; then
    export JAVA_HOME="${JAVA_HOME:-${DEFAULT_JDK25}}"
fi

# === Configuration =========================================================
PAYER="${PAYER:-2}"
GAS="${GAS:-300000}"

# Required: party + ids
: "${PARTY:?must be set to 'alice' or 'bob'}"
: "${CHANNEL_ID:?must be set to hex channel id (no 0x)}"
: "${CONNECTOR_ID:?must be set to hex connector id (no 0x)}"
: "${CONTRACT_ID:?must be set to PingPong contract id on PARTY network (shard.realm.num)}"

case "${PARTY}" in
    alice|bob) ;;
    *) printf "PARTY must be 'alice' or 'bob' (got '%s')\n" "${PARTY}" >&2; exit 1;;
esac

# Strip any accidental 0x prefix on hex inputs.
CHANNEL_ID="${CHANNEL_ID#0x}"
CONNECTOR_ID="${CONNECTOR_ID#0x}"

TARGET_CONTRACT_ID="${TARGET_CONTRACT_ID:-${CONTRACT_ID}}"
MESSAGE="${MESSAGE:-Hello world}"

LOG_DIR="${SCRIPT_DIR}/.run-logs"
mkdir -p "${LOG_DIR}"

# === Helpers ===============================================================
YELLOW=$'\033[1;33m'
CYAN=$'\033[1;36m'
GREEN=$'\033[1;32m'
RED=$'\033[1;31m'
MAGENTA=$'\033[1;35m'
RESET=$'\033[0m'

header() { printf "\n${CYAN}==> %s${RESET}\n" "$*"; }
step()   { printf "${YELLOW}  -- %s${RESET}\n" "$*"; }
ok()     { printf "${GREEN}     OK${RESET} %s\n" "$*"; }
die()    { printf "${RED}     FAIL${RESET} %s\n" "$*" >&2; exit 1; }

# Quote each argument so the displayed command is copy-pasteable.
shellquote() {
    local out=""
    for arg in "$@"; do
        if [[ "${arg}" =~ ^[A-Za-z0-9_./:=@%+,-]+$ ]]; then
            out+=" ${arg}"
        else
            out+=" '${arg//\'/\'\\\'\'}'"
        fi
    done
    printf '%s' "${out# }"
}

# Long-zero EVM address (20-byte hex, no 0x) from shard.realm.num.
# Assumes shard=realm=0, which holds for local Hedera networks.
long_zero_addr() {
    local num="${1##*.}"
    printf '%040x' "${num}"
}

# Pad hex $1 on the right with '0' chars until length is a multiple of 64.
pad_right_64() {
    local h="$1"
    local rem=$(( ${#h} % 64 ))
    if (( rem != 0 )); then
        h+="$(printf '%0*d' $((64 - rem)) 0)"
    fi
    printf '%s' "$h"
}

# Print non-negative integer $1 as a 64-hex-char (32-byte BE) word.
u256() { printf '%064x' "$1"; }

# ABI-encode calldata for PingPong.serve(bytes32, bytes32, bytes, bytes).
# Inputs: hex (no 0x). channelId and connectorId must each be exactly 64 hex chars (32 bytes).
# Selector: keccak256("serve(bytes32,bytes32,bytes,bytes)")[:4] = 0x662cc5fb
encode_serve_call() {
    local conn="${1#0x}" cid="${2#0x}" tgt="${3#0x}" msg="${4#0x}"
    [[ ${#conn} -eq 64 ]] || die "channelId must be 32 bytes (got ${#conn} hex chars)"
    [[ ${#cid} -eq 64 ]]  || die "connectorId must be 32 bytes (got ${#cid} hex chars)"
    local len_tgt=$(( ${#tgt} / 2 ))
    local len_msg=$(( ${#msg} / 2 ))
    # Head = 4 words (channelId, connectorId, off_tgt, off_msg) = 128 bytes.
    local off_tgt=128
    local off_msg=$(( off_tgt + 32 + ((len_tgt + 31) / 32) * 32 ))
    printf '662cc5fb%s%s%s%s%s%s%s%s' \
        "${conn}" \
        "${cid}" \
        "$(u256 ${off_tgt})" \
        "$(u256 ${off_msg})" \
        "$(u256 ${len_tgt})" "$(pad_right_64 "${tgt}")" \
        "$(u256 ${len_msg})" "$(pad_right_64 "${msg}")"
}

# Invoke yahcli; tee output to a log; fail on non-zero exit or any "FAILED" line.
yh() {
    local label="$1"; local logfile="$2"; shift 2
    step "${label}"
    printf "${MAGENTA}     \$ ./yahcli %s${RESET}\n" "$(shellquote "$@")"
    set +e
    ./yahcli "$@" 2>&1 | tee "${logfile}"
    local rc=${PIPESTATUS[0]}
    set -e
    if [[ ${rc} -ne 0 ]]; then
        die "${label} — yahcli exited ${rc}"
    fi
    if grep -qE '^\.!\. FAILED|^FAILED ' "${logfile}"; then
        die "${label} — yahcli reported FAILED"
    fi
    ok "${label}"
}

# === Preflight =============================================================
header "Preflight"
[[ -x "${SCRIPT_DIR}/yahcli" ]]     || die "missing ./yahcli launcher"
[[ -f "${SCRIPT_DIR}/yahcli.jar" ]]  || die "missing yahcli.jar — run: (cd ${REPO_ROOT} && ./gradlew :yahcli:copyYahCli)"

# Hex-encode the message string (UTF-8 bytes → lower-case hex, no separators, no newline).
MESSAGE_HEX="$(printf '%s' "${MESSAGE}" | xxd -p -c 1000000 | tr -d '\n')"
TARGET_HEX="$(long_zero_addr "${TARGET_CONTRACT_ID}")"
CALLDATA="$(encode_serve_call "${CHANNEL_ID}" "${CONNECTOR_ID}" "${TARGET_HEX}" "${MESSAGE_HEX}")"

echo "  JAVA_HOME           : ${JAVA_HOME:-(system default)}"
echo "  PARTY               : ${PARTY}"
echo "  CONTRACT_ID         : ${CONTRACT_ID}     (PingPong on ${PARTY})"
echo "  TARGET_CONTRACT_ID  : ${TARGET_CONTRACT_ID}     (peer PingPong; long-zero 0x${TARGET_HEX})"
echo "  CHANNEL_ID       : ${CHANNEL_ID}"
echo "  CONNECTOR_ID        : ${CONNECTOR_ID}"
echo "  MESSAGE             : ${MESSAGE}     (hex: ${MESSAGE_HEX})"
echo "  GAS                 : ${GAS}"
echo "  per-step logs       : ${LOG_DIR}/"
ok "All prerequisites present"

# === [1/1] serve() =========================================================
header "[1/1] contracts call — PingPong.serve() via ${CONTRACT_ID} on ${PARTY}"
yh "PingPong.serve() via ${CONTRACT_ID} on ${PARTY}" "${LOG_DIR}/serve-${PARTY}.log" \
    -n "${PARTY}" -p "${PAYER}" contracts call \
        --contract-id "${CONTRACT_ID}" \
        --call-data "${CALLDATA}" \
        --gas "${GAS}"

header "Done"
cat <<EOF
  Party               : ${PARTY}
  Source contract     : ${CONTRACT_ID}
  Target application  : 0x${TARGET_HEX}    (long-zero of ${TARGET_CONTRACT_ID})
  Message             : ${MESSAGE}    (hex: ${MESSAGE_HEX})

  Log                 : ${LOG_DIR}/serve-${PARTY}.log
EOF
