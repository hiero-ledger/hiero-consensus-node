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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../.." && pwd)"

export SOLO_CLUSTER_NAME="solo"
export SOLO_NAMESPACE="solo"
export SOLO_CLUSTER_SETUP_NAMESPACE="solo-cluster"
export SOLO_DEPLOYMENT="solo-deployment"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3}"
CONSENSUS_NODE_COUNT="$(awk -F',' '{print NF}' <<< "${NODE_ALIASES}")"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
LOG4J2_XML_PATH="${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml"
APP_PROPS_071_FILE="${SCRIPT_DIR}/resources/0.71/application.properties"
APP_PROPS_072_FILE="${SCRIPT_DIR}/resources/0.72/application.properties"
APP_PROPS_073_FILE="${SCRIPT_DIR}/resources/0.73/application.properties"
INITIAL_RELEASE_TAG="${INITIAL_RELEASE_TAG:-v0.71.2}"
UPGRADE_072_RELEASE_TAG="${UPGRADE_072_RELEASE_TAG:-v0.72.0-rc.2}"
# Used with --local-build-path for Solo chart/metadata; jar/image comes from LOCAL_BUILD_PATH.
UPGRADE_073_RELEASE_TAG="${UPGRADE_073_RELEASE_TAG:-0.73.0}"

# Placeholders for File 121 jumpstart-related network properties (until real jumpstart.bin tooling fills these).
JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH="${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH:-0000000000000000000000000000000000000000000000000000000000000000}"
JUMPSTART_STREAMING_HASHER_LEAF_COUNT="${JUMPSTART_STREAMING_HASHER_LEAF_COUNT:-1}"
JUMPSTART_STREAMING_HASHER_HASH_COUNT="${JUMPSTART_STREAMING_HASHER_HASH_COUNT:-1}"
# Comma-separated dummy subtree hashes (placeholder until real jumpstart tooling).
JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES="${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES:-1111111111111111111111111111111111111111111111111111111111111111}"
export JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH
export JUMPSTART_STREAMING_HASHER_LEAF_COUNT
export JUMPSTART_STREAMING_HASHER_HASH_COUNT
export JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES

CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
GRAFANA_LOCAL_PORT="${GRAFANA_LOCAL_PORT:-3000}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

# Downloaded record stream objects from Solo MinIO (Step 5), next to this script.
RECORD_STREAMS_DIR="${RECORD_STREAMS_DIR:-${SCRIPT_DIR}/recordStreams}"
MINIO_LOCAL_PORT="${MINIO_LOCAL_PORT:-19000}"
MINIO_BUCKET="${MINIO_BUCKET:-solo-streams}"
MINIO_NAMESPACE="${MINIO_NAMESPACE:-${SOLO_NAMESPACE}}"
# Optional overrides if auto-discovery fails (service name in MINIO_NAMESPACE).
MINIO_SERVICE_NAME="${MINIO_SERVICE_NAME:-}"

OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"

WORK_DIR="$(mktemp -d)"
NODE_SCRIPT="${WORK_DIR}/sdk-crypto-create-check.js"
FILE_121_JUMPSTART_SCRIPT="${WORK_DIR}/file-121-jumpstart-update.js"
CN_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-cn.log"
MIRROR_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-mirror.log"
GRAFANA_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-grafana.log"

CN_PORT_FORWARD_PID=""
MIRROR_PORT_FORWARD_PID=""
GRAFANA_PORT_FORWARD_PID=""

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
  log_banner "STEP ${step}/5: ${description}"
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

wait_for_consensus_pods_ready() {
  local timeout_secs="${1:-600}"
  local pod
  local nodes
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  for pod in "${nodes[@]}"; do
    log "Waiting for network-${pod}-0 to become Ready"
    kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/network-${pod}-0" --timeout="${timeout_secs}s"
  done
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
  log "Restarting port-forwards after upgrade (previous tunnels may be stale)"
  if [[ -n "${CN_PORT_FORWARD_PID}" ]]; then
    kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    CN_PORT_FORWARD_PID=""
  fi
  if [[ -n "${MIRROR_PORT_FORWARD_PID}" ]]; then
    kill "${MIRROR_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    MIRROR_PORT_FORWARD_PID=""
  fi
  sleep 1
  kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >"${CN_PORT_FORWARD_LOG}" 2>&1 &
  CN_PORT_FORWARD_PID="$!"
  kubectl -n "${SOLO_NAMESPACE}" port-forward svc/mirror-1-rest "${MIRROR_REST_LOCAL_PORT}:http" >"${MIRROR_PORT_FORWARD_LOG}" 2>&1 &
  MIRROR_PORT_FORWARD_PID="$!"
  sleep 2
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
  local pod endpoint creds_tmp all_objects match_file creds_file
  local u p selected_u selected_p fname remote subpath dest
  local server_url cfg_full
  local list_ok=0 list_attempt endpoint_try cred_pair
  local found=0 missing=0

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
    'cat "${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}" 2>/dev/null || true' 2>/dev/null || true)"
  server_url="$(echo "${cfg_full}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_SERVER_URL=//p' | head -1 | tr -d '"\r')"

  all_objects="$(mktemp)"
  # Retries plus alternate in-cluster endpoints avoid transient DNS/service hiccups during upgrade.
  for list_attempt in 1 2 3 4 5 6; do
    for endpoint_try in \
      "${server_url}" \
      "http://${svc}.${MINIO_NAMESPACE}.svc.cluster.local:${svc_port}" \
      "http://minio-hl.${MINIO_NAMESPACE}.svc.cluster.local:9000"; do
      [[ -n "${endpoint_try}" ]] || continue
      endpoint="${endpoint_try}"
      while IFS=$'\t' read -r u p; do
        [[ -n "${u}" && -n "${p}" ]] || continue
        if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -lc \
          "mc alias set local '${endpoint}' '${u}' '${p}' >/dev/null 2>&1; mc find local/${MINIO_BUCKET} --name '*.rcd.gz'" \
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

  while IFS= read -r fname; do
    [[ -z "${fname}" ]] && continue
    local matched=0
    match_file="$(mktemp)"
    rg -F "/${fname}" "${all_objects}" >"${match_file}" 2>/dev/null || true
    while IFS= read -r remote; do
      [[ -z "${remote}" ]] && continue
      subpath="${remote#local/${MINIO_BUCKET}/recordstreams/}"
      if [[ "${subpath}" == "${remote}" ]]; then
        subpath="$(basename "${remote}")"
      fi
      dest="${RECORD_STREAMS_DIR}/${subpath}"
      mkdir -p "$(dirname "${dest}")"
      if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -lc \
        "mc alias set local '${endpoint}' '${selected_u}' '${selected_p}' >/dev/null 2>&1; mc cat '${remote}'" \
        >"${dest}" 2>/dev/null; then
        matched=1
        found=$((found + 1))
      else
        rm -f "${dest}" >/dev/null 2>&1 || true
      fi
    done < "${match_file}"
    rm -f "${match_file}" >/dev/null 2>&1 || true
    if (( matched == 0 )); then
      missing=$((missing + 1))
    fi
  done < "${names_file}"

  rm -f "${all_objects}" >/dev/null 2>&1 || true

  log "In-pod MinIO fallback finished: copied ${found} file(s); ${missing} name(s) not found"
  if (( found == 0 )); then
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
    echo "${j}" | jq -r --argjson max "${max_block}" '.blocks[] | select(.number <= $max) | .name' >>"${out_file}"
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

fix_consensus_metrics_scrape_config() {
  log "Patching ServiceMonitor for consensus-node metrics discovery"
  kubectl -n "${SOLO_NAMESPACE}" patch servicemonitor solo-service-monitor --type merge -p '{
    "metadata": {
      "labels": {
        "release": "kube-prometheus-stack"
      }
    },
    "spec": {
      "selector": {
        "matchLabels": {
          "solo.hedera.com/type": "network-node-svc"
        }
      }
    }
  }' >/dev/null

  log "Patching consensus-node services to scrape metrics on targetPort 9999"
  local services
  services="$(kubectl -n "${SOLO_NAMESPACE}" get svc -l solo.hedera.com/type=network-node-svc -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')"
  if [[ -z "${services}" ]]; then
    echo "No consensus-node services found with label solo.hedera.com/type=network-node-svc" >&2
    return 1
  fi

  local svc
  while IFS= read -r svc; do
    [[ -z "${svc}" ]] && continue
    kubectl -n "${SOLO_NAMESPACE}" patch service "${svc}" --type merge -p '{
      "spec": {
        "ports": [
          {
            "name": "gossip",
            "port": 50111,
            "targetPort": 50111
          },
          {
            "name": "grpc-non-tls",
            "port": 50211,
            "targetPort": 50211
          },
          {
            "name": "grpc-tls",
            "port": 50212,
            "targetPort": 50212
          },
          {
            "name": "prometheus",
            "port": 9090,
            "targetPort": 9999
          }
        ]
      }
    }' >/dev/null
  done <<< "${services}"
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
  const blockNum = process.env.MIRROR_BLOCK_NUMBER;
  if (!operatorPrivateKey) {
    throw new Error("OPERATOR_PRIVATE_KEY is required");
  }
  if (!blockNum || blockNum === "null") {
    throw new Error("MIRROR_BLOCK_NUMBER is required (latest block from mirror)");
  }

  const prevHash =
    process.env.JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH || "";
  const leafCount = process.env.JUMPSTART_STREAMING_HASHER_LEAF_COUNT ?? "1";
  const hashCount = process.env.JUMPSTART_STREAMING_HASHER_HASH_COUNT ?? "1";
  const subtreeHashes =
    process.env.JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES ??
    "1111111111111111111111111111111111111111111111111111111111111111";

  const overrides = {
    "blockStream.jumpstart.blockNum": String(blockNum),
    "blockStream.jumpstart.previousWrappedRecordBlockHash": prevHash,
    "blockStream.jumpstart.streamingHasherLeafCount": String(leafCount),
    "blockStream.jumpstart.streamingHasherHashCount": String(hashCount),
    "blockStream.jumpstart.streamingHasherSubtreeHashes": subtreeHashes,
  };

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

log "Validating prerequisites"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd npm
require_cmd node
require_cmd curl
require_cmd jq

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

log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true

log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
kind create cluster -n "${SOLO_CLUSTER_NAME}"

log "Configuring Solo deployment"
solo cluster-ref config connect --cluster-ref kind-${SOLO_CLUSTER_NAME} --context kind-${SOLO_CLUSTER_NAME}
log "Deleting existing Solo deployment config ${SOLO_DEPLOYMENT} (if any)"
solo deployment config delete --deployment "${SOLO_DEPLOYMENT}" --quiet-mode >/dev/null 2>&1 || true
solo deployment config create -n "${SOLO_NAMESPACE}" --deployment "${SOLO_DEPLOYMENT}"
solo deployment cluster attach --deployment "${SOLO_DEPLOYMENT}" --cluster-ref kind-${SOLO_CLUSTER_NAME} --num-consensus-nodes "${CONSENSUS_NODE_COUNT}"
solo cluster-ref config setup -s "${SOLO_CLUSTER_SETUP_NAMESPACE}" --prometheus-stack true
start_grafana_port_forward

announce_step "1" "Deploy baseline network and verify pre-upgrade transaction flow"
log "Deploying consensus network at ${INITIAL_RELEASE_TAG} with 0.71 application.properties"
solo keys consensus generate --gossip-keys --tls-keys --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
solo consensus network deploy --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --application-properties "${APP_PROPS_071_FILE}" --log4j2-xml "${LOG4J2_XML_PATH}" --service-monitor true --pod-log true --pvcs true --release-tag "${INITIAL_RELEASE_TAG}"
solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --release-tag "${INITIAL_RELEASE_TAG}"
solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
wait_for_consensus_pods_ready 600
wait_for_haproxy_ready 600

#fix_consensus_metrics_scrape_config

log "Deploying mirror node and explorer"
solo mirror node add --deployment "${SOLO_DEPLOYMENT}" --enable-ingress --pinger
solo explorer node add --deployment "${SOLO_DEPLOYMENT}"

log "Starting port-forwards for consensus node and mirror REST"
kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >"${CN_PORT_FORWARD_LOG}" 2>&1 &
CN_PORT_FORWARD_PID="$!"
kubectl -n "${SOLO_NAMESPACE}" port-forward svc/mirror-1-rest "${MIRROR_REST_LOCAL_PORT}:http" >"${MIRROR_PORT_FORWARD_LOG}" 2>&1 &
MIRROR_PORT_FORWARD_PID="$!"

log "Waiting for mirror REST to become available"
wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/network/nodes" 60 5

log "Preparing JS SDK scenario runner"
write_sdk_verifier
write_file121_jumpstart_update
cd "${WORK_DIR}"
npm init -y >/dev/null 2>&1
npm install --no-fund --no-audit @hashgraph/sdk @hashgraph/proto >/dev/null 2>&1

export GRPC_ENDPOINT="127.0.0.1:${CN_GRPC_LOCAL_PORT}"
export MIRROR_REST_URL="http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
export OPERATOR_ACCOUNT_ID
export OPERATOR_PRIVATE_KEY

log "Step 1: submit crypto create; expect success and mirror visibility"
node "${NODE_SCRIPT}"

log "Waiting 120s after Step 1"
sleep 120

announce_step "2" "Upgrade consensus network to ${UPGRADE_072_RELEASE_TAG}"
log "Step 2: Upgrade CN network to 0.72 (target ${UPGRADE_072_RELEASE_TAG})"
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

announce_step "5" "Process mirror/minio data, update File 121, then upgrade to 0.73"
log "Step 5: mirror block query, File 121 jumpstart properties, upgrade to 0.73 (local build)"
log "Step 5: waiting 30s before querying mirror for latest block number"
sleep 30

MIRROR_BLOCKS_JSON="$(curl -sf "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?order=desc&limit=1")" || {
  echo "Failed to GET /api/v1/blocks from mirror REST" >&2
  exit 1
}
export MIRROR_BLOCK_NUMBER
MIRROR_BLOCK_NUMBER="$(echo "${MIRROR_BLOCKS_JSON}" | jq -r '.blocks[0].number')"
if [[ -z "${MIRROR_BLOCK_NUMBER}" || "${MIRROR_BLOCK_NUMBER}" == "null" ]]; then
  echo "Could not parse latest block number from mirror response" >&2
  exit 1
fi
log "Mirror latest block number: ${MIRROR_BLOCK_NUMBER}"
export MIRROR_BLOCK_NUMBER

log "Step 5: downloading record stream files from MinIO to ${RECORD_STREAMS_DIR} (blocks <= ${MIRROR_BLOCK_NUMBER})"
download_solo_minio_record_streams "${MIRROR_BLOCK_NUMBER}" "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"

log "Step 5: issuing File 0.0.121 update with blockStream.jumpstart.* (blockNum=${MIRROR_BLOCK_NUMBER})"
node "${FILE_121_JUMPSTART_SCRIPT}"

log "Step 5: waiting 30s after File 121 update before consensus upgrade"
sleep 30

log "Step 5: upgrading consensus network to 0.73 using local build (${LOCAL_BUILD_PATH}) and ${APP_PROPS_073_FILE}"
solo consensus network upgrade --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" \
  --local-build-path "${LOCAL_BUILD_PATH}" \
  --application-properties "${APP_PROPS_073_FILE}" \
  --quiet-mode --force

wait_for_consensus_pods_ready 600
wait_for_haproxy_ready 600
restart_post_upgrade_port_forwards
log "Waiting for mirror REST after 0.73 upgrade port-forward restart"
wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/network/nodes" 60 5

log "Cutover phase complete (through 0.73): PASS"
