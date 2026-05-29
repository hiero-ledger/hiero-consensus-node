#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Wrapped Record Block Jumpstart Scenario
# 1) Deploy a solo consensus network on v0.74.0
# 2) Wait 30s, then run offline wrapping from genesis records
# 3) Build temp upgrade application.properties from 0.74 base + jumpstart values
# 4) Upgrade to release tag with temp properties
# 5) Parse migration vote values from hgcaa.log and extract Block N
# 6) Replay wrapping up to Block N and compare vote values to replay jumpstart.bin

set -euo pipefail
set +m

usage() {
  cat <<'EOF'
Usage: solo-wrb-jumpstart.sh [--nodes 3|4]

Environment:
  INITIAL_RELEASE_TAG           Deploy release tag (default: v0.74.0)
  UPGRADE_TAG                   Consensus-node release tag to upgrade the network to
                                (default: v0.75.0-rc.3)
  DEPLOY_APP_PROPS_FILE         application.properties used for initial deploy
                                (default: wrapped-record-block-jumpstart/resources/0.73/application.properties)
  BASE_074_APP_PROPS_FILE       Base 0.74 properties used to generate temp upgrade file
                                (default: wrapped-record-block-jumpstart/resources/0.74/application.properties)
  LOG4J2_XML_PATH               log4j2 xml path (default: <repo>/hedera-node/configuration/dev/log4j2.xml)
  BLOCK_NODE_REPO_PATH          Path to hiero-block-node checkout (default: ../hiero-block-node)
  BLOCKS_WRAP_EXTRA_ARGS        Extra args appended to `blocks wrap ...`
  MIRROR_REST_URL               Mirror REST base URL. If set, script will not use mirror service port-forward.
  KEEP_NETWORK                  true|false (default: true)
  GENERATED_DIR                 Base directory for generated artifacts
                                (default: wrapped-record-block-jumpstart/generated)
  MIRROR_REST_LOCAL_PORT        Local port for mirror REST forwarding (default: 5551)
  CN_GRPC_LOCAL_PORT            Local port for consensus gRPC forwarding (default: 50211)
  RECORD_STREAMS_DIR            Local directory for downloaded record files
  WRAPPED_BLOCKS_DIR            Base directory for wrapped blocks outputs
  MINIO_BUCKET                  MinIO bucket name (default: solo-streams)
  MINIO_NAMESPACE               Namespace with MinIO service/pod (default: solo)
  MINIO_SERVICE_NAME            Optional override for MinIO service name
  BLOCKS_AFTER_JUMPSTART        Number of additional blocks to wait for after migration
                                before capturing a saved state (default: 100)
  DEPLOY_EXPLORER               true|false - deploy the Hiero Explorer web UI for browsing
                                blocks/transactions (default: true)
  EXPLORER_LOCAL_PORT           Local port for the Explorer UI port-forward (default: 8080)

After the migration-vote comparison succeeds the script waits BLOCKS_AFTER_JUMPSTART
more blocks, then extracts the latest signed-state directory from network-node1-0
into \${WORK_DIR}/saved-state/<round>/ for follow-up state inspection.

Examples:
  ./solo-wrb-jumpstart.sh
  NODE_ALIASES=node1,node2,node3 UPGRADE_TAG=v0.75.0-rc.3 ./solo-wrb-jumpstart.sh --nodes 3
EOF
}

NODE_COUNT_PARAM=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--nodes)
      [[ $# -ge 2 ]] || { echo "Missing value for $1 (expected 3 or 4)" >&2; exit 1; }
      NODE_COUNT_PARAM="$2"
      shift 2
      ;;
    --nodes=*)
      NODE_COUNT_PARAM="${1#*=}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../.." && pwd)"

export SOLO_CLUSTER_NAME="wrb-jumpstart"
export SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
export SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-cluster}"
export SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-deployment}"

if [[ -n "${NODE_COUNT_PARAM}" ]]; then
  case "${NODE_COUNT_PARAM}" in
    3) NODE_ALIASES="node1,node2,node3" ;;
    4) NODE_ALIASES="node1,node2,node3,node4" ;;
    *) echo "Invalid --nodes value: ${NODE_COUNT_PARAM} (expected 3 or 4)" >&2; exit 1 ;;
  esac
else
  NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3,node4}"
fi

CONSENSUS_NODE_COUNT="$(awk -F',' '{print NF}' <<< "${NODE_ALIASES}")"
INITIAL_RELEASE_TAG="${INITIAL_RELEASE_TAG:-v0.74.0}"
UPGRADE_TAG="${UPGRADE_TAG:-v0.75.0-rc.3}"
LOG4J2_XML_PATH="${LOG4J2_XML_PATH:-${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml}"
DEPLOY_APP_PROPS_FILE="${DEPLOY_APP_PROPS_FILE:-${SCRIPT_DIR}/resources/0.73/application.properties}"
BASE_074_APP_PROPS_FILE="${BASE_074_APP_PROPS_FILE:-${SCRIPT_DIR}/resources/0.74/application.properties}"
BLOCK_NODE_REPO_PATH="${BLOCK_NODE_REPO_PATH:-${REPO_ROOT}/../hiero-block-node}"
BLOCKS_WRAP_EXTRA_ARGS="${BLOCKS_WRAP_EXTRA_ARGS:-}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"
BLOCKS_AFTER_JUMPSTART="${BLOCKS_AFTER_JUMPSTART:-100}"
DEPLOY_EXPLORER="${DEPLOY_EXPLORER:-true}"

CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
MIRROR_REST_SERVICE="${MIRROR_REST_SERVICE:-mirror-1-rest}"
EXPLORER_LOCAL_PORT="${EXPLORER_LOCAL_PORT:-8080}"
MIRROR_REST_URL="${MIRROR_REST_URL:-}"
MINIO_BUCKET="${MINIO_BUCKET:-solo-streams}"
MINIO_NAMESPACE="${MINIO_NAMESPACE:-${SOLO_NAMESPACE}}"
MINIO_SERVICE_NAME="${MINIO_SERVICE_NAME:-}"

GENERATED_DIR="${GENERATED_DIR:-${SCRIPT_DIR}/generated}"
RECORD_STREAMS_DIR="${RECORD_STREAMS_DIR:-${GENERATED_DIR}/recordStreams}"
WRAPPED_BLOCKS_DIR="${WRAPPED_BLOCKS_DIR:-${GENERATED_DIR}/wrappedBlocks}"

WORK_DIR="${WORK_DIR:-${GENERATED_DIR}/work}"
TMP_UPGRADE_APP_PROPS="${WORK_DIR}/application.properties"
SAVED_STATE_DIR="${WORK_DIR}/saved-state"
MIRROR_METADATA_SCRIPT="${SCRIPT_DIR}/js/generate-mirror-metadata.js"
JUMPSTART_PARSE_SCRIPT="${SCRIPT_DIR}/js/parse-jumpstart-bin.js"
BLOCK_TIMES_FILE="${WORK_DIR}/block_times.bin"
DAY_BLOCKS_FILE="${WORK_DIR}/day_blocks.json"
MINIO_DOWNLOAD_LOG="${WORK_DIR}/minio-download.log"
MIRROR_METADATA_LOG="${WORK_DIR}/mirror-metadata.log"
WRAP_INPUT_PREP_LOG="${WORK_DIR}/wrap-input-prep.log"
BLOCK_NODE_WRAP_LOG="${WORK_DIR}/block-node-wrap.log"
MIGRATION_COMPARE_LOG="${WORK_DIR}/migration-compare.log"
CN_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-cn.log"
MIRROR_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-mirror.log"
EXPLORER_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-explorer.log"
WRAP_DAYS_SRC_DIR="${WORK_DIR}/recordDays"
WRAP_COMPRESSED_DAYS_DIR="${WORK_DIR}/compressedDays"
ZSTD_WRAPPER_DIR="${WORK_DIR}/zstd-wrapper"
ZSTD_WRAPPER_SRC="${ZSTD_WRAPPER_DIR}/ZstdCat"
ZSTD_WRAPPER_BIN="${ZSTD_WRAPPER_DIR}/zstd"

FIRST_WRAP_DIR="${WRAPPED_BLOCKS_DIR}/initial"
SECOND_WRAP_DIR="${WRAPPED_BLOCKS_DIR}/migration-replay"
FIRST_JUMPSTART_BIN=""
SECOND_JUMPSTART_BIN=""
FIRST_WRAP_BLOCK_NUMBER=""
MIGRATION_BLOCK_NUMBER=""
MIGRATION_PREV_HASH=""
MIGRATION_INTERMEDIATE_HASHES=""
MIGRATION_LEAF_COUNT=""
SAVED_STATE_REMOTE_DIR=""
SAVED_STATE_ROUND=""

CN_PORT_FORWARD_PID=""
MIRROR_PORT_FORWARD_PID=""
EXPLORER_PORT_FORWARD_PID=""
EXPLORER_URL=""

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

cleanup() {
  local exit_code=$?
  set +e
  if [[ -n "${CN_PORT_FORWARD_PID}" ]]; then
    kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${MIRROR_PORT_FORWARD_PID}" ]]; then
    kill "${MIRROR_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${EXPLORER_PORT_FORWARD_PID}" ]]; then
    kill "${EXPLORER_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_NETWORK}" != "true" && ${exit_code} -eq 0 ]]; then
    log "KEEP_NETWORK=false, destroying Solo resources and kind cluster"
    solo explorer node destroy --deployment "${SOLO_DEPLOYMENT}" --force >/dev/null 2>&1 || true
    solo mirror node destroy --deployment "${SOLO_DEPLOYMENT}" --force >/dev/null 2>&1 || true
    solo consensus node stop --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" >/dev/null 2>&1 || true
    solo consensus network destroy --deployment "${SOLO_DEPLOYMENT}" --force >/dev/null 2>&1 || true
    kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

require_cmd() {
  local cmd="$1"
  command -v "${cmd}" >/dev/null 2>&1 || { echo "Required command not found: ${cmd}" >&2; exit 1; }
}

validate_block_node_repo() {
  [[ -d "${BLOCK_NODE_REPO_PATH}" ]] || { echo "BLOCK_NODE_REPO_PATH not found: ${BLOCK_NODE_REPO_PATH}" >&2; return 1; }
  [[ -x "${BLOCK_NODE_REPO_PATH}/gradlew" ]] || { echo "Block Node gradlew not executable: ${BLOCK_NODE_REPO_PATH}/gradlew" >&2; return 1; }
}

ensure_zstd_command_for_block_node() {
  local zstd_jar
  if command -v zstd >/dev/null 2>&1; then
    return 0
  fi
  require_cmd java
  zstd_jar="$(find "${HOME}/.gradle/caches/modules-2/files-2.1/com.github.luben/zstd-jni" -name 'zstd-jni-*.jar' 2>/dev/null | sed -n '1p')"
  [[ -n "${zstd_jar}" && -f "${zstd_jar}" ]] || {
    echo "zstd command not found and no zstd-jni jar found in ~/.gradle cache." >&2
    return 1
  }
  mkdir -p "${ZSTD_WRAPPER_DIR}"
  cat > "${ZSTD_WRAPPER_SRC}.java" <<'EOF'
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import com.github.luben.zstd.ZstdInputStream;
public class ZstdCat {
  public static void main(String[] args) throws Exception {
    if (args.length < 1) System.exit(2);
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
    --decompress|-d|--stdout|-c|-T*|--threads=*|--) ;;
    -*) ;;
    *) input="$arg" ;;
  esac
done
[[ -n "${input}" ]] || exit 2
exec java --class-path "${ZSTD_JNI_JAR}" "${ZSTD_WRAPPER_CLASS}" "${input}"
EOF
  chmod +x "${ZSTD_WRAPPER_BIN}"
  export ZSTD_JNI_JAR="${zstd_jar}"
  export ZSTD_WRAPPER_CLASS="${ZSTD_WRAPPER_SRC}.java"
  export PATH="${ZSTD_WRAPPER_DIR}:${PATH}"
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
  return 1
}

kill_processes_on_local_port() {
  local port="$1"
  local pids=""
  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -ti "tcp:${port}" 2>/dev/null || true)"
    if [[ -n "${pids}" ]]; then
      kill "${pids}" >/dev/null 2>&1 || true
    fi
  fi
}

cleanup_stale_port_forwards() {
  pkill -f "port-forward svc/haproxy-node1-svc .*${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >/dev/null 2>&1 || true
  pkill -f "port-forward svc/${MIRROR_REST_SERVICE} .*${MIRROR_REST_LOCAL_PORT}:http" >/dev/null 2>&1 || true
  pkill -f "port-forward svc/hiero-explorer.* .*${EXPLORER_LOCAL_PORT}:" >/dev/null 2>&1 || true
}

wait_for_consensus_pods_ready() {
  local timeout_secs="${1:-600}"
  local node
  local nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    log "Waiting for network-${node}-0 to become Ready"
    kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/network-${node}-0" --timeout="${timeout_secs}s"
  done
}

wait_for_haproxy_ready() {
  local timeout_secs="${1:-600}"
  local node
  local nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    log "Waiting for haproxy-${node} rollout to become ready"
    kubectl -n "${SOLO_NAMESPACE}" rollout status "deployment/haproxy-${node}" --timeout="${timeout_secs}s"
  done
}

mirror_rest_service_exists() {
  kubectl -n "${SOLO_NAMESPACE}" get svc "${MIRROR_REST_SERVICE}" >/dev/null 2>&1
}

restart_post_upgrade_port_forwards() {
  if [[ -n "${CN_PORT_FORWARD_PID}" ]]; then
    kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${MIRROR_PORT_FORWARD_PID}" ]]; then
    kill "${MIRROR_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  CN_PORT_FORWARD_PID=""
  MIRROR_PORT_FORWARD_PID=""
  cleanup_stale_port_forwards
  kill_processes_on_local_port "${CN_GRPC_LOCAL_PORT}"
  kill_processes_on_local_port "${MIRROR_REST_LOCAL_PORT}"
  kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >"${CN_PORT_FORWARD_LOG}" 2>&1 &
  CN_PORT_FORWARD_PID="$!"
  if mirror_rest_service_exists; then
    kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${MIRROR_REST_SERVICE}" "${MIRROR_REST_LOCAL_PORT}:http" >"${MIRROR_PORT_FORWARD_LOG}" 2>&1 &
    MIRROR_PORT_FORWARD_PID="$!"
  fi
  wait_for_tcp_open "127.0.0.1" "${CN_GRPC_LOCAL_PORT}" 30 1 || {
    echo "Consensus gRPC port-forward is not reachable on localhost:${CN_GRPC_LOCAL_PORT}" >&2
    return 1
  }
  if [[ -n "${MIRROR_PORT_FORWARD_PID}" ]]; then
    wait_for_tcp_open "127.0.0.1" "${MIRROR_REST_LOCAL_PORT}" 30 1 || {
      echo "Mirror REST port-forward is not reachable on localhost:${MIRROR_REST_LOCAL_PORT}" >&2
      return 1
    }
  fi
  if [[ "${DEPLOY_EXPLORER}" == "true" ]] && explorer_service_exists; then
    start_explorer_port_forward
  fi
}

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

collect_record_filenames_in_range() {
  local mirror_base="$1"
  local min_block="$2"
  local max_block="$3"
  local out_file="$4"
  local next_url="${mirror_base%/}/api/v1/blocks?order=asc&limit=100"
  local j count last_num
  : >"${out_file}"
  while [[ -n "${next_url}" ]]; do
    j="$(curl -sf "${next_url}")" || return 1
    count="$(echo "${j}" | jq '.blocks | length')"
    if [[ "${count}" == "0" || "${count}" == "null" ]]; then
      break
    fi
    echo "${j}" | jq -r --argjson min "${min_block}" --argjson max "${max_block}" '
      .blocks[]
      | select(.number >= $min and .number <= $max)
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
  ' | sed -n '1p')"
  [[ -n "${svc}" ]] || return 1
  echo "${svc}"
}

minio_discover_service_port() {
  local ns="$1"
  local svc="$2"
  local port
  port="$(kubectl -n "${ns}" get svc "${svc}" -o json 2>/dev/null | jq -r '
    first(.spec.ports[] | select((.targetPort|tostring) == "9000") | .port // empty)
  ')"
  if [[ -z "${port}" || "${port}" == "null" ]]; then
    port="$(kubectl -n "${ns}" get svc "${svc}" -o json 2>/dev/null | jq -r '.spec.ports[0].port // empty')"
  fi
  [[ -n "${port}" && "${port}" != "null" ]] || return 1
  echo "${port}"
}

download_solo_record_streams_via_pod_mc() {
  local names_file="$1"
  local svc="$2"
  local svc_port="$3"
  local pod all_objects wanted_timestamps selected_objects creds
  local endpoint selected_u="" selected_p=""
  local found=0 sig_found=0 failed=0
  local remote subpath dest

  : > "${MINIO_DOWNLOAD_LOG}"
  pod="$(kubectl -n "${MINIO_NAMESPACE}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name | select(test("^minio-"))
  ' | sed -n '1p')"
  [[ -n "${pod}" ]] || { echo "Could not find MinIO pod in namespace ${MINIO_NAMESPACE}" >&2; return 1; }

  creds="$(kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -lc \
    "cat \"\${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}\" 2>/dev/null || true" 2>/dev/null || true)"
  selected_u="$(echo "${creds}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_USER=//p' | sed -n '1p' | tr -d '"\r')"
  selected_p="$(echo "${creds}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_PASSWORD=//p' | sed -n '1p' | tr -d '"\r')"
  [[ -n "${selected_u}" && -n "${selected_p}" ]] || {
    echo "Could not discover MinIO root credentials in ${MINIO_NAMESPACE}" >&2
    return 1
  }

  endpoint="http://${svc}.${MINIO_NAMESPACE}.svc.cluster.local:${svc_port}"
  all_objects="$(mktemp)"
  if ! kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -lc \
    "mc alias set local '${endpoint}' '${selected_u}' '${selected_p}' >/dev/null 2>&1; { mc find local/${MINIO_BUCKET}/recordstreams --name '*.rcd*'; mc find local/${MINIO_BUCKET}/recordstreams --name '*.rcs_sig'; }" \
    >"${all_objects}" 2>/dev/null; then
    rm -f "${all_objects}"
    echo "Failed to list MinIO objects via in-pod mc from ${endpoint}" >&2
    return 1
  fi

  wanted_timestamps="$(mktemp)"
  selected_objects="$(mktemp)"
  awk '{
    f = $0;
    sub(/^.*\//, "", f);
    if (match(f, /Z/)) {
      print substr(f, 1, RSTART);
    }
  }' "${names_file}" | sort -u > "${wanted_timestamps}"
  awk 'NR == FNR { wanted[$1] = 1; next }
    {
      bn = $0;
      sub(/^.*\//, "", bn);
      if (match(bn, /Z/)) {
        ts = substr(bn, 1, RSTART);
        if (wanted[ts]) print $0;
      }
    }' "${wanted_timestamps}" "${all_objects}" | sort -u > "${selected_objects}"

  mkdir -p "${RECORD_STREAMS_DIR}"
  while IFS= read -r remote; do
    [[ -n "${remote}" ]] || continue
    subpath="${remote#*/"${MINIO_BUCKET}"/recordstreams/}"
    dest="${RECORD_STREAMS_DIR}/${subpath}"
    mkdir -p "$(dirname "${dest}")"
    if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -lc \
      "mc alias set local '${endpoint}' '${selected_u}' '${selected_p}' >/dev/null 2>&1; mc cat '${remote}'" >"${dest}" 2>/dev/null; then
      ((found+=1))
      [[ "${dest}" == *.rcd_sig || "${dest}" == *.rcs_sig ]] && ((sig_found+=1))
    else
      rm -f "${dest}" >/dev/null 2>&1 || true
      ((failed+=1))
    fi
  done < "${selected_objects}"

  rm -f "${all_objects}" "${wanted_timestamps}" "${selected_objects}" >/dev/null 2>&1 || true
  log "Downloaded MinIO objects: files=${found} sigFiles=${sig_found} failed=${failed}"
  (( found > 0 )) || return 1
}

download_solo_minio_record_streams_range() {
  local min_block="$1"
  local max_block="$2"
  local mirror_base="$3"
  local names_file svc svc_port

  mkdir -p "${RECORD_STREAMS_DIR}"
  names_file="$(mktemp)"
  log "Collecting record stream names from mirror for blocks ${min_block}..${max_block}"
  collect_record_filenames_in_range "${mirror_base}" "${min_block}" "${max_block}" "${names_file}" || {
    rm -f "${names_file}"
    return 1
  }
  [[ -s "${names_file}" ]] || { echo "No mirror record file names found for block range ${min_block}..${max_block}" >&2; rm -f "${names_file}"; return 1; }
  svc="$(minio_discover_service "${MINIO_NAMESPACE}")" || { rm -f "${names_file}"; return 1; }
  svc_port="$(minio_discover_service_port "${MINIO_NAMESPACE}" "${svc}")" || { rm -f "${names_file}"; return 1; }
  download_solo_record_streams_via_pod_mc "${names_file}" "${svc}" "${svc_port}"
  rm -f "${names_file}"
}

load_jumpstart_env_from_bin() {
  local jumpstart_file="$1"
  local k v parser_out
  [[ -f "${jumpstart_file}" ]] || { echo "jumpstart.bin not found: ${jumpstart_file}" >&2; return 1; }
  # Capture parser output and exit code explicitly; process substitution
  # (`done < <(...)`) does NOT propagate the inner command's exit code, so a
  # failing `node` would otherwise silently leave the JUMPSTART_* vars unset
  # and the script would fail later with a misleading "unbound variable" error.
  if ! parser_out="$(node "${JUMPSTART_PARSE_SCRIPT}" "${jumpstart_file}" 2>&1)"; then
    echo "Failed to parse jumpstart.bin (${jumpstart_file}):" >&2
    echo "${parser_out}" >&2
    echo "jumpstart.bin size: $(wc -c <"${jumpstart_file}" 2>/dev/null || echo unknown) bytes" >&2
    echo "jumpstart.bin head (hex, first 200 bytes):" >&2
    # Order matters: send od stdout to fd 2 first, THEN drop od's own stderr.
    od -An -tx1 -N 200 "${jumpstart_file}" >&2 2>/dev/null || true
    return 1
  fi
  while IFS='=' read -r k v; do
    case "${k}" in
      JUMPSTART_BLOCK_NUMBER) JUMPSTART_BLOCK_NUMBER="${v}" ;;
      JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH) JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH="${v}" ;;
      JUMPSTART_CONSENSUS_TIMESTAMP_HASH) JUMPSTART_CONSENSUS_TIMESTAMP_HASH="${v}" ;;
      JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH) JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH="${v}" ;;
      JUMPSTART_STREAMING_HASHER_LEAF_COUNT) JUMPSTART_STREAMING_HASHER_LEAF_COUNT="${v}" ;;
      JUMPSTART_STREAMING_HASHER_HASH_COUNT) JUMPSTART_STREAMING_HASHER_HASH_COUNT="${v}" ;;
      JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES) JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES="${v}" ;;
    esac
  done <<< "${parser_out}"
  export JUMPSTART_BLOCK_NUMBER
  export JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH
  export JUMPSTART_CONSENSUS_TIMESTAMP_HASH
  export JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH
  export JUMPSTART_STREAMING_HASHER_LEAF_COUNT
  export JUMPSTART_STREAMING_HASHER_HASH_COUNT
  export JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES
}

generate_block_node_metadata_from_mirror() {
  local max_block="$1"
  export MIRROR_BLOCK_NUMBER="${max_block}"
  export BLOCK_TIMES_FILE
  export DAY_BLOCKS_FILE
  export MIRROR_REST_URL
  node "${MIRROR_METADATA_SCRIPT}" >"${MIRROR_METADATA_LOG}" 2>&1 || {
    sed -n '1,200p' "${MIRROR_METADATA_LOG}" >&2 || true
    return 1
  }
}

prepare_wrap_day_archives_from_record_streams() {
  local account_dir account_id src base ts day out_dir out_file stem stem_no_ext
  local primary_records=0 other_records=0 sig_files=0 tar_count=0
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
            ((primary_records+=1))
          else
            out_file="${out_dir}/${stem_no_ext}_node_${account_id}.rcd"
            gzip -dc "${src}" > "${out_file}"
            ((other_records+=1))
          fi
          ;;
        *.rcd)
          stem_no_ext="${base%.rcd}"
          if [[ "${stem_no_ext}" == "${ts}" && "${account_id}" == "0.0.3" && ! -f "${out_dir}/${ts}.rcd" ]]; then
            cp -f "${src}" "${out_dir}/${ts}.rcd"
            ((primary_records+=1))
          else
            cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcd"
            ((other_records+=1))
          fi
          ;;
        *.rcd_sig)
          stem_no_ext="${base%.rcd_sig}"
          cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcd_sig"
          ((sig_files+=1))
          ;;
        *.rcs_sig)
          stem_no_ext="${base%.rcs_sig}"
          cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcs_sig"
          ((sig_files+=1))
          ;;
      esac
    done
  done
  shopt -u nullglob
  (( primary_records > 0 )) || { echo "No primary record files prepared for wrap input under ${WRAP_DAYS_SRC_DIR}" >&2; return 1; }
  (( sig_files > 0 )) || { echo "No signature files prepared for wrap input under ${WRAP_DAYS_SRC_DIR}" >&2; return 1; }
  (
    cd "${BLOCK_NODE_REPO_PATH}" && ./gradlew :tools:run --args="days compress -o ${WRAP_COMPRESSED_DAYS_DIR} ${WRAP_DAYS_SRC_DIR}"
  ) >"${WRAP_INPUT_PREP_LOG}" 2>&1 || {
    sed -n '1,200p' "${WRAP_INPUT_PREP_LOG}" >&2 || true
    return 1
  }
  tar_count="$(find "${WRAP_COMPRESSED_DAYS_DIR}" -type f -name '*.tar.zstd' | wc -l | tr -d ' ')"
  [[ "${tar_count}" != "0" ]] || { echo "days compress produced no .tar.zstd files under ${WRAP_COMPRESSED_DAYS_DIR}" >&2; return 1; }
}

run_block_node_wrap_tool() {
  local records_dir="$1"
  local wrapped_dir="$2"
  local wrap_args jumpstart_file
  validate_block_node_repo
  ensure_zstd_command_for_block_node
  mkdir -p "${wrapped_dir}"
  wrap_args="blocks wrap -i ${records_dir} -o ${wrapped_dir} --blocktimes-file ${BLOCK_TIMES_FILE} --day-blocks ${DAY_BLOCKS_FILE}"
  if [[ -n "${BLOCKS_WRAP_EXTRA_ARGS}" ]]; then
    wrap_args="${wrap_args} ${BLOCKS_WRAP_EXTRA_ARGS}"
  fi
  (
    cd "${BLOCK_NODE_REPO_PATH}" && ./gradlew :tools:run --args="${wrap_args}"
  ) >"${BLOCK_NODE_WRAP_LOG}" 2>&1 || {
    sed -n '1,220p' "${BLOCK_NODE_WRAP_LOG}" >&2 || true
    return 1
  }
  jumpstart_file="$(find "${wrapped_dir}" -type f -name "jumpstart.bin" | sed -n '1p')"
  [[ -n "${jumpstart_file}" && -f "${jumpstart_file}" ]] || { echo "jumpstart.bin not found under ${wrapped_dir}" >&2; return 1; }
  echo "${jumpstart_file}"
}

current_mirror_latest_block() {
  local mirror_base="$1"
  curl -sf "${mirror_base%/}/api/v1/blocks?order=desc&limit=1" | jq -r '.blocks[0].number'
}

normalize_hash_list() {
  local input="$1"
  echo "${input}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]' | sed 's/^,//; s/,$//; s/,,*/,/g'
}

create_temp_upgrade_properties() {
  cp "${BASE_074_APP_PROPS_FILE}" "${TMP_UPGRADE_APP_PROPS}"
  {
    echo ""
    echo "# Added by solo-wrb-jumpstart.sh"
    echo "blockStream.jumpstart.blockNum=${JUMPSTART_BLOCK_NUMBER}"
    echo "blockStream.jumpstart.previousWrappedRecordBlockHash=${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}"
    echo "blockStream.jumpstart.consensusTimestampHash=${JUMPSTART_CONSENSUS_TIMESTAMP_HASH}"
    echo "blockStream.jumpstart.outputItemsTreeRootHash=${JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH}"
    echo "blockStream.jumpstart.streamingHasherLeafCount=${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}"
    echo "blockStream.jumpstart.streamingHasherHashCount=${JUMPSTART_STREAMING_HASHER_HASH_COUNT}"
    echo "blockStream.jumpstart.streamingHasherSubtreeHashes=${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES}"
    echo "state.saveStatePeriod=30"
  } >> "${TMP_UPGRADE_APP_PROPS}"
  log "Created temp upgrade properties: ${TMP_UPGRADE_APP_PROPS}"
}

parse_migration_vote_from_hgcaa() {
  local node pod line="" queued_line="" vote_pod=""
  local attempt=1 max_attempts=36
  local nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  while (( attempt <= max_attempts )); do
    for node in "${nodes[@]}"; do
      pod="network-${node}-0"
      line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
        "awk '/Finalized migration root hash vote values:/{last=\$0} END{if (last) print last}' /opt/hgcapp/services-hedera/HapiApp2.0/output/hgcaa.log 2>/dev/null" || true)"
      if [[ -n "${line}" ]]; then
        vote_pod="${pod}"
        break
      fi
    done
    if [[ -n "${line}" ]]; then
      break
    fi
    sleep 5
    ((attempt++))
  done

  [[ -n "${line}" ]] || {
    echo "Could not find migration vote finalization log line in hgcaa.log within $((max_attempts * 5))s" >&2
    return 1
  }

  if [[ "${line}" =~ Block[[:space:]]+([0-9]+)[[:space:]]+previousWrappedRecordBlockRootHash=([0-9a-fA-F]+),[[:space:]]*wrappedIntermediatePreviousBlockRootHashes=\[([^]]*)\],[[:space:]]*wrappedIntermediateBlockRootsLeafCount=([0-9]+) ]]; then
    MIGRATION_BLOCK_NUMBER="${BASH_REMATCH[1]}"
    MIGRATION_PREV_HASH="${BASH_REMATCH[2]}"
    MIGRATION_INTERMEDIATE_HASHES="${BASH_REMATCH[3]}"
    MIGRATION_LEAF_COUNT="${BASH_REMATCH[4]}"
  elif [[ "${line}" =~ previousWrappedRecordBlockRootHash=([0-9a-fA-F]+),[[:space:]]*wrappedIntermediatePreviousBlockRootHashes=\[([^]]*)\],[[:space:]]*wrappedIntermediateBlockRootsLeafCount=([0-9]+) ]]; then
    MIGRATION_PREV_HASH="${BASH_REMATCH[1]}"
    MIGRATION_INTERMEDIATE_HASHES="${BASH_REMATCH[2]}"
    MIGRATION_LEAF_COUNT="${BASH_REMATCH[3]}"
    queued_line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${vote_pod}" -c root-container -- sh -lc \
      "awk '/Applied queued hash for block[0-9]+:/{last=\$0} END{if (last) print last}' /opt/hgcapp/services-hedera/HapiApp2.0/output/hgcaa.log 2>/dev/null" || true)"
    if [[ "${queued_line}" =~ block([0-9]+): ]]; then
      MIGRATION_BLOCK_NUMBER="${BASH_REMATCH[1]}"
      log "Migration finalization log omitted Block N; inferred migration block from queued hash logs on ${vote_pod}: ${MIGRATION_BLOCK_NUMBER}"
    else
      MIGRATION_BLOCK_NUMBER="${JUMPSTART_BLOCK_NUMBER}"
      log "Migration finalization log omitted Block N and no queued-hash block log found; falling back to jumpstart block ${MIGRATION_BLOCK_NUMBER}"
    fi
  else
    echo "Migration vote line did not match expected format: ${line}" >&2
    return 1
  fi
  MIGRATION_PREV_HASH="$(echo "${MIGRATION_PREV_HASH}" | tr '[:upper:]' '[:lower:]')"
  MIGRATION_INTERMEDIATE_HASHES="$(normalize_hash_list "${MIGRATION_INTERMEDIATE_HASHES}")"
  log "Parsed migration vote values: block=${MIGRATION_BLOCK_NUMBER}, leafCount=${MIGRATION_LEAF_COUNT}"
}

wait_for_mirror_block_at_least() {
  local target="$1"
  local timeout_secs="${2:-600}"
  local sleep_secs=2
  local elapsed=0
  local latest=""
  while (( elapsed < timeout_secs )); do
    latest="$(current_mirror_latest_block "${MIRROR_REST_URL}" 2>/dev/null || true)"
    if [[ "${latest}" =~ ^[0-9]+$ ]] && (( latest >= target )); then
      log "Mirror reports block ${latest} (>= ${target})"
      return 0
    fi
    sleep "${sleep_secs}"
    elapsed=$((elapsed + sleep_secs))
  done
  echo "Mirror did not reach block ${target} within ${timeout_secs}s (last seen: ${latest:-none})" >&2
  return 1
}

# Latest signed state on a consensus-node pod lives under:
#   /opt/hgcapp/services-hedera/HapiApp2.0/data/saved/com.hedera.services.ServicesMain/<nodeId>/<swirld>/<round>/
# Round numbers run far ahead of block numbers (e.g. round ~7500 while block ~260),
# so we capture the most recent round directory rather than gating on a round that
# matches a block number. The follow-up state inspector reads BlockInfo from the
# captured state to recover the actual block number/hash.
resolve_latest_saved_state_on_pod() {
  local pod="$1"
  # shellcheck disable=SC2016  # $-expansions are intentional: they run in the remote pod shell, not locally
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc '
    base="/opt/hgcapp/services-hedera/HapiApp2.0/data/saved/com.hedera.services.ServicesMain"
    [ -d "$base" ] || exit 0
    for nodedir in "$base"/*/; do
      [ -d "$nodedir" ] || continue
      for swirlddir in "$nodedir"*/; do
        [ -d "$swirlddir" ] || continue
        r=$(ls -1 "$swirlddir" 2>/dev/null | grep -E "^[0-9]+$" | sort -n | tail -1)
        if [ -n "$r" ]; then
          printf "%s %s\n" "${swirlddir%/}" "$r"
          exit 0
        fi
      done
    done
  ' | tr -d '\r'
}

wait_for_saved_state() {
  local pod="network-node1-0"
  local timeout_secs="${1:-300}"
  local elapsed=0
  local sleep_secs=5
  local info=""
  while (( elapsed < timeout_secs )); do
    info="$(resolve_latest_saved_state_on_pod "${pod}")"
    if [[ -n "${info}" ]]; then
      SAVED_STATE_REMOTE_DIR="${info% *}"
      SAVED_STATE_ROUND="${info##* }"
      log "Detected latest saved state round ${SAVED_STATE_ROUND} on ${pod} (${SAVED_STATE_REMOTE_DIR}/${SAVED_STATE_ROUND})"
      return 0
    fi
    sleep "${sleep_secs}"
    elapsed=$((elapsed + sleep_secs))
  done
  echo "No saved state directory found on ${pod} within ${timeout_secs}s" >&2
  return 1
}

copy_latest_saved_state_locally() {
  local pod="network-node1-0"
  [[ -n "${SAVED_STATE_REMOTE_DIR}" && -n "${SAVED_STATE_ROUND}" ]] || {
    echo "Saved state location not resolved (dir='${SAVED_STATE_REMOTE_DIR}', round='${SAVED_STATE_ROUND}')" >&2
    return 1
  }
  rm -rf "${SAVED_STATE_DIR}" >/dev/null 2>&1 || true
  mkdir -p "${SAVED_STATE_DIR}"
  log "Copying saved state round ${SAVED_STATE_ROUND} from ${pod}:${SAVED_STATE_REMOTE_DIR}/${SAVED_STATE_ROUND} to ${SAVED_STATE_DIR}/"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- \
    sh -lc "cd '${SAVED_STATE_REMOTE_DIR}' && tar -cf - '${SAVED_STATE_ROUND}'" \
    | tar -xf - -C "${SAVED_STATE_DIR}"
  [[ -d "${SAVED_STATE_DIR}/${SAVED_STATE_ROUND}" ]] || { echo "Saved state directory was not copied to ${SAVED_STATE_DIR}/${SAVED_STATE_ROUND}" >&2; return 1; }
  log "Saved state copied to: ${SAVED_STATE_DIR}/${SAVED_STATE_ROUND}"
}

compare_replay_to_migration_vote() {
  local replay_prev replay_leaf replay_hashes
  local mismatch=0
  replay_prev="$(echo "${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}" | tr '[:upper:]' '[:lower:]')"
  replay_leaf="${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}"
  replay_hashes="$(normalize_hash_list "${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES}")"
  {
    echo "migration.block=${MIGRATION_BLOCK_NUMBER}"
    echo "migration.prevHash=${MIGRATION_PREV_HASH}"
    echo "migration.intermediateHashes=${MIGRATION_INTERMEDIATE_HASHES}"
    echo "migration.leafCount=${MIGRATION_LEAF_COUNT}"
    echo "replay.block=${JUMPSTART_BLOCK_NUMBER}"
    echo "replay.prevHash=${replay_prev}"
    echo "replay.intermediateHashes=${replay_hashes}"
    echo "replay.leafCount=${replay_leaf}"
  } > "${MIGRATION_COMPARE_LOG}"
  log "--------------------------------------------------------------------"
  log "Migration vs Replay Comparison"
  log "  blockNumber:"
  log "    migration = ${MIGRATION_BLOCK_NUMBER}"
  log "    replay    = ${JUMPSTART_BLOCK_NUMBER}"
  log "  previousWrappedRecordBlockRootHash:"
  log "    migration = ${MIGRATION_PREV_HASH}"
  log "    replay    = ${replay_prev}"
  log "  wrappedIntermediateBlockRootsLeafCount:"
  log "    migration = ${MIGRATION_LEAF_COUNT}"
  log "    replay    = ${replay_leaf}"
  log "  wrappedIntermediatePreviousBlockRootHashes:"
  log "    migration = [${MIGRATION_INTERMEDIATE_HASHES}]"
  log "    replay    = [${replay_hashes}]"

  if [[ "${MIGRATION_PREV_HASH}" != "${replay_prev}" ]]; then
    mismatch=1
    log "  mismatch: previousWrappedRecordBlockRootHash differs"
  fi
  if [[ "${MIGRATION_INTERMEDIATE_HASHES}" != "${replay_hashes}" ]]; then
    mismatch=1
    log "  mismatch: wrappedIntermediatePreviousBlockRootHashes differ"
  fi
  if [[ "${MIGRATION_LEAF_COUNT}" != "${replay_leaf}" ]]; then
    mismatch=1
    log "  mismatch: wrappedIntermediateBlockRootsLeafCount differs"
  fi

  if (( mismatch == 0 )); then
    log "  result: MATCH"
  else
    log "  result: MISMATCH"
  fi
  log "--------------------------------------------------------------------"

  (( mismatch == 0 ))
}

run_initial_offline_wrap_from_genesis() {
  local mirror_base="$1"
  local latest_block
  latest_block="$(current_mirror_latest_block "${mirror_base}")"
  [[ "${latest_block}" =~ ^[0-9]+$ ]] || { echo "Unable to determine latest mirror block number: ${latest_block}" >&2; return 1; }
  rm -rf "${RECORD_STREAMS_DIR}" "${FIRST_WRAP_DIR}" >/dev/null 2>&1 || true
  mkdir -p "${RECORD_STREAMS_DIR}" "${FIRST_WRAP_DIR}"
  download_solo_minio_record_streams_range 0 "${latest_block}" "${mirror_base}"
  generate_block_node_metadata_from_mirror "${latest_block}"
  prepare_wrap_day_archives_from_record_streams
  FIRST_JUMPSTART_BIN="$(run_block_node_wrap_tool "${WRAP_COMPRESSED_DAYS_DIR}" "${FIRST_WRAP_DIR}")"
  load_jumpstart_env_from_bin "${FIRST_JUMPSTART_BIN}"
  FIRST_WRAP_BLOCK_NUMBER="${JUMPSTART_BLOCK_NUMBER}"
  log "Initial wrap complete: blockNum=${FIRST_WRAP_BLOCK_NUMBER}, jumpstart=${FIRST_JUMPSTART_BIN}"
}

run_replay_wrap_to_migration_block() {
  local mirror_base="$1"
  local from_block="$2"
  local to_block="$3"
  rm -rf "${RECORD_STREAMS_DIR}" "${SECOND_WRAP_DIR}" >/dev/null 2>&1 || true
  mkdir -p "${RECORD_STREAMS_DIR}" "${SECOND_WRAP_DIR}"
  download_solo_minio_record_streams_range "${from_block}" "${to_block}" "${mirror_base}"
  generate_block_node_metadata_from_mirror "${to_block}"
  prepare_wrap_day_archives_from_record_streams
  SECOND_JUMPSTART_BIN="$(run_block_node_wrap_tool "${WRAP_COMPRESSED_DAYS_DIR}" "${SECOND_WRAP_DIR}")"
  load_jumpstart_env_from_bin "${SECOND_JUMPSTART_BIN}"
}

run_network_upgrade() {
  solo consensus network upgrade \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --upgrade-version "${UPGRADE_TAG}" \
    --application-properties "${TMP_UPGRADE_APP_PROPS}" \
    --quiet-mode \
    --force
}

ensure_mirror_node() {
  if mirror_rest_service_exists; then
    log "Mirror REST service already exists (${MIRROR_REST_SERVICE})"
    return 0
  fi
  log "Deploying mirror node"
  solo mirror node add --deployment "${SOLO_DEPLOYMENT}" --enable-ingress --pinger
  for _ in $(seq 1 60); do
    mirror_rest_service_exists && return 0
    sleep 5
  done
  echo "Mirror REST service ${MIRROR_REST_SERVICE} not found after mirror deployment" >&2
  return 1
}

explorer_service_info() {
  # Prints "<service-name> <service-port>" for the deployed explorer, or nothing.
  kubectl -n "${SOLO_NAMESPACE}" get svc -o json 2>/dev/null | jq -r '
    .items[]
    | select(.metadata.name | test("explorer"))
    | select(.metadata.name | test("ingress"; "i") | not)
    | "\(.metadata.name) \(.spec.ports[0].port)"
  ' | sed -n '1p'
}

explorer_service_exists() {
  [[ -n "$(explorer_service_info)" ]]
}

start_explorer_port_forward() {
  local info name port
  info="$(explorer_service_info)"
  [[ -n "${info}" ]] || { log "Explorer service not found; skipping explorer port-forward"; return 0; }
  name="${info%% *}"
  port="${info##* }"
  if [[ -n "${EXPLORER_PORT_FORWARD_PID}" ]]; then
    kill "${EXPLORER_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  EXPLORER_PORT_FORWARD_PID=""
  kill_processes_on_local_port "${EXPLORER_LOCAL_PORT}"
  kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${name}" "${EXPLORER_LOCAL_PORT}:${port}" >"${EXPLORER_PORT_FORWARD_LOG}" 2>&1 &
  EXPLORER_PORT_FORWARD_PID="$!"
  if wait_for_tcp_open "127.0.0.1" "${EXPLORER_LOCAL_PORT}" 30 1; then
    EXPLORER_URL="http://127.0.0.1:${EXPLORER_LOCAL_PORT}"
    log "Explorer UI available at ${EXPLORER_URL}"
  else
    log "Explorer port-forward not reachable on localhost:${EXPLORER_LOCAL_PORT} (continuing)"
  fi
}

# Deploy the Hiero Explorer web UI so blocks/transactions can be inspected from a
# browser. Non-fatal: a failed explorer deploy/port-forward must not fail the test.
ensure_explorer_node() {
  [[ "${DEPLOY_EXPLORER}" == "true" ]] || { log "DEPLOY_EXPLORER!=true; skipping explorer deployment"; return 0; }
  if explorer_service_exists; then
    log "Explorer service already exists"
  else
    log "Deploying Hiero Explorer"
    if ! solo explorer node add \
      --deployment "${SOLO_DEPLOYMENT}" \
      --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
      --enable-ingress \
      --force-port-forward false \
      --quiet-mode; then
      log "Explorer deployment failed; continuing without explorer"
      return 0
    fi
    for _ in $(seq 1 60); do
      explorer_service_exists && break
      sleep 5
    done
  fi
  start_explorer_port_forward
}

configure_mirror_rest_endpoint() {
  if [[ -n "${MIRROR_REST_URL}" ]]; then
    MIRROR_REST_URL="${MIRROR_REST_URL%/}"
    log "Using provided mirror REST endpoint: ${MIRROR_REST_URL}"
    return 0
  fi

  if ! mirror_rest_service_exists; then
    ensure_mirror_node
  fi

  restart_post_upgrade_port_forwards
  MIRROR_REST_URL="http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
  # Gate on the blocks endpoint (what this script actually depends on). The
  # /api/v1/network/nodes endpoint returns 404 on current mirror chart versions
  # until an address book is ingested, so it is not a reliable readiness signal.
  wait_for_http_ok "${MIRROR_REST_URL}/api/v1/blocks?limit=1" 60 5 || {
    echo "Mirror REST endpoint did not become healthy at ${MIRROR_REST_URL}" >&2
    return 1
  }
}

log "Validating prerequisites"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd curl
require_cmd jq
require_cmd awk
require_cmd node
require_cmd java
validate_block_node_repo
[[ -f "${LOG4J2_XML_PATH}" ]] || { echo "log4j2 config not found: ${LOG4J2_XML_PATH}" >&2; exit 1; }
[[ -f "${DEPLOY_APP_PROPS_FILE}" ]] || { echo "Deploy application.properties not found: ${DEPLOY_APP_PROPS_FILE}" >&2; exit 1; }
[[ -f "${BASE_074_APP_PROPS_FILE}" ]] || { echo "Base 0.74 application.properties not found: ${BASE_074_APP_PROPS_FILE}" >&2; exit 1; }
[[ -n "${UPGRADE_TAG}" ]] || {
  echo "UPGRADE_TAG is empty; set UPGRADE_TAG to the consensus-node release tag to upgrade to" >&2
  exit 1
}
log "Will upgrade network to release tag ${UPGRADE_TAG}"

log "Cleaning previous local artifacts"
rm -rf "${RECORD_STREAMS_DIR}" "${WRAPPED_BLOCKS_DIR}" "${WORK_DIR}" >/dev/null 2>&1 || true
mkdir -p "${GENERATED_DIR}" "${WORK_DIR}" >/dev/null 2>&1 || true
cleanup_stale_port_forwards

log "Resetting kind cluster ${SOLO_CLUSTER_NAME}"
kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
kind create cluster -n "${SOLO_CLUSTER_NAME}"

log "Configuring solo deployment ${SOLO_DEPLOYMENT} for ${CONSENSUS_NODE_COUNT} node(s)"
solo cluster-ref config connect --cluster-ref "kind-${SOLO_CLUSTER_NAME}" --context "kind-${SOLO_CLUSTER_NAME}"
solo deployment config delete --deployment "${SOLO_DEPLOYMENT}" --quiet-mode >/dev/null 2>&1 || true
solo deployment config create -n "${SOLO_NAMESPACE}" --deployment "${SOLO_DEPLOYMENT}"
solo deployment cluster attach --deployment "${SOLO_DEPLOYMENT}" --cluster-ref "kind-${SOLO_CLUSTER_NAME}" --num-consensus-nodes "${CONSENSUS_NODE_COUNT}"
solo cluster-ref config setup -s "${SOLO_CLUSTER_SETUP_NAMESPACE}" --prometheus-stack true

log "Deploying consensus network with release tag ${INITIAL_RELEASE_TAG}"
solo keys consensus generate --gossip-keys --tls-keys --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
solo consensus network deploy \
  --deployment "${SOLO_DEPLOYMENT}" \
  -i "${NODE_ALIASES}" \
  --application-properties "${DEPLOY_APP_PROPS_FILE}" \
  --log4j2-xml "${LOG4J2_XML_PATH}" \
  --service-monitor true \
  --pod-log true \
  --pvcs true \
  --release-tag "${INITIAL_RELEASE_TAG}"
solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --release-tag "${INITIAL_RELEASE_TAG}"
solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
wait_for_consensus_pods_ready 600
wait_for_haproxy_ready 600

configure_mirror_rest_endpoint

ensure_explorer_node

log "Waiting 30 seconds before offline wrap tooling"
sleep 30

log "Running offline wrap from genesis records"
run_initial_offline_wrap_from_genesis "${MIRROR_REST_URL}"
create_temp_upgrade_properties

log "Upgrading network to release tag ${UPGRADE_TAG} using temp application.properties"
run_network_upgrade
wait_for_consensus_pods_ready 600
wait_for_haproxy_ready 600
restart_post_upgrade_port_forwards

log "Parsing migration vote values from hgcaa logs"
parse_migration_vote_from_hgcaa
log "Replaying offline wrap from block 0 through migration block ${MIGRATION_BLOCK_NUMBER}"
run_replay_wrap_to_migration_block "${MIRROR_REST_URL}" 0 "${MIGRATION_BLOCK_NUMBER}"
[[ "${JUMPSTART_BLOCK_NUMBER}" == "${MIGRATION_BLOCK_NUMBER}" ]] || {
  echo "Replay jumpstart block number (${JUMPSTART_BLOCK_NUMBER}) did not match migration block (${MIGRATION_BLOCK_NUMBER})" >&2
  exit 1
}

compare_replay_to_migration_vote

log "SUCCESS: migration vote values match offline replay jumpstart values"

TARGET_BLOCK_AFTER_JUMPSTART=$((MIGRATION_BLOCK_NUMBER + BLOCKS_AFTER_JUMPSTART))
log "Waiting for mirror to reach block ${TARGET_BLOCK_AFTER_JUMPSTART} (${BLOCKS_AFTER_JUMPSTART} blocks after migration block ${MIGRATION_BLOCK_NUMBER})"
wait_for_mirror_block_at_least "${TARGET_BLOCK_AFTER_JUMPSTART}" 600

log "Waiting for a saved state on network-node1-0"
wait_for_saved_state 300

log "Extracting latest saved state from network-node1-0"
copy_latest_saved_state_locally

log "Generated artifacts root: ${GENERATED_DIR}"
log "Temp upgrade properties: ${TMP_UPGRADE_APP_PROPS}"
log "Initial jumpstart: ${FIRST_JUMPSTART_BIN}"
log "Replay jumpstart: ${SECOND_JUMPSTART_BIN}"
log "Comparison log: ${MIGRATION_COMPARE_LOG}"
log "Saved state copy: ${SAVED_STATE_DIR}/${SAVED_STATE_ROUND}"
if [[ -n "${EXPLORER_URL}" ]]; then
  log "Explorer UI: ${EXPLORER_URL} (browse blocks and transactions)"
fi
log "TODO (follow-up): run a state inspector on the saved-state copy to read BlockInfo,"
log "  then re-run blocks wrap through BlockInfo.lastBlockNumber and assert the resulting"
log "  jumpstart.bin previousWrappedRecordBlockHash equals BlockInfo.lastBlockHash."
