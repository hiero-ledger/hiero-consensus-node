#!/usr/bin/env bash

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../.." && pwd)"

export SOLO_CLUSTER_NAME="solo"
export SOLO_NAMESPACE="solo"
export SOLO_CLUSTER_SETUP_NAMESPACE="solo-cluster"
export SOLO_DEPLOYMENT="solo-deployment"
NODE_ALIASES="node1,node2,node3,node4"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
LOG4J2_XML_PATH="${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml"
APP_PROPS_071_FILE="${SCRIPT_DIR}/resources/0.71/application.properties"
APP_PROPS_072_FILE="${SCRIPT_DIR}/resources/0.72/application.properties"
INITIAL_RELEASE_TAG="${INITIAL_RELEASE_TAG:-v0.71.2}"
UPGRADE_072_RELEASE_TAG="${UPGRADE_072_RELEASE_TAG:-v0.72.0-rc.2}"

CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
GRAFANA_LOCAL_PORT="${GRAFANA_LOCAL_PORT:-3000}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"

WORK_DIR="$(mktemp -d)"
NODE_SCRIPT="${WORK_DIR}/sdk-crypto-create-check.js"
CN_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-cn.log"
MIRROR_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-mirror.log"
GRAFANA_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-grafana.log"

CN_PORT_FORWARD_PID=""
MIRROR_PORT_FORWARD_PID=""
GRAFANA_PORT_FORWARD_PID=""

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
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
  local nodes=(node1 node2 node3 node4)

  for pod in "${nodes[@]}"; do
    log "Waiting for network-${pod}-0 to become Ready"
    kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/network-${pod}-0" --timeout="${timeout_secs}s"
  done
}

wait_for_haproxy_ready() {
  local timeout_secs="${1:-600}"
  local proxy
  local proxies=(haproxy-node1 haproxy-node2 haproxy-node3 haproxy-node4)

  for proxy in "${proxies[@]}"; do
    log "Waiting for ${proxy} rollout to become ready"
    kubectl -n "${SOLO_NAMESPACE}" rollout status "deployment/${proxy}" --timeout="${timeout_secs}s"
  done
}

restart_mirror_importer() {
  if ! kubectl -n "${SOLO_NAMESPACE}" get deployment mirror-1-importer >/dev/null 2>&1; then
    log "mirror-1-importer deployment not found; skipping importer restart"
    return 0
  fi

  log "Restarting mirror-1-importer to reload stream credentials"
  kubectl -n "${SOLO_NAMESPACE}" rollout restart deployment/mirror-1-importer
  kubectl -n "${SOLO_NAMESPACE}" rollout status deployment/mirror-1-importer --timeout=300s
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

  await ensureAccountVisibleInMirror(mirrorUrl, accountId);
  console.log(`PASS: crypto create succeeded and mirror sees account ${accountId}`);
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
if ! validate_local_build_path "${LOCAL_BUILD_PATH}"; then
  echo "Invalid LOCAL_BUILD_PATH content: ${LOCAL_BUILD_PATH}" >&2
  echo "Expected jar artifacts under both data/lib and data/apps (run ./gradlew assemble)." >&2
  exit 1
fi

log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
kind create cluster -n "${SOLO_CLUSTER_NAME}"

log "Configuring Solo deployment"
solo init
solo cluster-ref config connect --cluster-ref kind-${SOLO_CLUSTER_NAME} --context kind-${SOLO_CLUSTER_NAME}
solo deployment config create -n "${SOLO_NAMESPACE}" --deployment "${SOLO_DEPLOYMENT}"
solo deployment cluster attach --deployment "${SOLO_DEPLOYMENT}" --cluster-ref kind-${SOLO_CLUSTER_NAME} --num-consensus-nodes 4
solo cluster-ref config setup -s "${SOLO_CLUSTER_SETUP_NAMESPACE}" --prometheus-stack true --grafana-agent true
start_grafana_port_forward

log "Deploying consensus network at ${INITIAL_RELEASE_TAG} with 0.71 application.properties"
solo keys consensus generate --gossip-keys --tls-keys --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
solo consensus network deploy --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --application-properties "${APP_PROPS_071_FILE}" --log4j2-xml "${LOG4J2_XML_PATH}" --service-monitor true --pod-log true --pvcs true --release-tag "${INITIAL_RELEASE_TAG}"
solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --release-tag "${INITIAL_RELEASE_TAG}"
solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
wait_for_consensus_pods_ready 600
wait_for_haproxy_ready 600

fix_consensus_metrics_scrape_config

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
cd "${WORK_DIR}"
npm init -y >/dev/null 2>&1
npm install --no-fund --no-audit @hashgraph/sdk >/dev/null 2>&1

export GRPC_ENDPOINT="127.0.0.1:${CN_GRPC_LOCAL_PORT}"
export MIRROR_REST_URL="http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
export OPERATOR_ACCOUNT_ID
export OPERATOR_PRIVATE_KEY

log "Step 1: submit crypto create; expect success and mirror visibility"
node "${NODE_SCRIPT}"

log "Waiting 120s after Step 1"
sleep 120

log "Step 2: Upgrade CN network to 0.72 (target ${UPGRADE_072_RELEASE_TAG})"
solo consensus network upgrade --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" --upgrade-version "${UPGRADE_072_RELEASE_TAG}" --quiet-mode --force

log "Step 3: apply ${UPGRADE_072_RELEASE_TAG} application.properties overrides for cutover flow"
solo consensus network deploy --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --application-properties "${APP_PROPS_072_FILE}" --log4j2-xml "${LOG4J2_XML_PATH}" --service-monitor true --pod-log true --pvcs true --release-tag "${UPGRADE_072_RELEASE_TAG}"
fix_consensus_metrics_scrape_config
#restart_mirror_importer

log "Step 4: setup upgraded consensus node binaries after redeploy"
solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --release-tag "${UPGRADE_072_RELEASE_TAG}" --quiet-mode

log "Step 5: ensure upgraded consensus nodes are started and ready"
solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
wait_for_consensus_pods_ready 900
wait_for_haproxy_ready 900

log "Step 6: verify post-upgrade crypto create and mirror visibility"
node "${NODE_SCRIPT}"

log "Cutover phase complete: PASS"
