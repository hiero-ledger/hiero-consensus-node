#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for the 0.75 -> 0.76 (TSS enablement, mock signatures) upgrade.
#
# There is no published 0.76 release tag, and Solo's `consensus network deploy`
# does NOT accept --local-build-path (only the upgrade command does). So we
# cannot start directly at 0.76. Instead we deploy a published 0.75 baseline
# and upgrade in place to the local 0.76 build:
#
#   1. Deploy a baseline CN network at the published v0.75.0-rc.4 release tag
#      with resources/0.75/application.properties.
#   2. Deploy a mirror node + explorer UI on top of the 0.75 baseline (importer
#      reads RECORD streams from MinIO).
#   3. Deploy a Block Node mid-chain; it verifies the mock-sig (RSA WRB) blocks
#      streamed by the CN through the RSA bootstrap roster.
#   4. Upgrade in place to the local build with resources/0.76/application.properties
#      + application.env, enabling TSS with tss.forceMockSignatures=true (the
#      0.76 "dual-write, mock signatures" state). The 0.76 properties are
#      regenerated at runtime so tss.wrapsProvingKeyDownloadUrl points at the
#      public mirror (https://builds.hedera.com/...) instead of the local nginx
#      server used by the wider cutover scripts; the shared resources/0.76/
#      file on disk stays untouched. WRAPS env (TSS_LIB_WRAPS_ARTIFACTS_PATH +
#      TSS_LIB_NUM_OF_CORES) is pre-injected so all nodes initialize the WRAPS
#      library in lockstep across the freeze-restart.
#
# Verifications after the 0.76 upgrade:
#   - local-build version on all consensus nodes
#   - WRAPS env wired, artifacts on disk, mock WRAPS proof construction in hgcaa.log
#   - Block Node still receiving + verifying mock-sig blocks (lastAvailableBlock advances)
#
# Block Node and mirror node + explorer are always deployed in this script — no toggles.

set -euo pipefail
set +m

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../../" && pwd)"

SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-solo-075-to-076}"
SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-075-to-076}"
SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-setup}"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3,node4}"

# Initial deploy pulls a real published binary at this release tag.
# Solo's `consensus network deploy` does not accept --local-build-path.
DEPLOY_RELEASE_TAG="${DEPLOY_RELEASE_TAG:-v0.75.0-rc.4}"

# Target version for the 0.76 upgrade.
#   Blank (default): upgrade in place to the LOCAL build at LOCAL_BUILD_PATH
#     (--local-build-path). Solo still requires --upgrade-version to be a published
#     tag; we reuse DEPLOY_RELEASE_TAG as that label.
#   Non-blank: upgrade to the published Solo tag named here (no --local-build-path).
UPGRADE_TAG="${UPGRADE_TAG:-}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"

# The CN downloads + extracts the WRAPS proving-key archive itself from this URL during the
# upgrade (tss.wrapsProvingKeyDownloadUrl + tss.wrapsProvingKeyDownloadEnabled=true).
WRAPS_ARTIFACTS_DOWNLOAD_URL="${WRAPS_ARTIFACTS_DOWNLOAD_URL:-https://builds.hedera.com/tss/hiero/wraps/v1.0/wraps-v1.0.0.tar.gz}"
WRAPS_REQUIRED_FILE_COUNT="${WRAPS_REQUIRED_FILE_COUNT:-4}"
# Cap the WRAPS (Nova/rayon) prover's thread pool to limit its off-heap memory during the genesis
# ceremony. Injected as TSS_LIB_NUM_OF_CORES in lockstep with the WRAPS artifacts path (before the
# upgrade) so all nodes init WRAPS identically. Without it the prover grabs every host CPU, and with
# multiple nodes proving concurrently the off-heap peak dominates RAM. Capping trades genesis-proof
# speed for much lower peak memory. Default 3 keeps node_count x cores within the ~15 visible CPUs at
# 4 nodes (4 x 3 = 12), avoiding oversubscription. Set empty (or 0) to use all cores.
WRAPS_NUM_CORES="${WRAPS_NUM_CORES:-3}"

APP_PROPS_075_FILE="${APP_PROPS_075_FILE:-${SCRIPT_DIR}/resources/0.75/application.properties}"
APP_PROPS_076_FILE="${APP_PROPS_076_FILE:-${SCRIPT_DIR}/resources/0.76/application.properties}"
APP_ENV_076_FILE="${APP_ENV_076_FILE:-${SCRIPT_DIR}/resources/0.76/application.env}"
LOG4J2_XML_PATH="${LOG4J2_XML_PATH:-${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml}"
HAPI_PATH="/opt/hgcapp/services-hedera/HapiApp2.0"
WRAPS_ARTIFACTS_CONTAINER_DIR_DEFAULT="${HAPI_PATH}/data/keys/wraps"

SOLO_UPGRADE_TIMEOUT_SECS="${SOLO_UPGRADE_TIMEOUT_SECS:-1800}"

# Local port-forward + operator key used by the post-upgrade tx nudge that
# pushes consensus rounds past the genesis WRAPS CRS-adoption stall.
CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"
NUDGE_TX_COUNT="${NUDGE_TX_COUNT:-5}"

# --- Block Node config -----------------------------------------------------------------
BLOCK_NODE_ID="${BLOCK_NODE_ID:-1}"
BLOCK_NODE_REPO_PATH="${BLOCK_NODE_REPO_PATH:-${REPO_ROOT}/../hiero-block-node}"
BLOCK_NODE_CHART_VERSION="${BLOCK_NODE_CHART_VERSION:-v0.35.0}"
BLOCK_NODE_PRIORITY_MAPPING="${BLOCK_NODE_PRIORITY_MAPPING:-}"
BLOCK_NODE_READY_TIMEOUT_SECS="${BLOCK_NODE_READY_TIMEOUT_SECS:-600}"
BLOCK_NODE_GRPC_PORT="${BLOCK_NODE_GRPC_PORT:-40840}"
BLOCK_NODE_GRPC_LOCAL_PORT="${BLOCK_NODE_GRPC_LOCAL_PORT:-40840}"
BLOCK_NODE_VALUES_FILE="${BLOCK_NODE_VALUES_FILE:-}"
# earliestManagedBlock: must sit ABOVE the CN's current block-stream block number when the
# BN joins mid-chain. Auto-computed (current max block + margin) unless set explicitly.
BLOCK_NODE_CUTOVER_START_BLOCK="${BLOCK_NODE_CUTOVER_START_BLOCK:-}"
BLOCK_NODE_START_BLOCK_MARGIN="${BLOCK_NODE_START_BLOCK_MARGIN:-20}"
# RSA roster-bootstrap env (BN >= 0.34). Unused in v0.34.0-rc1 (plugin jar not shipped) and
# this script has no mirror REST exposed inside the BN's reach, so the base URL is left empty.
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL:-}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS:-5}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS:-10}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE:-100}"

# Mirror node + explorer. The explorer is only a UI over the mirror node's REST API, so this
# script deploys BOTH (matching the full e2e script).
MIRROR_RESTJAVA_MEMORY_REQUEST="${MIRROR_RESTJAVA_MEMORY_REQUEST:-512Mi}"
MIRROR_RESTJAVA_MEMORY_LIMIT="${MIRROR_RESTJAVA_MEMORY_LIMIT:-1000Mi}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
MIRROR_REST_SERVICE="${MIRROR_REST_SERVICE:-mirror-1-rest}"
EXPLORER_INGRESS_LOCAL_PORT="${EXPLORER_INGRESS_LOCAL_PORT:-38080}"
EXPLORER_INGRESS_SERVICE_NAME="${EXPLORER_INGRESS_SERVICE_NAME:-hiero-explorer-1-solo}"
SOLO_MIRROR_DEPLOY_TIMEOUT_SECS="${SOLO_MIRROR_DEPLOY_TIMEOUT_SECS:-900}"
SOLO_EXPLORER_DEPLOY_TIMEOUT_SECS="${SOLO_EXPLORER_DEPLOY_TIMEOUT_SECS:-600}"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/solo-e2e-075-to-076.XXXXXX")"
NUDGE_SCRIPT="${WORK_DIR}/nudge-consensus.js"
CN_PORT_FORWARD_LOG="${WORK_DIR}/cn-port-forward.log"
RSA_BOOTSTRAP_ROSTER_FILE="${WORK_DIR}/rsa-bootstrap-roster.json"
BLOCK_NODE_CUTOVER_VALUES_FILE="${WORK_DIR}/block-node-values.yaml"
MIRROR_NODE_VALUES_FILE="${WORK_DIR}/mirror-node-values.yaml"
APP_PROPS_076_GENERATED_FILE="${WORK_DIR}/application-076-public-wraps.properties"
MIRROR_PORT_FORWARD_PID=""
EXPLORER_INGRESS_PORT_FORWARD_PID=""

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command not found: $1" >&2
    exit 1
  }
}

cleanup() {
  local ec=$?
  # The network is always kept: this script never tears down the kind cluster, and leaves the
  # mirror REST / explorer UI port-forwards up so everything stays reachable for inspection
  # (incl. on a failed exit). In CI the workflow's "Always Destroy Kind Cluster" step handles
  # teardown at the end of the run; locally, remove it yourself when done with:
  #   kind delete cluster -n "${SOLO_CLUSTER_NAME}"
  # Only the throwaway temp work dir is cleaned up here.
  rm -rf "${WORK_DIR}" >/dev/null 2>&1 || true
  exit "${ec}"
}
trap cleanup EXIT

validate_local_build_path() {
  local base="$1"
  [[ -f "${base}/apps/HederaNode.jar" ]] || return 1
  [[ -d "${base}/lib" ]] || return 1
}

wait_for_consensus_pods_ready() {
  local timeout_secs="${1:-600}"
  local node nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    log "Waiting for network-${node}-0 to become Ready"
    kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/network-${node}-0" --timeout="${timeout_secs}s"
  done
}

wait_for_haproxy_ready() {
  local timeout_secs="${1:-600}"
  local node nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    log "Waiting for haproxy-${node} rollout to become ready"
    kubectl -n "${SOLO_NAMESPACE}" rollout status "deployment/haproxy-${node}" --timeout="${timeout_secs}s"
  done
}

configured_wraps_artifacts_container_dir() {
  local configured=""
  configured="$(sed -n 's/^TSS_LIB_WRAPS_ARTIFACTS_PATH=//p' "${APP_ENV_076_FILE}" | head -n 1)"
  if [[ -n "${configured}" ]]; then
    printf '%s\n' "${configured}"
  else
    printf '%s\n' "${WRAPS_ARTIFACTS_CONTAINER_DIR_DEFAULT}"
  fi
}

# Writes a copy of the shared 0.76 application.properties with tss.wrapsProvingKeyDownloadUrl
# switched to the public WRAPS URL (the local nginx server is not used in this script). The
# shared resources/0.76/application.properties is left untouched so the wider cutover scripts
# that rely on the local nginx URL still work.
generate_076_application_properties_with_public_wraps_url() {
  sed -E "s#^tss.wrapsProvingKeyDownloadUrl=.*#tss.wrapsProvingKeyDownloadUrl=${WRAPS_ARTIFACTS_DOWNLOAD_URL}#" \
    "${APP_PROPS_076_FILE}" > "${APP_PROPS_076_GENERATED_FILE}"
  log "Generated 0.76 application.properties with public WRAPS URL at ${APP_PROPS_076_GENERATED_FILE}"
}

# Pre-inject TSS_LIB_WRAPS_ARTIFACTS_PATH into every consensus StatefulSet's
# container spec BEFORE Solo's 0.76 upgrade. Solo's --application-env drops the
# env file onto disk but the container entrypoint never sources it, so the JVM
# never sees it. `kubectl set env statefulset/...` is the only path that
# reliably reaches the JVM's /proc/<pid>/environ AND survives subsequent
# kubectl-delete-pod restarts. Doing this BEFORE the upgrade ensures all nodes
# initialize WRAPS in lockstep during the freeze-restart; injecting AFTER
# triggers independent rolling restarts that produce SELF_ISS failures.
inject_wraps_env_into_statefulsets() {
  local node sts log_file wraps_dir
  local nodes=()
  wraps_dir="$(configured_wraps_artifacts_container_dir)"
  log_file="${WORK_DIR}/inject-wraps-env.log"
  : > "${log_file}"

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  local -a wraps_env_args=("TSS_LIB_WRAPS_ARTIFACTS_PATH=${wraps_dir}")
  if [[ "${WRAPS_NUM_CORES}" =~ ^[1-9][0-9]*$ ]]; then
    wraps_env_args+=("TSS_LIB_NUM_OF_CORES=${WRAPS_NUM_CORES}")
  fi
  log "Injecting ${wraps_env_args[*]} into ${#nodes[@]} consensus StatefulSets (log: ${log_file})"

  for node in "${nodes[@]}"; do
    sts="network-${node}"
    {
      echo "=== set env statefulset/${sts} ==="
      kubectl -n "${SOLO_NAMESPACE}" set env "statefulset/${sts}" -c root-container \
        "${wraps_env_args[@]}" 2>&1
    } >> "${log_file}"
  done

  for node in "${nodes[@]}"; do
    sts="network-${node}"
    printf '  injecting env into statefulset/%s... ' "${sts}"
    if {
        echo "=== rollout status statefulset/${sts} ==="
        kubectl -n "${SOLO_NAMESPACE}" rollout status "statefulset/${sts}" --timeout=600s 2>&1
      } >> "${log_file}"; then
      echo "rolled out"
    else
      echo "FAILED (see ${log_file})"
      return 1
    fi
  done
}

local_build_implementation_version() {
  unzip -p "${LOCAL_BUILD_PATH}/apps/HederaNode.jar" META-INF/MANIFEST.MF 2>/dev/null \
    | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1
}

consensus_pod_implementation_version() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "unzip -p /opt/hgcapp/services-hedera/HapiApp2.0/data/apps/HederaNode.jar META-INF/MANIFEST.MF 2>/dev/null \
      | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1"
}

verify_local_build_on_consensus_nodes() {
  local node pod
  local nodes=()
  local local_version="" pod_version=""

  local_version="$(local_build_implementation_version)"
  [[ -n "${local_version}" ]] || { echo "Unable to determine local build version for verification" >&2; return 1; }

  log "Verifying local-build version on each consensus node (expected ${local_version})"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    pod_version="$(consensus_pod_implementation_version "${pod}" || true)"
    if [[ "${pod_version}" == "${local_version}" ]]; then
      echo "  ${pod}: ${pod_version} OK"
    else
      echo "  ${pod}: expected ${local_version}, found ${pod_version:-unknown}" >&2
      return 1
    fi
  done
}

consensus_pod_wraps_env() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "pid=\$(pgrep -f 'com.hedera.node.app.ServicesMain' | head -n 1);
     if [ -n \"\${pid}\" ] && [ -r \"/proc/\${pid}/environ\" ]; then
       tr '\\000' '\\n' < \"/proc/\${pid}/environ\" | sed -n 's/^TSS_LIB_WRAPS_ARTIFACTS_PATH=//p' | head -n 1
     fi" 2>/dev/null
}

consensus_pod_wraps_file_count() {
  local pod="$1"
  local wraps_dir="$2"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "find ${wraps_dir} -maxdepth 1 -type f 2>/dev/null | wc -l" 2>/dev/null | tr -d ' '
}

wraps_proof_present_in_log() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "grep -Eq 'Constructing (genesis|incremental) WRAPS proof with:' ${HAPI_PATH}/output/hgcaa.log" >/dev/null 2>&1
}

# Detect a GENUINE WRAPS failure to bail early. We deliberately do NOT match a
# bare "WRAPS library is not ready" — with the readiness-retry change that
# string also appears in the benign "Deferring <phase> output ... (will retry
# each consensus round until ready)" log line, which is transient and expected.
# We only treat the old drop-the-publication behavior as fatal: "Skipping
# publication of <phase> output: WRAPS library is not ready". If WRAPS never
# becomes ready, the deferral loops forever and the proof-construction wait
# below times out with a clear message instead.
wraps_failure_present_in_log() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "grep -Eq 'Skipping publication of [A-Z_]+ output: WRAPS library is not ready' ${HAPI_PATH}/output/hgcaa.log" >/dev/null 2>&1
}

# Submit a few cryptoCreate transactions against a CN's gRPC port to drive
# consensus rounds forward. The genesis WRAPS ceremony stalls at "All nodes
# have contributed to the CRS, waiting for final adoption" on an idle
# network — adoption needs rounds, rounds need transactions. ~3-5 txns is
# enough to push the ceremony through to proof construction.
nudge_consensus_with_transactions() {
  local pf_pid="" tx_count="${NUDGE_TX_COUNT}"

  log "Setting up CN gRPC port-forward (svc/haproxy-node1-svc → localhost:${CN_GRPC_LOCAL_PORT})"
  pkill -f "port-forward.*haproxy-node1-svc.*${CN_GRPC_LOCAL_PORT}" >/dev/null 2>&1 || true
  sleep 1
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc \
    "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" \
    > "${CN_PORT_FORWARD_LOG}" 2>&1 < /dev/null &
  pf_pid=$!

  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    if (: </dev/tcp/127.0.0.1/"${CN_GRPC_LOCAL_PORT}") >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  if ! (: </dev/tcp/127.0.0.1/"${CN_GRPC_LOCAL_PORT}") >/dev/null 2>&1; then
    kill "${pf_pid}" >/dev/null 2>&1 || true
    echo "CN gRPC port-forward did not become reachable on localhost:${CN_GRPC_LOCAL_PORT} (log: ${CN_PORT_FORWARD_LOG})" >&2
    return 1
  fi
  log "CN gRPC reachable on 127.0.0.1:${CN_GRPC_LOCAL_PORT}"

  cat > "${NUDGE_SCRIPT}" <<'EOF'
const {
  Client,
  AccountCreateTransaction,
  Hbar,
  PrivateKey,
  Status,
} = require("@hashgraph/sdk");

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  const grpcEndpoint = process.env.GRPC_ENDPOINT || "127.0.0.1:50211";
  const operatorAccountId = process.env.OPERATOR_ACCOUNT_ID || "0.0.2";
  const operatorPrivateKey = process.env.OPERATOR_PRIVATE_KEY;
  const txCount = Number(process.env.NUDGE_TX_COUNT || "5");
  if (!operatorPrivateKey) {
    throw new Error("OPERATOR_PRIVATE_KEY is required");
  }

  const client = Client.forNetwork({ [grpcEndpoint]: "0.0.3" });
  client.setOperator(operatorAccountId, PrivateKey.fromString(operatorPrivateKey));
  client.setMaxAttempts(3);
  client.setRequestTimeout(20000);

  for (let i = 1; i <= txCount; i++) {
    const tx = new AccountCreateTransaction()
      .setInitialBalance(new Hbar(1))
      .setKey(PrivateKey.generateED25519().publicKey)
      .setMaxTransactionFee(new Hbar(5));
    const response = await tx.execute(client);
    const receipt = await response.getReceipt(client);
    if (receipt.status !== Status.Success) {
      throw new Error(`tx ${i}/${txCount}: non-success status ${receipt.status.toString()}`);
    }
    const accountId = receipt.accountId ? receipt.accountId.toString() : "(no id)";
    console.log(`  nudge tx ${i}/${txCount}: cryptoCreate -> ${accountId}`);
    await sleep(500);
  }

  await client.close();
}

main().catch((err) => {
  console.error(`nudge FAIL: ${err.message}`);
  process.exit(1);
});
EOF

  if [[ ! -d "${WORK_DIR}/node_modules/@hashgraph/sdk" ]]; then
    log "Installing @hashgraph/sdk into ${WORK_DIR} (one-time, ~30s)"
    (
      cd "${WORK_DIR}"
      npm init -y >/dev/null 2>&1
      npm install --no-fund --no-audit @hashgraph/sdk >/dev/null 2>&1
    )
  fi

  log "Submitting ${tx_count} cryptoCreate txns to drive consensus rounds"
  local rc=0
  (
    cd "${WORK_DIR}"
    GRPC_ENDPOINT="127.0.0.1:${CN_GRPC_LOCAL_PORT}" \
    OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID}" \
    OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY}" \
    NUDGE_TX_COUNT="${tx_count}" \
      node "${NUDGE_SCRIPT}"
  ) || rc=$?

  kill "${pf_pid}" >/dev/null 2>&1 || true
  pkill -f "port-forward.*haproxy-node1-svc.*${CN_GRPC_LOCAL_PORT}" >/dev/null 2>&1 || true

  return "${rc}"
}

verify_wraps_on_consensus_nodes() {
  local wraps_dir="" expected_wraps=""
  local timeout_secs="${1:-600}"
  local deadline=0
  local node pod found_env found_wraps ready_for_proof
  local nodes=()

  wraps_dir="$(configured_wraps_artifacts_container_dir)"
  expected_wraps="${WRAPS_REQUIRED_FILE_COUNT}"

  log "Verifying WRAPS runtime on each consensus node (env=${wraps_dir}, expecting >=${expected_wraps} self-downloaded artifact files, up to ${timeout_secs}s/node for env+artifacts+proof construction)"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    deadline=$((SECONDS + timeout_secs))

    ready_for_proof=false
    found_env=""
    found_wraps=""
    while (( SECONDS < deadline )); do
      if wraps_failure_present_in_log "${pod}"; then
        echo "  ${pod}: WRAPS reported a runtime failure (check ${HAPI_PATH}/output/hgcaa.log)" >&2
        return 1
      fi
      found_env="$(consensus_pod_wraps_env "${pod}" || true)"
      found_wraps="$(consensus_pod_wraps_file_count "${pod}" "${wraps_dir}" || true)"
      if [[ "${found_env}" == "${wraps_dir}" && "${found_wraps:-0}" -ge "${expected_wraps}" ]]; then
        ready_for_proof=true
        break
      fi
      sleep 5
    done

    if ! ${ready_for_proof}; then
      echo "  ${pod}: timed out waiting for WRAPS env+artifacts (env='${found_env:-unset}' wanted '${wraps_dir}'; artifacts=${found_wraps:-0}/${expected_wraps})" >&2
      return 1
    fi

    echo "  ${pod}: env + ${found_wraps} artifacts OK; waiting for 'Constructing (genesis|incremental) WRAPS proof with:' in hgcaa.log"
    local progress_tick=0
    while (( SECONDS < deadline )); do
      if wraps_failure_present_in_log "${pod}"; then
        echo "  ${pod}: WRAPS reported a runtime failure (check ${HAPI_PATH}/output/hgcaa.log)" >&2
        return 1
      fi
      if wraps_proof_present_in_log "${pod}"; then
        echo "  ${pod}: WRAPS proof construction detected"
        break
      fi
      if (( progress_tick > 0 && progress_tick % 6 == 0 )); then
        echo "    ...still waiting on ${pod} ($((deadline - SECONDS))s remaining)"
      fi
      ((progress_tick++))
      sleep 5
    done

    if ! wraps_proof_present_in_log "${pod}"; then
      echo "  ${pod}: timed out after ${timeout_secs}s waiting for WRAPS proof construction" >&2
      return 1
    fi
  done

  echo "All consensus nodes confirmed: WRAPS env wired, artifacts present, proof construction observed"
}

# Report, per consensus node, how long the CN itself spent downloading + extracting + verifying
# the WRAPS proving-key archive from tss.wrapsProvingKeyDownloadUrl. Parsed from hgcaa.log:
#   start: "WrapsProvingKeyVerification - ... Initiating download"
#   end:   "WrapsProvingKeyVerification - Successfully downloaded and verified WRAPS proving key"
# Duration is computed in pure awk (ms-of-day delta) so it works on both GNU and BSD date hosts.
report_wraps_download_times() {
  local node pod start_line end_line dur
  local nodes=()
  local hgcaa="${HAPI_PATH}/output/hgcaa.log"

  log "WRAPS proving-key self-download times (per consensus node, from hgcaa.log):"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    start_line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "grep -aE 'WrapsProvingKeyVerification - .*Initiating download' '${hgcaa}' | head -n 1" 2>/dev/null || true)"
    end_line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "grep -aE 'WrapsProvingKeyVerification - Successfully downloaded and verified WRAPS proving key' '${hgcaa}' | head -n 1" 2>/dev/null || true)"
    if [[ -z "${start_line}" ]]; then
      echo "  ${pod}: no self-download observed (proving key already present, or not started)"
      continue
    fi
    if [[ -z "${end_line}" ]]; then
      echo "  ${pod}: download started but completion not yet logged"
      continue
    fi
    # Each hgcaa.log line begins with 'YYYY-MM-DD HH:MM:SS.mmm'; split on space/colon/dot and
    # take the wall-clock-of-day delta in milliseconds (guarding a midnight rollover).
    dur="$(awk -v a="${start_line}" -v b="${end_line}" '
      function ms(t,   x){ split(t, x, /[ :.]/); return ((x[2]*3600)+(x[3]*60)+x[4])*1000 + x[5] }
      BEGIN { d = ms(b) - ms(a); if (d < 0) d += 86400000; printf "%.3f", d/1000 }')"
    echo "  ${pod}: ${dur}s"
  done
}

run_command_with_timeout() {
  local timeout_secs="$1"
  shift
  local cmd_pid="" start_ts elapsed

  "$@" &
  cmd_pid=$!
  start_ts="$(date +%s)"

  while kill -0 "${cmd_pid}" >/dev/null 2>&1; do
    elapsed=$(( $(date +%s) - start_ts ))
    if (( elapsed >= timeout_secs )); then
      log "Command exceeded timeout (${timeout_secs}s); terminating PID ${cmd_pid}"
      pkill -TERM -P "${cmd_pid}" >/dev/null 2>&1 || true
      kill -TERM "${cmd_pid}" >/dev/null 2>&1 || true
      sleep 5
      pkill -KILL -P "${cmd_pid}" >/dev/null 2>&1 || true
      kill -KILL "${cmd_pid}" >/dev/null 2>&1 || true
      wait "${cmd_pid}" >/dev/null 2>&1 || true
      return 124
    fi
    sleep 5
  done

  wait "${cmd_pid}"
}

# ======================================================================================
# Block Node machinery. Deploys a BN mid-chain on the 0.75 baseline so it verifies the
# mock-sig (RSA WRB) blocks streamed by the CN through the RSA bootstrap roster. The BN
# stays through the 0.76 upgrade (which keeps mock signatures) and continues to verify.
# ======================================================================================

kill_processes_on_local_port() {
  local port="$1"
  pkill -f "port-forward.*:${port}\b" >/dev/null 2>&1 || true
  pkill -f "port-forward.* ${port}:" >/dev/null 2>&1 || true
}

wait_for_tcp_open() {
  local host="$1" port="$2" max_attempts="$3" sleep_secs="$4"
  local attempt=1
  while (( attempt <= max_attempts )); do
    if (: < "/dev/tcp/${host}/${port}") >/dev/null 2>&1; then
      return 0
    fi
    sleep "${sleep_secs}"
    ((attempt++))
  done
  return 1
}

build_default_block_node_priority_mapping() {
  local node mapping=""
  local nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    [[ -n "${mapping}" ]] && mapping+=","
    mapping+="${node}=1"
  done
  echo "${mapping}"
}

# Build the RSA bootstrap roster JSON from each CN's gossip public key (s-public-nodeN.pem).
# Lets the BN verify the mock-signature (RSA WRB) blocks streamed before TSS goes real.
generate_rsa_bootstrap_roster_json() {
  require_cmd openssl
  local node node_idx node_id pem hex
  local nodes=()
  local cn_pod="network-node1-0"
  local entries=""

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    node_idx="${node#node}"
    node_id="$((node_idx - 1))"
    pem="$(kubectl -n "${SOLO_NAMESPACE}" exec "${cn_pod}" -c root-container -- \
      cat "${HAPI_PATH}/data/keys/s-public-node${node_idx}.pem" 2>/dev/null || true)"
    if [[ -z "${pem}" ]]; then
      echo "Failed to read s-public-node${node_idx}.pem from ${cn_pod}" >&2
      return 1
    fi
    hex="$(printf '%s' "${pem}" \
      | openssl x509 -pubkey -noout 2>/dev/null \
      | openssl pkey -pubin -outform DER 2>/dev/null \
      | od -An -v -tx1 | tr -d ' \n')"
    if [[ -z "${hex}" ]]; then
      echo "Failed to extract X.509 SPKI hex for node${node_idx}" >&2
      return 1
    fi
    [[ -n "${entries}" ]] && entries+=","
    if [[ "${node_id}" == "0" ]]; then
      entries+=$'\n    {"RSAPubKey": "'"${hex}"'"}'
    else
      entries+=$'\n    {"nodeId": "'"${node_id}"'", "RSAPubKey": "'"${hex}"'"}'
    fi
  done

  cat > "${RSA_BOOTSTRAP_ROSTER_FILE}" <<EOF
{
  "nodeAddress": [${entries}
  ]
}
EOF
  log "Generated RSA bootstrap roster (${#nodes[@]} entries): ${RSA_BOOTSTRAP_ROSTER_FILE}"
}

# Helm values: earliestManagedBlock/backfill floor + a seed-rsa-bootstrap-roster init
# container that bakes the roster JSON onto the live PVC. (See the full e2e script for the
# field-by-field rationale; this is a verbatim port.)
write_block_node_cutover_values() {
  local roster_indented
  roster_indented="$(sed 's/^/          /' "${RSA_BOOTSTRAP_ROSTER_FILE}")"

  cat > "${BLOCK_NODE_CUTOVER_VALUES_FILE}" <<EOF
blockNode:
  config:
    BLOCK_NODE_EARLIEST_MANAGED_BLOCK: "${BLOCK_NODE_CUTOVER_START_BLOCK}"
    BACKFILL_START_BLOCK: "${BLOCK_NODE_CUTOVER_START_BLOCK}"
    APP_STATE_RSA_BOOTSTRAP_FILE_PATH: "/opt/hiero/block-node/data/live/rsa-bootstrap-roster.json"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL}"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS}"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS}"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE}"
  initContainers:
    - name: init-storage-dirs
      image: busybox
      command:
        - sh
        - -c
        - |
          mkdir -p /live-pvc/live-data && \\
          chown 2000:2000 /live-pvc/live-data && \\
          chmod 700 /live-pvc/live-data && \\
          mkdir -p /archive-pvc/archive-data && \\
          chown 2000:2000 /archive-pvc/archive-data && \\
          chmod 700 /archive-pvc/archive-data && \\
          chown 2000:2000 /verification-pvc && \\
          chmod 700 /verification-pvc
      volumeMounts:
        - name: live-storage
          mountPath: /live-pvc
        - name: archive-storage
          mountPath: /archive-pvc
        - name: verification-storage
          mountPath: /verification-pvc
    - name: seed-rsa-bootstrap-roster
      image: busybox
      command:
        - sh
        - -c
        - |
          cat > /live-pvc/live-data/rsa-bootstrap-roster.json <<'ROSTER'
${roster_indented}
          ROSTER
          chown 2000:2000 /live-pvc/live-data/rsa-bootstrap-roster.json
          chmod 644 /live-pvc/live-data/rsa-bootstrap-roster.json
          echo "Seeded rsa-bootstrap-roster.json:"
          ls -la /live-pvc/live-data/rsa-bootstrap-roster.json
      volumeMounts:
        - name: live-storage
          mountPath: /live-pvc
EOF
}

deploy_block_node() {
  validate_block_node_repo || return 1
  # earliestManagedBlock must sit ABOVE the CN's current block at deploy time; the BN then
  # snaps down to whatever CN first publishes. This script produces few blocks before this
  # point, so a high constant is safely above current. Override via env if needed.
  [[ -z "${BLOCK_NODE_CUTOVER_START_BLOCK}" ]] && BLOCK_NODE_CUTOVER_START_BLOCK="1000"
  [[ -z "${BLOCK_NODE_PRIORITY_MAPPING}" ]] && BLOCK_NODE_PRIORITY_MAPPING="$(build_default_block_node_priority_mapping)"

  generate_rsa_bootstrap_roster_json || return 1
  write_block_node_cutover_values

  local add_args=(
    solo block node add
    --deployment "${SOLO_DEPLOYMENT}"
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}"
    --quiet-mode
    --priority-mapping "${BLOCK_NODE_PRIORITY_MAPPING}"
  )
  [[ -n "${BLOCK_NODE_CHART_VERSION}" ]] && add_args+=(--chart-version "${BLOCK_NODE_CHART_VERSION}")
  local values_files="${BLOCK_NODE_CUTOVER_VALUES_FILE}"
  [[ -n "${BLOCK_NODE_VALUES_FILE}" ]] && values_files="${BLOCK_NODE_VALUES_FILE},${BLOCK_NODE_CUTOVER_VALUES_FILE}"
  add_args+=(--values-file "${values_files}")

  log "Deploying Block Node ${BLOCK_NODE_ID} (earliestManagedBlock=${BLOCK_NODE_CUTOVER_START_BLOCK}, priority '${BLOCK_NODE_PRIORITY_MAPPING}')"
  "${add_args[@]}"
  kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/block-node-${BLOCK_NODE_ID}-0" --timeout="${BLOCK_NODE_READY_TIMEOUT_SECS}s"
}

validate_block_node_repo() {
  if [[ ! -d "${BLOCK_NODE_REPO_PATH}" ]]; then
    echo "BLOCK_NODE_REPO_PATH not found: ${BLOCK_NODE_REPO_PATH} (needed for the serverStatus proto)" >&2
    return 1
  fi
}

# Poll BN serverStatus until lastAvailableBlock > 0, proving CN is streaming into it.
verify_block_node_has_blocks() {
  local timeout_secs="${1:-120}"
  local svc="block-node-${BLOCK_NODE_ID}"
  local remote_port="${BLOCK_NODE_GRPC_PORT}" local_port="${BLOCK_NODE_GRPC_LOCAL_PORT}"
  local pf_log="${WORK_DIR}/port-forward-block-node-status.log" pf_pid=""
  require_cmd grpcurl
  local proto_api_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto"
  local proto_services_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/block-node-protobuf"
  local proto_file="block-node/api/node_service.proto"
  if [[ ! -f "${proto_api_root}/${proto_file}" ]]; then
    echo "verify_block_node_has_blocks: proto not found at ${proto_api_root}/${proto_file}" >&2
    return 1
  fi

  kill_processes_on_local_port "${local_port}"
  : > "${pf_log}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${svc}" "${local_port}:${remote_port}" >"${pf_log}" 2>&1 < /dev/null &
  pf_pid=$!
  disown "${pf_pid}" 2>/dev/null || true
  if ! wait_for_tcp_open "127.0.0.1" "${local_port}" 20 1; then
    kill "${pf_pid}" >/dev/null 2>&1 || true
    echo "Could not port-forward to ${svc} (see ${pf_log})" >&2
    return 1
  fi

  local deadline=$((SECONDS + timeout_secs)) last_available="" raw=""
  log "Polling ${svc} serverStatus for lastAvailableBlock > 0 (up to ${timeout_secs}s)"
  while (( SECONDS < deadline )); do
    raw="$(grpcurl -plaintext -import-path "${proto_api_root}" -import-path "${proto_services_root}" \
            -proto "${proto_file}" -d '{}' "127.0.0.1:${local_port}" \
            org.hiero.block.api.BlockNodeService/serverStatus 2>/dev/null)" || true
    last_available="$(echo "${raw}" | jq -r '.lastAvailableBlock // empty' 2>/dev/null || true)"
    if [[ "${last_available}" =~ ^[0-9]+$ && "${last_available}" -gt 0 ]]; then
      log "verify_block_node_has_blocks: lastAvailableBlock=${last_available} (firstAvailableBlock=$(echo "${raw}" | jq -r '.firstAvailableBlock // "?"'))"
      kill "${pf_pid}" >/dev/null 2>&1 || true
      return 0
    fi
    sleep 5
  done
  echo "BN ${svc} did not report lastAvailableBlock > 0 within ${timeout_secs}s (last: ${raw:-<empty>})" >&2
  kill "${pf_pid}" >/dev/null 2>&1 || true
  return 1
}

create_cluster() {
  log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true

  log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
  kind create cluster -n "${SOLO_CLUSTER_NAME}"
}

configure_solo() {
  local consensus_node_count
  consensus_node_count="$(awk -F',' '{print NF}' <<< "${NODE_ALIASES}")"

  log "Configuring Solo deployment ${SOLO_DEPLOYMENT}"
  solo cluster-ref config connect \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --context "kind-${SOLO_CLUSTER_NAME}"

  solo deployment config create \
    --namespace "${SOLO_NAMESPACE}" \
    --deployment "${SOLO_DEPLOYMENT}"

  solo deployment cluster attach \
    --deployment "${SOLO_DEPLOYMENT}" \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --num-consensus-nodes "${consensus_node_count}"
}

setup_cluster_prereqs() {
  log "Installing Solo cluster prerequisites (MinIO only)"
  solo cluster-ref config setup \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --cluster-setup-namespace "${SOLO_CLUSTER_SETUP_NAMESPACE}" \
    --minio true \
    --prometheus-stack false \
    --quiet-mode
}

deploy_baseline_075() {
  log "Deploying baseline consensus network at released ${DEPLOY_RELEASE_TAG} with 0.75 properties"

  solo keys consensus generate \
    --gossip-keys \
    --tls-keys \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}"

  solo consensus network deploy \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --application-properties "${APP_PROPS_075_FILE}" \
    --log4j2-xml "${LOG4J2_XML_PATH}" \
    --pvcs true \
    --release-tag "${DEPLOY_RELEASE_TAG}"

  solo consensus node setup \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --release-tag "${DEPLOY_RELEASE_TAG}"

  solo consensus node start \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --force-port-forward false

  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
}

# ======================================================================================
# Mirror node + explorer. The explorer is a UI over the mirror node's REST API, so both
# are deployed together (matching the full e2e script).
# ======================================================================================
deployment_ready() {
  local deployment="$1"
  local timeout_secs="${2:-5}"
  kubectl -n "${SOLO_NAMESPACE}" rollout status "deployment/${deployment}" --timeout="${timeout_secs}s" >/dev/null 2>&1
}

required_mirror_services_ready() {
  local deployment
  for deployment in mirror-1-rest mirror-1-grpc mirror-1-importer mirror-1-monitor mirror-1-web3; do
    deployment_ready "${deployment}" 5 || return 1
  done
}

wait_for_required_mirror_services_ready() {
  local timeout_secs="${1:-600}" start_ts
  start_ts="$(date +%s)"
  while true; do
    if required_mirror_services_ready; then
      return 0
    fi
    if (( $(date +%s) - start_ts >= timeout_secs )); then
      return 1
    fi
    sleep 5
  done
}

# `solo mirror node add` can report failure solely because REST Java is slow to become ready,
# even though the services needed for ingestion + REST are up. Distinguish that case.
mirror_node_failed_only_on_restjava() {
  kubectl -n "${SOLO_NAMESPACE}" get deployment mirror-1-restjava >/dev/null 2>&1 || return 1
  required_mirror_services_ready || return 1
  deployment_ready mirror-1-restjava 5 && return 1
  return 0
}

write_mirror_node_values_override() {
  cat > "${MIRROR_NODE_VALUES_FILE}" <<EOF
restjava:
  resources:
    requests:
      memory: ${MIRROR_RESTJAVA_MEMORY_REQUEST}
    limits:
      memory: ${MIRROR_RESTJAVA_MEMORY_LIMIT}
EOF
}

# Minimal curl-based HTTP readiness wait.
wait_for_http_ok_simple() {
  local url="$1" max_attempts="$2" sleep_secs="$3" attempt=1
  while (( attempt <= max_attempts )); do
    if curl -sf -o /dev/null "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep "${sleep_secs}"
    attempt=$((attempt + 1))
  done
  return 1
}

start_mirror_rest_port_forward() {
  kubectl -n "${SOLO_NAMESPACE}" get svc "${MIRROR_REST_SERVICE}" >/dev/null 2>&1 || {
    echo "mirror REST service not found: ${SOLO_NAMESPACE}/${MIRROR_REST_SERVICE}" >&2; return 1
  }
  kill_processes_on_local_port "${MIRROR_REST_LOCAL_PORT}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${MIRROR_REST_SERVICE}" \
    "${MIRROR_REST_LOCAL_PORT}:http" >/dev/null 2>&1 < /dev/null &
  MIRROR_PORT_FORWARD_PID="$!"
  disown "${MIRROR_PORT_FORWARD_PID}" 2>/dev/null || true
  wait_for_tcp_open "127.0.0.1" "${MIRROR_REST_LOCAL_PORT}" 20 1
}

start_explorer_ingress_port_forward() {
  kubectl -n "${SOLO_NAMESPACE}" get svc "${EXPLORER_INGRESS_SERVICE_NAME}" >/dev/null 2>&1 || {
    echo "explorer service not found: ${SOLO_NAMESPACE}/${EXPLORER_INGRESS_SERVICE_NAME}" >&2; return 1
  }
  kill_processes_on_local_port "${EXPLORER_INGRESS_LOCAL_PORT}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${EXPLORER_INGRESS_SERVICE_NAME}" \
    "${EXPLORER_INGRESS_LOCAL_PORT}:80" >/dev/null 2>&1 < /dev/null &
  EXPLORER_INGRESS_PORT_FORWARD_PID="$!"
  disown "${EXPLORER_INGRESS_PORT_FORWARD_PID}" 2>/dev/null || true
  if wait_for_tcp_open "127.0.0.1" "${EXPLORER_INGRESS_LOCAL_PORT}" 20 1; then
    log "Explorer UI available at http://127.0.0.1:${EXPLORER_INGRESS_LOCAL_PORT}"
    return 0
  fi
  return 1
}

# Deploys a mirror node (with ingress) and the explorer UI, then opens local port-forwards.
# Returns non-zero only if the mirror node or explorer could not be deployed at all; transient
# REST-Java readiness and port-forward issues are downgraded to warnings.
deploy_mirror_and_explorer() {
  log "=== Deploying mirror node + explorer ==="
  write_mirror_node_values_override
  if ! run_command_with_timeout "${SOLO_MIRROR_DEPLOY_TIMEOUT_SECS}" \
      solo mirror node add --deployment "${SOLO_DEPLOYMENT}" --enable-ingress \
      --values-file "${MIRROR_NODE_VALUES_FILE}"; then
    if mirror_node_failed_only_on_restjava; then
      log "Mirror node add failed only on REST Java readiness; required mirror services are up — continuing"
    else
      log "Mirror node add failed; waiting up to 600s for required mirror services"
      wait_for_required_mirror_services_ready 600 || {
        echo "mirror: required services did not become ready" >&2; return 1
      }
    fi
  fi
  if ! run_command_with_timeout "${SOLO_EXPLORER_DEPLOY_TIMEOUT_SECS}" \
      solo explorer node add --deployment "${SOLO_DEPLOYMENT}"; then
    echo "explorer: 'solo explorer node add' failed" >&2; return 1
  fi
  start_explorer_ingress_port_forward || log "WARN: explorer UI tunnel unavailable; explorer may be inaccessible"
  start_mirror_rest_port_forward || log "WARN: mirror REST tunnel unavailable on localhost:${MIRROR_REST_LOCAL_PORT}"
  if wait_for_http_ok_simple "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1" 36 5; then
    log "Mirror REST is serving blocks; explorer UI at http://127.0.0.1:${EXPLORER_INGRESS_LOCAL_PORT}"
  else
    log "WARN: mirror REST /api/v1/blocks not OK yet; explorer data may lag (importer still catching up)"
  fi
}

# The focus of this script: the 0.75 -> 0.76 upgrade with TSS enabled in mock-signature mode.
# Solo's `consensus network deploy` does not accept --local-build-path, so we deploy a published
# 0.75 baseline first and then upgrade in place — to the local 0.76 build by default, or to a
# published Solo tag when UPGRADE_TAG is set.
upgrade_to_local_076() {
  generate_076_application_properties_with_public_wraps_url
  inject_wraps_env_into_statefulsets

  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --application-properties "${APP_PROPS_076_GENERATED_FILE}"
    --application-env "${APP_ENV_076_FILE}"
    --quiet-mode
    --force
  )
  if [[ -n "${UPGRADE_TAG}" ]]; then
    log "=== 0.76 upgrade: upgrade to published tag ${UPGRADE_TAG} with 0.76 properties (TSS, mock signatures) ==="
    upgrade_cmd+=(--upgrade-version "${UPGRADE_TAG}")
  else
    log "=== 0.76 upgrade: upgrade to local build at ${LOCAL_BUILD_PATH} (label ${DEPLOY_RELEASE_TAG}) with 0.76 properties (TSS, mock signatures) ==="
    upgrade_cmd+=(--upgrade-version "${DEPLOY_RELEASE_TAG}" --local-build-path "${LOCAL_BUILD_PATH}")
  fi
  run_command_with_timeout "${SOLO_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"

  log "--- 0.76 check 1/3: wait for consensus pods + haproxy + verify upgrade target ---"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  if [[ -z "${UPGRADE_TAG}" ]]; then
    verify_local_build_on_consensus_nodes
  else
    log "Skipping local-build version check (UPGRADE_TAG=${UPGRADE_TAG}); Solo's ACTIVE gate is the upgrade success signal"
  fi

  log "--- 0.76 check 2/3: nudge consensus with cryptoCreate txns (genesis WRAPS ceremony needs rounds) ---"
  nudge_consensus_with_transactions

  log "--- 0.76 check 3/3: verify WRAPS runtime + proof construction on every consensus node ---"
  verify_wraps_on_consensus_nodes 600

  report_wraps_download_times
}

log "Validating prerequisites"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd curl
require_cmd unzip
require_cmd node
require_cmd npm
require_cmd jq
require_cmd openssl
require_cmd grpcurl
validate_block_node_repo || {
  echo "BLOCK_NODE_REPO_PATH is invalid: ${BLOCK_NODE_REPO_PATH}" >&2
  echo "Set BLOCK_NODE_REPO_PATH to a hiero-block-node checkout." >&2
  exit 1
}

[[ -f "${APP_PROPS_075_FILE}" ]] || { echo "Missing file: ${APP_PROPS_075_FILE}" >&2; exit 1; }
[[ -f "${APP_PROPS_076_FILE}" ]] || { echo "Missing file: ${APP_PROPS_076_FILE}" >&2; exit 1; }
[[ -f "${APP_ENV_076_FILE}" ]] || { echo "Missing file: ${APP_ENV_076_FILE}" >&2; exit 1; }
[[ -f "${LOG4J2_XML_PATH}" ]] || { echo "Missing file: ${LOG4J2_XML_PATH}" >&2; exit 1; }

if [[ -z "${UPGRADE_TAG}" ]]; then
  validate_local_build_path "${LOCAL_BUILD_PATH}" || {
    echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}" >&2
    echo "Expected ${LOCAL_BUILD_PATH}/apps/HederaNode.jar and ${LOCAL_BUILD_PATH}/lib" >&2
    echo "(Set UPGRADE_TAG to a published Solo tag to skip the local-build requirement.)" >&2
    exit 1
  }
fi

create_cluster
configure_solo
setup_cluster_prereqs
deploy_baseline_075

# Deploy mirror node + explorer on the 0.75 baseline FIRST (before the BN) so the importer is
# wired to read RECORD streams from MinIO (the CN writes record streams in 0.75/0.76). If the BN
# were deployed first, Solo would auto-wire it as the importer's only block source and the
# importer would stall trying to fetch block 0 from a mid-chain BN.
deploy_mirror_and_explorer

# Deploy the BN mid-chain on the 0.75 baseline so it verifies the mock-sig (RSA WRB) blocks
# via the bootstrap roster through the 0.76 phase (which keeps mock signatures). We do NOT
# verify it has blocks pre-upgrade: on this non-jumpstarted network the CN starts at genesis,
# so the BN's earliestManagedBlock would sit above the CN tip and the BN never ingests. The
# post-upgrade check below is the BN gate.
log "=== Deploying Block Node ${BLOCK_NODE_ID} ==="
deploy_block_node

upgrade_to_local_076

log "--- Post-0.76 BN check: Block Node still receiving + verifying mock-sig blocks ---"
verify_block_node_has_blocks 180

log "PASS: 0.75 (${DEPLOY_RELEASE_TAG}) -> 0.76 (TSS enabled, mock signatures) upgrade completed cleanly"
log "PASS: Block Node verified the mock-sig (RSA WRB) blocks across the 0.75 -> 0.76 upgrade"
log "Explorer UI: http://127.0.0.1:${EXPLORER_INGRESS_LOCAL_PORT}    Mirror REST: http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
