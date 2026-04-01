#!/usr/bin/env bash
# Block Stream Cutover
#- [N/A] v0.70.1
#- [X] v0.71.2 (Records and Blocks) Genesis, No Block Nodes
#- [X] Upgrade to v0.72.0-rc.4
#    - [X] Wrapped record file hashes written to disk
#    - [X] No block nodes
#    - [] We can use dummy jumpstart data until we implement running the block node offline wrapping tool against the record files in the cloud bucket
#        - [ ] Produces jumpstart.bin file for the CN’s
#        - [ ] Issue a File 121 update with the jumpstart information
#           - blockStream.jumpstart.blockNum
             #blockStream.jumpstart.previousWrappedRecordBlockHash
             #blockStream.jumpstart.streamingHasherLeafCount
             #blockStream.jumpstart.streamingHasherHashCount
             #blockStream.jumpstart.streamingHasherSubtreeHashes
#    - [ ] Optional - https://github.com/hiero-ledger/hiero-block-node/blob/main/tools-and-tests/tools/src/main/java/org/hiero/block/tools/blocks/ToWrappedBlocksCommand.java
#        - We will run the offline tool up a certain block number N which would be equivalent in production to running the tool up to 10 days prior to the upgrade
#           to release/0.73
#        - Checkout the Block Node repository into a directory, use branch driley/local-wrapped-record-files
#        - ./gradlew :tools:run --args="blocks wrap -i /absolute/path/to/your/recordstreams -o /absolute/path/to/wrappedBlocks"

#        jumpstart.bin is a compact binary file written in this exact order:
#        1. Block number: long (8 bytes)
#        2. Previous block root hash: raw SHA-384 bytes (48 bytes)
#        3. Streaming hasher leaf count: long (8 bytes)
#        4. Streaming hasher hash count: int (4 bytes)
#        5. Pending subtree hashes: hashCount entries, each 48 bytes (SHA-384)
#        So total size is:
#        * 68 + (hashCount * 48) bytes
#        All integer fields are Java DataOutputStream format (big-endian).

#- [ ] Upgrade to v0.73.0 -> local build with appropriate application.properties overrides (no block nodes)
#    - [ ] *** Use WRAPS proving key, verification produced by ceremony
#         - TSS Library Requires env var to be set
#             environment.put("TSS_LIB_WRAPS_ARTIFACTS_PATH", System.getProperty("hapi.spec.tssLibWrapsArtifactsPath", ""));
#    - [ ] Enabling Feature flags
             #tss.wrapsProvingKeyPath=
             #tss.wrapsProvingKeyHash=
             #tss.wrapsProvingKeyDownloadUrl=?
             #tss.hintsEnabled = true
             #tss.historyEnabled = true
             #tss.wrapsEnabled = true
             #hedera.recordSream.computeHashesFromWrappedRecordBlocks = true
             #hedera.recordStream.liveWritePrevWrappedRecordHashes = true
             #blockStream.cutoverEnabled = false (*only used for when we cutover to BLOCKS only)
             #blockStream.enableStateProofs = true
             #tss.forceMockSignatures = true
#    - [ ] Use jumpstart info + 0.72’s wrapped record hashes to get the wrapped record block root hash of the freeze block
#       - [ ] Voting process occurs in which each CN votes on the above wrapped record block root hash
#    - [ ] CN’s start live wrapping of record files produced and put wrapped record block root hashes into state (BlockInfo singleton)
#       - We can verify this either through state changes or hgcaa log
#       - Optional - We should also confirm via running the block node offline tool up to the freeze block that we put together the hashes in the same way (same freeze block root hash)
#    - [ ] Before the upgrade to v0.74.0 Deploy 2 block nodes
#- [ ] Upgrade to v0.74.0 -> local build with appropriate application.properties overrides
#    - [ ] Enabling Feature flags
#       - blockStream.streamMode=BLOCKS
#       - blockStream.writerMode=GRPC
#       - blockStream.buffer.isBufferPersistenceEnabled=true
#       - tss.forceMockSignatures = false
#       - blockStream.cutoverEnabled = true
#       - tss.validateBlockSignatures=true ****? When do we set this
#       - Remove from code - hedera.recordStream.computeHashesFromWrappedRecordBlocks
#       - Remove from code - hedera.recordStream.liveWritePrevWrappedRecordHashes
#       - Remove from code - hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk
#    - [ ] Solo should add block-nodes.json to each CN in the deployment
#    - [ ] Solo Upgrade the mirror-node to force block node integration (—force)
#    - [ ] The Block Node validates TSS signatures on blocks
#    - [ ] Run State validator or StateChangesValidator as verifications
#- [ ] Perform more software upgrades of CN to simulate v0.75.0, v0.76.0, etc. and ensure blocks keep flowing e2e
#- [ ] Perform rolling upgrades of block nodes and ensure block keep flowing e2e

set -eo pipefail
set +m

NODE_COUNT_PARAM=""
ONLY_STEP6="${ONLY_STEP6:-false}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--nodes)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for $1 (expected 3 or 4)" >&2
        exit 1
      fi
      NODE_COUNT_PARAM="$2"
      shift 2
      ;;
    --nodes=*)
      NODE_COUNT_PARAM="${1#*=}"
      shift
      ;;
    --only-step6|--step6-only)
      ONLY_STEP6="true"
      shift
      ;;
    -h|--help)
      cat <<'EOF'
Usage: solo-e2e-block-stream-cutover.sh [--nodes 3|4] [--only-step6]

Options:
  -n, --nodes 3|4   Number of consensus nodes to deploy.
                    3 => node1,node2,node3
                    4 => node1,node2,node3,node4
                    If omitted, NODE_ALIASES env var (or default node1,node2,node3,node4) is used.
  --only-step6      Reuse existing deployment and run only Step 6 (File 121 + 0.73 upgrade path).

Environment:
  BLOCK_NODE_REPO_PATH      Path to hiero-block-node checkout (default: ../hiero-block-node)
  USE_BLOCK_NODE_JUMPSTART  true|false (default: true)
  BLOCKS_WRAP_EXTRA_ARGS    Extra args appended to `blocks wrap ...`
  JUMPSTART_BIN_PATH        Optional explicit jumpstart.bin path (if tool writes elsewhere)
  APP_PROPS_073_FILE         application.properties for the 0.73 consensus upgrade
                            (default: resources/0.73/application.properties next to this script)
  SOLO_073_NODE_COPY_CONCURRENT  Solo NODE_COPY_CONCURRENT for 0.73 step (default: 1)
  SOLO_073_LOCAL_BUILD_COPY_RETRY Solo LOCAL_BUILD_COPY_RETRY for 0.73 step (default: 300)
  FILE121_UPDATE_RETRIES         Retry count for Step 6 File 121 update (default: 3)
  FILE121_UPDATE_RETRY_DELAY_SECS Delay before retrying File 121 update (default: 15)
  SDK_READY_RETRIES              Retry count for SDK readiness probe before Step 6 transactions (default: 8)
  SDK_READY_RETRY_DELAY_SECS     Delay between SDK readiness probe retries (default: 10)
  EXPLORER_ADD_RETRIES           Retry count for explorer chart deployment in baseline setup (default: 3)
  EXPLORER_ADD_RETRY_DELAY_SECS  Delay between explorer deployment retries (default: 20)
  ALLOW_EXPLORER_DEPLOY_FAILURE  true|false, continue baseline when explorer deploy fails after retries (default: true)
  ONLY_STEP6                true|false (same as --only-step6 flag)
EOF
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Use --help for usage." >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../.." && pwd)"

export SOLO_CLUSTER_NAME="solo"
export SOLO_NAMESPACE="solo"
export SOLO_CLUSTER_SETUP_NAMESPACE="solo-cluster"
export SOLO_DEPLOYMENT="solo-deployment"
if [[ -n "${NODE_COUNT_PARAM}" ]]; then
  case "${NODE_COUNT_PARAM}" in
    3) NODE_ALIASES="node1,node2,node3" ;;
    4) NODE_ALIASES="node1,node2,node3,node4" ;;
    *)
      echo "Invalid --nodes value: ${NODE_COUNT_PARAM} (expected 3 or 4)" >&2
      exit 1
      ;;
  esac
else
  NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3,node4}"
fi
CONSENSUS_NODE_COUNT="$(awk -F',' '{print NF}' <<< "${NODE_ALIASES}")"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
LOG4J2_XML_PATH="${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml"
APP_PROPS_071_FILE="${SCRIPT_DIR}/resources/0.71/application.properties"
APP_PROPS_072_FILE="${SCRIPT_DIR}/resources/0.72/application.properties"
APP_PROPS_073_FILE="${APP_PROPS_073_FILE:-${SCRIPT_DIR}/resources/0.73/application.properties}"
INITIAL_RELEASE_TAG="${INITIAL_RELEASE_TAG:-v0.71.2}"
UPGRADE_072_RELEASE_TAG="${UPGRADE_072_RELEASE_TAG:-v0.72.0-rc.2}"
UPGRADE_073_RELEASE_TAG="${UPGRADE_073_RELEASE_TAG:-0.73.0}"
# After "Update node configuration files", Solo Helm-rolls pods then kubectl-cp's LOCAL_BUILD_PATH in parallel (Solo default 4).
# On kind, concurrent large copies can hit pods still in Failed/Terminating — serialize copies unless overridden.
SOLO_073_NODE_COPY_CONCURRENT="${SOLO_073_NODE_COPY_CONCURRENT:-1}"
SOLO_073_LOCAL_BUILD_COPY_RETRY="${SOLO_073_LOCAL_BUILD_COPY_RETRY:-300}"
FILE121_UPDATE_RETRIES="${FILE121_UPDATE_RETRIES:-3}"
FILE121_UPDATE_RETRY_DELAY_SECS="${FILE121_UPDATE_RETRY_DELAY_SECS:-15}"
SDK_READY_RETRIES="${SDK_READY_RETRIES:-8}"
SDK_READY_RETRY_DELAY_SECS="${SDK_READY_RETRY_DELAY_SECS:-10}"
EXPLORER_ADD_RETRIES="${EXPLORER_ADD_RETRIES:-3}"
EXPLORER_ADD_RETRY_DELAY_SECS="${EXPLORER_ADD_RETRY_DELAY_SECS:-20}"
ALLOW_EXPLORER_DEPLOY_FAILURE="${ALLOW_EXPLORER_DEPLOY_FAILURE:-true}"

# SHA-384 hashes are 48 bytes => 96 hex chars.
SHA384_ZERO_HEX="$(printf '0%.0s' {1..96})"
SHA384_ONE_HEX="$(printf '1%.0s' {1..96})"

# Placeholders for File 121 jumpstart-related network properties (until real jumpstart.bin tooling fills these).
JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH="${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH:-${SHA384_ZERO_HEX}}"
JUMPSTART_STREAMING_HASHER_LEAF_COUNT="${JUMPSTART_STREAMING_HASHER_LEAF_COUNT:-1}"
JUMPSTART_STREAMING_HASHER_HASH_COUNT="${JUMPSTART_STREAMING_HASHER_HASH_COUNT:-1}"
# Comma-separated dummy subtree hashes (placeholder until real jumpstart tooling).
JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES="${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES:-${SHA384_ONE_HEX}}"
export JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH
export JUMPSTART_STREAMING_HASHER_LEAF_COUNT
export JUMPSTART_STREAMING_HASHER_HASH_COUNT
export JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES

CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
MIRROR_REST_SERVICE="${MIRROR_REST_SERVICE:-mirror-1-rest}"
GRAFANA_LOCAL_PORT="${GRAFANA_LOCAL_PORT:-3000}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"
# If true, script continues when Grafana forwarding cannot be established.
ALLOW_GRAFANA_PORT_FORWARD_FAILURE="${ALLOW_GRAFANA_PORT_FORWARD_FAILURE:-true}"

# Downloaded record stream objects from Solo MinIO (Step 5), next to this script.
RECORD_STREAMS_DIR="${RECORD_STREAMS_DIR:-${SCRIPT_DIR}/recordStreams}"
WRAPPED_BLOCKS_DIR="${WRAPPED_BLOCKS_DIR:-${SCRIPT_DIR}/wrappedBlocks}"
MINIO_BUCKET="${MINIO_BUCKET:-solo-streams}"
MINIO_NAMESPACE="${MINIO_NAMESPACE:-${SOLO_NAMESPACE}}"
# Optional overrides if auto-discovery fails (service name in MINIO_NAMESPACE).
MINIO_SERVICE_NAME="${MINIO_SERVICE_NAME:-}"

# Block Node offline wrapping tool configuration (Step 5 jumpstart generation).
USE_BLOCK_NODE_JUMPSTART="${USE_BLOCK_NODE_JUMPSTART:-true}"
BLOCK_NODE_REPO_PATH="${BLOCK_NODE_REPO_PATH:-${REPO_ROOT}/../hiero-block-node}"
BLOCKS_WRAP_EXTRA_ARGS="${BLOCKS_WRAP_EXTRA_ARGS:-}"
JUMPSTART_BIN_PATH="${JUMPSTART_BIN_PATH:-}"

OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"

WORK_DIR="$(mktemp -d)"
NODE_SCRIPT="${WORK_DIR}/sdk-crypto-create-check.js"
NETWORK_PROBE_SCRIPT="${WORK_DIR}/sdk-network-probe.js"
FILE_121_JUMPSTART_SCRIPT="${WORK_DIR}/file-121-jumpstart-update.js"
JUMPSTART_PARSE_SCRIPT="${WORK_DIR}/parse-jumpstart-bin.js"
BLOCK_NODE_WRAP_LOG="${WORK_DIR}/block-node-wrap.log"
MIRROR_METADATA_LOG="${WORK_DIR}/mirror-metadata.log"
WRAP_INPUT_PREP_LOG="${WORK_DIR}/wrap-input-prep.log"
MINIO_DOWNLOAD_LOG="${WORK_DIR}/minio-download.log"
EXPLORER_ADD_LOG="${WORK_DIR}/explorer-add.log"
SOLO_073_UPGRADE_LOG="${WORK_DIR}/solo-073-upgrade.log"
CN_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-cn.log"
MIRROR_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-mirror.log"
GRAFANA_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-grafana.log"
BLOCK_TIMES_FILE="${WORK_DIR}/block_times.bin"
DAY_BLOCKS_FILE="${WORK_DIR}/day_blocks.json"
MIRROR_METADATA_SCRIPT="${WORK_DIR}/generate-mirror-metadata.js"
WRAP_DAYS_SRC_DIR="${WORK_DIR}/recordDays"
WRAP_COMPRESSED_DAYS_DIR="${WORK_DIR}/compressedDays"
ZSTD_WRAPPER_DIR="${WORK_DIR}/zstd-wrapper"
ZSTD_WRAPPER_SRC="${ZSTD_WRAPPER_DIR}/ZstdCat.java"
ZSTD_WRAPPER_BIN="${ZSTD_WRAPPER_DIR}/zstd"

CN_PORT_FORWARD_PID=""
MIRROR_PORT_FORWARD_PID=""
GRAFANA_PORT_FORWARD_PID=""
TOTAL_STEPS=6

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

log_banner() {
  local message="$1"
  log "===================================================================="
  log "${message}"
  log "===================================================================="
}

announce_step() {
  local step="$1"
  local description="$2"
  log_banner "STEP ${step}/${TOTAL_STEPS}: ${description}"
}

cleanup() {
  local exit_code=$?

  if [[ ${exit_code} -ne 0 ]]; then
    log "Script failed (exit=${exit_code}); preserving port-forwards and cluster for debugging"
    return
  fi

  if [[ "${KEEP_NETWORK}" == "true" ]]; then
    log "KEEP_NETWORK=true, leaving cluster, deployment, and port-forwards running"
    log "Consensus gRPC: 127.0.0.1:${CN_GRPC_LOCAL_PORT}"
    log "Mirror REST:    http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
    log "Grafana:        http://127.0.0.1:${GRAFANA_LOCAL_PORT}"
    return
  fi

  set +e
  [[ -n "${CN_PORT_FORWARD_PID}" ]] && kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  [[ -n "${MIRROR_PORT_FORWARD_PID}" ]] && kill "${MIRROR_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  [[ -n "${GRAFANA_PORT_FORWARD_PID}" ]] && kill "${GRAFANA_PORT_FORWARD_PID}" >/dev/null 2>&1 || true

  log "Destroying Solo resources and Kind cluster"
  if command -v solo >/dev/null 2>&1; then
    solo explorer node destroy --deployment "${SOLO_DEPLOYMENT}" >/dev/null 2>&1 || true
    solo relay node destroy --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" >/dev/null 2>&1 || true
    solo mirror node destroy --deployment "${SOLO_DEPLOYMENT}" --force >/dev/null 2>&1 || true
    solo block node destroy --deployment "${SOLO_DEPLOYMENT}" >/dev/null 2>&1 || true
    solo consensus node stop --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" >/dev/null 2>&1 || true
    solo consensus network destroy --deployment "${SOLO_DEPLOYMENT}" --force >/dev/null 2>&1 || true
  fi
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true

  rm -rf "${WORK_DIR}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

require_cmd() {
  local cmd="$1"
  command -v "${cmd}" >/dev/null 2>&1 || { echo "Required command not found: ${cmd}" >&2; exit 1; }
}

ensure_zstd_command_for_block_node() {
  local zstd_jar
  if command -v zstd >/dev/null 2>&1; then
    log "Using system zstd: $(command -v zstd)"
    return 0
  fi

  require_cmd java

  zstd_jar="$(find "${HOME}/.gradle/caches/modules-2/files-2.1/com.github.luben/zstd-jni" -name 'zstd-jni-*.jar' 2>/dev/null | head -n 1)"
  if [[ -z "${zstd_jar}" || ! -f "${zstd_jar}" ]]; then
    echo "zstd command not found and zstd-jni jar was not found in ~/.gradle cache." >&2
    echo "Install zstd (for example: brew install zstd) or run one block-node tools task once to download zstd-jni, then retry." >&2
    return 1
  fi

  mkdir -p "${ZSTD_WRAPPER_DIR}"
  cat > "${ZSTD_WRAPPER_SRC}" <<'EOF'
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import com.github.luben.zstd.ZstdInputStream;

public class ZstdCat {
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: ZstdCat <input.zstd>");
      System.exit(2);
    }
    try (InputStream in = new BufferedInputStream(new FileInputStream(args[0]));
         ZstdInputStream zin = new ZstdInputStream(in);
         OutputStream out = new BufferedOutputStream(System.out)) {
      zin.transferTo(out);
      out.flush();
    }
  }
}
EOF

  cat > "${ZSTD_WRAPPER_BIN}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
input=""
for arg in "$@"; do
  case "$arg" in
    --decompress|-d|--stdout|-c) ;;
    -T*|--threads=*) ;;
    --) ;;
    -*) ;;
    *) input="$arg" ;;
  esac
done
if [[ -z "${input}" ]]; then
  echo "zstd wrapper error: missing input file argument" >&2
  exit 2
fi
if [[ -z "${ZSTD_JNI_JAR:-}" || -z "${ZSTD_WRAPPER_SRC:-}" ]]; then
  echo "zstd wrapper error: ZSTD_JNI_JAR or ZSTD_WRAPPER_SRC is not set" >&2
  exit 2
fi
exec java --class-path "${ZSTD_JNI_JAR}" "${ZSTD_WRAPPER_SRC}" "${input}"
EOF
  chmod +x "${ZSTD_WRAPPER_BIN}"

  export ZSTD_JNI_JAR="${zstd_jar}"
  export ZSTD_WRAPPER_SRC
  export PATH="${ZSTD_WRAPPER_DIR}:${PATH}"
  log "zstd command not found; using Java zstd wrapper via zstd-jni (${zstd_jar})"
}

validate_block_node_repo() {
  if [[ ! -d "${BLOCK_NODE_REPO_PATH}" ]]; then
    echo "BLOCK_NODE_REPO_PATH not found: ${BLOCK_NODE_REPO_PATH}" >&2
    echo "Set BLOCK_NODE_REPO_PATH to your hiero-block-node checkout (branch driley/local-wrapped-record-files)." >&2
    return 1
  fi
  if [[ ! -x "${BLOCK_NODE_REPO_PATH}/gradlew" ]]; then
    echo "Block Node gradlew not executable: ${BLOCK_NODE_REPO_PATH}/gradlew" >&2
    return 1
  fi
}

validate_local_build_path() {
  local build_path="$1"
  [[ -d "${build_path}/lib" ]] || { echo "Missing directory: ${build_path}/lib" >&2; return 1; }
  [[ -d "${build_path}/apps" ]] || { echo "Missing directory: ${build_path}/apps" >&2; return 1; }
  compgen -G "${build_path}/lib/*.jar" >/dev/null || { echo "No jar files found in ${build_path}/lib" >&2; return 1; }
  compgen -G "${build_path}/apps/*.jar" >/dev/null || { echo "No jar files found in ${build_path}/apps" >&2; return 1; }
}

wait_for_http_ok() {
  local url="$1"
  local max_attempts="$2"
  local sleep_secs="$3"
  local attempt=1
  while (( attempt <= max_attempts )); do
    curl -sf "${url}" >/dev/null 2>&1 && return 0
    sleep "${sleep_secs}"
    ((attempt++))
  done
  echo "Timed out waiting for HTTP endpoint: ${url}" >&2
  return 1
}

wait_for_tcp_open() {
  local host="$1"
  local port="$2"
  local max_attempts="$3"
  local sleep_secs="$4"
  local attempt=1
  while (( attempt <= max_attempts )); do
    if command -v nc >/dev/null 2>&1; then
      nc -z "${host}" "${port}" >/dev/null 2>&1 && return 0
    else
      (: <"/dev/tcp/${host}/${port}") >/dev/null 2>&1 && return 0
    fi
    sleep "${sleep_secs}"
    ((attempt++))
  done
  echo "Timed out waiting for TCP endpoint: ${host}:${port}" >&2
  return 1
}

kill_processes_on_local_port() {
  local port="$1"
  local pids=""
  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -ti "tcp:${port}" 2>/dev/null || true)"
    if [[ -n "${pids}" ]]; then
      kill ${pids} >/dev/null 2>&1 || true
    fi
  fi
}

cleanup_stale_port_forwards() {
  log "Stopping stale port-forwards from previous runs (if any)"
  pkill -f "port-forward svc/haproxy-node1-svc .*${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >/dev/null 2>&1 || true
  pkill -f "port-forward svc/${MIRROR_REST_SERVICE} .*${MIRROR_REST_LOCAL_PORT}:http" >/dev/null 2>&1 || true
  pkill -f "port-forward svc/kube-prometheus-stack-grafana .*${GRAFANA_LOCAL_PORT}:80" >/dev/null 2>&1 || true
}

mirror_rest_service_exists() {
  kubectl -n "${SOLO_NAMESPACE}" get svc "${MIRROR_REST_SERVICE}" >/dev/null 2>&1
}

ensure_mirror_rest_service_for_step6() {
  local attempt=1
  local max_attempts=60

  if mirror_rest_service_exists; then
    log "ONLY_STEP6: mirror REST service ${MIRROR_REST_SERVICE} already exists"
    return 0
  fi

  log "ONLY_STEP6: mirror REST service ${MIRROR_REST_SERVICE} not found; deploying mirror node"
  solo mirror node add --deployment "${SOLO_DEPLOYMENT}" --enable-ingress --pinger

  while (( attempt <= max_attempts )); do
    if mirror_rest_service_exists; then
      log "ONLY_STEP6: mirror REST service ${MIRROR_REST_SERVICE} is available"
      return 0
    fi
    sleep 5
    ((attempt++))
  done

  echo "Mirror REST service ${MIRROR_REST_SERVICE} was not created after deploying the mirror node" >&2
  return 1
}

cleanup_record_stream_files_only() {
  local removed=0
  mkdir -p "${RECORD_STREAMS_DIR}"
  if [[ -d "${RECORD_STREAMS_DIR}" ]]; then
    removed="$(find "${RECORD_STREAMS_DIR}" \
      -type f \
      -path "${RECORD_STREAMS_DIR}/record0.0.*/*" \
      \( -name "*.rcd" -o -name "*.rcd.gz" -o -name "*.rcd_sig" -o -name "*.rcs_sig" \) \
      -print | wc -l | tr -d ' ')"
    find "${RECORD_STREAMS_DIR}" \
      -type f \
      -path "${RECORD_STREAMS_DIR}/record0.0.*/*" \
      \( -name "*.rcd" -o -name "*.rcd.gz" -o -name "*.rcd_sig" -o -name "*.rcs_sig" \) \
      -delete || true
  fi
  log "Cleaned ${removed} record file(s) under ${RECORD_STREAMS_DIR}/record0.0.*"
}

wait_for_consensus_pods_ready() {
  local timeout_secs="${1:-600}"
  local pod=""
  local nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  for pod in "${nodes[@]}"; do
    log "Waiting for network-${pod}-0 to become Ready"
    kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/network-${pod}-0" --timeout="${timeout_secs}s"
  done
}

capture_consensus_pod_uids() {
  local node
  local nodes=()
  local uids=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    uids+=("$(kubectl -n "${SOLO_NAMESPACE}" get pod "network-${node}-0" -o jsonpath='{.metadata.uid}')")
  done

  (
    IFS=','
    echo "${uids[*]}"
  )
}

wait_for_consensus_pod_recreation() {
  local original_uids_csv="$1"
  local timeout_secs="${2:-900}"
  local deadline=$((SECONDS + timeout_secs))
  local node=""
  local current_uid=""
  local idx=0
  local nodes=()
  local original_uids=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  IFS=',' read -r -a original_uids <<< "${original_uids_csv}"

  while (( SECONDS < deadline )); do
    idx=0
    for node in "${nodes[@]}"; do
      current_uid="$(kubectl -n "${SOLO_NAMESPACE}" get pod "network-${node}-0" -o jsonpath='{.metadata.uid}' 2>/dev/null || true)"
      if [[ -z "${current_uid}" || "${current_uid}" == "${original_uids[$idx]}" ]]; then
        break
      fi
      ((idx++))
    done

    if (( idx == ${#nodes[@]} )); then
      log "Detected recreated consensus pods for 0.73 upgrade"
      return 0
    fi

    sleep 2
  done

  echo "Timed out waiting for consensus pods to be recreated during 0.73 upgrade" >&2
  return 1
}

wait_for_haproxy_ready() {
  local timeout_secs="${1:-600}"
  local proxy
  local node_alias
  local node_aliases
  local proxies=()
  IFS=',' read -r -a node_aliases <<< "${NODE_ALIASES}"
  for node_alias in "${node_aliases[@]}"; do
    proxies+=("haproxy-${node_alias}")
  done

  for proxy in "${proxies[@]}"; do
    log "Waiting for ${proxy} rollout to become ready"
    kubectl -n "${SOLO_NAMESPACE}" rollout status "deployment/${proxy}" --timeout="${timeout_secs}s"
  done
}

# kubectl port-forward ties to pod endpoints; consensus network upgrade rolls HAProxy/backends and
# leaves the old tunnel broken even though localhost still listens. Port numbers (50211 in-cluster)
# do not change — the forward must be recreated.
restart_post_upgrade_port_forwards() {
  log "Starting/restarting consensus and mirror port-forwards"
  if [[ -n "${CN_PORT_FORWARD_PID}" ]]; then
    kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    CN_PORT_FORWARD_PID=""
  fi
  if [[ -n "${MIRROR_PORT_FORWARD_PID}" ]]; then
    kill "${MIRROR_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    MIRROR_PORT_FORWARD_PID=""
  fi
  cleanup_stale_port_forwards
  kill_processes_on_local_port "${CN_GRPC_LOCAL_PORT}"
  kill_processes_on_local_port "${MIRROR_REST_LOCAL_PORT}"
  sleep 1
  kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >"${CN_PORT_FORWARD_LOG}" 2>&1 &
  CN_PORT_FORWARD_PID="$!"
  if mirror_rest_service_exists; then
    kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${MIRROR_REST_SERVICE}" "${MIRROR_REST_LOCAL_PORT}:http" >"${MIRROR_PORT_FORWARD_LOG}" 2>&1 &
    MIRROR_PORT_FORWARD_PID="$!"
  else
    log "Mirror REST service ${MIRROR_REST_SERVICE} not found; skipping mirror port-forward"
  fi
  sleep 2
  if ! wait_for_tcp_open "127.0.0.1" "${CN_GRPC_LOCAL_PORT}" 20 1; then
    echo "Consensus gRPC port-forward did not become reachable on localhost:${CN_GRPC_LOCAL_PORT}" >&2
    tail -n 80 "${CN_PORT_FORWARD_LOG}" >&2 || true
    return 1
  fi
  if [[ -n "${MIRROR_PORT_FORWARD_PID}" ]] && ! wait_for_tcp_open "127.0.0.1" "${MIRROR_REST_LOCAL_PORT}" 20 1; then
    echo "Mirror REST port-forward did not become reachable on localhost:${MIRROR_REST_LOCAL_PORT}" >&2
    tail -n 80 "${MIRROR_PORT_FORWARD_LOG}" >&2 || true
    return 1
  fi
}

minio_discover_service() {
  local ns="$1"
  local svc
  if [[ -n "${MINIO_SERVICE_NAME}" ]]; then
    echo "${MINIO_SERVICE_NAME}"
    return 0
  fi
  if kubectl -n "${ns}" get svc minio >/dev/null 2>&1; then
    echo "minio"
    return 0
  fi
  if kubectl -n "${ns}" get svc minio-hl >/dev/null 2>&1; then
    echo "minio-hl"
    return 0
  fi
  svc="$(kubectl -n "${ns}" get svc -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("minio"; "i"))
    | select(test("console"; "i") | not)
    | select(test("headless"; "i") | not)
  ' | head -n 1)"
  if [[ -z "${svc}" ]]; then
    return 1
  fi
  echo "${svc}"
}

minio_discover_service_port() {
  local ns="$1"
  local svc="$2"
  local port
  # Prefer the service port that targets container port 9000.
  port="$(kubectl -n "${ns}" get svc "${svc}" -o json 2>/dev/null | jq -r '
    first(.spec.ports[] | select((.targetPort|tostring) == "9000") | .port // empty)
  ')"
  if [[ -z "${port}" || "${port}" == "null" ]]; then
    port="$(kubectl -n "${ns}" get svc "${svc}" -o json 2>/dev/null | jq -r '.spec.ports[0].port // empty')"
  fi
  [[ -n "${port}" && "${port}" != "null" ]] || return 1
  echo "${port}"
}

minio_discover_pod_credentials() {
  local ns="$1"
  local pod u p cfg
  pod="$(kubectl -n "${ns}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("^minio-"))
  ' | head -n 1)"
  [[ -n "${pod}" ]] || return 1

  cfg="$(kubectl -n "${ns}" exec "${pod}" -c minio -- sh -lc 'cat "${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}" 2>/dev/null || true' 2>/dev/null || true)"
  if [[ -n "${cfg}" ]]; then
    u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_USER=//p' | head -1 | tr -d '\r')"
    p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_PASSWORD=//p' | head -1 | tr -d '\r')"
    if [[ -z "${u}" || -z "${p}" ]]; then
      u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ACCESS_KEY=//p' | head -1 | tr -d '\r')"
      p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_SECRET_KEY=//p' | head -1 | tr -d '\r')"
    fi
  fi
  u="${u//$'\r'/}"
  p="${p//$'\r'/}"
  u="${u%\"}"
  u="${u#\"}"
  p="${p%\"}"
  p="${p#\"}"
  if [[ -n "${u}" && -n "${p}" ]]; then
    printf '%s\n' "${u}" "${p}"
    return 0
  fi
  return 1
}

minio_discover_secret_env_credentials() {
  local ns="$1"
  local secret="$2"
  local cfg u p
  cfg="$(kubectl -n "${ns}" get secret "${secret}" -o jsonpath='{.data.config\.env}' 2>/dev/null | base64 -d 2>/dev/null || true)"
  [[ -n "${cfg}" ]] || return 1
  u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_USER=//p' | head -1 | tr -d '\r')"
  p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_PASSWORD=//p' | head -1 | tr -d '\r')"
  u="${u%\"}"
  u="${u#\"}"
  p="${p%\"}"
  p="${p#\"}"
  if [[ -n "${u}" && -n "${p}" ]]; then
    printf '%s\n' "${u}" "${p}"
    return 0
  fi
  return 1
}

download_solo_record_streams_via_pod_mc() {
  local names_file="$1"
  local svc="$2"
  local svc_port="$3"
  local pod endpoint creds_tmp all_objects creds_file
  local wanted_timestamps selected_objects
  local u p selected_u selected_p remote subpath dest
  local server_url cfg_full
  local list_ok=0 endpoint_try
  local wanted_count selected_count matched_timestamps
  local found=0 sig_found=0 failed=0
  local progress_every=200

  : > "${MINIO_DOWNLOAD_LOG}"
  {
    echo "# MinIO download log"
    echo "# started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "# bucket=${MINIO_BUCKET}"
    echo "# namespace=${MINIO_NAMESPACE}"
  } >> "${MINIO_DOWNLOAD_LOG}"

  pod="$(kubectl -n "${MINIO_NAMESPACE}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("^minio-"))
  ' | head -n 1)"
  [[ -n "${pod}" ]] || {
    echo "Could not find MinIO pod in namespace ${MINIO_NAMESPACE}" >&2
    return 1
  }

  creds_file="$(mktemp)"
  creds_tmp="$(mktemp)"
  if minio_discover_pod_credentials "${MINIO_NAMESPACE}" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  : >"${creds_tmp}"
  if minio_discover_secret_env_credentials "${MINIO_NAMESPACE}" "minio-secrets" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  : >"${creds_tmp}"
  if minio_discover_secret_env_credentials "${MINIO_NAMESPACE}" "myminio-env-configuration" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  rm -f "${creds_tmp}"
  if [[ ! -s "${creds_file}" ]]; then
    rm -f "${creds_file}"
    echo "Could not discover any MinIO root credentials in namespace ${MINIO_NAMESPACE}" >&2
    return 1
  fi

  cfg_full="$(kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -lc \
    "cat \"\${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}\" 2>/dev/null || true" 2>/dev/null || true)"
  server_url="$(echo "${cfg_full}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_SERVER_URL=//p' | head -1 | tr -d '"\r')"

  all_objects="$(mktemp)"
  # Retries plus alternate in-cluster endpoints avoid transient DNS/service hiccups during upgrade.
  for _ in 1 2 3 4 5 6; do
    for endpoint_try in \
      "${server_url}" \
      "http://${svc}.${MINIO_NAMESPACE}.svc.cluster.local:${svc_port}" \
      "http://minio-hl.${MINIO_NAMESPACE}.svc.cluster.local:9000"; do
      [[ -n "${endpoint_try}" ]] || continue
      endpoint="${endpoint_try}"
      while IFS=$'\t' read -r u p; do
        [[ -n "${u}" && -n "${p}" ]] || continue
        if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -lc \
          "mc alias set local '${endpoint}' '${u}' '${p}' >/dev/null 2>&1; mc find local/${MINIO_BUCKET}/recordstreams --name '*.rcd*'" \
          >"${all_objects}" 2>/tmp/inpod-mc-list.err; then
          selected_u="${u}"
          selected_p="${p}"
          list_ok=1
          break
        fi
      done < "${creds_file}"
      (( list_ok == 1 )) && break
    done
    (( list_ok == 1 )) && break
    sleep 2
  done
  rm -f "${creds_file}" >/dev/null 2>&1 || true
  if (( list_ok == 0 )); then
    rm -f "${all_objects}"
    log "in-pod mc list stderr:"
    sed -n '1,20p' /tmp/inpod-mc-list.err >&2 || true
    echo "Failed to list MinIO objects via in-pod mc" >&2
    return 1
  fi
  log "Falling back to in-pod MinIO client copy from ${pod} via ${endpoint}"

  wanted_timestamps="$(mktemp)"
  selected_objects="$(mktemp)"
  awk '{
    f = $0;
    sub(/^.*\//, "", f);
    if (match(f, /Z/)) {
      print substr(f, 1, RSTART);
    }
  }' "${names_file}" | sort -u > "${wanted_timestamps}"
  wanted_count="$(wc -l < "${wanted_timestamps}" | tr -d ' ')"
  if [[ "${wanted_count}" == "0" ]]; then
    rm -f "${wanted_timestamps}" "${selected_objects}" "${all_objects}" >/dev/null 2>&1 || true
    echo "Could not derive wanted timestamps from mirror names file" >&2
    return 1
  fi

  awk 'NR == FNR { wanted[$1] = 1; next }
    {
      bn = $0;
      sub(/^.*\//, "", bn);
      if (match(bn, /Z/)) {
        ts = substr(bn, 1, RSTART);
        if (wanted[ts]) {
          print $0;
        }
      }
    }' "${wanted_timestamps}" "${all_objects}" | sort -u > "${selected_objects}"

  selected_count="$(wc -l < "${selected_objects}" | tr -d ' ')"
  matched_timestamps="$(awk '{
    bn = $0;
    sub(/^.*\//, "", bn);
    if (match(bn, /Z/)) {
      print substr(bn, 1, RSTART);
    }
  }' "${selected_objects}" | sort -u | wc -l | tr -d ' ')"
  log "Selected ${selected_count} MinIO object(s) across ${matched_timestamps}/${wanted_count} wanted timestamp(s)"
  {
    echo "# wanted_timestamps=${wanted_count}"
    echo "# matched_timestamps=${matched_timestamps}"
    echo "# selected_objects=${selected_count}"
    echo "# selected_by_extension"
    awk '
      /\.rcd\.gz$/ { c["rcd.gz"]++; next }
      /\.rcd_sig$/ { c["rcd_sig"]++; next }
      /\.rcs_sig$/ { c["rcs_sig"]++; next }
      { c["other"]++ }
      END {
        printf("rcd.gz=%d\n", c["rcd.gz"] + 0);
        printf("rcd_sig=%d\n", c["rcd_sig"] + 0);
        printf("rcs_sig=%d\n", c["rcs_sig"] + 0);
        printf("other=%d\n", c["other"] + 0);
      }' "${selected_objects}"
  } >> "${MINIO_DOWNLOAD_LOG}"

  while IFS= read -r remote; do
    [[ -z "${remote}" ]] && continue

    subpath="${remote#local/${MINIO_BUCKET}/recordstreams/}"
    if [[ "${subpath}" == "${remote}" ]]; then
      subpath="$(basename "${remote}")"
    fi
    dest="${RECORD_STREAMS_DIR}/${subpath}"
    mkdir -p "$(dirname "${dest}")"

    local copied=0
    for _ in 1 2 3; do
      if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -lc \
        "mc alias set local '${endpoint}' '${selected_u}' '${selected_p}' >/dev/null 2>&1; mc cat '${remote}'" \
        >"${dest}" 2>/dev/null; then
        copied=1
        break
      fi
      sleep 1
    done
    if (( copied == 1 )); then
      found=$((found + 1))
      echo "OK ${remote} -> ${dest}" >> "${MINIO_DOWNLOAD_LOG}"
      if [[ "${remote}" == *.rcd_sig || "${remote}" == *.rcs_sig ]]; then
        sig_found=$((sig_found + 1))
      fi
      if (( found % progress_every == 0 )); then
        log "MinIO download progress: ${found}/${selected_count} object(s) copied"
      fi
    else
      rm -f "${dest}" >/dev/null 2>&1 || true
      failed=$((failed + 1))
      echo "FAIL ${remote} -> ${dest}" >> "${MINIO_DOWNLOAD_LOG}"
    fi
  done < "${selected_objects}"

  rm -f "${wanted_timestamps}" >/dev/null 2>&1 || true
  rm -f "${selected_objects}" >/dev/null 2>&1 || true
  rm -f "${all_objects}" >/dev/null 2>&1 || true

  log "In-pod MinIO fallback finished: copied ${found} file(s), including ${sig_found} signature file(s), failed ${failed}"
  log "Detailed MinIO download log: ${MINIO_DOWNLOAD_LOG}"
  {
    echo "# copied=${found}"
    echo "# copied_signatures=${sig_found}"
    echo "# failed=${failed}"
    echo "# finished_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  } >> "${MINIO_DOWNLOAD_LOG}"
  if (( found == 0 )); then
    return 1
  fi
  if (( sig_found == 0 )); then
    echo "No signature files were downloaded from MinIO for selected timestamps" >&2
    return 1
  fi
  return 0
}

# Mirror may return an absolute URL or a path-only next link.
mirror_resolve_next_url() {
  local base="$1"
  local next="$2"
  if [[ -z "${next}" ]]; then
    echo ""
    return 0
  fi
  if [[ "${next}" == http://* || "${next}" == https://* ]]; then
    echo "${next}"
    return 0
  fi
  if [[ "${next}" == /* ]]; then
    local origin
    origin="$(echo "${base}" | sed -E 's|(https?://[^/]+).*|\1|')"
    echo "${origin}${next}"
    return 0
  fi
  echo "${base%/}/${next}"
}

# Paginate mirror /api/v1/blocks (ascending), collect unique record file basenames for blocks with number <= max_block.
collect_record_filenames_up_to_block() {
  local mirror_base="$1"
  local max_block="$2"
  local out_file="$3"
  local next_url="${mirror_base%/}/api/v1/blocks?order=asc&limit=100"
  local j last_num count
  : >"${out_file}"
  while [[ -n "${next_url}" ]]; do
    j="$(curl -sf "${next_url}")" || return 1
    count="$(echo "${j}" | jq '.blocks | length')"
    if [[ "${count}" == "0" || "${count}" == "null" ]]; then
      break
    fi
    echo "${j}" | jq -r --argjson max "${max_block}" '
      .blocks[]
      | select(.number <= $max)
      | (.name // empty)
      | split("/")
      | last
      | select(length > 0)
    ' >>"${out_file}"
    last_num="$(echo "${j}" | jq -r '.blocks[-1].number')"
    if [[ "${last_num}" == "null" ]]; then
      break
    fi
    if (( last_num >= max_block )); then
      break
    fi
    next_url="$(mirror_resolve_next_url "${mirror_base}" "$(echo "${j}" | jq -r '.links.next // empty')")"
  done
  sort -u "${out_file}" -o "${out_file}"
}

# Download record stream objects from the Solo MinIO bucket (default solo-streams) whose basenames appear
# on blocks <= max_block in the mirror (same names as /api/v1/blocks[].name).
download_solo_minio_record_streams() {
  local max_block="$1"
  local mirror_base="$2"
  local names_file svc svc_port nfiles

  mkdir -p "${RECORD_STREAMS_DIR}"
  names_file="$(mktemp)"
  log "Collecting record stream file names from mirror for blocks <= ${max_block}"
  collect_record_filenames_up_to_block "${mirror_base}" "${max_block}" "${names_file}" || {
    echo "Failed to list blocks from mirror for record file discovery" >&2
    rm -f "${names_file}"
    return 1
  }
  if [[ ! -s "${names_file}" ]]; then
    log "No record file names from mirror (empty result); skipping MinIO download"
    rm -f "${names_file}"
    return 0
  fi
  nfiles="$(wc -l < "${names_file}" | tr -d ' ')"
  log "Found ${nfiles} unique record stream file name(s) to resolve in MinIO"

  svc="$(minio_discover_service "${MINIO_NAMESPACE}")" || {
    echo "Could not find a MinIO Service in namespace ${MINIO_NAMESPACE}" >&2
    rm -f "${names_file}"
    return 1
  }
  svc_port="$(minio_discover_service_port "${MINIO_NAMESPACE}" "${svc}")" || {
    echo "Could not resolve service port for MinIO service ${svc}" >&2
    rm -f "${names_file}"
    return 1
  }

  if ! download_solo_record_streams_via_pod_mc "${names_file}" "${svc}" "${svc_port}"; then
    echo "Unable to download from in-pod MinIO fallback in namespace ${MINIO_NAMESPACE}" >&2
    rm -f "${names_file}"
    return 1
  fi
  rm -f "${names_file}"
}

local_build_implementation_version() {
  unzip -p "${LOCAL_BUILD_PATH}/apps/HederaNode.jar" META-INF/MANIFEST.MF 2>/dev/null \
    | sed -n 's/^Implementation-Version: //p' | head -n 1
}

consensus_pod_implementation_version() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "unzip -p /opt/hgcapp/services-hedera/HapiApp2.0/data/apps/HederaNode.jar META-INF/MANIFEST.MF 2>/dev/null \
      | sed -n 's/^Implementation-Version: //p' | head -n 1"
}

clean_consensus_pod_app_lib_dirs() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "mkdir -p /opt/hgcapp/services-hedera/HapiApp2.0/data/apps /opt/hgcapp/services-hedera/HapiApp2.0/data/lib \
      && find /opt/hgcapp/services-hedera/HapiApp2.0/data/apps -mindepth 1 -maxdepth 1 -exec rm -rf {} + \
      && find /opt/hgcapp/services-hedera/HapiApp2.0/data/lib -mindepth 1 -maxdepth 1 -exec rm -rf {} +"
}

clean_consensus_node_app_lib_dirs() {
  local node pod
  local nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    log "Cleaning existing apps/lib on ${pod}"
    clean_consensus_pod_app_lib_dirs "${pod}"
  done
}

stage_local_build_on_consensus_nodes() {
  local node pod
  local nodes=()
  local local_version=""
  local pod_version=""

  local_version="$(local_build_implementation_version)"
  if [[ -z "${local_version}" ]]; then
    echo "Unable to determine local build Implementation-Version from ${LOCAL_BUILD_PATH}/apps/HederaNode.jar" >&2
    return 1
  fi

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    pod_version="$(consensus_pod_implementation_version "${pod}" || true)"
    log "Restaging local build apps/lib on ${pod} (local=${local_version}, pod=${pod_version:-unknown})"
    COPYFILE_DISABLE=1 tar --disable-copyfile --no-mac-metadata --format ustar -C "${LOCAL_BUILD_PATH}" -cf - apps lib \
      | kubectl -n "${SOLO_NAMESPACE}" exec -i "${pod}" -c root-container -- sh -lc \
          "tar -xf - -C /opt/hgcapp/services-hedera/HapiApp2.0/data"
    pod_version="$(consensus_pod_implementation_version "${pod}" || true)"
    if [[ "${pod_version}" != "${local_version}" ]]; then
      echo "Local build restage did not take effect on ${pod}: expected ${local_version}, found ${pod_version:-unknown}" >&2
      return 1
    fi
  done
}

enable_network_node_service_on_consensus_nodes() {
  local node pod
  local nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    log "Enabling network-node service on ${pod}"
    kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "mkdir -p /opt/hgcapp/services-hedera/HapiApp2.0/state \
        && touch /opt/hgcapp/services-hedera/HapiApp2.0/state/network-node.enabled \
        && /command/s6-svc -u /run/service/network-node"
  done
}

verify_local_build_on_consensus_nodes() {
  local node pod
  local nodes=()
  local local_version=""
  local pod_version=""

  local_version="$(local_build_implementation_version)"
  [[ -n "${local_version}" ]] || { echo "Unable to determine local build version for verification" >&2; return 1; }

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    pod_version="$(consensus_pod_implementation_version "${pod}" || true)"
    log "Verifying local build version on ${pod} (expected ${local_version}, found ${pod_version:-unknown})"
    [[ "${pod_version}" == "${local_version}" ]]
  done
}

collect_consensus_diagnostics() {
  local node
  local pattern="FATAL|Critical failure|NullPointerException|Exception|ERROR|is ACTIVE|is STARTING|FAILED"
  local file_pattern="NoSuchMethodError|EXCEPTION|ERROR|FREEZE_COMPLETE|is ACTIVE|is STARTING|Shutting down gRPC|gRPC server listening|failed to start|FAILED"
  local no_such_method_seen=0

  log "Collecting consensus diagnostics from namespace ${SOLO_NAMESPACE}"
  kubectl -n "${SOLO_NAMESPACE}" get pods -o wide || true
  kubectl -n "${SOLO_NAMESPACE}" get events --sort-by=.lastTimestamp | tail -n 60 || true

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    log "Diagnostic scan for network-${node}-0"
    if command -v rg >/dev/null 2>&1; then
      kubectl -n "${SOLO_NAMESPACE}" logs "network-${node}-0" -c root-container --tail=1400 2>/dev/null \
        | rg -n "${pattern}" | tail -n 40 || true
    else
      kubectl -n "${SOLO_NAMESPACE}" logs "network-${node}-0" -c root-container --tail=1400 2>/dev/null \
        | grep -En "${pattern}" | tail -n 40 || true
    fi
    kubectl -n "${SOLO_NAMESPACE}" exec "network-${node}-0" -c root-container -- sh -lc \
      "for f in /opt/hgcapp/services-hedera/HapiApp2.0/output/hgcaa.log /opt/hgcapp/services-hedera/HapiApp2.0/output/swirlds.log; do [ -f \"\$f\" ] && { echo \"--- \${f} ---\"; tail -n 1200 \"\$f\"; }; done" 2>/dev/null \
      | grep -En "${file_pattern}" | tail -n 80 || true
    if kubectl -n "${SOLO_NAMESPACE}" exec "network-${node}-0" -c root-container -- sh -lc \
      "grep -q 'NoSuchMethodError' /opt/hgcapp/services-hedera/HapiApp2.0/output/swirlds.log" >/dev/null 2>&1; then
      no_such_method_seen=1
    fi
  done

  if (( no_such_method_seen == 1 )); then
    log "Detected NoSuchMethodError in consensus startup logs after freeze/upgrade."
    log "This usually means incompatible or mixed local-build jars were copied during upgrade."
    log "Recommended recovery: run './gradlew clean assemble', recreate baseline network, then rerun Step 6."
  fi
}

run_with_consensus_diagnostics() {
  local label="$1"
  local ec
  shift
  if "$@"; then
    return 0
  else
    ec=$?
  fi

  log "FAILED: ${label} (exit=${ec})"
  collect_consensus_diagnostics || true
  return "${ec}"
}

run_073_upgrade_once() {
  env NODE_COPY_CONCURRENT="${SOLO_073_NODE_COPY_CONCURRENT}" \
    LOCAL_BUILD_COPY_RETRY="${SOLO_073_LOCAL_BUILD_COPY_RETRY}" \
    solo consensus network upgrade --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" \
    --local-build-path "${LOCAL_BUILD_PATH}" \
    --application-properties "${APP_PROPS_073_FILE}" \
    --quiet-mode --force
}

run_073_upgrade_internal() {
  local ec=1
  local original_uids_csv=""
  local solo_pid=""

  original_uids_csv="$(capture_consensus_pod_uids)"
  : > "${SOLO_073_UPGRADE_LOG}"
  log "Step 6: executing solo consensus network upgrade (streaming logs; file: ${SOLO_073_UPGRADE_LOG})"

  (
    run_073_upgrade_once 2>&1 | tee "${SOLO_073_UPGRADE_LOG}"
  ) &
  solo_pid="$!"

  if ! wait_for_consensus_pod_recreation "${original_uids_csv}" 900; then
    if wait "${solo_pid}"; then
      :
    else
      ec=$?
    fi
    tail -n 160 "${SOLO_073_UPGRADE_LOG}" >&2 || true
    return "${ec}"
  fi

  wait_for_consensus_pods_ready 600
  log "Step 6: cleaning recreated pods before restaging local 0.73 apps/lib"
  clean_consensus_node_app_lib_dirs
  stage_local_build_on_consensus_nodes
  enable_network_node_service_on_consensus_nodes

  if wait "${solo_pid}"; then
    :
  else
    ec=$?
    tail -n 160 "${SOLO_073_UPGRADE_LOG}" >&2 || true
    return "${ec}"
  fi
}

run_073_upgrade() {
  run_with_consensus_diagnostics "solo 0.73 local-build network upgrade" \
    run_073_upgrade_internal
}

run_explorer_add_with_retry() {
  local attempt=1
  local max_attempts="${EXPLORER_ADD_RETRIES}"
  local delay_secs="${EXPLORER_ADD_RETRY_DELAY_SECS}"
  local ec=0

  while (( attempt <= max_attempts )); do
    if solo explorer node add --deployment "${SOLO_DEPLOYMENT}" >"${EXPLORER_ADD_LOG}" 2>&1; then
      log "Explorer deployment succeeded on attempt ${attempt}/${max_attempts}"
      return 0
    fi
    ec=$?
    log "Explorer deployment attempt ${attempt}/${max_attempts} failed (exit=${ec})"
    tail -n 60 "${EXPLORER_ADD_LOG}" >&2 || true
    if (( attempt == max_attempts )); then
      break
    fi
    log "Retrying explorer deployment in ${delay_secs}s"
    sleep "${delay_secs}"
    ((attempt++))
  done

  if [[ "${ALLOW_EXPLORER_DEPLOY_FAILURE}" == "true" ]]; then
    log "WARNING: Explorer deployment failed after ${max_attempts} attempt(s); continuing because ALLOW_EXPLORER_DEPLOY_FAILURE=true"
    return 0
  fi

  return "${ec}"
}

run_sdk_network_probe() {
  node "${NETWORK_PROBE_SCRIPT}"
}

ensure_sdk_network_ready() {
  local attempt=1
  local max_attempts="${SDK_READY_RETRIES}"
  local delay_secs="${SDK_READY_RETRY_DELAY_SECS}"

  while (( attempt <= max_attempts )); do
    if run_sdk_network_probe; then
      return 0
    fi
    if (( attempt == max_attempts )); then
      return 1
    fi
    log "SDK probe failed (attempt ${attempt}/${max_attempts}); restarting port-forwards and retrying in ${delay_secs}s"
    restart_post_upgrade_port_forwards || true
    wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/network/nodes" 20 2 || true
    sleep "${delay_secs}"
    ((attempt++))
  done
}

run_file121_update_with_retry() {
  local attempt=1
  local max_attempts="${FILE121_UPDATE_RETRIES}"
  local delay_secs="${FILE121_UPDATE_RETRY_DELAY_SECS}"

  while (( attempt <= max_attempts )); do
    if ! ensure_sdk_network_ready; then
      log "Step 6: SDK network probe failed before File 121 update; collecting diagnostics"
      collect_consensus_diagnostics || true
      return 1
    fi
    if node "${FILE_121_JUMPSTART_SCRIPT}"; then
      return 0
    fi
    if (( attempt == max_attempts )); then
      return 1
    fi
    log "Step 6: File 121 update attempt ${attempt}/${max_attempts} failed; restarting port-forwards and retrying in ${delay_secs}s"
    restart_post_upgrade_port_forwards || true
    wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/network/nodes" 20 2 || true
    sleep "${delay_secs}"
    ((attempt++))
  done
}

start_grafana_port_forward() {
  local attempt=1
  local max_attempts=60

  if wait_for_http_ok "http://127.0.0.1:${GRAFANA_LOCAL_PORT}/api/health" 1 1; then
    log "Grafana already reachable on http://127.0.0.1:${GRAFANA_LOCAL_PORT}"
    return 0
  fi

  log "Waiting for Grafana service to become available"
  while (( attempt <= max_attempts )); do
    if kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get svc kube-prometheus-stack-grafana >/dev/null 2>&1; then
      break
    fi
    sleep 5
    ((attempt++))
  done

  if (( attempt > max_attempts )); then
    echo "Timed out waiting for Grafana service in namespace ${SOLO_CLUSTER_SETUP_NAMESPACE}" >&2
    return 1
  fi

  local pf_attempt=1
  local pf_max_attempts=6
  while (( pf_attempt <= pf_max_attempts )); do
    log "Starting early Grafana port-forward on ${GRAFANA_LOCAL_PORT} (attempt ${pf_attempt}/${pf_max_attempts})"
    kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" port-forward svc/kube-prometheus-stack-grafana "${GRAFANA_LOCAL_PORT}:80" >"${GRAFANA_PORT_FORWARD_LOG}" 2>&1 &
    GRAFANA_PORT_FORWARD_PID="$!"

    sleep 2
    if kill -0 "${GRAFANA_PORT_FORWARD_PID}" >/dev/null 2>&1 \
      && wait_for_http_ok "http://127.0.0.1:${GRAFANA_LOCAL_PORT}/api/health" 10 1; then
      log "Grafana is reachable at http://127.0.0.1:${GRAFANA_LOCAL_PORT}"
      return 0
    fi

    [[ -n "${GRAFANA_PORT_FORWARD_PID}" ]] && kill "${GRAFANA_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    GRAFANA_PORT_FORWARD_PID=""
    sleep 2
    ((pf_attempt++))
  done

  echo "Failed to establish Grafana port-forward on localhost:${GRAFANA_LOCAL_PORT}" >&2
  return 1
}

write_sdk_verifier() {
  cat > "${NODE_SCRIPT}" <<'EOF'
const { Client, AccountCreateTransaction, PrivateKey, Hbar, Status } = require("@hashgraph/sdk");

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function ensureAccountVisibleInMirror(mirrorUrl, accountId, timeoutMs = 180000, intervalMs = 5000) {
  const accountPath = `${mirrorUrl.replace(/\/$/, "")}/api/v1/accounts/${accountId}`;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(accountPath);
      if (response.ok) {
        return;
      }
    } catch (err) {
      // Mirror may not be ready yet, continue polling.
    }
    await sleep(intervalMs);
  }
  throw new Error(`Mirror did not report account ${accountId} within timeout`);
}

async function main() {
  const grpcEndpoint = process.env.GRPC_ENDPOINT || "127.0.0.1:50211";
  const mirrorUrl = process.env.MIRROR_REST_URL || "http://127.0.0.1:5551";
  const mirrorAccountWaitMs = Number(process.env.MIRROR_ACCOUNT_WAIT_MS || "180000");
  const operatorAccountId = process.env.OPERATOR_ACCOUNT_ID || "0.0.2";
  const operatorPrivateKey = process.env.OPERATOR_PRIVATE_KEY;
  if (!operatorPrivateKey) {
    throw new Error("OPERATOR_PRIVATE_KEY is required");
  }

  const client = Client.forNetwork({ [grpcEndpoint]: "0.0.3" });
  client.setOperator(operatorAccountId, PrivateKey.fromString(operatorPrivateKey));
  client.setMaxAttempts(1);
  client.setRequestTimeout(15000);

  const tx = new AccountCreateTransaction()
    .setInitialBalance(new Hbar(1))
    .setKey(PrivateKey.generateED25519().publicKey)
    .setMaxTransactionFee(new Hbar(5));
  const response = await tx.execute(client);
  const receipt = await response.getReceipt(client);

  if (receipt.status !== Status.Success) {
    throw new Error(`Expected SUCCESS status but got ${receipt.status.toString()}`);
  }

  const accountId = receipt.accountId ? receipt.accountId.toString() : "";
  if (!accountId) {
    throw new Error("Receipt did not include a new accountId");
  }

  await ensureAccountVisibleInMirror(mirrorUrl, accountId, mirrorAccountWaitMs);
  console.log(`PASS: crypto create succeeded and mirror sees account ${accountId}`);
  await client.close();
}

main().catch((err) => {
  console.error(`FAIL: ${err.message}`);
  process.exit(1);
});
EOF
}

write_sdk_network_probe() {
  cat > "${NETWORK_PROBE_SCRIPT}" <<'EOF'
const { Client, AccountBalanceQuery, PrivateKey } = require("@hashgraph/sdk");

async function main() {
  const grpcEndpoint = process.env.GRPC_ENDPOINT || "127.0.0.1:50211";
  const operatorAccountId = process.env.OPERATOR_ACCOUNT_ID || "0.0.2";
  const operatorPrivateKey = process.env.OPERATOR_PRIVATE_KEY;
  if (!operatorPrivateKey) {
    throw new Error("OPERATOR_PRIVATE_KEY is required");
  }

  const client = Client.forNetwork({ [grpcEndpoint]: "0.0.3" });
  client.setOperator(operatorAccountId, PrivateKey.fromString(operatorPrivateKey));
  client.setMaxAttempts(1);
  client.setRequestTimeout(15000);

  const balance = await new AccountBalanceQuery().setAccountId(operatorAccountId).execute(client);
  console.log(`[sdk-probe] PASS endpoint=${grpcEndpoint} operator=${operatorAccountId} balance=${balance.hbars.toString()}`);
  await client.close();
}

main().catch((err) => {
  const details = err && err.stack ? err.stack : String(err);
  console.error(`[sdk-probe] FAIL endpoint=${process.env.GRPC_ENDPOINT || "127.0.0.1:50211"} details=${details}`);
  process.exit(1);
});
EOF
}

write_file121_jumpstart_update() {
  cat > "${FILE_121_JUMPSTART_SCRIPT}" <<'EOF'
const {
  Client,
  FileContentsQuery,
  FileUpdateTransaction,
  FileId,
  Hbar,
  PrivateKey,
  Status,
} = require("@hashgraph/sdk");
const { proto } = require("@hashgraph/proto");

const SHA384_HEX_RE = /^[0-9a-fA-F]{96}$/;

function merge121Contents(existingBytes, overrides) {
  const list =
    existingBytes.length > 0
      ? proto.ServicesConfigurationList.decode(new Uint8Array(existingBytes))
      : proto.ServicesConfigurationList.create({ nameValue: [] });
  const byName = new Map();
  for (const s of list.nameValue ?? []) {
    byName.set(s.name, s);
  }
  for (const [name, value] of Object.entries(overrides)) {
    byName.set(name, { name, value: String(value) });
  }
  list.nameValue = Array.from(byName.values());
  return Buffer.from(proto.ServicesConfigurationList.encode(list).finish());
}

async function main() {
  const grpcEndpoint = process.env.GRPC_ENDPOINT || "127.0.0.1:50211";
  const operatorAccountId = process.env.OPERATOR_ACCOUNT_ID || "0.0.2";
  const operatorPrivateKey = process.env.OPERATOR_PRIVATE_KEY;
  const blockNum = process.env.JUMPSTART_BLOCK_NUMBER || process.env.MIRROR_BLOCK_NUMBER;
  if (!operatorPrivateKey) {
    throw new Error("OPERATOR_PRIVATE_KEY is required");
  }
  if (!blockNum || blockNum === "null") {
    throw new Error("JUMPSTART_BLOCK_NUMBER (or MIRROR_BLOCK_NUMBER) is required");
  }

  const prevHash =
    process.env.JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH || "";
  const leafCountRaw = process.env.JUMPSTART_STREAMING_HASHER_LEAF_COUNT ?? "1";
  const hashCountRaw = process.env.JUMPSTART_STREAMING_HASHER_HASH_COUNT ?? "1";
  const subtreeHashesRaw =
    process.env.JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES ??
    "111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111";

  if (!SHA384_HEX_RE.test(prevHash)) {
    throw new Error(
      "JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH must be exactly 96 hex chars (SHA-384)"
    );
  }
  const leafCount = Number(leafCountRaw);
  const hashCount = Number(hashCountRaw);
  if (!Number.isInteger(leafCount) || leafCount < 0) {
    throw new Error("JUMPSTART_STREAMING_HASHER_LEAF_COUNT must be a non-negative integer");
  }
  if (!Number.isInteger(hashCount) || hashCount < 0) {
    throw new Error("JUMPSTART_STREAMING_HASHER_HASH_COUNT must be a non-negative integer");
  }

  const subtreeHashes = String(subtreeHashesRaw)
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (subtreeHashes.length !== hashCount) {
    throw new Error(
      `JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES count (${subtreeHashes.length}) must match hash count (${hashCount})`
    );
  }
  for (const h of subtreeHashes) {
    if (!SHA384_HEX_RE.test(h)) {
      throw new Error("Each subtree hash must be exactly 96 hex chars (SHA-384)");
    }
  }

  const overrides = {
    "blockStream.jumpstart.blockNum": String(blockNum),
    "blockStream.jumpstart.previousWrappedRecordBlockHash": prevHash,
    "blockStream.jumpstart.streamingHasherLeafCount": String(leafCountRaw),
    "blockStream.jumpstart.streamingHasherHashCount": String(hashCountRaw),
    "blockStream.jumpstart.streamingHasherSubtreeHashes": subtreeHashes.join(","),
  };

  console.log(
    `[jumpstart] blockNum=${blockNum}, leafCount=${leafCountRaw}, hashCount=${hashCountRaw}, prevHashLen=${prevHash.length}, subtreeCount=${subtreeHashes.length}`
  );

  const client = Client.forNetwork({ [grpcEndpoint]: "0.0.3" });
  client.setOperator(operatorAccountId, PrivateKey.fromString(operatorPrivateKey));
  client.setMaxAttempts(10);
  client.setRequestTimeout(60000);

  const fileId = FileId.fromString(process.env.NETWORK_PROPERTIES_FILE_ID || "0.0.121");
  const existing = await new FileContentsQuery().setFileId(fileId).execute(client);
  const merged = merge121Contents(existing, overrides);

  const tx = new FileUpdateTransaction()
    .setFileId(fileId)
    .setContents(merged)
    .setMaxTransactionFee(new Hbar(15));
  const response = await tx.execute(client);
  const receipt = await response.getReceipt(client);
  if (receipt.status !== Status.Success) {
    throw new Error(`File 121 update expected SUCCESS but got ${receipt.status.toString()}`);
  }
  console.log(`PASS: File ${fileId.toString()} updated with jumpstart-related properties (blockNum=${blockNum})`);
  await client.close();
}

main().catch((err) => {
  console.error(`FAIL: ${err.message}`);
  process.exit(1);
});
EOF
}

write_jumpstart_parser() {
  cat > "${JUMPSTART_PARSE_SCRIPT}" <<'EOF'
const fs = require("fs");

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

const file = process.argv[2];
if (!file) fail("Missing jumpstart.bin path argument");

let b;
try {
  b = fs.readFileSync(file);
} catch (e) {
  fail(`Unable to read jumpstart file '${file}': ${e.message}`);
}

if (b.length < 68) {
  fail(`jumpstart.bin too small: ${b.length} bytes (expected at least 68)`);
}

const blockNum = b.readBigInt64BE(0);
const prevHash = b.subarray(8, 56).toString("hex");
const leafCount = b.readBigInt64BE(56);
const hashCount = b.readInt32BE(64);
if (hashCount < 0) {
  fail(`Invalid negative hashCount ${hashCount}`);
}

const expected = 68 + (hashCount * 48);
if (b.length !== expected) {
  fail(`jumpstart.bin size mismatch: got ${b.length}, expected ${expected} (hashCount=${hashCount})`);
}

const subtreeHashes = [];
let offset = 68;
for (let i = 0; i < hashCount; i += 1) {
  subtreeHashes.push(b.subarray(offset, offset + 48).toString("hex"));
  offset += 48;
}

console.log(`JUMPSTART_BLOCK_NUMBER=${blockNum.toString()}`);
console.log(`JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH=${prevHash}`);
console.log(`JUMPSTART_STREAMING_HASHER_LEAF_COUNT=${leafCount.toString()}`);
console.log(`JUMPSTART_STREAMING_HASHER_HASH_COUNT=${hashCount}`);
console.log(`JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES=${subtreeHashes.join(",")}`);
EOF
}

write_mirror_metadata_generator() {
  cat > "${MIRROR_METADATA_SCRIPT}" <<'EOF'
const fs = require("fs");
const path = require("path");

const FIRST_BLOCK_TIME = "2019-09-13T21:53:51.396440Z";

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

function parseTimestampToEpochNanos(tsLike) {
  const ts = String(tsLike).replace(/_/g, ":");
  const m = ts.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/
  );
  if (!m) {
    throw new Error(`Invalid timestamp format: ${tsLike}`);
  }
  const [
    ,
    y,
    mo,
    d,
    h,
    mi,
    s,
    fracRaw = "",
  ] = m;
  const ms = Date.UTC(
    Number(y),
    Number(mo) - 1,
    Number(d),
    Number(h),
    Number(mi),
    Number(s)
  );
  const epochSeconds = BigInt(Math.floor(ms / 1000));
  const fracNanos = BigInt((fracRaw + "000000000").slice(0, 9));
  return (epochSeconds * 1_000_000_000n) + fracNanos;
}

function recordNameToEpochNanos(recordName) {
  const base = path.basename(String(recordName));
  const z = base.indexOf("Z");
  if (z < 0) {
    throw new Error(`Record file name does not include Z timestamp: ${recordName}`);
  }
  const ts = base.slice(0, z + 1);
  return parseTimestampToEpochNanos(ts);
}

function resolveNextUrl(base, next) {
  if (!next) {
    return "";
  }
  if (next.startsWith("http://") || next.startsWith("https://")) {
    return next;
  }
  if (next.startsWith("/")) {
    return `${base}${next}`;
  }
  return `${base}/${next}`;
}

async function fetchAllBlocksUpTo(mirrorBase, maxBlock) {
  const blocks = [];
  let nextUrl = `${mirrorBase}/api/v1/blocks?order=asc&limit=100`;
  while (nextUrl) {
    const response = await fetch(nextUrl);
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} from ${nextUrl}`);
    }
    const body = await response.json();
    const page = Array.isArray(body.blocks) ? body.blocks : [];
    if (page.length === 0) {
      break;
    }

    for (const b of page) {
      const n = Number(b.number);
      if (!Number.isFinite(n)) {
        continue;
      }
      if (n > maxBlock) {
        return blocks;
      }
      blocks.push({
        number: n,
        name: b.name || "",
        hash: String(b.hash || "").replace(/^0x/i, ""),
      });
    }

    const lastNumber = Number(page[page.length - 1].number);
    if (Number.isFinite(lastNumber) && lastNumber >= maxBlock) {
      break;
    }
    nextUrl = resolveNextUrl(mirrorBase, body.links && body.links.next);
  }
  return blocks;
}

function ensureNoBlockGaps(sortedBlocks) {
  if (sortedBlocks.length < 2) {
    return;
  }
  for (let i = 1; i < sortedBlocks.length; i += 1) {
    const expected = sortedBlocks[i - 1].number + 1;
    const actual = sortedBlocks[i].number;
    if (actual !== expected) {
      throw new Error(`Gap in mirror blocks: expected ${expected}, got ${actual}`);
    }
  }
}

function dayFromRecordName(recordName) {
  const base = path.basename(String(recordName));
  const z = base.indexOf("Z");
  if (z < 0) {
    throw new Error(`Record file name does not include Z timestamp: ${recordName}`);
  }
  const ts = base.slice(0, z + 1).replace(/_/g, ":");
  return ts.slice(0, 10);
}

async function main() {
  const mirrorBase = String(process.env.MIRROR_REST_URL || "http://127.0.0.1:5551").replace(/\/$/, "");
  const maxBlockRaw = process.env.MIRROR_BLOCK_NUMBER;
  const blockTimesFile = process.env.BLOCK_TIMES_FILE;
  const dayBlocksFile = process.env.DAY_BLOCKS_FILE;
  if (!maxBlockRaw) fail("MIRROR_BLOCK_NUMBER is required");
  if (!blockTimesFile) fail("BLOCK_TIMES_FILE is required");
  if (!dayBlocksFile) fail("DAY_BLOCKS_FILE is required");

  const maxBlock = Number(maxBlockRaw);
  if (!Number.isInteger(maxBlock) || maxBlock < 0) {
    fail(`Invalid MIRROR_BLOCK_NUMBER: ${maxBlockRaw}`);
  }

  const blocks = await fetchAllBlocksUpTo(mirrorBase, maxBlock);
  if (blocks.length === 0) {
    fail("Mirror returned no blocks for metadata generation");
  }
  blocks.sort((a, b) => a.number - b.number);
  ensureNoBlockGaps(blocks);
  const highest = blocks[blocks.length - 1].number;
  if (highest < maxBlock) {
    fail(`Mirror highest fetched block ${highest} is below requested ${maxBlock}`);
  }

  const firstEpochNanos = parseTimestampToEpochNanos(FIRST_BLOCK_TIME);
  const buf = Buffer.alloc((maxBlock + 1) * 8);
  const byDay = new Map();

  for (const b of blocks) {
    const epochNanos = recordNameToEpochNanos(b.name);
    const blockTime = epochNanos - firstEpochNanos;
    if (blockTime < 0n) {
      fail(`Negative block time for block ${b.number} (${b.name})`);
    }
    buf.writeBigInt64BE(blockTime, b.number * 8);

    const day = dayFromRecordName(b.name);
    const [year, month, dayNum] = day.split("-").map(Number);
    const prev = byDay.get(day);
    if (!prev) {
      byDay.set(day, {
        year,
        month,
        day: dayNum,
        firstBlockNumber: b.number,
        firstBlockHash: b.hash,
        lastBlockNumber: b.number,
        lastBlockHash: b.hash,
      });
    } else {
      prev.lastBlockNumber = b.number;
      prev.lastBlockHash = b.hash;
    }
  }

  fs.mkdirSync(path.dirname(blockTimesFile), { recursive: true });
  fs.mkdirSync(path.dirname(dayBlocksFile), { recursive: true });
  fs.writeFileSync(blockTimesFile, buf);

  const dayBlocks = Array.from(byDay.values()).sort((a, b) => {
    if (a.year !== b.year) return a.year - b.year;
    if (a.month !== b.month) return a.month - b.month;
    return a.day - b.day;
  });
  fs.writeFileSync(dayBlocksFile, `${JSON.stringify(dayBlocks, null, 2)}\n`);

  console.log(
    `PASS: generated ${blockTimesFile} (${maxBlock + 1} entries) and ${dayBlocksFile} (${dayBlocks.length} day entries)`
  );
}

main().catch((err) => {
  console.error(`FAIL: ${err.message}`);
  process.exit(1);
});
EOF
}

generate_block_node_metadata_from_mirror() {
  local max_block="$1"
  write_mirror_metadata_generator

  export MIRROR_BLOCK_NUMBER="${max_block}"
  export BLOCK_TIMES_FILE
  export DAY_BLOCKS_FILE
  export MIRROR_REST_URL

  log "Generating block_times.bin and day_blocks.json from local mirror REST (blocks <= ${MIRROR_BLOCK_NUMBER})"
  if ! node "${MIRROR_METADATA_SCRIPT}" >"${MIRROR_METADATA_LOG}" 2>&1; then
    echo "Mirror metadata generation failed. Log: ${MIRROR_METADATA_LOG}" >&2
    tail -n 120 "${MIRROR_METADATA_LOG}" >&2 || true
    return 1
  fi
  tail -n 20 "${MIRROR_METADATA_LOG}" || true
}

prepare_wrap_day_archives_from_record_streams() {
  local account_dir account_id src base ts day
  local out_dir out_file stem stem_no_ext
  local primary_records=0
  local other_records=0
  local sig_files=0
  local tar_count=0

  rm -rf "${WRAP_DAYS_SRC_DIR}" "${WRAP_COMPRESSED_DAYS_DIR}" >/dev/null 2>&1 || true
  mkdir -p "${WRAP_DAYS_SRC_DIR}" "${WRAP_COMPRESSED_DAYS_DIR}"

  shopt -s nullglob
  for account_dir in "${RECORD_STREAMS_DIR}"/record0.0.*; do
    [[ -d "${account_dir}" ]] || continue
    account_id="${account_dir##*/record}"
    for src in "${account_dir}"/*; do
      [[ -f "${src}" ]] || continue
      base="$(basename "${src}")"
      [[ "${base}" == *Z* ]] || continue
      ts="${base%%Z*}Z"
      day="${ts%%T*}"
      out_dir="${WRAP_DAYS_SRC_DIR}/${day}/${ts}"
      mkdir -p "${out_dir}"

      case "${base}" in
        *.rcd.gz)
          stem="${base%.gz}"
          stem_no_ext="${stem%.rcd}"
          if [[ "${stem_no_ext}" == "${ts}" && "${account_id}" == "0.0.3" && ! -f "${out_dir}/${ts}.rcd" ]]; then
            gzip -dc "${src}" > "${out_dir}/${ts}.rcd"
            primary_records=$((primary_records + 1))
          else
            out_file="${out_dir}/${stem_no_ext}_node_${account_id}.rcd"
            gzip -dc "${src}" > "${out_file}"
            other_records=$((other_records + 1))
          fi
          ;;
        *.rcd)
          stem_no_ext="${base%.rcd}"
          if [[ "${stem_no_ext}" == "${ts}" && "${account_id}" == "0.0.3" && ! -f "${out_dir}/${ts}.rcd" ]]; then
            cp -f "${src}" "${out_dir}/${ts}.rcd"
            primary_records=$((primary_records + 1))
          else
            cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcd"
            other_records=$((other_records + 1))
          fi
          ;;
        *.rcd_sig)
          stem_no_ext="${base%.rcd_sig}"
          cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcd_sig"
          sig_files=$((sig_files + 1))
          ;;
        *.rcs_sig)
          stem_no_ext="${base%.rcs_sig}"
          cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcs_sig"
          sig_files=$((sig_files + 1))
          ;;
      esac
    done
  done
  shopt -u nullglob

  if (( primary_records == 0 )); then
    echo "No primary record files prepared for wrap input under ${WRAP_DAYS_SRC_DIR}" >&2
    return 1
  fi
  if (( sig_files == 0 )); then
    echo "No signature files prepared for wrap input under ${WRAP_DAYS_SRC_DIR}" >&2
    return 1
  fi

  log "Prepared wrap day source files: primaryRecords=${primary_records}, otherRecords=${other_records}, signatureFiles=${sig_files}"
  if ! (
    cd "${BLOCK_NODE_REPO_PATH}" && ./gradlew :tools:run --args="days compress -o ${WRAP_COMPRESSED_DAYS_DIR} ${WRAP_DAYS_SRC_DIR}"
  ) >"${WRAP_INPUT_PREP_LOG}" 2>&1; then
    echo "Failed to build .tar.zstd wrap input archives. Log: ${WRAP_INPUT_PREP_LOG}" >&2
    tail -n 120 "${WRAP_INPUT_PREP_LOG}" >&2 || true
    return 1
  fi

  tar_count="$(find "${WRAP_COMPRESSED_DAYS_DIR}" -type f -name '*.tar.zstd' | wc -l | tr -d ' ')"
  if [[ "${tar_count}" == "0" ]]; then
    echo "days compress produced no .tar.zstd files under ${WRAP_COMPRESSED_DAYS_DIR}" >&2
    echo "days compress log: ${WRAP_INPUT_PREP_LOG}" >&2
    return 1
  fi
  log "Prepared ${tar_count} day archive(s) for blocks wrap input at ${WRAP_COMPRESSED_DAYS_DIR}"
}

run_block_node_wrap_tool() {
  local records_dir="$1"
  local wrapped_dir="$2"
  local wrap_args jumpstart_file

  if [[ "${USE_BLOCK_NODE_JUMPSTART}" != "true" ]]; then
    log "USE_BLOCK_NODE_JUMPSTART=false; skipping Block Node wrap tool and using configured jumpstart env values"
    return 0
  fi

  if ! validate_block_node_repo; then
    return 1
  fi
  if [[ ! -d "${records_dir}" ]]; then
    echo "recordStreams directory not found: ${records_dir}" >&2
    return 1
  fi
  if ! ensure_zstd_command_for_block_node; then
    echo "Failed to provide a working zstd command for Block Node wrapping." >&2
    return 1
  fi

  mkdir -p "${wrapped_dir}"
  wrap_args="blocks wrap -i ${records_dir} -o ${wrapped_dir} --blocktimes-file ${BLOCK_TIMES_FILE} --day-blocks ${DAY_BLOCKS_FILE}"
  if [[ -n "${BLOCKS_WRAP_EXTRA_ARGS}" ]]; then
    wrap_args="${wrap_args} ${BLOCKS_WRAP_EXTRA_ARGS}"
  fi

  log "Running Block Node offline tool to produce wrapped blocks and jumpstart.bin"
  log "Block Node repo: ${BLOCK_NODE_REPO_PATH}"
  log "Wrap args: ${wrap_args}"

  if ! (
    cd "${BLOCK_NODE_REPO_PATH}" && ./gradlew :tools:run --args="${wrap_args}"
  ) >"${BLOCK_NODE_WRAP_LOG}" 2>&1; then
    echo "Block Node wrap command failed. Log: ${BLOCK_NODE_WRAP_LOG}" >&2
    tail -n 120 "${BLOCK_NODE_WRAP_LOG}" >&2 || true
    return 1
  fi

  if [[ -n "${JUMPSTART_BIN_PATH}" ]]; then
    jumpstart_file="${JUMPSTART_BIN_PATH}"
  else
    jumpstart_file="$(find "${wrapped_dir}" -type f -name "jumpstart.bin" | head -n 1)"
  fi
  if [[ -z "${jumpstart_file}" || ! -f "${jumpstart_file}" ]]; then
    echo "jumpstart.bin not found under ${wrapped_dir}. Override with JUMPSTART_BIN_PATH." >&2
    echo "Block Node log: ${BLOCK_NODE_WRAP_LOG}" >&2
    return 1
  fi

  export JUMPSTART_BIN_PATH="${jumpstart_file}"
  log "Block Node tooling produced jumpstart file: ${JUMPSTART_BIN_PATH}"
}

load_jumpstart_env_from_bin() {
  local jumpstart_file="$1"
  local k v

  [[ -f "${jumpstart_file}" ]] || { echo "jumpstart.bin not found: ${jumpstart_file}" >&2; return 1; }
  write_jumpstart_parser

  while IFS='=' read -r k v; do
    case "${k}" in
      JUMPSTART_BLOCK_NUMBER) JUMPSTART_BLOCK_NUMBER="${v}" ;;
      JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH) JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH="${v}" ;;
      JUMPSTART_STREAMING_HASHER_LEAF_COUNT) JUMPSTART_STREAMING_HASHER_LEAF_COUNT="${v}" ;;
      JUMPSTART_STREAMING_HASHER_HASH_COUNT) JUMPSTART_STREAMING_HASHER_HASH_COUNT="${v}" ;;
      JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES) JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES="${v}" ;;
    esac
  done < <(node "${JUMPSTART_PARSE_SCRIPT}" "${jumpstart_file}")

  [[ -n "${JUMPSTART_BLOCK_NUMBER}" ]] || { echo "Failed to parse JUMPSTART_BLOCK_NUMBER from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}" ]] || { echo "Failed to parse previous hash from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}" ]] || { echo "Failed to parse leaf count from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_STREAMING_HASHER_HASH_COUNT}" ]] || { echo "Failed to parse hash count from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES}" || "${JUMPSTART_STREAMING_HASHER_HASH_COUNT}" == "0" ]] || {
    echo "Failed to parse subtree hashes from ${jumpstart_file}" >&2
    return 1
  }

  export JUMPSTART_BLOCK_NUMBER
  export JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH
  export JUMPSTART_STREAMING_HASHER_LEAF_COUNT
  export JUMPSTART_STREAMING_HASHER_HASH_COUNT
  export JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES

  log "Loaded jumpstart.bin values: blockNum=${JUMPSTART_BLOCK_NUMBER}, leafCount=${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}, hashCount=${JUMPSTART_STREAMING_HASHER_HASH_COUNT}"
}

prepare_js_sdk_runtime() {
  log "Preparing JS SDK scenario runner"
  write_sdk_verifier
  write_sdk_network_probe
  write_file121_jumpstart_update
  cd "${WORK_DIR}"
  npm init -y >/dev/null 2>&1
  npm install --no-fund --no-audit @hashgraph/sdk @hashgraph/proto >/dev/null 2>&1

  export GRPC_ENDPOINT="127.0.0.1:${CN_GRPC_LOCAL_PORT}"
  export MIRROR_REST_URL="http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
  export OPERATOR_ACCOUNT_ID
  export OPERATOR_PRIVATE_KEY
}

log "Validating prerequisites"
log "Node deployment plan: ${CONSENSUS_NODE_COUNT} consensus node(s) [${NODE_ALIASES}]"
log "Execution mode: ONLY_STEP6=${ONLY_STEP6}"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd npm
require_cmd node
require_cmd curl
require_cmd jq
require_cmd java

if [[ "${USE_BLOCK_NODE_JUMPSTART}" == "true" ]]; then
  if ! validate_block_node_repo; then
    exit 1
  fi
fi

if [[ ! -d "${LOCAL_BUILD_PATH}" ]]; then
  echo "Local build path not found: ${LOCAL_BUILD_PATH}" >&2
  echo "Build the consensus node first (for example: ./gradlew assemble)" >&2
  exit 1
fi
if [[ ! -f "${LOG4J2_XML_PATH}" ]]; then
  echo "log4j2 config not found: ${LOG4J2_XML_PATH}" >&2
  exit 1
fi
if [[ ! -f "${APP_PROPS_071_FILE}" ]]; then
  echo "application.properties file not found: ${APP_PROPS_071_FILE}" >&2
  exit 1
fi
if [[ ! -f "${APP_PROPS_072_FILE}" ]]; then
  echo "application.properties file not found: ${APP_PROPS_072_FILE}" >&2
  exit 1
fi
if [[ ! -f "${APP_PROPS_073_FILE}" ]]; then
  echo "application.properties file not found: ${APP_PROPS_073_FILE}" >&2
  exit 1
fi
if ! validate_local_build_path "${LOCAL_BUILD_PATH}"; then
  echo "Invalid LOCAL_BUILD_PATH content: ${LOCAL_BUILD_PATH}" >&2
  echo "Expected jar artifacts under both data/lib and data/apps (run ./gradlew assemble)." >&2
  exit 1
fi

if [[ "${ONLY_STEP6}" == "true" ]]; then
  log "ONLY_STEP6=true: reusing existing deployment ${SOLO_DEPLOYMENT} in namespace ${SOLO_NAMESPACE}"
  cleanup_stale_port_forwards
  wait_for_consensus_pods_ready 300
  wait_for_haproxy_ready 300
  ensure_mirror_rest_service_for_step6
  restart_post_upgrade_port_forwards
  log "Waiting for mirror REST in ONLY_STEP6 mode"
  wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/network/nodes" 60 5
  prepare_js_sdk_runtime
  log "ONLY_STEP6: probing SDK transaction/query readiness before Step 6"
  if ! ensure_sdk_network_ready; then
    log "ONLY_STEP6: SDK probe failed before Step 6; the network is not serving gRPC requests"
    collect_consensus_diagnostics || true
    exit 1
  fi
else
  log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
  cleanup_stale_port_forwards
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  log "Cleaning local artifacts from previous runs"
  cleanup_record_stream_files_only
  rm -rf "${WRAPPED_BLOCKS_DIR}" >/dev/null 2>&1 || true

  log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
  kind create cluster -n "${SOLO_CLUSTER_NAME}"

  log "Configuring Solo deployment"
  solo cluster-ref config connect --cluster-ref kind-${SOLO_CLUSTER_NAME} --context kind-${SOLO_CLUSTER_NAME}
  log "Deleting existing Solo deployment config ${SOLO_DEPLOYMENT} (if any)"
  solo deployment config delete --deployment "${SOLO_DEPLOYMENT}" --quiet-mode >/dev/null 2>&1 || true
  solo deployment config create -n "${SOLO_NAMESPACE}" --deployment "${SOLO_DEPLOYMENT}"
  solo deployment cluster attach --deployment "${SOLO_DEPLOYMENT}" --cluster-ref kind-${SOLO_CLUSTER_NAME} --num-consensus-nodes "${CONSENSUS_NODE_COUNT}"
  solo cluster-ref config setup -s "${SOLO_CLUSTER_SETUP_NAMESPACE}" --prometheus-stack true
  if ! start_grafana_port_forward; then
    if [[ "${ALLOW_GRAFANA_PORT_FORWARD_FAILURE}" == "true" ]]; then
      log "WARNING: Grafana port-forward could not be established; continuing without Grafana tunnel"
    else
      exit 1
    fi
  fi

  announce_step "1" "Deploy baseline network and verify pre-upgrade transaction flow"
  log "Deploying consensus network at ${INITIAL_RELEASE_TAG} with 0.71 application.properties"
  solo keys consensus generate --gossip-keys --tls-keys --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
  solo consensus network deploy --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --application-properties "${APP_PROPS_071_FILE}" --log4j2-xml "${LOG4J2_XML_PATH}" --service-monitor true --pod-log true --pvcs true --release-tag "${INITIAL_RELEASE_TAG}"
  solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --release-tag "${INITIAL_RELEASE_TAG}"
  solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600

  log "Deploying mirror node and explorer"
  solo mirror node add --deployment "${SOLO_DEPLOYMENT}" --enable-ingress --pinger
  run_explorer_add_with_retry

  log "Starting port-forwards for consensus node and mirror REST"
  restart_post_upgrade_port_forwards

  log "Waiting for mirror REST to become available"
  wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/network/nodes" 60 5
  prepare_js_sdk_runtime

  log "Step 1: submit crypto create; expect success and mirror visibility"
  node "${NODE_SCRIPT}"

  log "Waiting 45s after Step 1"
  sleep 45

  announce_step "2" "Upgrade consensus network to ${UPGRADE_072_RELEASE_TAG}"
  log "Step 2: Upgrade CN network to 0.72 (target ${UPGRADE_072_RELEASE_TAG})"
  run_with_consensus_diagnostics "solo 0.72 network upgrade" \
    solo consensus network upgrade --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" --upgrade-version "${UPGRADE_072_RELEASE_TAG}" --quiet-mode --force --application-properties "${APP_PROPS_072_FILE}"

  announce_step "3" "Wait for upgraded nodes and refresh service port-forwards"
  log "Step 3: apply ${UPGRADE_072_RELEASE_TAG} application.properties overrides for cutover flow"

  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600

  restart_post_upgrade_port_forwards
  log "Waiting for mirror REST after port-forward restart"
  wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/network/nodes" 60 5

  announce_step "4" "Verify post-upgrade transaction flow and mirror visibility"
  log "Step 4: verify post-upgrade crypto create and mirror visibility"
  export MIRROR_ACCOUNT_WAIT_MS="${MIRROR_ACCOUNT_WAIT_MS:-600000}"
  node "${NODE_SCRIPT}"
fi

announce_step "5" "Download record files and wrap them into jumpstart artifacts"
log "Step 5: mirror block query and record/wrap artifact generation"
log "Step 5: waiting 30s before querying mirror for latest block number"
sleep 30

MIRROR_BLOCKS_JSON="$(curl -sf "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?order=desc&limit=1")" || {
  echo "Failed to GET /api/v1/blocks from mirror REST" >&2
  exit 1
}
MIRROR_BLOCK_NUMBER="$(echo "${MIRROR_BLOCKS_JSON}" | jq -r '.blocks[0].number')"
if [[ -z "${MIRROR_BLOCK_NUMBER}" || "${MIRROR_BLOCK_NUMBER}" == "null" ]]; then
  echo "Could not parse latest block number from mirror response" >&2
  exit 1
fi
log "Mirror latest block number: ${MIRROR_BLOCK_NUMBER}"
export MIRROR_BLOCK_NUMBER

log "Step 5: downloading record stream files from MinIO to ${RECORD_STREAMS_DIR} (blocks <= ${MIRROR_BLOCK_NUMBER})"
download_solo_minio_record_streams "${MIRROR_BLOCK_NUMBER}" "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"

log "Step 5: preparing .tar.zstd day archives from downloaded record streams for blocks wrap input"
prepare_wrap_day_archives_from_record_streams

log "Step 5: generating block_times.bin and day_blocks.json for local wrap run"
generate_block_node_metadata_from_mirror "${MIRROR_BLOCK_NUMBER}"

log "Step 5: running Block Node offline wrap tool (records -> wrapped blocks + jumpstart.bin)"
run_block_node_wrap_tool "${WRAP_COMPRESSED_DAYS_DIR}" "${WRAPPED_BLOCKS_DIR}"

announce_step "6" "Issue File 121 jumpstart update, then upgrade to ${UPGRADE_073_RELEASE_TAG}"
STEP6_USE_BLOCK_NODE_JUMPSTART="${USE_BLOCK_NODE_JUMPSTART}"
if [[ "${ONLY_STEP6}" == "true" && "${STEP6_USE_BLOCK_NODE_JUMPSTART}" == "true" ]]; then
  if [[ -n "${JUMPSTART_BIN_PATH}" && -f "${JUMPSTART_BIN_PATH}" ]]; then
    log "ONLY_STEP6: using explicit jumpstart.bin at ${JUMPSTART_BIN_PATH}"
  else
    jumpstart_candidate="$(find "${WRAPPED_BLOCKS_DIR}" -type f -name "jumpstart.bin" 2>/dev/null | head -n 1 || true)"
    if [[ -n "${jumpstart_candidate}" && -f "${jumpstart_candidate}" ]]; then
      export JUMPSTART_BIN_PATH="${jumpstart_candidate}"
      log "ONLY_STEP6: auto-discovered jumpstart.bin at ${JUMPSTART_BIN_PATH}"
    else
      log "WARNING: ONLY_STEP6 has no jumpstart.bin (JUMPSTART_BIN_PATH/WRAPPED_BLOCKS_DIR). Falling back to mirror block number."
      log "WARNING: Set USE_BLOCK_NODE_JUMPSTART=false (recommended for Step 6-only) or provide JUMPSTART_BIN_PATH."
      STEP6_USE_BLOCK_NODE_JUMPSTART="false"
    fi
  fi
fi

if [[ "${STEP6_USE_BLOCK_NODE_JUMPSTART}" == "true" ]]; then
  log "Step 6: parsing jumpstart.bin and loading blockStream.jumpstart.* values"
  load_jumpstart_env_from_bin "${JUMPSTART_BIN_PATH}"
else
  export JUMPSTART_BLOCK_NUMBER="${MIRROR_BLOCK_NUMBER}"
  log "Step 6: USE_BLOCK_NODE_JUMPSTART=false, using fallback jumpstart values from env (blockNum=${JUMPSTART_BLOCK_NUMBER})"
fi

log "Step 6: issuing File 0.0.121 update with blockStream.jumpstart.* (blockNum=${JUMPSTART_BLOCK_NUMBER:-${MIRROR_BLOCK_NUMBER}})"
run_file121_update_with_retry

# Step 6 upgrade path remains WIP; keep disabled until cutover path is validated.
# log "Step 6: waiting 30s after File 121 update before consensus upgrade"
sleep 30
log "Step 6: running solo consensus network upgrade to ${UPGRADE_073_RELEASE_TAG} using local build (${LOCAL_BUILD_PATH})"
run_073_upgrade

wait_for_consensus_pods_ready 600
wait_for_haproxy_ready 600
restart_post_upgrade_port_forwards
verify_local_build_on_consensus_nodes
log "Waiting for mirror REST after 0.73 upgrade port-forward restart"
wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/network/nodes" 60 5

log "Cutover phase complete (through 0.73): PASS"
