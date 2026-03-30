#!/usr/bin/env bash

set -Eeo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../.." && pwd)"

export SOLO_CLUSTER_NAME="solo"
export SOLO_NAMESPACE="solo"
export SOLO_CLUSTER_SETUP_NAMESPACE="solo-cluster"
export SOLO_DEPLOYMENT="solo-deployment"
export MIRROR_NODE_VERSION="v0.149.0"
export BLOCK_NODE_VERSION="v0.28.1"
NODE_ALIASES="node1,node2,node3,node4"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
LOG4J2_XML_PATH="${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml"

CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
GRAFANA_LOCAL_PORT="${GRAFANA_LOCAL_PORT:-3000}"
OUTAGE_WAIT_SECONDS="${OUTAGE_WAIT_SECONDS:-600}"
RECOVERY_WAIT_SECONDS="${RECOVERY_WAIT_SECONDS:-60}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"

WORK_DIR="$(mktemp -d)"
NODE_SCRIPT="${WORK_DIR}/sdk-crypto-create-check.js"
APP_PROPS_FILE="${SCRIPT_DIR}/resources/application.properties"
CN_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-cn.log"
MIRROR_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-mirror.log"
GRAFANA_PORT_FORWARD_LOG="${WORK_DIR}/port-forward-grafana.log"
ARTIFACT_DIR="${ARTIFACT_DIR:-${WORK_DIR}/artifacts}"
RUN_LOG="${ARTIFACT_DIR}/run.log"
SOLO_HOME="${SOLO_HOME:-${WORK_DIR}/solo-home}"
DIAGNOSTICS_DIR="${ARTIFACT_DIR}/diagnostics"
DIAGNOSTICS_COLLECTED="false"

CN_PORT_FORWARD_PID=""
MIRROR_PORT_FORWARD_PID=""
GRAFANA_PORT_FORWARD_PID=""

mkdir -p "${ARTIFACT_DIR}" "${SOLO_HOME}" "${DIAGNOSTICS_DIR}"
export SOLO_HOME

exec > >(tee -a "${RUN_LOG}") 2>&1

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

collect_diagnostics() {
  if [[ "${DIAGNOSTICS_COLLECTED}" == "true" ]]; then
    return
  fi
  DIAGNOSTICS_COLLECTED="true"

  local kubectl_dir="${DIAGNOSTICS_DIR}/kubectl"
  local solo_dir="${DIAGNOSTICS_DIR}/solo"
  mkdir -p "${kubectl_dir}" "${solo_dir}"

  log "Collecting diagnostics into ${DIAGNOSTICS_DIR}"
  set +e

  kubectl get pods -A -o wide > "${kubectl_dir}/pods-all-namespaces.txt" 2>&1 || true
  kubectl get events -A --sort-by=.metadata.creationTimestamp > "${kubectl_dir}/events-all-namespaces.txt" 2>&1 || true
  kubectl -n "${SOLO_NAMESPACE}" get all > "${kubectl_dir}/resources-${SOLO_NAMESPACE}.txt" 2>&1 || true
  kubectl -n "${SOLO_NAMESPACE}" describe pods > "${kubectl_dir}/describe-pods-${SOLO_NAMESPACE}.txt" 2>&1 || true

  while IFS= read -r pod_name; do
    [[ -z "${pod_name}" ]] && continue
    safe_pod_name="${pod_name//\//_}"
    kubectl -n "${SOLO_NAMESPACE}" describe "${pod_name}" > "${kubectl_dir}/describe-${safe_pod_name}.txt" 2>&1 || true
    containers="$(kubectl -n "${SOLO_NAMESPACE}" get "${pod_name}" -o jsonpath='{.spec.containers[*].name}' 2>/dev/null || true)"
    for container_name in ${containers}; do
      kubectl -n "${SOLO_NAMESPACE}" logs "${pod_name}" -c "${container_name}" --since=24h --timestamps \
        > "${kubectl_dir}/${safe_pod_name}-${container_name}.log" 2>&1 || true
    done
  done < <(kubectl -n "${SOLO_NAMESPACE}" get pods -o name 2>/dev/null || true)

  solo deployment diagnostics logs --deployment "${SOLO_DEPLOYMENT}" > "${solo_dir}/solo-diagnostics-logs.txt" 2>&1 \
    || solo deployment diagnostics all --deployment "${SOLO_DEPLOYMENT}" > "${solo_dir}/solo-diagnostics-all.txt" 2>&1 \
    || true

  if [[ -d "${SOLO_HOME}/logs" ]]; then
    cp -R "${SOLO_HOME}/logs" "${solo_dir}/solo-home-logs" >/dev/null 2>&1 || true
  fi

  cp "${CN_PORT_FORWARD_LOG}" "${ARTIFACT_DIR}/" >/dev/null 2>&1 || true
  cp "${MIRROR_PORT_FORWARD_LOG}" "${ARTIFACT_DIR}/" >/dev/null 2>&1 || true
  cp "${GRAFANA_PORT_FORWARD_LOG}" "${ARTIFACT_DIR}/" >/dev/null 2>&1 || true

  log "Diagnostics collection complete"
}

cleanup() {
  local exit_code=$?

  if [[ ${exit_code} -ne 0 ]]; then
    collect_diagnostics
    log "Artifacts preserved at: ${ARTIFACT_DIR}"
    log "Script failed (exit=${exit_code}); preserving port-forwards and cluster for debugging"
    return
  fi

  if [[ "${KEEP_NETWORK}" == "true" ]]; then
    log "Artifacts preserved at: ${ARTIFACT_DIR}"
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

scale_block_node() {
  local name="$1"
  local replicas="$2"
  if kubectl -n "${SOLO_NAMESPACE}" get deployment "${name}" >/dev/null 2>&1; then
    kubectl -n "${SOLO_NAMESPACE}" scale deployment "${name}" --replicas="${replicas}"
  elif kubectl -n "${SOLO_NAMESPACE}" get statefulset "${name}" >/dev/null 2>&1; then
    kubectl -n "${SOLO_NAMESPACE}" scale statefulset "${name}" --replicas="${replicas}"
  else
    echo "Could not find deployment/statefulset for ${name} in namespace ${SOLO_NAMESPACE}" >&2
    return 1
  fi
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

function formatErrorDetails(err) {
  if (!err || typeof err !== "object") {
    return `error=${String(err)}`;
  }

  const details = {
    name: err.name ?? null,
    message: err.message ?? null,
    status: err.status?.toString?.() ?? err.status ?? null,
    transactionId: err.transactionId?.toString?.() ?? err.transactionId ?? null,
    nodeId: err.nodeId?.toString?.() ?? err.nodeId ?? null,
    causeName: err.cause?.name ?? null,
    causeMessage: err.cause?.message ?? null,
  };

  const rawKeys = Object.keys(err).sort();
  return JSON.stringify({ details, rawKeys }, null, 2);
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
  const expected = process.argv[2];
  if (!expected || (expected !== "expect-success" && expected !== "expect-failure")) {
    throw new Error("Usage: node sdk-crypto-create-check.js <expect-success|expect-failure>");
  }

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

  let receipt = null;
  try {
    const tx = new AccountCreateTransaction()
      .setInitialBalance(new Hbar(1))
      .setKey(PrivateKey.generateED25519().publicKey)
      .setMaxTransactionFee(new Hbar(5));
    const response = await tx.execute(client);
    receipt = await response.getReceipt(client);
  } catch (err) {
    console.error(`SDK error details:\n${formatErrorDetails(err)}`);
    if (err?.stack) {
      console.error(`SDK error stack:\n${err.stack}`);
    }
    if (expected === "expect-failure") {
      console.log(`PASS: crypto create failed as expected (${err.message})`);
      await client.close();
      return;
    }
    throw new Error(`Expected success but transaction failed: ${err.message}`);
  }

  if (expected === "expect-failure") {
    throw new Error("Expected failure but crypto create succeeded");
  }
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
solo block node add --deployment "${SOLO_DEPLOYMENT}"
solo block node add --deployment "${SOLO_DEPLOYMENT}"

log "Deploying consensus network with application.properties overrides"
solo keys consensus generate --gossip-keys --tls-keys --deployment "${SOLO_DEPLOYMENT}" -i node1,node2,node3,node4
solo consensus network deploy --deployment "${SOLO_DEPLOYMENT}" -i node1,node2,node3,node4 --application-properties "${APP_PROPS_FILE}" --log4j2-xml "${LOG4J2_XML_PATH}" --service-monitor true --pod-log true --release-tag v0.72.0-alpha.4
solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" -i node1,node2,node3,node4 --local-build-path "${LOCAL_BUILD_PATH}"
solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i node1,node2,node3,node4

fix_consensus_metrics_scrape_config

#log "Deploying mirror node, JSON-RPC relay, and explorer"
solo mirror node add --deployment "${SOLO_DEPLOYMENT}" --enable-ingress --pinger --force
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

log "Step 1: submitting crypto create; expecting success and mirror visibility"
node "${NODE_SCRIPT}" expect-success

log "Step 2: stopping both block nodes"
scale_block_node "block-node-1" 0
scale_block_node "block-node-2" 0

log "Waiting ${OUTAGE_WAIT_SECONDS}s with block nodes down"
sleep "${OUTAGE_WAIT_SECONDS}"

log "Step 3: submitting crypto create; expecting failure while both block nodes are down"
node "${NODE_SCRIPT}" expect-failure

log "Step 4: starting both block nodes again"
scale_block_node "block-node-1" 1
scale_block_node "block-node-2" 1

log "Waiting ${RECOVERY_WAIT_SECONDS}s for block node recovery"
sleep "${RECOVERY_WAIT_SECONDS}"

log "Step 5: submitting crypto create after recovery; expecting success"
node "${NODE_SCRIPT}" expect-success

log "Scenario complete: PASS"
log "Artifacts saved to: ${ARTIFACT_DIR}"
