#!/usr/bin/env bash
#
# Deploy CLPR contracts to local Sei EVM and open one demo channel.
#
# This mirrors clpr-smart-contracts/script/demo.sh, but targets the local Sei
# EVM RPC through foundry's "hiero" alias (HIERO_RPC).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
SEI_EVM_RPC="${SEI_EVM_RPC:-http://127.0.0.1:8545}"
SEI_TM_RPC="${SEI_TM_RPC:-http://127.0.0.1:26657}"
ANVIL_DEV_KEY_0="0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"

die() { echo "FAIL $*" >&2; exit 1; }
note() { echo "$*" >&2; }

resolve_smart_contracts_repo() {
    if [[ -n "${SMART_CONTRACTS_REPO:-}" ]]; then
        cd "${SMART_CONTRACTS_REPO}" && pwd
        return
    fi
    for candidate in "${REPO_ROOT}/../clpr-smart-contracts" "${REPO_ROOT}/../../clpr-smart-contracts"; do
        if [[ -f "${candidate}/bin/deploy.sh" ]]; then
            cd "${candidate}" && pwd
            return
        fi
    done
    return 1
}

clear_dotenv_state_ids() {
    local env_file="$1"
    [[ -f "${env_file}" ]] || return 0
    python3 - "$env_file" <<'PY'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
lines = path.read_text().splitlines()
drop = re.compile(r"^\s*(?:export\s+)?(?:CHANNEL_ID|CONNECTOR_ID)\s*=")
path.write_text("\n".join(line for line in lines if not drop.match(line)).rstrip() + "\n")
PY
}

text = Path(sys.argv[1]).read_text()
payload = "".join(line.strip() for line in text.splitlines() if not line.startswith("---"))
print("0x" + base64.b64decode(payload).hex())
PY
)"
    cast wallet public-key --private-key "${private_key}"
}

ensure_relay_endpoint_registered() {
    local relay_public_key="$1"
    local relay_address
    relay_address="$(cast wallet address --private-key "${PRIVATE_KEY}")"

    local registered
    registered="$(cast call "${CLPR_SERVICE}" 'isRegistered(address)(bool)' "${relay_address}" --rpc-url "${SEI_EVM_RPC}" 2>/dev/null || true)"
    if [[ "${registered}" =~ true|1 ]]; then
        note "Relay endpoint already registered on Sei: ${relay_address}"
        return
    fi

    note "Registering relay endpoint on Sei: ${relay_address}"
    cast send "${CLPR_SERVICE}" \
        'registerEndpoint(bytes)' \
        "${relay_public_key}" \
        --private-key "${PRIVATE_KEY}" \
        --rpc-url "${SEI_EVM_RPC}" >/dev/null
}

tm_network() {
    local body
    if ! body="$(curl -fsS -X POST "${SEI_TM_RPC}" \
        -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","id":1,"method":"status","params":{}}')"; then
        die "CometBFT RPC is not reachable at ${SEI_TM_RPC}. Restart Sei with published ports or set SEI_TM_RPC."
    fi
    python3 -c 'import json,sys; payload=json.load(sys.stdin); print(payload.get("result", payload)["node_info"]["network"])' \
        <<< "${body}"
}

for tool in curl cast forge python3; do
    command -v "${tool}" >/dev/null 2>&1 || die "${tool} not found on PATH"
done

SMART_CONTRACTS_REPO="$(resolve_smart_contracts_repo)" \
    || die "clpr-smart-contracts repo not found. Set SMART_CONTRACTS_REPO=/path/to/clpr-smart-contracts"

export HIERO_RPC="${SEI_EVM_RPC}"
export PRIVATE_KEY="${PRIVATE_KEY:-${ANVIL_DEV_KEY_0}}"
export INITIAL_OWNER="${INITIAL_OWNER:-$(cast wallet address --private-key "${PRIVATE_KEY}")}"
RELAY_EP_SIGNING_KEY="$(cast wallet public-key --private-key "${PRIVATE_KEY}")"
SEI_NETWORK="${SEI_NETWORK:-$(tm_network)}"
export CHAIN_ID="${CHAIN_ID:-cosmos:${SEI_NETWORK}}"
export PEER_CHAIN_ID="${PEER_CHAIN_ID:-${CHAIN_ID}}"
export PROTOCOL_VERSION="${PROTOCOL_VERSION:-1}"

note "Deploying CLPR to Sei EVM..."
note "  SMART_CONTRACTS_REPO: ${SMART_CONTRACTS_REPO}"
note "  SEI_EVM_RPC         : ${SEI_EVM_RPC}"
note "  SEI_TM_RPC          : ${SEI_TM_RPC}"
note "  CLPR chain id       : ${CHAIN_ID}"
note "  PEER_CHAIN_ID       : ${PEER_CHAIN_ID}"

cd "${SMART_CONTRACTS_REPO}"
"${SMART_CONTRACTS_REPO}/bin/deploy.sh" all --rpc hiero

set -a
# shellcheck disable=SC1091
. "${SMART_CONTRACTS_REPO}/.env"
set +a
[[ -n "${CLPR_SERVICE:-}" ]] || die "CLPR_SERVICE missing after deploy"

note "Setting ledger configuration serviceAddress=${CLPR_SERVICE}..."
cast send "${CLPR_SERVICE}" \
    'updateLedgerConfiguration(bytes,(uint64,uint64,uint64,uint64,uint64,uint64,uint64),(string,uint32,bytes,bytes,bytes)[],bytes,bytes)' \
    "${CLPR_SERVICE}" \
    "(${MAX_MESSAGES_PER_BUNDLE:-100},${MAX_SYNCS_PER_SEC:-10},${MAX_MESSAGE_PAYLOAD_BYTES:-1024},${MAX_GAS_PER_MESSAGE:-1000000},${MAX_QUEUE_DEPTH:-1000},${MAX_SYNC_BYTES:-1048576},${MAX_BUNDLES_PER_SEC:-0})" \
    "[]" \
    "0x" \
    "0x" \
    --private-key "${PRIVATE_KEY}" \
    --rpc-url "${SEI_EVM_RPC}" >/dev/null

ensure_relay_endpoint_registered "${RELAY_EP_SIGNING_KEY}"

clear_dotenv_state_ids "${SMART_CONTRACTS_REPO}/.env"
unset CHANNEL_ID CONNECTOR_ID

note "Opening demo channel on Sei..."
forge script script/CreateChannel.s.sol --rpc-url hiero --broadcast --legacy
forge script script/CreateConnector.s.sol  --rpc-url hiero --broadcast --legacy
forge script script/SendMessage.s.sol      --rpc-url hiero --broadcast --legacy
forge script script/SeedHieroEndpoint.s.sol --rpc-url hiero --broadcast --legacy

EVM_CHAIN_ID="$(cast chain-id --rpc-url "${SEI_EVM_RPC}")"
note "Done."
note "  CLPR service : ${CLPR_SERVICE}"
note "  State files  : ${SMART_CONTRACTS_REPO}/deployments/${EVM_CHAIN_ID}/"
