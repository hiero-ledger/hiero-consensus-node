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
#   - Peer chain id for the trust anchor  (← channel.json's peerChainId)
# Uses the SAME secp256k1 keys as CreateChannel.s.sol / CreateConnector.s.sol
# so the on-chain commitments match without hand-edits.
#
# REQUIRED env:
#   VERIFIER_CONTRACT   id (0.0.X) of a pre-deployed Besu-QBFT verifier on this
#                       Hiero network. There is no `deploy-besu-verifier` yahcli
#                       subcommand yet, so this is supplied externally.
#
# Optional env (defaults shown):
#   SMART_CONTRACTS_REPO  ../../../../../clpr-smart-contracts  (sibling repo)
#   CHAIN_ID              auto-detect from $SMART_CONTRACTS_REPO/deployments/
#   PEER_PK               0x00…00A11CE   (CreateChannel.s.sol default)
#   CONNECTOR_PK          0x00…00C044EC  (CreateConnector.s.sol default)
#   PEER_CHAIN_ID         read from channel.json's peerChainId
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

# Layout: SCRIPT_DIR = <hiero-repo>/hedera-node/yahcli/scripts/clpre2e
# Walk up 4 levels to land on the hiero repo root (so REPO_ROOT/hedera-node exists).
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

# yahcli launcher + gen-channel-identity helper live two levels up (the yahcli/
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
PEER_PK_HEX="${PEER_PK:-0x00000000000000000000000000000000000000000000000000000000000a11ce}"
CONNECTOR_PK_HEX="${CONNECTOR_PK:-0x0000000000000000000000000000000000000000000000000000000000c044ec}"

PASS_THROUGH_AUTH="${PASS_THROUGH_AUTH:-${REPO_ROOT}/hedera-node/test-clients/src/main/resources/contract/contracts/PassThroughAuth/PassThroughAuth.bin}"
# ledger-config.json ships with yahcli (run-clpr-end-to-end.sh writes it).
CONFIG_LOCAL="${CONFIG_LOCAL:-${YAHCLI_DIR}/ledger-config.json}"

# Scratch + log dirs stay next to this script — they're per-run artifacts, no
# reason to put them inside the yahcli dir where they'd collide with other tooling.
CHANNEL_FILE="${SCRIPT_DIR}/.bridge/channel.json"
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
[[ -x "${SCRIPT_DIR}/gen-channel-identity.sh" ]] || die "missing gen-channel-identity.sh in ${SCRIPT_DIR}"
[[ -f "${CONFIG_LOCAL}" ]]            || die "missing local ledger-config: ${CONFIG_LOCAL}"
[[ -f "${PASS_THROUGH_AUTH}" ]]       || die "missing PassThroughAuth.bin at ${PASS_THROUGH_AUTH}"
[[ -n "${VERIFIER_CONTRACT:-}" ]]     || die "VERIFIER_CONTRACT env var is required (id of pre-deployed Besu-QBFT verifier on this Hiero net)"

for tool in jq cast python3; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool not in PATH"
done

CONFIG_PAYLOAD_PY="${TOOLS_DIR}/build-besu-qbft-config-payload.py"
SIGN_CONNECTOR_PY="${TOOLS_DIR}/sign-clpr-connector-identity.py"
[[ -f "${CONFIG_PAYLOAD_PY}" ]]  || die "missing ${CONFIG_PAYLOAD_PY}"
[[ -f "${SIGN_CONNECTOR_PY}" ]]  || die "missing ${SIGN_CONNECTOR_PY}"

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
PEER_CHAIN_ID="${PEER_CHAIN_ID:-$(jq -r '.peerChainId' "${CHANNEL_JSON}")}"
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

# Validator address = address of the deployer key. On dev Besu the deployer
# is the QBFT validator; in prod you'd override with the real validator.
VALIDATOR="${VALIDATOR:-$(cast wallet address --private-key "${PRIVATE_KEY}" 2>/dev/null || true)}"
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
echo "  PEER_CHAIN_ID  : ${PEER_CHAIN_ID}"
echo "  PEER_PK        : ${PEER_PK_HEX}"
echo "  CONNECTOR_PK   : ${CONNECTOR_PK_HEX}"
ok "Besu state resolved"

# ─── [1/9] Push local Hiero ledger configuration ──────────────────────────
header "[1/9] update-ledger-configuration on ${NET}"
yh "update-ledger-configuration on ${NET}" "${LOG_DIR}/01-update-cfg.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr update-ledger-configuration --config-file "${CONFIG_LOCAL}"

# ─── [2/9] Build Besu QBFT config payload → config-proof hex ──────────────
header "[2/9] build Besu QBFT config proof (with throttles + seed endpoints from on-chain)"

# Fetch the live LedgerConfiguration from Besu. cast renders it as a nested
# tuple, including the throttles 7-tuple and the seed-endpoints list. The
# Python helper below shells the parsing because regex over arbitrarily-nested
# tuples in pure bash is fragile.
step "cast call getLedgerConfiguration() on ${BESU_RPC}"
LEDGER_CFG_RAW="$(cast call "${CLPR_SERVICE}" \
    'getLedgerConfiguration()((uint32,string,bytes,uint96,(uint64,uint64,uint64,uint64,uint64,uint64,uint64),(string,uint32,bytes,bytes,bytes)[]))' \
    --rpc-url "${BESU_RPC}" 2>/dev/null || true)"
[[ -n "${LEDGER_CFG_RAW}" ]] || die "cast call getLedgerConfiguration() returned nothing — Besu down or wrong address?"

# Derive the deployer's uncompressed secp256k1 public key for use as the
# fallback seed-endpoint signing key when Besu's endpoints list is empty
# (typical for the dev e2e setup, which deploys CLPR but never calls
# registerEndpoint). Hiero needs at least one endpoint with a valid signing
# key so the verifier can authenticate inbound bundles.
DEPLOYER_PUBKEY="$(cast wallet public-key --private-key "${PRIVATE_KEY}" 2>/dev/null || true)"

# Allow overrides for the synthetic endpoint (used only if Besu has no
# real endpoints registered). Default port 9545 matches the local
# clpr-evm-endpoint relay's gRPC listener (see clpr-evm-endpoint/script/
# run-from-state.sh — same value the relay binds to), so the bridge points
# the Hiero peer at a real, reachable local endpoint by default.
FALLBACK_EP_IP="${FALLBACK_EP_IP:-127.0.0.1}"
FALLBACK_EP_PORT="${FALLBACK_EP_PORT:-9545}"
FALLBACK_EP_KEY="${FALLBACK_EP_KEY:-${DEPLOYER_PUBKEY}}"

LEDGER_CFG_JSON="${SCRIPT_DIR}/.bridge/besu-ledger-config.json"
python3 - "${LEDGER_CFG_RAW}" "${FALLBACK_EP_IP}" "${FALLBACK_EP_PORT}" "${FALLBACK_EP_KEY}" "${LEDGER_CFG_JSON}" <<'PYEOF'
"""Parse cast's tuple rendering of getLedgerConfiguration() and dump a JSON
file matching the shape build-besu-qbft-config-payload.py expects under
--config-json. Synthesizes a fallback seed endpoint when Besu's list is empty.
"""
import json, re, sys

raw, fb_ip, fb_port, fb_key, out_path = sys.argv[1:6]

# cast wraps numeric values >2**53 with "[1.779e18]" scientific-notation hints;
# strip them so the rest of the parsing only sees the bare integers.
cleaned = re.sub(r'\s*\[[^\]]+\]', '', raw)

cfg = re.search(r'^\(\s*\d+\s*,\s*"[^"]*"\s*,\s*(0x[0-9a-fA-F]*)\s*,', cleaned)
if not cfg:
    print('ERROR: could not find serviceAddress in cast output', file=sys.stderr)
    sys.exit(2)
service_address = cfg.group(1)

# Throttles: first 7-element parenthesized tuple of integers after the timestamp.
# Matches "(100, 10, 1024, 1000000, 1000, 1048576, 0)" anywhere in the string.
m = re.search(
    r'\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)',
    cleaned)
if not m:
    print('ERROR: could not find throttles 7-tuple in cast output', file=sys.stderr)
    sys.exit(2)
throttles = {
    "max_messages_per_bundle":   int(m.group(1)),
    "max_syncs_per_sec":         int(m.group(2)),
    "max_message_payload_bytes": int(m.group(3)),
    "max_gas_per_message":       int(m.group(4)),
    "max_queue_depth":           int(m.group(5)),
    "max_sync_bytes":            int(m.group(6)),
    "max_bundles_per_sec":       int(m.group(7)),
}

# Seed endpoints: the trailing `[...]` after the throttles tuple. We don't try
# to deep-parse non-empty arrays here — the dev e2e flow always leaves it empty,
# and the synthetic endpoint below is sufficient for that case. Operators with
# real on-chain endpoints can pre-build their own --config-json and skip this
# script's auto-fetch path.
endpoints_section = cleaned[m.end():]
endpoints = []
if '[]' not in endpoints_section:
    print('WARNING: on-chain endpoints is non-empty; this script only auto-handles the empty case. '
          'Falling back to synthetic endpoint anyway.', file=sys.stderr)

if not endpoints:
    key = fb_key.strip()
    if key.startswith('0x') or key.startswith('0X'):
        key = key[2:]
    endpoints = [{
        "ip_address": fb_ip,
        "port": int(fb_port),
        "tls_certificate": "",
        "ecdsa_signing_key": "0x" + key,
        "account_id": "",
    }]
    print(f'note: synthesized 1 fallback seed endpoint at {fb_ip}:{fb_port} '
          f'with deployer-pubkey signing key (use FALLBACK_EP_* env to override)',
          file=sys.stderr)

with open(out_path, 'w') as f:
    json.dump({"service_address": service_address, "throttles": throttles, "endpoints": endpoints}, f, indent=2)
print(f'wrote {out_path}', file=sys.stderr)
PYEOF

note "ledger-config JSON: ${LEDGER_CFG_JSON}"

ONCHAIN_SERVICE_ADDRESS="$(jq -r '.service_address // "0x"' "${LEDGER_CFG_JSON}")"
ONCHAIN_SERVICE_ADDRESS_LC="$(printf '%s' "${ONCHAIN_SERVICE_ADDRESS}" | tr '[:upper:]' '[:lower:]')"
CLPR_SERVICE_LC="$(printf '%s' "${CLPR_SERVICE}" | tr '[:upper:]' '[:lower:]')"
if [[ "${ONCHAIN_SERVICE_ADDRESS_LC}" != "${CLPR_SERVICE_LC}" ]]; then
    note "Besu config serviceAddress is ${ONCHAIN_SERVICE_ADDRESS}; updating slot 23 to ${CLPR_SERVICE}"
    THROTTLES_ARG="$(jq -r '.throttles | "(\(.max_messages_per_bundle),\(.max_syncs_per_sec),\(.max_message_payload_bytes),\(.max_gas_per_message),\(.max_queue_depth),\(.max_sync_bytes),\(.max_bundles_per_sec))"' "${LEDGER_CFG_JSON}")"
    step "cast send updateLedgerConfiguration(serviceAddress=${CLPR_SERVICE})"
    cast send "${CLPR_SERVICE}" \
        'updateLedgerConfiguration(bytes,(uint64,uint64,uint64,uint64,uint64,uint64,uint64),(string,uint32,bytes,bytes,bytes)[],bytes,bytes)' \
        "${CLPR_SERVICE}" \
        "${THROTTLES_ARG}" \
        "[]" \
        "0x" \
        "0x" \
        --private-key "${PRIVATE_KEY}" \
        --rpc-url "${BESU_RPC}" \
        | tee "${LOG_DIR}/02a-update-besu-ledger-config.log" >/dev/null
    ok "updated Besu ledger configuration serviceAddress"
fi

step "python3 build-besu-qbft-config-payload.py --config-json ${LEDGER_CFG_JSON} (+ headers/proofs)"
PAYLOAD_OUT="$(python3 "${CONFIG_PAYLOAD_PY}" \
    --rpc-url "${BESU_RPC}" \
    --service "${CLPR_SERVICE}" \
    --chain-id "${PEER_CHAIN_ID}" \
    --protocol-version "${PROTOCOL_VERSION}" \
    --config-json "${LEDGER_CFG_JSON}" \
    --block-tag "${CONFIG_BLOCK_TAG}" \
    --out-payload "${QBFT_CONFIG_PAYLOAD}" 2>&1)"
echo "${PAYLOAD_OUT}" | tee "${LOG_DIR}/02-qbft-config-payload.log" >/dev/null
CONFIG_PROOF_HEX="$(echo "${PAYLOAD_OUT}" | awk '/^qbft_ledger_configuration_payload / { getline; print; exit }')"
[[ -n "${CONFIG_PROOF_HEX}" ]] || die "could not extract qbft_ledger_configuration_payload hex from config-payload tool"
note "config-proof payload: ${QBFT_CONFIG_PAYLOAD}"
note "config-proof hex (${#CONFIG_PROOF_HEX} chars): ${CONFIG_PROOF_HEX:0:60}…"

# ─── [3/9] Generate channel identity bundle ────────────────────────────
header "[3/9] generate channel identity (using PEER_PK from smart-contracts side)"
"${SCRIPT_DIR}/gen-channel-identity.sh" \
    --channel-id "${CHANNEL_ID}" \
    --priv    "${PEER_PK_HEX}" \
    --out     "${CHANNEL_FILE}" >/dev/null
CHANNEL_COMMIT="$(jq -r '.ownershipCommitment' "${CHANNEL_FILE}")"
[[ -n "${CHANNEL_COMMIT}" && "${CHANNEL_COMMIT}" != "null" ]] || die "no ownershipCommitment in ${CHANNEL_FILE}"
note "ownershipCommitment: ${CHANNEL_COMMIT}"

# ─── [4/9] register-channel on Hiero ───────────────────────────────────
header "[4/9] register-channel on ${NET}"
yh "register-channel" "${LOG_DIR}/04-register-conn.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr register-channel --commitment "${CHANNEL_COMMIT}"

# ─── [5/9] complete-channel on Hiero ───────────────────────────────────
header "[5/9] complete-channel on ${NET} via verifier ${VERIFIER_CONTRACT}"
yh_allow_status "CLPR_CHANNEL_ALREADY_EXISTS" "complete-channel" "${LOG_DIR}/05-complete-conn.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr complete-channel \
        --identity "${CHANNEL_FILE}" \
        --verifier-contract "${VERIFIER_CONTRACT}" \
        --config-proof-hex "${CONFIG_PROOF_HEX}"

# ─── [6/9] Build connector identity bundle (using CONNECTOR_PK + salt=0) ─
header "[6/9] generate connector identity (using CONNECTOR_PK from smart-contracts side)"
cat > "${CONNECTOR_FILE}" <<JSON
{
  "channelId":   "${CHANNEL_ID}",
  "privateKey":     "${CONNECTOR_PK_HEX}",
  "signatureScheme":"ECDSA_SECP256K1",
  "salt":           "0x0000000000000000000000000000000000000000000000000000000000000000"
}
JSON
python3 "${SIGN_CONNECTOR_PY}" --in-place "${CONNECTOR_FILE}" \
    | tee "${LOG_DIR}/06-sign-connector.log" >/dev/null
CONNECTOR_COMMIT="$(jq -r '.commitment'  "${CONNECTOR_FILE}")"
CONNECTOR_ID_HIERO="$(jq -r '.connectorId' "${CONNECTOR_FILE}")"
[[ -n "${CONNECTOR_COMMIT}" && "${CONNECTOR_COMMIT}" != "null" ]] || die "no commitment in ${CONNECTOR_FILE}"
note "connectorId (hiero): ${CONNECTOR_ID_HIERO}"
note "commitment:          ${CONNECTOR_COMMIT}"
if [[ "${CONNECTOR_ID_HIERO}" != "${CONNECTOR_ID}" ]]; then
    note "${YELLOW}warn:${RESET} connectorId derived here ≠ value in smart-contracts/connector.json"
    note "       Hiero will accept this, but it diverges from Besu's view."
fi

# ─── [7/9] register-connector on Hiero ────────────────────────────────────
header "[7/9] register-connector on ${NET}"
yh "register-connector" "${LOG_DIR}/07-register-connector.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr register-connector --commitment "${CONNECTOR_COMMIT}"

# ─── [8/9] deploy PassThroughAuth ─────────────────────────────────────────
header "[8/9] deploy PassThroughAuth on ${NET}"
yh "contracts create PassThroughAuth" "${LOG_DIR}/08-create-pta.log" \
    -n "${NET}" -p "${PAYER}" contracts create \
        --init-code-file "${PASS_THROUGH_AUTH}" \
        --gas 500000 --memo "pass-through connector auth" --immutable
CONNECTOR_CONTRACT="$(capture_contract_id "${LOG_DIR}/08-create-pta.log")"
[[ -n "${CONNECTOR_CONTRACT}" ]] || die "could not capture PassThroughAuth contract id"

# ─── [9/9] complete-connector on Hiero ────────────────────────────────────
header "[9/9] complete-connector on ${NET}"
yh "complete-connector" "${LOG_DIR}/09-complete-connector.log" \
    -n "${NET}" -p "${PAYER}" \
    clpr complete-connector \
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
    Channel bundle  : ${CHANNEL_FILE}
    Connector bundle   : ${CONNECTOR_FILE}

  Per-step logs        : ${LOG_DIR}/
EOF
