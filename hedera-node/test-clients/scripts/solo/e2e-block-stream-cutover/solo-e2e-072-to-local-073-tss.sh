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
CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"
RECREATE_CLUSTER="${RECREATE_CLUSTER:-false}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"
CLEAN_SOLO_STATE="${CLEAN_SOLO_STATE:-true}"
REUSE_BASELINE_SNAPSHOT="${REUSE_BASELINE_SNAPSHOT:-true}"
ROLLBACK_ON_UPGRADE_FAILURE="${ROLLBACK_ON_UPGRADE_FAILURE:-true}"
CLEAN_RERUN="${CLEAN_RERUN:-false}"

UPGRADE_072_RELEASE_TAG="${UPGRADE_072_RELEASE_TAG:-v0.72.0-rc.2}"
UPGRADE_073_RELEASE_TAG="${UPGRADE_073_RELEASE_TAG:-0.73.0}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
APP_PROPS_072_FILE="${SCRIPT_DIR}/resources/0.72/application.properties"
APP_PROPS_073_FILE="${SCRIPT_DIR}/resources/0.73/application.properties"
APP_ENV_073_FILE="${SCRIPT_DIR}/resources/0.73/application.env"
LOG4J2_XML_PATH="${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml"

WORK_DIR="$(mktemp -d)"
SOLO_HOME_DIR="${HOME}/.solo"
BASELINE_SNAPSHOT_DIR="${BASELINE_SNAPSHOT_DIR:-${SOLO_HOME_DIR}/baselines/${SOLO_DEPLOYMENT}-${UPGRADE_072_RELEASE_TAG#v}-${CONSENSUS_NODE_COUNT}n}"
NODE_SCRIPT="${WORK_DIR}/sdk-crypto-create-check.js"
CN_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-cn.log"
CN_PORT_FORWARD_PID=""
UPGRADE_PID=""
CLUSTER_CREATED_THIS_RUN="false"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --clean-rerun)
        CLEAN_RERUN="true"
        ;;
      *)
        echo "Unknown argument: $1" >&2
        echo "Usage: $0 [--clean-rerun]" >&2
        exit 1
        ;;
    esac
    shift
  done
}

apply_run_mode_overrides() {
  if [[ "${CLEAN_RERUN}" == "true" ]]; then
    log "CLEAN_RERUN enabled: forcing fresh cluster/bootstrap and disabling baseline reuse for this run"
    RECREATE_CLUSTER="true"
    REUSE_BASELINE_SNAPSHOT="false"
  fi
}

build_local_project() {
  log "Building local project with ./gradlew clean assemble"
  (
    cd "${REPO_ROOT}"
    ./gradlew clean assemble
  )
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
  if [[ -n "${CN_PORT_FORWARD_PID}" ]]; then
    kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${UPGRADE_PID}" ]]; then
    kill "${UPGRADE_PID}" >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_NETWORK}" != "true" && "${CLUSTER_CREATED_THIS_RUN}" == "true" ]]; then
    kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
    cleanup_solo_state
  fi
  rm -rf "${WORK_DIR}" >/dev/null 2>&1 || true
  exit "${ec}"
}
trap cleanup EXIT

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command not found: $1" >&2
    exit 1
  }
}

wait_for_tcp_open() {
  local host="$1"
  local port="$2"
  local attempts="${3:-20}"
  local sleep_secs="${4:-1}"
  local i=1

  while (( i <= attempts )); do
    if nc -z "${host}" "${port}" >/dev/null 2>&1; then
      return 0
    fi
    sleep "${sleep_secs}"
    ((i++))
  done
  return 1
}

kill_processes_on_local_port() {
  local port="$1"
  local pids=""
  pids="$(lsof -ti "tcp:${port}" 2>/dev/null || true)"
  if [[ -n "${pids}" ]]; then
    kill "${pids}" >/dev/null 2>&1 || true
  fi
}

cleanup_stale_port_forwards() {
  pkill -f "port-forward svc/haproxy-node1-svc .*${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >/dev/null 2>&1 || true
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

detect_image_pull_failures() {
  kubectl -n "${SOLO_NAMESPACE}" get pods -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{range .status.initContainerStatuses[*]}{.state.waiting.reason}{" "}{end}{range .status.containerStatuses[*]}{.state.waiting.reason}{" "}{end}{"\n"}{end}' 2>/dev/null \
    | awk '$0 ~ /(ErrImagePull|ImagePullBackOff)/ {print $1 "\t" $0}'
}

describe_failed_image_pull_pods() {
  local failures="$1"
  local pod=""
  local seen=""

  while IFS=$'\t' read -r pod _; do
    [[ -n "${pod}" ]] || continue
    if [[ " ${seen} " == *" ${pod} "* ]]; then
      continue
    fi
    seen+=" ${pod}"
    log "kubectl describe pod/${pod} (image pull failure)"
    kubectl -n "${SOLO_NAMESPACE}" describe "pod/${pod}" >&2 || true
  done <<< "${failures}"
}

run_with_image_pull_monitor() {
  local context="$1"
  shift

  local cmd_pid=""
  local failures=""

  "$@" &
  cmd_pid="$!"

  while kill -0 "${cmd_pid}" >/dev/null 2>&1; do
    failures="$(detect_image_pull_failures || true)"
    if [[ -n "${failures}" ]]; then
      echo "Detected Kubernetes image pull failures during ${context}:" >&2
      printf '%s\n' "${failures}" >&2
      describe_failed_image_pull_pods "${failures}"
      kill "${cmd_pid}" >/dev/null 2>&1 || true
      wait "${cmd_pid}" >/dev/null 2>&1 || true
      return 1
    fi
    sleep 5
  done

  wait "${cmd_pid}"
}

kind_cluster_exists() {
  kind get clusters 2>/dev/null | grep -qx "${SOLO_CLUSTER_NAME}"
}

solo_namespace_exists() {
  kubectl get namespace "${SOLO_NAMESPACE}" >/dev/null 2>&1
}

baseline_snapshot_exists() {
  [[ -f "${BASELINE_SNAPSHOT_DIR}/metadata.env" ]]
}

local_config_file() {
  printf '%s\n' "${SOLO_HOME_DIR}/local-config.yaml"
}

local_config_has_cluster_ref() {
  local config_file=""
  config_file="$(local_config_file)"
  [[ -f "${config_file}" ]] || return 1
  rg -q "^[[:space:]]*kind-${SOLO_CLUSTER_NAME}:[[:space:]]+kind-${SOLO_CLUSTER_NAME}$" "${config_file}"
}

local_config_has_deployment() {
  local config_file=""
  config_file="$(local_config_file)"
  [[ -f "${config_file}" ]] || return 1
  rg -q "^[[:space:]]*name:[[:space:]]+${SOLO_DEPLOYMENT}$" "${config_file}"
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

configure_solo_deployment() {
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

setup_cluster_support_services() {
  solo cluster-ref config setup -s "${SOLO_CLUSTER_SETUP_NAMESPACE}" --prometheus-stack true
}

baseline_expected_implementation_version() {
  printf '%s\n' "${UPGRADE_072_RELEASE_TAG#v}"
}

consensus_nodes_match_version() {
  local expected_version="$1"
  local pod_version=""
  local node=""
  local pod=""
  local nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    pod_version="$(consensus_pod_implementation_version "${pod}" 2>/dev/null || true)"
    [[ "${pod_version}" == "${expected_version}" ]] || return 1
  done
}

baseline_network_is_reusable() {
  local expected_version=""
  local node=""
  local pod_ready=""
  local nodes=()

  solo_namespace_exists || return 1

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    kubectl -n "${SOLO_NAMESPACE}" get "pod/network-${node}-0" >/dev/null 2>&1 || return 1
    kubectl -n "${SOLO_NAMESPACE}" get "deployment/haproxy-${node}" >/dev/null 2>&1 || return 1
    pod_ready="$(kubectl -n "${SOLO_NAMESPACE}" get "pod/network-${node}-0" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
    [[ "${pod_ready}" == "True" ]] || return 1
  done

  expected_version="$(baseline_expected_implementation_version)"
  consensus_nodes_match_version "${expected_version}"
}

current_consensus_pod_uids_csv() {
  local node=""
  local pod=""
  local uid=""
  local nodes=()
  local uids=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    uid="$(kubectl -n "${SOLO_NAMESPACE}" get pod "${pod}" -o jsonpath='{.metadata.uid}')"
    [[ -n "${uid}" ]] || return 1
    uids+=("${uid}")
  done

  local joined=""
  local index=0
  for uid in "${uids[@]}"; do
    if (( index > 0 )); then
      joined+=","
    fi
    joined+="${uid}"
    ((index += 1))
  done
  printf '%s\n' "${joined}"
}

wait_for_consensus_pod_recreation() {
  local original_uids_csv="$1"
  local timeout_secs="${2:-600}"
  local deadline=$((SECONDS + timeout_secs))
  local node=""
  local pod=""
  local current_uid=""
  local nodes=()
  local original_uids=()
  local index=0
  local all_changed=0

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  IFS=',' read -r -a original_uids <<< "${original_uids_csv}"

  while (( SECONDS < deadline )); do
    all_changed=1
    index=0
    for node in "${nodes[@]}"; do
      pod="network-${node}-0"
      current_uid="$(kubectl -n "${SOLO_NAMESPACE}" get pod "${pod}" -o jsonpath='{.metadata.uid}' 2>/dev/null || true)"
      if [[ -z "${current_uid}" || "${current_uid}" == "${original_uids[${index}]:-}" ]]; then
        all_changed=0
        break
      fi
      ((index += 1))
    done

    if (( all_changed == 1 )); then
      log "Consensus pods were recreated during the 0.73 upgrade"
      return 0
    fi
    sleep 5
  done

  echo "Timed out waiting for consensus pod recreation during the 0.73 upgrade" >&2
  return 1
}

stage_local_build_on_consensus_nodes() {
  local node=""
  local pod=""
  local nodes=()
  local remote_data_dir="/opt/hgcapp/services-hedera/HapiApp2.0/data"

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    log "Restaging local build apps/lib on ${pod}"
    kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "find '${remote_data_dir}/apps' -mindepth 1 -maxdepth 1 -exec rm -rf {} + && \
       find '${remote_data_dir}/lib' -mindepth 1 -maxdepth 1 -exec rm -rf {} +"
    tar --disable-copyfile --no-mac-metadata --format ustar -cf - -C "${LOCAL_BUILD_PATH}" apps lib \
      | kubectl -n "${SOLO_NAMESPACE}" exec -i "${pod}" -c root-container -- sh -lc \
        "tar -xf - -C '${remote_data_dir}'"
  done
}

restart_consensus_pods_to_pick_up_staged_build() {
  local node=""
  local pod=""
  local nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    log "Deleting ${pod} so it restarts with refreshed filesystem contents"
    kubectl -n "${SOLO_NAMESPACE}" delete pod "${pod}" --wait=false
  done
}

restart_port_forwards() {
  log "Starting/restarting consensus gRPC port-forward"
  if [[ -n "${CN_PORT_FORWARD_PID}" ]]; then
    kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    CN_PORT_FORWARD_PID=""
  fi

  cleanup_stale_port_forwards
  kill_processes_on_local_port "${CN_GRPC_LOCAL_PORT}"
  sleep 1

  kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >"${CN_PORT_FORWARD_LOG}" 2>&1 &
  CN_PORT_FORWARD_PID="$!"
  sleep 2

  wait_for_tcp_open "127.0.0.1" "${CN_GRPC_LOCAL_PORT}" 20 1 || {
    tail -n 80 "${CN_PORT_FORWARD_LOG}" >&2 || true
    echo "Consensus gRPC port-forward did not become reachable" >&2
    return 1
  }
}

write_sdk_verifier() {
  cat > "${NODE_SCRIPT}" <<'EOF'
const { Client, AccountCreateTransaction, PrivateKey, Hbar, Status } = require("@hashgraph/sdk");

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

  console.log(`PASS: crypto create succeeded with account ${accountId}`);
  await client.close();
}

main().catch((err) => {
  console.error(`FAIL: ${err.message}`);
  process.exit(1);
});
EOF
}

prepare_js_sdk_runtime() {
  write_sdk_verifier
  (
    cd "${WORK_DIR}"
    npm init -y >/dev/null 2>&1
    npm install --no-fund --no-audit @hashgraph/sdk >/dev/null 2>&1
  )
  export GRPC_ENDPOINT="127.0.0.1:${CN_GRPC_LOCAL_PORT}"
  export OPERATOR_ACCOUNT_ID
  export OPERATOR_PRIVATE_KEY
}

validate_local_build_path() {
  local base="$1"
  [[ -f "${base}/apps/HederaNode.jar" ]] || return 1
  [[ -d "${base}/lib" ]] || return 1
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

deploy_baseline_072() {
  local deploy_cmd=()

  log "Deploying baseline ${UPGRADE_072_RELEASE_TAG} consensus network"
  solo keys consensus generate --gossip-keys --tls-keys --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
  deploy_cmd=(
    solo consensus network deploy
    --deployment "${SOLO_DEPLOYMENT}"
    -i "${NODE_ALIASES}"
    --application-properties "${APP_PROPS_072_FILE}"
    --log4j2-xml "${LOG4J2_XML_PATH}"
    --service-monitor true
    --pod-log true
    --pvcs true
    --release-tag "${UPGRADE_072_RELEASE_TAG}"
  )
  run_with_image_pull_monitor "baseline ${UPGRADE_072_RELEASE_TAG} deploy" "${deploy_cmd[@]}"
  solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --release-tag "${UPGRADE_072_RELEASE_TAG}"
  solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
}

capture_baseline_snapshot() {
  local node=""
  local pod=""
  local nodes=()
  local snapshot_tar=""

  mkdir -p "${BASELINE_SNAPSHOT_DIR}"
  rm -f "${BASELINE_SNAPSHOT_DIR}"/*.tar

  log "Capturing reusable ${UPGRADE_072_RELEASE_TAG} baseline snapshot into ${BASELINE_SNAPSHOT_DIR}"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    snapshot_tar="${BASELINE_SNAPSHOT_DIR}/${pod}.tar"
    kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "tar -cf - -C / \
        opt/hgcapp/services-hedera/HapiApp2.0/data \
        opt/hgcapp/services-hedera/HapiApp2.0/state \
        opt/hgcapp/services-hedera/HapiApp2.0/output \
        opt/hgcapp/recordStreams \
        opt/hgcapp/blockStreams \
        opt/hgcapp/eventsStreams" > "${snapshot_tar}"
  done

  cat > "${BASELINE_SNAPSHOT_DIR}/metadata.env" <<EOF
SOLO_CLUSTER_NAME=${SOLO_CLUSTER_NAME}
SOLO_DEPLOYMENT=${SOLO_DEPLOYMENT}
SOLO_NAMESPACE=${SOLO_NAMESPACE}
NODE_ALIASES=${NODE_ALIASES}
UPGRADE_072_RELEASE_TAG=${UPGRADE_072_RELEASE_TAG}
EOF
}

restore_snapshot_on_consensus_nodes() {
  local node=""
  local pod=""
  local nodes=()
  local snapshot_tar=""

  baseline_snapshot_exists || {
    echo "Baseline snapshot is missing from ${BASELINE_SNAPSHOT_DIR}" >&2
    return 1
  }

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    snapshot_tar="${BASELINE_SNAPSHOT_DIR}/${pod}.tar"
    [[ -f "${snapshot_tar}" ]] || {
      echo "Missing baseline snapshot tarball: ${snapshot_tar}" >&2
      return 1
    }
    log "Restoring baseline snapshot into ${pod}"
    kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "mkdir -p \
         /opt/hgcapp/services-hedera/HapiApp2.0/data \
         /opt/hgcapp/services-hedera/HapiApp2.0/state \
         /opt/hgcapp/services-hedera/HapiApp2.0/output \
         /opt/hgcapp/recordStreams \
         /opt/hgcapp/blockStreams \
         /opt/hgcapp/eventsStreams && \
       find /opt/hgcapp/services-hedera/HapiApp2.0/data -mindepth 1 -exec rm -rf {} + && \
       find /opt/hgcapp/services-hedera/HapiApp2.0/state -mindepth 1 -exec rm -rf {} + && \
       find /opt/hgcapp/services-hedera/HapiApp2.0/output -mindepth 1 -exec rm -rf {} + && \
       find /opt/hgcapp/recordStreams -mindepth 1 -exec rm -rf {} + && \
       find /opt/hgcapp/blockStreams -mindepth 1 -exec rm -rf {} + && \
       find /opt/hgcapp/eventsStreams -mindepth 1 -exec rm -rf {} +"
    kubectl -n "${SOLO_NAMESPACE}" exec -i "${pod}" -c root-container -- sh -lc "tar -xf - -C /" < "${snapshot_tar}"
  done
}

restore_baseline_from_snapshot() {
  baseline_snapshot_exists || {
    echo "No reusable ${UPGRADE_072_RELEASE_TAG} baseline snapshot found at ${BASELINE_SNAPSHOT_DIR}" >&2
    return 1
  }

  log "Restoring reusable ${UPGRADE_072_RELEASE_TAG} baseline snapshot"
  if solo_namespace_exists; then
    kubectl delete namespace "${SOLO_NAMESPACE}" --wait=true >/dev/null 2>&1 || true
    while kubectl get namespace "${SOLO_NAMESPACE}" >/dev/null 2>&1; do
      sleep 2
    done
  fi

  deploy_baseline_072
  restore_snapshot_on_consensus_nodes
  restart_consensus_pods_to_pick_up_staged_build
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  consensus_nodes_match_version "$(baseline_expected_implementation_version)"
}

attempt_073_upgrade() {
  local upgrade_cmd=()
  local original_uids_csv=""

  restart_port_forwards
  prepare_js_sdk_runtime

  log "Upgrading consensus network to local build with TSS-enabled 0.73 application.properties"
  upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --local-build-path "${LOCAL_BUILD_PATH}"
    --application-properties "${APP_PROPS_073_FILE}"
    --application-env "${APP_ENV_073_FILE}"
    --force
  )

  original_uids_csv="$(current_consensus_pod_uids_csv)"

  "${upgrade_cmd[@]}" &
  UPGRADE_PID="$!"

  if ! wait_for_consensus_pod_recreation "${original_uids_csv}" 600; then
    wait "${UPGRADE_PID}" || true
    UPGRADE_PID=""
    return 1
  fi

  wait_for_consensus_pods_ready 600
  stage_local_build_on_consensus_nodes
  restart_consensus_pods_to_pick_up_staged_build

  wait "${UPGRADE_PID}"
  UPGRADE_PID=""

  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  restart_port_forwards
  verify_local_build_on_consensus_nodes

  log "Post-upgrade check: crypto create on local 0.73 build with TSS enabled"
  node "${NODE_SCRIPT}"
}

log "Validating prerequisites"
parse_args "$@"
apply_run_mode_overrides
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd node
require_cmd npm
require_cmd unzip
require_cmd nc
require_cmd lsof

[[ -f "${APP_PROPS_072_FILE}" ]] || { echo "Missing file: ${APP_PROPS_072_FILE}" >&2; exit 1; }
[[ -f "${APP_PROPS_073_FILE}" ]] || { echo "Missing file: ${APP_PROPS_073_FILE}" >&2; exit 1; }
[[ -f "${APP_ENV_073_FILE}" ]] || { echo "Missing file: ${APP_ENV_073_FILE}" >&2; exit 1; }
[[ -f "${LOG4J2_XML_PATH}" ]] || { echo "Missing file: ${LOG4J2_XML_PATH}" >&2; exit 1; }
[[ -x "${REPO_ROOT}/gradlew" ]] || { echo "Missing executable gradlew: ${REPO_ROOT}/gradlew" >&2; exit 1; }

build_local_project

validate_local_build_path "${LOCAL_BUILD_PATH}" || {
  echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}" >&2
  echo "Expected ${LOCAL_BUILD_PATH}/apps/HederaNode.jar and ${LOCAL_BUILD_PATH}/lib" >&2
  exit 1
}

cleanup_stale_port_forwards

if [[ "${RECREATE_CLUSTER}" == "true" ]] || ! kind_cluster_exists; then
  log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  cleanup_solo_state

  log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
  kind create cluster -n "${SOLO_CLUSTER_NAME}"
  CLUSTER_CREATED_THIS_RUN="true"

  configure_solo_deployment
  setup_cluster_support_services
else
  log "Reusing existing Kind cluster ${SOLO_CLUSTER_NAME}"
  configure_solo_deployment
fi

if [[ "${REUSE_BASELINE_SNAPSHOT}" == "true" ]] && baseline_network_is_reusable; then
  log "Reusing healthy ${UPGRADE_072_RELEASE_TAG} baseline already running in ${SOLO_NAMESPACE}"
  if [[ "${REUSE_BASELINE_SNAPSHOT}" == "true" ]] && ! baseline_snapshot_exists; then
    capture_baseline_snapshot
  fi
elif [[ "${REUSE_BASELINE_SNAPSHOT}" == "true" ]] && baseline_snapshot_exists; then
  restore_baseline_from_snapshot
else
  deploy_baseline_072
  if [[ "${REUSE_BASELINE_SNAPSHOT}" == "true" ]]; then
    capture_baseline_snapshot
  fi
fi

if ! attempt_073_upgrade; then
  if [[ "${ROLLBACK_ON_UPGRADE_FAILURE}" == "true" ]] && baseline_snapshot_exists; then
    log "0.73 upgrade failed; restoring ${UPGRADE_072_RELEASE_TAG} baseline snapshot"
    restore_baseline_from_snapshot
  fi
  exit 1
fi

log "PASS: 0.72 -> local 0.73 TSS-enabled upgrade smoke test completed"
