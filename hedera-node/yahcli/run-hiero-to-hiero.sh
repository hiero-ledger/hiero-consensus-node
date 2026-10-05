#!/usr/bin/env bash
#
# run-clpr-end-to-end.sh
#
# Runs the full CLPR cross-ledger setup against two local Hiero networks and
# finishes by calling alice's PingPong.serve() to kick off a volley toward
# bob's PingPong. Captures contract ids and identity fields from yahcli
# output so re-runs don't need any hand-editing.
#
# PingPong plays three roles per network: connector contract (registered via
# `complete-connector`), wrapper that invokes the CLPR precompile (via the
# `serve()` method), and CLPR application (via onClprMessage/onClprResponse).
# Deploy PingPong on each network first via ./setup-clpr-ping-pong.sh, then
# pass the resulting ids in as PINGPONG_A / PINGPONG_B.
#
# Both `complete-channel` calls target the fixed verifier contract
# ${VERIFIER_CONTRACT} (default 0.0.366). Override via env var if needed.
#
# Required env vars:
#   PINGPONG_A   shard.realm.num — PingPong contract on network A
#   PINGPONG_B   shard.realm.num — PingPong contract on network B
#
# Usage:
#   PINGPONG_A=0.0.<A>  PINGPONG_B=0.0.<B>  ./run-clpr-end-to-end.sh
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
NET_A="${NET_A:-alice}"
NET_B="${NET_B:-bob}"
PAYER="${PAYER:-2}"

CONFIG_A="${CONFIG_A:-${SCRIPT_DIR}/alice-config.json}"
CONFIG_B="${CONFIG_B:-${SCRIPT_DIR}/bob-config.json}"

VERIFIER_CONTRACT="${VERIFIER_CONTRACT:-0.0.366}"

# Required: PingPong contract ids (per-deploy, no defaults).
: "${PINGPONG_A:?must be set to PingPong contract id on ${NET_A:-network A} (e.g. 0.0.1004)}"
: "${PINGPONG_B:?must be set to PingPong contract id on ${NET_B:-network B} (e.g. 0.0.1004)}"

LOCKED_STAKE="${LOCKED_STAKE:-100000000}"
# Hex of "Hello world" — the payload alice sends to bob's PingPong.
MESSAGE_DATA="${MESSAGE_DATA:-48656c6c6f20776f726c64}"

PRIME_RECIPIENT="${PRIME_RECIPIENT:-0.0.3}"
# Bumped to 1 billion hbar (1e9). Account 0.0.3 is the node payer for every
# internally-submitted ClprSubmitBundle the inbound sync pipeline produces; each
# bundle dispatch triggers a synthetic ContractCall to the verifier and burns
# the gas requirement from this account. At ~7 sync ticks/sec the original 100k
# hbar prime drained in ~25 min. 1B hbar lasts the foreseeable future of a
# local dev session. Account 0.0.2 (treasury) is seeded with 50 billion hbar
# at genesis so it can comfortably afford this transfer.
PRIME_AMOUNT="${PRIME_AMOUNT:-1000000000}"

CHANNEL_FILE="${SCRIPT_DIR}/channel-identity.json"
CONNECTOR_FILE="${SCRIPT_DIR}/connector-identity.json"
PROOF_A="${SCRIPT_DIR}/alice-proof.bin"
PROOF_B="${SCRIPT_DIR}/bob-proof.bin"
PEER_CONFIG_A="${SCRIPT_DIR}/alice-observed-config.json"
PEER_CONFIG_B="${SCRIPT_DIR}/bob-observed-config.json"
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

# Read a top-level string field from a flat JSON file.
read_json_field() {
    local file="$1" field="$2"
    if command -v jq >/dev/null 2>&1; then
        jq -r ".${field}" "${file}"
    else
        sed -nE 's/.*"'"${field}"'"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "${file}" | head -1
    fi
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
    # Dynamic-data offsets, measured from start of args block (after selector).
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

# Extract a 0.0.X contract id from yahcli SUCCESS output.
capture_contract_id() {
    local logfile="$1"
    grep -oE 'created contract 0\.[0-9]+\.[0-9]+' "${logfile}" \
        | head -1 | awk '{print $NF}'
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
[[ -f "${CONFIG_A}" ]]               || die "missing ${CONFIG_A}"
[[ -f "${CONFIG_B}" ]]               || die "missing ${CONFIG_B}"
echo "  JAVA_HOME         : ${JAVA_HOME:-(system default)}"
echo "  NET_A             : ${NET_A}"
echo "  NET_B             : ${NET_B}"
echo "  VERIFIER_CONTRACT : ${VERIFIER_CONTRACT}"
echo "  PINGPONG_A        : ${PINGPONG_A}"
echo "  PINGPONG_B        : ${PINGPONG_B}"
echo "  MESSAGE_DATA      : ${MESSAGE_DATA}"
echo "  per-step logs     : ${LOG_DIR}/"
ok "All prerequisites present"

# === [1/10] prime 0.0.3 with hbar on both networks =========================
header "[1/10] accounts send — prime ${PRIME_RECIPIENT} with ${PRIME_AMOUNT} hbar on both networks"
yh "accounts send ${PRIME_AMOUNT} hbar -> ${PRIME_RECIPIENT} on ${NET_A}" \
    "${LOG_DIR}/00a-prime-send.log" \
    -n "${NET_A}" -p "${PAYER}" \
    accounts send --to "${PRIME_RECIPIENT}" "${PRIME_AMOUNT}" -d hbar
yh "accounts send ${PRIME_AMOUNT} hbar -> ${PRIME_RECIPIENT} on ${NET_B}" \
    "${LOG_DIR}/00b-prime-send.log" \
    -n "${NET_B}" -p "${PAYER}" \
    accounts send --to "${PRIME_RECIPIENT}" "${PRIME_AMOUNT}" -d hbar

# === [2/10] channel identity ============================================
header "[2/10] generate-channel-identity (one bundle, shared by both networks)"
yh "generate-channel-identity" "${LOG_DIR}/01-gen-conn.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr generate-channel-identity --out "${CHANNEL_FILE}"
CHANNEL_COMMIT=$(read_json_field "${CHANNEL_FILE}" "ownershipCommitment")
[[ -n "${CHANNEL_COMMIT}" && "${CHANNEL_COMMIT}" != "null" ]] || die "no ownershipCommitment in ${CHANNEL_FILE}"
echo "  ownershipCommitment: ${CHANNEL_COMMIT}"

# === [3/10] push ledger configurations =====================================
header "[3/10] update-ledger-configuration on both networks"
yh "update-ledger-configuration on ${NET_B}" "${LOG_DIR}/02b-update-cfg.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr update-ledger-configuration --config-file "${CONFIG_B}"
yh "update-ledger-configuration on ${NET_A}" "${LOG_DIR}/02a-update-cfg.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr update-ledger-configuration --config-file "${CONFIG_A}"

# === [4/10] pull each network's state proof ================================
header "[4/10] get-ledger-configuration + state proof bytes"
yh "get-ledger-configuration on ${NET_B}" "${LOG_DIR}/03b-get-cfg.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr get-ledger-configuration --out "${PEER_CONFIG_B}" --proof-path "${PROOF_B}"
yh "get-ledger-configuration on ${NET_A}" "${LOG_DIR}/03a-get-cfg.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr get-ledger-configuration --out "${PEER_CONFIG_A}" --proof-path "${PROOF_A}"
[[ -s "${PROOF_A}" ]] || die "${PROOF_A} is empty — wait for the next signed snapshot and retry"
[[ -s "${PROOF_B}" ]] || die "${PROOF_B} is empty — wait for the next signed snapshot and retry"

# === [5/10] register-channel (commit phase) =============================
header "[5/10] register-channel on both networks"
yh "register-channel on ${NET_A}" "${LOG_DIR}/05a-register-conn.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr register-channel --commitment "${CHANNEL_COMMIT}"
yh "register-channel on ${NET_B}" "${LOG_DIR}/05b-register-conn.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr register-channel --commitment "${CHANNEL_COMMIT}"

# === [6/10] complete-channel (reveal phase) =============================
header "[6/10] complete-channel — both sides use verifier ${VERIFIER_CONTRACT}"
yh "complete-channel on ${NET_A} via verifier ${VERIFIER_CONTRACT}" \
    "${LOG_DIR}/06a-complete-conn.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr complete-channel --identity "${CHANNEL_FILE}" \
        --verifier-contract "${VERIFIER_CONTRACT}" --config-proof "${PROOF_B}"

yh "complete-channel on ${NET_B} via verifier ${VERIFIER_CONTRACT}" \
    "${LOG_DIR}/06b-complete-conn.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr complete-channel --identity "${CHANNEL_FILE}" \
        --verifier-contract "${VERIFIER_CONTRACT}" --config-proof "${PROOF_A}"

CHANNEL_ID=$(read_json_field "${CHANNEL_FILE}" "channelId")
[[ -n "${CHANNEL_ID}" && "${CHANNEL_ID}" != "null" ]] || die "no channelId in ${CHANNEL_FILE}"
echo "  CHANNEL_ID: ${CHANNEL_ID}"

# === [7/10] generate connector identity ====================================
header "[7/10] generate-connector-identity"
yh "generate-connector-identity" "${LOG_DIR}/07-gen-connector.log" \
    clpr generate-connector-identity \
        --channel-id "${CHANNEL_ID}" --out "${CONNECTOR_FILE}"
CONNECTOR_COMMIT=$(read_json_field "${CONNECTOR_FILE}" "commitment")
CONNECTOR_ID=$(read_json_field "${CONNECTOR_FILE}" "connectorId")
[[ -n "${CONNECTOR_COMMIT}" && "${CONNECTOR_COMMIT}" != "null" ]] \
    || die "no commitment in ${CONNECTOR_FILE}"
[[ -n "${CONNECTOR_ID}" && "${CONNECTOR_ID}" != "null" ]] \
    || die "no connectorId in ${CONNECTOR_FILE}"
echo "  connector commitment: ${CONNECTOR_COMMIT}"
echo "  CONNECTOR_ID:         ${CONNECTOR_ID}"

# === [8/10] register-connector =============================================
header "[8/10] register-connector on both networks"
yh "register-connector on ${NET_A}" "${LOG_DIR}/08a-register-connector.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr register-connector --commitment "${CONNECTOR_COMMIT}"
yh "register-connector on ${NET_B}" "${LOG_DIR}/08b-register-connector.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr register-connector --commitment "${CONNECTOR_COMMIT}"

# === [9/10] complete-connector (PingPong is the connector contract) ========
header "[9/10] complete-connector on both networks — connector contract is PingPong"
yh "complete-connector on ${NET_A} via PingPong ${PINGPONG_A}" \
    "${LOG_DIR}/10a-complete-connector.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr complete-connector --identity "${CONNECTOR_FILE}" \
        --connector-contract "${PINGPONG_A}" \
        --locked-stake "${LOCKED_STAKE}"
yh "complete-connector on ${NET_B} via PingPong ${PINGPONG_B}" \
    "${LOG_DIR}/10b-complete-connector.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr complete-connector --identity "${CONNECTOR_FILE}" \
        --connector-contract "${PINGPONG_B}" \
        --locked-stake "${LOCKED_STAKE}"

# === [10/10] kick off the volley — alice's PingPong.serve() ================
TARGET_APP_HEX="$(long_zero_addr "${PINGPONG_B}")"
CALLDATA="$(encode_serve_call "${CHANNEL_ID}" "${CONNECTOR_ID}" "${TARGET_APP_HEX}" "${MESSAGE_DATA}")"
header "[10/10] contracts call — PingPong.serve() on ${NET_A} targeting ${PINGPONG_B} (${TARGET_APP_HEX})"
yh "PingPong.serve() via ${PINGPONG_A} on ${NET_A}" "${LOG_DIR}/11-serve.log" \
    -n "${NET_A}" -p "${PAYER}" contracts call \
        --contract-id "${PINGPONG_A}" \
        --call-data "${CALLDATA}" \
        --gas 300000

header "Done"
cat <<EOF
  Channel ID       : ${CHANNEL_ID}
  Connector ID        : ${CONNECTOR_ID}
  Verifier contract   : ${VERIFIER_CONTRACT}    (both networks)
  PingPong contract   : ${PINGPONG_A} on ${NET_A} / ${PINGPONG_B} on ${NET_B}
                        (acts as connector contract, send wrapper, and CLPR application)
  Target application  : 0x${TARGET_APP_HEX}    (long-zero of ${PINGPONG_B})
  Message data        : ${MESSAGE_DATA}

  Per-step logs       : ${LOG_DIR}/
EOF
