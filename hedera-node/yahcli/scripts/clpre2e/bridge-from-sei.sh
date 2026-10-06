#!/usr/bin/env bash
#
# Bridge a local Sei CLPR channel into Hiero using the native Sei verifier.
#
# Assumes:
#   1. Hiero local node is running.
#   2. Sei local node is running.
#   3. deploy-sei-clpr.sh has deployed CLPR and written clpr-smart-contracts/deployments/<chainId>/.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
YAHCLI_DIR="$(cd "${YAHCLI_DIR:-${SCRIPT_DIR}/../..}" && pwd)"
TOOLS_DIR="${TOOLS_DIR:-${REPO_ROOT}/hedera-node/tools}"
SEI_EVM_RPC="${SEI_EVM_RPC:-http://127.0.0.1:8545}"
SEI_TM_RPC="${SEI_TM_RPC:-http://127.0.0.1:26657}"
ANVIL_DEV_KEY_0="0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"

default_yahcli_network() {
    local config="${YAHCLI_DIR}/config.yml"
    if [[ -f "${config}" ]]; then
        if grep -qE '^[[:space:]]+localhost:' "${config}"; then
            echo "localhost"
            return
        fi
        if grep -qE '^[[:space:]]+alice:' "${config}"; then
            echo "alice"
            return
        fi
    fi
    echo "localhost"
}

NET="${NET:-$(default_yahcli_network)}"
PAYER="${PAYER:-2}"
LOCKED_STAKE="${LOCKED_STAKE:-100000000}"
PROTOCOL_VERSION="${PROTOCOL_VERSION:-1}"
VERIFIER_CONTRACT="${VERIFIER_CONTRACT:-0.0.368}"
PEER_PK_HEX="${PEER_PK:-0x00000000000000000000000000000000000000000000000000000000000a11ce}"
CONNECTOR_PK_HEX="${CONNECTOR_PK:-0x0000000000000000000000000000000000000000000000000000000000c044ec}"
PASS_THROUGH_AUTH="${PASS_THROUGH_AUTH:-${REPO_ROOT}/hedera-node/test-clients/src/main/resources/contract/contracts/PassThroughAuth/PassThroughAuth.bin}"
CONFIG_LOCAL="${CONFIG_LOCAL:-${YAHCLI_DIR}/ledger-config.json}"
CHANNEL_FILE="${SCRIPT_DIR}/.bridge/sei-channel.json"
CONNECTOR_FILE="${SCRIPT_DIR}/.bridge/sei-connector.json"
SEI_CONFIG_JSON="${SCRIPT_DIR}/.bridge/sei-ledger-config.json"
SEI_CONFIG_PAYLOAD="${SCRIPT_DIR}/.bridge/sei-config-payload.bin"
LOG_DIR="${SCRIPT_DIR}/.run-logs-sei-bridge"

DEFAULT_JDK25="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
if [[ -x "${DEFAULT_JDK25}/bin/java" ]]; then
    export JAVA_HOME="${JAVA_HOME:-${DEFAULT_JDK25}}"
fi

YELLOW=$'\033[1;33m'
CYAN=$'\033[1;36m'
GREEN=$'\033[1;32m'
RED=$'\033[1;31m'
MAGENTA=$'\033[1;35m'
RESET=$'\033[0m'

header() { printf "\n${CYAN}==> %s${RESET}\n" "$*"; }
step() { printf "${YELLOW}  -- %s${RESET}\n" "$*"; }
ok() { printf "${GREEN}     OK${RESET} %s\n" "$*"; }
note() { printf "       %s\n" "$*"; }
die() { printf "${RED}     FAIL${RESET} %s\n" "$*" >&2; exit 1; }

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

yh() {
    local label="$1"; local logfile="$2"; shift 2
    step "${label}"
    printf "${MAGENTA}     \$ (cd %s && ./yahcli) %s${RESET}\n" "${YAHCLI_DIR}" "$(shellquote "$@")"
    set +e
    (cd "${YAHCLI_DIR}" && ./yahcli "$@") 2>&1 | tee "${logfile}"
    local rc=${PIPESTATUS[0]}
    set -e
    [[ ${rc} -eq 0 ]] || die "${label} - yahcli exited ${rc}"
    grep -qE '^\.!\. FAILED|^FAILED ' "${logfile}" && die "${label} - yahcli reported FAILED"
    ok "${label}"
}

yh_allow_status() {
    local allowed_status="$1"; local label="$2"; local logfile="$3"; shift 3
    step "${label}"
    printf "${MAGENTA}     \$ (cd %s && ./yahcli) %s${RESET}\n" "${YAHCLI_DIR}" "$(shellquote "$@")"
    set +e
    (cd "${YAHCLI_DIR}" && ./yahcli "$@") 2>&1 | tee "${logfile}"
    local rc=${PIPESTATUS[0]}
    set -e
    if grep -q "${allowed_status}" "${logfile}"; then
        ok "${label} (${allowed_status}; already done)"
        return 0
    fi
    [[ ${rc} -eq 0 ]] || die "${label} - yahcli exited ${rc}"
    grep -qE '^\.!\. FAILED|^FAILED ' "${logfile}" && die "${label} - yahcli reported FAILED"
    ok "${label}"
}

capture_contract_id() {
    grep -oE '(deployed ClprLedgerVerifier as|created contract) 0\.[0-9]+\.[0-9]+' "$1" \
        | head -1 | awk '{print $NF}'
}

resolve_smart_contracts_repo() {
    if [[ -n "${SMART_CONTRACTS_REPO:-}" ]]; then
        cd "${SMART_CONTRACTS_REPO}" && pwd
        return
    fi
    for candidate in "${REPO_ROOT}/../clpr-smart-contracts" "${REPO_ROOT}/../../clpr-smart-contracts"; do
        if [[ -d "${candidate}/deployments" ]]; then
            cd "${candidate}" && pwd
            return
        fi
    done
    return 1
}

read_env_var() {
    local file="$1" key="$2"
    grep -E "^${key}=" "${file}" 2>/dev/null | head -1 | cut -d= -f2- | sed 's/[[:space:]]*#.*$//' | tr -d '"' || true
}

write_default_local_config() {
    local out_file="$1"
    if [[ -f "${out_file}" && "${REGENERATE_LOCAL_CONFIG:-true}" != "true" ]]; then
        return 0
    fi

    local ip="${LOCAL_HIERO_EP_IP:-127.0.0.1}"
    local port="${LOCAL_HIERO_EP_PORT:-50211}"
    local service_hex="${LOCAL_CLPR_SERVICE_ADDRESS_HEX:-000000000000000000000000000000000000016e}"
    local tls_hex="${LOCAL_HIERO_EP_TLS_CERT_HEX:-01}"

    python3 - "${out_file}" "${ip}" "${port}" "${service_hex}" "${tls_hex}" <<'PY'
import base64
import json
import sys
from pathlib import Path

out_file, ip, port, service_hex, tls_hex = sys.argv[1:6]

def b64hex(value: str) -> str:
    value = value[2:] if value.startswith(("0x", "0X")) else value
    if len(value) % 2:
        value = "0" + value
    return base64.b64encode(bytes.fromhex(value)).decode("ascii")

cfg = {
    "serviceAddress": b64hex(service_hex),
    "throttles": {
        "maxMessagesPerBundle": 100,
        "maxSyncsPerSec": 10,
        "maxMessagePayloadBytes": 1024,
        "maxGasPerMessage": 1000000,
        "maxQueueDepth": 1000,
        "maxSyncBytes": 1048576,
        "maxBundlesPerSec": 0,
    },
    "endpoints": [
        {
            "serviceEndpoint": {
                "ipAddress": ip,
                "port": int(port),
            },
            "tlsCertificate": b64hex(tls_hex),
        }
    ],
}
Path(out_file).write_text(json.dumps(cfg, indent=2) + "\n")
PY
}

mkdir -p "${SCRIPT_DIR}/.bridge" "${LOG_DIR}"

header "Preflight"
for tool in jq cast python3 curl; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool not in PATH"
done
write_default_local_config "${CONFIG_LOCAL}"
[[ -x "${YAHCLI_DIR}/yahcli" ]] || die "missing ${YAHCLI_DIR}/yahcli launcher"
[[ -f "${YAHCLI_DIR}/yahcli.jar" ]] || die "missing yahcli.jar - run ./gradlew :yahcli:copyYahCli"
[[ -f "${CONFIG_LOCAL}" ]] || die "missing local ledger-config: ${CONFIG_LOCAL}"
[[ -f "${PASS_THROUGH_AUTH}" ]] || die "missing PassThroughAuth.bin at ${PASS_THROUGH_AUTH}"
[[ -f "${TOOLS_DIR}/build-sei-config-payload.py" ]] || die "missing ${TOOLS_DIR}/build-sei-config-payload.py"
[[ -f "${TOOLS_DIR}/sign-clpr-connector-identity.py" ]] || die "missing ${TOOLS_DIR}/sign-clpr-connector-identity.py"
[[ -x "${SCRIPT_DIR}/gen-channel-identity.sh" ]] || die "missing gen-channel-identity.sh"
SMART_CONTRACTS_REPO="$(resolve_smart_contracts_repo)" || die "clpr-smart-contracts repo not found"

echo "  JAVA_HOME            : ${JAVA_HOME:-(system default)}"
echo "  SMART_CONTRACTS_REPO : ${SMART_CONTRACTS_REPO}"
echo "  SEI_EVM_RPC          : ${SEI_EVM_RPC}"
echo "  SEI_TM_RPC           : ${SEI_TM_RPC}"
echo "  NET                  : ${NET}   PAYER=${PAYER}"
echo "  VERIFIER_CONTRACT    : ${VERIFIER_CONTRACT}"
echo "  LOCAL_CONFIG         : ${CONFIG_LOCAL}"
ok "All prerequisites present"

header "Resolve Sei deployment state"
EVM_CHAIN_ID="${EVM_CHAIN_ID:-$(cast chain-id --rpc-url "${SEI_EVM_RPC}")}"
CHANNEL_JSON="${SMART_CONTRACTS_REPO}/deployments/${EVM_CHAIN_ID}/channel.json"
CONNECTOR_JSON="${SMART_CONTRACTS_REPO}/deployments/${EVM_CHAIN_ID}/connector.json"
[[ -f "${CHANNEL_JSON}" ]] || die "${CHANNEL_JSON} missing - run deploy-sei-clpr.sh first"
[[ -f "${CONNECTOR_JSON}" ]] || die "${CONNECTOR_JSON} missing - run deploy-sei-clpr.sh first"

CLPR_SERVICE="$(jq -r '.clprService' "${CHANNEL_JSON}")"
CHANNEL_ID="$(jq -r '.channelId' "${CHANNEL_JSON}")"
PEER_CHAIN_ID="${PEER_CHAIN_ID:-$(jq -r '.peerChainId' "${CHANNEL_JSON}")}"
CONNECTOR_ID="$(jq -r '.connectorId' "${CONNECTOR_JSON}")"

env_file="${SMART_CONTRACTS_REPO}/.env"
if [[ -z "${PRIVATE_KEY:-}" ]]; then
    PRIVATE_KEY="$(read_env_var "${env_file}" PRIVATE_KEY)"
fi
PRIVATE_KEY="${PRIVATE_KEY:-${ANVIL_DEV_KEY_0}}"
DEPLOYER_PUBKEY="$(cast wallet public-key --private-key "${PRIVATE_KEY}")"

echo "  EVM_CHAIN_ID   : ${EVM_CHAIN_ID}"
echo "  CLPR_SERVICE   : ${CLPR_SERVICE}"
echo "  CHANNEL_ID  : ${CHANNEL_ID}"
echo "  CONNECTOR_ID   : ${CONNECTOR_ID}"
echo "  PEER_CHAIN_ID  : ${PEER_CHAIN_ID}"
ok "Sei state resolved"

header "[1/10] update-ledger-configuration on ${NET}"
yh "update-ledger-configuration on ${NET}" "${LOG_DIR}/01-update-cfg.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr update-ledger-configuration --config-file "${CONFIG_LOCAL}"

if [[ "${FUND_NODE_ACCOUNT:-true}" == "true" ]]; then
    header "[2/10] fund Hiero node payer"
    yh "fund ${NODE_ACCOUNT:-0.0.3} with ${NODE_ACCOUNT_FUND_HBAR:-1000000000} hbar" \
        "${LOG_DIR}/02-fund-node.log" \
        -n "${NET}" -p "${PAYER}" \
        accounts send --to "${NODE_ACCOUNT:-0.0.3}" "${NODE_ACCOUNT_FUND_HBAR:-1000000000}" -d hbar
else
    header "[2/10] fund Hiero node payer"
    note "skipped because FUND_NODE_ACCOUNT=false"
fi

header "[3/10] build Sei config payload"
FALLBACK_EP_IP="${FALLBACK_EP_IP:-127.0.0.1}"
FALLBACK_EP_PORT="${FALLBACK_EP_PORT:-9545}"
FALLBACK_EP_KEY="${FALLBACK_EP_KEY:-${DEPLOYER_PUBKEY}}"
python3 - "${SEI_CONFIG_JSON}" "${FALLBACK_EP_IP}" "${FALLBACK_EP_PORT}" "${FALLBACK_EP_KEY}" <<'PYEOF'
import json, sys
out_path, ip, port, key = sys.argv[1:5]
if key.startswith(("0x", "0X")):
    key = key[2:]
cfg = {
    "throttles": {
        "max_messages_per_bundle": 100,
        "max_syncs_per_sec": 10,
        "max_message_payload_bytes": 1024,
        "max_gas_per_message": 1000000,
        "max_queue_depth": 1000,
        "max_sync_bytes": 1048576,
        "max_bundles_per_sec": 0
    },
    "endpoints": [{
        "ip_address": ip,
        "port": int(port),
        "tls_certificate": "",
        "ecdsa_signing_key": "0x" + key,
        "account_id": ""
    }]
}
with open(out_path, "w") as f:
    json.dump(cfg, f, indent=2)
PYEOF

PAYLOAD_OUT="$(python3 "${TOOLS_DIR}/build-sei-config-payload.py" \
    --tm-rpc "${SEI_TM_RPC}" \
    --service "${CLPR_SERVICE}" \
    --chain-id "${PEER_CHAIN_ID}" \
    --protocol-version "${PROTOCOL_VERSION}" \
    --config-json "${SEI_CONFIG_JSON}" \
    --out-payload "${SEI_CONFIG_PAYLOAD}" 2>&1)"
echo "${PAYLOAD_OUT}" | tee "${LOG_DIR}/02-sei-config-payload.log" >/dev/null
CONFIG_PROOF_HEX="$(echo "${PAYLOAD_OUT}" | awk '/^sei_ledger_configuration_payload / { getline; print; exit }')"
[[ -n "${CONFIG_PROOF_HEX}" ]] || die "could not extract sei_ledger_configuration_payload hex"
note "config-proof payload: ${SEI_CONFIG_PAYLOAD}"
note "config-proof hex (${#CONFIG_PROOF_HEX} chars): ${CONFIG_PROOF_HEX:0:60}..."

header "[4/10] generate channel identity"
"${SCRIPT_DIR}/gen-channel-identity.sh" \
    --channel-id "${CHANNEL_ID}" \
    --priv "${PEER_PK_HEX}" \
    --out "${CHANNEL_FILE}" >/dev/null
CHANNEL_COMMIT="$(jq -r '.ownershipCommitment' "${CHANNEL_FILE}")"
[[ -n "${CHANNEL_COMMIT}" && "${CHANNEL_COMMIT}" != "null" ]] || die "no ownershipCommitment in ${CHANNEL_FILE}"
note "ownershipCommitment: ${CHANNEL_COMMIT}"

header "[5/10] register-channel on ${NET}"
yh "register-channel" "${LOG_DIR}/04-register-conn.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr register-channel --commitment "${CHANNEL_COMMIT}"

header "[6/10] complete-channel on ${NET} via verifier ${VERIFIER_CONTRACT}"
yh_allow_status "CLPR_CHANNEL_ALREADY_EXISTS" "complete-channel" "${LOG_DIR}/05-complete-conn.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr complete-channel \
        --identity "${CHANNEL_FILE}" \
        --verifier-contract "${VERIFIER_CONTRACT}" \
        --config-proof-hex "${CONFIG_PROOF_HEX}"

header "[7/10] generate connector identity"
cat > "${CONNECTOR_FILE}" <<JSON
{
  "channelId":   "${CHANNEL_ID}",
  "privateKey":     "${CONNECTOR_PK_HEX}",
  "signatureScheme":"ECDSA_SECP256K1",
  "salt":           "0x0000000000000000000000000000000000000000000000000000000000000000"
}
JSON
python3 "${TOOLS_DIR}/sign-clpr-connector-identity.py" --in-place "${CONNECTOR_FILE}" \
    | tee "${LOG_DIR}/06-sign-connector.log" >/dev/null
CONNECTOR_COMMIT="$(jq -r '.commitment' "${CONNECTOR_FILE}")"
CONNECTOR_ID_HIERO="$(jq -r '.connectorId' "${CONNECTOR_FILE}")"
[[ -n "${CONNECTOR_COMMIT}" && "${CONNECTOR_COMMIT}" != "null" ]] || die "no commitment in ${CONNECTOR_FILE}"
note "connectorId (hiero): ${CONNECTOR_ID_HIERO}"
if [[ "${CONNECTOR_ID_HIERO}" != "${CONNECTOR_ID}" ]]; then
    note "${YELLOW}warn:${RESET} connectorId derived here differs from smart-contracts/connector.json"
fi

header "[8/10] register-connector on ${NET}"
yh "register-connector" "${LOG_DIR}/07-register-connector.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr register-connector --commitment "${CONNECTOR_COMMIT}"

header "[9/10] deploy PassThroughAuth on ${NET}"
yh "contracts create PassThroughAuth" "${LOG_DIR}/08-create-pta.log" \
    -n "${NET}" -p "${PAYER}" contracts create \
        --init-code-file "${PASS_THROUGH_AUTH}" \
        --gas 500000 --memo "pass-through connector auth" --immutable
CONNECTOR_CONTRACT="$(capture_contract_id "${LOG_DIR}/08-create-pta.log")"
[[ -n "${CONNECTOR_CONTRACT}" ]] || die "could not capture PassThroughAuth contract id"

header "[10/10] complete-connector on ${NET}"
yh "complete-connector" "${LOG_DIR}/09-complete-connector.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr complete-connector \
        --identity "${CONNECTOR_FILE}" \
        --connector-contract "${CONNECTOR_CONTRACT}" \
        --locked-stake "${LOCKED_STAKE}"

header "Done"
cat <<EOF
  Sei side:
    EVM chain id      : ${EVM_CHAIN_ID}
    CLPR service      : ${CLPR_SERVICE}
    Channel id     : ${CHANNEL_ID}
    Connector id      : ${CONNECTOR_ID}

  Hiero side:
    Network            : ${NET}
    Sei verifier       : ${VERIFIER_CONTRACT}
    Connector contract : ${CONNECTOR_CONTRACT}
    Channel bundle  : ${CHANNEL_FILE}
    Connector bundle   : ${CONNECTOR_FILE}

  Per-step logs        : ${LOG_DIR}/
EOF
