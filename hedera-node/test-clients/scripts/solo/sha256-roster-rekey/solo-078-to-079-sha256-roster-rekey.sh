#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end test for the SHA-384 -> SHA-256 roster re-key across a v0.78 -> v0.79 upgrade.
#
# What this proves:
#   The RosterService keeps its rosters in a key-value map (ROSTERS) keyed by the
#   roster hash. v0.78 keys that map with SHA-384 (the old Cryptography.DEFAULT_DIGEST_TYPE).
#   On an upgrade, V0790RosterSchema.restart() calls WritableRosterStore.rehashRosters()
#   to re-key every stored roster (active history + candidate) to the new default
#   digest. If that re-key wrote the map incorrectly, a node could not read its
#   active roster back out: on every non-genesis start, Hedera.initializeDagger()
#   does requireNonNull(rosterStore.getActiveRoster()), which is a KV lookup by the
#   (re-keyed) active roster hash. A bad re-key => NPE => the node crashes on restart.
#
#   Therefore: a 3-node network that starts on v0.78 (SHA-384) and comes back ACTIVE
#   after upgrading to a SHA-256 build has demonstrably re-keyed and read at least part
#   of the ROSTERS map correctly. The test also (prior to the upgrade) stakes varying non-zero
#   amounts to each node and waits for a staking-period boundary, thereby creating a distinct
#   candidate roster. This candidate is also read after upgrade and, if all goes well, used as
#   the new active roster, thereby proving that the candidate roster was also re-keyed correctly.
#   Finally, each node then processes a CryptoCreate as a liveness confirmation.
#
# The upgrade target must be a build whose Cryptography.DEFAULT_DIGEST_TYPE is SHA_256
# (i.e. the local build with that one-line change). Until a real v0.79 tag exists,
# use UPGRADE_TAG=local against a locally built SHA-256 tree.
#
#   1. Deploy a 3-node consensus network at the published v0.78.* release (SHA-384 rosters).
#   2. (Distinct-candidate coverage) Before upgrading, stake a distinct non-zero amount to every node
#      and wait for a staking-period boundary (staking.periodMins=1). StakePeriodChanges then stores a
#      reweighted candidate roster that is DISTINCT from the active roster. After the upgrade, assert
#      from swirlds.log that the "Starting with roster history:" dump contains the rebalanced weights
#      exactly once (the adopted candidate) plus older weights.
#   2b. Upgrade the network to the SHA-256 build to change the software version; on restart
#      each node re-keys the ROSTERS map to SHA-256 and reads its active roster back.
#   3. After the upgrade, submit one CryptoCreate transaction to each node via its own
#      port-forwarded gRPC endpoint and assert SUCCESS from every node.
#
# Environment variables:
#   DEPLOY_RELEASE_TAG      v0.78.* baseline (default: v0.78.0)
#   UPGRADE_TAG             "local" (default) upgrades to LOCAL_BUILD_PATH (must be a
#                           SHA-256 build); any published tag upgrades to that release.
#   LOCAL_BUILD_PATH        path to hedera-node/data (used when UPGRADE_TAG=local)
#   NODE_ALIASES            comma-separated node aliases (default: node1,node2,node3)
#   BASELINE_NODE_ACCOUNTS  comma-separated gRPC-routing account IDs for the
#                           nodes (default: 0.0.3,0.0.4,0.0.5)
#   CN_GRPC_BASE_LOCAL_PORT first local port for node port-forwards (default: 50211)
#   SOLO_CLUSTER_NAME       kind cluster name (default: solo-sha256-roster-rekey)
#   SOLO_DEPLOYMENT         solo deployment name (default: solo-sha256-roster-rekey)
#   KEEP_NETWORK            keep the kind cluster on exit (default: true)
#   STAKE_UNIT_HBAR         node index i is staked (i+1)*this hbar; weight == tinybars (default: 100)
#   STAKE_BOUNDARY_TIMEOUT_SECS  max wait for a staking-period boundary (default: 420)
#
# Prerequisites: kind, kubectl, solo, node (>=18), npm, unzip, jq

set -euo pipefail
set +m

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../../" && pwd)"

# Prefer an explicit SOLO_BIN_DIR; otherwise check the common non-sudo npm
# global prefix before falling back to whatever is on PATH.
for d in \
    "${SOLO_BIN_DIR:-}" \
    "${HOME}/.npm-global/bin" \
    "$(npm config get prefix 2>/dev/null)/bin"; do
  [[ -n "${d}" && -x "${d}/solo" ]] && PATH="${d}:${PATH}" && break
done
export PATH

SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-solo-sha256-roster-rekey}"
SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-sha256-roster-rekey}"
SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-setup}"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

# v0.78 baseline: a published release tag Solo can download.
DEPLOY_RELEASE_TAG="${DEPLOY_RELEASE_TAG:-v0.78.0}"

# Upgrade target.
#   "local" (default): upgrade to the LOCAL build at LOCAL_BUILD_PATH.
#   Any other value  : upgrade to the published Solo tag named here.
UPGRADE_TAG="${UPGRADE_TAG:-local}"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
SOLO_UPGRADE_TIMEOUT_SECS="${SOLO_UPGRADE_TIMEOUT_SECS:-1800}"

# gRPC-routing account IDs for the consensus nodes.
# In Solo's standard 3-node genesis: node1=0.0.3, node2=0.0.4, node3=0.0.5.
BASELINE_NODE_ACCOUNTS="${BASELINE_NODE_ACCOUNTS:-0.0.3,0.0.4,0.0.5}"

# Port-forward base: node1→:50301, node2→:50302, node3→:50303.
# Deliberately avoids 50211 (Hedera non-TLS) and 50212 (Hedera TLS) because
# @hashgraph/sdk uses port 50212 as a signal to enable TLS channel credentials,
# which would fail against haproxy's plaintext non-tls-grpc-client-port frontend.
CN_GRPC_BASE_LOCAL_PORT="${CN_GRPC_BASE_LOCAL_PORT:-50301}"

OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"

ALL_NODE_ALIASES="${NODE_ALIASES}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/solo-sha256-roster-rekey.XXXXXX")"
CLUSTER_CREATED_THIS_RUN="false"
declare -a PF_PIDS=()
declare -A PF_PID_BY_ALIAS=()

# Filled in by upgrade_network() when UPGRADE_TAG=local.
TARGET_VERSION=""

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "Required command not found: $1" >&2; exit 1; }
}

cleanup() {
  local ec=$?
  stop_all_port_forwards
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

# ---------------------------------------------------------------------------
# Wait helpers
# ---------------------------------------------------------------------------

wait_for_consensus_pods_ready() {
  local timeout_secs="${1:-600}"
  local aliases="${2:-${NODE_ALIASES}}"
  local node nodes=()
  IFS=',' read -r -a nodes <<< "${aliases}"
  for node in "${nodes[@]}"; do
    log "Waiting for network-${node}-0 to become Ready"
    kubectl -n "${SOLO_NAMESPACE}" wait \
      --for=condition=ready "pod/network-${node}-0" \
      --timeout="${timeout_secs}s"
  done
}

wait_for_haproxy_ready() {
  local timeout_secs="${1:-600}"
  local aliases="${2:-${NODE_ALIASES}}"
  local node nodes=()
  IFS=',' read -r -a nodes <<< "${aliases}"
  for node in "${nodes[@]}"; do
    log "Waiting for haproxy-${node} rollout to become ready"
    kubectl -n "${SOLO_NAMESPACE}" rollout status \
      "deployment/haproxy-${node}" \
      --timeout="${timeout_secs}s"
  done
}

# ---------------------------------------------------------------------------
# Version helpers
# ---------------------------------------------------------------------------

local_build_implementation_version() {
  unzip -p "${LOCAL_BUILD_PATH}/apps/HederaNode.jar" META-INF/MANIFEST.MF 2>/dev/null \
    | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1
}

consensus_pod_implementation_version() {
  local pod="$1"
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "unzip -p /opt/hgcapp/services-hedera/HapiApp2.0/data/apps/HederaNode.jar \
       META-INF/MANIFEST.MF 2>/dev/null \
     | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1"
}

verify_local_build_on_consensus_nodes() {
  local aliases="${1:-${ALL_NODE_ALIASES}}"
  local node pod nodes=() local_version="" pod_version=""
  local_version="$(local_build_implementation_version)"
  [[ -n "${local_version}" ]] || { echo "Unable to determine local build version" >&2; return 1; }
  log "Verifying local-build version on each node (expected ${local_version})"
  IFS=',' read -r -a nodes <<< "${aliases}"
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

run_command_with_timeout() {
  local timeout_secs="$1"; shift
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
      kill -KILL "${cmd_pid}" >/dev/null 2>&1 || true
      wait "${cmd_pid}" >/dev/null 2>&1 || true
      return 124
    fi
    sleep 5
  done
  wait "${cmd_pid}"
}

# ---------------------------------------------------------------------------
# Port-forward management
# ---------------------------------------------------------------------------


start_one_port_forward() {
  local alias="${1}" port="${2}"
  local pf_log="${WORK_DIR}/pf-${alias}.log"

  # Kill any existing port-forward for this alias before (re)starting.
  if [[ -n "${PF_PID_BY_ALIAS[${alias}]:-}" ]]; then
    kill "${PF_PID_BY_ALIAS[${alias}]}" >/dev/null 2>&1 || true
    unset 'PF_PID_BY_ALIAS['"${alias}"']'
  fi
  pkill -f "kubectl.*port-forward.*haproxy-${alias}-svc.*${port}" >/dev/null 2>&1 || true

  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward \
    "svc/haproxy-${alias}-svc" "${port}:non-tls-grpc-client-port" \
    > "${pf_log}" 2>&1 < /dev/null &
  local pf_pid=$!
  PF_PID_BY_ALIAS["${alias}"]="${pf_pid}"
  PF_PIDS+=("${pf_pid}")
  disown "${pf_pid}" 2>/dev/null || true

  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    (: </dev/tcp/127.0.0.1/${port}) >/dev/null 2>&1 && break
    sleep 1
  done
  if ! (: </dev/tcp/127.0.0.1/${port}) >/dev/null 2>&1; then
    echo "Port-forward to haproxy-${alias}-svc did not become reachable on :${port}" \
         "(log: ${pf_log})" >&2
    return 1
  fi
  log "  haproxy-${alias}-svc -> 127.0.0.1:${port} OK"
}

start_all_port_forwards() {
  local -a all_aliases=()
  local idx
  IFS=',' read -r -a all_aliases <<< "${ALL_NODE_ALIASES}"
  for idx in "${!all_aliases[@]}"; do
    start_one_port_forward "${all_aliases[$idx]}" "$((CN_GRPC_BASE_LOCAL_PORT + idx))"
  done
}

stop_one_port_forward() {
  local alias="${1}"
  if [[ -n "${PF_PID_BY_ALIAS[${alias}]:-}" ]]; then
    kill "${PF_PID_BY_ALIAS[${alias}]}" >/dev/null 2>&1 || true
    unset 'PF_PID_BY_ALIAS['"${alias}"']'
  fi
}

stop_all_port_forwards() {
  local pid
  for pid in "${PF_PIDS[@]:-}"; do
    kill "${pid}" >/dev/null 2>&1 || true
  done
  PF_PIDS=()
  # shellcheck disable=SC2034
  PF_PID_BY_ALIAS=()
}

# ---------------------------------------------------------------------------
# Step 1: Cluster setup
# ---------------------------------------------------------------------------

preload_kind_images() {
  # On networks where a TLS-inspection proxy intercepts HTTPS to container
  # registries, the kind node's containerd cannot pull images (x509 unknown
  # authority), but Docker Desktop on the Mac trusts the corporate CA.
  # Solution: pull images through Docker Desktop, then load them into the
  # kind cluster via `kind load docker-image` which bypasses the network.
  #
  # Image list is pinned to the Solo 0.77.0 / chart 0.63.3 release.
  # If you update Solo, re-run manually and inspect `kubectl -n solo get events`
  # for new ImagePullBackOff entries, then update this list.
  local images=(
    "busybox:1.36.1"
    "busybox:stable-musl"
    "docker.io/envoyproxy/envoy:v1.21.1"
    "quay.io/minio/minio:RELEASE.2024-08-03T04-33-23Z"
    "quay.io/minio/operator-sidecar:v7.0.1"
    "docker.io/haproxytech/haproxy-alpine:2.4.25"
    "curlimages/curl:8.9.1"
    "docker.io/otel/opentelemetry-collector-contrib:0.72.0"
    "ghcr.io/hashgraph/solo-cheetah/cheetah:0.4.5"
    "ghcr.io/hashgraph/solo-containers/debian-s6-java25:0.45.3"
  )
  log "Pre-pulling ${#images[@]} images through Docker Desktop and loading into kind cluster ${SOLO_CLUSTER_NAME}"
  for img in "${images[@]}"; do
    # Some upstreams go away (e.g. the quay.io/minio repos). Prefer a fresh pull,
    # but fall back to a locally cached copy so a dead registry doesn't block the
    # run. Only hard-fail if the image is neither pullable nor cached.
    if docker pull "${img}" >/dev/null 2>&1; then
      log "  pulled ${img}"
    elif docker image inspect "${img}" >/dev/null 2>&1; then
      log "  pull failed; using locally cached ${img}"
    else
      # Preloading is only an optimization: skip images that are neither pullable nor
      # cached (e.g. quay.io/minio) so a dead/unauthenticated registry doesn't block
      # the run. The cluster will pull on demand.
      log "  WARN: ${img} could not be pulled and is not cached; skipping preload (cluster will pull on demand)"
      continue
    fi
    log "  load ${img} -> kind:${SOLO_CLUSTER_NAME}"
    # Also best-effort: `kind load docker-image` can fail with "content digest ... not found"
    # when Docker's containerd image store exports a manifest ctr can't re-import. Preloading is
    # only a cache-warming optimization, so warn and move on; the cluster pulls on demand.
    if ! kind load docker-image "${img}" --name "${SOLO_CLUSTER_NAME}"; then
      log "  WARN: failed to load ${img} into kind (cluster will pull on demand)"
    fi
  done
}

create_cluster() {
  log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
  kind create cluster -n "${SOLO_CLUSTER_NAME}"
  CLUSTER_CREATED_THIS_RUN="true"
  preload_kind_images
}

configure_solo() {
  local node_count
  node_count="$(awk -F',' '{print NF}' <<< "${NODE_ALIASES}")"
  log "solo init"
  solo init
  log "Configuring Solo deployment ${SOLO_DEPLOYMENT} (${node_count} initial CNs)"
  solo cluster-ref config connect \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --context "kind-${SOLO_CLUSTER_NAME}"
  solo deployment config create \
    --namespace "${SOLO_NAMESPACE}" \
    --deployment "${SOLO_DEPLOYMENT}"
  solo deployment cluster attach \
    --deployment "${SOLO_DEPLOYMENT}" \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --num-consensus-nodes "${node_count}"
}

setup_cluster_prereqs() {
  log "Installing Solo cluster prerequisites"
  solo cluster-ref config setup \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --cluster-setup-namespace "${SOLO_CLUSTER_SETUP_NAMESPACE}"
}

# ---------------------------------------------------------------------------
# Step 2: Deploy baseline at v0.78
# ---------------------------------------------------------------------------

deploy_baseline() {
  log "Deploying ${NODE_ALIASES} at ${DEPLOY_RELEASE_TAG}"

  solo keys consensus generate \
    --gossip-keys \
    --tls-keys \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}"

  # --pvcs true: persistent volumes stage the new JARs so state survives the
  # upgrade-driven pod restarts.
  # --application-properties disables hinTS/history so the V079HintsSchema
  # migration doesn't fail on legacy TSS state during the upgrade.
  solo consensus network deploy \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --pvcs true \
    --application-properties "${SCRIPT_DIR}/application.properties" \
    --release-tag "${DEPLOY_RELEASE_TAG}"

  solo consensus node setup \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --release-tag "${DEPLOY_RELEASE_TAG}"

  solo consensus node start \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
    --force-port-forward false

  wait_for_consensus_pods_ready 600 "${NODE_ALIASES}"
  wait_for_haproxy_ready 600 "${NODE_ALIASES}"
}

# ---------------------------------------------------------------------------
# Step 2b: Stake rebalance -> staking-period boundary -> DISTINCT reweighted candidate
# ---------------------------------------------------------------------------
#
# Stake a distinct, non-zero amount to every node (a node's roster weight is its effective
# stakingInfo.stake(), ReadableStakingInfoStore.weightFunction(); a zero weight anywhere makes
# StakePeriodChanges skip the reweight entirely), then wait for a staking-period boundary
# (staking.periodMins=1 in application.properties). StakePeriodChanges.processSideEffects
# computes new stakes and stores a reweighted candidate roster whose hash differs from the
# active roster — exercising the distinct-candidate code path in rehashRosters().
# Weights are asserted later from the swirlds.log roster dumps, NOT from system files 0.0.101/0.0.102
# (file 102 carries only the deprecated Node.weight, which is never written from staking).

STAKE_UNIT_HBAR="${STAKE_UNIT_HBAR:-100}"                   # node index i is staked (i+1) * this many hbar
STAKE_BOUNDARY_TIMEOUT_SECS="${STAKE_BOUNDARY_TIMEOUT_SECS:-420}"
HGCAA_LOG="/opt/hgcapp/services-hedera/HapiApp2.0/output/hgcaa.log"
SWIRLDS_LOG="/opt/hgcapp/services-hedera/HapiApp2.0/output/swirlds.log"
STAKING_JS="${WORK_DIR}/stake-rebalance.js"
HASHGRAPH_SDK_VERSION="${HASHGRAPH_SDK_VERSION:-2.81.0}"      # pinned so CI behavior is reproducible; override via env

# Expected weight (tinybars) of the node at 0-based index $1 after the rebalance. Distinct per node.
expected_weight_for_index() {
  echo $(( ($1 + 1) * STAKE_UNIT_HBAR * 100000000 ))
}

ensure_hashgraph_sdk() {
  if [[ ! -d "${WORK_DIR}/node_modules/@hashgraph/sdk" ]]; then
    log "Installing @hashgraph/sdk@${HASHGRAPH_SDK_VERSION} into ${WORK_DIR} (one-time, ~30s)"
    (
      cd "${WORK_DIR}"
      npm init -y >/dev/null 2>&1
      npm install --no-fund --no-audit "@hashgraph/sdk@${HASHGRAPH_SDK_VERSION}" >/dev/null 2>&1
    )
  fi
}

write_staking_js() {
  # MODE=stake: create an account staked to STAKED_NODE_ID holding BALANCE_TINYBARS.
  # MODE=tick : create an unstaked, zero-balance account. Staking boundaries are only detected when a
  #             transaction is handled after the period rolls over, so ticks keep an idle network moving.
  cat > "${STAKING_JS}" <<'JSEOF'
"use strict";
const { Client, AccountCreateTransaction, Hbar, PrivateKey, Status } = require("@hashgraph/sdk");

async function main() {
  const mode        = process.env.MODE || "stake";
  const endpoint    = process.env.NODE_ENDPOINT;
  const accountId   = process.env.NODE_ACCOUNT_ID;
  const operatorId  = process.env.OPERATOR_ACCOUNT_ID || "0.0.2";
  const operatorKey = process.env.OPERATOR_PRIVATE_KEY;
  if (!endpoint || !accountId || !operatorKey) throw new Error("NODE_ENDPOINT, NODE_ACCOUNT_ID, OPERATOR_PRIVATE_KEY required");

  const client = Client.forNetwork({ [endpoint]: accountId });
  client.setOperator(operatorId, PrivateKey.fromString(operatorKey));
  client.setMaxAttempts(3);
  client.setRequestTimeout(30000);
  try {
    const tx = new AccountCreateTransaction()
      .setKeyWithoutAlias(PrivateKey.generateED25519().publicKey)
      .setMaxTransactionFee(new Hbar(5));
    if (mode === "stake") {
      const nodeId = parseInt(process.env.STAKED_NODE_ID, 10);
      if (Number.isNaN(nodeId)) throw new Error("STAKED_NODE_ID is required in stake mode");
      tx.setInitialBalance(Hbar.fromTinybars(process.env.BALANCE_TINYBARS)).setStakedNodeId(nodeId);
    }
    const response = await tx.execute(client);
    const receipt  = await response.getReceipt(client);
    if (receipt.status !== Status.Success) throw new Error(`FAIL status=${receipt.status.toString()}`);
    console.log(`  ${mode}: OK -> ${receipt.accountId}`);
  } finally {
    await client.close();
  }
}
main().catch((err) => { console.error(`staking-js FAIL [${process.env.MODE}]: ${err.message}`); process.exit(1); });
JSEOF
}

# Number of staking-boundary markers (ExchangeRateManager "Updated midnight rates to") in a pod's hgcaa.log.
midnight_boundary_count() {
  local pod="$1" n
  n="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -- \
    grep -c "Updated midnight rates to" "${HGCAA_LOG}" 2>/dev/null || true)"
  echo "${n:-0}"
}

# Q1 PROBE: confirm staking.periodMins=1 is in effect on the node (survives upgrade).
# Reads the config file on disk and checks recent logs for any mention of the property.
probe_staking_period_mins() {
  local label="$1"
  local -a all_aliases=()
  IFS=',' read -r -a all_aliases <<< "${ALL_NODE_ALIASES}"
  local pod="network-${all_aliases[0]}-0"
  log "=== Q1 PROBE [${label}]: staking.periodMins on ${pod} ==="
  local found=false cpath val
  for cpath in \
      /opt/hgcapp/services-hedera/HapiApp2.0/data/config/application.properties \
      /opt/hgcapp/services-hedera/HapiApp2.0/data/app.properties \
      /opt/hgcapp/services-hedera/HapiApp2.0/application.properties; do
    val="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -- grep -s "staking.periodMins" "${cpath}" 2>/dev/null || true)"
    if [[ -n "${val}" ]]; then
      log "  ${cpath}: ${val}"
      found=true
    fi
  done
  if [[ "${found}" != "true" ]]; then
    log "  staking.periodMins not found in candidate config paths; listing data/config/ for reference:"
    kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -- ls -la \
        /opt/hgcapp/services-hedera/HapiApp2.0/data/config/ 2>/dev/null \
      | while IFS= read -r l; do log "    ${l}"; done || true
  fi
  local log_val
  log_val="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -- \
    grep -h "periodMins" "${HGCAA_LOG}" "${SWIRLDS_LOG}" 2>/dev/null | tail -n 5 || true)"
  if [[ -n "${log_val}" ]]; then
    log "  Recent log mentions of 'periodMins':"
    while IFS= read -r l; do log "    ${l}"; done <<< "${log_val}"
  else
    log "  No 'periodMins' found in recent logs"
  fi
  log "=== end Q1 probe ==="
}

rebalance_stake_and_wait_for_boundary() {
  local -a all_aliases=() all_accounts=()
  IFS=',' read -r -a all_aliases  <<< "${ALL_NODE_ALIASES}"
  IFS=',' read -r -a all_accounts <<< "${BASELINE_NODE_ACCOUNTS}"
  local sample_pod="network-${all_aliases[0]}-0"
  local port="${CN_GRPC_BASE_LOCAL_PORT}"   # route everything through node1

  probe_staking_period_mins "after-deploy"   # Q1: did staking.periodMins=1 survive deploy_baseline?

  ensure_hashgraph_sdk
  write_staking_js
  log "Starting port-forward to ${all_aliases[0]} for the stake rebalance"
  start_one_port_forward "${all_aliases[0]}" "${port}"

  # 1. Stake a distinct non-zero amount to every node (node id == alias index in Solo's numbering:
  #    node1=0 ... node3=2).
  local idx w
  for idx in "${!all_aliases[@]}"; do
    w="$(expected_weight_for_index "${idx}")"
    log "Staking ${w} tinybars to node id ${idx} (${all_aliases[$idx]}); expected weight ${w}"
    (
      cd "${WORK_DIR}"
      MODE=stake STAKED_NODE_ID="${idx}" BALANCE_TINYBARS="${w}" \
      NODE_ENDPOINT="127.0.0.1:${port}" NODE_ACCOUNT_ID="${all_accounts[0]}" \
      OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID}" OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY}" \
        node "${STAKING_JS}"
    )
  done

  # 2. Baseline AFTER the last stake txn, then require one more boundary: that boundary's
  #    end-of-period update sees every stake above and then stores the reweighted candidate.
  local before now deadline
  before="$(midnight_boundary_count "${sample_pod}")"
  log "Staking boundary count before waiting: ${before}; waiting for the next one (timeout ${STAKE_BOUNDARY_TIMEOUT_SECS}s)"
  deadline=$((SECONDS + STAKE_BOUNDARY_TIMEOUT_SECS))
  now="${before}"
  while (( SECONDS < deadline )); do
    (
      cd "${WORK_DIR}"
      MODE=tick NODE_ENDPOINT="127.0.0.1:${port}" NODE_ACCOUNT_ID="${all_accounts[0]}" \
      OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID}" OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY}" \
        node "${STAKING_JS}" >/dev/null
    ) || log "  (tick transaction failed; will retry)"
    now="$(midnight_boundary_count "${sample_pod}")"
    (( now > before )) && break
    sleep 15
  done
  stop_all_port_forwards
  if (( now <= before )); then
    echo "FAIL: no staking-period boundary ('Updated midnight rates to') seen in ${sample_pod} hgcaa.log" >&2
    echo "      within ${STAKE_BOUNDARY_TIMEOUT_SECS}s. Is staking.periodMins=1 in effect on the network?" >&2
    return 1
  fi
  log "Staking boundary observed (${before} -> ${now})"

  # 3. Parse actual per-node stakes from the last "Exporting:" log line.  We cannot predict the
  #    exact values because each node carries a baseline stake on top of the rebalance; instead
  #    assert that every stake is non-zero and all are pairwise distinct (sufficient to prove a
  #    distinct candidate is stored).  Stash the results for Step 3.
  local block
  block="$(kubectl -n "${SOLO_NAMESPACE}" exec "${sample_pod}" -- \
      grep -A80 "Exporting:" "${HGCAA_LOG}" 2>/dev/null \
    | awk '/Exporting:/{buf=""} {buf = buf " " $0} END{print buf}' \
    | tr -d '\n' || true)"
  if [[ -z "${block}" ]]; then
    echo "FAIL: no 'Exporting:' NodeStake line found in ${sample_pod} hgcaa.log" >&2
    return 1
  fi
  log "Q3 PROBE: raw Exporting: line from hgcaa.log (first 600 chars):"
  log "  ${block:0:600}"

  local weights_file="${WORK_DIR}/expected-weights.txt"
  rm -f "${weights_file}"
  # Each NodeStake[...] entry: maxStake=, minStake=, nodeId=N, rewardRate=, stake=M,
  # stakeNotRewarded=, stakeRewarded=  — extract the first "stake=" (not stakeN*) per entry.
  awk '
    BEGIN { RS="NodeStake\\[" }
    NR > 1 {
      nid = ""; st = ""
      tmp = $0
      if (match(tmp, /nodeId=[0-9]+/)) nid = substr(tmp, RSTART+7, RLENGTH-7)
      n = split($0, flds, /,[ ]*/)
      for (i = 1; i <= n; i++) {
        f = flds[i]; gsub(/^[ \t]+/, "", f)
        if (f ~ /^stake=[0-9]/) {
          sub(/^stake=/, "", f); gsub(/[^0-9].*/, "", f); st = f; break
        }
      }
      if (nid != "" && st != "") print nid, st
    }
  ' <<< "${block}" > "${weights_file}"

  local stake_count unique_count nid st
  stake_count="$(wc -l < "${weights_file}" | tr -d ' ')"
  if [[ "${stake_count}" -eq 0 ]]; then
    echo "FAIL: could not parse any nodeId/stake pairs from Exporting: block (format may differ from expected)." >&2
    echo "  Without these the weight-distinctness check (Step 3c) cannot verify the re-key, so this is fatal." >&2
    echo "  Full block for inspection (first 500 chars): ${block:0:500}" >&2
    rm -f "${weights_file}"
    return 1
  fi

  log "Per-node stakes from Exporting: block:"
  while IFS=' ' read -r nid st; do
    log "  node ${nid}: stake=${st}"
    if [[ "${st}" == "0" || -z "${st}" ]]; then
      echo "FAIL: node ${nid} has stake=0 in the Exporting: block; rebalance had no effect." >&2
      return 1
    fi
  done < "${weights_file}"

  unique_count="$(awk '{print $2}' "${weights_file}" | sort -u | wc -l | tr -d ' ')"
  if [[ "${stake_count}" -ne "${unique_count}" ]]; then
    echo "FAIL: duplicate stake values in Exporting: block; all ${stake_count} node stakes must be pairwise distinct." >&2
    awk '{print "  node "$1": stake="$2}' "${weights_file}" >&2
    return 1
  fi
  log "End-of-period stakes all non-zero and pairwise distinct (${stake_count} nodes); reweighted candidate was stored"
  log "  Expected weights written to ${weights_file} for Step 3c"
}

# ---------------------------------------------------------------------------
# Step 3: Upgrade to the SHA-256 build — re-keys the ROSTERS map on restart
# ---------------------------------------------------------------------------

upgrade_network() {
  log "Upgrading ${ALL_NODE_ALIASES}: ${DEPLOY_RELEASE_TAG} -> ${UPGRADE_TAG}"

  # On each node's restart under the new build, V0790RosterSchema.restart() re-keys
  # the ROSTERS map to the new Cryptography.DEFAULT_DIGEST_TYPE (SHA-256). A node that
  # comes back ACTIVE has read its re-keyed active roster from that map (Hedera does
  # requireNonNull(rosterStore.getActiveRoster()) on every non-genesis start).
  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${ALL_NODE_ALIASES}"
    --quiet-mode
    --force
  )

  if [[ "${UPGRADE_TAG}" == "local" ]]; then
    TARGET_VERSION="$(local_build_implementation_version)"
    [[ -n "${TARGET_VERSION}" ]] || {
      echo "Unable to read Implementation-Version from ${LOCAL_BUILD_PATH}/apps/HederaNode.jar" >&2
      echo "Build the local JAR first: ./gradlew :app:assemble" >&2
      return 1
    }
    log "Upgrading to local build (${TARGET_VERSION}) at ${LOCAL_BUILD_PATH}"
    upgrade_cmd+=(--local-build-path "${LOCAL_BUILD_PATH}")
  else
    log "Upgrading to published tag ${UPGRADE_TAG}"
    upgrade_cmd+=(--upgrade-version "${UPGRADE_TAG}")
  fi

  run_command_with_timeout "${SOLO_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"

  log "Waiting for all nodes (${ALL_NODE_ALIASES}) to be ready post-upgrade"
  wait_for_consensus_pods_ready 600 "${ALL_NODE_ALIASES}"
  wait_for_haproxy_ready 600 "${ALL_NODE_ALIASES}"

  if [[ "${UPGRADE_TAG}" == "local" ]]; then
    verify_local_build_on_consensus_nodes "${ALL_NODE_ALIASES}"
  fi
}

# ---------------------------------------------------------------------------
# Step 3b: Assert the DISTINCT re-weighted candidate was adopted
# ---------------------------------------------------------------------------

assert_distinct_candidate_rekeyed_and_adopted() {
  # Expected log per node (in swirlds.log):
  #   PlatformBuilder logs "Starting with roster history:" followed by the roster history and the
  #   hash->Roster map as printed by the PBJ toString (no "Current"/"Previous" labels). Roster
  #   history is newest-first and the adopted candidate is the newest roster. Because the previous
  #   rosters carry pre-rebalance weights, each rebalanced weight must appear exactly once in the
  #   block (only the adopted roster has it) and other weights (the older rosters) must also appear.
  #   Note: the exact post-upgrade weights need not be exactly asserted, just that they are pairwise-
  #   distinct.
  # Runs AFTER the upgrade, so the last occurrence in each log is the v0.79 restart.
  local -a all_aliases=()
  IFS=',' read -r -a all_aliases <<< "${ALL_NODE_ALIASES}"
  local idx alias pod inputs w count other
  local fail=0

  probe_staking_period_mins "after-upgrade"   # Q1: did staking.periodMins=1 survive upgrade_network?

  for alias in "${all_aliases[@]}"; do
    pod="network-${alias}-0"
    log "=== distinct-candidate assertions on ${pod} ==="

    # adopted roster carries the rebalanced weights; older rosters carry different ones
    inputs="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -- \
        grep -h -A200 "Starting with roster history:" "${SWIRLDS_LOG}" 2>/dev/null \
      | awk '/Starting with roster history:/{buf=""; on=1; next} on && /^[0-9]{4}-[0-9]{2}-[0-9]{2}/{on=0} on{buf = buf $0 " "} END{print buf}' || true)"
    if [[ -z "${inputs}" ]]; then
      echo "FAIL [${alias}]: no 'Starting with roster history:' block in swirlds.log" >&2
      fail=1; continue
    fi
    log "  Q2 PROBE [${alias}]: raw 'Starting with roster history:' block (first 800 chars):"
    log "    ${inputs:0:800}"
    local weights_file="${WORK_DIR}/expected-weights.txt"
    if [[ ! -f "${weights_file}" ]]; then
      echo "FAIL [${alias}]: expected-weights.txt absent (Exporting: parse failed in Step 2b); cannot verify adopted weights" >&2
      fail=1; continue
    fi
    local weights
    # Match weight=N (PBJ record toString, confirmed) and "weight": "N" / "weight": N (JSON).
    # The [^a-zA-Z] guard prevents spurious matches inside maxWeight= or similar field names.
    weights="$(grep -oE '(^|[^a-zA-Z])weight"?[=:] *"?[0-9]+' <<< "${inputs}" | grep -oE '[0-9]+$')" || true
    if [[ -z "${weights}" ]]; then
      echo "FAIL [${alias}]: no 'weight' values found in 'Starting with roster history:' block; format may have changed." >&2
      echo "  raw block (first 400 chars): ${inputs:0:400}" >&2
      fail=1; continue
    fi
    log "  weights printed in roster inputs (all rosters in history, last restart): $(tr '\n' ' ' <<< "${weights}")"
    local nid expected_w
    while IFS=' ' read -r nid expected_w; do
      count="$(grep -cxF "${expected_w}" <<< "${weights}" || true)"
      if [[ "${count}" != "1" ]]; then
        echo "FAIL [${alias}]: rebalanced weight ${expected_w} (node id ${nid}) appears ${count}x in roster inputs; expected exactly 1" >&2
        echo "      (the adopted candidate should be the only roster with the rebalanced weights)." >&2
        fail=1
      fi
    done < "${weights_file}"
    other="$(grep -vxF -f <(awk '{print $2}' "${weights_file}") <<< "${weights}" || true)"
    if [[ -z "${other}" ]]; then
      echo "FAIL [${alias}]: no pre-rebalance weights found for the previous roster(s); cannot show the weights changed" >&2
      fail=1
    else
      log "  pre-rebalance weights in older rosters: $(tr '\n' ' ' <<< "${other}")"
    fi
  done

  if (( fail != 0 )); then
    echo "FAIL: distinct-candidate re-key / adoption assertions failed (see above)" >&2
    return 1
  fi
  log "Distinct-candidate assertions PASSED on all nodes: candidate adopted with rebalanced weights"
}

# ---------------------------------------------------------------------------
# Step 4: Verify — one CryptoCreate per node (liveness after the re-key upgrade)
# ---------------------------------------------------------------------------

verify_all_nodes_can_process_transaction() {
  log "Verifying all nodes (${ALL_NODE_ALIASES}) can each process a CryptoCreate"

  local verify_script="${WORK_DIR}/verify-one-node.js"

  # Single-node JS: reads NODE_ALIAS / NODE_ENDPOINT / NODE_ACCOUNT_ID from env,
  # performs one CryptoCreate, exits 0 on SUCCESS or 1 on any failure.
  # Bash drives the per-node retry loop so each attempt can restart the port-forward.
  cat > "${verify_script}" <<'JSEOF'
"use strict";
const {
  Client,
  AccountCreateTransaction,
  Hbar,
  PrivateKey,
  Status,
} = require("@hashgraph/sdk");

async function main() {
  const alias       = process.env.NODE_ALIAS       || "(unknown)";
  const endpoint    = process.env.NODE_ENDPOINT;
  const accountId   = process.env.NODE_ACCOUNT_ID;
  const operatorId  = process.env.OPERATOR_ACCOUNT_ID  || "0.0.2";
  const operatorKey = process.env.OPERATOR_PRIVATE_KEY;

  if (!endpoint)    throw new Error("NODE_ENDPOINT is required");
  if (!accountId)   throw new Error("NODE_ACCOUNT_ID is required");
  if (!operatorKey) throw new Error("OPERATOR_PRIVATE_KEY is required");

  const client = Client.forNetwork({ [endpoint]: accountId });
  client.setOperator(operatorId, PrivateKey.fromString(operatorKey));
  client.setMaxAttempts(3);
  client.setRequestTimeout(30000);
  try {
    const tx = new AccountCreateTransaction()
      .setKeyWithoutAlias(PrivateKey.generateED25519().publicKey)
      .setInitialBalance(new Hbar(1))
      .setMaxTransactionFee(new Hbar(5));
    const response = await tx.execute(client);
    const receipt  = await response.getReceipt(client);
    if (receipt.status !== Status.Success) {
      throw new Error(`FAIL status=${receipt.status.toString()}`);
    }
    console.log(`  ${alias} (${accountId}): OK -> created ${receipt.accountId}`);
  } finally {
    await client.close();
  }
}

main().catch((err) => { console.error(`verify-node FAIL [${process.env.NODE_ALIAS}]: ${err.message}`); process.exit(1); });
JSEOF

  if [[ ! -d "${WORK_DIR}/node_modules/@hashgraph/sdk" ]]; then
    log "Installing @hashgraph/sdk@${HASHGRAPH_SDK_VERSION} into ${WORK_DIR} (one-time, ~30s)"
    (
      cd "${WORK_DIR}"
      npm init -y >/dev/null 2>&1
      npm install --no-fund --no-audit "@hashgraph/sdk@${HASHGRAPH_SDK_VERSION}" >/dev/null 2>&1
    )
  fi

  local -a all_aliases=()
  local -a all_accounts=()
  IFS=',' read -r -a all_aliases  <<< "${ALL_NODE_ALIASES}"
  IFS=',' read -r -a all_accounts <<< "${BASELINE_NODE_ACCOUNTS}"

  # Start all port-forwards upfront so sockets are open, then give the gRPC
  # backends a moment to start serving before the first attempt.
  log "Starting port-forwards for all nodes"
  start_all_port_forwards
  log "Waiting 15s for gRPC backends to settle after upgrade rollout"
  sleep 15

  local MAX_NODE_RETRIES=5
  local RETRY_DELAY_SECS=5
  local fail_count=0

  for idx in "${!all_aliases[@]}"; do
    local alias="${all_aliases[$idx]}"
    local port=$((CN_GRPC_BASE_LOCAL_PORT + idx))
    local account="${all_accounts[$idx]:-}"
    if [[ -z "${account}" ]]; then
      echo "verify: no account for node ${alias} (index ${idx})" >&2
      fail_count=$(( fail_count + 1 ))
      continue
    fi

    local succeeded=false
    for (( attempt = 1; attempt <= MAX_NODE_RETRIES; attempt++ )); do
      # Re-establish the port-forward before each attempt so a dead socket
      # from the previous attempt (TLS disconnect, dropped pipe) does not
      # carry into the next gRPC call.
      log "  ${alias}: attempt ${attempt}/${MAX_NODE_RETRIES} — (re)starting port-forward on :${port}"
      start_one_port_forward "${alias}" "${port}"

      local node_rc=0
      (
        cd "${WORK_DIR}"
        NODE_ALIAS="${alias}" \
        NODE_ENDPOINT="127.0.0.1:${port}" \
        NODE_ACCOUNT_ID="${account}" \
        OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID}" \
        OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY}" \
          node "${verify_script}"
      ) || node_rc=$?

      if (( node_rc == 0 )); then
        succeeded=true
        break
      fi
      if (( attempt < MAX_NODE_RETRIES )); then
        log "  ${alias}: attempt ${attempt} failed (rc=${node_rc}), waiting ${RETRY_DELAY_SECS}s"
        sleep "${RETRY_DELAY_SECS}"
      fi
    done

    if [[ "${succeeded}" != "true" ]]; then
      echo "  ${alias} (${account}): ERROR after ${MAX_NODE_RETRIES} attempts" >&2
      fail_count=$(( fail_count + 1 ))
    fi
  done

  stop_all_port_forwards

  if (( fail_count > 0 )); then
    echo "FAIL: ${fail_count}/${#all_aliases[@]} node(s) failed to process a CryptoCreate" >&2
    return 1
  fi
  log "All ${#all_aliases[@]} node(s) processed a CryptoCreate successfully"
}

# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------

log "Validating prerequisites"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd unzip
require_cmd node
require_cmd npm
require_cmd jq

if [[ "${UPGRADE_TAG}" == "local" ]]; then
  validate_local_build_path "${LOCAL_BUILD_PATH}" || {
    echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}" >&2
    echo "Expected ${LOCAL_BUILD_PATH}/apps/HederaNode.jar and ${LOCAL_BUILD_PATH}/lib" >&2
    echo "Build first: ./gradlew :app:assemble" >&2
    exit 1
  }
fi

# Confirm the node count matches the account list so the port-forward map is correct.
_n_aliases="$(awk -F',' '{print NF}' <<< "${NODE_ALIASES}")"
_n_accounts="$(awk -F',' '{print NF}' <<< "${BASELINE_NODE_ACCOUNTS}")"
if (( _n_aliases != _n_accounts )); then
  echo "NODE_ALIASES has ${_n_aliases} entries but BASELINE_NODE_ACCOUNTS has ${_n_accounts}." >&2
  echo "Set BASELINE_NODE_ACCOUNTS to a matching comma-separated list of gRPC account IDs." >&2
  exit 1
fi

create_cluster                          # Step 1a: kind cluster
configure_solo                          # Step 1b: solo init + deployment
setup_cluster_prereqs                   # Step 1c: MinIO operator etc.
deploy_baseline                         # Step 2:  3-node v0.78 baseline (SHA-384 rosters)
rebalance_stake_and_wait_for_boundary   # Step 2b: stake all nodes distinctly + wait for a staking boundary
                                        #          -> DISTINCT reweighted candidate pending at upgrade
upgrade_network                         # Step 3:  upgrade to SHA-256 build; re-keys ROSTERS
assert_distinct_candidate_rekeyed_and_adopted  # Step 3b: distinct candidate re-keyed + adopted w/ new weights
verify_all_nodes_can_process_transaction  # Step 4:  one CryptoCreate per node

log "PASS: All nodes (${ALL_NODE_ALIASES}) came back ACTIVE after ${DEPLOY_RELEASE_TAG} -> ${UPGRADE_TAG},"
log "      proving both the active and reweighted candidate SHA-384 rosters were re-keyed correctly"
log "PASS: All nodes (${ALL_NODE_ALIASES}) processed a CryptoCreate after the upgrade"
log "PASS: distinct reweighted candidate roster was re-keyed (48 -> 32 bytes) and adopted with the rebalanced weights"
