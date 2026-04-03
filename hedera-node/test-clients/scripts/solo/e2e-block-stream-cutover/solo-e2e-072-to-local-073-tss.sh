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
CONSENSUS_NODE_COUNT="$(awk -F',' '{print NF}' <<< "${NODE_ALIASES}")"
KEEP_NETWORK="${KEEP_NETWORK:-true}"
CLEAN_SOLO_STATE="${CLEAN_SOLO_STATE:-true}"
WRAPS_KEY_PATH="${WRAPS_KEY_PATH:-${HOME}/.solo/cache/wraps-v0.2.0}"

UPGRADE_072_RELEASE_TAG="${UPGRADE_072_RELEASE_TAG:-v0.72.0-rc.2}"
UPGRADE_073_RELEASE_TAG="${UPGRADE_073_RELEASE_TAG:-v0.73.0-rc.1}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
APP_PROPS_072_FILE="${SCRIPT_DIR}/resources/0.72/application.properties"
APP_PROPS_073_FILE="${SCRIPT_DIR}/resources/0.73/application.properties"
APP_ENV_073_FILE="${SCRIPT_DIR}/resources/0.73/application.env"
LOG4J2_XML_PATH="${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml"

SOLO_HOME_DIR="${HOME}/.solo"
CLUSTER_CREATED_THIS_RUN="false"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

cleanup_solo_state() {
  if [[ "${CLEAN_SOLO_STATE}" != "true" ]]; then
    return 0
  fi

  log "Cleaning Solo state under ${SOLO_HOME_DIR}"
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

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command not found: $1" >&2
    exit 1
  }
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

namespace_exists() {
  local namespace="$1"
  kubectl get namespace "${namespace}" >/dev/null 2>&1
}

wait_for_namespace_deleted() {
  local namespace="$1"
  local timeout_secs="${2:-300}"
  local deadline=$((SECONDS + timeout_secs))

  while (( SECONDS < deadline )); do
    if ! namespace_exists "${namespace}"; then
      return 0
    fi
    sleep 2
  done

  return 1
}

delete_namespace_if_exists() {
  local namespace="$1"

  if ! namespace_exists "${namespace}"; then
    return 0
  fi

  log "Deleting namespace ${namespace} for a clean baseline rerun"
  kubectl delete namespace "${namespace}" --wait=false >/dev/null 2>&1 || true
  wait_for_namespace_deleted "${namespace}" 300
}

solo_remote_config_exists() {
  kubectl -n "${SOLO_NAMESPACE}" get configmap solo-remote-config >/dev/null 2>&1
}

local_config_file() {
  printf '%s\n' "${SOLO_HOME_DIR}/local-config.yaml"
}

local_config_has_cluster_ref() {
  local config_file=""
  config_file="$(local_config_file)"
  [[ -f "${config_file}" ]] || return 1
  grep -Eq "^[[:space:]]*kind-${SOLO_CLUSTER_NAME}:[[:space:]]+kind-${SOLO_CLUSTER_NAME}$" "${config_file}"
}

local_config_has_deployment() {
  local config_file=""
  config_file="$(local_config_file)"
  [[ -f "${config_file}" ]] || return 1
  grep -Eq "^[[:space:]]*name:[[:space:]]+${SOLO_DEPLOYMENT}$" "${config_file}"
}

local_config_deployment_has_cluster_ref() {
  local config_file=""
  config_file="$(local_config_file)"
  [[ -f "${config_file}" ]] || return 1
  awk -v deployment="${SOLO_DEPLOYMENT}" -v cluster_ref="kind-${SOLO_CLUSTER_NAME}" '
    $1 == "-" && $2 == "clusters:" {
      in_block = 1
      seen_cluster = 0
      next
    }
    in_block && $1 == "-" && $2 == cluster_ref {
      seen_cluster = 1
      next
    }
    in_block && $1 == "name:" {
      if ($2 == deployment && seen_cluster) {
        found = 1
        exit
      }
      in_block = 0
      seen_cluster = 0
    }
    END {
      exit(found ? 0 : 1)
    }
  ' "${config_file}"
}

reset_local_solo_config_if_remote_config_missing() {
  local config_file=""
  config_file="$(local_config_file)"

  if [[ -f "${config_file}" ]] && ! solo_remote_config_exists; then
    log "solo-remote-config is missing; resetting local Solo config so deployment bootstrap can be recreated cleanly"
    rm -f "${config_file}"
  fi
}

configure_solo_deployment() {
  reset_local_solo_config_if_remote_config_missing
  log "Configuring Solo deployment ${SOLO_DEPLOYMENT}"
  if ! local_config_has_cluster_ref; then
    solo cluster-ref config connect --cluster-ref "kind-${SOLO_CLUSTER_NAME}" --context "kind-${SOLO_CLUSTER_NAME}"
  else
    log "Cluster ref kind-${SOLO_CLUSTER_NAME} already present in local Solo config"
  fi

  if ! local_config_has_deployment; then
    solo deployment config create -n "${SOLO_NAMESPACE}" --deployment "${SOLO_DEPLOYMENT}"
    solo deployment cluster attach --deployment "${SOLO_DEPLOYMENT}" --cluster-ref "kind-${SOLO_CLUSTER_NAME}" --num-consensus-nodes "${CONSENSUS_NODE_COUNT}"
  else
    log "Deployment ${SOLO_DEPLOYMENT} already present in local Solo config"
    if ! local_config_deployment_has_cluster_ref; then
      solo deployment cluster attach --deployment "${SOLO_DEPLOYMENT}" --cluster-ref "kind-${SOLO_CLUSTER_NAME}" --num-consensus-nodes "${CONSENSUS_NODE_COUNT}"
    fi
  fi
}

setup_cluster_minio_prereqs() {
  log "Installing Solo cluster prerequisites required for consensus deploy"
  solo cluster-ref config setup \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --cluster-setup-namespace "${SOLO_CLUSTER_SETUP_NAMESPACE}" \
    --minio true \
    --prometheus-stack false \
    --quiet-mode
}

validate_local_build_path() {
  local base="$1"
  [[ -f "${base}/apps/HederaNode.jar" ]] || return 1
  [[ -d "${base}/lib" ]] || return 1
}

local_build_implementation_version() {
  unzip -p "${LOCAL_BUILD_PATH}/apps/HederaNode.jar" META-INF/MANIFEST.MF 2>/dev/null \
    | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1
}

consensus_pod_implementation_version() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "unzip -p /opt/hgcapp/services-hedera/HapiApp2.0/data/apps/HederaNode.jar META-INF/MANIFEST.MF 2>/dev/null \
      | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1" 2>/dev/null
}

verify_local_build_on_consensus_nodes() {
  local local_version=""
  local pod_version=""
  local node=""
  local pod=""
  local nodes=()

  local_version="$(local_build_implementation_version)"
  [[ -n "${local_version}" ]] || {
    echo "Unable to determine local build Implementation-Version from ${LOCAL_BUILD_PATH}/apps/HederaNode.jar" >&2
    return 1
  }

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    pod_version="$(consensus_pod_implementation_version "${pod}" || true)"
    log "Verifying local build version on ${pod} (expected ${local_version}, found ${pod_version:-unknown})"
    if [[ "${pod_version}" != "${local_version}" ]]; then
      return 1
    fi
  done
}

append_upgrade_wraps_key_path_arg() {
  local -n args_ref="$1"

  [[ -d "${WRAPS_KEY_PATH}" ]] || {
    echo "Missing WRAPS_KEY_PATH directory: ${WRAPS_KEY_PATH}" >&2
    return 1
  }

  log "Using WRAPS key path for upgrade: ${WRAPS_KEY_PATH}"
  args_ref+=(--wraps-key-path "${WRAPS_KEY_PATH}")
}

deploy_baseline_072() {
  local deploy_cmd=()

  if namespace_exists "${SOLO_NAMESPACE}"; then
    log "Removing existing Solo namespace before baseline deploy"
    solo consensus node stop --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" >/dev/null 2>&1 || true
    solo consensus network destroy --deployment "${SOLO_DEPLOYMENT}" --force >/dev/null 2>&1 || true
    delete_namespace_if_exists "${SOLO_NAMESPACE}"
    configure_solo_deployment
  fi

  log "Deploying baseline ${UPGRADE_072_RELEASE_TAG} consensus network"
  solo keys consensus generate --gossip-keys --tls-keys --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
  deploy_cmd=(
    solo consensus network deploy
    --deployment "${SOLO_DEPLOYMENT}"
    -i "${NODE_ALIASES}"
    --application-properties "${APP_PROPS_072_FILE}"
    --log4j2-xml "${LOG4J2_XML_PATH}"
    --pvcs true
    --release-tag "${UPGRADE_072_RELEASE_TAG}"
    --wraps
  )
  "${deploy_cmd[@]}"
  solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --release-tag "${UPGRADE_072_RELEASE_TAG}"
  solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --force-port-forward false
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
}

attempt_073_upgrade() {
  local upgrade_cmd=()

  log "Upgrading consensus network to local build with TSS-enabled 0.73 application.properties"
  upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --upgrade-version "${UPGRADE_073_RELEASE_TAG}"
    --local-build-path "${LOCAL_BUILD_PATH}"
    --application-properties "${APP_PROPS_073_FILE}"
    --application-env "${APP_ENV_073_FILE}"
    --force
  )
  append_upgrade_wraps_key_path_arg upgrade_cmd || return 1

  "${upgrade_cmd[@]}" || return 1

  wait_for_consensus_pods_ready 600 || return 1
  wait_for_haproxy_ready 600 || return 1
  verify_local_build_on_consensus_nodes || return 1
}

log "Validating prerequisites"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd unzip

[[ -f "${APP_PROPS_072_FILE}" ]] || { echo "Missing file: ${APP_PROPS_072_FILE}" >&2; exit 1; }
[[ -f "${APP_PROPS_073_FILE}" ]] || { echo "Missing file: ${APP_PROPS_073_FILE}" >&2; exit 1; }
[[ -f "${APP_ENV_073_FILE}" ]] || { echo "Missing file: ${APP_ENV_073_FILE}" >&2; exit 1; }
[[ -f "${LOG4J2_XML_PATH}" ]] || { echo "Missing file: ${LOG4J2_XML_PATH}" >&2; exit 1; }

validate_local_build_path "${LOCAL_BUILD_PATH}" || {
  echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}" >&2
  echo "Expected ${LOCAL_BUILD_PATH}/apps/HederaNode.jar and ${LOCAL_BUILD_PATH}/lib" >&2
  exit 1
}

log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
cleanup_solo_state

log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
kind create cluster -n "${SOLO_CLUSTER_NAME}"
CLUSTER_CREATED_THIS_RUN="true"

configure_solo_deployment
setup_cluster_minio_prereqs

deploy_baseline_072

if ! attempt_073_upgrade; then
  exit 1
fi

log "PASS: 0.72 -> local 0.73 TSS-enabled upgrade smoke test completed"
