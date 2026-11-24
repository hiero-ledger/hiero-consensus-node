#!/usr/bin/env bash
#
# bridge-from-besu.sh
#
# Drive the Hiero side of a Besu↔Hiero CLPR bridge using deployment state
# pulled from the sibling clpr-smart-contracts repo. Eliminates manual
# copy-paste of:
#   - CLPR service contract address       (← deployments/<chainId>/channel.json)
#   - Channel id                       (← deployments/<chainId>/channel.json)
#   - Connector id                        (← deployments/<chainId>/connector.json)
#   - Besu QBFT validator address         (← derived from .env PRIVATE_KEY)
#   - CLPR service code hash              (← cast keccak of cast code, live)
#   - Besu chain ID for the config proof (← Besu's on-chain ledger configuration)
# Uses the same connector key and salt as CreateConnector.s.sol to derive its ID.
#
# REQUIRED env:
#   VERIFIER_CONTRACT   id (0.0.X) of a pre-deployed Besu-QBFT verifier on this
#                       Hiero network. There is no `deploy-besu-verifier` yahcli
#                       subcommand yet, so this is supplied externally.
#
# Optional env (defaults shown):
#   SMART_CONTRACTS_REPO  ../../../../../clpr-smart-contracts  (sibling repo)
#   CHAIN_ID              auto-detect from $SMART_CONTRACTS_REPO/deployments/
#   CONNECTOR_PK          0x00…00C044EC  (CreateConnector.s.sol default)
#   PEER_CHAIN_ID         optional assertion for channel.json's Hiero peerChainId
#   NET                   localhost
#   PAYER                 2
#   LOCKED_STAKE          100000000
#   PASS_THROUGH_AUTH     ../test-clients/.../PassThroughAuth.bin (auto-deployed)
#   CONFIG_LOCAL          ledger-config.json (used for update-ledger-configuration)
#   PROTOCOL_VERSION      1                                  (for config payload)
#   CONFIG_BLOCK_TAG      finalized                          (for QBFT config proof)
#
# Usage:
#   VERIFIER_CONTRACT=0.0.367 ./bridge-from-besu.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=key-files.sh
. "${SCRIPT_DIR}/key-files.sh"

# Layout: SCRIPT_DIR = <hiero-repo>/hedera-node/yahcli/scripts/clpre2e
# Walk up 4 levels to land on the hiero repo root (so REPO_ROOT/hedera-node exists).
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

# yahcli launcher lives two levels up (the yahcli/
# directory). Override with YAHCLI_DIR=/path if running against a non-standard
# layout.
YAHCLI_DIR="$(cd "${YAHCLI_DIR:-${SCRIPT_DIR}/../..}" && pwd)"

# Trust-anchor / connector-signing python tools live under hedera-node/tools.
TOOLS_DIR="${TOOLS_DIR:-${REPO_ROOT}/hedera-node/tools}"

# ─── JDK guard (yahcli.jar is class file 69 = JDK 25) ─────────────────────
DEFAULT_JDK25="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
if [[ -x "${DEFAULT_JDK25}/bin/java" ]]; then
    export JAVA_HOME="${JAVA_HOME:-${DEFAULT_JDK25}}"
fi

# ─── Defaults ─────────────────────────────────────────────────────────────
# Find clpr-smart-contracts. Tries (in order): explicit env var → sibling of
# REPO_ROOT (`../clpr-smart-contracts`) → grandparent layout
# (`../../clpr-smart-contracts`). The grandparent variant covers the common
# `~/.../clpr/clpr-hiero/` + `~/.../clpr/clpr-smart-contracts/` layout.
SMART_CONTRACTS_REPO="${SMART_CONTRACTS_REPO:-}"
if [[ -z "${SMART_CONTRACTS_REPO}" ]]; then
    for candidate in \
        "${REPO_ROOT}/../clpr-smart-contracts" \
        "${REPO_ROOT}/../../clpr-smart-contracts"; do
        if [[ -d "${candidate}" ]]; then
            SMART_CONTRACTS_REPO="$(cd "${candidate}" && pwd)"
            break
        fi
    done
fi

NET="${NET:-localhost}"
PAYER="${PAYER:-2}"
LOCKED_STAKE="${LOCKED_STAKE:-100000000}"
PROTOCOL_VERSION="${PROTOCOL_VERSION:-1}"
CONFIG_BLOCK_TAG="${CONFIG_BLOCK_TAG:-finalized}"

PASS_THROUGH_AUTH="${PASS_THROUGH_AUTH:-${REPO_ROOT}/hedera-node/test-clients/src/main/resources/contract/contracts/PassThroughAuth/PassThroughAuth.bin}"
# ledger-config.json ships with yahcli (run-clpr-end-to-end.sh writes it).
CONFIG_LOCAL="${CONFIG_LOCAL:-${YAHCLI_DIR}/ledger-config.json}"

# Scratch + log dirs stay next to this script — they're per-run artifacts, no
# reason to put them inside the yahcli dir where they'd collide with other tooling.
CONNECTOR_FILE="${SCRIPT_DIR}/.bridge/connector.json"
QBFT_CONFIG_PAYLOAD="${SCRIPT_DIR}/.bridge/besu-config-payload.bin"
LOG_DIR="${SCRIPT_DIR}/.run-logs-bridge"
mkdir -p "${SCRIPT_DIR}/.bridge" "${LOG_DIR}"

# ─── Helpers (cloned from run-clpr-end-to-end.sh) ─────────────────────────
YELLOW=$'\033[1;33m'
CYAN=$'\033[1;36m'
GREEN=$'\033[1;32m'
RED=$'\033[1;31m'
MAGENTA=$'\033[1;35m'
RESET=$'\033[0m'

header() { printf "\n${CYAN}==> %s${RESET}\n" "$*"; }
step()   { printf "${YELLOW}  -- %s${RESET}\n" "$*"; }
ok()     { printf "${GREEN}     OK${RESET} %s\n" "$*"; }
note()   { printf "       %s\n" "$*"; }
die()    { printf "${RED}     FAIL${RESET} %s\n" "$*" >&2; exit 1; }

# Identity keys come from files (never from env vars or the command line). Unset = the well-known
# dev defaults that match the clpr-smart-contracts scripts.
if [[ -n "${CONNECTOR_PK:-}" ]]; then
    die "Use CONNECTOR_PK_FILE to supply the connector key"
fi
if [[ -n "${CONNECTOR_PK_FILE:-}" ]]; then
    CONNECTOR_PK_HEX="$(kf_read_key_file "${CONNECTOR_PK_FILE}")" || die "bad CONNECTOR_PK_FILE"
else
    CONNECTOR_PK_HEX="0x0000000000000000000000000000000000000000000000000000000000c044ec"
fi

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
    [[ ${rc} -eq 0 ]] || die "${label} — yahcli exited ${rc}"
    grep -qE '^\.!\. FAILED|^FAILED ' "${logfile}" \
        && die "${label} — yahcli reported FAILED"
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
    [[ ${rc} -eq 0 ]] || die "${label} — yahcli exited ${rc}"
    grep -qE '^\.!\. FAILED|^FAILED ' "${logfile}" \
        && die "${label} — yahcli reported FAILED"
    ok "${label}"
}

capture_contract_id() {
    grep -oE '(deployed ClprLedgerVerifier as|created contract) 0\.[0-9]+\.[0-9]+' "$1" \
        | head -1 | awk '{print $NF}'
}

# ─── Preflight ────────────────────────────────────────────────────────────
header "Preflight"
[[ -n "${SMART_CONTRACTS_REPO}" && -d "${SMART_CONTRACTS_REPO}" ]] \
    || die "smart-contracts repo not found. Set SMART_CONTRACTS_REPO=/path/to/clpr-smart-contracts"
[[ -d "${YAHCLI_DIR}" ]]              || die "yahcli directory not found at ${YAHCLI_DIR}; set YAHCLI_DIR=/path/to/yahcli"
[[ -x "${YAHCLI_DIR}/yahcli" ]]       || die "missing ${YAHCLI_DIR}/yahcli launcher"
[[ -f "${YAHCLI_DIR}/yahcli.jar" ]]   || die "missing yahcli.jar — (cd ${REPO_ROOT} && ./gradlew :yahcli:copyYahCli)"
[[ -f "${CONFIG_LOCAL}" ]]            || die "missing local ledger-config: ${CONFIG_LOCAL}"
[[ -f "${PASS_THROUGH_AUTH}" ]]       || die "missing PassThroughAuth.bin at ${PASS_THROUGH_AUTH}"
[[ -n "${VERIFIER_CONTRACT:-}" ]]     || die "VERIFIER_CONTRACT env var is required (id of pre-deployed Besu-QBFT verifier on this Hiero net)"

for tool in jq cast python3; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool not in PATH"
done

CONFIG_PAYLOAD_PY="${TOOLS_DIR}/build-besu-qbft-config-payload.py"
DERIVE_CONNECTOR_PY="${TOOLS_DIR}/derive-clpr-connector-identity.py"
[[ -f "${CONFIG_PAYLOAD_PY}" ]]  || die "missing ${CONFIG_PAYLOAD_PY}"
[[ -f "${DERIVE_CONNECTOR_PY}" ]]  || die "missing ${DERIVE_CONNECTOR_PY}"

echo "  JAVA_HOME            : ${JAVA_HOME:-(system default)}"
echo "  SMART_CONTRACTS_REPO : ${SMART_CONTRACTS_REPO}"
echo "  NET                  : ${NET}   PAYER=${PAYER}"
echo "  VERIFIER_CONTRACT    : ${VERIFIER_CONTRACT}"
echo "  per-step logs        : ${LOG_DIR}/"
ok "All prerequisites present"

# ─── Pull Besu state from smart-contracts repo ────────────────────────────
header "Resolve Besu deployment state from clpr-smart-contracts"

deployments_dir="${SMART_CONTRACTS_REPO}/deployments"
[[ -d "${deployments_dir}" ]] || die "${deployments_dir} missing — run smart-contracts/script/demo.sh first"

CHAIN_ID="${CHAIN_ID:-}"
if [[ -z "${CHAIN_ID}" ]]; then
    count=$(find "${deployments_dir}" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')
    if [[ "${count}" = "1" ]]; then
        CHAIN_ID="$(basename "$(find "${deployments_dir}" -mindepth 1 -maxdepth 1 -type d)")"
        note "auto-detected CHAIN_ID=${CHAIN_ID}"
    else
        die "multiple chains under deployments/ — set CHAIN_ID=<chainId> explicitly"
    fi
fi

CHANNEL_JSON="${deployments_dir}/${CHAIN_ID}/channel.json"
CONNECTOR_JSON="${deployments_dir}/${CHAIN_ID}/connector.json"
[[ -f "${CHANNEL_JSON}" ]]      || die "${CHANNEL_JSON} missing — run smart-contracts/script/demo.sh first"
[[ -f "${CONNECTOR_JSON}" ]] || die "${CONNECTOR_JSON} missing — run smart-contracts/script/demo.sh first"

CLPR_SERVICE="$(jq -r '.clprService'   "${CHANNEL_JSON}")"
CHANNEL_ID="$(jq -r '.channelId' "${CHANNEL_JSON}")"
HIERO_PEER_CHAIN_ID="$(jq -er '.peerChainId | strings | select(length > 0)' "${CHANNEL_JSON}")" \
    || die "missing peerChainId in ${CHANNEL_JSON}"
[[ "${PEER_CHAIN_ID:-${HIERO_PEER_CHAIN_ID}}" == "${HIERO_PEER_CHAIN_ID}" ]] \
    || die "PEER_CHAIN_ID differs from the deployment peerChainId (${HIERO_PEER_CHAIN_ID})"
CONNECTOR_ID="$(jq -r '.connectorId'   "${CONNECTOR_JSON}")"

# `grep ... || true` so pipefail doesn't kill us when a key is absent.
read_env_var() {
    local file="$1" key="$2"
    grep -E "^${key}=" "${file}" 2>/dev/null | head -1 | cut -d= -f2- | sed 's/[[:space:]]*#.*$//' | tr -d '"' || true
}

env_file="${SMART_CONTRACTS_REPO}/.env"
[[ -f "${env_file}" ]] || die "${env_file} missing — run smart-contracts/script/demo.sh first"

case "${CHAIN_ID}" in
    1337) BESU_RPC_FROM_ENV="$(read_env_var "${env_file}" BESU_RPC_A)" ;;
    1338) BESU_RPC_FROM_ENV="$(read_env_var "${env_file}" BESU_RPC_B)" ;;
    *)    BESU_RPC_FROM_ENV="" ;;
esac
BESU_RPC="${RELAY_RPC_URL:-${BESU_RPC_FROM_ENV:-http://localhost:53321}}"

# PRIVATE_KEY resolution order:
#   1. shell env (user usually exports it for deploy.sh)
#   2. .env file (if persisted)
#   3. Anvil dev key #0 (the prefunded default in genesis-a.json)
ANVIL_DEV_KEY_0="0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
if [[ -z "${PRIVATE_KEY:-}" ]]; then
    PRIVATE_KEY="$(read_env_var "${env_file}" PRIVATE_KEY)"
fi
if [[ -z "${PRIVATE_KEY}" ]]; then
    note "PRIVATE_KEY not in shell or .env — defaulting to Anvil dev key #0 (prefunded in genesis-a.json)"
    PRIVATE_KEY="${ANVIL_DEV_KEY_0}"
fi

# Sign with `cast` through a throwaway encrypted keystore (random password, 0700 dir, removed on
# exit) instead of `--private-key`, which would expose the key on the command line to every process.
CAST_KS_DIR="$(mktemp -d)"
trap 'rm -rf "${CAST_KS_DIR}"' EXIT
chmod 700 "${CAST_KS_DIR}"
(umask 177 && head -c 24 /dev/urandom | base64 > "${CAST_KS_DIR}/password")
printf '%s' "${PRIVATE_KEY}" | python3 "${TOOLS_DIR}/write-eth-keystore.py" \
    --out "${CAST_KS_DIR}/keystore.json" --password-file "${CAST_KS_DIR}/password" \
    || die "could not create a keystore from PRIVATE_KEY"
CAST_SIGNER=(--keystore "${CAST_KS_DIR}/keystore.json" --password-file "${CAST_KS_DIR}/password")

# Validator address = address of the deployer key. On dev Besu the deployer
# is the QBFT validator; in prod you'd override with the real validator.
VALIDATOR="${VALIDATOR:-$(cast wallet address "${CAST_SIGNER[@]}" 2>/dev/null || true)}"
[[ -n "${VALIDATOR}" ]] || die "could not derive validator address — bad PRIVATE_KEY?"

# Code hash of the live ClprService bytecode.
CODE_RAW="$(cast code "${CLPR_SERVICE}" --rpc-url "${BESU_RPC}" 2>/dev/null || true)"
if [[ -z "${CODE_RAW}" || "${CODE_RAW}" == "0x" ]]; then
    die "no bytecode at ${CLPR_SERVICE} on ${BESU_RPC} — Besu down, or channel.json stale (re-run smart-contracts/script/demo.sh)?"
fi
CODE_HASH="$(cast keccak "${CODE_RAW}")"

echo "  CHAIN_ID       : ${CHAIN_ID}"
echo "  BESU_RPC       : ${BESU_RPC}"
echo "  CLPR_SERVICE   : ${CLPR_SERVICE}"
echo "  CHANNEL_ID  : ${CHANNEL_ID}"
echo "  CONNECTOR_ID   : ${CONNECTOR_ID}"
echo "  VALIDATOR      : ${VALIDATOR}"
echo "  CODE_HASH      : ${CODE_HASH}"
echo "  HIERO_PEER_CHAIN_ID : ${HIERO_PEER_CHAIN_ID}"
echo "  CONNECTOR_PK   : (set; not printed)"
ok "Besu state resolved"

# ─── [1/6] Push local Hiero ledger configuration ──────────────────────────
header "[1/6] update-ledger-configuration on ${NET}"
HIERO_CONFIG="${SCRIPT_DIR}/.bridge/hiero-ledger-config.json"
yh "check Hiero peer chain ID" "${LOG_DIR}/01-get-cfg.log" \
    -n "${NET}" -p "${PAYER}" clpr get-ledger-configuration --out "${HIERO_CONFIG}"
HIERO_CHAIN_ID="$(jq -er '.configuration.chain_id | strings | select(length > 0)' "${HIERO_CONFIG}")" \
    || die "Hiero returned no configured chain ID"
[[ "${HIERO_CHAIN_ID}" == "${HIERO_PEER_CHAIN_ID}" ]] \
    || die "EVM channel expects Hiero ${HIERO_PEER_CHAIN_ID}, but ${NET} reports ${HIERO_CHAIN_ID}"

yh "update-ledger-configuration on ${NET}" "${LOG_DIR}/01-update-cfg.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr update-ledger-configuration --config-file "${CONFIG_LOCAL}"

# ─── [2/6] Build Besu QBFT config payload → config-proof hex ──────────────
header "[2/6] build Besu QBFT config proof (with throttles from on-chain)"

# Decode the LedgerConfiguration returned by the one-step EVM service.
step "cast call getLedgerConfiguration() on ${BESU_RPC}"
THROTTLES_TYPE='(uint32,uint64,uint64,uint32,uint64,uint32,uint32)'
LEDGER_CFG_RAW="$(cast call --json "${CLPR_SERVICE}" \
    "getLedgerConfiguration()((uint32,string,bytes,uint96,${THROTTLES_TYPE},bytes,bytes))" \
    --rpc-url "${BESU_RPC}")"
LEDGER_CFG_JSON="${SCRIPT_DIR}/.bridge/besu-ledger-config.json"
python3 - "${LEDGER_CFG_RAW}" "${LEDGER_CFG_JSON}" <<'PYEOF'
import json
import sys

(config,) = json.loads(sys.argv[1])
_, chain_id, service_address, _, throttles, trust_anchor, trust_anchor_id = config
if not isinstance(chain_id, str) or not chain_id.strip():
    sys.exit("Besu returned no configured chain ID")
fields = ("max_messages_per_bundle", "max_message_payload_bytes", "max_gas_per_message",
          "max_queue_depth", "max_sync_bytes", "max_local_endpoints", "max_peer_endpoints")
if len(throttles) != len(fields):
    sys.exit("Unexpected Besu throttles tuple")
with open(sys.argv[2], "w") as f:
    json.dump({"chain_id": chain_id, "service_address": service_address,
               "throttles": dict(zip(fields, map(int, throttles))),
               "trust_anchor": trust_anchor, "trust_anchor_id": trust_anchor_id}, f)
PYEOF

note "ledger-config JSON: ${LEDGER_CFG_JSON}"

ONCHAIN_SERVICE_ADDRESS="$(jq -r '.service_address // "0x"' "${LEDGER_CFG_JSON}")"
ONCHAIN_SERVICE_ADDRESS_LC="$(printf '%s' "${ONCHAIN_SERVICE_ADDRESS}" | tr '[:upper:]' '[:lower:]')"
CLPR_SERVICE_LC="$(printf '%s' "${CLPR_SERVICE}" | tr '[:upper:]' '[:lower:]')"
if [[ "${ONCHAIN_SERVICE_ADDRESS_LC}" != "${CLPR_SERVICE_LC}" ]]; then
    note "Besu config serviceAddress is ${ONCHAIN_SERVICE_ADDRESS}; updating it to ${CLPR_SERVICE}"
    THROTTLES_ARG="$(python3 -c 'import json, sys; print("(" + ",".join(map(str, json.load(sys.stdin)[0][4])) + ")")' <<<"${LEDGER_CFG_RAW}")"
    step "cast send updateLedgerConfiguration(serviceAddress=${CLPR_SERVICE})"
    cast send "${CLPR_SERVICE}" \
        "updateLedgerConfiguration(bytes,${THROTTLES_TYPE},bytes,bytes)" \
        "${CLPR_SERVICE}" "${THROTTLES_ARG}" \
        "$(jq -r '.trust_anchor' "${LEDGER_CFG_JSON}")" "$(jq -r '.trust_anchor_id' "${LEDGER_CFG_JSON}")" \
        "${CAST_SIGNER[@]}" \
        --rpc-url "${BESU_RPC}" \
        | tee "${LOG_DIR}/02a-update-besu-ledger-config.log" >/dev/null
    ok "updated Besu ledger configuration serviceAddress"
fi

step "python3 build-besu-qbft-config-payload.py --config-json ${LEDGER_CFG_JSON} (+ headers/proofs)"
PAYLOAD_OUT="$(python3 "${CONFIG_PAYLOAD_PY}" \
    --rpc-url "${BESU_RPC}" \
    --service "${CLPR_SERVICE}" \
    --protocol-version "${PROTOCOL_VERSION}" \
    --config-json "${LEDGER_CFG_JSON}" \
    --block-tag "${CONFIG_BLOCK_TAG}" \
    --out-payload "${QBFT_CONFIG_PAYLOAD}" 2>&1)"
echo "${PAYLOAD_OUT}" | tee "${LOG_DIR}/02-qbft-config-payload.log" >/dev/null
CONFIG_PROOF_HEX="$(echo "${PAYLOAD_OUT}" | awk '/^qbft_ledger_configuration_payload / { getline; print; exit }')"
[[ -n "${CONFIG_PROOF_HEX}" ]] || die "could not extract qbft_ledger_configuration_payload hex from config-payload tool"
note "config-proof payload: ${QBFT_CONFIG_PAYLOAD}"
note "config-proof hex (${#CONFIG_PROOF_HEX} chars): ${CONFIG_PROOF_HEX:0:60}…"

# Use the same salt and EVM-side verifier used when the EVM channel was opened.
: "${CHANNEL_SALT:?Set CHANNEL_SALT to the EVM channel salt (32-byte hex)}"
: "${EVM_VERIFIER_ADDRESS:?Set EVM_VERIFIER_ADDRESS to the verifier on the EVM ledger (20-byte hex)}"
header "[3/6] create-channel on ${NET} via verifier ${VERIFIER_CONTRACT}"
yh_allow_status "CLPR_CHANNEL_ALREADY_EXISTS" "create-channel" "${LOG_DIR}/05-complete-conn.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr create-channel \
        --channel-id "${CHANNEL_ID}" --salt "${CHANNEL_SALT}" --peer-verifier "${EVM_VERIFIER_ADDRESS}" \
        --verifier-contract "${VERIFIER_CONTRACT}" \
        --config-proof-hex "${CONFIG_PROOF_HEX}"

# ─── [4/6] Build connector identity bundle (using CONNECTOR_PK + salt=0) ─
header "[4/6] generate connector identity (using CONNECTOR_PK from smart-contracts side)"
# Public-only stub (no key material); the private key goes to ${CONNECTOR_FILE}.key (0600).
CONNECTOR_STUB="$(mktemp)"
cat > "${CONNECTOR_STUB}" <<JSON
{
  "signatureScheme":"ECDSA_SECP256K1",
  "salt":           "0x0000000000000000000000000000000000000000000000000000000000000000"
}
JSON
kf_write_key_file "${CONNECTOR_FILE}.key" "${CONNECTOR_PK_HEX}"
python3 "${DERIVE_CONNECTOR_PY}" "${CONNECTOR_STUB}" --key-file "${CONNECTOR_FILE}.key" --out "${CONNECTOR_FILE}" \
    | tee "${LOG_DIR}/06-derive-connector.log" >/dev/null
rm -f "${CONNECTOR_STUB}"
CONNECTOR_ID_HIERO="$(jq -r '.connectorId' "${CONNECTOR_FILE}")"
note "connectorId (hiero): ${CONNECTOR_ID_HIERO}"
if [[ "${CONNECTOR_ID_HIERO}" != "${CONNECTOR_ID}" ]]; then
    die "Peer connector ID differs from keccak256(publicKey || salt). Check the peer deployment, key and salt."
fi

# ─── [5/6] deploy PassThroughAuth ─────────────────────────────────────────
header "[5/6] deploy PassThroughAuth on ${NET}"
yh "contracts create PassThroughAuth" "${LOG_DIR}/08-create-pta.log" \
    -n "${NET}" -p "${PAYER}" contracts create \
        --init-code-file "${PASS_THROUGH_AUTH}" \
        --gas 500000 --memo "pass-through connector auth" --immutable
CONNECTOR_CONTRACT="$(capture_contract_id "${LOG_DIR}/08-create-pta.log")"
[[ -n "${CONNECTOR_CONTRACT}" ]] || die "could not capture PassThroughAuth contract id"

# ─── [6/6] create-connector on Hiero ────────────────────────────────────
header "[6/6] create-connector on ${NET}"
yh "create-connector" "${LOG_DIR}/09-create-connector.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr create-connector --channel-id "${CHANNEL_ID}" \
        --identity "${CONNECTOR_FILE}" \
        --connector-contract "${CONNECTOR_CONTRACT}" \
        --locked-stake "${LOCKED_STAKE}"

# ─── Summary ──────────────────────────────────────────────────────────────
header "Done"
cat <<EOF
  Besu side (from clpr-smart-contracts/deployments/${CHAIN_ID}/):
    CLPR service      : ${CLPR_SERVICE}
    Channel id     : ${CHANNEL_ID}
    Connector id      : ${CONNECTOR_ID}

  Hiero side (this script):
    Network            : ${NET}
    Verifier contract  : ${VERIFIER_CONTRACT}
    Connector contract : ${CONNECTOR_CONTRACT}
    Connector bundle   : ${CONNECTOR_FILE}

  Per-step logs        : ${LOG_DIR}/
EOF
