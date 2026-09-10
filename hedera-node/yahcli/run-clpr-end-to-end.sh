#!/usr/bin/env bash
#
# run-clpr-end-to-end.sh
#
# Runs the full CLPR cross-ledger setup against two local Hiero networks and
# finishes by sending a message from network A through the ClprSendMessage
# wrapper contract on network A. Mirrors the 12-step flow that succeeded
# manually in the May-13 yahcli session; captures contract ids and identity
# fields from yahcli output so re-runs don't need any hand-editing.
#
# Required env vars (change every node restart):
#   LEDGER_ID_A  hex (no 0x) — genesis-rooted ledger id of network A
#   LEDGER_ID_B  hex (no 0x) — genesis-rooted ledger id of network B
#
# Usage:
#   LEDGER_ID_A=<hex>  LEDGER_ID_B=<hex>  ./run-clpr-end-to-end.sh
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
NET_A="${NET_A:-localhost}"
NET_B="${NET_B:-localhost2}"
PAYER="${PAYER:-2}"

CONFIG_A="${CONFIG_A:-${SCRIPT_DIR}/ledger-config.json}"
CONFIG_B="${CONFIG_B:-${SCRIPT_DIR}/ledger-config-b.json}"

PASS_THROUGH_AUTH="${PASS_THROUGH_AUTH:-${REPO_ROOT}/hedera-node/test-clients/src/main/resources/contract/contracts/PassThroughAuth/PassThroughAuth.bin}"
SEND_MESSAGE_BIN="${SEND_MESSAGE_BIN:-${REPO_ROOT}/hedera-node/test-clients/src/main/resources/contract/contracts/ClprSendMessage/ClprSendMessage.bin}"

LOCKED_STAKE="${LOCKED_STAKE:-100000000}"
TARGET_APPLICATION="${TARGET_APPLICATION:-deadbeefdeadbeefdeadbeefdeadbeefdeadbeef}"
MESSAGE_DATA="${MESSAGE_DATA:-48656c6c6f}"   # "Hello"

PRIME_RECIPIENT="${PRIME_RECIPIENT:-0.0.3}"
PRIME_AMOUNT="${PRIME_AMOUNT:-100000}"

CHANNEL_FILE="${SCRIPT_DIR}/channel.json"
CONNECTOR_FILE="${SCRIPT_DIR}/connector-identity.json"
PROOF_A="${SCRIPT_DIR}/peer-proof.bin"
PROOF_B="${SCRIPT_DIR}/peer-proof2.bin"
PEER_CONFIG_A="${SCRIPT_DIR}/peer-config.json"
PEER_CONFIG_B="${SCRIPT_DIR}/peer-config2.json"
LOG_DIR="${SCRIPT_DIR}/.run-logs"
mkdir -p "${LOG_DIR}"

# Required: ledger ids change every node genesis, so we don't ship defaults.
: "${LEDGER_ID_A:?must be set to network A's genesis ledger id (hex, no 0x prefix)}"
: "${LEDGER_ID_B:?must be set to network B's genesis ledger id (hex, no 0x prefix)}"

# Strip any accidental 0x prefix.
LEDGER_ID_A="${LEDGER_ID_A#0x}"
LEDGER_ID_B="${LEDGER_ID_B#0x}"

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

# Extract a 0.0.X contract id from yahcli SUCCESS output.
capture_contract_id() {
    local logfile="$1"
    grep -oE '(deployed ClprLedgerVerifier as|created contract) 0\.[0-9]+\.[0-9]+' "${logfile}" \
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
[[ -f "${PASS_THROUGH_AUTH}" ]]      || die "missing PassThroughAuth.bin at ${PASS_THROUGH_AUTH}"
[[ -f "${SEND_MESSAGE_BIN}" ]]       || die "missing ClprSendMessage.bin at ${SEND_MESSAGE_BIN}"
echo "  JAVA_HOME    : ${JAVA_HOME:-(system default)}"
echo "  NET_A        : ${NET_A}   LEDGER_ID_A=${LEDGER_ID_A}"
echo "  NET_B        : ${NET_B}   LEDGER_ID_B=${LEDGER_ID_B}"
echo "  per-step logs: ${LOG_DIR}/"
ok "All prerequisites present"

# === [1/13] prime 0.0.3 with hbar on both networks =========================
header "[1/13] accounts send — prime ${PRIME_RECIPIENT} with ${PRIME_AMOUNT} hbar on both networks"
yh "accounts send ${PRIME_AMOUNT} hbar -> ${PRIME_RECIPIENT} on ${NET_A}" \
    "${LOG_DIR}/00a-prime-send.log" \
    -n "${NET_A}" -p "${PAYER}" \
    accounts send --to "${PRIME_RECIPIENT}" "${PRIME_AMOUNT}" -d hbar
yh "accounts send ${PRIME_AMOUNT} hbar -> ${PRIME_RECIPIENT} on ${NET_B}" \
    "${LOG_DIR}/00b-prime-send.log" \
    -n "${NET_B}" -p "${PAYER}" \
    accounts send --to "${PRIME_RECIPIENT}" "${PRIME_AMOUNT}" -d hbar

# === [2/13] channel identity ============================================
header "[2/13] generate-channel-identity (one bundle, shared by both networks)"
yh "generate-channel-identity" "${LOG_DIR}/01-gen-conn.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr generate-channel-identity --out "${CHANNEL_FILE}"
CHANNEL_COMMIT=$(read_json_field "${CHANNEL_FILE}" "ownershipCommitment")
[[ -n "${CHANNEL_COMMIT}" && "${CHANNEL_COMMIT}" != "null" ]] || die "no ownershipCommitment in ${CHANNEL_FILE}"
echo "  ownershipCommitment: ${CHANNEL_COMMIT}"

# === [3/13] push ledger configurations =====================================
header "[3/13] update-ledger-configuration on both networks"
yh "update-ledger-configuration on ${NET_B}" "${LOG_DIR}/02b-update-cfg.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr update-ledger-configuration --config-file "${CONFIG_B}"
yh "update-ledger-configuration on ${NET_A}" "${LOG_DIR}/02a-update-cfg.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr update-ledger-configuration --config-file "${CONFIG_A}"

# === [4/13] pull each network's state proof ================================
header "[4/13] get-ledger-configuration + state proof bytes"
yh "get-ledger-configuration on ${NET_B}" "${LOG_DIR}/03b-get-cfg.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr get-ledger-configuration --out "${PEER_CONFIG_B}" --proof-path "${PROOF_B}"
yh "get-ledger-configuration on ${NET_A}" "${LOG_DIR}/03a-get-cfg.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr get-ledger-configuration --out "${PEER_CONFIG_A}" --proof-path "${PROOF_A}"
[[ -s "${PROOF_A}" ]] || die "${PROOF_A} is empty — wait for the next signed snapshot and retry"
[[ -s "${PROOF_B}" ]] || die "${PROOF_B} is empty — wait for the next signed snapshot and retry"

# === [5/13] deploy ClprLedgerVerifier on each side (pins the peer's ledger id)
header "[5/13] deploy-clpr-verifier (each network pins the OTHER's ledger id)"
yh "deploy-clpr-verifier on ${NET_A} pinning ${NET_B}" "${LOG_DIR}/04a-verifier.log" \
    -n "${NET_A}" -p "${PAYER}" contracts deploy-clpr-verifier \
        --ledger-id "${LEDGER_ID_B}" --gas 500000 --memo "demo verifier" --immutable
VERIFIER_A=$(capture_contract_id "${LOG_DIR}/04a-verifier.log")
[[ -n "${VERIFIER_A}" ]] || die "could not capture verifier contract id on ${NET_A}"

yh "deploy-clpr-verifier on ${NET_B} pinning ${NET_A}" "${LOG_DIR}/04b-verifier.log" \
    -n "${NET_B}" -p "${PAYER}" contracts deploy-clpr-verifier \
        --ledger-id "${LEDGER_ID_A}" --gas 500000 --memo "demo verifier" --immutable
VERIFIER_B=$(capture_contract_id "${LOG_DIR}/04b-verifier.log")
[[ -n "${VERIFIER_B}" ]] || die "could not capture verifier contract id on ${NET_B}"
echo "  VERIFIER_A=${VERIFIER_A}  VERIFIER_B=${VERIFIER_B}"

# === [6/13] register-channel (commit phase) =============================
header "[6/13] register-channel on both networks"
yh "register-channel on ${NET_A}" "${LOG_DIR}/05a-register-conn.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr register-channel --commitment "${CHANNEL_COMMIT}"
yh "register-channel on ${NET_B}" "${LOG_DIR}/05b-register-conn.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr register-channel --commitment "${CHANNEL_COMMIT}"

# === [7/13] complete-channel (reveal phase) =============================
header "[7/13] complete-channel — each side verifies the peer's proof"
yh "complete-channel on ${NET_A} via verifier ${VERIFIER_A}" \
    "${LOG_DIR}/06a-complete-conn.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr complete-channel --identity "${CHANNEL_FILE}" \
        --verifier-contract "${VERIFIER_A}" --config-proof "${PROOF_B}"

yh "complete-channel on ${NET_B} via verifier ${VERIFIER_B}" \
    "${LOG_DIR}/06b-complete-conn.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr complete-channel --identity "${CHANNEL_FILE}" \
        --verifier-contract "${VERIFIER_B}" --config-proof "${PROOF_A}"

CHANNEL_ID=$(read_json_field "${CHANNEL_FILE}" "channelId")
[[ -n "${CHANNEL_ID}" && "${CHANNEL_ID}" != "null" ]] || die "no channelId in ${CHANNEL_FILE}"
echo "  CHANNEL_ID: ${CHANNEL_ID}"

# === [8/13] generate connector identity ====================================
header "[8/13] generate-connector-identity"
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

# === [9/13] register-connector =============================================
header "[9/13] register-connector on both networks"
yh "register-connector on ${NET_A}" "${LOG_DIR}/08a-register-connector.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr register-connector --commitment "${CONNECTOR_COMMIT}"
yh "register-connector on ${NET_B}" "${LOG_DIR}/08b-register-connector.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr register-connector --commitment "${CONNECTOR_COMMIT}"

# === [10/13] deploy PassThroughAuth (becomes the connector contract) =======
header "[10/13] contracts create — PassThroughAuth on both networks"
yh "contracts create PassThroughAuth on ${NET_A}" "${LOG_DIR}/09a-create-pta.log" \
    -n "${NET_A}" -p "${PAYER}" contracts create \
        --init-code-file "${PASS_THROUGH_AUTH}" \
        --gas 500000 --memo "pass-through connector auth" --immutable
CONNECTOR_CONTRACT_A=$(capture_contract_id "${LOG_DIR}/09a-create-pta.log")
[[ -n "${CONNECTOR_CONTRACT_A}" ]] || die "could not capture connector-contract id on ${NET_A}"

yh "contracts create PassThroughAuth on ${NET_B}" "${LOG_DIR}/09b-create-pta.log" \
    -n "${NET_B}" -p "${PAYER}" contracts create \
        --init-code-file "${PASS_THROUGH_AUTH}" \
        --gas 500000 --memo "pass-through connector auth" --immutable
CONNECTOR_CONTRACT_B=$(capture_contract_id "${LOG_DIR}/09b-create-pta.log")
[[ -n "${CONNECTOR_CONTRACT_B}" ]] || die "could not capture connector-contract id on ${NET_B}"
echo "  CONNECTOR_CONTRACT_A=${CONNECTOR_CONTRACT_A}  CONNECTOR_CONTRACT_B=${CONNECTOR_CONTRACT_B}"

# === [11/13] complete-connector ============================================
header "[11/13] complete-connector on both networks"
yh "complete-connector on ${NET_A}" "${LOG_DIR}/10a-complete-connector.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr complete-connector --identity "${CONNECTOR_FILE}" \
        --connector-contract "${CONNECTOR_CONTRACT_A}" \
        --locked-stake "${LOCKED_STAKE}"
yh "complete-connector on ${NET_B}" "${LOG_DIR}/10b-complete-connector.log" \
    -n "${NET_B}" -p "${PAYER}" \
    clpr complete-connector --identity "${CONNECTOR_FILE}" \
        --connector-contract "${CONNECTOR_CONTRACT_B}" \
        --locked-stake "${LOCKED_STAKE}"

# === [12/13] deploy ClprSendMessage wrapper on the sender ==================
header "[12/13] contracts create — ClprSendMessage wrapper on ${NET_A}"
yh "contracts create ClprSendMessage on ${NET_A}" "${LOG_DIR}/11-create-send.log" \
    -n "${NET_A}" -p "${PAYER}" contracts create \
        --init-code-file "${SEND_MESSAGE_BIN}" \
        --gas 500000 --memo "clpr send-message wrapper" --immutable
SEND_CONTRACT=$(capture_contract_id "${LOG_DIR}/11-create-send.log")
[[ -n "${SEND_CONTRACT}" ]] || die "could not capture send-message contract id"
echo "  SEND_CONTRACT=${SEND_CONTRACT}"

# === [13/13] send the cross-ledger message =================================
header "[13/13] send-message"
yh "send-message via ${SEND_CONTRACT} on ${NET_A}" "${LOG_DIR}/12-send-message.log" \
    -n "${NET_A}" -p "${PAYER}" \
    clpr send-message \
        --contract "${SEND_CONTRACT}" \
        --channel-id "${CHANNEL_ID}" \
        --connector-id "${CONNECTOR_ID}" \
        --target-application "${TARGET_APPLICATION}" \
        --message-data "${MESSAGE_DATA}"

header "Done"
cat <<EOF
  Channel ID       : ${CHANNEL_ID}
  Connector ID        : ${CONNECTOR_ID}
  Verifier (${NET_A})  : ${VERIFIER_A}    (pins ${NET_B} ledger ${LEDGER_ID_B})
  Verifier (${NET_B}) : ${VERIFIER_B}    (pins ${NET_A} ledger ${LEDGER_ID_A})
  Connector contract  : ${CONNECTOR_CONTRACT_A} on ${NET_A} / ${CONNECTOR_CONTRACT_B} on ${NET_B}
  Send contract       : ${SEND_CONTRACT} on ${NET_A}

  Per-step logs       : ${LOG_DIR}/
EOF
