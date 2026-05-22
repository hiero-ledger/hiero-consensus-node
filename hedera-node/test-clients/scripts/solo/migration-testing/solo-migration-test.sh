#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Steps 1-3 of issue hiero-ledger/hiero-consensus-node#25552 (Migration Testing
# Regression Panel) as a stand-alone driver. Trimmed from the e2e-solo-cutover
# branch's solo-e2e-075-to-076-tss.sh — TSS/WRAPS-specific plumbing removed.
#
#   Step 1 — Version detection
#     Read version.txt, derive MAJOR.MINOR, compute the previous minor series,
#     pick the latest matching v<MAJOR>.<MINOR-1>.* tag, derive UPGRADE_VERSION
#     by stripping -SNAPSHOT.
#
#   Step 2 — Solo 3-node + Mirror Node deployment at the previous-minor tag
#     kind cluster + solo {init, cluster-ref, deployment, cluster-ref-setup,
#     keys, consensus network deploy, consensus node setup, consensus node
#     start, mirror node add --pinger} — all against the released CN image at
#     the tag computed in step 1.
#
#   Step 3 — Upgrade to the local build (current branch)
#     `solo consensus network upgrade --local-build-path` with UPGRADE_VERSION,
#     wait for pods + haproxy, then verify each consensus node is actually
#     running the local build's HederaNode.jar via META-INF/MANIFEST.MF.
#
# Steps 4+ (CryptoCreate smoke, runner label, Slack reporting, XTS plumbing)
# are out of scope here — they belong in the wrapping `zxc-*.yaml` workflow,
# not in this script.

set -euo pipefail
set +m

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../../" && pwd)"

# Prefer an explicit SOLO_BIN_DIR; otherwise look in the usual non-sudo npm
# global prefix (`npm install -g --prefix ~/.npm-global`) before falling back
# to whatever's on PATH. macOS users routinely hit this when /usr/local is
# root-owned and they install solo into a user-writable prefix.
for d in \
    "${SOLO_BIN_DIR:-}" \
    "${HOME}/.npm-global/bin" \
    "$(npm config get prefix 2>/dev/null)/bin" ; do
  [[ -n "${d}" && -x "${d}/solo" ]] && PATH="${d}:${PATH}" && break
done
export PATH

SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-solo-migration}"
SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-migration}"
SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-setup}"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
SOLO_UPGRADE_TIMEOUT_SECS="${SOLO_UPGRADE_TIMEOUT_SECS:-1800}"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/solo-migration.XXXXXX")"
CLUSTER_CREATED_THIS_RUN="false"

# Filled in by step 1.
DEPLOY_RELEASE_TAG=""
UPGRADE_VERSION=""

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command not found: $1" >&2
    exit 1
  }
}

cleanup() {
  local ec=$?
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
  local node pod nodes=() local_version="" pod_version=""

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

# === Step 1 ====================================================================
# Read version.txt -> extract MAJOR.MINOR -> previous minor -> latest matching
# released/RC tag. Derive UPGRADE_VERSION by stripping -SNAPSHOT.
compute_versions() {
  local raw major_minor major minor prev_series prev_tag
  raw="$(cat "${REPO_ROOT}/version.txt")"
  major_minor="$(printf '%s' "${raw}" | cut -d. -f1,2)"
  major="${major_minor%.*}"
  minor="${major_minor#*.}"
  prev_series="${major}.$((minor - 1))"

  log "version.txt   : ${raw}"
  log "current series: ${major_minor}"
  log "previous series: ${prev_series}"

  log "Fetching tags to resolve latest v${prev_series}.* release/rc"
  git -C "${REPO_ROOT}" fetch --tags --quiet

  prev_tag="$(git -C "${REPO_ROOT}" tag -l "v${prev_series}.*" | sort -V | tail -n 1)"
  [[ -n "${prev_tag}" ]] || {
    echo "No tag matches v${prev_series}.*; cannot determine previous-minor baseline" >&2
    return 1
  }

  DEPLOY_RELEASE_TAG="${prev_tag}"
  UPGRADE_VERSION="v${raw%-SNAPSHOT}"

  # `solo consensus network upgrade --upgrade-version <tag>` validates the tag
  # against solo's image registry before applying --local-build-path. For an
  # in-development MAJOR.MINOR (no rc or final yet published) the derived label
  # won't resolve, so fall back to the deploy tag — the label is purely
  # informational; what actually gets staged is `--local-build-path`.
  if ! git -C "${REPO_ROOT}" rev-parse -q --verify "refs/tags/${UPGRADE_VERSION}" >/dev/null 2>&1; then
    log "No published tag ${UPGRADE_VERSION}; falling back upgrade label to ${DEPLOY_RELEASE_TAG}"
    UPGRADE_VERSION="${DEPLOY_RELEASE_TAG}"
  fi

  log "DEPLOY_RELEASE_TAG = ${DEPLOY_RELEASE_TAG}"
  log "UPGRADE_VERSION    = ${UPGRADE_VERSION}"
}

# === Step 2 ====================================================================
# kind + solo prereqs.
create_cluster() {
  log "Deleting existing Kind cluster ${SOLO_CLUSTER_NAME} (if any)"
  kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true

  log "Creating Kind cluster ${SOLO_CLUSTER_NAME}"
  kind create cluster -n "${SOLO_CLUSTER_NAME}"
  CLUSTER_CREATED_THIS_RUN="true"
}

configure_solo() {
  local consensus_node_count
  consensus_node_count="$(awk -F',' '{print NF}' <<< "${NODE_ALIASES}")"

  log "solo init"
  solo init

  log "Configuring Solo deployment ${SOLO_DEPLOYMENT} (${consensus_node_count} CNs)"
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
  log "Installing Solo cluster prerequisites (MinIO operator, ClusterRole, etc.)"
  solo cluster-ref config setup \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --cluster-setup-namespace "${SOLO_CLUSTER_SETUP_NAMESPACE}"
}

# Deploy the baseline 3-node consensus network at the previous-minor tag and
# attach a Mirror Node with the pinger enabled.
deploy_baseline() {
  log "Deploying baseline consensus network at ${DEPLOY_RELEASE_TAG}"

  solo keys consensus generate \
    --gossip-keys \
    --tls-keys \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}"

  # `--pvcs true` is required because step 3 uses `consensus network upgrade
  # --local-build-path`, which stages new JARs through persistent volumes that
  # must survive the upgrade-driven pod restarts.
  solo consensus network deploy \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}" \
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

  log "Adding Mirror Node (with Pinger enabled)"
  # Race-fix for Apple Silicon: the mirror-1-web3 Spring Boot JVM cold-starts
  # in ~3-5 min under Rosetta translation, but the chart's default web3 probes
  # are way too tight for that:
  #   - startupProbe: failureThreshold=60, period=1s  -> 60s of runway before
  #     kubelet sends SIGKILL (exit 137) and the pod restart-loops
  #   - liveness/readiness: initialDelay=0, failureThreshold=60, period=1s
  # The startupProbe is the actual gatekeeper; while it is in effect the
  # liveness probe is suspended. Native amd64 Linux CI doesn't need this
  # patch (JVM cold-start fits within the default 60s), but the patch is
  # harmless there. As soon as solo's helm install creates the deployment,
  # bump all three probes so the JVM has up to ~5 min of cold-start headroom.
  ( deadline=$((SECONDS + 600))
    while (( SECONDS < deadline )); do
      if kubectl -n "${SOLO_NAMESPACE}" get deployment mirror-1-web3 >/dev/null 2>&1; then
        kubectl -n "${SOLO_NAMESPACE}" patch deployment mirror-1-web3 --type=json -p='[
          {"op":"replace","path":"/spec/template/spec/containers/0/startupProbe/failureThreshold","value":300},
          {"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/initialDelaySeconds","value":60},
          {"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/failureThreshold","value":120},
          {"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/initialDelaySeconds","value":60},
          {"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/failureThreshold","value":120}
        ]' >/dev/null 2>&1 && log "Patched mirror-1-web3 probes (startup=300, liveness/readiness delay=60s threshold=120)" && exit 0
      fi
      sleep 2
    done
    log "WARN: mirror-1-web3 did not appear within 10 min; probe-patch helper exiting" ) &
  local _web3_patch_pid=$!

  solo mirror node add \
    --deployment "${SOLO_DEPLOYMENT}" \
    --cluster-ref "kind-${SOLO_CLUSTER_NAME}" \
    --pinger

  wait "${_web3_patch_pid}" 2>/dev/null || true
}

# === Step 3 ====================================================================
# Upgrade the running consensus network to the local build (current branch
# checkout) labeled UPGRADE_VERSION, then verify each CN is actually running
# the local build's HederaNode.jar.
upgrade_to_local() {
  log "Upgrading consensus network to local build (labeled ${UPGRADE_VERSION})"

  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --upgrade-version "${UPGRADE_VERSION}"
    --local-build-path "${LOCAL_BUILD_PATH}"
    --quiet-mode
    --force
  )

  run_command_with_timeout "${SOLO_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"

  log "Waiting for post-upgrade pod readiness"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600

  log "Verifying every consensus node is running the local build"
  verify_local_build_on_consensus_nodes
}

# === Driver ====================================================================
log "Validating prerequisites"
require_cmd kind
require_cmd kubectl
require_cmd solo
require_cmd git
require_cmd unzip

validate_local_build_path "${LOCAL_BUILD_PATH}" || {
  echo "Invalid LOCAL_BUILD_PATH: ${LOCAL_BUILD_PATH}" >&2
  echo "Expected ${LOCAL_BUILD_PATH}/apps/HederaNode.jar and ${LOCAL_BUILD_PATH}/lib" >&2
  echo "Build it first with: ./gradlew :app:assemble" >&2
  exit 1
}

compute_versions             # Step 1
create_cluster               # Step 2 (prereq: kind cluster)
configure_solo               #         solo init + cluster-ref + deployment
setup_cluster_prereqs        #         MinIO operator
deploy_baseline              #         CN @ prev_tag + MN with pinger
upgrade_to_local             # Step 3 (CN upgrade to local build)

log "PASS: baseline ${DEPLOY_RELEASE_TAG} -> local ${UPGRADE_VERSION} upgrade completed"
