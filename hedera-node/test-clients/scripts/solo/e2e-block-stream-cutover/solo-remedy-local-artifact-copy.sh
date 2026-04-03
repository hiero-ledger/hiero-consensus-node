#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-tss-upgrade}"
SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-}"
WRAPS_KEY_PATH="${WRAPS_KEY_PATH:-${HOME}/.solo/cache/wraps-v0.2.0}"
WAIT_TIMEOUT_SECS="${WAIT_TIMEOUT_SECS:-300}"

HAPI_PATH="/opt/hgcapp/services-hedera/HapiApp2.0"
ROOT_CONTAINER="root-container"
WRAPS_DEST_PATH_DEFAULT="${HAPI_PATH}/wraps-v0.2.0"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command not found: $1" >&2
    exit 1
  }
}

validate_local_build_path() {
  local base="$1"
  [[ -n "${base}" ]] || return 1
  [[ -f "${base}/apps/HederaNode.jar" ]] || return 1
  [[ -d "${base}/lib" ]] || return 1
}

local_wraps_available() {
  [[ -d "${WRAPS_KEY_PATH}" ]] || return 1
  find "${WRAPS_KEY_PATH}" -type f | grep -q .
}

pod_name_for_alias() {
  printf 'network-%s-0\n' "$1"
}

wait_for_pod_ready() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/${pod}" --timeout="${WAIT_TIMEOUT_SECS}s" >/dev/null
}

local_build_version() {
  unzip -p "${LOCAL_BUILD_PATH}/apps/HederaNode.jar" META-INF/MANIFEST.MF 2>/dev/null \
    | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1
}

pod_build_version() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c "${ROOT_CONTAINER}" -- sh -lc \
    "unzip -p ${HAPI_PATH}/data/apps/HederaNode.jar META-INF/MANIFEST.MF 2>/dev/null \
      | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1" 2>/dev/null
}

local_lib_count() {
  find "${LOCAL_BUILD_PATH}/lib" -maxdepth 1 -name '*.jar' | wc -l | tr -d ' '
}

pod_lib_count() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c "${ROOT_CONTAINER}" -- sh -lc \
    "find ${HAPI_PATH}/data/lib -maxdepth 1 -name '*.jar' | wc -l" 2>/dev/null | tr -d ' '
}

missing_libs_for_pod() {
  local pod="$1"
  comm -23 \
    <(find "${LOCAL_BUILD_PATH}/lib" -maxdepth 1 -name '*.jar' | sed 's#.*/##' | sort) \
    <(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c "${ROOT_CONTAINER}" -- sh -lc \
      "find ${HAPI_PATH}/data/lib -maxdepth 1 -name '*.jar' | sed 's#.*/##' | sort")
}

local_wraps_file_count() {
  if ! local_wraps_available; then
    printf '0\n'
    return 0
  fi
  find "${WRAPS_KEY_PATH}" -type f | wc -l | tr -d ' '
}

pod_wraps_dest_path() {
  local pod="$1"
  local pod_path=""
  pod_path="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c "${ROOT_CONTAINER}" -- sh -lc \
    'printf "%s" "${TSS_LIB_WRAPS_ARTIFACTS_PATH:-}"' 2>/dev/null || true)"
  if [[ -n "${pod_path}" ]]; then
    printf '%s\n' "${pod_path}"
  else
    printf '%s\n' "${WRAPS_DEST_PATH_DEFAULT}"
  fi
}

pod_wraps_file_count() {
  local pod="$1"
  local wraps_dest_path=""
  wraps_dest_path="$(pod_wraps_dest_path "${pod}")"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c "${ROOT_CONTAINER}" -- sh -lc \
    "find ${wraps_dest_path} -type f 2>/dev/null | wc -l" 2>/dev/null | tr -d ' '
}

missing_wraps_for_pod() {
  local pod="$1"
  local wraps_dest_path=""
  if ! local_wraps_available; then
    return 0
  fi
  wraps_dest_path="$(pod_wraps_dest_path "${pod}")"

  comm -23 \
    <(find "${WRAPS_KEY_PATH}" -type f | sed "s#^${WRAPS_KEY_PATH}/##" | sort) \
    <(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c "${ROOT_CONTAINER}" -- sh -lc \
      "find ${wraps_dest_path} -type f 2>/dev/null | sed 's#^${wraps_dest_path}/##' | sort")
}

pod_needs_local_build_remedy() {
  local pod="$1"
  local expected_version="$2"
  local found_version=""
  local expected_libs=""
  local found_libs=""

  found_version="$(pod_build_version "${pod}" || true)"
  expected_libs="$(local_lib_count)"
  found_libs="$(pod_lib_count "${pod}" || true)"

  log "Inspecting ${pod}: expected version ${expected_version}, found ${found_version:-unknown}; expected ${expected_libs} libs, found ${found_libs:-0}"

  [[ "${found_version}" == "${expected_version}" ]] || return 0
  [[ "${found_libs}" == "${expected_libs}" ]] || return 0
  [[ -z "$(missing_libs_for_pod "${pod}")" ]] || return 0
  return 1
}

restage_pod_local_build() {
  local pod="$1"

  log "Restaging local build apps/lib on ${pod}"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c "${ROOT_CONTAINER}" -- sh -lc \
    "rm -f ${HAPI_PATH}/data/apps/*.jar ${HAPI_PATH}/data/lib/*.jar"
  kubectl cp "${LOCAL_BUILD_PATH}/apps/." "${SOLO_NAMESPACE}/${pod}:${HAPI_PATH}/data/apps" -c "${ROOT_CONTAINER}"
  kubectl cp "${LOCAL_BUILD_PATH}/lib/." "${SOLO_NAMESPACE}/${pod}:${HAPI_PATH}/data/lib" -c "${ROOT_CONTAINER}"
}

restage_pod_wraps_artifacts() {
  local pod="$1"
  local expected_wraps=""
  local found_wraps=""
  local wraps_dest_path=""

  if ! local_wraps_available; then
    log "WRAPS source directory is unavailable at ${WRAPS_KEY_PATH}; skipping WRAPS restage"
    return 0
  fi

  expected_wraps="$(local_wraps_file_count)"
  found_wraps="$(pod_wraps_file_count "${pod}" || true)"
  wraps_dest_path="$(pod_wraps_dest_path "${pod}")"

  log "Inspecting ${pod} WRAPS path ${wraps_dest_path}: expected ${expected_wraps} files, found ${found_wraps:-0}"

  [[ "${found_wraps}" == "${expected_wraps}" ]] && [[ -z "$(missing_wraps_for_pod "${pod}")" ]] && return 0

  log "Restaging WRAPS artifacts on ${pod} into ${wraps_dest_path}"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c "${ROOT_CONTAINER}" -- sh -lc \
    "rm -rf ${wraps_dest_path} && mkdir -p ${wraps_dest_path}"
  kubectl cp "${WRAPS_KEY_PATH}/." "${SOLO_NAMESPACE}/${pod}:${wraps_dest_path}" -c "${ROOT_CONTAINER}"
}

delete_pod_for_restart() {
  local pod="$1"
  log "Deleting ${pod} so it restarts with the restaged local build"
  kubectl -n "${SOLO_NAMESPACE}" delete pod "${pod}" >/dev/null
}

start_nodes() {
  log "Starting consensus nodes via Solo"
  solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --force-port-forward false
}

main() {
  local expected_version=""
  local node=""
  local pod=""
  local nodes=()
  local remedied_local_build="false"
  local remedied_nodes=()

  require_cmd kubectl
  require_cmd solo
  require_cmd unzip
  require_cmd comm

  validate_local_build_path "${LOCAL_BUILD_PATH}" || {
    echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}" >&2
    echo "Expected ${LOCAL_BUILD_PATH}/apps/HederaNode.jar and ${LOCAL_BUILD_PATH}/lib" >&2
    exit 1
  }

  expected_version="$(local_build_version)"
  [[ -n "${expected_version}" ]] || {
    echo "Unable to determine local build version from ${LOCAL_BUILD_PATH}/apps/HederaNode.jar" >&2
    exit 1
  }

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="$(pod_name_for_alias "${node}")"
    wait_for_pod_ready "${pod}"
    if pod_needs_local_build_remedy "${pod}" "${expected_version}"; then
      remedied_local_build="true"
      restage_pod_local_build "${pod}"
      remedied_nodes+=("${pod}")
    fi
  done

  if [[ "${remedied_local_build}" == "true" ]]; then
    for pod in "${remedied_nodes[@]}"; do
      delete_pod_for_restart "${pod}"
    done
    for pod in "${remedied_nodes[@]}"; do
      wait_for_pod_ready "${pod}"
    done
  fi

  for node in "${nodes[@]}"; do
    pod="$(pod_name_for_alias "${node}")"
    wait_for_pod_ready "${pod}"
    restage_pod_wraps_artifacts "${pod}"
  done

  if [[ "${remedied_local_build}" != "true" ]]; then
    log "No local-build artifact gaps detected; proceeding to node start"
  fi

  start_nodes
}

main "$@"
