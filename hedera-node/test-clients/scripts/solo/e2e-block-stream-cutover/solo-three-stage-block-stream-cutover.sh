#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Three-stage block-stream cutover using the checkout's local CN build at every stage:
#   1. 0.79.0 configuration: WRB streaming + disk hashes with mock signatures, without a BN.
#   2. 0.79.1 configuration: generate jumpstart.bin with offline Block Node tooling,
#      verify it against every node's saved hashes, then
#      enable jumpstart + TSS. Replay the records to verify every node's migration vote.
#   3. 0.80.0 configuration: cut over to BLOCKS/GRPC with real signatures, verify BN persistence
#      and restart a CN to check replay for SELF_ISS.
#   4. Run SDK TCK, then verify fresh ingestion, BN reads, transaction continuity and stopped records.
# These are configuration stages, not claims about the local build's binary version.
# Build first with ./gradlew assemble. See README-three-stage-cutover.md for prerequisites.

set -euo pipefail
set +m

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../../" && pwd)"

SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-solo-three-stage-cutover}"
SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-three-stage-cutover}"
SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-setup}"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3,node4}"

# Optional published genesis baseline for the follow-on release-tag migration. Empty uses local JARs.
DEPLOY_RELEASE_TAG="${DEPLOY_RELEASE_TAG:-}"
# Solo uses this as a deployment label; node setup below supplies the actual local binary.
DEPLOY_VERSION_LABEL="${DEPLOY_VERSION_LABEL:-v$(cat "${REPO_ROOT}/version.txt")}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"

# Cap the WRAPS (Nova/rayon) prover's thread pool to limit its off-heap memory during the genesis
# ceremony. Injected as TSS_LIB_NUM_OF_CORES before startup so all nodes initialize WRAPS
# with the same core limit. Without it the prover grabs every host CPU, and with
# multiple nodes proving concurrently the off-heap peak dominates RAM. Capping trades genesis-proof
# speed for much lower peak memory. Default 1 matches the HAPI subprocess tests and prevents four
# concurrent provers from starving consensus on shared CI runners. Set empty (or 0) to use all cores.
WRAPS_NUM_CORES="${WRAPS_NUM_CORES:-1}"

APP_PROPS_STAGE1_FILE="${APP_PROPS_STAGE1_FILE:-${SCRIPT_DIR}/resources/0.79.0/application.properties}"
APP_PROPS_STAGE2_FILE="${APP_PROPS_STAGE2_FILE:-${SCRIPT_DIR}/resources/0.79.1/application.properties}"
APP_PROPS_STAGE3_FILE="${APP_PROPS_STAGE3_FILE:-${SCRIPT_DIR}/resources/0.80.0/application.properties}"
LOG4J2_XML_PATH="${LOG4J2_XML_PATH:-${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml}"
HAPI_PATH="/opt/hgcapp/services-hedera/HapiApp2.0"

SOLO_UPGRADE_TIMEOUT_SECS="${SOLO_UPGRADE_TIMEOUT_SECS:-1800}"

# Every time Solo rewrites application.properties on the consensus nodes
# (Helpers.copyBlockNodesJsonToConsensusNodes -> updateBlockStreamPropertiesForMode, added in Solo
# v0.87.0) it force-sets blockStream.writerMode to constants.BLOCK_STREAM_WRITER_MODE. The sole call
# site passes only the resolved streamMode, so that parameter always falls back to its default of
# FILE_AND_GRPC and silently overwrites the writerMode=GRPC the final stage properties ask for.
#
# The upgrade itself is handled the way the stage 1 deploy handles Solo's property rewrites: stage it
# with --skip-node-start and re-apply the properties before the JVMs start. `block node upgrade`
# runs after the nodes are already up, though, so a re-apply there would not be picked up until the
# next restart -- for that one command we use Solo's own environment override instead
# (constants.ts: getEnvironmentVariable('BLOCK_STREAM_WRITER_MODE')).
BLOCK_STREAM_WRITER_MODE_CUTOVER="${BLOCK_STREAM_WRITER_MODE_CUTOVER:-GRPC}"

# When true (default), after the stage 3 upgrade we delete a CN pod to force an
# event replay and assert it comes back ACTIVE without a SELF_ISS. This is the
# actual failure mode observed in the full run (an OOMKilled node replayed past
# the upgrade round and hit SELF_ISS). Set false to only test the happy path.
RESTART_REPLAY_CHECK="${RESTART_REPLAY_CHECK:-true}"
RESTART_REPLAY_NODE="${RESTART_REPLAY_NODE:-node1}"
RESTART_REPLAY_TIMEOUT_SECS="${RESTART_REPLAY_TIMEOUT_SECS:-600}"

# Local port-forward + operator key used by the post-upgrade tx nudge that
# pushes consensus rounds past the genesis WRAPS CRS-adoption stall.
CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"
NUDGE_TX_COUNT="${NUDGE_TX_COUNT:-5}"

# Deploy BN v0.44.2 only after the stage 2 jumpstart + TSS upgrade. Seed its TSS ledger
# id before stage 3 so it can verify real signatures at the BLOCKS-only cutover.
# The offline wrapping tools are pinned to the same BN release.
MINIO_NAMESPACE="${MINIO_NAMESPACE:-${SOLO_NAMESPACE}}"
MINIO_BUCKET="${MINIO_BUCKET:-solo-streams}"
BLOCK_NODE_ID="${BLOCK_NODE_ID:-1}"
BLOCK_NODE_REPO_PATH="${BLOCK_NODE_REPO_PATH:-${REPO_ROOT}/../hiero-block-node}"
BLOCK_NODE_CHART_VERSION="${BLOCK_NODE_CHART_VERSION:-v0.44.2}"
BLOCK_NODE_UPGRADE_VERSION="${BLOCK_NODE_UPGRADE_VERSION:-${BLOCK_NODE_CHART_VERSION}}"
BLOCK_NODE_INIT_IMAGE="${BLOCK_NODE_INIT_IMAGE:-docker.io/library/busybox:1.36.1}"
BLOCK_NODE_MIN_FREE_DISK_MIB="${BLOCK_NODE_MIN_FREE_DISK_MIB:-1024}"
BLOCK_NODE_PRIORITY_MAPPING="${BLOCK_NODE_PRIORITY_MAPPING:-}"
BLOCK_NODE_READY_TIMEOUT_SECS="${BLOCK_NODE_READY_TIMEOUT_SECS:-600}"
BLOCK_NODE_GRPC_PORT="${BLOCK_NODE_GRPC_PORT:-40840}"
BLOCK_NODE_GRPC_LOCAL_PORT="${BLOCK_NODE_GRPC_LOCAL_PORT:-40840}"
BLOCK_NODE_VALUES_FILE="${BLOCK_NODE_VALUES_FILE:-}"
# Block Node earliestManagedBlock (also used as the backfill start block). Must sit ABOVE the CN's
# block-stream tip when the BN joins mid-chain; defaults to a fixed 1000 in deploy_block_node
# (safely above this short test's tip). Override via env if needed.
BLOCK_NODE_CUTOVER_START_BLOCK="${BLOCK_NODE_CUTOVER_START_BLOCK:-}"
# RSA roster-bootstrap env (BN >= 0.34). The base URL is left empty because the BN has no mirror
# REST reachable from it; the RSA roster is seeded as a file instead (deploy_block_node).
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL:-}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS:-5}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS:-10}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE:-100}"

# Mirror node + explorer. The explorer is only a UI over the mirror node's REST API, so this
# script deploys BOTH. The mirror supplies record metadata
# for jumpstart generation and must be available before stage 2.
MIRROR_RESTJAVA_MEMORY_REQUEST="${MIRROR_RESTJAVA_MEMORY_REQUEST:-512Mi}"
MIRROR_RESTJAVA_MEMORY_LIMIT="${MIRROR_RESTJAVA_MEMORY_LIMIT:-1000Mi}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
MIRROR_REST_SERVICE="${MIRROR_REST_SERVICE:-mirror-1-rest}"
EXPLORER_INGRESS_LOCAL_PORT="${EXPLORER_INGRESS_LOCAL_PORT:-38080}"
EXPLORER_INGRESS_SERVICE_NAME="${EXPLORER_INGRESS_SERVICE_NAME:-hiero-explorer-1-solo}"
SOLO_MIRROR_DEPLOY_TIMEOUT_SECS="${SOLO_MIRROR_DEPLOY_TIMEOUT_SECS:-900}"
SOLO_EXPLORER_DEPLOY_TIMEOUT_SECS="${SOLO_EXPLORER_DEPLOY_TIMEOUT_SECS:-600}"

MIRROR_NODE_VERSION="${MIRROR_NODE_VERSION:-v0.165.0-rc1}"
MIRROR_BLOCK_CUTOVER_HAPIVERSION="${MIRROR_BLOCK_CUTOVER_HAPIVERSION:-0.79.0}"

# Explicit ingestion checks supplement SDK TCK and run even when TCK is disabled.
MIRROR_VALIDATION_TX_COUNT="${MIRROR_VALIDATION_TX_COUNT:-1}"
BN_ISOLATION_TX_COUNT="${BN_ISOLATION_TX_COUNT:-5}"
DOCKER_MIN_MEMORY_MIB="${DOCKER_MIN_MEMORY_MIB:-24576}"
MINIO_RESTORE_TIMEOUT_SECS="${MINIO_RESTORE_TIMEOUT_SECS:-300}"
MIRROR_ACCOUNT_WAIT_SECS="${MIRROR_ACCOUNT_WAIT_SECS:-180}"
MN_BLOCK_PROGRESS_MIN_DELTA="${MN_BLOCK_PROGRESS_MIN_DELTA:-3}"
MN_BLOCK_PROGRESS_TIMEOUT_SECS="${MN_BLOCK_PROGRESS_TIMEOUT_SECS:-180}"
MN_BLOCK_PROGRESS_POLL_SECS="${MN_BLOCK_PROGRESS_POLL_SECS:-5}"
BN_GET_BLOCK_MAX_PER_CHECK="${BN_GET_BLOCK_MAX_PER_CHECK:-25}"
RECORD_CESSATION_SETTLE_SECS="${RECORD_CESSATION_SETTLE_SECS:-15}"
VALIDATION_SUMMARY_LINES=()

# Run SDK TCK against the final BLOCKS-only network by default, using the XTS pins.
ENABLE_TCK_TESTS="${ENABLE_TCK_TESTS:-true}"
CITR_ENV_FILE="${REPO_ROOT}/.github/workflows/support/citr/.citr-env"
TCK_VERSION="${TCK_VERSION:-$({ sed -n 's/^tck-version=//p' "${CITR_ENV_FILE}" 2>/dev/null | head -n 1; } || true)}"
JS_SDK_VERSION="${JS_SDK_VERSION:-$({ sed -n 's/^js-sdk-version=//p' "${CITR_ENV_FILE}" 2>/dev/null | head -n 1; } || true)}"
TCK_REPO_PATH="${TCK_REPO_PATH:-}"
JS_SDK_REPO_PATH="${JS_SDK_REPO_PATH:-}"
TCK_TEST_FILES="${TCK_TEST_FILES:-}"
TCK_TEST_SCRIPT="${TCK_TEST_SCRIPT:-test}"
TCK_JSON_RPC_PORT="${TCK_JSON_RPC_PORT:-8544}"
MIRROR_GRPC_LOCAL_PORT="${MIRROR_GRPC_LOCAL_PORT:-5600}"
MIRROR_RESTJAVA_LOCAL_PORT="${MIRROR_RESTJAVA_LOCAL_PORT:-8084}"
MIRROR_RESTJAVA_READY_TIMEOUT_SECS="${MIRROR_RESTJAVA_READY_TIMEOUT_SECS:-300}"
TCK_TEST_TIMEOUT_SECS="${TCK_TEST_TIMEOUT_SECS:-3600}"
TCK_INSTALL_TIMEOUT_SECS="${TCK_INSTALL_TIMEOUT_SECS:-900}"

GENERATED_DIR="${GENERATED_DIR:-${SCRIPT_DIR}/generated/three-stage-cutover}"
mkdir -p "${GENERATED_DIR}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/solo-three-stage-cutover.XXXXXX")"
CURRENT_STAGE="prerequisites"
TX_ID_OUT_FILE="${GENERATED_DIR}/submitted-transactions.txt"
ACCOUNT_ID_OUT_FILE="${WORK_DIR}/nudge-accounts.txt"
: > "${TX_ID_OUT_FILE}"
RECORD_STREAMS_DIR="${GENERATED_DIR}/recordStreams"
WRAPPED_BLOCKS_DIR="${GENERATED_DIR}/wrappedBlocks"
REPLAY_WRAPPED_BLOCKS_DIR="${GENERATED_DIR}/replayWrappedBlocks"
WRAP_DAYS_SRC_DIR="${GENERATED_DIR}/wrap-days"
WRAP_COMPRESSED_DAYS_DIR="${GENERATED_DIR}/compressed-days"
BLOCK_TIMES_FILE="${GENERATED_DIR}/block-times.bin"
DAY_BLOCKS_FILE="${GENERATED_DIR}/day-blocks.json"
MIRROR_METADATA_SCRIPT="${WORK_DIR}/mirror-metadata.js"
JUMPSTART_PARSE_SCRIPT="${WORK_DIR}/parse-jumpstart-bin.js"
JUMPSTART_BIN_PATH=""
USE_BLOCK_NODE_JUMPSTART=true
BLOCKS_WRAP_EXTRA_ARGS="${BLOCKS_WRAP_EXTRA_ARGS:-}"
MINIO_SERVICE_NAME="${MINIO_SERVICE_NAME:-}"
ZSTD_WRAPPER_DIR="${WORK_DIR}/zstd-bin"
ZSTD_WRAPPER_SRC="${ZSTD_WRAPPER_DIR}/ZstdCat.java"
ZSTD_WRAPPER_BIN="${ZSTD_WRAPPER_DIR}/zstd"
MIGRATION_COMPARE_LOG="${GENERATED_DIR}/migration-compare.log"
TMP_STAGE2_APP_PROPS="${GENERATED_DIR}/application-jumpstart.properties"
WRAPPED_RECORD_HASHES_FILE="/opt/hgcapp/services-hedera/HapiApp2.0/data/wrappedRecordHashes/wrapped-record-hashes.pb"

NUDGE_SCRIPT="${WORK_DIR}/nudge-consensus.js"
CN_PORT_FORWARD_LOG="${WORK_DIR}/cn-port-forward.log"
RSA_BOOTSTRAP_ROSTER_FILE="${WORK_DIR}/rsa-bootstrap-roster.json"
BLOCK_NODE_CUTOVER_VALUES_FILE="${WORK_DIR}/block-node-cutover-values.yaml"
LEDGER_ID_EXTRACTOR_DIR="${WORK_DIR}/ledgerid-extractor"
LEDGER_ID_EXTRACTOR_SRC="${LEDGER_ID_EXTRACTOR_DIR}/extract_ledger_id_publication.py"
BN_TSS_PARAMS_LOCAL="${WORK_DIR}/tss-bootstrap-roster.json"
BN_BLOCK_FILES_DIR="${WORK_DIR}/bn-block-files"
BN_TSS_PARAMS_CONTAINER_PATH="${BN_TSS_PARAMS_CONTAINER_PATH:-/opt/hiero/block-node/application-state/tss-bootstrap-roster.json}"
MIRROR_NODE_VALUES_FILE="${WORK_DIR}/mirror-node-values.yaml"
MIRROR_PORT_FORWARD_PID=""
EXPLORER_INGRESS_PORT_FORWARD_PID=""

TCK_CLONE_DIR="${WORK_DIR}/hiero-sdk-tck"
JS_SDK_CLONE_DIR="${WORK_DIR}/hiero-sdk-js"
TCK_SDK_SERVER_LOG="${WORK_DIR}/tck-sdk-server.log"
TCK_REPORT_EXPORT_DIR="${TCK_REPORT_EXPORT_DIR:-${GENERATED_DIR}/tck-report}"
TCK_REPO_DIR=""
JS_SDK_REPO_DIR=""
TCK_STATS_SUMMARY=""
TCK_SDK_SERVER_PID=""
TCK_CN_PORT_FORWARD_PID=""
MIRROR_GRPC_PORT_FORWARD_PID=""
MIRROR_RESTJAVA_PORT_FORWARD_PID=""
MIRROR_WEB3_PORT_FORWARD_PID=""

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
  if ! reconnect_importer_to_minio; then
    echo "MinIO recovery incomplete; inspect ${MINIO_RESTORE_RESOURCE} in ${MINIO_NAMESPACE} and Kind node health" >&2
    (( ec != 0 )) || ec=1
  fi
  stop_bn_port_forward
  if (( ec != 0 )) && [[ "${CURRENT_STAGE}" != prerequisites ]]; then
    collect_stage_diagnostics "${CURRENT_STAGE}-failure" || true
  fi
  cleanup_tck_resources
  # Keep generated jumpstart inputs, comparisons and per-stage logs for CI artifacts.
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

# Solo creates the per-node `network-<alias>-data-config-cm` ConfigMaps straight through the
# Kubernetes API during `block node add` (Helpers.copyBlockNodesJsonToConsensusNodes), passing an
# empty label map, so they carry no Helm ownership metadata. The solo-deployment chart templates the
# same ConfigMaps, but only once `hedera.nodes[i].blockNodesJson` is set -- and `block node add` is
# what sets it. So `consensus network deploy` renders none of them, Solo then creates them out of
# band, and the next `helm upgrade` (driven by `consensus network upgrade`) aborts with "invalid
# ownership metadata". Stamp the standard Helm adoption keys so Helm can take ownership instead.
# Helm 4 also tracks field ownership: release Solo data claims so Helm can apply new values.
# This includes the shared application.properties ConfigMap, which a client-side Helm update
# can own as helm/Update even though Helm 4 applies as helm/Apply.
adopt_node_data_config_maps_into_helm() {
  # Solo's release name for the solo-deployment chart (constants.SOLO_DEPLOYMENT_CHART). This is NOT
  # ${SOLO_DEPLOYMENT}, which is the Solo deployment name and defaults to something else entirely.
  local helm_release="solo-deployment"
  local node cm helm_version ownership_patch
  local nodes=() config_maps=(network-node-data-config-cm)
  helm_version="$(helm version --short)"

  log "Adopting consensus ConfigMaps for Helm updates"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    config_maps+=("network-${node}-data-config-cm")
  done
  for cm in "${config_maps[@]}"; do
    if ! kubectl -n "${SOLO_NAMESPACE}" get configmap "${cm}" >/dev/null 2>&1; then
      echo "  ${cm}: absent, nothing to adopt"
      continue
    fi
    kubectl -n "${SOLO_NAMESPACE}" label configmap "${cm}" \
      "app.kubernetes.io/managed-by=Helm" --overwrite >/dev/null
    kubectl -n "${SOLO_NAMESPACE}" annotate configmap "${cm}" \
      "meta.helm.sh/release-name=${helm_release}" \
      "meta.helm.sh/release-namespace=${SOLO_NAMESPACE}" --overwrite >/dev/null
    if [[ "${helm_version}" == v4.* ]]; then
      ownership_patch="$(kubectl -n "${SOLO_NAMESPACE}" get configmap "${cm}" --show-managed-fields -o json |
        jq -c -f "${SCRIPT_DIR}/helm-configmap-ownership.jq")"
      if [[ "${ownership_patch}" != "[]" ]]; then
        # Resource-version test makes the metadata-only patch atomic against concurrent writes.
        kubectl -n "${SOLO_NAMESPACE}" patch configmap "${cm}" --type=json \
          --patch "${ownership_patch}" >/dev/null
        echo "  ${cm}: released Solo data-field ownership for Helm 4 apply"
      fi
    fi
    echo "  ${cm}: adopted into Helm release ${helm_release}"
  done
}

reapply_application_property_overrides() {
  local properties_file="$1"
  local node pod
  local nodes=()
  local remote_overrides="/tmp/cutover-application-overrides.properties"
  local app_properties="${HAPI_PATH}/data/config/application.properties"

  log "Reapplying $(basename "${properties_file}") after Solo-generated property overrides"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    kubectl -n "${SOLO_NAMESPACE}" cp "${properties_file}" \
      "${pod}:${remote_overrides}" -c root-container
    kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc "
      set -eu
      app='${app_properties}'
      overrides='${remote_overrides}'
      merged=\"\${app}.cutover-merged\"
      cp \"\${app}\" \"\${merged}\"
      while IFS= read -r line || [ -n \"\${line}\" ]; do
        case \"\${line}\" in
          ''|'#'*) continue ;;
        esac
        key=\${line%%=*}
        grep -v -F \"\${key}=\" \"\${merged}\" > \"\${merged}.next\" || true
        mv \"\${merged}.next\" \"\${merged}\"
        printf '%s\n' \"\${line}\" >> \"\${merged}\"
      done < \"\${overrides}\"
      cat \"\${merged}\" > \"\${app}\"
      rm -f \"\${merged}\" \"\${overrides}\"
    "
  done
}

# Pre-inject the WRAPS core limit into every consensus StatefulSet's
# container spec BEFORE Solo's stage 1 deploy. Solo's --application-env drops the
# env file onto disk but the container entrypoint never sources it, so the JVM
# never sees it. `kubectl set env statefulset/...` is the only path that
# reliably reaches the JVM's /proc/<pid>/environ AND survives subsequent
# kubectl-delete-pod restarts. Doing this BEFORE the upgrade ensures all nodes
# initialize WRAPS in lockstep during the freeze-restart; injecting AFTER
# triggers independent rolling restarts that produce SELF_ISS failures.
inject_wraps_env_into_statefulsets() {
  local node sts log_file
  local nodes=()
  log_file="${WORK_DIR}/inject-wraps-env.log"
  : > "${log_file}"

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  # Proving parameters ship in the library; no external artifacts or swap file are used.
  # Removing the variable selects the library default (all cores).
  local -a wraps_env_args=("TSS_LIB_NUM_OF_CORES-")
  if [[ "${WRAPS_NUM_CORES}" =~ ^[1-9][0-9]*$ ]]; then
    wraps_env_args=("TSS_LIB_NUM_OF_CORES=${WRAPS_NUM_CORES}")
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

# Verifies each consensus node runs a release whose Implementation-Version contains the expected
# substring. Used when DEPLOY_RELEASE_TAG selects a published stage 1 baseline.
verify_release_version_on_consensus_nodes() {
  local expected_substr="$1"
  local node pod pod_version
  local nodes=()

  log "Verifying release version on each consensus node (expected to contain '${expected_substr}')"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    pod_version="$(consensus_pod_implementation_version "${pod}" || true)"
    if [[ "${pod_version}" == *"${expected_substr}"* ]]; then
      echo "  ${pod}: ${pod_version} OK"
    else
      echo "  ${pod}: expected version containing '${expected_substr}', found ${pod_version:-unknown}" >&2
      return 1
    fi
  done
}

verify_runtime_config_on_consensus_nodes() {
  local phase="$1"
  shift
  local node pod expectation key expected runtime_line actual
  local nodes=()

  log "Verifying ${phase} runtime configuration on each consensus node"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    for expectation in "$@"; do
      key="${expectation%%=*}"
      expected="${expectation#*=}"
      runtime_line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
        "grep -F '${key} = ' ${HAPI_PATH}/output/hgcaa.log | tail -n 1" 2>/dev/null || true)"
      actual="${runtime_line#*= }"
      if [[ -z "${runtime_line}" || "${actual}" != "${expected}" ]]; then
        echo "  ${pod}: expected ${key}=${expected}, found ${actual:-unset}" >&2
        return 1
      fi
    done
    echo "  ${pod}: ${phase} runtime configuration OK"
  done
}

wraps_proof_present_in_log() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "grep -Eq 'History proof constructed \(#.*WRAPS-extensible\? true\)' ${HAPI_PATH}/output/hgcaa.log" >/dev/null 2>&1
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
    "grep -Eq 'SELF_ISS|ISS detected|is CATASTROPHIC_FAILURE' ${HAPI_PATH}/output/hgcaa.log" >/dev/null 2>&1
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
  disown "${pf_pid}" 2>/dev/null || true

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

  : > "${ACCOUNT_ID_OUT_FILE}"
  cat > "${NUDGE_SCRIPT}" <<'EOF'
const fs = require("fs");
const {
  Client,
  AccountCreateTransaction,
  Hbar,
  PrivateKey,
  Status,
  TransferTransaction,
} = require("@hashgraph/sdk");

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function recordTransaction(txId) {
  const nanos = String(txId.validStart.nanos).padStart(9, "0");
  fs.appendFileSync(process.env.TX_ID_OUT_FILE, `${txId.accountId}-${txId.validStart.seconds}-${nanos}\n`);
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
    if (!receipt.accountId) {
      throw new Error(`tx ${i}/${txCount}: receipt has no account ID`);
    }
    recordTransaction(response.transactionId);
    const accountId = receipt.accountId.toString();
    fs.appendFileSync(process.env.ACCOUNT_ID_OUT_FILE, `${accountId}\n`);
    console.log(`  nudge tx ${i}/${txCount}: cryptoCreate -> ${accountId}`);
    await sleep(500);
  }

  // A later transaction in another record period closes the last create's record block.
  // Keep its receipt too; the final BLOCKS-mode continuity sweep must see every submission.
  await sleep(3000);
  const flush = await new TransferTransaction()
    .addHbarTransfer(operatorAccountId, Hbar.fromTinybars(-1))
    .addHbarTransfer(operatorAccountId, Hbar.fromTinybars(1))
    .execute(client);
  const flushReceipt = await flush.getReceipt(client);
  if (flushReceipt.status !== Status.Success) {
    throw new Error(`flush: non-success status ${flushReceipt.status.toString()}`);
  }
  recordTransaction(flush.transactionId);
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
    TX_ID_OUT_FILE="${TX_ID_OUT_FILE}" \
    ACCOUNT_ID_OUT_FILE="${ACCOUNT_ID_OUT_FILE}" \
    NUDGE_TX_COUNT="${tx_count}" \
      node "${NUDGE_SCRIPT}"
  ) || rc=$?

  kill "${pf_pid}" >/dev/null 2>&1 || true
  pkill -f "port-forward.*haproxy-node1-svc.*${CN_GRPC_LOCAL_PORT}" >/dev/null 2>&1 || true

  return "${rc}"
}

verify_wraps_on_consensus_nodes() {
  local timeout_secs="${1:-600}"
  local deadline=0
  local node pod
  local nodes=()

  log "Verifying completed WRAPS history proofs on each consensus node (up to ${timeout_secs}s/node)"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    deadline=$((SECONDS + timeout_secs))

    echo "  ${pod}: waiting for a completed, WRAPS-extensible history proof in hgcaa.log"
    local progress_tick=0
    while (( SECONDS < deadline )); do
      if wraps_failure_present_in_log "${pod}"; then
        echo "  ${pod}: WRAPS reported a runtime failure (check ${HAPI_PATH}/output/hgcaa.log)" >&2
        return 1
      fi
      if wraps_proof_present_in_log "${pod}"; then
        echo "  ${pod}: Completed WRAPS history proof detected"
        break
      fi
      if (( progress_tick > 0 && progress_tick % 6 == 0 )); then
        echo "    ...still waiting on ${pod} ($((deadline - SECONDS))s remaining)"
      fi
      progress_tick=$((progress_tick + 1))
      sleep 5
    done

    if ! wraps_proof_present_in_log "${pod}"; then
      echo "  ${pod}: timed out after ${timeout_secs}s waiting for a completed WRAPS history proof" >&2
      return 1
    fi
  done

  echo "All consensus nodes confirmed: completed WRAPS history proof observed"
}

# Delete a CN pod after the cutover to force a fresh JVM start + event replay,
# then assert it returns to Ready without logging a SELF_ISS / CATASTROPHIC_FAILURE.
# This reproduces the real failure mode: in the full run, an OOMKilled node
# replayed events past the stage 3 cutover round and computed a divergent state
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
        "grep -nE 'SELF_ISS|ISS detected|is CATASTROPHIC_FAILURE' ${HAPI_PATH}/output/hgcaa.log | tail -5" >&2 2>/dev/null || true
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
# Block Node verification and TSS-ledger-id bootstrap machinery.
# Lets this fast reproducer also exercise: BN verification of real-TSS blocks during stage 2,
# seeding the BN with the network's TSS ledger id, and BN verification +
# persistence of the real-TSS-signed blocks produced after the stage 3 upgrade.
# ======================================================================================

kill_processes_on_local_port() {
  local port="$1"
  local pid
  # TCK's pnpm/nodemon grandchildren can outlive their launcher. Find the listener itself,
  # since matching kubectl port-forward commands does not stop an SDK JSON-RPC server.
  if command -v lsof >/dev/null 2>&1; then
    while IFS= read -r pid; do
      [[ -n "${pid}" ]] && kill "${pid}" >/dev/null 2>&1 || true
    done < <(lsof -ti "tcp:${port}" -sTCP:LISTEN 2>/dev/null || true)
  fi
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
# Lets the BN verify the mock-signature (RSA WRB) blocks streamed before the stage 3 cutover.
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

# Helm values: earliestManagedBlock/backfill floor and an init container that seeds
# the RSA bootstrap roster onto the live PVC before the Block Node starts.
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
      image: "${BLOCK_NODE_INIT_IMAGE}"
      imagePullPolicy: IfNotPresent
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
          chown 2000:2000 /application-state-pvc && \\
          chmod 700 /application-state-pvc
      volumeMounts:
        - name: live-storage
          mountPath: /live-pvc
        - name: archive-storage
          mountPath: /archive-pvc
        - name: application-state-storage
          mountPath: /application-state-pvc
    - name: seed-rsa-bootstrap-roster
      image: "${BLOCK_NODE_INIT_IMAGE}"
      imagePullPolicy: IfNotPresent
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

verify_kind_disk_headroom() {
  local kind_nodes node available_mib
  if [[ ! "${BLOCK_NODE_MIN_FREE_DISK_MIB}" =~ ^[0-9]+$ ]]; then
    echo "BLOCK_NODE_MIN_FREE_DISK_MIB must be a nonnegative integer" >&2
    return 1
  fi
  kind_nodes="$(kind get nodes --name "${SOLO_CLUSTER_NAME}")" || return 1
  [[ -n "${kind_nodes}" ]] || { echo "No Kind nodes found for ${SOLO_CLUSTER_NAME}" >&2; return 1; }
  while IFS= read -r node; do
    available_mib="$(docker exec "${node}" df -Pm /var | awk 'NR == 2 { print $4 }')" || return 1
    if [[ ! "${available_mib}" =~ ^[0-9]+$ ]]; then
      echo "Could not determine free disk space on ${node}" >&2
      return 1
    fi
    if (( available_mib < BLOCK_NODE_MIN_FREE_DISK_MIB )); then
      echo "${node}: only ${available_mib} MiB free on /var; need at least ${BLOCK_NODE_MIN_FREE_DISK_MIB} MiB before deploying BN" >&2
      echo "Increase Docker Desktop's disk limit or free unused Docker storage before retrying. A full disk prevents image pulls and can stop the Kubernetes API server." >&2
      docker exec "${node}" df -h /var >&2 || true
      return 1
    fi
    log "${node}: ${available_mib} MiB free before BN deployment"
  done <<< "${kind_nodes}"
}

deploy_block_node() {
  verify_kind_disk_headroom || return 1
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
    --force-port-forward false
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
  local status_proto="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto/block-node/api/node_service.proto"
  if [[ ! -d "${BLOCK_NODE_REPO_PATH}" ]]; then
    echo "BLOCK_NODE_REPO_PATH not found: ${BLOCK_NODE_REPO_PATH} (needed for the serverStatus proto)" >&2
    return 1
  fi
  if [[ ! -f "${status_proto}" ]] || ! grep -q 'next_expected_block' "${status_proto}"; then
    echo "BLOCK_NODE_REPO_PATH must provide the v0.39+ serverStatus proto with next_expected_block: ${status_proto}" >&2
    return 1
  fi
}

# v0.39 uses uint64 max as the nextExpectedBlock sentinel when no publisher stream is currently
# attached. In that state, lastAvailableBlock + 1 is the equivalent persisted-store position.
bn_effective_next_expected_from_status() {
  local raw="$1" next_expected="" last_available=""
  next_expected="$(echo "${raw}" | jq -r '.nextExpectedBlock // empty' 2>/dev/null || true)"
  if [[ "${next_expected}" == "18446744073709551615" ]]; then
    last_available="$(echo "${raw}" | jq -r '.lastAvailableBlock // empty' 2>/dev/null || true)"
    if [[ "${last_available}" =~ ^[0-9]+$ ]]; then
      echo $((last_available + 1))
      return
    fi
  fi
  echo "${next_expected}"
}

# Poll BN serverStatus until nextExpectedBlock > 1 (BN holds >= block 1), proving CN is streaming into it.
verify_block_node_has_blocks() {
  local timeout_secs="${1:-120}"
  local svc="block-node-${BLOCK_NODE_ID}"
  local remote_port="${BLOCK_NODE_GRPC_PORT}" local_port="${BLOCK_NODE_GRPC_LOCAL_PORT}"
  local pf_log="${WORK_DIR}/port-forward-block-node-status.log" pf_pid=""
  local grpc_err="${WORK_DIR}/grpcurl-block-node-status.err"
  require_cmd grpcurl
  local proto_api_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto"
  # node_service.proto imports services/basic_types.proto; resolve it from this repo's tracked hapi
  # source (always checked out, build-independent) instead of the BN repo's gitignored generated dir.
  local proto_hapi_root="${REPO_ROOT}/hapi/hedera-protobuf-java-api/src/main/proto"
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
    echo "Could not port-forward to ${svc}; kubectl port-forward log:" >&2
    cat "${pf_log}" >&2 2>/dev/null || true
    return 1
  fi

  local deadline=$((SECONDS + timeout_secs)) next_expected="" raw=""
  log "Polling ${svc} serverStatus for nextExpectedBlock > 1 (up to ${timeout_secs}s)"
  while (( SECONDS < deadline )); do
    raw="$(grpcurl -plaintext -import-path "${proto_api_root}" -import-path "${proto_hapi_root}" \
            -proto "${proto_file}" -d '{}' "127.0.0.1:${local_port}" \
            org.hiero.block.api.BlockNodeService/serverStatus 2>"${grpc_err}")" || true
    next_expected="$(bn_effective_next_expected_from_status "${raw}")"
    if [[ "${next_expected}" =~ ^[0-9]+$ && "${next_expected}" -gt 1 ]]; then
      log "verify_block_node_has_blocks: effective nextExpectedBlock=${next_expected} (reported=$(echo "${raw}" | jq -r '.nextExpectedBlock // "?"'), firstAvailableBlock=$(echo "${raw}" | jq -r '.firstAvailableBlock // "?"'))"
      kill "${pf_pid}" >/dev/null 2>&1 || true
      return 0
    fi
    sleep 5
  done
  echo "BN ${svc} did not report nextExpectedBlock > 1 within ${timeout_secs}s (last serverStatus stdout: ${raw:-<empty>})" >&2
  echo "  --- last grpcurl stderr (serverStatus) ---" >&2
  cat "${grpc_err}" >&2 2>/dev/null || true
  echo "  --- kubectl port-forward log (${svc}) ---" >&2
  cat "${pf_log}" >&2 2>/dev/null || true
  kill "${pf_pid}" >/dev/null 2>&1 || true
  return 1
}

# Dependency-free Python extractor: walks the protobuf wire format of a block-stream file
# and converts the LedgerIdPublicationTransactionBody (Block.items[1] ->
# BlockItem.signed_transaction[4] -> SignedTransaction.bodyBytes[1] ->
# TransactionBody.ledger_id_publication[77]) into the TssData JSON that the
# block-verification plugin reads from app.state.tssBootstrapFilePath.
write_ledger_id_extractor() {
  mkdir -p "${LEDGER_ID_EXTRACTOR_DIR}"
  cat > "${LEDGER_ID_EXTRACTOR_SRC}" <<'EOF'
#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import sys
import gzip
import base64
import json
import os


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


def tss_data_json(pub, block_number):
    ledger_id = find_field(pub, 1)
    contributions = []
    for fnum, wtype, val in iter_fields(pub):
        if fnum != 3 or wtype != 2:
            continue
        node_id = 0
        weight = 0
        history_key = b""
        for cnum, ctype, cval in iter_fields(val):
            if cnum == 1 and ctype == 0:
                node_id = cval
            elif cnum == 2 and ctype == 0:
                weight = cval
            elif cnum == 3 and ctype == 2:
                history_key = cval
        contributions.append({
            "nodeId": str(node_id),
            "weight": str(weight),
            "schnorrPublicKey": base64.b64encode(history_key).decode("ascii"),
        })
    if not ledger_id or not contributions:
        raise ValueError("LedgerIdPublication is missing ledger id or node contributions")
    return {
        "ledgerId": base64.b64encode(ledger_id).decode("ascii"),
        "currentRoster": {"rosterEntries": contributions},
        "validFromBlock": str(block_number),
    }


def main():
    if len(sys.argv) < 3:
        sys.stderr.write("usage: extract.py <out.json> <blockFile.blk[.gz]> [...]\n")
        sys.exit(2)
    out = sys.argv[1]
    for path in sys.argv[2:]:
        try:
            with open(path, "rb") as f:
                raw = f.read()
            data = gzip.decompress(raw) if path.endswith(".gz") else raw
            pub = extract_from_block(data)
            if pub is not None:
                block_number = int(os.path.basename(path).split(".", 1)[0])
                with open(out, "w", encoding="utf-8") as o:
                    json.dump(tss_data_json(pub, block_number), o, separators=(",", ":"))
                print("FOUND ledgerIdPublication in %s -> wrote canonical TssData JSON to %s" % (path, out))
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
# blocks after the stage 3 upgrade. The BN only self-learns the ledger id from block 0; this
# mid-chain BN never sees it, so we extract the LedgerIdPublication (published during stage 2)
# from a MinIO .blk.gz, convert it to application-state TssData JSON, and roll the BN.
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
    # `pulled=$((pulled + 1))` not `((pulled++))`: the latter returns exit 1 when pulled goes 0->1,
    # which trips `set -e` on bash 5 (CI). An assignment always returns 0.
    if [[ -s "${dest}" ]]; then pulled=$((pulled + 1)); else rm -f "${dest}"; fi
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
    echo "seed: no LedgerIdPublication found in block stream (was it published during stage 2?)" >&2; return 1
  fi
  [[ -s "${BN_TSS_PARAMS_LOCAL}" ]] || { echo "seed: extracted TssData JSON is empty" >&2; return 1; }

  bn_pod="block-node-${BLOCK_NODE_ID}-0"
  log "Seeding ${bn_pod}:${BN_TSS_PARAMS_CONTAINER_PATH} and rolling the Block Node"
  kubectl -n "${SOLO_NAMESPACE}" exec "${bn_pod}" -- sh -lc "mkdir -p '$(dirname "${BN_TSS_PARAMS_CONTAINER_PATH}")'" >/dev/null 2>&1 || true
  # Tar-free push (kubectl cp needs tar in the target container): stream the file into the
  # pod's cat via stdin.
  if ! kubectl -n "${SOLO_NAMESPACE}" exec -i "${bn_pod}" -- sh -lc "cat > '${BN_TSS_PARAMS_CONTAINER_PATH}'" < "${BN_TSS_PARAMS_LOCAL}"; then
    echo "seed: streaming TssData JSON into ${bn_pod} failed" >&2; return 1
  fi
  # Mark the roll instant (UTC, CN-log timestamp format): wait_for_block_node_caught_up must only
  # trust comms-log lines written after this, or a stale pre-roll line passes its gate vacuously.
  BN_SEED_ROLL_UTC="$(date -u '+%Y-%m-%d %H:%M:%S')"
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
  # Confirmation: the active v0.39 block-verification plugin logs a successful application-state
  # TSS update during init. `kubectl logs` can
  # briefly return a transitioning container right after the roll, so poll generously. An explicit
  # parse/load failure is fatal; but if we merely never observe the marker (a logs-cutover race)
  # while the file is present and the pod is Ready, continue with a warning —
  # verify_block_node_persists_post_cutover is the real gate on whether the BN verifies the blocks.
  sleep 5
  local deadline=$((SECONDS + 180))
  local bn_logs=""
  while (( SECONDS < deadline )); do
    bn_logs="$(kubectl -n "${SOLO_NAMESPACE}" logs "${bn_pod}" 2>/dev/null)"
    if grep -q "Successfully updated TSS data" <<<"${bn_logs}"; then
      log "Block Node loaded seeded TSS application state — ready to verify real-TSS blocks"
      return 0
    fi
    if grep -qiE "Failed to read TssData file|Failed to update TSS data in verification|invalid TssData|TssData.*(error|corrupt)" <<<"${bn_logs}"; then
      echo "seed: BN reported a TSS parameters load failure:" >&2
      grep -iE "tss" <<<"${bn_logs}" | tail -5 >&2
      return 1
    fi
    sleep 3
  done
  log "WARN seed: did not observe a successful TSS application-state update in ${bn_pod} after polling ~180s, but the seeded file is present and the pod is Ready (likely a kubectl-logs cutover race); continuing — post-cutover BN verification will gate this"
  return 0
}

# After the stage 3 upgrade, assert the BN VERIFIES + PERSISTS the real-TSS-signed blocks.
# Reads serverStatus.nextExpectedBlock twice over a window and requires it to advance.
# nextExpectedBlock is the live publisher position (= lastAvailableBlock + 1), bumped as the BN
# receives/verifies each block; unlike lastAvailableBlock it does not lag behind the persistence/
# available-store watermark, which can stay stuck after the cutover re-seed even while the BN is
# verifying + persisting fine. If the BN were rejecting the real-TSS blocks it would be stuck.
# Also surfaces any recent 'Verification failed' log lines on failure for diagnosis.
verify_block_node_persists_post_cutover() {
  local timeout_secs="${1:-300}"
  local bn_pod="block-node-${BLOCK_NODE_ID}-0"
  local svc="block-node-${BLOCK_NODE_ID}"
  local remote_port="${BLOCK_NODE_GRPC_PORT}" local_port="${BLOCK_NODE_GRPC_LOCAL_PORT}"
  local pf_log="${WORK_DIR}/port-forward-bn-postcutover.log" pf_pid=""
  require_cmd grpcurl
  local proto_api_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto"
  local proto_hapi_root="${REPO_ROOT}/hapi/hedera-protobuf-java-api/src/main/proto"
  local proto_file="block-node/api/node_service.proto"

  read_bn_next_expected() {
    local raw
    raw="$(grpcurl -plaintext -import-path "${proto_api_root}" -import-path "${proto_hapi_root}" \
            -proto "${proto_file}" -d '{}' "127.0.0.1:${local_port}" \
            org.hiero.block.api.BlockNodeService/serverStatus 2>/dev/null)" || true
    bn_effective_next_expected_from_status "${raw}"
  }

  kill_processes_on_local_port "${local_port}"
  : > "${pf_log}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${svc}" "${local_port}:${remote_port}" >"${pf_log}" 2>&1 < /dev/null &
  pf_pid=$!
  disown "${pf_pid}" 2>/dev/null || true
  wait_for_tcp_open "127.0.0.1" "${local_port}" 20 1 || { kill "${pf_pid}" >/dev/null 2>&1 || true; echo "post-cutover: BN port-forward failed" >&2; return 1; }

  local baseline="" current=""
  baseline="$(read_bn_next_expected)"
  [[ "${baseline}" =~ ^[0-9]+$ ]] || baseline=""
  log "Asserting BN persists post-cutover blocks (baseline nextExpectedBlock=${baseline}; must climb within ${timeout_secs}s)"
  local deadline=$((SECONDS + timeout_secs))
  nudge_consensus_with_transactions
  while (( SECONDS < deadline )); do
    sleep 10
    current="$(read_bn_next_expected)"
    if [[ -z "${baseline}" && "${current}" =~ ^[0-9]+$ ]]; then
      baseline="${current}"
      nudge_consensus_with_transactions
      continue
    fi
    if [[ "${current}" =~ ^[0-9]+$ && -n "${baseline}" && "${current}" -gt "${baseline}" ]]; then
      log "verify_block_node_persists_post_cutover: nextExpectedBlock advanced ${baseline} -> ${current} — BN verified the real-TSS post-cutover blocks"
      kill "${pf_pid}" >/dev/null 2>&1 || true
      return 0
    fi
  done
  echo "post-cutover: BN nextExpectedBlock stuck at ${baseline} for ${timeout_secs}s — BN is NOT verifying the real-TSS blocks" >&2
  echo "  recent BN verification failures:" >&2
  kubectl -n "${SOLO_NAMESPACE}" logs "${bn_pod}" --since=10m 2>/dev/null | grep -E "Verification failed for block=" | tail -5 >&2 || true
  kill "${pf_pid}" >/dev/null 2>&1 || true
  return 1
}

# Seeding rolls the BN, which severs the CN->BN publisher stream at the BN's last block. The CN keeps
# producing during stage 2, but those blocks live only in the CN's in-memory block buffer, which RESETS when
# the stage 3 upgrade restarts the CN. If the BN hasn't re-ingested up to the live tip before that
# restart, the gap blocks are orphaned (the BN's wanted block falls below the CN's reset buffer floor,
# "block out of range") and the BN stalls forever. So after seeding, wait until the BN is actively
# advancing again (publisher reconnected + streaming the post-roll blocks) BEFORE the stage 3 upgrade.
wait_for_block_node_caught_up() {
  local timeout_secs="${1:-300}"
  local svc="block-node-${BLOCK_NODE_ID}"
  local remote_port="${BLOCK_NODE_GRPC_PORT}" local_port="${BLOCK_NODE_GRPC_LOCAL_PORT}"
  local pf_log="${WORK_DIR}/port-forward-bn-catchup.log" pf_pid=""
  require_cmd grpcurl
  local proto_api_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto"
  local proto_hapi_root="${REPO_ROOT}/hapi/hedera-protobuf-java-api/src/main/proto"
  local proto_file="block-node/api/node_service.proto"
  local cn_pod="network-${NODE_ALIASES%%,*}-0"
  local comms_log="/opt/hgcapp/services-hedera/HapiApp2.0/output/block-node-comms.log"

  read_bn_next_expected() {
    local raw
    raw="$(grpcurl -plaintext -import-path "${proto_api_root}" -import-path "${proto_hapi_root}" \
            -proto "${proto_file}" -d '{}' "127.0.0.1:${local_port}" \
            org.hiero.block.api.BlockNodeService/serverStatus 2>/dev/null)" || true
    bn_effective_next_expected_from_status "${raw}"
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
  # Only comms-log lines stamped at/after BN_SEED_ROLL_UTC count: the CN takes up to ~15s (next send
  # attempt + cool-down) to even notice the roll severed the stream, so the pre-roll tail still ends
  # with a stale "available for streaming" line that would pass this gate before any reconnection
  # (that stale match let the 2026-08-01 run cut over into the orphaned-blocks stall).
  log "Waiting for the CN to report the Block Node in-range for streaming after the seed roll (up to ${timeout_secs}s) before the stage 3 cutover"
  local prev="" cur cn_view
  local deadline=$((SECONDS + timeout_secs))
  while (( SECONDS < deadline )); do
    cur="$(read_bn_next_expected)"
    cn_view="$(kubectl -n "${SOLO_NAMESPACE}" exec "${cn_pod}" -c root-container -- sh -c \
      "awk -v ts='${BN_SEED_ROLL_UTC:-}' 'ts == \"\" || \$0 >= ts' '${comms_log}' 2>/dev/null \
        | grep -aE 'available for streaming \(wantedBlock|block out of range|No block nodes available for streaming' | tail -1" 2>/dev/null || true)"
    case "${cn_view}" in
      *"available for streaming (wantedBlock"*)
        log "Block Node is caught up — CN reports it in-range for streaming (BN nextExpectedBlock=${cur:-?}); safe to cut over"
        kill "${pf_pid}" >/dev/null 2>&1 || true
        return 0
        ;;
    esac
    if [[ "${cur}" =~ ^[0-9]+$ && "${cur}" != "${prev}" ]]; then
      log "  BN nextExpectedBlock=${cur} (CN view: ${cn_view:-pending})"
      prev="${cur}"
    fi
    sleep 5
  done
  echo "WARN catchup: CN did not report the BN in-range within ${timeout_secs}s (last BN nextExpectedBlock=${prev:-?}); the stage 3 cutover may orphan blocks — consider seeding during the freeze" >&2
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

# Save diagnostics before Solo rotates logs during the next upgrade, and on failure.
collect_stage_diagnostics() {
  local stage="$1" node pod name kind_node kind_nodes nodes=()
  local out="${GENERATED_DIR}/${stage}"
  mkdir -p "${out}"
  # Collect storage evidence without the Kubernetes API, which can stop on a full disk.
  kind_nodes="$(kind get nodes --name "${SOLO_CLUSTER_NAME}" 2>/dev/null || true)"
  while IFS= read -r kind_node; do
    [[ -n "${kind_node}" ]] || continue
    run_command_with_timeout 15 docker exec "${kind_node}" df -h /var > "${out}/${kind_node}-disk.txt" 2>&1 || true
    run_command_with_timeout 15 docker exec "${kind_node}" sh -c \
      'cat /proc/meminfo; cat /proc/loadavg; cat /proc/pressure/memory; cat /proc/pressure/io; cat /sys/fs/cgroup/memory.events; cat /sys/fs/cgroup/memory.swap.current' \
      > "${out}/${kind_node}-memory-pressure.txt" 2>&1 || true
  done <<< "${kind_nodes}"
  kubectl --request-timeout=10s get nodes -o wide > "${out}/nodes.txt" 2>&1 || true
  kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" get pods -o wide > "${out}/pods.txt" 2>&1 || true
  kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" get events --sort-by=.lastTimestamp \
    > "${out}/events.txt" 2>&1 || true
  kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" logs deployment/mirror-1-importer --tail=500 \
    > "${out}/mirror-importer.log" 2>&1 || true
  kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" logs deployment/mirror-1-importer --previous --tail=500 \
    > "${out}/mirror-importer-previous.log" 2>&1 || true
  kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" logs deployment/mirror-1-rest --tail=500 \
    > "${out}/mirror-rest.log" 2>&1 || true
  kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" logs deployment/mirror-1-rest --previous --tail=500 \
    > "${out}/mirror-rest-previous.log" 2>&1 || true
  kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" describe pod -l app.kubernetes.io/component=rest \
    > "${out}/mirror-rest-describe.txt" 2>&1 || true
  kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" describe pod minio-pool-1-0 \
    > "${out}/minio-describe.txt" 2>&1 || true
  kubectl -n "${SOLO_NAMESPACE}" describe pod "block-node-${BLOCK_NODE_ID}-0" --request-timeout=10s \
    > "${out}/block-node-describe.txt" 2>&1 || true
  kubectl -n "${SOLO_NAMESPACE}" get events --field-selector "involvedObject.name=block-node-${BLOCK_NODE_ID}-0" \
    --sort-by=.lastTimestamp --request-timeout=10s > "${out}/block-node-events.txt" 2>&1 || true
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    for name in output/hgcaa.log output/block-node-comms.log data/config/application.properties; do
      kubectl --request-timeout=10s -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- cat "${HAPI_PATH}/${name}" \
        > "${out}/${node}-$(basename "${name}")" 2>/dev/null || true
    done
  done
}

run_step() {
  log "$1"
  shift
  "$@"
}

# No published 0.79.0 binary is needed: node setup supplies the checkout's local JARs.
deploy_stage1_hash_writing() {
  CURRENT_STAGE="stage1-hash-writing"
  log "=== Stage 1 (0.79.0 configuration): dual-write and save wrapped record hashes ==="
  solo keys consensus generate --gossip-keys --tls-keys \
    --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}"
  solo consensus network deploy \
    --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" \
    --application-properties "${APP_PROPS_STAGE1_FILE}" --log4j2-xml "${LOG4J2_XML_PATH}" \
    --pvcs true --release-tag "${DEPLOY_RELEASE_TAG:-${DEPLOY_VERSION_LABEL}}"
  local setup_args=(solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}")
  if [[ -n "${DEPLOY_RELEASE_TAG}" ]]; then
    setup_args+=(--release-tag "${DEPLOY_RELEASE_TAG}")
  else
    # Without an explicit version, setup falls back to Solo's default release (v0.76.4) and looks
    # for that version's staging files instead of the ones prepared by network deploy.
    setup_args+=(--local-build-path "${LOCAL_BUILD_PATH}" --consensus-node-version "${DEPLOY_VERSION_LABEL}")
  fi
  "${setup_args[@]}"
  inject_wraps_env_into_statefulsets
  reapply_application_property_overrides "${APP_PROPS_STAGE1_FILE}"
  solo consensus node start --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" --force-port-forward false
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  if [[ -z "${DEPLOY_RELEASE_TAG}" ]]; then
    verify_local_build_on_consensus_nodes
  else
    verify_release_version_on_consensus_nodes "${DEPLOY_RELEASE_TAG#v}"
  fi
  verify_runtime_config_on_consensus_nodes "stage 1 hash writing" \
    "contracts.chainId=298" "nodes.gossipFqdnRestricted=false" \
    "hedera.config.version=0" \
    "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk=true" \
    "hedera.recordStream.wrappedRecordHashesDir=/opt/hgcapp/services-hedera/HapiApp2.0/data/wrappedRecordHashes" \
    "hedera.recordStream.liveWritePrevWrappedRecordHashes=false" \
    "hedera.recordStream.computeHashesFromWrappedRecordBlocks=false" \
    "blockStream.streamMode=BOTH" "blockStream.writerMode=FILE_AND_GRPC" \
    "blockStream.streamWrappedRecordBlocks=true" \
    "blockStream.enableCutover=false" "blockStream.jumpstart.blockNum=-1" \
    "tss.hintsEnabled=false" "tss.historyEnabled=false" \
    "tss.forceMockSignatures=true"
}

wrapped_hash_file_size() {
  kubectl -n "${SOLO_NAMESPACE}" exec "network-$1-0" -c root-container -- sh -c \
    "wc -c < '${WRAPPED_RECORD_HASHES_FILE}'" 2>/dev/null | tr -d '[:space:]'
}

verify_wrapped_hash_files_grow() {
  local node before after deadline nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  nudge_consensus_with_transactions
  for node in "${nodes[@]}"; do
    deadline=$((SECONDS + 120))
    before=0
    while (( SECONDS < deadline )); do
      before="$(wrapped_hash_file_size "${node}" || echo 0)"
      [[ "${before}" =~ ^[0-9]+$ ]] && (( before > 0 )) && break
      sleep 2
    done
    if [[ ! "${before}" =~ ^[0-9]+$ ]] || (( before == 0 )); then
      echo "${node}: no wrapped record hashes written" >&2
      return 1
    fi
    nudge_consensus_with_transactions
    deadline=$((SECONDS + 120))
    after=0
    while (( SECONDS < deadline )); do
      after="$(wrapped_hash_file_size "${node}" || echo 0)"
      [[ "${after}" =~ ^[0-9]+$ ]] && (( after > before )) && break
      sleep 2
    done
    if [[ ! "${after}" =~ ^[0-9]+$ ]] || (( after <= before )); then
      echo "${node}: wrapped hashes did not grow (${before} -> ${after})" >&2
      return 1
    fi
    log "${node}: wrapped hashes grew ${before} -> ${after} bytes"
  done
}

# Generate jumpstart.bin by downloading records, compressing day archives and running blocks wrap.
generate_stage2_jumpstart() {
  log "Generating jumpstart.bin from stage 1 record streams"
  MIRROR_REST_URL="http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
  export MIRROR_REST_URL
  wait_for_http_ok_simple "${MIRROR_REST_URL}/api/v1/blocks?limit=1" 36 5
  local block node deadline verification_rc nodes=()
  block="$(curl -fsS "${MIRROR_REST_URL}/api/v1/blocks?order=desc&limit=1" | jq -er '.blocks[0].number | select(. > 0)')"
  download_solo_minio_record_streams "${block}" "${MIRROR_REST_URL}"
  prepare_wrap_day_archives_from_record_streams
  generate_block_node_metadata_from_mirror "${block}"
  run_block_node_wrap_tool "${WRAP_COMPRESSED_DAYS_DIR}" "${WRAPPED_BLOCKS_DIR}"
  load_jumpstart_env_from_bin "${JUMPSTART_BIN_PATH}"
  [[ "${JUMPSTART_BLOCK_NUMBER}" == "${block}" ]] || { echo "Wrapping ended at ${JUMPSTART_BLOCK_NUMBER}, expected ${block}" >&2; return 1; }

  # Produce newer hashes for the migration to continue from the historical jumpstart point.
  nudge_consensus_with_transactions
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  mkdir -p "${GENERATED_DIR}/stage1-hashes"
  for node in "${nodes[@]}"; do
    deadline=$((SECONDS + 120))
    while true; do
      if kubectl -n "${SOLO_NAMESPACE}" exec "network-${node}-0" -c root-container -- cat "${WRAPPED_RECORD_HASHES_FILE}" \
          > "${GENERATED_DIR}/stage1-hashes/${node}.pb"; then
        verification_rc=0
        python3 "${SCRIPT_DIR}/verify-jumpstart-hashes.py" "${JUMPSTART_BIN_PATH}" "${GENERATED_DIR}/stage1-hashes/${node}.pb" || verification_rc=$?
        case "${verification_rc}" in
          0) break ;;
          2) ;; # The asynchronous writer has not finished the required entries yet.
          *) return "${verification_rc}" ;;
        esac
      fi
      if (( SECONDS >= deadline )); then
        echo "${node}: saved hashes did not match jumpstart within 120s" >&2
        return 1
      fi
      sleep 2
    done
  done
  cp "${APP_PROPS_STAGE2_FILE}" "${TMP_STAGE2_APP_PROPS}"
  cat >> "${TMP_STAGE2_APP_PROPS}" <<PROPS

# Generated from Block Node offline wrapping; include both hashes to require on-disk verification.
blockStream.jumpstart.blockNum=${JUMPSTART_BLOCK_NUMBER}
blockStream.jumpstart.previousWrappedRecordBlockHash=${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}
blockStream.jumpstart.currentBlockConsensusTimestampHash=${JUMPSTART_CONSENSUS_TIMESTAMP_HASH}
blockStream.jumpstart.currentBlockOutputItemsTreeRootHash=${JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH}
blockStream.jumpstart.streamingHasherLeafCount=${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}
blockStream.jumpstart.streamingHasherHashCount=${JUMPSTART_STREAMING_HASHER_HASH_COUNT}
blockStream.jumpstart.streamingHasherSubtreeHashes=${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES}
PROPS
}

upgrade_stage2_jumpstart_and_tss() {
  CURRENT_STAGE="stage2-jumpstart-tss"
  log "=== Stage 2 (0.79.1 configuration): consume jumpstart and enable TSS ==="
  upgrade_local_stage "${TMP_STAGE2_APP_PROPS}"
  verify_runtime_config_on_consensus_nodes "stage 2 jumpstart + TSS" \
    "contracts.chainId=298" "nodes.gossipFqdnRestricted=false" \
    "hedera.config.version=1" \
    "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk=true" \
    "hedera.recordStream.computeHashesFromWrappedRecordBlocks=true" \
    "hedera.recordStream.liveWritePrevWrappedRecordHashes=true" \
    "blockStream.streamMode=BOTH" "blockStream.writerMode=FILE_AND_GRPC" \
    "blockStream.streamWrappedRecordBlocks=true" "blockStream.enableCutover=false" \
    "tss.hintsEnabled=true" "tss.historyEnabled=true" "tss.forceMockSignatures=true" \
    "blockStream.jumpstart.blockNum=${JUMPSTART_BLOCK_NUMBER}" \
    "blockStream.jumpstart.previousWrappedRecordBlockHash=${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}" \
    "blockStream.jumpstart.currentBlockConsensusTimestampHash=${JUMPSTART_CONSENSUS_TIMESTAMP_HASH}" \
    "blockStream.jumpstart.currentBlockOutputItemsTreeRootHash=${JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH}" \
    "blockStream.jumpstart.streamingHasherLeafCount=${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}" \
    "blockStream.jumpstart.streamingHasherHashCount=${JUMPSTART_STREAMING_HASHER_HASH_COUNT}"
  local node nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    # Completion follows the current-block hash match and range validation in the migration.
    kubectl -n "${SOLO_NAMESPACE}" exec "network-${node}-0" -c root-container -- sh -ec "
      grep -F 'Successfully loaded jumpstart config: blockNumber=${JUMPSTART_BLOCK_NUMBER},' '${HAPI_PATH}/output/hgcaa.log'
      grep -F 'Computed wrapped record block hash migration result (state write deferred)' '${HAPI_PATH}/output/hgcaa.log'
      grep -F 'Truncated wrapped record hashes file' '${HAPI_PATH}/output/hgcaa.log'
      grep -F 'Seeded live wrapped-record hash chain from migration result' '${HAPI_PATH}/output/hgcaa.log'
    "
  done
}

verify_stage2_migration_votes() {
  local node expected_block nodes=()
  parse_migration_vote_from_hgcaa
  expected_block="${MIGRATION_BLOCK_NUMBER}"
  run_replay_wrap_to_migration "${MIRROR_REST_URL}" "${expected_block}"
  [[ "${JUMPSTART_BLOCK_NUMBER}" == "${expected_block}" ]] || { echo "Replay block did not match migration" >&2; return 1; }
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    NODE_ALIASES="${node}" parse_migration_vote_from_hgcaa
    [[ "${MIGRATION_BLOCK_NUMBER}" == "${expected_block}" ]] || { echo "${node}: migration block disagrees" >&2; return 1; }
    MIGRATION_COMPARE_LOG="${GENERATED_DIR}/${node}-migration-compare.log" compare_replay_to_migration_vote
  done
}

# ======================================================================================
# Mirror node + explorer. The explorer is a UI over the mirror node's REST API, so both are
# deployed together. Best-effort: deployment failures warn but do not abort the core cutover
# test. Independent of the BN path (the BN uses the pre-seeded RSA bootstrap roster, not the
# mirror REST endpoint).
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

# Configure automatic cutover at initial deployment. Record downloading remains active
# until native block ingestion succeeds; block.enabled and record.enabled cannot both be true.
# Pin the source to BN so stage 1's preview block files in MinIO cannot trigger cutover.
write_mirror_node_values_override() {
  cat > "${MIRROR_NODE_VALUES_FILE}" <<EOF
rest:
  resources:
    requests:
      cpu: 100m
      memory: ${MIRROR_REST_MEMORY_REQUEST:-256Mi}
    limits:
      cpu: '2'
      memory: ${MIRROR_REST_MEMORY_LIMIT:-512Mi}
restjava:
  resources:
    requests:
      memory: ${MIRROR_RESTJAVA_MEMORY_REQUEST}
    limits:
      memory: ${MIRROR_RESTJAVA_MEMORY_LIMIT}
importer:
  resources:
    requests:
      cpu: 100m
      memory: ${MIRROR_IMPORTER_MEMORY_REQUEST:-512Mi}
    limits:
      cpu: '2'
      memory: ${MIRROR_IMPORTER_MEMORY_LIMIT:-1Gi}
  env:
    HIERO_MIRROR_IMPORTER_DOWNLOADER_RECORD_ENABLED: 'true'
    HIERO_MIRROR_IMPORTER_BLOCK_ENABLED: 'false'
    HIERO_MIRROR_IMPORTER_BLOCK_SOURCETYPE: 'BLOCK_NODE'
    HIERO_MIRROR_IMPORTER_BLOCK_CUTOVER_ENABLED: 'true'
    HIERO_MIRROR_IMPORTER_BLOCK_CUTOVER_HAPIVERSION: '${MIRROR_BLOCK_CUTOVER_HAPIVERSION}'
    HIERO_MIRROR_IMPORTER_BLOCK_NODES_0_ENDPOINTS_0_HOST: 'block-node-${BLOCK_NODE_ID}.${SOLO_NAMESPACE}.svc.cluster.local'
    HIERO_MIRROR_IMPORTER_BLOCK_NODES_0_ENDPOINTS_0_PORT: '${BLOCK_NODE_GRPC_PORT}'
grpc:
  resources:
    requests:
      cpu: 100m
      memory: ${MIRROR_GRPC_MEMORY_REQUEST:-256Mi}
    limits:
      cpu: '1'
      memory: ${MIRROR_GRPC_MEMORY_LIMIT:-512Mi}
web3:
  resources:
    requests:
      cpu: 100m
      memory: ${MIRROR_WEB3_MEMORY_REQUEST:-256Mi}
    limits:
      cpu: '1'
      memory: ${MIRROR_WEB3_MEMORY_LIMIT:-512Mi}
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
    "${MIRROR_REST_LOCAL_PORT}:http" >"${GENERATED_DIR}/mirror-rest-port-forward.log" 2>&1 < /dev/null &
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
      solo mirror node add --deployment "${SOLO_DEPLOYMENT}" --enable-ingress --force-port-forward false \
      --mirror-node-version "${MIRROR_NODE_VERSION}" \
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
      solo explorer node add --deployment "${SOLO_DEPLOYMENT}" --force-port-forward false; then
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

# MN stays at its initially deployed version. Its preconfigured cutover service switches
# from records to BN after record production ceases, without a chart update or pod restart.
verify_mirror_after_block_cutover() {
  log "Waiting for the preconfigured mirror importer (${MIRROR_NODE_VERSION}) after cutover"
  deployment_ready mirror-1-importer 180
  deployment_ready mirror-1-rest 180
  start_mirror_rest_port_forward
  wait_for_http_ok_simple "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1" 36 5
}

upgrade_local_stage() {
  local properties_file="$1"
  adopt_node_data_config_maps_into_helm
  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --local-build-path "${LOCAL_BUILD_PATH}"
    --application-properties "${properties_file}"
    --skip-node-start
    --quiet-mode
    --force
  )
  run_command_with_timeout "${SOLO_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"
  reapply_application_property_overrides "${properties_file}"
  solo consensus node start \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --force-port-forward false
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  verify_local_build_on_consensus_nodes
}

upgrade_stage3_cutover() {
  CURRENT_STAGE="stage3-cutover"
  log "=== Stage 3 (0.80.0 configuration): BLOCKS-only cutover with real TSS ==="
  upgrade_local_stage "${APP_PROPS_STAGE3_FILE}"

  # Keep the stage 2 BN running through cutover unless a separate upgrade is requested.
  if [[ "${BLOCK_NODE_UPGRADE_VERSION}" != "${BLOCK_NODE_CHART_VERSION}" ]]; then
    log "--- stage 3 step: upgrade Block Node ${BLOCK_NODE_ID} to ${BLOCK_NODE_UPGRADE_VERSION} ---"
    # `block node upgrade` rebuilds block-nodes.json through the same helper and runs after the nodes
    # are already up, so re-applying the properties afterwards would not take effect until the next
    # restart. Use Solo's environment override here so the on-disk file never drifts back.
    env "BLOCK_STREAM_WRITER_MODE=${BLOCK_STREAM_WRITER_MODE_CUTOVER}" \
      solo block node upgrade \
      --deployment "${SOLO_DEPLOYMENT}" \
      --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
      --id "${BLOCK_NODE_ID}" \
      --upgrade-version "${BLOCK_NODE_UPGRADE_VERSION}" \
      --quiet-mode
    # Same StatefulSet race seed_block_node_tss_parameters documents: an in-place `block node upgrade`
    # rolls the StatefulSet, and `wait --for=condition=ready pod/<name>` matches the OLD pod -- still
    # Ready while it terminates -- so it returns before the upgraded pod exists. The script then races
    # ahead and the post-cutover BN port-forward finds no ready endpoint behind svc/block-node-N.
    # Block on the rollout first, then confirm the recreated pod.
    kubectl -n "${SOLO_NAMESPACE}" rollout status "statefulset/block-node-${BLOCK_NODE_ID}" \
      --timeout="${BLOCK_NODE_READY_TIMEOUT_SECS}s"
    kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/block-node-${BLOCK_NODE_ID}-0" --timeout="${BLOCK_NODE_READY_TIMEOUT_SECS}s"
  fi

  log "--- stage 3 check 1/4: wait for consensus pods + haproxy + verify local-build version ---"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  verify_local_build_on_consensus_nodes
  verify_runtime_config_on_consensus_nodes "stage 3 cutover" \
    "contracts.chainId=298" "nodes.gossipFqdnRestricted=false" \
    "hedera.config.version=2" \
    "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk=false" \
    "blockStream.jumpstart.blockNum=-1" \
    "nodes.nodeRewardsEnabled=false" \
    "blockStream.streamMode=BLOCKS" \
    "blockStream.writerMode=GRPC" \
    "blockStream.enableCutover=true" \
    "blockStream.streamWrappedRecordBlocks=false" \
    "blockStream.buffer.isBufferPersistenceEnabled=true" \
    "tss.hintsEnabled=true" \
    "tss.historyEnabled=true" \
    "tss.forceMockSignatures=false"

  log "--- stage 3 check 2/4: nudge consensus with cryptoCreate txns ---"
  nudge_consensus_with_transactions

  # A history proof constructed during stage 2 may be reused after the upgrade. Real block
  # signing is asserted by forceMockSignatures=false above and BN verification below.
  log "--- stage 3 check 3/4: verify WRAPS runtime + history proof construction ---"
  verify_wraps_on_consensus_nodes 600

  log "--- stage 3 check 4/4: restart ${RESTART_REPLAY_NODE} and confirm clean replay (no SELF_ISS) ---"
  verify_node_replays_without_iss
}

# Open a CN forward for the whole suite; transaction nudges only keep theirs temporarily.
start_tck_consensus_port_forward() {
  if wait_for_tcp_open 127.0.0.1 "${CN_GRPC_LOCAL_PORT}" 1 1; then
    return 0
  fi
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc \
    "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" \
    > "${CN_PORT_FORWARD_LOG}" 2>&1 < /dev/null &
  TCK_CN_PORT_FORWARD_PID=$!
  disown "${TCK_CN_PORT_FORWARD_PID}" 2>/dev/null || true
  if ! wait_for_tcp_open 127.0.0.1 "${CN_GRPC_LOCAL_PORT}" 30 1; then
    cat "${CN_PORT_FORWARD_LOG}" >&2
    return 1
  fi
}

run_post_cutover_tck() {
  if [[ "${ENABLE_TCK_TESTS}" != true ]]; then
    log "SDK TCK skipped (ENABLE_TCK_TESTS=false)"
    return 0
  fi
  CURRENT_STAGE="sdk-tck"
  log "=== SDK TCK regression against the BLOCKS-only network ==="
  # GENERATED_DIR can survive previous runs; never summarize stale reports as this run's results.
  local report
  for report in "${TCK_REPORT_EXPORT_DIR}"/mochawesome*.json "${TCK_REPORT_EXPORT_DIR}"/mochawesome*.html; do
    rm -f "${report}"
  done
  prepare_tck_repos
  install_tck_client_dependencies
  start_tck_consensus_port_forward
  start_mirror_rest_port_forward
  ensure_tck_port_forwards
  start_tck_sdk_server
  fund_tck_operator_account

  local tck_rc=0
  run_tck_tests || tck_rc=$?
  if [[ -z "${TCK_TEST_FILES}" ]]; then
    export_tck_report || true
  fi
  print_tck_report_summary || true
  if (( tck_rc != 0 )); then
    echo "SDK TCK FAILED (rc=${tck_rc}); report: ${TCK_REPORT_EXPORT_DIR}" >&2
    return "${tck_rc}"
  fi
  log "PASS: SDK TCK ${TCK_STATS_SUMMARY:-suite passed (report statistics unavailable)}"
}

# shellcheck source=cutover-jumpstart.sh
source "${SCRIPT_DIR}/cutover-jumpstart.sh"
# shellcheck source=cutover-tck.sh
source "${SCRIPT_DIR}/cutover-tck.sh"
# shellcheck source=cutover-validation.sh
source "${SCRIPT_DIR}/cutover-validation.sh"

log "Validating prerequisites"
for cmd in kind kubectl docker helm solo curl unzip node npm jq openssl grpcurl python3 java git; do
  require_cmd "${cmd}"
done
[[ "${DOCKER_MIN_MEMORY_MIB}" =~ ^[1-9][0-9]*$ ]] || { echo "DOCKER_MIN_MEMORY_MIB must be a positive integer" >&2; exit 1; }
verify_docker_memory_budget
case "${ENABLE_TCK_TESTS}" in
  true) require_cmd pgrep; ensure_pnpm_available ;;
  false) ;;
  *) echo "ENABLE_TCK_TESTS must be true or false" >&2; exit 1 ;;
esac
for setting in MIRROR_VALIDATION_TX_COUNT BN_ISOLATION_TX_COUNT MIRROR_ACCOUNT_WAIT_SECS \
    MN_BLOCK_PROGRESS_MIN_DELTA MN_BLOCK_PROGRESS_TIMEOUT_SECS MN_BLOCK_PROGRESS_POLL_SECS \
    BN_GET_BLOCK_MAX_PER_CHECK RECORD_CESSATION_SETTLE_SECS MINIO_RESTORE_TIMEOUT_SECS; do
  [[ "${!setting}" =~ ^[1-9][0-9]*$ ]] || {
    echo "${setting} must be a positive integer" >&2
    exit 1
  }
done
prepare_block_node_tools_checkout
validate_block_node_repo
[[ -x "${BLOCK_NODE_REPO_PATH}/gradlew" ]] || { echo "Block Node tools require an executable gradlew" >&2; exit 1; }
for file in "${APP_PROPS_STAGE1_FILE}" "${APP_PROPS_STAGE2_FILE}" "${APP_PROPS_STAGE3_FILE}" "${LOG4J2_XML_PATH}"; do
  [[ -f "${file}" ]] || { echo "Missing file: ${file}" >&2; exit 1; }
done
validate_local_build_path "${LOCAL_BUILD_PATH}" || {
  echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}; run ./gradlew assemble first" >&2
  exit 1
}

# Each run must wrap only its own records. Keep prior per-stage diagnostics until overwritten.
rm -rf "${RECORD_STREAMS_DIR}" "${WRAPPED_BLOCKS_DIR}" "${REPLAY_WRAPPED_BLOCKS_DIR}"
create_cluster
configure_solo
setup_cluster_prereqs
deploy_stage1_hash_writing
verify_wrapped_hash_files_grow
# Mirror is required to discover the record files and metadata for offline wrapping.
deploy_mirror_and_explorer
# Re-establish/probe the REST forward in case Solo's explorer command affected it.
start_mirror_rest_port_forward
validate_fresh_mirror_ingestion
generate_stage2_jumpstart
collect_stage_diagnostics "stage1-hash-writing"

upgrade_stage2_jumpstart_and_tss
# Only now add BN v0.44.2, after jumpstart + TSS. Give it a floor above the current stream.
BLOCK_NODE_CUTOVER_START_BLOCK="${BLOCK_NODE_CUTOVER_START_BLOCK:-$((JUMPSTART_BLOCK_NUMBER + 1000))}"
BLOCK_STREAM_WRITER_MODE=FILE_AND_GRPC deploy_block_node
reapply_application_property_overrides "${TMP_STAGE2_APP_PROPS}"
verify_block_node_has_blocks 180
nudge_consensus_with_transactions
verify_wraps_on_consensus_nodes 600
verify_stage2_migration_votes
seed_block_node_tss_parameters
wait_for_block_node_caught_up 180
validate_fresh_mirror_ingestion
collect_stage_diagnostics "stage2-jumpstart-tss"

upgrade_stage3_cutover
verify_block_node_persists_post_cutover 300
verify_mirror_after_block_cutover
CURRENT_STAGE="post-cutover-validation"
verify_record_uploads_ceased_baseline
verify_bn_ingestion_without_minio
collect_stage_diagnostics "stage3-cutover"
run_post_cutover_tck
cleanup_tck_resources
verify_post_tck_continuity

log "PASS: three-stage hash writing -> jumpstart + TSS -> BLOCKS-only cutover"
log "PASS: Block Node ${BLOCK_NODE_UPGRADE_VERSION} verified and persisted real-TSS blocks"
log "Explorer UI: http://127.0.0.1:${EXPLORER_INGRESS_LOCAL_PORT}    Mirror REST: http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
