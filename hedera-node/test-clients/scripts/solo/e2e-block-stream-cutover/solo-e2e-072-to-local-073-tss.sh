#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail
set +m

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../../" && pwd)"

SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-solo-tss-upgrade}"
SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-tss-upgrade}"
SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-setup}"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

UPGRADE_072_RELEASE_TAG="${UPGRADE_072_RELEASE_TAG:-v0.72.0-rc.2}"
UPGRADE_073_VERSION="${UPGRADE_073_VERSION:-v0.73.0-rc.1}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
WRAPS_KEY_PATH="${WRAPS_KEY_PATH:-${HOME}/.solo/cache/wraps-v0.2.0}"
WRAPS_ARTIFACTS_DOWNLOAD_URL="${WRAPS_ARTIFACTS_DOWNLOAD_URL:-https://builds.hedera.com/tss/hiero/wraps/v0.2/wraps-v0.2.0.tar.gz}"
WRAPS_REQUIRED_FILE_COUNT="${WRAPS_REQUIRED_FILE_COUNT:-4}"

APP_PROPS_072_FILE="${SCRIPT_DIR}/resources/0.72/application.properties"
APP_PROPS_073_FILE="${SCRIPT_DIR}/resources/0.73/application.properties"
APP_ENV_073_FILE="${SCRIPT_DIR}/resources/0.73/application.env"
LOG4J2_XML_PATH="${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml"
HAPI_PATH="/opt/hgcapp/services-hedera/HapiApp2.0"
WRAPS_ARTIFACTS_CONTAINER_DIR_DEFAULT="${HAPI_PATH}/keys/wraps"

SOLO_HOME_DIR="${HOME}/.solo"
CLUSTER_CREATED_THIS_RUN="false"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command not found: $1" >&2
    exit 1
  }
}

cleanup_solo_state() {
  rm -rf "${SOLO_HOME_DIR}/cache" >/dev/null 2>&1 || true
  rm -rf "${SOLO_HOME_DIR}/logs" >/dev/null 2>&1 || true
  rm -f "${SOLO_HOME_DIR}/local-config.yaml" >/dev/null 2>&1 || true
}

cleanup() {
  local ec=$?
  if [[ "${KEEP_NETWORK}" != "true" && "${CLUSTER_CREATED_THIS_RUN}" == "true" ]]; then
    kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
    cleanup_solo_state
  fi
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
  local node=""
  local nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    log "Waiting for network-${node}-0 to become Ready"
    kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/network-${node}-0" --timeout="${timeout_secs}s"
  done
}

wait_for_haproxy_ready() {
  local timeout_secs="${1:-600}"
  local node=""
  local nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    log "Waiting for haproxy-${node} rollout to become ready"
    kubectl -n "${SOLO_NAMESPACE}" rollout status "deployment/haproxy-${node}" --timeout="${timeout_secs}s"
  done
}

ensure_wraps_artifacts_downloaded() {
  local file_count=""
  local tmp_dir=""
  local archive_path=""
  local extract_dir=""
  local extracted_root=""
  local extracted_dirs=""
  local extracted_entries=""

  if [[ -d "${WRAPS_KEY_PATH}" ]]; then
    file_count="$(find "${WRAPS_KEY_PATH}" -maxdepth 1 -type f | wc -l | tr -d ' ')"
    if [[ "${file_count}" -ge "${WRAPS_REQUIRED_FILE_COUNT}" ]]; then
      log "Using cached WRAPS artifacts from ${WRAPS_KEY_PATH}"
      return 0
    fi
  fi

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/solo-wraps-download.XXXXXX")"
  archive_path="${tmp_dir}/wraps-v0.2.0.tar.gz"
  extract_dir="${tmp_dir}/extract"
  mkdir -p "${extract_dir}"

  log "Downloading WRAPS artifacts from ${WRAPS_ARTIFACTS_DOWNLOAD_URL}"
  curl -fL "${WRAPS_ARTIFACTS_DOWNLOAD_URL}" -o "${archive_path}"
  tar -xzf "${archive_path}" -C "${extract_dir}"

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

configured_wraps_artifacts_container_dir() {
  local configured=""

  configured="$(sed -n 's/^TSS_LIB_WRAPS_ARTIFACTS_PATH=//p' "${APP_ENV_073_FILE}" | head -n 1)"
  if [[ -n "${configured}" ]]; then
    printf '%s\n' "${configured}"
  else
    printf '%s\n' "${WRAPS_ARTIFACTS_CONTAINER_DIR_DEFAULT}"
  fi
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

wraps_failure_present_in_log() {
  local pod="$1"

  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "grep -Eq 'WRAPS library is not ready|Skipping publication of POST_AGGREGATION output: WRAPS library is not ready' ${HAPI_PATH}/output/hgcaa.log" >/dev/null 2>&1
}

verify_wraps_on_consensus_nodes() {
  local wraps_dir=""
  local expected_wraps=""
  local timeout_secs="${1:-180}"
  local deadline=0
  local node=""
  local pod=""
  local found_env=""
  local found_wraps=""
  local nodes=()

  wraps_dir="$(configured_wraps_artifacts_container_dir)"
  expected_wraps="$(find "${WRAPS_KEY_PATH}" -maxdepth 1 -type f | wc -l | tr -d ' ')"
  [[ "${expected_wraps}" -ge "${WRAPS_REQUIRED_FILE_COUNT}" ]] || {
    echo "Expected at least ${WRAPS_REQUIRED_FILE_COUNT} WRAPS artifacts in ${WRAPS_KEY_PATH}, found ${expected_wraps}" >&2
    return 1
  }

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    found_env="$(consensus_pod_wraps_env "${pod}" || true)"
    found_wraps="$(consensus_pod_wraps_file_count "${pod}" "${wraps_dir}" || true)"
    log "Verifying WRAPS runtime on ${pod} (expected env ${wraps_dir}, found ${found_env:-unset}; expected ${expected_wraps} files, found ${found_wraps:-0})"
    [[ "${found_env}" == "${wraps_dir}" ]] || return 1
    [[ "${found_wraps}" == "${expected_wraps}" ]] || return 1

    log "Waiting for WRAPS proof construction in ${pod} hgcaa.log"
    deadline=$((SECONDS + timeout_secs))
    while (( SECONDS < deadline )); do
      if wraps_failure_present_in_log "${pod}"; then
        echo "WRAPS reported a runtime failure in ${pod}" >&2
        return 1
      fi
      if wraps_proof_present_in_log "${pod}"; then
        break
      fi
      sleep 5
    done

    if ! wraps_proof_present_in_log "${pod}"; then
      echo "Timed out waiting for WRAPS proof construction in ${pod}" >&2
      return 1
    fi
  done
}

create_cluster() {
  log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  cleanup_solo_state

  log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
  kind create cluster -n "${SOLO_CLUSTER_NAME}"
  CLUSTER_CREATED_THIS_RUN="true"
}

configure_solo() {
  local consensus_node_count=""
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
  log "Installing Solo cluster prerequisites"
  solo cluster-ref config setup \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --cluster-setup-namespace "${SOLO_CLUSTER_SETUP_NAMESPACE}" \
    --minio true \
    --prometheus-stack false \
    --quiet-mode
}

deploy_baseline_072() {
  log "Deploying baseline ${UPGRADE_072_RELEASE_TAG} consensus network"

  solo keys consensus generate \
    --gossip-keys \
    --tls-keys \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}"

  solo consensus network deploy \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --application-properties "${APP_PROPS_072_FILE}" \
    --log4j2-xml "${LOG4J2_XML_PATH}" \
    --pvcs true \
    --release-tag "${UPGRADE_072_RELEASE_TAG}"

  solo consensus node setup \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --release-tag "${UPGRADE_072_RELEASE_TAG}"

  solo consensus node start \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --force-port-forward false

  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
}

upgrade_to_local_073() {
  log "Upgrading consensus network to local build ${UPGRADE_073_VERSION}"
  ensure_wraps_artifacts_downloaded

  solo consensus network upgrade \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --upgrade-version "${UPGRADE_073_VERSION}" \
    --local-build-path "${LOCAL_BUILD_PATH}" \
    --application-properties "${APP_PROPS_073_FILE}" \
    --application-env "${APP_ENV_073_FILE}" \
    --wraps-key-path "${WRAPS_KEY_PATH}" \
    --force

  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  verify_wraps_on_consensus_nodes 180
}

log "Validating prerequisites"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd curl
require_cmd tar

[[ -f "${APP_PROPS_072_FILE}" ]] || { echo "Missing file: ${APP_PROPS_072_FILE}" >&2; exit 1; }
[[ -f "${APP_PROPS_073_FILE}" ]] || { echo "Missing file: ${APP_PROPS_073_FILE}" >&2; exit 1; }
[[ -f "${APP_ENV_073_FILE}" ]] || { echo "Missing file: ${APP_ENV_073_FILE}" >&2; exit 1; }
[[ -f "${LOG4J2_XML_PATH}" ]] || { echo "Missing file: ${LOG4J2_XML_PATH}" >&2; exit 1; }

validate_local_build_path "${LOCAL_BUILD_PATH}" || {
  echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}" >&2
  echo "Expected ${LOCAL_BUILD_PATH}/apps/HederaNode.jar and ${LOCAL_BUILD_PATH}/lib" >&2
  exit 1
}

create_cluster
configure_solo
setup_cluster_prereqs
deploy_baseline_072
upgrade_to_local_073

log "PASS: 0.72 -> local 0.73 upgrade completed"
