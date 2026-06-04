#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Isolated reproducer for the 0.75 -> 0.76 (TSS enablement) upgrade that
# corresponds to step 10 of solo-e2e-block-stream-cutover.sh. The full cutover
# script takes 30+ minutes and exercises Block Node + jumpstart + mirror node
# before reaching the failing TSS-enabling upgrade. This script skips all of
# that machinery and goes straight to:
#
#   1. Deploy a baseline CN network at the published v0.75.0-rc.1 release tag
#      with resources/0.75/application.properties. Solo's `consensus network
#      deploy` does NOT accept --local-build-path (only the upgrade command
#      does), so the baseline binaries come from the registry.
#   2. Upgrade in place to the local build (0.75-SNAPSHOT) with
#      resources/0.76/application.properties + application.env, enabling TSS
#      with tss.forceMockSignatures=true. The upgrade-version label must be a
#      published Solo tag (Solo fetches it before applying --local-build-path),
#      so we reuse v0.75.0-rc.1 — same as deploy — since no newer RC is
#      published yet.
#
# WRAPS handling mirrors step 10 of the main cutover script:
#   - download wraps-v1.0.0.tar.gz into a local cache
#   - run an nginx docker container exposing the tarball at
#     http://host.docker.internal:8089/
#   - inject TSS_LIB_WRAPS_ARTIFACTS_PATH into each StatefulSet's container
#     spec BEFORE solo's upgrade fires (lockstep WRAPS init across nodes)
#
# No Block Node, no jumpstart, no mirror node, no port-forwards.

set -euo pipefail
set +m

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../../" && pwd)"

SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-solo-tss-upgrade}"
SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-tss-upgrade}"
SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-setup}"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3,node4}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

# Initial deploy pulls a real published binary at this release tag.
# Solo's `consensus network deploy` does not accept --local-build-path.
DEPLOY_RELEASE_TAG="${DEPLOY_RELEASE_TAG:-v0.75.0-rc.1}"

# Upgrade uses the local build (0.75-SNAPSHOT). The label must point at a
# published Solo tag (Solo fetches it before applying --local-build-path).
# Reusing v0.75.0-rc.1 because no newer RC of 0.75.0 is published yet.
UPGRADE_VERSION_LABEL="${UPGRADE_VERSION_LABEL:-v0.75.0-rc.1}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"

WRAPS_KEY_PATH="${WRAPS_KEY_PATH:-${HOME}/.solo/cache/wraps-v1.0.0}"
WRAPS_TARBALL_CACHE_PATH="${WRAPS_TARBALL_CACHE_PATH:-${HOME}/.solo/cache/wraps-v1.0.0.tar.gz}"
WRAPS_ARTIFACTS_DOWNLOAD_URL="${WRAPS_ARTIFACTS_DOWNLOAD_URL:-https://builds.hedera.com/tss/hiero/wraps/v1.0/wraps-v1.0.0.tar.gz}"
WRAPS_REQUIRED_FILE_COUNT="${WRAPS_REQUIRED_FILE_COUNT:-4}"
WRAPS_SERVER_PORT="${WRAPS_SERVER_PORT:-8089}"
WRAPS_SERVER_CONTAINER_NAME="${WRAPS_SERVER_CONTAINER_NAME:-wraps-proving-key-server}"

APP_PROPS_075_FILE="${APP_PROPS_075_FILE:-${SCRIPT_DIR}/resources/0.75/application.properties}"
APP_PROPS_076_FILE="${APP_PROPS_076_FILE:-${SCRIPT_DIR}/resources/0.76/application.properties}"
APP_ENV_076_FILE="${APP_ENV_076_FILE:-${SCRIPT_DIR}/resources/0.76/application.env}"
LOG4J2_XML_PATH="${LOG4J2_XML_PATH:-${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml}"
HAPI_PATH="/opt/hgcapp/services-hedera/HapiApp2.0"
WRAPS_ARTIFACTS_CONTAINER_DIR_DEFAULT="${HAPI_PATH}/data/keys/wraps"

SOLO_UPGRADE_TIMEOUT_SECS="${SOLO_UPGRADE_TIMEOUT_SECS:-1800}"

# Local port-forward + operator key used by the post-upgrade tx nudge that
# pushes consensus rounds past the genesis WRAPS CRS-adoption stall.
CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"
NUDGE_TX_COUNT="${NUDGE_TX_COUNT:-5}"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/solo-e2e-075-to-076.XXXXXX")"
NUDGE_SCRIPT="${WORK_DIR}/nudge-consensus.js"
CN_PORT_FORWARD_LOG="${WORK_DIR}/cn-port-forward.log"
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

stop_wraps_proving_key_server() {
  if command -v docker >/dev/null 2>&1; then
    docker rm -f "${WRAPS_SERVER_CONTAINER_NAME}" >/dev/null 2>&1 || true
  fi
}

cleanup() {
  local ec=$?
  stop_wraps_proving_key_server
  if [[ "${KEEP_NETWORK}" != "true" && "${CLUSTER_CREATED_THIS_RUN}" == "true" ]]; then
    kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  fi
  rm -rf "${WORK_DIR}" >/dev/null 2>&1 || true
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
  local node nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    log "Waiting for network-${node}-0 to become Ready"
    kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/network-${node}-0" --timeout="${timeout_secs}s"
  done
}

wait_for_haproxy_ready() {
  local timeout_secs="${1:-600}"
  local node nodes=()

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    log "Waiting for haproxy-${node} rollout to become ready"
    kubectl -n "${SOLO_NAMESPACE}" rollout status "deployment/haproxy-${node}" --timeout="${timeout_secs}s"
  done
}

# Same shape as ensure_wraps_artifacts_downloaded in the main cutover script:
# cache the tarball alongside the extracted dir so step 10's local nginx server
# can serve it without re-downloading.
ensure_wraps_artifacts_downloaded() {
  local file_count="" tmp_dir="" extract_dir="" extracted_root=""
  local extracted_dirs="" extracted_entries=""

  if [[ -d "${WRAPS_KEY_PATH}" ]]; then
    file_count="$(find "${WRAPS_KEY_PATH}" -maxdepth 1 -type f | wc -l | tr -d ' ')"
    if [[ "${file_count}" -ge "${WRAPS_REQUIRED_FILE_COUNT}" && -f "${WRAPS_TARBALL_CACHE_PATH}" ]]; then
      log "Using cached WRAPS artifacts from ${WRAPS_KEY_PATH}"
      return 0
    fi
  fi

  mkdir -p "$(dirname "${WRAPS_TARBALL_CACHE_PATH}")"
  if [[ ! -f "${WRAPS_TARBALL_CACHE_PATH}" ]]; then
    log "Downloading WRAPS artifacts from ${WRAPS_ARTIFACTS_DOWNLOAD_URL}"
    curl -fL "${WRAPS_ARTIFACTS_DOWNLOAD_URL}" -o "${WRAPS_TARBALL_CACHE_PATH}.partial"
    mv "${WRAPS_TARBALL_CACHE_PATH}.partial" "${WRAPS_TARBALL_CACHE_PATH}"
  fi

  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/solo-wraps-extract.XXXXXX")"
  extract_dir="${tmp_dir}/extract"
  mkdir -p "${extract_dir}"
  tar -xzf "${WRAPS_TARBALL_CACHE_PATH}" -C "${extract_dir}"

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

ensure_wraps_proving_key_server() {
  local server_url
  server_url="http://127.0.0.1:${WRAPS_SERVER_PORT}/$(basename "${WRAPS_TARBALL_CACHE_PATH}")"

  if curl -sfI "${server_url}" >/dev/null 2>&1; then
    log "Wraps proving key server already serving ${server_url}"
    return 0
  fi

  require_cmd docker
  if [[ ! -f "${WRAPS_TARBALL_CACHE_PATH}" ]]; then
    echo "Wraps tarball cache not found: ${WRAPS_TARBALL_CACHE_PATH}" >&2
    return 1
  fi

  log "Starting wraps proving key server (nginx Docker on port ${WRAPS_SERVER_PORT})"
  WRAPS_TAR_PATH="${WRAPS_TARBALL_CACHE_PATH}" \
  WRAPS_SERVER_PORT="${WRAPS_SERVER_PORT}" \
  WRAPS_SERVER_CONTAINER_NAME="${WRAPS_SERVER_CONTAINER_NAME}" \
    "${SCRIPT_DIR}/start-wraps-proving-key-server.sh"
}

configured_wraps_artifacts_container_dir() {
  local configured=""
  configured="$(sed -n 's/^TSS_LIB_WRAPS_ARTIFACTS_PATH=//p' "${APP_ENV_076_FILE}" | head -n 1)"
  if [[ -n "${configured}" ]]; then
    printf '%s\n' "${configured}"
  else
    printf '%s\n' "${WRAPS_ARTIFACTS_CONTAINER_DIR_DEFAULT}"
  fi
}

# Pre-inject TSS_LIB_WRAPS_ARTIFACTS_PATH into every consensus StatefulSet's
# container spec BEFORE Solo's upgrade. Solo's --application-env drops the env
# file onto disk but the container entrypoint never sources it, so the JVM
# never sees it. `kubectl set env statefulset/...` is the only path that
# reliably reaches the JVM's /proc/<pid>/environ AND survives subsequent
# kubectl-delete-pod restarts. Doing this BEFORE solo upgrade ensures all
# nodes initialize WRAPS in lockstep during the freeze-restart; injecting
# AFTER triggers independent rolling restarts that produce SELF_ISS failures.
inject_wraps_env_into_statefulsets() {
  local node sts log_file wraps_dir
  local nodes=()
  wraps_dir="$(configured_wraps_artifacts_container_dir)"
  log_file="${WORK_DIR}/inject-wraps-env.log"
  : > "${log_file}"

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  log "Injecting TSS_LIB_WRAPS_ARTIFACTS_PATH=${wraps_dir} into ${#nodes[@]} consensus StatefulSets (log: ${log_file})"

  for node in "${nodes[@]}"; do
    sts="network-${node}"
    {
      echo "=== set env statefulset/${sts} ==="
      kubectl -n "${SOLO_NAMESPACE}" set env "statefulset/${sts}" -c root-container \
        "TSS_LIB_WRAPS_ARTIFACTS_PATH=${wraps_dir}" 2>&1
    } >> "${log_file}"
  done

  for node in "${nodes[@]}"; do
    sts="network-${node}"
    printf '  injecting env into statefulset/%s... ' "${sts}"
    if {
        echo "=== rollout status statefulset/${sts} ==="
        kubectl -n "${SOLO_NAMESPACE}" rollout status "statefulset/${sts}" --timeout=600s 2>&1
      } >> "${log_file}"; then
      echo "rolled out"
    else
      echo "FAILED (see ${log_file})"
      return 1
    fi
  done
}

local_build_implementation_version() {
  unzip -p "${LOCAL_BUILD_PATH}/apps/HederaNode.jar" META-INF/MANIFEST.MF 2>/dev/null \
    | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1
}

consensus_pod_implementation_version() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "unzip -p /opt/hgcapp/services-hedera/HapiApp2.0/data/apps/HederaNode.jar META-INF/MANIFEST.MF 2>/dev/null \
      | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1"
}

verify_local_build_on_consensus_nodes() {
  local node pod
  local nodes=()
  local local_version="" pod_version=""

  local_version="$(local_build_implementation_version)"
  [[ -n "${local_version}" ]] || { echo "Unable to determine local build version for verification" >&2; return 1; }

  log "Verifying local-build version on each consensus node (expected ${local_version})"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    pod_version="$(consensus_pod_implementation_version "${pod}" || true)"
    if [[ "${pod_version}" == "${local_version}" ]]; then
      echo "  ${pod}: ${pod_version} OK"
    else
      echo "  ${pod}: expected ${local_version}, found ${pod_version:-unknown}" >&2
      return 1
    fi
  done
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

# Submit a few cryptoCreate transactions against a CN's gRPC port to drive
# consensus rounds forward. The genesis WRAPS ceremony stalls at "All nodes
# have contributed to the CRS, waiting for final adoption" on an idle
# network — adoption needs rounds, rounds need transactions. ~3-5 txns is
# enough to push the ceremony through to proof construction.
nudge_consensus_with_transactions() {
  local pf_pid="" tx_count="${NUDGE_TX_COUNT}"

  log "Setting up CN gRPC port-forward (svc/haproxy-node1-svc → localhost:${CN_GRPC_LOCAL_PORT})"
  # Kill any stale port-forward on this port.
  pkill -f "port-forward.*haproxy-node1-svc.*${CN_GRPC_LOCAL_PORT}" >/dev/null 2>&1 || true
  sleep 1
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc \
    "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" \
    > "${CN_PORT_FORWARD_LOG}" 2>&1 < /dev/null &
  pf_pid=$!

  # Wait for the port to become reachable (up to 30s).
  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    if (: </dev/tcp/127.0.0.1/"${CN_GRPC_LOCAL_PORT}") >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  if ! (: </dev/tcp/127.0.0.1/"${CN_GRPC_LOCAL_PORT}") >/dev/null 2>&1; then
    kill "${pf_pid}" >/dev/null 2>&1 || true
    echo "CN gRPC port-forward did not become reachable on localhost:${CN_GRPC_LOCAL_PORT} (log: ${CN_PORT_FORWARD_LOG})" >&2
    return 1
  fi
  log "CN gRPC reachable on 127.0.0.1:${CN_GRPC_LOCAL_PORT}"

  # Write the tx-nudge script.
  cat > "${NUDGE_SCRIPT}" <<'EOF'
const {
  Client,
  AccountCreateTransaction,
  Hbar,
  PrivateKey,
  Status,
} = require("@hashgraph/sdk");

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  const grpcEndpoint = process.env.GRPC_ENDPOINT || "127.0.0.1:50211";
  const operatorAccountId = process.env.OPERATOR_ACCOUNT_ID || "0.0.2";
  const operatorPrivateKey = process.env.OPERATOR_PRIVATE_KEY;
  const txCount = Number(process.env.NUDGE_TX_COUNT || "5");
  if (!operatorPrivateKey) {
    throw new Error("OPERATOR_PRIVATE_KEY is required");
  }

  const client = Client.forNetwork({ [grpcEndpoint]: "0.0.3" });
  client.setOperator(operatorAccountId, PrivateKey.fromString(operatorPrivateKey));
  client.setMaxAttempts(3);
  client.setRequestTimeout(20000);

  for (let i = 1; i <= txCount; i++) {
    const tx = new AccountCreateTransaction()
      .setInitialBalance(new Hbar(1))
      .setKey(PrivateKey.generateED25519().publicKey)
      .setMaxTransactionFee(new Hbar(5));
    const response = await tx.execute(client);
    const receipt = await response.getReceipt(client);
    if (receipt.status !== Status.Success) {
      throw new Error(`tx ${i}/${txCount}: non-success status ${receipt.status.toString()}`);
    }
    const accountId = receipt.accountId ? receipt.accountId.toString() : "(no id)";
    console.log(`  nudge tx ${i}/${txCount}: cryptoCreate -> ${accountId}`);
    await sleep(500);
  }

  await client.close();
}

main().catch((err) => {
  console.error(`nudge FAIL: ${err.message}`);
  process.exit(1);
});
EOF

  # Make sure @hashgraph/sdk is installed in WORK_DIR (idempotent, fast on re-run).
  if [[ ! -d "${WORK_DIR}/node_modules/@hashgraph/sdk" ]]; then
    log "Installing @hashgraph/sdk into ${WORK_DIR} (one-time, ~30s)"
    (
      cd "${WORK_DIR}"
      npm init -y >/dev/null 2>&1
      npm install --no-fund --no-audit @hashgraph/sdk >/dev/null 2>&1
    )
  fi

  log "Submitting ${tx_count} cryptoCreate txns to drive consensus rounds past CRS-adoption stall"
  local rc=0
  (
    cd "${WORK_DIR}"
    GRPC_ENDPOINT="127.0.0.1:${CN_GRPC_LOCAL_PORT}" \
    OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID}" \
    OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY}" \
    NUDGE_TX_COUNT="${tx_count}" \
      node "${NUDGE_SCRIPT}"
  ) || rc=$?

  kill "${pf_pid}" >/dev/null 2>&1 || true
  pkill -f "port-forward.*haproxy-node1-svc.*${CN_GRPC_LOCAL_PORT}" >/dev/null 2>&1 || true

  return "${rc}"
}

verify_wraps_on_consensus_nodes() {
  local wraps_dir="" expected_wraps=""
  local timeout_secs="${1:-600}"
  local deadline=0
  local node pod found_env found_wraps ready_for_proof
  local nodes=()

  wraps_dir="$(configured_wraps_artifacts_container_dir)"
  expected_wraps="$(find "${WRAPS_KEY_PATH}" -maxdepth 1 -type f | wc -l | tr -d ' ')"
  [[ "${expected_wraps}" -ge "${WRAPS_REQUIRED_FILE_COUNT}" ]] || {
    echo "Expected at least ${WRAPS_REQUIRED_FILE_COUNT} WRAPS artifacts in ${WRAPS_KEY_PATH}, found ${expected_wraps}" >&2
    return 1
  }

  log "Verifying WRAPS runtime on each consensus node (env=${wraps_dir}, expecting ${expected_wraps} extracted files, up to ${timeout_secs}s/node for env+artifacts+proof construction)"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    deadline=$((SECONDS + timeout_secs))

    # Phase 1: wait for TSS_LIB_WRAPS_ARTIFACTS_PATH to be set in the JVM env
    # AND for WrapsProvingKeyVerification to finish downloading + extracting
    # the proving-key archive. Both happen asynchronously after the pod
    # reports Ready, so poll rather than failing fast on the initial sample.
    ready_for_proof=false
    found_env=""
    found_wraps=""
    while (( SECONDS < deadline )); do
      if wraps_failure_present_in_log "${pod}"; then
        echo "  ${pod}: WRAPS reported a runtime failure (check ${HAPI_PATH}/output/hgcaa.log)" >&2
        return 1
      fi
      found_env="$(consensus_pod_wraps_env "${pod}" || true)"
      found_wraps="$(consensus_pod_wraps_file_count "${pod}" "${wraps_dir}" || true)"
      if [[ "${found_env}" == "${wraps_dir}" && "${found_wraps}" == "${expected_wraps}" ]]; then
        ready_for_proof=true
        break
      fi
      sleep 5
    done

    if ! ${ready_for_proof}; then
      echo "  ${pod}: timed out waiting for WRAPS env+artifacts (env='${found_env:-unset}' wanted '${wraps_dir}'; artifacts=${found_wraps:-0}/${expected_wraps})" >&2
      return 1
    fi

    echo "  ${pod}: env + ${found_wraps} artifacts OK; waiting for 'Constructing (genesis|incremental) WRAPS proof with:' in hgcaa.log"
    local progress_tick=0
    while (( SECONDS < deadline )); do
      if wraps_failure_present_in_log "${pod}"; then
        echo "  ${pod}: WRAPS reported a runtime failure (check ${HAPI_PATH}/output/hgcaa.log)" >&2
        return 1
      fi
      if wraps_proof_present_in_log "${pod}"; then
        echo "  ${pod}: WRAPS proof construction detected"
        break
      fi
      if (( progress_tick > 0 && progress_tick % 6 == 0 )); then
        echo "    ...still waiting on ${pod} ($((deadline - SECONDS))s remaining)"
      fi
      ((progress_tick++))
      sleep 5
    done

    if ! wraps_proof_present_in_log "${pod}"; then
      echo "  ${pod}: timed out after ${timeout_secs}s waiting for WRAPS proof construction" >&2
      return 1
    fi
  done

  echo "All consensus nodes confirmed: WRAPS env wired, artifacts present, proof construction observed"
}

run_command_with_timeout() {
  local timeout_secs="$1"
  shift
  local cmd_pid="" start_ts elapsed

  "$@" &
  cmd_pid=$!
  start_ts="$(date +%s)"

  while kill -0 "${cmd_pid}" >/dev/null 2>&1; do
    elapsed=$(( $(date +%s) - start_ts ))
    if (( elapsed >= timeout_secs )); then
      log "Command exceeded timeout (${timeout_secs}s); terminating PID ${cmd_pid}"
      pkill -TERM -P "${cmd_pid}" >/dev/null 2>&1 || true
      kill -TERM "${cmd_pid}" >/dev/null 2>&1 || true
      sleep 5
      pkill -KILL -P "${cmd_pid}" >/dev/null 2>&1 || true
      kill -KILL "${cmd_pid}" >/dev/null 2>&1 || true
      wait "${cmd_pid}" >/dev/null 2>&1 || true
      return 124
    fi
    sleep 5
  done

  wait "${cmd_pid}"
}

create_cluster() {
  # Deleting + recreating the kind cluster is enough cleanup for a repeat run.
  # We deliberately do NOT wipe ~/.solo/ here so the cached WRAPS proving-key
  # tarball (~1.9 GB at ~/.solo/cache/wraps-v1.0.0.tar.gz) survives across runs.
  log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true

  log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
  kind create cluster -n "${SOLO_CLUSTER_NAME}"
  CLUSTER_CREATED_THIS_RUN="true"
}

configure_solo() {
  local consensus_node_count
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
  log "Installing Solo cluster prerequisites (MinIO only)"
  solo cluster-ref config setup \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --cluster-setup-namespace "${SOLO_CLUSTER_SETUP_NAMESPACE}" \
    --minio true \
    --prometheus-stack false \
    --quiet-mode
}

deploy_baseline_075() {
  log "Deploying baseline consensus network at released ${DEPLOY_RELEASE_TAG} with 0.75 properties"

  solo keys consensus generate \
    --gossip-keys \
    --tls-keys \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}"

  solo consensus network deploy \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --application-properties "${APP_PROPS_075_FILE}" \
    --log4j2-xml "${LOG4J2_XML_PATH}" \
    --pvcs true \
    --release-tag "${DEPLOY_RELEASE_TAG}"

  solo consensus node setup \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --release-tag "${DEPLOY_RELEASE_TAG}"

  solo consensus node start \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --force-port-forward false

  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
}

upgrade_to_local_076() {
  log "Upgrading consensus network to local build (labeled ${UPGRADE_VERSION_LABEL}) with 0.76 properties (TSS, mocked signatures)"

  ensure_wraps_artifacts_downloaded
  ensure_wraps_proving_key_server

  # Pre-inject TSS_LIB_WRAPS_ARTIFACTS_PATH before Solo's upgrade so all CNs
  # initialize WRAPS in lockstep during the freeze-restart.
  inject_wraps_env_into_statefulsets

  # --wraps-key-path is intentionally omitted: Solo only honors it on deploy,
  # not on upgrade. The 0.76 properties drive WRAPS via tss.wrapsProvingKeyDownloadUrl
  # (the local nginx server) + tss.wrapsProvingKeyDownloadEnabled=true.
  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --upgrade-version "${UPGRADE_VERSION_LABEL}"
    --local-build-path "${LOCAL_BUILD_PATH}"
    --application-properties "${APP_PROPS_076_FILE}"
    --application-env "${APP_ENV_076_FILE}"
    --quiet-mode
    --force
  )

  # With hiero-ledger/solo#4440 in place, the upgrade stops the JVMs before
  # the JAR cp and restarts them after, so the previous JAR-staging race is
  # gone. We let any non-zero Solo exit (timeout, deploy validation, ACTIVE
  # check failure) propagate via set -e.
  run_command_with_timeout "${SOLO_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"

  log "--- Check 1/3: wait for consensus pods + haproxy + verify local-build version ---"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  verify_local_build_on_consensus_nodes

  log "--- Check 2/3: nudge consensus with a few cryptoCreate txns (genesis WRAPS ceremony needs rounds) ---"
  nudge_consensus_with_transactions

  log "--- Check 3/3: verify WRAPS runtime + proof construction on every consensus node ---"
  verify_wraps_on_consensus_nodes 600
}

log "Validating prerequisites"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd curl
require_cmd tar
require_cmd docker
require_cmd unzip
require_cmd node
require_cmd npm

[[ -f "${APP_PROPS_075_FILE}" ]] || { echo "Missing file: ${APP_PROPS_075_FILE}" >&2; exit 1; }
[[ -f "${APP_PROPS_076_FILE}" ]] || { echo "Missing file: ${APP_PROPS_076_FILE}" >&2; exit 1; }
[[ -f "${APP_ENV_076_FILE}" ]] || { echo "Missing file: ${APP_ENV_076_FILE}" >&2; exit 1; }
[[ -f "${LOG4J2_XML_PATH}" ]] || { echo "Missing file: ${LOG4J2_XML_PATH}" >&2; exit 1; }

validate_local_build_path "${LOCAL_BUILD_PATH}" || {
  echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}" >&2
  echo "Expected ${LOCAL_BUILD_PATH}/apps/HederaNode.jar and ${LOCAL_BUILD_PATH}/lib" >&2
  exit 1
}

create_cluster
configure_solo
setup_cluster_prereqs
deploy_baseline_075
upgrade_to_local_076

log "PASS: v0.75.0-rc.1 release -> local 0.75-SNAPSHOT (TSS, mocked signatures) upgrade completed"
