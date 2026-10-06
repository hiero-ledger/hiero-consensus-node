#!/usr/bin/env bash
#
# Generate relay.yaml from the local Sei deployment and run clpr-evm-endpoint.
#
# Usage:
#   ./run-sei-relay-from-state.sh          # foreground
#   ./run-sei-relay-from-state.sh print    # only write/print config path
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
SEI_EVM_RPC="${SEI_EVM_RPC:-http://127.0.0.1:8545}"
SEI_TM_RPC="${SEI_TM_RPC:-http://127.0.0.1:26657}"
RELAY_PORT="${RELAY_PORT:-9545}"
RELAY_MAX_MESSAGE_SIZE="${RELAY_MAX_MESSAGE_SIZE:-10485760}"
ANVIL_DEV_KEY_0="0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
CMD="${1:-run}"

DEFAULT_JDK25="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
if [[ -x "${DEFAULT_JDK25}/bin/java" ]]; then
    export JAVA_HOME="${JAVA_HOME:-${DEFAULT_JDK25}}"
fi

die() { echo "FAIL $*" >&2; exit 1; }
note() { echo "$*" >&2; }

resolve_repo() {
    local env_name="$1"
    local marker="$2"
    local fallback_a="$3"
    local fallback_b="$4"
    local value="${!env_name:-}"
    if [[ -n "${value}" ]]; then
        cd "${value}" && pwd
        return
    fi
    for candidate in "${fallback_a}" "${fallback_b}"; do
        if [[ -e "${candidate}/${marker}" ]]; then
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

ensure_relay_endpoint_registered() {
    [[ "${REGISTER_RELAY_ENDPOINT:-true}" == "true" ]] || return 0

    local relay_address relay_public_key registered
    relay_address="$(cast wallet address --private-key "${PRIVATE_KEY}")"
    relay_public_key="$(cast wallet public-key --private-key "${PRIVATE_KEY}")"
    registered="$(cast call "${CLPR_SERVICE}" 'isRegistered(address)(bool)' "${relay_address}" --rpc-url "${SEI_EVM_RPC}" 2>/dev/null || true)"
    if [[ "${registered}" =~ true|1 ]]; then
        note "Relay endpoint already registered: ${relay_address}"
        return 0
    fi

    note "Registering relay endpoint before start: ${relay_address}"
    cast send "${CLPR_SERVICE}" \
        'registerEndpoint(bytes)' \
        "${relay_public_key}" \
        --private-key "${PRIVATE_KEY}" \
        --rpc-url "${SEI_EVM_RPC}" >/dev/null
}

for tool in cast jq; do
    command -v "${tool}" >/dev/null 2>&1 || die "${tool} not found on PATH"
done

SMART_CONTRACTS_REPO="$(resolve_repo SMART_CONTRACTS_REPO deployments "${REPO_ROOT}/../clpr-smart-contracts" "${REPO_ROOT}/../../clpr-smart-contracts")" \
    || die "clpr-smart-contracts repo not found"
EVM_ENDPOINT_REPO="$(resolve_repo EVM_ENDPOINT_REPO clpr-relay-app "${REPO_ROOT}/../clpr-evm-endpoint" "${REPO_ROOT}/../../clpr-evm-endpoint")" \
    || die "clpr-evm-endpoint repo not found"

EVM_CHAIN_ID="${EVM_CHAIN_ID:-$(cast chain-id --rpc-url "${SEI_EVM_RPC}")}"
CHANNEL_JSON="${SMART_CONTRACTS_REPO}/deployments/${EVM_CHAIN_ID}/channel.json"
[[ -f "${CHANNEL_JSON}" ]] || die "${CHANNEL_JSON} missing - run deploy-sei-clpr.sh first"
CLPR_SERVICE="$(jq -r '.clprService' "${CHANNEL_JSON}")"
CHANNEL_ID="$(jq -r '.channelId' "${CHANNEL_JSON}")"

env_file="${SMART_CONTRACTS_REPO}/.env"
if [[ -z "${PRIVATE_KEY:-}" ]]; then
    PRIVATE_KEY="$(read_env_var "${env_file}" PRIVATE_KEY)"
fi
PRIVATE_KEY="${PRIVATE_KEY:-${ANVIL_DEV_KEY_0}}"
ensure_relay_endpoint_registered

RUN_DIR="${SCRIPT_DIR}/.bridge"
RELAY_CONFIG="${RELAY_CONFIG:-${RUN_DIR}/sei-relay.yaml}"
mkdir -p "${RUN_DIR}"

cat > "${RELAY_CONFIG}" <<EOF
signingPrivateKeyHex: "${PRIVATE_KEY}"
grpc:
  port: ${RELAY_PORT}
  maxMessageSize: ${RELAY_MAX_MESSAGE_SIZE}
localNetworks:
  - id: sei-local
    proofType: CometBFT
    evm:
      jsonRpcUrl: "${SEI_EVM_RPC}"
      chainId: ${EVM_CHAIN_ID}
      pollIntervalMs: ${RELAY_POLL_INTERVAL_MS:-1000}
      requestTimeoutMs: ${RELAY_EVM_REQUEST_TIMEOUT_MS:-30000}
      maxRpcRetries: ${RELAY_EVM_MAX_RETRIES:-3}
    cometBft:
      cometBftRpcUrl: "${SEI_TM_RPC}"
      maxMessagesPerBundle: ${RELAY_MAX_MESSAGES_PER_BUNDLE:-10}
      maxRetries: ${RELAY_COMET_MAX_RETRIES:-3}
      requestTimeoutMs: ${RELAY_COMET_REQUEST_TIMEOUT_MS:-30000}
backoff:
  baseMs: 1000
  capMs: 30000
channels:
  - channelId: "${CHANNEL_ID}"
    commitmentLevel: LATEST
    localNetwork: sei-local
    peerProofType: Hiero
    serviceAddress: "${CLPR_SERVICE}"
EOF

note "Relay config: ${RELAY_CONFIG}"
note "  EVM RPC       : ${SEI_EVM_RPC}"
note "  CometBFT RPC  : ${SEI_TM_RPC}"
note "  CLPR service  : ${CLPR_SERVICE}"
note "  Channel id : ${CHANNEL_ID}"

if [[ "${CMD}" == "print" ]]; then
    exit 0
fi
[[ "${CMD}" == "run" ]] || die "usage: $0 [run|print]"

export RELAY_CONFIG_FILE="${RELAY_CONFIG}"
export LOG_CONFIG_PATH="${EVM_ENDPOINT_REPO}/log.properties"
cd "${EVM_ENDPOINT_REPO}"
exec ./gradlew :clpr-relay-app:run
