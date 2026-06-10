#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Standalone WRB streaming scenario.
#
# This script intentionally does not run the record-stream-to-block-stream
# upgrade flow. Instead, it deploys a fresh Solo network with block streaming
# configured from genesis, adds a Block Node, and verifies that the Block Node
# receives and serves WRB-specific block contents.

set -euo pipefail
set +m

usage() {
  cat <<'EOF'
Usage: solo-wrb-block-node-streaming.sh [--nodes 3|4]

Deploys a fresh Solo network with WRB/block streaming properties configured
before the first node start, adds a Solo Block Node, and verifies the Block Node
serves a WRB record-file block. No upgrade/cutover is performed.

The WRB assertion is intentionally content-based. It does not pass merely
because ordinary block data is available; it requires a RecordFileItem
("recordFile" in grpcurl JSON) and a SignedRecordFileProof
("signedRecordFileProof"). This is expected to fail on branches that only
support normal block streaming. The script exits non-zero if WRB content is not observed.

Environment:
  RELEASE_TAG                   Consensus release tag used by network deploy and node setup
                                (default: v0.73.0-rc.5)
  USE_LOCAL_BUILD               true|false. If true, node setup uploads LOCAL_BUILD_PATH
                                after deploying the chart with RELEASE_TAG (default: true)
  LOCAL_BUILD_PATH              Local build path with lib/ and apps/ jars
                                (default: <repo>/hedera-node/data)
  GENESIS_APP_PROPS_FILE        Base application.properties for genesis deploy
                                (default: wrapped-record-block-jumpstart/resources/0.75/application.properties)
  LOG4J2_XML_PATH               log4j2 xml path (default: <repo>/hedera-node/configuration/dev/log4j2.xml)
  BLOCK_STREAM_MODE             blockStream.streamMode for genesis (default: BOTH)
  BLOCK_STREAM_WRITER_MODE      blockStream.writerMode for genesis (default: FILE_AND_GRPC)
  BLOCK_NODE_REPO_PATH          Optional path to hiero-block-node checkout. If set, defaults
                                BLOCK_NODE_CHART_DIR and BLOCK_NODE_PROTO_PATH from this checkout.
  BLOCK_NODE_ID                 Expected Solo Block Node id to verify (default: 1)
  BLOCK_NODE_CHART_DIR          Optional local block-node chart dir. If unset, Solo uses its
                                release/chart configuration.
  BLOCK_NODE_CHART_VERSION      Block Node chart version passed to Solo block node add
                                (default: v0.32.0)
  BLOCK_NODE_RELEASE_TAG        Optional consensus release tag passed to Solo block node add
                                (default: RELEASE_TAG)
  BLOCK_NODE_IMAGE_TAG          Optional image tag override passed to Solo block node add
  BLOCK_NODE_VALUES_FILE        Optional values file passed to Solo block node add
  BLOCK_NODE_PRIORITY_MAPPING   Optional priority mapping. Defaults to all script nodes
                                routed to the deployed Block Node with priority 1.
  BLOCK_NODE_PROTO_PATH         Optional Block Node proto import path used by grpcurl. If unset,
                                the script writes a minimal compatible proto set under WORK_DIR.
  HAPI_PROTO_PATH               HAPI proto import path used by grpcurl
                                (default: <repo>/hapi/hedera-protobuf-java-api/src/main/proto)
  WRB_SEARCH_MAX_BLOCKS         Maximum number of persisted Block Node blocks to inspect for
                                RecordFileItem content (default: 25)
  BLOCK_NODE_PERSIST_MAX_ATTEMPTS
                                Number of Block Node status polling attempts (default: 90)
  BLOCK_NODE_PERSIST_SLEEP_SECS Seconds between Block Node status polling attempts (default: 5)
  KEEP_NETWORK                  true|false (default: true)
  GENERATED_DIR                 Base directory for generated artifacts
                                (default: wrapped-record-block-jumpstart/generated)
  CN_GRPC_LOCAL_PORT            Local port for consensus gRPC forwarding (default: 50211)
  BLOCK_NODE_GRPC_LOCAL_PORT    Local port for Block Node gRPC forwarding (default: 40840)

Examples:
  ./solo-wrb-block-node-streaming.sh
  NODE_ALIASES=node1,node2,node3 ./solo-wrb-block-node-streaming.sh --nodes 3
  USE_LOCAL_BUILD=false RELEASE_TAG=v0.74.0 ./solo-wrb-block-node-streaming.sh --nodes 3
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

# Shared remote-cluster helpers (default StorageClass, deployment re-establish after destroy,
# toleration patcher) reused from the jumpstart scenario; the functions are no-ops on kind.
# shellcheck source=../remote-cluster-helpers.sh
source "${SCRIPT_DIR}/../remote-cluster-helpers.sh"

export SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-solo}"
export SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
export SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-cluster}"
export SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-deployment}"

# Cluster target: "kind" (default, ephemeral local cluster) or "remote" (pre-allocated cluster
# reached via an already-current kubectl context, e.g. Teleport). On remote, adopt the CITR
# conventions so this lands on the shared cluster the same way the longevity tooling does.
CLUSTER_TARGET="${CLUSTER_TARGET:-kind}"
if [[ "${CLUSTER_TARGET}" == "remote" ]]; then
  : "${KUBE_CONTEXT:?CLUSTER_TARGET=remote requires KUBE_CONTEXT (the kubectl context to use)}"
  CLUSTER_REF="${CLUSTER_REF:-${SOLO_NAMESPACE}-ref}"
  export SOLO_CLUSTER_SETUP_NAMESPACE="solo-setup"
  export SOLO_DEPLOYMENT="${SOLO_NAMESPACE}-test"
elif [[ "${CLUSTER_TARGET}" == "kind" ]]; then
  KUBE_CONTEXT="${KUBE_CONTEXT:-kind-${SOLO_CLUSTER_NAME}}"
  CLUSTER_REF="${CLUSTER_REF:-kind-${SOLO_CLUSTER_NAME}}"
else
  echo "Invalid CLUSTER_TARGET: ${CLUSTER_TARGET} (expected 'kind' or 'remote')" >&2
  exit 1
fi

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
RELEASE_TAG="${RELEASE_TAG:-v0.73.0-rc.5}"
USE_LOCAL_BUILD="${USE_LOCAL_BUILD:-true}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
LOG4J2_XML_PATH="${LOG4J2_XML_PATH:-${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml}"
GENESIS_APP_PROPS_FILE="${GENESIS_APP_PROPS_FILE:-${SCRIPT_DIR}/resources/0.75/application.properties}"
BLOCK_STREAM_MODE="${BLOCK_STREAM_MODE:-BOTH}"
BLOCK_STREAM_WRITER_MODE="${BLOCK_STREAM_WRITER_MODE:-FILE_AND_GRPC}"
BLOCK_NODE_REPO_PATH="${BLOCK_NODE_REPO_PATH:-}"
BLOCK_NODE_ID="${BLOCK_NODE_ID:-1}"
if [[ -n "${BLOCK_NODE_REPO_PATH}" ]]; then
  BLOCK_NODE_CHART_DIR="${BLOCK_NODE_CHART_DIR:-${BLOCK_NODE_REPO_PATH}/charts}"
  BLOCK_NODE_PROTO_PATH="${BLOCK_NODE_PROTO_PATH:-${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto}"
else
  BLOCK_NODE_CHART_DIR="${BLOCK_NODE_CHART_DIR:-}"
  BLOCK_NODE_PROTO_PATH="${BLOCK_NODE_PROTO_PATH:-}"
fi
BLOCK_NODE_CHART_VERSION="${BLOCK_NODE_CHART_VERSION:-v0.32.0}"
BLOCK_NODE_RELEASE_TAG="${BLOCK_NODE_RELEASE_TAG:-${RELEASE_TAG}}"
BLOCK_NODE_IMAGE_TAG="${BLOCK_NODE_IMAGE_TAG:-}"
BLOCK_NODE_VALUES_FILE="${BLOCK_NODE_VALUES_FILE:-}"
BLOCK_NODE_PRIORITY_MAPPING="${BLOCK_NODE_PRIORITY_MAPPING:-}"
HAPI_PROTO_PATH="${HAPI_PROTO_PATH:-${REPO_ROOT}/hapi/hedera-protobuf-java-api/src/main/proto}"

CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
BLOCK_NODE_GRPC_LOCAL_PORT="${BLOCK_NODE_GRPC_LOCAL_PORT:-40840}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

GENERATED_DIR="${GENERATED_DIR:-${SCRIPT_DIR}/generated}"
WORK_DIR="${WORK_DIR:-${GENERATED_DIR}/work-genesis-block-node}"
TMP_GENESIS_APP_PROPS="${WORK_DIR}/genesis-application.properties"
WRB_LOCAL_PAYLOAD_PATH="${WORK_DIR}/localPayload/data"
GENERATED_BLOCK_NODE_PROTO_PATH="${WORK_DIR}/block-node-protos"
EFFECTIVE_LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH}"
CN_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-cn.log"
BLOCK_NODE_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-block-node.log"
BLOCK_NODE_STATUS_LOG="${WORK_DIR}/block-node-status.log"
BLOCK_NODE_WRB_SUBSCRIBE_LOG="${WORK_DIR}/block-node-wrb-subscribe.log"
BLOCK_NODE_WRB_SCAN_LOG="${WORK_DIR}/block-node-wrb-scan.log"
BLOCK_NODE_K8S_LOG="${WORK_DIR}/block-node-k8s.log"
CONSENSUS_BLOCK_NODE_LOG_CHECK="${WORK_DIR}/consensus-block-node-log-check.log"
WRB_SEARCH_MAX_BLOCKS="${WRB_SEARCH_MAX_BLOCKS:-25}"
BLOCK_NODE_PERSIST_MAX_ATTEMPTS="${BLOCK_NODE_PERSIST_MAX_ATTEMPTS:-90}"
BLOCK_NODE_PERSIST_SLEEP_SECS="${BLOCK_NODE_PERSIST_SLEEP_SECS:-5}"

CN_PORT_FORWARD_PID=""
BLOCK_NODE_PORT_FORWARD_PID=""
OBSERVED_FIRST_BLOCK=""
OBSERVED_LAST_BLOCK=""
OBSERVED_WRB_BLOCK=""

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

cleanup() {
  local exit_code=$?
  set +e
  [[ -n "${CN_PORT_FORWARD_PID}" ]] && kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  [[ -n "${BLOCK_NODE_PORT_FORWARD_PID}" ]] && kill "${BLOCK_NODE_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  stop_remote_toleration_patcher
  if [[ "${KEEP_NETWORK}" != "true" && ${exit_code} -eq 0 ]]; then
    log "KEEP_NETWORK=false, destroying Solo resources (and the kind cluster when CLUSTER_TARGET=kind)"
    solo block node destroy --deployment "${SOLO_DEPLOYMENT}" --id "${BLOCK_NODE_ID}" --quiet-mode --force >/dev/null 2>&1 || true
    solo consensus node stop --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" >/dev/null 2>&1 || true
    solo consensus network destroy --deployment "${SOLO_DEPLOYMENT}" --force >/dev/null 2>&1 || true
    # Never delete the shared remote cluster; only an ephemeral kind cluster is torn down here.
    if [[ "${CLUSTER_TARGET}" == "kind" ]]; then
      kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
    fi
  fi
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

local_build_implementation_version() {
  unzip -p "${LOCAL_BUILD_PATH}/apps/HederaNode.jar" META-INF/MANIFEST.MF 2>/dev/null \
    | sed -n 's/^Implementation-Version: //p' | sed -n '1p' | tr -d '\r'
}

consensus_pod_implementation_version() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "unzip -p /opt/hgcapp/services-hedera/HapiApp2.0/data/apps/HederaNode.jar META-INF/MANIFEST.MF 2>/dev/null \
      | sed -n 's/^Implementation-Version: //p' | sed -n '1p'" | tr -d '\r'
}

verify_local_build_on_consensus_nodes() {
  local expected node pod actual
  local nodes=()
  expected="$(local_build_implementation_version)"
  [[ -n "${expected}" ]] || { echo "Unable to determine local build Implementation-Version from ${LOCAL_BUILD_PATH}/apps/HederaNode.jar" >&2; return 1; }
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    actual="$(consensus_pod_implementation_version "${pod}" || true)"
    if [[ "${actual}" != "${expected}" ]]; then
      echo "Local build was not applied on ${pod}: expected '${expected}', found '${actual:-unknown}'" >&2
      return 1
    fi
  done
  log "Verified local build Implementation-Version on all nodes: ${expected}"
}

set_property_in_file() {
  local file="$1"
  local key="$2"
  local value="$3"
  local tmp_file
  tmp_file="$(mktemp)"
  awk -v key="${key}" -v value="${value}" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
      if (!replaced) {
        print key "=" value
        replaced = 1
      }
      next
    }
    { print }
    END {
      if (!replaced) {
        print key "=" value
      }
    }
  ' "${file}" > "${tmp_file}"
  mv "${tmp_file}" "${file}"
}

build_default_block_node_priority_mapping() {
  local node
  local nodes=()
  local mapping=""
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    [[ -n "${mapping}" ]] && mapping+=","
    mapping+="${node}=1"
  done
  echo "${mapping}"
}

write_generated_block_node_protos() {
  mkdir -p "${GENERATED_BLOCK_NODE_PROTO_PATH}/block-node/api"

  # Keep this proto surface intentionally small. The test only needs
  # serverStatus and subscribeBlockStream, plus enough response shape to decode
  # HAPI BlockItem JSON and assert WRB-specific recordFile content.
  cat > "${GENERATED_BLOCK_NODE_PROTO_PATH}/block-node/api/node_service.proto" <<'EOF'
syntax = "proto3";

package org.hiero.block.api;

message ServerStatusRequest {}

message ServerStatusResponse {
  uint64 first_available_block = 1;
  uint64 last_available_block = 2;
  bool only_latest_state = 3;
}

service BlockNodeService {
  rpc serverStatus(ServerStatusRequest) returns (ServerStatusResponse);
}
EOF

  cat > "${GENERATED_BLOCK_NODE_PROTO_PATH}/block-node/api/shared_message_types.proto" <<'EOF'
syntax = "proto3";

package org.hiero.block.api;

import "block/stream/block_item.proto";

message BlockItemSet {
  repeated com.hedera.hapi.block.stream.BlockItem block_items = 1;
}

message BlockEnd {
  uint64 block_number = 1;
}
EOF

  cat > "${GENERATED_BLOCK_NODE_PROTO_PATH}/block-node/api/block_stream_subscribe_service.proto" <<'EOF'
syntax = "proto3";

package org.hiero.block.api;

import "block-node/api/shared_message_types.proto";

message SubscribeStreamRequest {
  uint64 start_block_number = 1;
  uint64 end_block_number = 2;
}

message SubscribeStreamResponse {
  enum Code {
    UNKNOWN = 0;
    SUCCESS = 1;
    INVALID_REQUEST = 2;
    ERROR = 3;
    INVALID_START_BLOCK_NUMBER = 4;
    INVALID_END_BLOCK_NUMBER = 5;
    NOT_AVAILABLE = 6;
  }

  oneof response {
    Code status = 1;
    BlockItemSet block_items = 2;
    BlockEnd end_of_block = 3;
  }
}

service BlockStreamSubscribeService {
  rpc subscribeBlockStream(SubscribeStreamRequest) returns (stream SubscribeStreamResponse);
}
EOF
}

prepare_block_node_proto_path() {
  if [[ -n "${BLOCK_NODE_PROTO_PATH}" ]]; then
    [[ -f "${BLOCK_NODE_PROTO_PATH}/block-node/api/node_service.proto" ]] || {
      echo "Block Node node_service.proto not found under BLOCK_NODE_PROTO_PATH=${BLOCK_NODE_PROTO_PATH}" >&2
      return 1
    }
    [[ -f "${BLOCK_NODE_PROTO_PATH}/block-node/api/block_stream_subscribe_service.proto" ]] || {
      echo "Block Node block_stream_subscribe_service.proto not found under BLOCK_NODE_PROTO_PATH=${BLOCK_NODE_PROTO_PATH}" >&2
      return 1
    }
    return 0
  fi

  write_generated_block_node_protos
  BLOCK_NODE_PROTO_PATH="${GENERATED_BLOCK_NODE_PROTO_PATH}"
  log "Generated minimal Block Node grpcurl protos at ${BLOCK_NODE_PROTO_PATH}"
}

wait_for_tcp_open() {
  local host="$1"
  local port="$2"
  local max_attempts="${3:-30}"
  local sleep_secs="${4:-1}"
  local attempt=1
  while (( attempt <= max_attempts )); do
    if command -v nc >/dev/null 2>&1; then
      nc -z "${host}" "${port}" >/dev/null 2>&1 && return 0
    else
      (: <"/dev/tcp/${host}/${port}") >/dev/null 2>&1 && return 0
    fi
    sleep "${sleep_secs}"
    ((++attempt))
  done
  return 1
}

kill_processes_on_local_port() {
  local port="$1"
  local pids=""
  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -ti "tcp:${port}" 2>/dev/null || true)"
    [[ -n "${pids}" ]] && kill ${pids} >/dev/null 2>&1 || true
  fi
}

cleanup_stale_port_forwards() {
  pkill -f "port-forward svc/haproxy-node1-svc .*${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >/dev/null 2>&1 || true
  pkill -f "port-forward svc/block-node-${BLOCK_NODE_ID} .*${BLOCK_NODE_GRPC_LOCAL_PORT}:40840" >/dev/null 2>&1 || true
}

restart_port_forwards() {
  [[ -n "${CN_PORT_FORWARD_PID}" ]] && kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  [[ -n "${BLOCK_NODE_PORT_FORWARD_PID}" ]] && kill "${BLOCK_NODE_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  CN_PORT_FORWARD_PID=""
  BLOCK_NODE_PORT_FORWARD_PID=""
  cleanup_stale_port_forwards
  kill_processes_on_local_port "${CN_GRPC_LOCAL_PORT}"
  kill_processes_on_local_port "${BLOCK_NODE_GRPC_LOCAL_PORT}"

  kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >"${CN_PORT_FORWARD_LOG}" 2>&1 &
  CN_PORT_FORWARD_PID="$!"
  kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/block-node-${BLOCK_NODE_ID}" "${BLOCK_NODE_GRPC_LOCAL_PORT}:40840" >"${BLOCK_NODE_PORT_FORWARD_LOG}" 2>&1 &
  BLOCK_NODE_PORT_FORWARD_PID="$!"

  wait_for_tcp_open "127.0.0.1" "${CN_GRPC_LOCAL_PORT}" 30 1 || {
    echo "Consensus gRPC port-forward is not reachable on localhost:${CN_GRPC_LOCAL_PORT}" >&2
    return 1
  }
  wait_for_tcp_open "127.0.0.1" "${BLOCK_NODE_GRPC_LOCAL_PORT}" 30 1 || {
    echo "Block Node gRPC port-forward is not reachable on localhost:${BLOCK_NODE_GRPC_LOCAL_PORT}" >&2
    return 1
  }
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

create_genesis_application_properties() {
  cp "${GENESIS_APP_PROPS_FILE}" "${TMP_GENESIS_APP_PROPS}"
  # Genesis-mode WRB/Block Node streaming: block streaming is enabled before
  # first node start, so this validates steady-state streaming without relying
  # on the record-stream-to-block-stream upgrade migration path.
  set_property_in_file "${TMP_GENESIS_APP_PROPS}" "blockStream.streamMode" "${BLOCK_STREAM_MODE}"
  set_property_in_file "${TMP_GENESIS_APP_PROPS}" "blockStream.writerMode" "${BLOCK_STREAM_WRITER_MODE}"
  set_property_in_file "${TMP_GENESIS_APP_PROPS}" "blockNode.blockNodeConnectionFileDir" "data/config"
  set_property_in_file "${TMP_GENESIS_APP_PROPS}" "blockStream.buffer.isBufferPersistenceEnabled" "true"
  set_property_in_file "${TMP_GENESIS_APP_PROPS}" "blockNode.blockNodeStatusTimeout" "10s"
  set_property_in_file "${TMP_GENESIS_APP_PROPS}" "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk" "true"
  chmod 0644 "${TMP_GENESIS_APP_PROPS}"
  log "Created genesis application.properties with ${BLOCK_STREAM_MODE}/${BLOCK_STREAM_WRITER_MODE}: ${TMP_GENESIS_APP_PROPS}"
}

prepare_wrb_local_build_payload() {
  EFFECTIVE_LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH}"
  [[ "${USE_LOCAL_BUILD}" == "true" ]] || return 0

  rm -rf "${WRB_LOCAL_PAYLOAD_PATH}" >/dev/null 2>&1 || true
  mkdir -p "${WRB_LOCAL_PAYLOAD_PATH}"
  cp -R "${LOCAL_BUILD_PATH}/apps" "${WRB_LOCAL_PAYLOAD_PATH}/"
  cp -R "${LOCAL_BUILD_PATH}/lib" "${WRB_LOCAL_PAYLOAD_PATH}/"

  shopt -s nullglob
  local builder_jars=("${WRB_LOCAL_PAYLOAD_PATH}"/lib/helidon-builder-api-*.jar)
  shopt -u nullglob

  [[ ${#builder_jars[@]} -gt 0 ]] || {
    echo "WRB Block Node streaming requires helidon-builder-api in ${LOCAL_BUILD_PATH}/lib" >&2
    return 1
  }

  local jar_path jar_name alias_name
  for jar_path in "${builder_jars[@]}"; do
    jar_name="$(basename "${jar_path}")"
    alias_name="${jar_name/helidon-builder-api/helidon-bldr-api}"
    # Solo filters local-build paths containing the substring "build"; this
    # alias keeps the same jar contents while letting Solo copy the dependency.
    cp "${jar_path}" "${WRB_LOCAL_PAYLOAD_PATH}/lib/${alias_name}"
  done

  EFFECTIVE_LOCAL_BUILD_PATH="${WRB_LOCAL_PAYLOAD_PATH}"
  log "Prepared WRB local-build payload for Solo at ${EFFECTIVE_LOCAL_BUILD_PATH}"
}

deploy_block_node_for_streaming() {
  local add_args=(
    solo block node add
    --deployment "${SOLO_DEPLOYMENT}"
    --cluster-ref "${CLUSTER_REF}"
    --quiet-mode
    --priority-mapping "${BLOCK_NODE_PRIORITY_MAPPING}"
  )
  [[ -n "${BLOCK_NODE_CHART_DIR}" ]] && add_args+=(--block-node-chart-dir "${BLOCK_NODE_CHART_DIR}")
  [[ -n "${BLOCK_NODE_CHART_VERSION}" ]] && add_args+=(--chart-version "${BLOCK_NODE_CHART_VERSION}")
  [[ -n "${BLOCK_NODE_RELEASE_TAG}" ]] && add_args+=(--release-tag "${BLOCK_NODE_RELEASE_TAG}")
  [[ -n "${BLOCK_NODE_IMAGE_TAG}" ]] && add_args+=(--image-tag "${BLOCK_NODE_IMAGE_TAG}")
  [[ -n "${BLOCK_NODE_VALUES_FILE}" ]] && add_args+=(--values-file "${BLOCK_NODE_VALUES_FILE}")

  log "Deploying Block Node ${BLOCK_NODE_ID} and routing consensus nodes with priority mapping '${BLOCK_NODE_PRIORITY_MAPPING}'"
  "${add_args[@]}"
  kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/block-node-${BLOCK_NODE_ID}-0" --timeout=600s
}

block_node_server_status_json() {
  grpcurl -plaintext -emit-defaults \
    -import-path "${BLOCK_NODE_PROTO_PATH}" \
    -import-path "${HAPI_PROTO_PATH}" \
    -proto block-node/api/node_service.proto \
    -d '{}' \
    "127.0.0.1:${BLOCK_NODE_GRPC_LOCAL_PORT}" \
    org.hiero.block.api.BlockNodeService/serverStatus
}

wait_for_block_node_to_persist_block() {
  local max_attempts="${BLOCK_NODE_PERSIST_MAX_ATTEMPTS}"
  local sleep_secs="${BLOCK_NODE_PERSIST_SLEEP_SECS}"
  local attempt=1 status first last
  : > "${BLOCK_NODE_STATUS_LOG}"
  while (( attempt <= max_attempts )); do
    status="$(block_node_server_status_json 2>>"${BLOCK_NODE_STATUS_LOG}" || true)"
    if [[ -n "${status}" ]]; then
      echo "${status}" > "${BLOCK_NODE_STATUS_LOG}"
      first="$(echo "${status}" | jq -r '.firstAvailableBlock // empty')"
      last="$(echo "${status}" | jq -r '.lastAvailableBlock // empty')"
      if [[ "${first}" =~ ^[0-9]+$ && "${last}" =~ ^[0-9]+$ ]]; then
        log "Block Node status: firstAvailableBlock=${first}, lastAvailableBlock=${last}"
        if [[ "${first}" != "18446744073709551615" && "${last}" != "18446744073709551615" && ${last} -ge ${first} ]]; then
          OBSERVED_FIRST_BLOCK="${first}"
          OBSERVED_LAST_BLOCK="${last}"
          return 0
        fi
      fi
    fi
    sleep "${sleep_secs}"
    ((++attempt))
  done
  echo "Timed out waiting for Block Node to persist streamed block data" >&2
  sed -n '1,80p' "${BLOCK_NODE_STATUS_LOG}" >&2 || true
  return 1
}

assert_block_node_serves_wrb_record_file_block() {
  local start="${OBSERVED_FIRST_BLOCK}"
  local last="${OBSERVED_LAST_BLOCK}"
  local block scanned tmp record_file_count proof_count

  [[ "${start}" =~ ^[0-9]+$ && "${last}" =~ ^[0-9]+$ ]] || {
    echo "Cannot inspect WRB blocks without numeric Block Node range: first='${start}', last='${last}'" >&2
    return 1
  }

  : > "${BLOCK_NODE_WRB_SCAN_LOG}"
  rm -f "${BLOCK_NODE_WRB_SUBSCRIBE_LOG}" >/dev/null 2>&1 || true

  block="${start}"
  scanned=0
  while (( block <= last && scanned < WRB_SEARCH_MAX_BLOCKS )); do
    tmp="${WORK_DIR}/subscribe-block-${block}.json"
    echo "Checking Block Node block ${block} for WRB RecordFileItem content" >> "${BLOCK_NODE_WRB_SCAN_LOG}"

    if grpcurl -plaintext -emit-defaults \
      -import-path "${BLOCK_NODE_PROTO_PATH}" \
      -import-path "${HAPI_PROTO_PATH}" \
      -proto block-node/api/block_stream_subscribe_service.proto \
      -d "{\"startBlockNumber\": ${block}, \"endBlockNumber\": ${block}}" \
      "127.0.0.1:${BLOCK_NODE_GRPC_LOCAL_PORT}" \
      org.hiero.block.api.BlockStreamSubscribeService/subscribeBlockStream \
      >"${tmp}" 2>&1; then
      record_file_count="$(grep -c '"recordFile"' "${tmp}" || true)"
      proof_count="$(grep -c '"signedRecordFileProof"' "${tmp}" || true)"

      if (( record_file_count > 0 )); then
        cp "${tmp}" "${BLOCK_NODE_WRB_SUBSCRIBE_LOG}"
        if (( record_file_count != 1 )); then
          echo "WRB block ${block} should contain exactly one RecordFileItem, found ${record_file_count}" >&2
          sed -n '1,160p' "${BLOCK_NODE_WRB_SUBSCRIBE_LOG}" >&2 || true
          return 1
        fi
        if (( proof_count < 1 )); then
          echo "WRB block ${block} contains RecordFileItem but no SignedRecordFileProof" >&2
          sed -n '1,160p' "${BLOCK_NODE_WRB_SUBSCRIBE_LOG}" >&2 || true
          return 1
        fi
        OBSERVED_WRB_BLOCK="${block}"
        log "Verified Block Node serves WRB RecordFileItem content in block ${block}"
        return 0
      fi

      echo "Block ${block}: no RecordFileItem" >> "${BLOCK_NODE_WRB_SCAN_LOG}"
    else
      echo "Block ${block}: subscribe failed; see ${tmp}" >> "${BLOCK_NODE_WRB_SCAN_LOG}"
    fi

    ((++scanned))
    ((++block))
  done

  echo "ERROR: WRB streaming verification failed." >&2
  echo "Did not find WRB RecordFileItem content in the first ${scanned} persisted Block Node block(s) inspected." >&2
  echo "Observed Block Node range: firstAvailableBlock=${start}, lastAvailableBlock=${last}" >&2
  echo "This means ordinary Block Node streaming may be working, but WRB record-file streaming was not observed." >&2
  sed -n '1,160p' "${BLOCK_NODE_WRB_SCAN_LOG}" >&2 || true
  return 1
}

assert_consensus_block_node_logs_clean() {
  local node pod bad=0 node_log block_nodes_json
  local nodes=()
  : > "${CONSENSUS_BLOCK_NODE_LOG_CHECK}"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    block_nodes_json="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "cat /opt/hgcapp/services-hedera/HapiApp2.0/data/config/block-nodes.json 2>/dev/null" || true)"
    if ! echo "${block_nodes_json}" | grep -q "block-node-${BLOCK_NODE_ID}"; then
      echo "Consensus node ${pod} does not have block-node-${BLOCK_NODE_ID} in block-nodes.json" >&2
      return 1
    fi
    node_log="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "cat /opt/hgcapp/services-hedera/HapiApp2.0/output/hgcaa.log 2>/dev/null" || true)"
    if echo "${node_log}" | grep -E 'No active connections available for streaming|No block nodes available to connect to|Block stream worker interrupted|Failed to read/parse block node configuration|Failed to parse block node configuration|Failed to resolve block node host' >>"${CONSENSUS_BLOCK_NODE_LOG_CHECK}"; then
      bad=1
    fi
  done
  if (( bad == 1 )); then
    echo "Consensus-node logs contain Block Node streaming errors:" >&2
    sed -n '1,160p' "${CONSENSUS_BLOCK_NODE_LOG_CHECK}" >&2 || true
    return 1
  fi
  log "Verified consensus nodes have block-nodes.json and no obvious Block Node streaming errors"
}

assert_block_node_logs_clean() {
  kubectl -n "${SOLO_NAMESPACE}" logs "pod/block-node-${BLOCK_NODE_ID}-0" --all-containers --tail=-1 >"${BLOCK_NODE_K8S_LOG}" 2>&1 || {
    sed -n '1,160p' "${BLOCK_NODE_K8S_LOG}" >&2 || true
    return 1
  }
  if grep -Ei '\b(SEVERE|ERROR|Exception|OutOfMemoryError)\b|failed to (persist|process|verify|publish|subscribe|read|write)' "${BLOCK_NODE_K8S_LOG}" >/dev/null; then
    echo "Block Node logs contain obvious errors:" >&2
    grep -Ein '\b(SEVERE|ERROR|Exception|OutOfMemoryError)\b|failed to (persist|process|verify|publish|subscribe|read|write)' "${BLOCK_NODE_K8S_LOG}" | sed -n '1,160p' >&2 || true
    return 1
  fi
}

log "Validating prerequisites"
# kind is only needed for the local ephemeral cluster; the remote runner has no kind binary.
if [[ "${CLUSTER_TARGET}" == "kind" ]]; then
  require_cmd kind
fi
require_cmd kubectl
require_cmd solo
require_cmd jq
require_cmd awk
require_cmd grpcurl
require_cmd unzip
[[ -f "${LOG4J2_XML_PATH}" ]] || { echo "log4j2 config not found: ${LOG4J2_XML_PATH}" >&2; exit 1; }
[[ -f "${GENESIS_APP_PROPS_FILE}" ]] || { echo "Genesis application.properties not found: ${GENESIS_APP_PROPS_FILE}" >&2; exit 1; }
[[ -z "${BLOCK_NODE_CHART_DIR}" || -d "${BLOCK_NODE_CHART_DIR}" ]] || { echo "BLOCK_NODE_CHART_DIR not found: ${BLOCK_NODE_CHART_DIR}" >&2; exit 1; }
[[ -f "${HAPI_PROTO_PATH}/services/basic_types.proto" ]] || {
  echo "HAPI services/basic_types.proto not found under HAPI_PROTO_PATH=${HAPI_PROTO_PATH}" >&2
  exit 1
}
[[ "${WRB_SEARCH_MAX_BLOCKS}" =~ ^[1-9][0-9]*$ ]] || {
  echo "WRB_SEARCH_MAX_BLOCKS must be a positive integer, found '${WRB_SEARCH_MAX_BLOCKS}'" >&2
  exit 1
}
[[ "${BLOCK_NODE_PERSIST_MAX_ATTEMPTS}" =~ ^[1-9][0-9]*$ ]] || {
  echo "BLOCK_NODE_PERSIST_MAX_ATTEMPTS must be a positive integer, found '${BLOCK_NODE_PERSIST_MAX_ATTEMPTS}'" >&2
  exit 1
}
[[ "${BLOCK_NODE_PERSIST_SLEEP_SECS}" =~ ^[1-9][0-9]*$ ]] || {
  echo "BLOCK_NODE_PERSIST_SLEEP_SECS must be a positive integer, found '${BLOCK_NODE_PERSIST_SLEEP_SECS}'" >&2
  exit 1
}
if [[ "${USE_LOCAL_BUILD}" == "true" ]]; then
  validate_local_build_path "${LOCAL_BUILD_PATH}"
  local_build_version="$(local_build_implementation_version)"
  [[ -n "${local_build_version}" ]] || {
    echo "Unable to determine local build Implementation-Version from ${LOCAL_BUILD_PATH}/apps/HederaNode.jar" >&2
    exit 1
  }
  log "Using local build Implementation-Version ${local_build_version}; deploy chart release tag is ${RELEASE_TAG}"
else
  log "Using release tag ${RELEASE_TAG} without local build upload"
fi
if [[ -z "${BLOCK_NODE_PRIORITY_MAPPING}" ]]; then
  BLOCK_NODE_PRIORITY_MAPPING="$(build_default_block_node_priority_mapping)"
fi

log "Cleaning previous local artifacts"
rm -rf "${WORK_DIR}" >/dev/null 2>&1 || true
mkdir -p "${GENERATED_DIR}" "${WORK_DIR}" >/dev/null 2>&1 || true
cleanup_stale_port_forwards
prepare_block_node_proto_path
create_genesis_application_properties
prepare_wrb_local_build_payload

if [[ "${CLUSTER_TARGET}" == "kind" ]]; then
  log "Resetting kind cluster ${SOLO_CLUSTER_NAME}"
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  kind create cluster -n "${SOLO_CLUSTER_NAME}"
fi

log "Configuring Solo deployment ${SOLO_DEPLOYMENT} for ${CONSENSUS_NODE_COUNT} node(s) (cluster-ref=${CLUSTER_REF}, context=${KUBE_CONTEXT})"
solo cluster-ref config connect --cluster-ref "${CLUSTER_REF}" --context "${KUBE_CONTEXT}"
solo deployment config delete --deployment "${SOLO_DEPLOYMENT}" --quiet-mode >/dev/null 2>&1 || true
solo deployment config create -n "${SOLO_NAMESPACE}" --deployment "${SOLO_DEPLOYMENT}"
solo deployment cluster attach --deployment "${SOLO_DEPLOYMENT}" --cluster-ref "${CLUSTER_REF}" --num-consensus-nodes "${CONSENSUS_NODE_COUNT}"

if [[ "${CLUSTER_TARGET}" == "remote" ]]; then
  # Mark local-path default, tear down any prior network, re-establish the deployment config the
  # destroy removes, and run cluster-ref setup only if missing (shared helper).
  remote_reset_and_prepare_deployment
else
  solo cluster-ref config setup -s "${SOLO_CLUSTER_SETUP_NAMESPACE}" --prometheus-stack true
fi

log "Deploying consensus network with genesis block streaming properties"
solo keys consensus generate --gossip-keys --tls-keys --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
# On remote, pass the scheduling/storage value overrides and deploy without PVCs (emptyDir); kind keeps PVCs.
deploy_pvcs="true"
deploy_args=(
  solo consensus network deploy
  --deployment "${SOLO_DEPLOYMENT}"
  -i "${NODE_ALIASES}"
  --application-properties "${TMP_GENESIS_APP_PROPS}"
  --log4j2-xml "${LOG4J2_XML_PATH}"
  --service-monitor true
  --pod-log true
  --release-tag "${RELEASE_TAG}"
)
if [[ "${CLUSTER_TARGET}" == "remote" ]]; then
  deploy_pvcs="false"
  deploy_args+=(--values-file "${REMOTE_CLUSTER_NETWORK_VALUES}")
fi
deploy_args+=(--pvcs "${deploy_pvcs}")
"${deploy_args[@]}"

setup_args=(
  solo consensus node setup
  --deployment "${SOLO_DEPLOYMENT}"
  -i "${NODE_ALIASES}"
  --release-tag "${RELEASE_TAG}"
)
if [[ "${USE_LOCAL_BUILD}" == "true" ]]; then
  setup_args+=(--local-build-path "${EFFECTIVE_LOCAL_BUILD_PATH}")
fi
"${setup_args[@]}"

solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
wait_for_consensus_pods_ready 600
wait_for_haproxy_ready 600
if [[ "${USE_LOCAL_BUILD}" == "true" ]]; then
  verify_local_build_on_consensus_nodes
fi

# On remote, keep the block-node (and any shared-resources) pods tolerating the node taint while
# they come up; no-op on kind.
start_remote_toleration_patcher
deploy_block_node_for_streaming
restart_port_forwards

log "Waiting for Block Node to persist streamed block data"
wait_for_block_node_to_persist_block
stop_remote_toleration_patcher
log "Verifying required WRB RecordFileItem streaming; absence of WRB content is a test failure"
assert_block_node_serves_wrb_record_file_block
assert_consensus_block_node_logs_clean
assert_block_node_logs_clean

log "SUCCESS: Block Node received and served WRB RecordFileItem block data"
log "Observed Block Node range: firstAvailableBlock=${OBSERVED_FIRST_BLOCK}, lastAvailableBlock=${OBSERVED_LAST_BLOCK}"
log "Observed WRB block: ${OBSERVED_WRB_BLOCK}"
log "Generated artifacts root: ${GENERATED_DIR}"
log "Genesis application.properties: ${TMP_GENESIS_APP_PROPS}"
log "Block Node status log: ${BLOCK_NODE_STATUS_LOG}"
log "Block Node WRB scan log: ${BLOCK_NODE_WRB_SCAN_LOG}"
log "Block Node WRB subscribe log: ${BLOCK_NODE_WRB_SUBSCRIBE_LOG}"
log "Block Node Kubernetes log: ${BLOCK_NODE_K8S_LOG}"
