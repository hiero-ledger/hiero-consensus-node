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

# Apple Silicon: the NMT binary at /opt/hgcapp/node-mgmt-tools/bin/nmt is
# x86-64-only (built dynamically against /lib64/ld-linux-x86-64.so.2). On an
# arm64 macOS host, Solo by default deploys arm64 pods, which have neither
# the x86-64 dynamic linker nor a translation layer — `nmt preflight` then
# dies with `rosetta error: failed to open elf at /lib64/ld-linux-x86-64.so.2`
# and exit 133. Forcing DOCKER_DEFAULT_PLATFORM=linux/amd64 makes Docker
# Desktop pull amd64 images and translate everything through Rosetta-for-Linux
# (or fall back to QEMU, which is slower but still works). Expect ~1.5-2x
# wall time vs an amd64 host. Rosetta-for-Linux must be enabled in Docker
# Desktop (Settings -> General).
if [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]]; then
  if [[ "${DOCKER_DEFAULT_PLATFORM:-}" != "linux/amd64" ]]; then
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" \
      "Apple Silicon detected; exporting DOCKER_DEFAULT_PLATFORM=linux/amd64 (NMT is x86-64-only)"
    export DOCKER_DEFAULT_PLATFORM=linux/amd64
  fi
  # Fail fast if Docker Desktop is going to silently fall back to QEMU.
  # QEMU "works" for trivial containers (uname -m prints x86_64) but
  # kubelet hangs in wait-control-plane for 4 minutes before timing out.
  # Rosetta-for-Linux registers a binfmt_misc handler named "rosetta" --
  # if it's absent inside an amd64 container, the user hasn't enabled
  # "Use Rosetta for x86/amd64 emulation" in Docker Desktop.
  if ! docker run --rm --platform=linux/amd64 alpine \
       test -e /proc/sys/fs/binfmt_misc/rosetta >/dev/null 2>&1; then
    cat >&2 <<'EOF'
Docker Desktop is falling back to QEMU for amd64 emulation on this arm64
host. The kind cluster will hang in kubeadm wait-control-plane for 4
minutes before timing out. Enable Rosetta first:

  Docker Desktop -> Settings -> General
    -> Virtual Machine Manager: Apple Virtualization framework
    -> [x] Use Rosetta for x86/amd64 emulation on Apple Silicon
    -> Apply & Restart

Then re-run this script.
EOF
    exit 1
  fi
fi

SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-solo-migration}"
SOLO_DEPLOYMENT="${SOLO_DEPLOYMENT:-solo-migration}"
SOLO_NAMESPACE="${SOLO_NAMESPACE:-solo}"
SOLO_CLUSTER_SETUP_NAMESPACE="${SOLO_CLUSTER_SETUP_NAMESPACE:-solo-setup}"
NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"

LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
SOLO_UPGRADE_TIMEOUT_SECS="${SOLO_UPGRADE_TIMEOUT_SECS:-1800}"

# Real-NMT upgrade flow (issue #25736). Replaces `solo consensus node setup`
# / `start` with NMT install + `nmt watch`, and replaces `solo consensus
# network upgrade` with yahcli-driven PREPARE_UPGRADE + FREEZE_UPGRADE.
NMT_VERSION="${NMT_VERSION:-v1.3.4}"
NMT_INSTALLER_BASENAME="node-mgmt-tools-installer-v1.3.4-efea0dd4.run"
NMT_INSTALLER_URL="https://builds.hedera.com/node/mgmt-tools/v1.3/${NMT_INSTALLER_BASENAME}"
NMT_PROFILE="jrs"
OPENJDK_VERSION="${OPENJDK_VERSION:-25.0.2}"
# NMT-baked consensus-node image (built from nmt-image/Dockerfile, side-loaded
# into kind, referenced by Solo via nmt-image/solo-values-override.yaml). Must
# stay in sync with the image:tag literals in that values file.
NMT_IMAGE_DIR="${SCRIPT_DIR}/nmt-image"
NMT_IMAGE_REPO="${NMT_IMAGE_REPO:-solo-nmt-network-node}"
NMT_IMAGE_TAG="${NMT_IMAGE_TAG:-v1.3.4-jdk25}"
NMT_IMAGE_VALUES_FILE="${NMT_IMAGE_DIR}/solo-values-override.yaml"
# Hedera CN v0.75+ JARs are compiled to class file v69 (JDK 25). NMT v1.3.4
# defaults to JDK 21 in helper.sh but ships checksums for 25.0.2; we override.
JAVA_MAIN_CLASS_OVERRIDE="${JAVA_MAIN_CLASS_OVERRIDE:-com.hedera.node.app.ServicesMain}"
HGCAPP_DIR="/opt/hgcapp"
NMT_DIR="${HGCAPP_DIR}/node-mgmt-tools"
HAPI_PATH="${HGCAPP_DIR}/services-hedera/HapiApp2.0"
UPGRADE_CURRENT_DIR="${HAPI_PATH}/data/upgrade/current"
HEDERA_HOME_DIR="/home/hedera"
CACHE_DIR="${CACHE_DIR:-${HOME}/.cache/hiero-migration}"
YAHCLI_JAR="${YAHCLI_JAR:-}"
# yahcli uses 0.0.58 (the system-files admin) as the operator for the
# software-zip upload + PREPARE_UPGRADE + FREEZE_UPGRADE transactions. Solo
# seeds the same well-known dev key onto that account, so OPERATOR_PRIVATE_KEY
# below is the right input for generate_yahcli_creds.
YAHCLI_OPERATOR_ACCOUNT_NUM="${YAHCLI_OPERATOR_ACCOUNT_NUM:-58}"
YAHCLI_KEY_PASSPHRASE="${YAHCLI_KEY_PASSPHRASE:-migration-test}"
MARKER_TIMEOUT_SECS="${MARKER_TIMEOUT_SECS:-600}"
UPGRADE_RESTART_TIMEOUT_SECS="${UPGRADE_RESTART_TIMEOUT_SECS:-900}"
NETWORK_ACTIVE_TIMEOUT_SECS="${NETWORK_ACTIVE_TIMEOUT_SECS:-900}"

# Filled in by fetch_artifacts / build_upgrade_zip.
PLATFORM_INSTALLER_BASENAME=""
PLATFORM_INSTALLER_URL=""
PLATFORM_INSTALLER_PATH=""
NMT_INSTALLER_PATH=""
UPGRADE_ZIP_PATH=""
UPGRADE_ZIP_SHA384=""
YAHCLI_PF_PID=""

# Step 4 (CryptoCreate smoke) port-forwards + operator credentials.
# Account 0.0.2 + the well-known genesis Ed25519 dev key is what Solo's local
# deployments seed by default. Override via env vars in CI if needed.
CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"
SMOKE_POLL_TIMEOUT_MS="${SMOKE_POLL_TIMEOUT_MS:-60000}"

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
  # #25736: kill the yahcli port-forward and any in-pod `nmt watch`
  # processes before tearing down the cluster.
  [[ -n "${YAHCLI_PF_PID}" ]] && kill "${YAHCLI_PF_PID}" >/dev/null 2>&1 || true
  if [[ "${CLUSTER_CREATED_THIS_RUN}" == "true" ]]; then
    for _pod in $(iterate_pods 2>/dev/null); do
      kexec "${_pod}" pkill -f "nmt watch" >/dev/null 2>&1 || true
    done
  fi
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
  # Pin Kubernetes v1.30 — kind's default (v1.35 today) hangs in
  # wait-control-plane under Rosetta-for-Linux on Apple Silicon. v1.30.x
  # is the last release confirmed to survive amd64 emulation reliably.
  # Override via KIND_NODE_IMAGE if you're on amd64 and want the default.
  kind create cluster -n "${SOLO_CLUSTER_NAME}" \
    --image "${KIND_NODE_IMAGE:-kindest/node:v1.30.4}"
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

  # Build + side-load the NMT-baked root-container image so Solo's StatefulSet
  # picks it up via the values override below. Doing this here (instead of in
  # bring_up_consensus_via_nmt) means it happens before solo helm-installs the
  # network-node chart — once the StatefulSet exists, image changes require a
  # rolling restart.
  build_and_load_nmt_image

  solo keys consensus generate \
    --gossip-keys \
    --tls-keys \
    --deployment "${SOLO_DEPLOYMENT}" \
    --node-aliases "${NODE_ALIASES}"

  # `--pvcs true` is required because step 3 uses `consensus network upgrade
  # --local-build-path`, which stages new JARs through persistent volumes that
  # must survive the upgrade-driven pod restarts.
  #
  # Image override path: Solo CLI's `addRootImageValues()` injects per-node
  # `hedera.nodes[N].root.image.{registry,repository,tag}` via helm `--set`,
  # which beats anything we put in `--values-file` (per Helm precedence).
  # The three SOLO_S6_NODE_IMAGE_* env vars override the defaults Solo uses
  # in that --set, so they're the only way to redirect the root-container
  # image at the per-node level. The `--values-file` still carries
  # `pullPolicy: Never` (Solo doesn't --set pullPolicy, so the values-file
  # wins for that one key).
  #
  # The deploy is wrapped in a diagnostic harness: solo's own pod-ready check
  # times out at 900 attempts (~15min). If Solo gives up, an EXIT trap on the
  # subshell dumps `kubectl get pods/events/describe` so the CI log shows
  # *why* the root-container never started (image pull, volume mount, etc.)
  # instead of just a generic "phases: Running not found" error.
  (
    diag_dump() {
      ec=$?
      [[ ${ec} -eq 0 ]] && return
      local pod_ns="${SOLO_NAMESPACE}"
      echo "===== diagnostic dump after solo deploy exit ${ec} ====="
      kubectl -n "${pod_ns}" get pods -o wide 2>&1 | head -20
      echo "----- events (last 40) -----"
      kubectl -n "${pod_ns}" get events --sort-by=.lastTimestamp 2>&1 | tail -40
      for pod in $(iterate_pods); do
        echo "----- describe ${pod} -----"
        kubectl -n "${pod_ns}" describe pod "${pod}" 2>&1 \
          | awk '/^Status:|^Init Containers:|^Containers:|^Conditions:|^Volumes:|^Events:/{flag=1} flag' \
          | head -80
      done
      echo "----- crictl images on kind node -----"
      docker exec "${SOLO_CLUSTER_NAME}-control-plane" \
        crictl images 2>&1 | grep -E 'solo-nmt|REPOSITORY' | head -10
      echo "===== end diagnostic dump ====="
    }
    trap diag_dump EXIT
    SOLO_S6_NODE_IMAGE_REGISTRY=localhost \
    SOLO_S6_NODE_IMAGE_REPOSITORY="${NMT_IMAGE_REPO}" \
    SOLO_S6_NODE_IMAGE_VERSION="${NMT_IMAGE_TAG}" \
    solo consensus network deploy \
      --deployment "${SOLO_DEPLOYMENT}" \
      --node-aliases "${NODE_ALIASES}" \
      --pvcs true \
      --values-file "${NMT_IMAGE_VALUES_FILE}" \
      --release-tag "${DEPLOY_RELEASE_TAG}"
  )

  wait_for_consensus_pods_ready 600

  # #25736: instead of `solo consensus node setup` + `start`, install real
  # NMT inside each pod, start `nmt watch`, and let NMT bring up the JVM
  # via its docker-compose stack — mirrors mainnet topology.
  fetch_artifacts
  bring_up_consensus_via_nmt
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
# #25736: upgrade through real PREPARE_UPGRADE + FREEZE_UPGRADE transactions.
# NMT's `nmt watch` inside each pod sees now_frozen.mf via inotify, stops
# the JVM, swaps artifacts from data/upgrade/pending/ -> current/, and
# restarts on the local build. This is the production code path; the old
# `solo consensus network upgrade --local-build-path` bypassed it.
upgrade_to_local() {
  build_upgrade_zip
  ensure_yahcli
  start_yahcli_port_forward

  yahcli_run sysfiles upload software-zip
  yahcli_run prepare-upgrade --upgrade-zip-hash "${UPGRADE_ZIP_SHA384}"
  wait_for_marker "execute_immediate.mf" "${MARKER_TIMEOUT_SECS}"

  local freeze_at
  freeze_at="$(date -u -v+30S '+%Y-%m-%d.%H:%M:%S' 2>/dev/null \
    || date -u -d '+30 seconds' '+%Y-%m-%d.%H:%M:%S')"
  yahcli_run freeze-upgrade --upgrade-zip-hash "${UPGRADE_ZIP_SHA384}" \
    --start-time "${freeze_at}"

  wait_for_marker "now_frozen.mf" "${MARKER_TIMEOUT_SECS}"

  log "Waiting for NMT to restart each node on the local build"
  local pod
  for pod in $(iterate_pods); do
    wait_for_node_active "${pod}" "${UPGRADE_RESTART_TIMEOUT_SECS}"
  done

  log "Verifying every consensus node is running the local build"
  verify_local_build_on_consensus_nodes
}

# === NMT helpers (issue #25736) ==============================================
# Real-NMT bring-up + freeze-upgrade flow, vendored/adapted from
# solo-charts' .github/workflows/support/scripts/helper.sh.

# Build the NMT-baked consensus-node image and side-load it into the kind
# cluster so Solo can pull it with imagePullPolicy=Never. Replaces the
# previous runtime `sudo apt-get install ... ; sudo /.../nmt-installer.run`
# chain inside the pod — sudo was failing in ARC GitHub runners with
# `PAM account management error` even when the root-container is uid 0.
build_and_load_nmt_image() {
  log "Building NMT-baked consensus-node image ${NMT_IMAGE_REPO}:${NMT_IMAGE_TAG} and side-loading into kind"
  IMAGE_REPO="${NMT_IMAGE_REPO}" \
  IMAGE_TAG="${NMT_IMAGE_TAG}" \
  KIND_CLUSTER="${SOLO_CLUSTER_NAME}" \
    "${NMT_IMAGE_DIR}/build.sh"
}

kexec() {
  local pod="$1"; shift
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- "$@"
}

kcp() {
  local src="$1" pod="$2" dst="$3"
  kubectl -n "${SOLO_NAMESPACE}" cp "${src}" "${pod}:${dst}" -c root-container
}

iterate_pods() {
  local node nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    printf '%s\n' "network-${node}-0"
  done
}

# Fetch NMT installer + previous-minor platform build, cached on the runner.
# Builds URL from DEPLOY_RELEASE_TAG using the builds.hedera.com layout:
# node/software/v<MAJOR>.<MINOR>/build-<tag>.zip
fetch_artifacts() {
  local nmt_cache="${CACHE_DIR}/nmt"
  local plat_cache="${CACHE_DIR}/platform"
  mkdir -p "${nmt_cache}" "${plat_cache}"

  # builds.hedera.com lays the bucket out as node/software/v<MAJOR>.<MINOR>/
  # (no patch). The previous parameter-expansion attempt stripped after the
  # last `.`, which for an rc tag (v0.75.0-rc.4) lands inside the rc suffix
  # and yields v0.75.0 — a directory that doesn't exist.
  local prev_major_minor="v$(echo "${DEPLOY_RELEASE_TAG#v}" | cut -d. -f1,2)"
  PLATFORM_INSTALLER_BASENAME="build-${DEPLOY_RELEASE_TAG}.zip"
  PLATFORM_INSTALLER_URL="https://builds.hedera.com/node/software/${prev_major_minor}/${PLATFORM_INSTALLER_BASENAME}"
  NMT_INSTALLER_PATH="${nmt_cache}/${NMT_INSTALLER_BASENAME}"
  PLATFORM_INSTALLER_PATH="${plat_cache}/${PLATFORM_INSTALLER_BASENAME}"

  if [[ ! -f "${NMT_INSTALLER_PATH}" ]]; then
    log "Fetching NMT installer ${NMT_VERSION}"
    curl -sSL --fail -o "${NMT_INSTALLER_PATH}" "${NMT_INSTALLER_URL}"
  else
    log "Using cached NMT installer ${NMT_INSTALLER_PATH}"
  fi

  if [[ ! -f "${PLATFORM_INSTALLER_PATH}" ]]; then
    log "Fetching platform build ${DEPLOY_RELEASE_TAG}"
    curl -sSL --fail -o "${PLATFORM_INSTALLER_PATH}" "${PLATFORM_INSTALLER_URL}"
  else
    log "Using cached platform build ${PLATFORM_INSTALLER_PATH}"
  fi
}

# Generate JRS-style config.txt from real pod IPs. Adapted from
# helper.sh::prep_address_book. The other JRS configs (log4j2.xml,
# settings.txt, application/api-permission/bootstrap.properties and
# genesis-network.json) come from Solo's network-node-data-config-cm
# ConfigMap via the snapshot+restore in install_nmt_in_pod.
prep_address_book_and_configs() {
  local config_file="${WORK_DIR}/config.txt"
  local node_seq=0 account_id_seq=3
  local node nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  : > "${config_file}"
  echo "swirld, 123" >> "${config_file}"
  echo "app, HederaNode.jar" >> "${config_file}"

  for node in "${nodes[@]}"; do
    local pod="network-${node}-0" pod_ip svc_ip
    pod_ip="$(kubectl -n "${SOLO_NAMESPACE}" get pod "${pod}" -o jsonpath='{.status.podIP}')"
    svc_ip="$(kubectl -n "${SOLO_NAMESPACE}" get svc "network-${node}-svc" -o jsonpath='{.spec.clusterIP}')"
    [[ -n "${pod_ip}" && -n "${svc_ip}" ]] || {
      echo "Missing pod/svc IP for ${node}" >&2; return 1
    }
    echo "address, ${node_seq}, ${node}, ${node}, 1, ${pod_ip}, 50111, ${svc_ip}, 50111, 0.0.${account_id_seq}" >> "${config_file}"
    node_seq=$((node_seq + 1))
    account_id_seq=$((account_id_seq + 1))
  done
  echo "nextNodeId, ${node_seq}" >> "${config_file}"

  log "Generated address book:"
  sed 's/^/  /' "${config_file}"
}

# Prepare a Solo pod for an NMT install. The base image (see nmt-image/
# Dockerfile) already ships NMT v1.3.4, the apt/dnf prereqs, the canonical
# /opt/hgcapp layout, the Ubuntu /etc/os-release spoof, the hedera-in-docker
# group, and a passwordless sudoers entry. This function only does the bits
# that genuinely need runtime state from inside the pod: detach Solo's bind
# mounts (so NMT's snapshot rm-rf doesn't EBUSY) and start dockerd by hand
# (no systemd PID 1, so the daemon can't autostart).
install_nmt_in_pod() {
  local pod="$1"

  # Solo's network-node StatefulSet template bind-mounts per-subdir paths
  # from the kind node's /dev/vda1 under /opt/hgcapp/ (HapiApp2.0/{data/*,
  # output,state} plus blockStreams, eventsStreams, recordStreams). NMT's
  # snapshot/rotation logic (bkp_folder_enable_snapshot_support in
  # tools/common/backup/backup_folder.inc.sh) does `rm -rf` of the whole
  # HapiApp2.0 tree, which fails with EBUSY on every mount point.
  # `--pvcs false` doesn't help — the mounts are part of the pod template,
  # not the PVC machinery. Detach them with lazy umount before nmt preflight
  # runs. We're inside a privileged container with full CAP_SYS_ADMIN, so
  # umount succeeds; the directories become empty regular dirs on the pod's
  # container filesystem. The consensus-node JVM isn't running yet (NMT
  # hasn't started it), so nothing's holding files open in those mounts.
  #
  # Two of those mounts arrive pre-populated by Solo's init container
  # (`cp -L /data/config/genesis-network.json ...` and the bind-mounted
  # gossip key secret): data/config (genesis-network.json, application
  # /api-permission/bootstrap.properties, settings.txt, log4j2.xml) and
  # data/keys (the node's gossip + TLS keys). The JVM won't reach ACTIVE
  # without them. Snapshot every non-empty mount to a tarball under /tmp
  # *before* the lazy umount, then restore the contents to the (now
  # detached) directory afterwards. Mounts that arrive empty (output,
  # state, the *Streams dirs) are skipped — the tar is a no-op anyway.
  log "Snapshotting + detaching Solo's per-subdir bind mounts under /opt/hgcapp in ${pod}"
  kexec "${pod}" bash -c "
    set -e
    snapdir=/tmp/hgcapp-snap
    rm -rf \$snapdir && mkdir -p \$snapdir
    # sort -r so we umount deepest-first (children before parents).
    for mp in \$(mount | awk '/\\/opt\\/hgcapp\\// {print \$3}' | sort -r); do
      # Skip empty mounts — saves several tar invocations per pod and keeps
      # the restore loop cheap. find -mindepth 1 -quit short-circuits on the
      # first entry, so this is O(1) for empty dirs.
      if [ -n \"\$(find \"\$mp\" -mindepth 1 -print -quit 2>/dev/null)\" ]; then
        # Each mount gets its own tar; the path is sanitised to a flat name
        # (slashes -> dashes) so we can pair tar <-> mountpoint after umount.
        safe=\$(echo \"\$mp\" | sed 's|^/||; s|/|-|g')
        tar -cf \"\$snapdir/\$safe.tar\" -C \"\$mp\" . 2>/dev/null || true
      fi
      umount -l \"\$mp\" 2>/dev/null || true
    done
    # Restore the snapshots into the now-detached directories. The
    # directories are still there (umount just detaches the mount, the
    # underlying image-baked dir is unchanged), so we extract back into
    # them. Re-derive the mountpoint from the safe name.
    for tarball in \"\$snapdir\"/*.tar; do
      [ -f \"\$tarball\" ] || continue
      base=\$(basename \"\$tarball\" .tar)
      mp=/\$(echo \"\$base\" | sed 's|-|/|g')
      mkdir -p \"\$mp\"
      tar -xf \"\$tarball\" -C \"\$mp\"
    done
    rm -rf \$snapdir
  "

  # Re-assert ownership on the canonical NMT/HAPI dirs after umount — the
  # umount uncovers the image-baked directories underneath, which already
  # have the right ownership, but a defensive chown is cheap insurance.
  # Also chown the just-restored data/config + data/keys so the JVM (running
  # as hedera) can read them.
  kexec "${pod}" chown -R hedera:hedera \
    "${NMT_DIR}/state" "${NMT_DIR}/logs" "${HGCAPP_DIR}/services-hedera"

  # Start dockerd in the background. NMT install builds the swirlds-node and
  # swirlds-haveged docker images (and `nmt start` later docker-compose-ups
  # them); without a running daemon, install dies with "failed to connect to
  # the docker API at unix:///var/run/docker.sock". The Solo container has
  # no systemd PID 1, so we launch dockerd by hand. The container is
  # privileged + full CAP_SYS_ADMIN, so DinD nesting works. --storage-driver=
  # vfs is necessary because the kind node already uses overlayfs and overlay-
  # on-overlay fails during image build.
  log "Starting dockerd in ${pod} (NMT install needs the docker daemon)"
  kexec "${pod}" bash -c "
    if ! pgrep -x dockerd >/dev/null; then
      # --storage-driver=vfs: nested DinD doesn't stack overlay-on-overlay
      # cleanly. The kind node already uses overlayfs; trying to do overlay
      # again inside fails with 'mount source: overlay ... too many levels
      # of symbolic links' during image build. vfs is much slower (no CoW)
      # but works in any nested context.
      (nohup dockerd --storage-driver=vfs > /var/log/dockerd.log 2>&1 &) </dev/null
    fi
    for i in \$(seq 1 30); do
      [ -S /var/run/docker.sock ] && exit 0
      sleep 1
    done
    echo 'dockerd did not start in 30s' >&2
    tail -30 /var/log/dockerd.log >&2 || true
    exit 1
  "
}

install_platform_via_nmt() {
  local pod="$1" node="$2"
  log "Copying platform build to ${pod}"
  kcp "${PLATFORM_INSTALLER_PATH}" "${pod}" "${HEDERA_HOME_DIR}/${PLATFORM_INSTALLER_BASENAME}"

  # Use the legacy shell wrapper (node-mgmt-tool) for preflight/install/start/
  # stop. The newer Go binary at ${NMT_DIR}/bin/nmt has a different command
  # set (watch, upgrade dispatch, ...) and rejects these subcommands with
  # `unknown command "preflight" for "nmt"`. We use the Go binary later in
  # start_nmt_watcher() for `nmt watch`, which IS one of its commands.
  log "nmt preflight in ${pod}"
  kexec "${pod}" "${NMT_DIR}/bin/node-mgmt-tool" -VV preflight \
    -j "${OPENJDK_VERSION}" -df -i "${NMT_PROFILE}" -k 256m -m 512m

  # NMT v1.3.4 requires both -n <node_alias> and -g <node_id>. Solo's node
  # aliases are node1/node2/node3; NMT's numeric ids are 0/1/2.
  local node_id="${node#node}"
  node_id=$((node_id - 1))

  # NMT's jrs-network-node Dockerfile does `FROM gcr.io/swirlds-registry/
  # network-node-base:jdk-X.Y.Z`. That registry is private, so we have to
  # build the base ourselves with the matching JDK before nmt install can
  # build the jrs image on top. NMT v1.3.4 ships checksum files for jdk-25.0.2.
  log "Pre-building network-node-base:jdk-${OPENJDK_VERSION} (private gcr.io workaround)"
  # `runuser -u hedera` instead of `sudo -u hedera`: PAM is not consulted, so
  # ARC runners (where /etc/shadow doesn't list uid 2000) don't fail with
  # `PAM account management error`. The baked image puts hedera in the docker
  # group already, so this docker build has socket access.
  kexec "${pod}" runuser -u hedera -- bash -c "
    docker image inspect gcr.io/swirlds-registry/network-node-base:jdk-${OPENJDK_VERSION} >/dev/null 2>&1 || \
      docker build --tag gcr.io/swirlds-registry/network-node-base:jdk-${OPENJDK_VERSION} \
        --progress plain ${NMT_DIR}/images/network-node-base 2>&1 | tail -5
  "

  log "nmt install in ${pod} (platform ${DEPLOY_RELEASE_TAG}, node ${node}/id=${node_id}, JDK ${OPENJDK_VERSION})"
  # `-e <main_class>` forces the post-v0.40 entry point. Stock NMT defaults
  # to com.swirlds.platform.Browser (removed in platform v0.40+), so without
  # this the JVM exits with ClassNotFoundException on startup.
  kexec "${pod}" "${NMT_DIR}/bin/node-mgmt-tool" -VV install \
    -p "${HEDERA_HOME_DIR}/${PLATFORM_INSTALLER_BASENAME}" \
    -n "${node}" \
    -g "${node_id}" \
    -x "${DEPLOY_RELEASE_TAG}" \
    -j "${OPENJDK_VERSION}" \
    -e "${JAVA_MAIN_CLASS_OVERRIDE}"
}

seed_node_config() {
  local pod="$1"
  log "Seeding config.txt into ${pod}:${HAPI_PATH}"
  kcp "${WORK_DIR}/config.txt" "${pod}" "${HAPI_PATH}/config.txt"
  kexec "${pod}" chown hedera:hedera "${HAPI_PATH}/config.txt"

  # log4j2.xml, settings.txt, application.properties, api-permission.properties,
  # bootstrap.properties and genesis-network.json arrive via the snapshot+restore
  # path in install_nmt_in_pod (Solo's init container copies them into
  # data/config before we kexec in, and we re-stage them across the umount).

  # gc.log workaround from helper.sh — JVM refuses to start without it.
  kexec "${pod}" touch "${HAPI_PATH}/gc.log"
  kexec "${pod}" chown hedera:hedera "${HAPI_PATH}/gc.log"
}

# Manual stand-in for nmt-ics.service. The subshell + nohup + & is needed
# so kubectl exec returns instead of blocking on the watcher.
start_nmt_watcher() {
  local pod="$1"
  log "Starting nmt watch in ${pod}"
  kexec "${pod}" bash -c \
    "(nohup ${NMT_DIR}/bin/nmt watch -L debug > /tmp/nmt-watch.log 2>&1 &) ; sleep 1"
  if ! kexec "${pod}" pgrep -f "nmt watch" >/dev/null; then
    echo "nmt watch did not stay up in ${pod}" >&2
    kexec "${pod}" tail -50 /tmp/nmt-watch.log >&2 || true
    return 1
  fi
}

# helper.sh::nmt_start — `nmt start` ups swirlds-node + swirlds-haveged via
# docker-compose.jrs.yml inside the pod.
start_consensus_node_via_nmt() {
  local pod="$1"
  # NMT's `nmt start` does `docker compose up --no-recreate`, which reuses
  # the existing container if any — meaning .env edits from `nmt install`
  # don't reach the container env. Use `docker compose up --force-recreate`
  # directly so the new JAVA_MAIN_CLASS / JAVA_OPTS / JDK actually land.
  log "docker compose up --force-recreate in ${pod} (bypassing nmt-start's --no-recreate)"
  # `runuser -u hedera` (not `sudo -u hedera`) — see install_platform_via_nmt
  # for the PAM rationale.
  kexec "${pod}" runuser -u hedera -- bash -c "
    cd ${NMT_DIR}/compose/network-node
    docker compose --project-directory . -f docker-compose.yml -f docker-compose.jrs.yml \
      up -d --force-recreate 2>&1 | tail -10
  "

  local attempts=0
  while (( attempts < 60 )); do
    local state
    state="$(kexec "${pod}" docker ps -a -f 'name=swirlds-node' --format '{{.State}}' 2>/dev/null || true)"
    [[ "${state}" == "running" ]] && { log "  swirlds-node running in ${pod}"; return 0; }
    attempts=$((attempts + 1))
    sleep 5
  done
  echo "swirlds-node failed to come up in ${pod}" >&2
  kexec "${pod}" docker ps -a >&2 || true
  return 1
}

# Poll HAPI_PATH/logs/hgcaa.log for "ACTIVE" — helper.sh::verify_network_state.
wait_for_node_active() {
  local pod="$1" timeout_secs="$2"
  local deadline=$((SECONDS + timeout_secs))
  while (( SECONDS < deadline )); do
    if kexec "${pod}" grep -q "ACTIVE" "${HAPI_PATH}/logs/hgcaa.log" 2>/dev/null; then
      log "  ${pod}: ACTIVE"
      return 0
    fi
    sleep 5
  done
  echo "${pod} never reached ACTIVE within ${timeout_secs}s" >&2
  kexec "${pod}" tail -100 "${HAPI_PATH}/logs/hgcaa.log" >&2 || true
  return 1
}

# Fail fast if the pod can't actually run x86-64 binaries. Catches the
# case where DOCKER_DEFAULT_PLATFORM wasn't propagated, Rosetta-for-Linux
# is off in Docker Desktop, or the host is Linux on aarch64 without
# binfmt_misc — before we waste time downloading 200+ MB into a doomed pod.
verify_pod_arch_supports_nmt() {
  local pod_arch
  pod_arch="$(kexec "$(iterate_pods | head -n 1)" uname -m 2>/dev/null | tr -d '\r')"
  if [[ "${pod_arch}" != "x86_64" ]]; then
    cat >&2 <<EOF
Pod arch is '${pod_arch}', but NMT is x86-64-only. Aborting before NMT install.

On Apple Silicon: enable "Use Rosetta for x86/amd64 emulation" in Docker
Desktop (Settings -> General), then re-run. The script already exports
DOCKER_DEFAULT_PLATFORM=linux/amd64 in that environment, but the existing
Kind cluster was created without it; tear it down first:

  kind delete cluster -n ${SOLO_CLUSTER_NAME}
EOF
    return 1
  fi
  log "Pod arch ${pod_arch} OK for NMT"
}

bring_up_consensus_via_nmt() {
  verify_pod_arch_supports_nmt
  prep_address_book_and_configs
  local node nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    local pod="network-${node}-0"
    install_nmt_in_pod "${pod}"
    install_platform_via_nmt "${pod}" "${node}"
    seed_node_config "${pod}"
    start_nmt_watcher "${pod}"
    start_consensus_node_via_nmt "${pod}"
  done
  log "Waiting for all consensus nodes to reach ACTIVE"
  local pod
  for pod in $(iterate_pods); do
    wait_for_node_active "${pod}" "${NETWORK_ACTIVE_TIMEOUT_SECS}"
  done
}

build_upgrade_zip() {
  local zip_path="${WORK_DIR}/softwareUpgrade.zip"
  log "Packaging local build at ${LOCAL_BUILD_PATH} into ${zip_path}"
  (cd "${LOCAL_BUILD_PATH}" && zip -qr "${zip_path}" .)
  UPGRADE_ZIP_PATH="${zip_path}"
  UPGRADE_ZIP_SHA384="$(shasum -a 384 "${zip_path}" | awk '{print $1}')"
  log "  sha384: ${UPGRADE_ZIP_SHA384}"
}

ensure_yahcli() {
  if [[ -n "${YAHCLI_JAR}" && -f "${YAHCLI_JAR}" ]]; then
    log "Using pre-built yahcli at ${YAHCLI_JAR}"
    return 0
  fi
  log "Building yahcli from source"
  (cd "${REPO_ROOT}" && ./gradlew :yahcli:assemble -q)
  YAHCLI_JAR="$(ls -t "${REPO_ROOT}"/hedera-node/yahcli/build/libs/yahcli-*.jar 2>/dev/null | head -n 1)"
  [[ -n "${YAHCLI_JAR}" && -f "${YAHCLI_JAR}" ]] || {
    echo "yahcli build did not produce a JAR" >&2; return 1
  }
}

# Build yahcli's localhost profile: encrypted PKCS#8 PEM for the operator
# account (default 0.0.58, the system-files admin that PREPARE_UPGRADE and
# FREEZE_UPGRADE require), the corresponding .pass file, and a config.yml
# that points yahcli at our port-forwarded haproxy. Idempotent — skips if
# the files already exist (e.g. between yahcli_run calls in the same step).
#
# OPERATOR_PRIVATE_KEY is the well-known genesis Ed25519 dev key Solo seeds
# into every special-purpose account (0.0.2 .. 0.0.100) at deploy time, so
# the same hex works for account 0.0.58. The hex is already an unencrypted
# PKCS#8 DER (302e... header is Ed25519 OID 1.3.101.112 + 32-byte seed),
# so we just base64-wrap it, then re-encrypt with openssl pkcs8 -topk8.
generate_yahcli_creds() {
  local yahcli_dir="${WORK_DIR}/yahcli-config"
  local key_dir="${yahcli_dir}/localhost/keys"
  local pem="${key_dir}/account${YAHCLI_OPERATOR_ACCOUNT_NUM}.pem"
  local pass="${key_dir}/account${YAHCLI_OPERATOR_ACCOUNT_NUM}.pass"

  mkdir -p "${key_dir}" "${yahcli_dir}/localhost/sysfiles"

  if [[ -f "${pem}" && -f "${pass}" && -f "${yahcli_dir}/config.yml" ]]; then
    return 0
  fi

  # Find an openssl binary that actually supports Ed25519. macOS's
  # /usr/bin/openssl is LibreSSL 3.x — it can read Ed25519 but pkcs8 -topk8
  # bails with `unsupported private key algorithm: TYPE=Ed25519`. Real
  # OpenSSL >= 1.1.1 works. Homebrew on macOS installs it as openssl@3.
  local openssl_bin=""
  for cand in \
      "${OPENSSL_BIN:-}" \
      /opt/homebrew/opt/openssl@3/bin/openssl \
      /usr/local/opt/openssl@3/bin/openssl \
      openssl ; do
    [[ -z "${cand}" ]] && continue
    if command -v "${cand}" >/dev/null 2>&1 \
       && "${cand}" version 2>/dev/null | grep -q '^OpenSSL'; then
      openssl_bin="${cand}"; break
    fi
  done
  if [[ -z "${openssl_bin}" ]]; then
    echo "generate_yahcli_creds: no real OpenSSL on PATH (LibreSSL can't" >&2
    echo "  encrypt Ed25519 PKCS#8). Install via 'brew install openssl@3'" >&2
    echo "  on macOS, or set OPENSSL_BIN to a real openssl >=1.1.1." >&2
    return 1
  fi

  log "Generating yahcli creds for account 0.0.${YAHCLI_OPERATOR_ACCOUNT_NUM} under ${yahcli_dir} (openssl=${openssl_bin})"

  # 1. Hex -> raw bytes -> base64 -> PEM (unencrypted PKCS#8).
  local raw_pem="${WORK_DIR}/account${YAHCLI_OPERATOR_ACCOUNT_NUM}.raw.pem"
  {
    echo "-----BEGIN PRIVATE KEY-----"
    printf '%s' "${OPERATOR_PRIVATE_KEY}" | xxd -r -p | base64
    echo "-----END PRIVATE KEY-----"
  } > "${raw_pem}"

  # 2. .pass file (single line, no trailing newline — yahcli reads it raw).
  printf '%s' "${YAHCLI_KEY_PASSPHRASE}" > "${pass}"

  # 3. Re-encrypt with AES-256 so yahcli's standard "encrypted PEM + .pass"
  # path picks it up. openssl reads the passphrase from the file we just
  # wrote so the secret never lands on the process command line.
  "${openssl_bin}" pkcs8 -topk8 -in "${raw_pem}" -v2 aes256 \
    -passout "file:${pass}" -out "${pem}"
  rm -f "${raw_pem}"

  # 4. config.yml — minimal localhost profile. yahcli connects to the
  # port-forwarded haproxy at 127.0.0.1:${CN_GRPC_LOCAL_PORT} (set up by
  # start_yahcli_port_forward). node id 0 / account 0.0.3 is node1, which
  # is the haproxy target we forward to.
  cat > "${yahcli_dir}/config.yml" <<EOF
defaultNetwork: localhost
networks:
  localhost:
    nodes:
      - { ipv4Addr: 127.0.0.1, account: 0.0.3, nodeId: 0 }
    defaultPayer: ${YAHCLI_OPERATOR_ACCOUNT_NUM}
EOF
}

# Run yahcli against the local port-forward, using a generated config dir
# under WORK_DIR.
yahcli_run() {
  generate_yahcli_creds
  local yahcli_dir="${WORK_DIR}/yahcli-config"
  [[ -n "${UPGRADE_ZIP_PATH}" && ! -f "${yahcli_dir}/localhost/sysfiles/softwareUpgrade.zip" ]] && \
    cp "${UPGRADE_ZIP_PATH}" "${yahcli_dir}/localhost/sysfiles/softwareUpgrade.zip"
  (cd "${yahcli_dir}" && java -jar "${YAHCLI_JAR}" -n localhost -p "${YAHCLI_OPERATOR_ACCOUNT_NUM}" "$@")
}

start_yahcli_port_forward() {
  pkill -f "kubectl.*port-forward.*haproxy-node1-svc.*${CN_GRPC_LOCAL_PORT}" >/dev/null 2>&1 || true
  sleep 1
  log "Port-forwarding haproxy-node1-svc -> 127.0.0.1:${CN_GRPC_LOCAL_PORT} (yahcli)"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward \
    svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" \
    > "${WORK_DIR}/pf-yahcli.log" 2>&1 < /dev/null &
  YAHCLI_PF_PID=$!
  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    (: </dev/tcp/127.0.0.1/${CN_GRPC_LOCAL_PORT}) >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "yahcli port-forward never came up" >&2
  return 1
}

# Wait for a marker file (execute_immediate.mf, now_frozen.mf, ...) to appear
# in the first pod's data/upgrade/current/ directory.
wait_for_marker() {
  local marker="$1" timeout_secs="$2"
  local pod1 marker_path
  pod1="$(iterate_pods | head -n 1)"
  marker_path="${UPGRADE_CURRENT_DIR}/${marker}"
  log "Waiting for ${marker} in ${pod1}:${marker_path}"
  local deadline=$((SECONDS + timeout_secs))
  while (( SECONDS < deadline )); do
    if kexec "${pod1}" test -f "${marker_path}"; then
      log "  ${marker} appeared in ${pod1}"
      return 0
    fi
    sleep 5
  done
  echo "${marker} never appeared in ${pod1} within ${timeout_secs}s" >&2
  kexec "${pod1}" ls -la "${UPGRADE_CURRENT_DIR}" >&2 || true
  return 1
}

# === Step 4 + 4a ===============================================================
# Submit a CryptoCreate via the Hedera SDK against the upgraded consensus
# network, then poll the Mirror Node REST API until it reports the transaction
# with `result == "SUCCESS"`. Finally, scan the mirror importer's log for any
# ERROR-level lines as the 4a "no errors in the log" check.
step4_crypto_create_smoke() {
  log "Step 4: CryptoCreate smoke test (SDK -> consensus, then poll mirror REST)"
  require_cmd node
  require_cmd npm

  # 1) Port-forward CN gRPC + Mirror REST. Kill any stale forwards first.
  pkill -f "kubectl.*port-forward.*haproxy-node1-svc.*${CN_GRPC_LOCAL_PORT}" >/dev/null 2>&1 || true
  pkill -f "kubectl.*port-forward.*mirror-1-rest.*${MIRROR_REST_LOCAL_PORT}" >/dev/null 2>&1 || true
  sleep 1

  log "Port-forwarding haproxy-node1-svc -> 127.0.0.1:${CN_GRPC_LOCAL_PORT}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward \
    svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" \
    > "${WORK_DIR}/pf-grpc.log" 2>&1 < /dev/null &
  local _pf_grpc=$!

  log "Port-forwarding mirror-1-rest -> 127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward \
    svc/mirror-1-rest "${MIRROR_REST_LOCAL_PORT}:http" \
    > "${WORK_DIR}/pf-rest.log" 2>&1 < /dev/null &
  local _pf_rest=$!

  local p deadline
  for p in "${CN_GRPC_LOCAL_PORT}" "${MIRROR_REST_LOCAL_PORT}"; do
    deadline=$((SECONDS + 30))
    while (( SECONDS < deadline )); do
      (: </dev/tcp/127.0.0.1/$p) >/dev/null 2>&1 && break
      sleep 1
    done
    if ! (: </dev/tcp/127.0.0.1/$p) >/dev/null 2>&1; then
      kill "${_pf_grpc}" "${_pf_rest}" >/dev/null 2>&1 || true
      echo "Port ${p} did not become reachable" >&2
      return 1
    fi
  done

  # 2) Install @hashgraph/sdk into WORK_DIR (one-time per script run). The
  #    JS lives at ${SCRIPT_DIR}/crypto-create-smoke.mjs; copy it into WORK_DIR
  #    so Node resolves `@hashgraph/sdk` from WORK_DIR/node_modules (ESM
  #    resolution walks up from the script's directory).
  if [[ ! -d "${WORK_DIR}/node_modules/@hashgraph/sdk" ]]; then
    log "Installing @hashgraph/sdk into ${WORK_DIR} (one-time, ~30s)"
    (
      cd "${WORK_DIR}"
      cat > package.json <<'PKG'
{ "name": "smoke", "version": "0.0.0", "private": true, "type": "module" }
PKG
      npm install --no-fund --no-audit @hashgraph/sdk >/dev/null 2>&1
    )
  fi
  cp "${SCRIPT_DIR}/crypto-create-smoke.mjs" "${WORK_DIR}/crypto-create-smoke.mjs"

  log "Running CryptoCreate smoke"
  local rc=0
  (
    cd "${WORK_DIR}"
    GRPC_ENDPOINT="127.0.0.1:${CN_GRPC_LOCAL_PORT}" \
    MIRROR_REST_URL="http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}" \
    OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID}" \
    OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY}" \
    POLL_TIMEOUT_MS="${SMOKE_POLL_TIMEOUT_MS}" \
      node "${WORK_DIR}/crypto-create-smoke.mjs"
  ) || rc=$?

  kill "${_pf_grpc}" "${_pf_rest}" >/dev/null 2>&1 || true

  if (( rc != 0 )); then
    echo "Step 4 (CryptoCreate smoke) FAILED with rc=${rc}" >&2
    return "${rc}"
  fi

  # Step 4a: scan mirror importer log for ERROR-level lines.
  log "Step 4a: scanning mirror importer log for ERROR-level entries"
  local importer_pod errors
  importer_pod="$(kubectl -n "${SOLO_NAMESPACE}" get pods \
    -l 'app.kubernetes.io/component=importer,app.kubernetes.io/instance=mirror-1' \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
  if [[ -z "${importer_pod}" ]]; then
    echo "Step 4a: importer pod not found" >&2
    return 1
  fi
  # Spring-Boot log format: `<timestamp> ERROR <pid> --- ...` or `... ERROR <module> ...`.
  errors="$(kubectl -n "${SOLO_NAMESPACE}" logs "${importer_pod}" --tail=-1 2>&1 \
    | grep -cE '(^|[^A-Z])ERROR ([0-9]+ --- |[a-z])' || true)"
  if (( errors > 0 )); then
    echo "Step 4a FAIL: ${errors} ERROR line(s) in ${importer_pod} log:" >&2
    kubectl -n "${SOLO_NAMESPACE}" logs "${importer_pod}" --tail=-1 2>&1 \
      | grep -E '(^|[^A-Z])ERROR ([0-9]+ --- |[a-z])' | head -10 >&2
    return 1
  fi
  log "  ${importer_pod}: 0 ERROR lines in log"
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
step4_crypto_create_smoke    # Step 4 + 4a (CryptoCreate via SDK + MN log scan)

log "PASS: baseline ${DEPLOY_RELEASE_TAG} -> local ${UPGRADE_VERSION} upgrade + CryptoCreate smoke completed"
