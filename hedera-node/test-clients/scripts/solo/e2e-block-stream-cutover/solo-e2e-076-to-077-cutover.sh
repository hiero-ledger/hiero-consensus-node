#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Isolated reproducer for the 0.76 -> 0.77 (BLOCKS-only cutover, real TSS
# signatures) upgrade that corresponds to step 11 of
# solo-e2e-block-stream-cutover.sh. The full cutover script takes 30+ minutes
# and exercises Block Node + jumpstart + mirror node before reaching the 0.77
# cutover. This script skips all of that and goes straight to the transition we
# care about.
#
# There is no published 0.76 release tag, and Solo's `consensus network deploy`
# does NOT accept --local-build-path (only the upgrade command does). So we
# cannot start directly at 0.76. Instead we establish the 0.76 baseline the
# same way step 10 does, then perform the focused 0.77 cutover:
#
#   1. Deploy a baseline CN network at the published v0.75.0-rc.1 release tag
#      with resources/0.75/application.properties.
#   2. Upgrade in place to the local build with resources/0.76/application.properties
#      + application.env, enabling TSS with tss.forceMockSignatures=true (the
#      0.76 "dual-write, mock signatures" state). WRAPS env is pre-injected so
#      all nodes initialize the WRAPS library in lockstep.
#   3. Upgrade in place to the local build with resources/0.77/application.properties
#      — BLOCKS-only (streamMode=BLOCKS, writerMode=GRPC), real TSS signatures
#      (tss.forceMockSignatures=false), state proofs on. WRAPS env + on-disk
#      artifacts carry forward from step 2 (no re-injection, matching the main
#      script's run_077_upgrade).
#
# Verifications after the 0.77 cutover:
#   - local-build version on all nodes
#   - real (non-mock) WRAPS proof construction in hgcaa.log
#   - (optional) a node restart replays cleanly WITHOUT SELF_ISS. The cutover
#     itself comes up ACTIVE; the SELF_ISS we chased only surfaced when a node
#     restarted (e.g. OOMKilled) and replayed events past the cutover round. This
#     forced-restart check is OFF by default (happy path) — enable the failure-mode
#     reproducer with RESTART_REPLAY_CHECK=true.
#
# Optional add-ons (default on, toggle with the flags shown): a Block Node
# (ENABLE_BLOCK_NODE) for the TSS-ledger-id seeding + verification path, and a mirror
# node + explorer UI (ENABLE_EXPLORER) for browsing blocks/transactions. Set both to
# false for the original lean CN-only ISS reproducer with no port-forwards beyond the
# transient one used to nudge consensus.

set -euo pipefail
set +m

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../../" && pwd)"

SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-solo-cutover-77}"
SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-cutover-77}"
SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-setup}"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3,node4}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

# Initial deploy pulls a real published binary at this release tag.
# Solo's `consensus network deploy` does not accept --local-build-path.
# v0.75.0-rc.3 matches the baseline the full cutover script (step 6) deploys.
DEPLOY_RELEASE_TAG="${DEPLOY_RELEASE_TAG:-v0.75.0-rc.3}"

# Both upgrades use the local build. The --upgrade-version label must point at a
# published Solo tag (Solo resolves it before applying --local-build-path), but
# the actual binary always comes from LOCAL_BUILD_PATH. Solo accepts re-using
# the same label across upgrades when --local-build-path is supplied, so we
# reuse v0.75.0-rc.3 for both the 0.76 and 0.77 upgrades.
UPGRADE_VERSION_LABEL="${UPGRADE_VERSION_LABEL:-v0.75.0-rc.3}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"

WRAPS_KEY_PATH="${WRAPS_KEY_PATH:-${HOME}/.solo/cache/wraps-v1.0.0}"
WRAPS_TARBALL_CACHE_PATH="${WRAPS_TARBALL_CACHE_PATH:-${HOME}/.solo/cache/wraps-v1.0.0.tar.gz}"
WRAPS_ARTIFACTS_DOWNLOAD_URL="${WRAPS_ARTIFACTS_DOWNLOAD_URL:-https://builds.hedera.com/tss/hiero/wraps/v1.0/wraps-v1.0.0.tar.gz}"
WRAPS_REQUIRED_FILE_COUNT="${WRAPS_REQUIRED_FILE_COUNT:-4}"
WRAPS_SERVER_PORT="${WRAPS_SERVER_PORT:-8089}"
WRAPS_SERVER_CONTAINER_NAME="${WRAPS_SERVER_CONTAINER_NAME:-wraps-proving-key-server}"
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
APP_PROPS_077_FILE="${APP_PROPS_077_FILE:-${SCRIPT_DIR}/resources/0.77/application.properties}"
LOG4J2_XML_PATH="${LOG4J2_XML_PATH:-${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml}"
HAPI_PATH="/opt/hgcapp/services-hedera/HapiApp2.0"
WRAPS_ARTIFACTS_CONTAINER_DIR_DEFAULT="${HAPI_PATH}/data/keys/wraps"

SOLO_UPGRADE_TIMEOUT_SECS="${SOLO_UPGRADE_TIMEOUT_SECS:-1800}"

# When true (default), after the 0.77 cutover we delete a CN pod to force an
# event replay and assert it comes back ACTIVE without a SELF_ISS. This is the
# actual failure mode observed in the full run (an OOMKilled node replayed past
# the cutover round and hit SELF_ISS). Set false to only test the happy path.
RESTART_REPLAY_CHECK="${RESTART_REPLAY_CHECK:-false}"
RESTART_REPLAY_NODE="${RESTART_REPLAY_NODE:-node1}"
RESTART_REPLAY_TIMEOUT_SECS="${RESTART_REPLAY_TIMEOUT_SECS:-600}"

# Local port-forward + operator key used by the post-upgrade tx nudge that
# pushes consensus rounds past the genesis WRAPS CRS-adoption stall.
CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"
NUDGE_TX_COUNT="${NUDGE_TX_COUNT:-5}"

# --- Block Node + TSS-ledger-id config (ported from the full e2e script) ---------------
# When ENABLE_BLOCK_NODE=true (default), the reproducer also deploys a Block Node before
# the 0.76 step, seeds it with the network's TSS ledger id before the 0.77 cutover, and
# asserts the BN verifies + persists the real-TSS-signed post-cutover blocks. Set false to
# get the original lean CN-only ISS reproducer.
ENABLE_BLOCK_NODE="${ENABLE_BLOCK_NODE:-true}"
MINIO_NAMESPACE="${MINIO_NAMESPACE:-${SOLO_NAMESPACE}}"
MINIO_BUCKET="${MINIO_BUCKET:-solo-streams}"
BLOCK_NODE_ID="${BLOCK_NODE_ID:-1}"
BLOCK_NODE_REPO_PATH="${BLOCK_NODE_REPO_PATH:-${REPO_ROOT}/../hiero-block-node}"
BLOCK_NODE_CHART_VERSION="${BLOCK_NODE_CHART_VERSION:-v0.34.0-rc1}"
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
# this reproducer has no mirror node, so the base URL is left empty.
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL:-}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS:-5}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS:-10}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE:-100}"

# Mirror node + explorer. The explorer is only a UI over the mirror node's REST API, so enabling it
# deploys BOTH a mirror node and the explorer (matching the full e2e script). This adds memory load
# (postgres + mirror rest/grpc/importer/web3/monitor + the explorer UI); set ENABLE_EXPLORER=false
# to keep the lean stack. Deployment is best-effort: failures warn but do not abort the core
# 0.76 -> 0.77 / BN cutover test.
ENABLE_EXPLORER="${ENABLE_EXPLORER:-true}"
MIRROR_RESTJAVA_MEMORY_REQUEST="${MIRROR_RESTJAVA_MEMORY_REQUEST:-512Mi}"
MIRROR_RESTJAVA_MEMORY_LIMIT="${MIRROR_RESTJAVA_MEMORY_LIMIT:-1000Mi}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
MIRROR_REST_SERVICE="${MIRROR_REST_SERVICE:-mirror-1-rest}"
EXPLORER_INGRESS_LOCAL_PORT="${EXPLORER_INGRESS_LOCAL_PORT:-38080}"
EXPLORER_INGRESS_SERVICE_NAME="${EXPLORER_INGRESS_SERVICE_NAME:-hiero-explorer-1-solo}"
SOLO_MIRROR_DEPLOY_TIMEOUT_SECS="${SOLO_MIRROR_DEPLOY_TIMEOUT_SECS:-900}"
SOLO_EXPLORER_DEPLOY_TIMEOUT_SECS="${SOLO_EXPLORER_DEPLOY_TIMEOUT_SECS:-600}"
# Block-cutover for the mirror importer. The MN is deployed in 0.75 reading RECORD files from
# MinIO (the CN writes record streams in 0.75/0.76). After the 0.77 cutover (BLOCKS-only,
# gRPC-only — no MinIO files), the importer is reconfigured to read 0.77 blocks from the Block
# Node. Per Mirror Node guidance: in v0.154 hiero.mirror.importer.block.enabled defaults FALSE so
# it must be set true, and there is NO block.cutover.hapiVersion — the importer auto-switches to
# blockstream whenever no record-stream file is processed within block.cutover.threshold (16s
# default), which is exactly what happens at the 0.77 cutover. Once it reads one block from the BN
# the switch is permanent. (hapiVersion only exists in 0.155+; leave the var empty on 0.154.)
MIRROR_NODE_VERSION="${MIRROR_NODE_VERSION:-v0.154.0}"
MIRROR_BLOCK_CUTOVER_HAPIVERSION="${MIRROR_BLOCK_CUTOVER_HAPIVERSION:-}"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/solo-e2e-076-to-077.XXXXXX")"
NUDGE_SCRIPT="${WORK_DIR}/nudge-consensus.js"
CN_PORT_FORWARD_LOG="${WORK_DIR}/cn-port-forward.log"
CLUSTER_CREATED_THIS_RUN="false"
RSA_BOOTSTRAP_ROSTER_FILE="${WORK_DIR}/rsa-bootstrap-roster.json"
BLOCK_NODE_CUTOVER_VALUES_FILE="${WORK_DIR}/block-node-cutover-values.yaml"
LEDGER_ID_EXTRACTOR_DIR="${WORK_DIR}/ledgerid-extractor"
LEDGER_ID_EXTRACTOR_SRC="${LEDGER_ID_EXTRACTOR_DIR}/extract_ledger_id_publication.py"
BN_TSS_PARAMS_LOCAL="${WORK_DIR}/tss-parameters.bin"
BN_BLOCK_FILES_DIR="${WORK_DIR}/bn-block-files"
BN_TSS_PARAMS_CONTAINER_PATH="${BN_TSS_PARAMS_CONTAINER_PATH:-/opt/hiero/block-node/verification/tss-parameters.bin}"
MIRROR_NODE_VALUES_FILE="${WORK_DIR}/mirror-node-values.yaml"
MIRROR_NODE_CUTOVER_VALUES_FILE="${WORK_DIR}/mirror-node-block-cutover-values.yaml"
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

stop_wraps_proving_key_server() {
  if command -v docker >/dev/null 2>&1; then
    docker rm -f "${WRAPS_SERVER_CONTAINER_NAME}" >/dev/null 2>&1 || true
  fi
}

cleanup() {
  local ec=$?
  if [[ "${KEEP_NETWORK}" != "true" && "${CLUSTER_CREATED_THIS_RUN}" == "true" ]]; then
    # Only tear down the WRAPS proving-key server + the mirror REST/explorer UI port-forwards when we
    # are actually deleting the cluster. When the cluster is kept (KEEP_NETWORK=true, incl. on a
    # failed exit), leave them up so the network stays fully functional (CNs keep fetching the WRAPS
    # proving key) and the explorer stays reachable for inspection.
    stop_wraps_proving_key_server
    [[ -n "${MIRROR_PORT_FORWARD_PID}" ]] && kill "${MIRROR_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    [[ -n "${EXPLORER_INGRESS_PORT_FORWARD_PID}" ]] && kill "${EXPLORER_INGRESS_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  fi
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

# Cache the WRAPS tarball alongside the extracted dir so the local nginx server
# can serve it without re-downloading. Mirrors ensure_wraps_artifacts_downloaded
# in the main cutover script.
ensure_wraps_artifacts_downloaded() {
  local file_count="" tmp_dir="" extract_dir="" extracted_root=""
  local extracted_dirs="" extracted_entries=""

  if [[ -d "${WRAPS_KEY_PATH}" ]]; then
    file_count="$(find "${WRAPS_KEY_PATH}" -maxdepth 1 -type f | wc -l | tr -d ' ')"
    if [[ "${file_count}" -ge "${WRAPS_REQUIRED_FILE_COUNT}" && -f "${WRAPS_TARBALL_CACHE_PATH}" ]]; then
      log "Using cached WRAPS artifacts from ${WRAPS_KEY_PATH}"
      return 0
    fi
  fi

  mkdir -p "$(dirname "${WRAPS_TARBALL_CACHE_PATH}")"
  if [[ ! -f "${WRAPS_TARBALL_CACHE_PATH}" ]]; then
    log "Downloading WRAPS artifacts from ${WRAPS_ARTIFACTS_DOWNLOAD_URL}"
    curl -fL "${WRAPS_ARTIFACTS_DOWNLOAD_URL}" -o "${WRAPS_TARBALL_CACHE_PATH}.partial"
    mv "${WRAPS_TARBALL_CACHE_PATH}.partial" "${WRAPS_TARBALL_CACHE_PATH}"
  fi

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/solo-wraps-extract.XXXXXX")"
  extract_dir="${tmp_dir}/extract"
  mkdir -p "${extract_dir}"
  tar -xzf "${WRAPS_TARBALL_CACHE_PATH}" -C "${extract_dir}"

  extracted_root="${extract_dir}"
  extracted_dirs="$(find "${extract_dir}" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
  extracted_entries="$(find "${extract_dir}" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')"
  if [[ "${extracted_dirs}" == "1" && "${extracted_entries}" == "1" ]]; then
    extracted_root="$(find "${extract_dir}" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  fi

  mkdir -p "$(dirname "${WRAPS_KEY_PATH}")"
  rm -rf "${WRAPS_KEY_PATH}"
  mkdir -p "${WRAPS_KEY_PATH}"
  find "${extracted_root}" -maxdepth 1 -type f -exec cp '{}' "${WRAPS_KEY_PATH}/" ';'
  rm -rf "${tmp_dir}"
}

ensure_wraps_proving_key_server() {
  local server_url
  server_url="http://127.0.0.1:${WRAPS_SERVER_PORT}/$(basename "${WRAPS_TARBALL_CACHE_PATH}")"

  if curl -sfI "${server_url}" >/dev/null 2>&1; then
    log "Wraps proving key server already serving ${server_url}"
    return 0
  fi

  require_cmd docker
  if [[ ! -f "${WRAPS_TARBALL_CACHE_PATH}" ]]; then
    echo "Wraps tarball cache not found: ${WRAPS_TARBALL_CACHE_PATH}" >&2
    return 1
  fi

  log "Starting wraps proving key server (nginx Docker on port ${WRAPS_SERVER_PORT})"
  WRAPS_TAR_PATH="${WRAPS_TARBALL_CACHE_PATH}" \
  WRAPS_SERVER_PORT="${WRAPS_SERVER_PORT}" \
  WRAPS_SERVER_CONTAINER_NAME="${WRAPS_SERVER_CONTAINER_NAME}" \
    "${SCRIPT_DIR}/start-wraps-proving-key-server.sh"
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

iss_present_in_log() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "grep -Eq 'ISS detected|is CATASTROPHIC_FAILURE' ${HAPI_PATH}/output/hgcaa.log" >/dev/null 2>&1
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
  expected_wraps="$(find "${WRAPS_KEY_PATH}" -maxdepth 1 -type f | wc -l | tr -d ' ')"
  [[ "${expected_wraps}" -ge "${WRAPS_REQUIRED_FILE_COUNT}" ]] || {
    echo "Expected at least ${WRAPS_REQUIRED_FILE_COUNT} WRAPS artifacts in ${WRAPS_KEY_PATH}, found ${expected_wraps}" >&2
    return 1
  }

  log "Verifying WRAPS runtime on each consensus node (env=${wraps_dir}, expecting ${expected_wraps} extracted files, up to ${timeout_secs}s/node for env+artifacts+proof construction)"
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
      if [[ "${found_env}" == "${wraps_dir}" && "${found_wraps}" == "${expected_wraps}" ]]; then
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

# Delete a CN pod after the cutover to force a fresh JVM start + event replay,
# then assert it returns to Ready without logging a SELF_ISS / CATASTROPHIC_FAILURE.
# This reproduces the real failure mode: in the full run, an OOMKilled node
# replayed events past the 0.77 cutover round and computed a divergent state
# hash (SELF_ISS) right after the WrapsHistoryProver AGGREGATE phase.
verify_node_replays_without_iss() {
  local node="${RESTART_REPLAY_NODE}"
  local timeout_secs="${RESTART_REPLAY_TIMEOUT_SECS}"
  local pod="network-${node}-0"

  if [[ "${RESTART_REPLAY_CHECK}" != "true" ]]; then
    log "Skipping restart-replay ISS check (RESTART_REPLAY_CHECK=${RESTART_REPLAY_CHECK})"
    return 0
  fi

  if iss_present_in_log "${pod}"; then
    echo "FAIL: ${pod} already shows an ISS/CATASTROPHIC_FAILURE before the restart check" >&2
    return 1
  fi

  log "Restart-replay check: deleting ${pod} to force a post-cutover event replay"
  kubectl -n "${SOLO_NAMESPACE}" delete pod "${pod}" --wait=true >/dev/null 2>&1 || true

  # Wait for the StatefulSet to recreate the pod object before polling exec/status.
  local create_deadline=$((SECONDS + 120))
  while (( SECONDS < create_deadline )); do
    if kubectl -n "${SOLO_NAMESPACE}" get pod "${pod}" >/dev/null 2>&1; then
      break
    fi
    sleep 3
  done

  log "Watching ${pod} replay (up to ${timeout_secs}s): pass = Ready, fail = ISS/CATASTROPHIC_FAILURE"
  local deadline=$((SECONDS + timeout_secs))
  local became_ready=false
  while (( SECONDS < deadline )); do
    # Fail fast if the node logs an ISS / catastrophic failure during replay.
    if iss_present_in_log "${pod}"; then
      echo "FAIL: ${pod} hit ISS/CATASTROPHIC_FAILURE during post-cutover replay" >&2
      kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
        "grep -nE 'ISS detected|is CATASTROPHIC_FAILURE' ${HAPI_PATH}/output/hgcaa.log | tail -5" >&2 2>/dev/null || true
      return 1
    fi
    if [[ "$(kubectl -n "${SOLO_NAMESPACE}" get pod "${pod}" \
              -o jsonpath='{.status.containerStatuses[?(@.name=="root-container")].ready}' 2>/dev/null)" == "true" ]]; then
      became_ready=true
      break
    fi
    sleep 5
  done

  if ! ${became_ready}; then
    echo "FAIL: ${pod} did not become Ready within ${timeout_secs}s after restart (possible replay stall)" >&2
    return 1
  fi

  # ISS can fire late in replay; give it a moment after Ready and re-scan.
  sleep 15
  if iss_present_in_log "${pod}"; then
    echo "FAIL: ${pod} logged an ISS/CATASTROPHIC_FAILURE shortly after returning Ready" >&2
    return 1
  fi

  log "${pod} replayed cleanly after restart — no SELF_ISS"
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
# Block Node + TSS-ledger-id machinery (ported from solo-e2e-block-stream-cutover.sh).
# Lets this fast reproducer also exercise: BN verification of mock-sig (RSA WRB) blocks
# during 0.76, seeding the BN with the network's TSS ledger id, and BN verification +
# persistence of the real-TSS-signed blocks produced after the 0.77 cutover.
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
    if nc -z "${host}" "${port}" >/dev/null 2>&1; then
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
# Lets the BN verify the mock-signature (RSA WRB) blocks streamed before the 0.77 cutover.
generate_rsa_bootstrap_roster_json() {
  require_cmd openssl
  require_cmd xxd
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
      | xxd -p | tr -d '\n')"
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
  # snaps down to whatever CN first publishes. The reproducer produces few blocks before
  # this point, so a high constant is safely above current. Override via env if needed.
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

minio_discover_pod_credentials() {
  local ns="$1" pod u p cfg
  pod="$(kubectl -n "${ns}" get pods -o json 2>/dev/null | jq -r '.items[].metadata.name | select(test("^minio-"))' | head -n 1)"
  [[ -n "${pod}" ]] || return 1
  # shellcheck disable=SC2016
  cfg="$(kubectl -n "${ns}" exec "${pod}" -c minio -- sh -c 'cat "${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}" 2>/dev/null || true' 2>/dev/null || true)"
  u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_USER=//p' | head -1 | tr -d '"\r')"
  p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_PASSWORD=//p' | head -1 | tr -d '"\r')"
  if [[ -z "${u}" || -z "${p}" ]]; then
    u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ACCESS_KEY=//p' | head -1 | tr -d '"\r')"
    p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_SECRET_KEY=//p' | head -1 | tr -d '"\r')"
  fi
  [[ -n "${u}" && -n "${p}" ]] || return 1
  printf '%s\n' "${u}" "${p}"
}

# Dependency-free Python extractor: walks the protobuf wire format of a block-stream file
# and slices out the serialized LedgerIdPublicationTransactionBody (Block.items[1] ->
# BlockItem.signed_transaction[4] -> SignedTransaction.bodyBytes[1] ->
# TransactionBody.ledger_id_publication[77]) — the form the BN expects at
# verification.tssParametersFilePath.
write_ledger_id_extractor() {
  mkdir -p "${LEDGER_ID_EXTRACTOR_DIR}"
  cat > "${LEDGER_ID_EXTRACTOR_SRC}" <<'EOF'
#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import sys
import gzip


def read_varint(buf, pos):
    shift = 0
    result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


def iter_fields(msg):
    pos = 0
    n = len(msg)
    while pos < n:
        key, pos = read_varint(msg, pos)
        fnum = key >> 3
        wtype = key & 0x07
        if wtype == 0:
            val, pos = read_varint(msg, pos)
            yield fnum, wtype, val
        elif wtype == 1:
            yield fnum, wtype, msg[pos : pos + 8]
            pos += 8
        elif wtype == 2:
            length, pos = read_varint(msg, pos)
            yield fnum, wtype, msg[pos : pos + length]
            pos += length
        elif wtype == 5:
            yield fnum, wtype, msg[pos : pos + 4]
            pos += 4
        else:
            return


def find_field(msg, field_num):
    for fnum, wtype, val in iter_fields(msg):
        if fnum == field_num and wtype == 2:
            return val
    return None


def extract_from_block(data):
    for fnum, wtype, val in iter_fields(data):
        if fnum != 1 or wtype != 2:
            continue
        stx = find_field(val, 4)
        if stx is None:
            continue
        body = find_field(stx, 1)
        if body is None:
            continue
        pub = find_field(body, 77)
        if pub is not None:
            return pub
    return None


def main():
    if len(sys.argv) < 3:
        sys.stderr.write("usage: extract.py <out.bin> <blockFile.blk[.gz]> [...]\n")
        sys.exit(2)
    out = sys.argv[1]
    for path in sys.argv[2:]:
        try:
            with open(path, "rb") as f:
                raw = f.read()
            data = gzip.decompress(raw) if path.endswith(".gz") else raw
            pub = extract_from_block(data)
            if pub is not None:
                with open(out, "wb") as o:
                    o.write(pub)
                print("FOUND ledgerIdPublication in %s -> wrote %d bytes to %s" % (path, len(pub), out))
                return
        except Exception as e:  # noqa: BLE001
            sys.stderr.write("skip %s: %s\n" % (path, e))
    sys.stderr.write("No ledgerIdPublication found in any input block file\n")
    sys.exit(1)


if __name__ == "__main__":
    main()
EOF
}

# Bootstrap the BN with the network's TSS ledger id so it can verify real-TSS-signed
# blocks after the 0.77 cutover. The BN only self-learns the ledger id from block 0; this
# mid-chain BN never sees it, so we extract the LedgerIdPublication (published during 0.76)
# from a MinIO .blk.gz, drop it into the BN's tssParametersFilePath, and roll the BN.
seed_block_node_tss_parameters() {
  require_cmd python3
  local minio_pod creds_tmp u p in_pod_dir bn_pod
  minio_pod="$(kubectl -n "${MINIO_NAMESPACE}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name | select(test("^minio-"))' | head -n 1)"
  [[ -n "${minio_pod}" ]] || { echo "seed: no MinIO pod in ${MINIO_NAMESPACE}" >&2; return 1; }

  creds_tmp="$(mktemp)"
  if ! minio_discover_pod_credentials "${MINIO_NAMESPACE}" >"${creds_tmp}"; then
    rm -f "${creds_tmp}"; echo "seed: could not discover MinIO credentials" >&2; return 1
  fi
  u="$(sed -n '1p' "${creds_tmp}")"; p="$(sed -n '2p' "${creds_tmp}")"; rm -f "${creds_tmp}"

  in_pod_dir="/tmp/bn-blk-$$"
  log "Copying block-stream files from MinIO ${MINIO_BUCKET}/blockStreams via in-pod mc"
  if ! kubectl -n "${MINIO_NAMESPACE}" exec "${minio_pod}" -c minio -- sh -c \
      "rm -rf '${in_pod_dir}'; mkdir -p '${in_pod_dir}'; \
       mc alias set local 'http://minio-hl.${MINIO_NAMESPACE}.svc.cluster.local:9000' '${u}' '${p}' >/dev/null 2>&1; \
       mc cp --recursive 'local/${MINIO_BUCKET}/blockStreams/' '${in_pod_dir}/' >/dev/null 2>&1"; then
    echo "seed: in-pod mc cp of blockStreams failed" >&2; return 1
  fi
  rm -rf "${BN_BLOCK_FILES_DIR}"; mkdir -p "${BN_BLOCK_FILES_DIR}"
  # Copy each block file out with a tar-free `kubectl exec ... cat` (kubectl cp shells out
  # to tar, which the distroless MinIO image does not ship). Stdout without a TTY is a raw
  # binary stream, so .blk.gz bytes survive intact.
  local rel base dest pulled=0
  while IFS= read -r rel; do
    [[ -n "${rel}" ]] || continue
    base="$(basename "${rel}")"
    dest="${BN_BLOCK_FILES_DIR}/${base}"
    kubectl -n "${MINIO_NAMESPACE}" exec "${minio_pod}" -c minio -- cat "${rel}" > "${dest}" 2>/dev/null
    if [[ -s "${dest}" ]]; then ((pulled++)); else rm -f "${dest}"; fi
  done < <(kubectl -n "${MINIO_NAMESPACE}" exec "${minio_pod}" -c minio -- sh -c \
            "for f in ${in_pod_dir}/*/*.blk.gz ${in_pod_dir}/*.blk.gz; do [ -f \"\$f\" ] && echo \"\$f\"; done" 2>/dev/null)
  kubectl -n "${MINIO_NAMESPACE}" exec "${minio_pod}" -c minio -- sh -c "rm -rf '${in_pod_dir}'" >/dev/null 2>&1 || true

  local blk_files=()
  while IFS= read -r bf; do blk_files+=("${bf}"); done < <(find "${BN_BLOCK_FILES_DIR}" -type f -name '*.blk.gz' | sort)
  [[ "${#blk_files[@]}" -gt 0 ]] || { echo "seed: no .blk.gz files retrieved from MinIO (pulled=${pulled})" >&2; return 1; }
  log "Retrieved ${#blk_files[@]} block-stream files; extracting LedgerIdPublication"

  write_ledger_id_extractor
  rm -f "${BN_TSS_PARAMS_LOCAL}"
  if ! python3 "${LEDGER_ID_EXTRACTOR_SRC}" "${BN_TSS_PARAMS_LOCAL}" "${blk_files[@]}"; then
    echo "seed: no LedgerIdPublication found in block stream (was it published during 0.76?)" >&2; return 1
  fi
  [[ -s "${BN_TSS_PARAMS_LOCAL}" ]] || { echo "seed: extracted tss-parameters.bin is empty" >&2; return 1; }

  bn_pod="block-node-${BLOCK_NODE_ID}-0"
  log "Seeding ${bn_pod}:${BN_TSS_PARAMS_CONTAINER_PATH} and rolling the Block Node"
  kubectl -n "${SOLO_NAMESPACE}" exec "${bn_pod}" -- sh -lc "mkdir -p '$(dirname "${BN_TSS_PARAMS_CONTAINER_PATH}")'" >/dev/null 2>&1 || true
  # Tar-free push (kubectl cp needs tar in the target container): stream the file into the
  # pod's cat via stdin.
  if ! kubectl -n "${SOLO_NAMESPACE}" exec -i "${bn_pod}" -- sh -lc "cat > '${BN_TSS_PARAMS_CONTAINER_PATH}'" < "${BN_TSS_PARAMS_LOCAL}"; then
    echo "seed: streaming tss-parameters into ${bn_pod} failed" >&2; return 1
  fi
  kubectl -n "${SOLO_NAMESPACE}" delete pod "${bn_pod}" --wait=true >/dev/null 2>&1 || true
  # Wait on the StatefulSet rollout, NOT `wait pod/<name>`: a `wait --for=condition=ready pod/<name>`
  # can match the OLD pod (still Ready during graceful termination) and return before the new pod
  # exists, which is what made the marker poll below race and false-negative. `rollout status` blocks
  # until the freshly-recreated pod is actually up.
  kubectl -n "${SOLO_NAMESPACE}" rollout status "statefulset/block-node-${BLOCK_NODE_ID}" --timeout=300s >/dev/null 2>&1 || true
  kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/${bn_pod}" --timeout=300s >/dev/null 2>&1 || {
    echo "seed: ${bn_pod} did not become ready after roll" >&2; return 1
  }
  # Authoritative success signal: the seeded file survived the roll into the running pod. (kubectl cp
  # needs tar, which the distroless BN image lacks, so verify presence + non-empty via exec test.)
  if ! kubectl -n "${SOLO_NAMESPACE}" exec "${bn_pod}" -- sh -lc "test -s '${BN_TSS_PARAMS_CONTAINER_PATH}'" >/dev/null 2>&1; then
    echo "seed: ${BN_TSS_PARAMS_CONTAINER_PATH} missing or empty in ${bn_pod} after roll" >&2; return 1
  fi
  # Confirmation: the BN logs "Loaded TSS parameters from file" during init. `kubectl logs` can
  # briefly return a transitioning container right after the roll, so poll generously. An explicit
  # parse/load failure is fatal; but if we merely never observe the marker (a logs-cutover race)
  # while the file is present and the pod is Ready, continue with a warning —
  # verify_block_node_persists_post_cutover is the real gate on whether the BN verifies the blocks.
  sleep 5
  local deadline=$((SECONDS + 180))
  local bn_logs=""
  while (( SECONDS < deadline )); do
    bn_logs="$(kubectl -n "${SOLO_NAMESPACE}" logs "${bn_pod}" 2>/dev/null)"
    if grep -q "Loaded TSS parameters from file" <<<"${bn_logs}"; then
      log "Block Node loaded TSS parameters from seeded file — ready to verify real-TSS blocks"
      return 0
    fi
    if grep -qiE "failed to (load|parse|read).*tss|invalid tss parameters|tss parameters.*(error|corrupt)" <<<"${bn_logs}"; then
      echo "seed: BN reported a TSS parameters load failure:" >&2
      grep -iE "tss" <<<"${bn_logs}" | tail -5 >&2
      return 1
    fi
    sleep 3
  done
  log "WARN seed: did not observe 'Loaded TSS parameters from file' in ${bn_pod} after polling ~180s, but the seeded file is present and the pod is Ready (likely a kubectl-logs cutover race); continuing — post-cutover BN verification will gate this"
  return 0
}

# After the 0.77 cutover, assert the BN VERIFIES + PERSISTS the real-TSS-signed blocks.
# Reads serverStatus.lastAvailableBlock twice over a window and requires it to advance —
# if the BN were rejecting the real-TSS blocks (the pre-seed failure mode), it would be
# stuck and lastAvailableBlock would not move. Also surfaces any recent 'Verification
# failed' log lines on failure for diagnosis.
verify_block_node_persists_post_cutover() {
  local timeout_secs="${1:-300}"
  local bn_pod="block-node-${BLOCK_NODE_ID}-0"
  local svc="block-node-${BLOCK_NODE_ID}"
  local remote_port="${BLOCK_NODE_GRPC_PORT}" local_port="${BLOCK_NODE_GRPC_LOCAL_PORT}"
  local pf_log="${WORK_DIR}/port-forward-bn-postcutover.log" pf_pid=""
  require_cmd grpcurl
  local proto_api_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto"
  local proto_services_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/block-node-protobuf"
  local proto_file="block-node/api/node_service.proto"

  read_bn_last_available() {
    local raw
    raw="$(grpcurl -plaintext -import-path "${proto_api_root}" -import-path "${proto_services_root}" \
            -proto "${proto_file}" -d '{}' "127.0.0.1:${local_port}" \
            org.hiero.block.api.BlockNodeService/serverStatus 2>/dev/null)" || true
    echo "${raw}" | jq -r '.lastAvailableBlock // empty' 2>/dev/null || true
  }

  kill_processes_on_local_port "${local_port}"
  : > "${pf_log}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${svc}" "${local_port}:${remote_port}" >"${pf_log}" 2>&1 < /dev/null &
  pf_pid=$!
  disown "${pf_pid}" 2>/dev/null || true
  wait_for_tcp_open "127.0.0.1" "${local_port}" 20 1 || { kill "${pf_pid}" >/dev/null 2>&1 || true; echo "post-cutover: BN port-forward failed" >&2; return 1; }

  local baseline="" current=""
  baseline="$(read_bn_last_available)"
  [[ "${baseline}" =~ ^[0-9]+$ ]] || baseline=0
  log "Asserting BN persists post-cutover blocks (baseline lastAvailableBlock=${baseline}; must climb within ${timeout_secs}s)"
  local deadline=$((SECONDS + timeout_secs))
  while (( SECONDS < deadline )); do
    sleep 10
    current="$(read_bn_last_available)"
    if [[ "${current}" =~ ^[0-9]+$ && "${current}" -gt "${baseline}" ]]; then
      log "verify_block_node_persists_post_cutover: lastAvailableBlock advanced ${baseline} -> ${current} — BN verified the real-TSS post-cutover blocks"
      kill "${pf_pid}" >/dev/null 2>&1 || true
      return 0
    fi
  done
  echo "post-cutover: BN lastAvailableBlock stuck at ${baseline} for ${timeout_secs}s — BN is NOT verifying the real-TSS blocks" >&2
  echo "  recent BN verification failures:" >&2
  kubectl -n "${SOLO_NAMESPACE}" logs "${bn_pod}" --since=10m 2>/dev/null | grep -E "Verification failed for block=" | tail -5 >&2 || true
  kill "${pf_pid}" >/dev/null 2>&1 || true
  return 1
}

# Seeding rolls the BN, which severs the CN->BN publisher stream at the BN's last block. The CN keeps
# producing on 0.76, but those blocks live only in the CN's in-memory block buffer, which RESETS when
# the 0.77 upgrade restarts the CN. If the BN hasn't re-ingested up to the live tip before that
# restart, the gap blocks are orphaned (the BN's wanted block falls below the CN's reset buffer floor,
# "block out of range") and the BN stalls forever. So after seeding, wait until the BN is actively
# advancing again (publisher reconnected + streaming the post-roll blocks) BEFORE the 0.77 cutover.
wait_for_block_node_caught_up() {
  local timeout_secs="${1:-300}"
  local svc="block-node-${BLOCK_NODE_ID}"
  local remote_port="${BLOCK_NODE_GRPC_PORT}" local_port="${BLOCK_NODE_GRPC_LOCAL_PORT}"
  local pf_log="${WORK_DIR}/port-forward-bn-catchup.log" pf_pid=""
  require_cmd grpcurl
  local proto_api_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto"
  local proto_services_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/block-node-protobuf"
  local proto_file="block-node/api/node_service.proto"
  local cn_pod="network-${NODE_ALIASES%%,*}-0"
  local comms_log="/opt/hgcapp/services-hedera/HapiApp2.0/output/block-node-comms.log"

  read_bn_last_available() {
    local raw
    raw="$(grpcurl -plaintext -import-path "${proto_api_root}" -import-path "${proto_services_root}" \
            -proto "${proto_file}" -d '{}' "127.0.0.1:${local_port}" \
            org.hiero.block.api.BlockNodeService/serverStatus 2>/dev/null)" || true
    echo "${raw}" | jq -r '.lastAvailableBlock // empty' 2>/dev/null || true
  }

  kill_processes_on_local_port "${local_port}"
  : > "${pf_log}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${svc}" "${local_port}:${remote_port}" >"${pf_log}" 2>&1 < /dev/null &
  pf_pid=$!
  disown "${pf_pid}" 2>/dev/null || true
  wait_for_tcp_open "127.0.0.1" "${local_port}" 20 1 || {
    kill "${pf_pid}" >/dev/null 2>&1 || true
    echo "catchup: BN port-forward failed" >&2; return 1
  }

  # "Caught up" = the CN considers the BN an IN-RANGE streaming target ("available for streaming
  # (wantedBlock: N)"), meaning it will keep the BN current as it produces. That holds even when the
  # CN is idle and the BN is already at the tip — so it's the correct gate (requiring the BN to keep
  # *advancing* false-fails whenever the CN produces nothing for a while). The failure case is the
  # gap: the CN reports "block out of range" (BN fell below the CN's block-buffer floor).
  log "Waiting for the CN to report the Block Node in-range for streaming after the seed roll (up to ${timeout_secs}s) before the 0.77 cutover"
  local prev="" cur cn_view
  local deadline=$((SECONDS + timeout_secs))
  while (( SECONDS < deadline )); do
    cur="$(read_bn_last_available)"
    cn_view="$(kubectl -n "${SOLO_NAMESPACE}" exec "${cn_pod}" -c root-container -- sh -c \
      "grep -aE 'available for streaming \(wantedBlock|block out of range|No block nodes available for streaming' '${comms_log}' 2>/dev/null | tail -1" 2>/dev/null || true)"
    case "${cn_view}" in
      *"available for streaming (wantedBlock"*)
        log "Block Node is caught up — CN reports it in-range for streaming (BN lastAvailableBlock=${cur:-?}); safe to cut over"
        kill "${pf_pid}" >/dev/null 2>&1 || true
        return 0
        ;;
    esac
    if [[ "${cur}" =~ ^[0-9]+$ && "${cur}" != "${prev}" ]]; then
      log "  BN lastAvailableBlock=${cur} (CN view: ${cn_view:-pending})"
      prev="${cur}"
    fi
    sleep 5
  done
  echo "WARN catchup: CN did not report the BN in-range within ${timeout_secs}s (last BN lastAvailableBlock=${prev:-?}); the 0.77 cutover may orphan blocks — consider seeding during the freeze" >&2
  kill "${pf_pid}" >/dev/null 2>&1 || true
  return 1
}

create_cluster() {
  log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true

  log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
  kind create cluster -n "${SOLO_CLUSTER_NAME}"
  CLUSTER_CREATED_THIS_RUN="true"
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

# Establish the 0.76 TSS+WRAPS baseline (mock signatures) — equivalent to
# step 10 of the main cutover script. This is the prerequisite state the 0.77
# cutover upgrades from; we cannot deploy 0.76 directly.
establish_076_baseline() {
  log "=== Establishing 0.76 baseline: upgrade to local build with 0.76 properties (TSS, mock signatures) ==="

  ensure_wraps_artifacts_downloaded
  ensure_wraps_proving_key_server
  inject_wraps_env_into_statefulsets

  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --upgrade-version "${UPGRADE_VERSION_LABEL}"
    --local-build-path "${LOCAL_BUILD_PATH}"
    --application-properties "${APP_PROPS_076_FILE}"
    --application-env "${APP_ENV_076_FILE}"
    --quiet-mode
    --force
  )
  run_command_with_timeout "${SOLO_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"

  log "--- 0.76 check 1/3: wait for consensus pods + haproxy + verify local-build version ---"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  verify_local_build_on_consensus_nodes

  log "--- 0.76 check 2/3: nudge consensus with cryptoCreate txns (genesis WRAPS ceremony needs rounds) ---"
  nudge_consensus_with_transactions

  log "--- 0.76 check 3/3: verify WRAPS runtime + proof construction on every consensus node ---"
  verify_wraps_on_consensus_nodes 600
}

# The focus of this script: the 0.76 -> 0.77 BLOCKS-only cutover with real TSS
# signatures. WRAPS env + on-disk artifacts carry forward from the 0.76 step
# (no re-injection, matching the main script's run_077_upgrade), and the local
# ======================================================================================
# Mirror node + explorer (optional, ENABLE_EXPLORER=true). The explorer is a UI over the
# mirror node's REST API, so both are deployed together. Best-effort: deployment failures
# warn but do not abort the core cutover test. Independent of the BN path (the BN uses the
# pre-seeded RSA bootstrap roster, not the mirror REST endpoint).
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

# Minimal curl-based HTTP readiness wait (the lean reproducer has no spinner helpers).
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
  log "=== Deploying mirror node + explorer (ENABLE_EXPLORER=true) ==="
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

# Writes the values file that switches the importer to read 0.77 blocks from the Block Node while
# keeping the record-stream downloader alive for the pre-cutover (0.75/0.76) blocks.
write_mirror_node_block_cutover_values() {
  # Enable block-stream ingestion and point the importer at the Block Node. block.enabled defaults
  # false in v0.154, so it MUST be set true; the importer then auto-detects the cutover (switches to
  # blockstream when no record file arrives within block.cutover.threshold, ~16s) — no hapiVersion
  # needed on 0.154.
  cat > "${MIRROR_NODE_CUTOVER_VALUES_FILE}" <<EOF
restjava:
  resources:
    requests:
      memory: ${MIRROR_RESTJAVA_MEMORY_REQUEST}
    limits:
      memory: ${MIRROR_RESTJAVA_MEMORY_LIMIT}
importer:
  env:
    HIERO_MIRROR_IMPORTER_BLOCK_ENABLED: 'true'
    HIERO_MIRROR_IMPORTER_BLOCK_NODES_0_HOST: 'block-node-${BLOCK_NODE_ID}.${SOLO_NAMESPACE}.svc.cluster.local'
EOF
  if [[ -n "${MIRROR_BLOCK_CUTOVER_HAPIVERSION}" ]]; then
    # 0.155+ only: pin the HAPI version at which to switch from record to block stream.
    printf '    HIERO_MIRROR_IMPORTER_BLOCK_CUTOVER_HAPIVERSION: %s\n' \
      "'${MIRROR_BLOCK_CUTOVER_HAPIVERSION}'" >> "${MIRROR_NODE_CUTOVER_VALUES_FILE}"
  fi
}

# After the 0.77 cutover, reconfigure the already-deployed mirror node to also read blocks from the
# Block Node (block.enabled=true) while keeping the pre-cutover records from MinIO. The importer
# auto-detects the cutover and permanently switches to blockstream once it reads a block from the
# BN. Uses `solo mirror node upgrade` (NOT add) to reuse the existing ingress release; --force
# bypasses Solo's CN/BN/MN version gates; --mirror-node-version pins a chart that recognizes the
# block keys. Best-effort: failure warns. NOTE: MN can only ingest the 0.77 blocks if the BN has
# successfully verified + stored them, so the BN-persist check is the real gate.
update_mirror_node_for_block_cutover() {
  write_mirror_node_block_cutover_values
  log "Reconfiguring mirror node (${MIRROR_NODE_VERSION}) to read 0.77 blocks from block-node-${BLOCK_NODE_ID} (block.enabled=true, auto-cutover)"
  if ! run_command_with_timeout "${SOLO_MIRROR_DEPLOY_TIMEOUT_SECS}" \
      solo mirror node upgrade \
      --deployment "${SOLO_DEPLOYMENT}" \
      --force \
      --mirror-node-version "${MIRROR_NODE_VERSION}" \
      --values-file "${MIRROR_NODE_CUTOVER_VALUES_FILE}"; then
    echo "mirror: 'solo mirror node upgrade' for block cutover failed" >&2
    return 1
  fi
}

# nginx proving-key server stays up from establish_076_baseline.
upgrade_to_local_077() {
  log "=== 0.77 cutover: upgrade to local build with 0.77 properties (BLOCKS-only, real TSS signatures, state proofs) ==="

  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --upgrade-version "${UPGRADE_VERSION_LABEL}"
    --local-build-path "${LOCAL_BUILD_PATH}"
    --application-properties "${APP_PROPS_077_FILE}"
    --quiet-mode
    --force
  )
  run_command_with_timeout "${SOLO_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"

  log "--- 0.77 check 1/4: wait for consensus pods + haproxy + verify local-build version ---"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  verify_local_build_on_consensus_nodes

  log "--- 0.77 check 2/4: nudge consensus with cryptoCreate txns ---"
  nudge_consensus_with_transactions

  log "--- 0.77 check 3/4: verify WRAPS runtime + real (non-mock) proof construction ---"
  verify_wraps_on_consensus_nodes 600

  log "--- 0.77 check 4/4: restart ${RESTART_REPLAY_NODE} and confirm clean replay (no SELF_ISS) ---"
  verify_node_replays_without_iss
}

log "Validating prerequisites"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd curl
require_cmd tar
require_cmd docker
require_cmd unzip
require_cmd node
require_cmd npm
if [[ "${ENABLE_BLOCK_NODE}" == "true" ]]; then
  require_cmd jq
  require_cmd nc
  require_cmd openssl
  require_cmd xxd
  require_cmd grpcurl
  require_cmd python3
  validate_block_node_repo || {
    echo "ENABLE_BLOCK_NODE=true but BLOCK_NODE_REPO_PATH is invalid: ${BLOCK_NODE_REPO_PATH}" >&2
    echo "Set BLOCK_NODE_REPO_PATH to a hiero-block-node checkout, or run with ENABLE_BLOCK_NODE=false." >&2
    exit 1
  }
fi

if [[ "${ENABLE_EXPLORER}" == "true" ]]; then
  # nc backs the port-forward readiness waits; curl backs the mirror REST readiness probe.
  require_cmd nc
  require_cmd curl
fi

[[ -f "${APP_PROPS_075_FILE}" ]] || { echo "Missing file: ${APP_PROPS_075_FILE}" >&2; exit 1; }
[[ -f "${APP_PROPS_076_FILE}" ]] || { echo "Missing file: ${APP_PROPS_076_FILE}" >&2; exit 1; }
[[ -f "${APP_ENV_076_FILE}" ]] || { echo "Missing file: ${APP_ENV_076_FILE}" >&2; exit 1; }
[[ -f "${APP_PROPS_077_FILE}" ]] || { echo "Missing file: ${APP_PROPS_077_FILE}" >&2; exit 1; }
[[ -f "${LOG4J2_XML_PATH}" ]] || { echo "Missing file: ${LOG4J2_XML_PATH}" >&2; exit 1; }

validate_local_build_path "${LOCAL_BUILD_PATH}" || {
  echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}" >&2
  echo "Expected ${LOCAL_BUILD_PATH}/apps/HederaNode.jar and ${LOCAL_BUILD_PATH}/lib" >&2
  exit 1
}

create_cluster
configure_solo
setup_cluster_prereqs
deploy_baseline_075

if [[ "${ENABLE_EXPLORER}" == "true" ]]; then
  # Deploy mirror node + explorer on the 0.75 baseline FIRST (before the BN) so the importer is
  # wired to read RECORD streams from MinIO (the CN writes records in 0.75/0.76). If the BN were
  # deployed first, Solo would auto-wire it as the importer's only block source and the importer
  # would stall trying to fetch block 0 from a mid-chain BN. After the 0.77 cutover the importer is
  # switched to BLOCKS/BN mode (update_mirror_node_for_block_cutover). Best-effort: a failure here
  # must not abort the core cutover test.
  deploy_mirror_and_explorer || log "WARN: mirror/explorer deployment incomplete; continuing with the core 0.76 -> 0.77 cutover test"
fi

if [[ "${ENABLE_BLOCK_NODE}" == "true" ]]; then
  # Deploy the BN now (mid-chain, on the 0.75 WRB-streaming baseline) so it verifies the
  # mock-sig (RSA WRB) blocks via the bootstrap roster through the 0.76 phase.
  log "=== Deploying Block Node ${BLOCK_NODE_ID} (will verify mock-sig blocks, then seeded for real-TSS) ==="
  deploy_block_node
  verify_block_node_has_blocks 180
fi

establish_076_baseline

if [[ "${ENABLE_BLOCK_NODE}" == "true" ]]; then
  # The ledger id is published during 0.76 (history/WRAPS construction completes). Seed it
  # into the BN before the 0.77 cutover so the BN can verify the real-TSS-signed blocks.
  log "=== Seeding Block Node with TSS ledger id (pre-0.77-cutover) ==="
  seed_block_node_tss_parameters
  # The seed rolled the BN; let it re-catch-up to the live stream on 0.76 before the cutover restart
  # resets the CN block buffer, otherwise the gap blocks are orphaned and the BN stalls.
  wait_for_block_node_caught_up 180 || log "WARN: BN did not re-catch-up after seeding; the 0.77 cutover may orphan blocks (consider seeding during the freeze instead)"
fi

upgrade_to_local_077

if [[ "${ENABLE_EXPLORER}" == "true" && "${ENABLE_BLOCK_NODE}" == "true" ]]; then
  # 0.77 streams BLOCKS-only via gRPC (no MinIO record files), so switch the importer to read the
  # post-cutover blocks from the Block Node while it keeps the pre-cutover records from MinIO.
  log "=== Reconfiguring mirror node to BLOCKS mode for the 0.77 cutover (read post-cutover blocks from BN) ==="
  update_mirror_node_for_block_cutover || log "WARN: mirror block-cutover reconfigure failed; explorer may not show post-0.77 blocks"
fi

if [[ "${ENABLE_BLOCK_NODE}" == "true" ]]; then
  log "--- 0.77 BN check: confirm Block Node verifies + persists the real-TSS post-cutover blocks ---"
  verify_block_node_persists_post_cutover 300
fi

log "PASS: 0.76 (TSS, mock sigs) -> 0.77 (BLOCKS-only cutover, real TSS signatures) upgrade completed and replayed cleanly"
if [[ "${ENABLE_BLOCK_NODE}" == "true" ]]; then
  log "PASS: Block Node verified the real-TSS-signed post-cutover blocks after TSS ledger-id seeding"
fi
