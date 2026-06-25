#!/usr/bin/env bash
# Block Stream Cutover
#- [x] Deploy v0.73.0 with application.properties
#- [x] Deploy MN and explorer
#- [x] Upgrade to v0.74.0 with application.properties
#- [x] Produce jumpstart.bin via block-node wrapping tool (offline)
#- [x] Build temp upgrade properties using parsed jumpstart values
#- [x] Upgrade to v0.75.0 with application.properties and jumpstart values
#- [x] Deploy BN with first managed block jumpstart block + 1000
#- [x] Upgrade to v0.76.0 -> TSS + WRAPS enabled, dual-write (BOTH / FILE_AND_GRPC), mock signatures.
#      Each CN self-downloads + extracts the WRAPS proving key from the tss.wrapsProvingKeyDownloadUrl
#      in resources/0.76|0.77 application.properties (public builds.hedera.com URL); no host-side
#      pre-download or local nginx server. Per-node download times are reported from hgcaa.log.
#- [x] Upgrade to v0.77.0 -> Block Stream Cutover w/TSS (BLOCKS only, GRPC writer, real signatures, state proofs on)

set -eo pipefail
set +m

NODE_COUNT_PARAM=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--nodes)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for $1 (expected 3 or 4)" >&2
        exit 1
      fi
      NODE_COUNT_PARAM="$2"
      shift 2
      ;;
    --nodes=*)
      NODE_COUNT_PARAM="${1#*=}"
      shift
      ;;
    -h|--help)
      cat <<'EOF'
Usage: solo-e2e-block-stream-cutover.sh [--nodes 3|4]

Options:
  -n, --nodes 3|4   Number of consensus nodes to deploy.
                    3 => node1,node2,node3
                    4 => node1,node2,node3,node4
                    If omitted, NODE_ALIASES env var (or default node1,node2,node3,node4) is used.
Environment:
  BLOCK_NODE_REPO_PATH      Path to hiero-block-node checkout (default: ../hiero-block-node)
  BLOCK_NODE_CUTOVER_START_BLOCK
                            Block number at which the Block Node joins the chain. Rendered into the BN
                            pod as BOTH env vars in the same helm values file:
                              BLOCK_NODE_EARLIEST_MANAGED_BLOCK (NodeConfig.earliestManagedBlock)
                              BACKFILL_START_BLOCK              (BackfillConfiguration.startBlock)
                            Defaults at Step 7 time to JUMPSTART_BLOCK_NUMBER + 1000. The +1000 margin
                            keeps earliestManagedBlock ABOVE CN's current block-stream block number,
                            so BN's catch-up path (streamBeforeEmbOrElse) snaps nextUnstreamed down
                            to whatever CN first publishes via the CAS in LiveStreamPublisherManager.
                            Without these the BN expects block 0 next and rejects every publish with
                            NODE_BEHIND_PUBLISHER, leaving the BN permanently empty.
  USE_BLOCK_NODE_JUMPSTART  true|false (default: true)
  BLOCKS_WRAP_EXTRA_ARGS    Extra args appended to `blocks wrap ...`
  JUMPSTART_BIN_PATH        Optional explicit jumpstart.bin path (if tool writes elsewhere)
  APP_PROPS_073_FILE         application.properties for the initial 0.73.0 deployment
                            (default: resources/0.73/application.properties next to this script)
  APP_PROPS_074_FILE         application.properties for the 0.74.0-rc.1 tagged upgrade
                            (default: resources/0.74/application.properties next to this script)
  APP_PROPS_075_FILE         application.properties for the local-build 0.75.0 jumpstart upgrade
                            (default: resources/0.75/application.properties next to this script)
  APP_PROPS_076_FILE         application.properties for the local-build 0.76.0 upgrade
                            (default: resources/0.76/application.properties next to this script)
                            Carries the tss.wrapsProvingKeyDownloadUrl each CN self-downloads the
                            WRAPS proving key from; point this at an edited copy to change the URL.
  APP_PROPS_077_FILE         application.properties for the local-build 0.77.0 BLOCKS-only cutover upgrade
                            (default: resources/0.77/application.properties next to this script)
                            Carries the same tss.wrapsProvingKeyDownloadUrl as the 0.76 file.
  UPGRADE_074_RELEASE_TAG    Solo release tag for the intermediate upgrade (default: v0.74.0-rc.1)
  UPGRADE_075_VERSION        Solo upgrade-version label for the local-build jumpstart step.
                            Must point at a published Solo tag (Solo resolves it before applying
                            --local-build-path), but the binary always comes from the local build.
                            Solo accepts reusing the same label across local-build upgrades.
                            (default: v0.75.0-rc.6)
  UPGRADE_076_VERSION        Solo release tag for the 0.76 upgrade. Upgrades to this published
                            release image (no --local-build-path), so the 0.76 step exercises the
                            tag's own default config. (default: v0.76.0-rc.1)
  UPGRADE_077_VERSION        Solo upgrade-version label for the local-build 0.77 BLOCKS-only cutover step.
                            Must be >= the 0.76 release the network is on, or Solo rejects it as a
                            downgrade. (default: UPGRADE_076_VERSION, i.e. v0.76.0-rc.1)
  SOLO_075_UPGRADE_TIMEOUT_SECS  Timeout for the 0.75 local-build upgrade (default: 900)
  SOLO_076_UPGRADE_TIMEOUT_SECS  Timeout for the 0.76 release upgrade (default: 900)
  SOLO_077_UPGRADE_TIMEOUT_SECS  Timeout for the 0.77 local-build upgrade (default: 900)
  KEEP_PORT_FORWARD_WATCHDOG true|false; keep CN/mirror/grafana forwards healthy post-run (default: true)
  EXPLORER_INGRESS_LOCAL_PORT Local port for explorer UI tunnel (default: 38080)
                            Matches Solo's own persist-port-forward for the explorer pod (38080 -> 8080),
                            so our forward short-circuits to Solo's auto-managed tunnel when present.
  EXPLORER_INGRESS_SERVICE_NAME Explorer service name (default: hiero-explorer-1-solo)
  START_STEP                 Step number to resume from (1..12; default: 1).
                            Skips earlier steps; caller is responsible for cluster state matching
                            the end of step (START_STEP - 1). When >1, a resume prelude rebuilds
                            the SDK runtime and re-establishes the CN/mirror port-forwards.
EOF
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Use --help for usage." >&2
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

export SOLO_CLUSTER_NAME="${SOLO_CLUSTER_NAME:-cutover-e2e-testing}"
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
    *)
      echo "Invalid --nodes value: ${NODE_COUNT_PARAM} (expected 3 or 4)" >&2
      exit 1
      ;;
  esac
else
  NODE_ALIASES="${NODE_ALIASES:-node1,node2,node3,node4}"
fi
CONSENSUS_NODE_COUNT="$(awk -F',' '{print NF}' <<< "${NODE_ALIASES}")"
LOCAL_BUILD_PATH="${LOCAL_BUILD_PATH:-${REPO_ROOT}/hedera-node/data}"
LOG4J2_XML_PATH="${REPO_ROOT}/hedera-node/configuration/dev/log4j2.xml"
APP_PROPS_073_FILE="${APP_PROPS_073_FILE:-${SCRIPT_DIR}/resources/0.73/application.properties}"
APP_PROPS_074_FILE="${APP_PROPS_074_FILE:-${SCRIPT_DIR}/resources/0.74/application.properties}"
APP_PROPS_075_FILE="${APP_PROPS_075_FILE:-${SCRIPT_DIR}/resources/0.75/application.properties}"
APP_PROPS_076_FILE="${APP_PROPS_076_FILE:-${SCRIPT_DIR}/resources/0.76/application.properties}"
APP_ENV_076_FILE="${APP_ENV_076_FILE:-${SCRIPT_DIR}/resources/0.76/application.env}"
APP_PROPS_077_FILE="${APP_PROPS_077_FILE:-${SCRIPT_DIR}/resources/0.77/application.properties}"
INITIAL_RELEASE_TAG="${INITIAL_RELEASE_TAG:-v0.73.0}"
UPGRADE_074_RELEASE_TAG="${UPGRADE_074_RELEASE_TAG:-v0.74.0}"
UPGRADE_075_VERSION="${UPGRADE_075_VERSION:-v0.75.0-rc.6}"
UPGRADE_076_VERSION="${UPGRADE_076_VERSION:-v0.76.0-rc.1}"
# The 0.77 upgrade uses the local build, but its --upgrade-version label must be >= the network's
# current version (now ${UPGRADE_076_VERSION} after the 0.76 step) or Solo rejects it as a downgrade.
# Default to the 0.76 tag so it tracks automatically if UPGRADE_076_VERSION is bumped.
UPGRADE_077_VERSION="${UPGRADE_077_VERSION:-${UPGRADE_076_VERSION}}"
SOLO_075_UPGRADE_TIMEOUT_SECS="${SOLO_075_UPGRADE_TIMEOUT_SECS:-900}"
SOLO_076_UPGRADE_TIMEOUT_SECS="${SOLO_076_UPGRADE_TIMEOUT_SECS:-900}"
SOLO_077_UPGRADE_TIMEOUT_SECS="${SOLO_077_UPGRADE_TIMEOUT_SECS:-900}"
MIRROR_RESTJAVA_MEMORY_REQUEST="${MIRROR_RESTJAVA_MEMORY_REQUEST:-512Mi}"
MIRROR_RESTJAVA_MEMORY_LIMIT="${MIRROR_RESTJAVA_MEMORY_LIMIT:-1000Mi}"
# The mirror importer (GraalVM native image) defaults to only 220Mi, which OOMKills while ingesting
# the block stream from the BN (crash-loops right after "Start streaming block N"). Bump its
# container memory like restjava; being a native image it sizes its heap from the cgroup limit, so
# no -Xmx is needed.
MIRROR_IMPORTER_MEMORY_REQUEST="${MIRROR_IMPORTER_MEMORY_REQUEST:-768Mi}"
MIRROR_IMPORTER_MEMORY_LIMIT="${MIRROR_IMPORTER_MEMORY_LIMIT:-1536Mi}"

# WRAPS proving-key config (Step 10).
# The CN downloads + extracts the WRAPS proving-key archive itself from the
# tss.wrapsProvingKeyDownloadUrl set in resources/0.76|0.77 application.properties
# (tss.wrapsProvingKeyDownloadEnabled=true). The script does not pre-download or serve it;
# to use a different URL, point APP_PROPS_076_FILE/APP_PROPS_077_FILE at edited copies.
WRAPS_REQUIRED_FILE_COUNT="${WRAPS_REQUIRED_FILE_COUNT:-4}"
HAPI_PATH="${HAPI_PATH:-/opt/hgcapp/services-hedera/HapiApp2.0}"
WRAPS_ARTIFACTS_CONTAINER_DIR_DEFAULT="${HAPI_PATH}/keys/wraps"
# Cap the WRAPS (Nova/rayon) prover's thread pool to limit its off-heap memory during the genesis
# ceremony. Injected as TSS_LIB_NUM_OF_CORES in lockstep with the WRAPS artifacts path (before the
# upgrade) so all nodes init WRAPS identically. Without it the prover grabs every host CPU, and with
# multiple nodes proving concurrently the off-heap peak dominates RAM. Capping trades genesis-proof
# speed for much lower peak memory. Default 3 keeps node_count x cores within the ~15 visible CPUs at
# 4 nodes (4 x 3 = 12), avoiding oversubscription. Set empty (or 0) to use all cores.
WRAPS_NUM_CORES="${WRAPS_NUM_CORES:-2}"

# SHA-384 hashes are 48 bytes => 96 hex chars.
SHA384_ZERO_HEX="$(printf '0%.0s' {1..96})"
SHA384_ONE_HEX="$(printf '1%.0s' {1..96})"

# Placeholder jumpstart properties used when jumpstart.bin parsing is skipped.
JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH="${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH:-${SHA384_ZERO_HEX}}"
JUMPSTART_CONSENSUS_TIMESTAMP_HASH="${JUMPSTART_CONSENSUS_TIMESTAMP_HASH:-${SHA384_ZERO_HEX}}"
JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH="${JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH:-${SHA384_ZERO_HEX}}"
JUMPSTART_STREAMING_HASHER_LEAF_COUNT="${JUMPSTART_STREAMING_HASHER_LEAF_COUNT:-1}"
JUMPSTART_STREAMING_HASHER_HASH_COUNT="${JUMPSTART_STREAMING_HASHER_HASH_COUNT:-1}"
# Comma-separated dummy subtree hashes (placeholder until real jumpstart tooling).
JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES="${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES:-${SHA384_ONE_HEX}}"
export JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH
export JUMPSTART_CONSENSUS_TIMESTAMP_HASH
export JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH
export JUMPSTART_STREAMING_HASHER_LEAF_COUNT
export JUMPSTART_STREAMING_HASHER_HASH_COUNT
export JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES

CN_GRPC_LOCAL_PORT="${CN_GRPC_LOCAL_PORT:-50211}"
MIRROR_REST_LOCAL_PORT="${MIRROR_REST_LOCAL_PORT:-5551}"
MIRROR_REST_SERVICE="${MIRROR_REST_SERVICE:-mirror-1-rest}"
GRAFANA_LOCAL_PORT="${GRAFANA_LOCAL_PORT:-3000}"
GRAFANA_SERVICE_NAME="${GRAFANA_SERVICE_NAME:-kube-prometheus-stack-grafana}"
EXPLORER_INGRESS_LOCAL_PORT="${EXPLORER_INGRESS_LOCAL_PORT:-38080}"
EXPLORER_INGRESS_SERVICE_NAME="${EXPLORER_INGRESS_SERVICE_NAME:-hiero-explorer-1-solo}"
KEEP_NETWORK="${KEEP_NETWORK:-true}"
# If true, script continues when Grafana forwarding cannot be established.
ALLOW_GRAFANA_PORT_FORWARD_FAILURE="${ALLOW_GRAFANA_PORT_FORWARD_FAILURE:-true}"
KEEP_PORT_FORWARD_WATCHDOG="${KEEP_PORT_FORWARD_WATCHDOG:-true}"
# Observability stack (kube-prometheus-stack: Prometheus + Grafana + node-exporter +
# kube-state-metrics + the Solo ServiceMonitor + a long-lived Grafana port-forward).
# Default OFF: on a single-node kind VM this is a large, constant CPU/memory/scrape load
# that is pure observability — it contributes to Docker Desktop instability under the
# upgrade bursts and is not needed to validate the cutover. Set ENABLE_MONITORING=true
# to bring it back.
ENABLE_MONITORING="${ENABLE_MONITORING:-false}"

# Root for generated artifacts (record streams, wrap outputs, comparison logs).
# This directory is .gitignored so all run-time output stays in one place.
GENERATED_DIR="${GENERATED_DIR:-${SCRIPT_DIR}/generated}"

# Downloaded record stream objects from Solo MinIO (Step 5).
RECORD_STREAMS_DIR="${RECORD_STREAMS_DIR:-${GENERATED_DIR}/recordStreams}"
# Block Node wrap tool output for the initial wrap (Step 5).
WRAPPED_BLOCKS_DIR="${WRAPPED_BLOCKS_DIR:-${GENERATED_DIR}/wrappedBlocks}"
# Block Node wrap tool output for the post-0.75 replay used by jumpstart validation (Step 7).
REPLAY_WRAPPED_BLOCKS_DIR="${REPLAY_WRAPPED_BLOCKS_DIR:-${GENERATED_DIR}/replayWrappedBlocks}"
# Migration vote vs replay comparison log, written by Step 7.
MIGRATION_COMPARE_LOG="${MIGRATION_COMPARE_LOG:-${GENERATED_DIR}/migration-compare.log}"
MINIO_BUCKET="${MINIO_BUCKET:-solo-streams}"
MINIO_NAMESPACE="${MINIO_NAMESPACE:-${SOLO_NAMESPACE}}"
# Optional overrides if auto-discovery fails (service name in MINIO_NAMESPACE).
MINIO_SERVICE_NAME="${MINIO_SERVICE_NAME:-}"

# Block Node offline wrapping tool configuration (Step 5 jumpstart generation).
USE_BLOCK_NODE_JUMPSTART="${USE_BLOCK_NODE_JUMPSTART:-true}"
BLOCK_NODE_REPO_PATH="${BLOCK_NODE_REPO_PATH:-${REPO_ROOT}/../hiero-block-node}"
BLOCKS_WRAP_EXTRA_ARGS="${BLOCKS_WRAP_EXTRA_ARGS:-}"
JUMPSTART_BIN_PATH="${JUMPSTART_BIN_PATH:-}"

# Solo-deployed Block Node configuration (Step 7 cutover deployment).
BLOCK_NODE_ID="${BLOCK_NODE_ID:-1}"
# Defaults to "<node>=1,..." across NODE_ALIASES if empty.
BLOCK_NODE_PRIORITY_MAPPING="${BLOCK_NODE_PRIORITY_MAPPING:-}"
BLOCK_NODE_CHART_DIR="${BLOCK_NODE_CHART_DIR:-}"
BLOCK_NODE_CHART_VERSION="${BLOCK_NODE_CHART_VERSION:-v0.35.0-rc1}"
# Solo deploys the BN with a tiny 160Mi memory limit and no JVM heap flag (its deploy profile
# overrides the chart's generous 12Gi/15Gi + -Xmx16G defaults), so the BN's container-aware heap is
# only ~40MiB. It then OOMs ("Java heap space") verifying the first post-cutover block, crash-loops,
# and -- because 0.77 is BLOCKS-only (CN streams only to the BN) -- backpressure stalls CN block
# production (mirror/transactions freeze at the last persisted block). We bump it via a direct
# statefulset patch after deploy (helm/solo value precedence for BN resources is unreliable).
BLOCK_NODE_MEMORY_LIMIT="${BLOCK_NODE_MEMORY_LIMIT:-4Gi}"
BLOCK_NODE_MEMORY_REQUEST="${BLOCK_NODE_MEMORY_REQUEST:-2Gi}"
BLOCK_NODE_HEAP_OPTS="${BLOCK_NODE_HEAP_OPTS:--Xms512m -Xmx3g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/dump.hprof}"
BLOCK_NODE_RELEASE_TAG="${BLOCK_NODE_RELEASE_TAG:-}"
BLOCK_NODE_IMAGE_TAG="${BLOCK_NODE_IMAGE_TAG:-}"
BLOCK_NODE_VALUES_FILE="${BLOCK_NODE_VALUES_FILE:-}"
BLOCK_NODE_READY_TIMEOUT_SECS="${BLOCK_NODE_READY_TIMEOUT_SECS:-600}"
# BLOCK_NODE_CUTOVER_START_BLOCK is rendered into the BN pod as both
# BLOCK_NODE_EARLIEST_MANAGED_BLOCK (NodeConfig.earliestManagedBlock) and
# BACKFILL_START_BLOCK (BackfillConfiguration.startBlock). Together they tell
# the BN it's joining mid-chain at this block: stop expecting genesis, accept
# the publisher's hash as the new chain root, and only backfill from here
# upward. Without these (defaults 0) the BN rejects every publish with
# NODE_BEHIND_PUBLISHER and stays empty.
# Computed at Step 7 time as JUMPSTART_BLOCK_NUMBER + 1000. The +1000 margin
# keeps BN's earliestManagedBlock ABOVE CN's current block-stream block at
# deploy time. When publisher offers a block below earliestManagedBlock,
# BN's catch-up CAS in streamBeforeEmbOrElse snaps nextUnstreamedBlockNumber
# down to that block and accepts it. With a too-low margin the publisher's
# block is ABOVE earliestManagedBlock and BN replies SEND_BEHIND forever.
# User can override.
BLOCK_NODE_CUTOVER_START_BLOCK="${BLOCK_NODE_CUTOVER_START_BLOCK:-}"

# RSA roster bootstrap (BN >= 0.34): without these, BN's RsaRosterBootstrapPlugin has no
# bootstrap file and no Mirror Node fallback to query, fails fast at startup, and the BN's
# verifier never receives the CN address book — every WRB block then fails verification with
# `IllegalStateException: Expected exactly 1 element matching predicate` in
# ExtendedMerkleTreeSession.finalizeVerification (the missing leaf is the per-node RSA pubkey
# subtree that the verifier expects to find for each signer in the address book).
# Mapped to env vars via AutomaticEnvironmentVariableConfigSource (configDataName dots->_,
# uppercased; camelCase property name uppercased with `_` before each capital).
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL:-http://${MIRROR_REST_SERVICE}.${SOLO_NAMESPACE}.svc.cluster.local}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS:-5}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS:-10}"
ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE="${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE:-100}"

# Mirror node Block Node cutover overrides (Step 9 mirror reconfiguration).
# Only used on MN >= 0.155 (written as HIERO_MIRROR_IMPORTER_BLOCK_CUTOVER_HAPIVERSION). On 0.154
# there is no hapiVersion key — leave empty and the importer auto-detects the record→block cutover.
MIRROR_BLOCK_CUTOVER_HAPIVERSION="${MIRROR_BLOCK_CUTOVER_HAPIVERSION:-}"
# Mirror node chart version pinned for BOTH `solo mirror node add` (Step 3) and `solo mirror node
# upgrade` (Step 9). Pinning both to one explicit version keeps the upgrade target == the deployed
# version, satisfying Solo's no-downgrade guard (assertUpgradeVersionNotOlder). Block-cutover env
# wiring needs MN >= 0.153.1 to honor the HIERO_MIRROR_IMPORTER_BLOCK_CUTOVER_* keys; 0.156.0 does.
# COUPLING: this must stay >= the mirror chart default of the pinned Solo (workflow `solo-version`,
# currently 0.79.0 -> default 0.156.0). If you bump solo-version, bump this in lockstep or the add
# itself can deploy a newer default than this pin and the Step 9 upgrade then reads as a downgrade.
MIRROR_NODE_VERSION="${MIRROR_NODE_VERSION:-v0.156.0}"

# Step at which to start; lower-numbered steps are skipped. Default 1 = full run.
START_STEP="${START_STEP:-1}"
if ! [[ "${START_STEP}" =~ ^[1-9]$|^1[012]$ ]]; then
  echo "START_STEP must be an integer 1..12, got '${START_STEP}'" >&2
  exit 1
fi
should_run_step() { (( START_STEP <= $1 )); }

OPERATOR_ACCOUNT_ID="${OPERATOR_ACCOUNT_ID:-0.0.2}"
OPERATOR_PRIVATE_KEY="${OPERATOR_PRIVATE_KEY:-302e020100300506032b65700422042091132178e72057a1d7528025956fe39b0b847f200ab59b2fdd367017f3087137}"

WORK_DIR="$(mktemp -d)"
NODE_SCRIPT="${WORK_DIR}/sdk-crypto-create-check.js"
NETWORK_PROBE_SCRIPT="${WORK_DIR}/sdk-network-probe.js"
JUMPSTART_PARSE_SCRIPT="${WORK_DIR}/parse-jumpstart-bin.js"
TMP_075_UPGRADE_APP_PROPS="${WORK_DIR}/application-075-jumpstart.properties"
MIRROR_NODE_VALUES_FILE="${WORK_DIR}/mirror-node-cutover-values.yaml"
MIRROR_NODE_CUTOVER_VALUES_FILE="${WORK_DIR}/mirror-node-block-cutover-values.yaml"
BLOCK_NODE_CUTOVER_VALUES_FILE="${WORK_DIR}/block-node-cutover-values.yaml"
RSA_BOOTSTRAP_ROSTER_FILE="${WORK_DIR}/rsa-bootstrap-roster.json"
BLOCK_TIMES_FILE="${WORK_DIR}/block_times.bin"
DAY_BLOCKS_FILE="${WORK_DIR}/day_blocks.json"
MIRROR_METADATA_SCRIPT="${WORK_DIR}/generate-mirror-metadata.js"
WRAP_DAYS_SRC_DIR="${WORK_DIR}/recordDays"
WRAP_COMPRESSED_DAYS_DIR="${WORK_DIR}/compressedDays"
ZSTD_WRAPPER_DIR="${WORK_DIR}/zstd-wrapper"
ZSTD_WRAPPER_SRC="${ZSTD_WRAPPER_DIR}/ZstdCat.java"
ZSTD_WRAPPER_BIN="${ZSTD_WRAPPER_DIR}/zstd"

# Extractor that pulls the LedgerIdPublicationTransactionBody out of a block-stream
# file, used to seed the Block Node's TSS parameters before the 0.77 cutover.
LEDGER_ID_EXTRACTOR_DIR="${WORK_DIR}/ledgerid-extractor"
LEDGER_ID_EXTRACTOR_SRC="${LEDGER_ID_EXTRACTOR_DIR}/extract_ledger_id_publication.py"
BN_TSS_PARAMS_LOCAL="${WORK_DIR}/tss-parameters.bin"
BN_BLOCK_FILES_DIR="${WORK_DIR}/bn-block-files"
BN_TSS_PARAMS_CONTAINER_PATH="${BN_TSS_PARAMS_CONTAINER_PATH:-/opt/hiero/block-node/verification/tss-parameters.bin}"
PORT_FORWARD_WATCHDOG_SCRIPT="${WORK_DIR}/post-run-port-forward-watchdog.sh"
PORT_FORWARD_WATCHDOG_LOG="${WORK_DIR}/post-run-port-forward-watchdog.log"

CN_PORT_FORWARD_PID=""
MIRROR_PORT_FORWARD_PID=""
GRAFANA_PORT_FORWARD_PID=""
EXPLORER_INGRESS_PORT_FORWARD_PID=""
PORT_FORWARD_WATCHDOG_PID=""
ACTIVE_GRAFANA_SERVICE_NAME="${GRAFANA_SERVICE_NAME}"
ACTIVE_INGRESS_NAMESPACE="${SOLO_NAMESPACE}"
ACTIVE_INGRESS_SERVICE_NAME="${EXPLORER_INGRESS_SERVICE_NAME}"
ACTIVE_INGRESS_REMOTE_PORT="80"

log() { :; }

# STEP_START_TS is set by print_banner and consumed by print_step_complete to
# emit a wall-clock summary for the whole step block. Unset between steps so a
# skipped step (via START_STEP) doesn't leak a stale start time into the next.
STEP_START_TS=""

print_banner() {
  local msg="$1"
  STEP_START_TS=$SECONDS
  echo
  echo "======================================================================"
  echo "== ${msg}"
  echo "======================================================================"
}

print_step_complete() {
  if [[ -z "${STEP_START_TS}" ]]; then
    return 0
  fi
  local elapsed=$((SECONDS - STEP_START_TS))
  local label="${1:-Step}"
  echo "${label} complete (${elapsed}s)"
  STEP_START_TS=""
}

# Foreground wrapper for long-running subprocesses (solo / kind / gradle).
# Usage: run_step "Human-readable label" cmd arg1 arg2 ...
#
# Behavior:
# - Prints "▶ <label>" and the literal command, then runs the command in the
#   foreground with stdout + stderr streaming straight to the script's
#   stdout/stderr (no log-file capture, no spinner). This is the "I want the
#   full solo logs in real time" mode.
# - Prints "✓ <label> (Ns)" on success, "✗ <label> (rc=N, Ns)" on failure,
#   then returns the command's exit code so `set -e` aborts naturally.
# - Name retained so existing call sites stay unchanged.
run_step() {
  local label="$1"; shift
  echo "▶ ${label}"
  echo "  $ $*"
  local start_ts=$SECONDS
  local rc=0
  "$@" || rc=$?
  local elapsed=$((SECONDS - start_ts))
  if (( rc == 0 )); then
    printf '  ✓ %s (%ds)\n' "${label}" "${elapsed}"
  else
    printf '  ✗ %s (rc=%d, %ds)\n' "${label}" "${rc}" "${elapsed}" >&2
  fi
  return ${rc}
}

cleanup() {
  local exit_code=$?

  # Stop the remote toleration patcher first (no-op on kind / when never started), regardless of
  # exit status or KEEP_NETWORK, so no background loop survives the script.
  stop_remote_toleration_patcher

  # Always restore MinIO regardless of exit status / KEEP_NETWORK. Step 9's
  # disconnect helper sets MINIO_DISCONNECTED_OWNER_* when it scales MinIO to
  # zero; if Step 9 aborts after the scale-down, the next steps (or a re-run)
  # need MinIO back up.
  reconnect_importer_to_minio >/dev/null 2>&1 || true

  if [[ ${exit_code} -ne 0 ]]; then
    return
  fi

  if [[ "${KEEP_NETWORK}" == "true" ]]; then
    return
  fi

  set +e
  if [[ -n "${CN_PORT_FORWARD_PID}" ]]; then
    kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${MIRROR_PORT_FORWARD_PID}" ]]; then
    kill "${MIRROR_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${GRAFANA_PORT_FORWARD_PID}" ]]; then
    kill "${GRAFANA_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${EXPLORER_INGRESS_PORT_FORWARD_PID}" ]]; then
    kill "${EXPLORER_INGRESS_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${PORT_FORWARD_WATCHDOG_PID}" ]]; then
    kill "${PORT_FORWARD_WATCHDOG_PID}" >/dev/null 2>&1 || true
  fi

  if command -v solo >/dev/null 2>&1; then
    solo explorer node destroy --deployment "${SOLO_DEPLOYMENT}" >/dev/null 2>&1 || true
    solo relay node destroy --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" >/dev/null 2>&1 || true
    solo mirror node destroy --deployment "${SOLO_DEPLOYMENT}" --force >/dev/null 2>&1 || true
    solo block node destroy --deployment "${SOLO_DEPLOYMENT}" >/dev/null 2>&1 || true
    solo consensus node stop --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" >/dev/null 2>&1 || true
    solo consensus network destroy --deployment "${SOLO_DEPLOYMENT}" --force >/dev/null 2>&1 || true
  fi
  # Never delete the shared remote cluster; only an ephemeral kind cluster is torn down here.
  if [[ "${CLUSTER_TARGET}" == "kind" ]]; then
    kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  fi

  rm -rf "${WORK_DIR}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

require_cmd() {
  local cmd="$1"
  command -v "${cmd}" >/dev/null 2>&1 || { echo "Required command not found: ${cmd}" >&2; exit 1; }
}

# Writes the LedgerIdPublication extractor: a dependency-free Python script that walks
# the protobuf wire format of a block-stream file and slices out the serialized
# LedgerIdPublicationTransactionBody — exactly the form the Block Node expects at
# verification.tssParametersFilePath. No protobuf classes / JVM / compile needed: a
# length-delimited field's value bytes ARE the serialized sub-message, so we navigate by
# field number  Block.items(1) -> BlockItem.signed_transaction(4) ->
# SignedTransaction.bodyBytes(1) -> TransactionBody.ledger_id_publication(77).
write_ledger_id_extractor() {
  mkdir -p "${LEDGER_ID_EXTRACTOR_DIR}"
  cat > "${LEDGER_ID_EXTRACTOR_SRC}" <<'EOF'
#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import sys
import gzip


def read_varint(buf, pos):
    shift = 0
    result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


def iter_fields(msg):
    pos = 0
    n = len(msg)
    while pos < n:
        key, pos = read_varint(msg, pos)
        fnum = key >> 3
        wtype = key & 0x07
        if wtype == 0:
            val, pos = read_varint(msg, pos)
            yield fnum, wtype, val
        elif wtype == 1:
            yield fnum, wtype, msg[pos : pos + 8]
            pos += 8
        elif wtype == 2:
            length, pos = read_varint(msg, pos)
            yield fnum, wtype, msg[pos : pos + length]
            pos += length
        elif wtype == 5:
            yield fnum, wtype, msg[pos : pos + 4]
            pos += 4
        else:
            return


def find_field(msg, field_num):
    for fnum, wtype, val in iter_fields(msg):
        if fnum == field_num and wtype == 2:
            return val
    return None


def extract_from_block(data):
    # Block.items = field 1 (repeated). Each item -> BlockItem.
    for fnum, wtype, val in iter_fields(data):
        if fnum != 1 or wtype != 2:
            continue
        stx = find_field(val, 4)  # BlockItem.signed_transaction
        if stx is None:
            continue
        body = find_field(stx, 1)  # SignedTransaction.bodyBytes
        if body is None:
            continue
        pub = find_field(body, 77)  # TransactionBody.ledger_id_publication
        if pub is not None:
            return pub
    return None


def main():
    if len(sys.argv) < 3:
        sys.stderr.write("usage: extract.py <out.bin> <blockFile.blk[.gz]> [...]\n")
        sys.exit(2)
    out = sys.argv[1]
    for path in sys.argv[2:]:
        try:
            with open(path, "rb") as f:
                raw = f.read()
            data = gzip.decompress(raw) if path.endswith(".gz") else raw
            pub = extract_from_block(data)
            if pub is not None:
                with open(out, "wb") as o:
                    o.write(pub)
                print("FOUND ledgerIdPublication in %s -> wrote %d bytes to %s" % (path, len(pub), out))
                return
        except Exception as e:  # noqa: BLE001 - skip unreadable/foreign files, keep scanning
            sys.stderr.write("skip %s: %s\n" % (path, e))
    sys.stderr.write("No ledgerIdPublication found in any input block file\n")
    sys.exit(1)


if __name__ == "__main__":
    main()
EOF
}

# Bootstraps the Block Node with the network's TSS ledger ID so it can verify the
# real-TSS-signed blocks produced after the 0.77 cutover.
#
# WHY THIS MIGHT NOT BE NEEDED: the BN learns the ledger ID from a
# LedgerIdPublicationTransactionBody, but its built-in scan only fires for block 0
# (genesis). In this Solo flow the BN is deployed mid-chain and the ledger id is
# published mid-chain (when the WRAPS/history construction completes during the 0.76
# step), so the BN never picks it up and rejects every real-TSS block (verifySignature
# returns false on a null ledgerId). The BN design doc sanctions placing the TSS
# parameters file manually for mid-chain joiners. If the BN is later changed to learn
# the ledger id from any block (not just block 0), or the deploy is provisioned with the
# parameters through the normal operational path, this whole step becomes unnecessary
# and can be deleted.
#
# Source: the LedgerIdPublication tx is a synthetic "Ledger id" admin tx in the block
# stream; we pull .blk.gz from the MinIO solo-streams bucket (the CN block-stream
# uploader writes them there; gzip, no zstd needed), extract the body, drop it into the
# BN volume at verification.tssParametersFilePath, then roll the BN so init() loads it.
seed_block_node_tss_parameters() {
  local minio_pod creds_tmp u p in_pod_dir bn_pod
  minio_pod="$(kubectl -n "${MINIO_NAMESPACE}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name | select(test("^minio-"))' | head -n 1)"
  if [[ -z "${minio_pod}" ]]; then
    echo "seed_block_node_tss_parameters: no MinIO pod found in ${MINIO_NAMESPACE}" >&2
    return 1
  fi

  creds_tmp="$(mktemp)"
  if ! minio_discover_pod_credentials "${MINIO_NAMESPACE}" >"${creds_tmp}"; then
    rm -f "${creds_tmp}"
    echo "seed_block_node_tss_parameters: could not discover MinIO credentials" >&2
    return 1
  fi
  u="$(sed -n '1p' "${creds_tmp}")"
  p="$(sed -n '2p' "${creds_tmp}")"
  rm -f "${creds_tmp}"

  # Copy block-stream objects out of MinIO via in-pod mc, then stream them out file-by-file.
  # Be resilient to MinIO reconnect races and object-prefix casing drift.
  in_pod_dir="/tmp/bn-blk-$$"
  local minio_prefixes=("blockStreams" "blockstreams")
  local minio_prefix=""
  local minio_copy_ok=0
  local minio_attempt=0
  local minio_max_attempts=10
  local minio_sleep_secs=5
  local minio_copy_err=""
  local prefix
  echo "Copying block-stream files from MinIO ${MINIO_BUCKET}/blockStreams via in-pod mc"
  for prefix in "${minio_prefixes[@]}"; do
    for (( minio_attempt=1; minio_attempt<=minio_max_attempts; minio_attempt++ )); do
      minio_copy_err="$(kubectl -n "${MINIO_NAMESPACE}" exec "${minio_pod}" -c minio -- sh -c \
        "rm -rf '${in_pod_dir}'; mkdir -p '${in_pod_dir}'; \
         mc alias set local 'http://minio-hl.${MINIO_NAMESPACE}.svc.cluster.local:9000' '${u}' '${p}' >/dev/null 2>&1; \
         mc cp --recursive 'local/${MINIO_BUCKET}/${prefix}/' '${in_pod_dir}/'" 2>&1)" && {
          minio_copy_ok=1
          minio_prefix="${prefix}"
          break 2
        }
      echo "  MinIO copy attempt ${minio_attempt}/${minio_max_attempts} failed for prefix '${prefix}', retrying in ${minio_sleep_secs}s"
      sleep "${minio_sleep_secs}"
    done
  done
  if (( minio_copy_ok == 0 )); then
    echo "seed_block_node_tss_parameters: in-pod mc cp of blockStreams/blockstreams failed after ${minio_max_attempts} attempts per prefix" >&2
    if [[ -n "${minio_copy_err}" ]]; then
      echo "Last mc error output:" >&2
      echo "${minio_copy_err}" >&2
    fi
    echo "MinIO bucket listing for diagnostics:" >&2
    kubectl -n "${MINIO_NAMESPACE}" exec "${minio_pod}" -c minio -- sh -c \
      "mc alias set local 'http://minio-hl.${MINIO_NAMESPACE}.svc.cluster.local:9000' '${u}' '${p}' >/dev/null 2>&1; \
       mc ls 'local/${MINIO_BUCKET}/' || true; \
       mc ls --recursive 'local/${MINIO_BUCKET}/' | sed -n '1,80p' || true" >&2 || true
    return 1
  fi
  echo "Copied block-stream objects from MinIO prefix '${minio_prefix}'"
  rm -rf "${BN_BLOCK_FILES_DIR}"; mkdir -p "${BN_BLOCK_FILES_DIR}"
  # Copy each block file out with a tar-free `kubectl exec ... cat` (kubectl cp shells out
  # to tar, which the distroless MinIO image does not ship). Discover block files recursively
  # to tolerate MinIO CLI layout differences (some variants keep a blockStreams/ level).
  # Stdout without a TTY is a raw binary stream, so compressed bytes survive intact.
  local rel base dest pulled=0 pull_failures=0
  while IFS= read -r rel; do
    [[ -n "${rel}" ]] || continue
    base="$(basename "${rel}")"
    dest="${BN_BLOCK_FILES_DIR}/${base}"
    if ! kubectl -n "${MINIO_NAMESPACE}" exec "${minio_pod}" -c minio -- cat "${rel}" > "${dest}" 2>/dev/null; then
      ((pull_failures++))
      echo "  WARN: failed to copy MinIO block object '${rel}'"
      rm -f "${dest}"
      continue
    fi
    if [[ -s "${dest}" ]]; then
      ((pulled++))
    else
      ((pull_failures++))
      rm -f "${dest}"
    fi
  done < <(kubectl -n "${MINIO_NAMESPACE}" exec "${minio_pod}" -c minio -- sh -c \
            "find '${in_pod_dir}' -type f \\( -name '*.blk.gz' -o -name '*.blk' \\) | sort" 2>/dev/null)
  kubectl -n "${MINIO_NAMESPACE}" exec "${minio_pod}" -c minio -- sh -c "rm -rf '${in_pod_dir}'" >/dev/null 2>&1 || true

  local blk_files=()
  while IFS= read -r bf; do blk_files+=("${bf}"); done < <(find "${BN_BLOCK_FILES_DIR}" -type f \( -name '*.blk.gz' -o -name '*.blk' \) | sort)
  if [[ "${#blk_files[@]}" -eq 0 ]]; then
    echo "seed_block_node_tss_parameters: no .blk(.gz) files retrieved from MinIO (pulled=${pulled}, pull_failures=${pull_failures})" >&2
    return 1
  fi
  echo "Retrieved ${#blk_files[@]} block-stream files (pull_failures=${pull_failures}); extracting LedgerIdPublication"

  require_cmd python3
  write_ledger_id_extractor || return 1
  rm -f "${BN_TSS_PARAMS_LOCAL}"
  if ! python3 "${LEDGER_ID_EXTRACTOR_SRC}" "${BN_TSS_PARAMS_LOCAL}" "${blk_files[@]}"; then
    echo "seed_block_node_tss_parameters: extractor found no LedgerIdPublication in block stream" >&2
    return 1
  fi
  [[ -s "${BN_TSS_PARAMS_LOCAL}" ]] || { echo "seed_block_node_tss_parameters: extracted tss-parameters.bin is empty" >&2; return 1; }

  bn_pod="block-node-${BLOCK_NODE_ID}-0"
  echo "Seeding ${bn_pod}:${BN_TSS_PARAMS_CONTAINER_PATH} and rolling the Block Node"
  kubectl -n "${SOLO_NAMESPACE}" exec "${bn_pod}" -- sh -lc \
    "mkdir -p '$(dirname "${BN_TSS_PARAMS_CONTAINER_PATH}")'" >/dev/null 2>&1 || true
  # Tar-free push (kubectl cp needs tar in the target container): stream the file into the
  # pod's cat via stdin.
  if ! kubectl -n "${SOLO_NAMESPACE}" exec -i "${bn_pod}" -- sh -lc "cat > '${BN_TSS_PARAMS_CONTAINER_PATH}'" < "${BN_TSS_PARAMS_LOCAL}"; then
    echo "seed_block_node_tss_parameters: streaming tss-parameters into ${bn_pod} failed" >&2
    return 1
  fi

  # Roll the BN so init() reloads and calls initializeTssParameters from the file.
  kubectl -n "${SOLO_NAMESPACE}" delete pod "${bn_pod}" --wait=true >/dev/null 2>&1 || true
  # Wait on the StatefulSet rollout, NOT `wait pod/<name>`: the latter can match the OLD pod (still
  # Ready during graceful termination) and return before the new pod exists, which made the marker
  # poll below race and false-negative. `rollout status` blocks until the freshly-recreated pod is up.
  kubectl -n "${SOLO_NAMESPACE}" rollout status "statefulset/block-node-${BLOCK_NODE_ID}" --timeout=300s >/dev/null 2>&1 || true
  kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/${bn_pod}" --timeout=300s >/dev/null 2>&1 || {
    echo "seed_block_node_tss_parameters: ${bn_pod} did not become ready after roll" >&2
    return 1
  }

  # Authoritative success signal: the seeded file survived the roll into the running pod. (kubectl cp
  # needs tar, which the distroless BN image lacks, so verify presence + non-empty via exec test.)
  if ! kubectl -n "${SOLO_NAMESPACE}" exec "${bn_pod}" -- sh -lc "test -s '${BN_TSS_PARAMS_CONTAINER_PATH}'" >/dev/null 2>&1; then
    echo "seed_block_node_tss_parameters: ${BN_TSS_PARAMS_CONTAINER_PATH} missing or empty in ${bn_pod} after roll" >&2
    return 1
  fi

  # Confirmation: the BN logs "Loaded TSS parameters from file" during init. `kubectl logs` can
  # briefly return a transitioning container right after the roll, so poll generously. An explicit
  # parse/load failure is fatal; but if we merely never observe the marker (a logs-cutover race)
  # while the file is present and the pod is Ready, continue with a warning — the post-cutover BN
  # verification is the real gate.
  sleep 5
  local deadline=$((SECONDS + 180))
  local bn_logs=""
  while (( SECONDS < deadline )); do
    bn_logs="$(kubectl -n "${SOLO_NAMESPACE}" logs "${bn_pod}" 2>/dev/null)"
    if grep -q "Loaded TSS parameters from file" <<<"${bn_logs}"; then
      echo "Block Node loaded TSS parameters from seeded file — ready to verify real-TSS blocks"
      return 0
    fi
    if grep -qiE "failed to (load|parse|read).*tss|invalid tss parameters|tss parameters.*(error|corrupt)" <<<"${bn_logs}"; then
      echo "seed_block_node_tss_parameters: BN reported a TSS parameters load failure:" >&2
      grep -iE "tss" <<<"${bn_logs}" | tail -5 >&2
      return 1
    fi
    sleep 3
  done
  echo "WARN seed_block_node_tss_parameters: did not observe 'Loaded TSS parameters from file' in ${bn_pod} after polling ~180s, but the seeded file is present and the pod is Ready (likely a kubectl-logs cutover race); continuing" >&2
  return 0
}

ensure_zstd_command_for_block_node() {
  local zstd_jar
  if command -v zstd >/dev/null 2>&1; then
    log "Using system zstd: $(command -v zstd)"
    return 0
  fi

  require_cmd java

  zstd_jar="$(find "${HOME}/.gradle/caches/modules-2/files-2.1/com.github.luben/zstd-jni" -name 'zstd-jni-*.jar' 2>/dev/null | head -n 1)"
  if [[ -z "${zstd_jar}" || ! -f "${zstd_jar}" ]]; then
    echo "zstd command not found and zstd-jni jar was not found in ~/.gradle cache." >&2
    echo "Install zstd (for example: brew install zstd) or run one block-node tools task once to download zstd-jni, then retry." >&2
    return 1
  fi

  mkdir -p "${ZSTD_WRAPPER_DIR}"
  cat > "${ZSTD_WRAPPER_SRC}" <<'EOF'
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import com.github.luben.zstd.ZstdInputStream;

public class ZstdCat {
  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: ZstdCat <input.zstd>");
      System.exit(2);
    }
    try (InputStream in = new BufferedInputStream(new FileInputStream(args[0]));
         ZstdInputStream zin = new ZstdInputStream(in);
         OutputStream out = new BufferedOutputStream(System.out)) {
      zin.transferTo(out);
      out.flush();
    }
  }
}
EOF

  cat > "${ZSTD_WRAPPER_BIN}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
input=""
for arg in "$@"; do
  case "$arg" in
    --decompress|-d|--stdout|-c) ;;
    -T*|--threads=*) ;;
    --) ;;
    -*) ;;
    *) input="$arg" ;;
  esac
done
if [[ -z "${input}" ]]; then
  echo "zstd wrapper error: missing input file argument" >&2
  exit 2
fi
if [[ -z "${ZSTD_JNI_JAR:-}" || -z "${ZSTD_WRAPPER_SRC:-}" ]]; then
  echo "zstd wrapper error: ZSTD_JNI_JAR or ZSTD_WRAPPER_SRC is not set" >&2
  exit 2
fi
exec java --class-path "${ZSTD_JNI_JAR}" "${ZSTD_WRAPPER_SRC}" "${input}"
EOF
  chmod +x "${ZSTD_WRAPPER_BIN}"

  export ZSTD_JNI_JAR="${zstd_jar}"
  export ZSTD_WRAPPER_SRC
  export PATH="${ZSTD_WRAPPER_DIR}:${PATH}"
}

validate_block_node_repo() {
  if [[ ! -d "${BLOCK_NODE_REPO_PATH}" ]]; then
    echo "BLOCK_NODE_REPO_PATH not found: ${BLOCK_NODE_REPO_PATH}" >&2
    echo "Set BLOCK_NODE_REPO_PATH to your hiero-block-node checkout" >&2
    return 1
  fi
  if [[ ! -x "${BLOCK_NODE_REPO_PATH}/gradlew" ]]; then
    echo "Block Node gradlew not executable: ${BLOCK_NODE_REPO_PATH}/gradlew" >&2
    return 1
  fi
}

wait_for_http_ok() {
  local url="$1"
  local max_attempts="$2"
  local sleep_secs="$3"
  local label="${4:-Waiting for HTTP endpoint ${url}}"
  # Quick probes (max_attempts <= 3) stay silent — they're used as "is this
  # service ready yet" checks that callers branch on and don't need decoration.
  if (( max_attempts <= 3 )); then
    local attempt=1
    while (( attempt <= max_attempts )); do
      curl -sf "${url}" >/dev/null 2>&1 && return 0
      sleep "${sleep_secs}"
      ((attempt++))
    done
    return 1
  fi
  wait_until "${label}" "${max_attempts}" "${sleep_secs}" \
    curl -sf -o /dev/null "${url}"
}

wait_for_tcp_open() {
  local host="$1"
  local port="$2"
  local max_attempts="$3"
  local sleep_secs="$4"
  local label="${5:-Waiting for TCP endpoint ${host}:${port}}"
  # Quick probes stay silent — see wait_for_http_ok.
  if (( max_attempts <= 3 )); then
    local attempt=1
    while (( attempt <= max_attempts )); do
      if command -v nc >/dev/null 2>&1; then
        nc -z "${host}" "${port}" >/dev/null 2>&1 && return 0
      else
        (: <"/dev/tcp/${host}/${port}") >/dev/null 2>&1 && return 0
      fi
      sleep "${sleep_secs}"
      ((attempt++))
    done
    return 1
  fi
  if command -v nc >/dev/null 2>&1; then
    wait_until "${label}" "${max_attempts}" "${sleep_secs}" \
      nc -z "${host}" "${port}"
  else
    wait_until "${label}" "${max_attempts}" "${sleep_secs}" \
      bash -c "(: <\"/dev/tcp/${host}/${port}\") >/dev/null 2>&1"
  fi
}

# Internal helper shared by wait_for_http_ok / wait_for_tcp_open for long polls.
# Runs the predicate command every sleep_secs (up to max_attempts), printing one
# line per attempt and a final ✓/✗ summary. No spinner so the surrounding logs
# (solo / kubectl) stream uninterrupted.
wait_until() {
  local label="$1"
  local max_attempts="$2"
  local sleep_secs="$3"
  shift 3
  local start_ts=$SECONDS

  echo "▶ ${label}"

  local attempt=0
  while (( attempt < max_attempts )); do
    ((++attempt))
    if "$@" >/dev/null 2>&1; then
      local elapsed=$((SECONDS - start_ts))
      printf '  ✓ %s (%ds, %d attempts)\n' "${label}" "${elapsed}" "${attempt}"
      return 0
    fi
    sleep "${sleep_secs}"
  done

  local elapsed=$((SECONDS - start_ts))
  printf '  ✗ %s — Timed out (%ds, %d attempts)\n' "${label}" "${elapsed}" "${attempt}" >&2
  return 1
}

# Gate post-port-forward-restart SDK use behind a real gRPC readiness check.
# kubectl port-forward binds the local socket the instant it starts, so
# wait_for_tcp_open passes on a path that isn't actually serving traffic
# yet — HAProxy's backend connection to a freshly-restarted CN is cold for
# several seconds. The script's cryptoCreate uses setMaxAttempts(1) +
# setRequestTimeout(15000) and aborts the whole script on the first failed
# attempt, so we need a real end-to-end probe before firing it.
#
# Reuses NETWORK_PROBE_SCRIPT (AccountBalanceQuery, no consensus needed)
# written by prepare_js_sdk_runtime. Callers must have run that helper
# before invoking this — by the time any post-upgrade step fires, step 3
# has already prepared the SDK runtime.
wait_for_sdk_responsive() {
  local timeout_secs="${1:-180}"
  local deadline=$((SECONDS + timeout_secs))
  local attempt=0
  local last_err="${WORK_DIR}/sdk-probe-last.err"
  echo "Polling SDK readiness via AccountBalanceQuery (up to ${timeout_secs}s)"
  while (( SECONDS < deadline )); do
    attempt=$((attempt + 1))
    if node "${NETWORK_PROBE_SCRIPT}" >/dev/null 2>"${last_err}"; then
      echo "  ✓ SDK responsive after ${attempt} attempt(s)"
      return 0
    fi
    sleep 1
  done
  echo "SDK probe did not succeed within ${timeout_secs}s (attempts=${attempt})" >&2
  [[ -s "${last_err}" ]] && { echo "  last probe stderr:" >&2; sed 's/^/    /' "${last_err}" >&2; }
  return 1
}

kill_processes_on_local_port() {
  local port="$1"
  local pids=""
  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -ti "tcp:${port}" 2>/dev/null || true)"
    if [[ -n "${pids}" ]]; then
      kill "${pids}" >/dev/null 2>&1 || true
    fi
  fi
}

cleanup_stale_port_forwards() {
  local include_grafana="${1:-false}"
  pkill -f "port-forward svc/haproxy-node1-svc .*${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >/dev/null 2>&1 || true
  pkill -f "port-forward svc/${MIRROR_REST_SERVICE} .*${MIRROR_REST_LOCAL_PORT}:http" >/dev/null 2>&1 || true
  pkill -f "port-forward svc/${EXPLORER_INGRESS_SERVICE_NAME} .*${EXPLORER_INGRESS_LOCAL_PORT}:80" >/dev/null 2>&1 || true
  if [[ "${include_grafana}" == "true" ]]; then
    pkill -f "port-forward svc/.*grafana .*${GRAFANA_LOCAL_PORT}:80" >/dev/null 2>&1 || true
  fi
}

# Aggressively clear port-forwards + helper processes left over from PRIOR runs. Solo's persisted
# port-forwards (persist-port-forward.js) and the post-run watchdog survive a failed/kept run and
# otherwise pile up across back-to-back runs (memory + FDs) and re-bind our local ports. Safe ONLY
# at the very start (Step 1), before this run's cluster exists -- it does NOT distinguish "this run"
# from "old runs", so never call it mid-run.
preflight_kill_stale_port_forwards() {
  echo "Preflight: clearing stray port-forwards / helper procs from previous runs"
  # Stop watchdogs FIRST so they can't immediately re-spawn the forwards we kill below.
  # WORK_DIR varies per run, but the watchdog script filename is stable across runs.
  pkill -f "post-run-port-forward-watchdog.sh" >/dev/null 2>&1 || true
  # Solo's own persisted explorer / mirror-ingress port-forwards from a prior run.
  pkill -f "persist-port-forward.js" >/dev/null 2>&1 || true
  # Known service-forward patterns (incl. grafana).
  cleanup_stale_port_forwards true
  # Belt-and-suspenders: free every local port this script forwards to, regardless of how the
  # forward was launched (catches stale forwards whose svc/pattern no longer matches).
  local p
  for p in "${CN_GRPC_LOCAL_PORT}" "${MIRROR_REST_LOCAL_PORT}" "${EXPLORER_INGRESS_LOCAL_PORT}" "${GRAFANA_LOCAL_PORT}" "${BLOCK_NODE_GRPC_LOCAL_PORT:-40840}"; do
    kill_processes_on_local_port "${p}"
  done
}

mirror_rest_service_exists() {
  kubectl -n "${SOLO_NAMESPACE}" get svc "${MIRROR_REST_SERVICE}" >/dev/null 2>&1
}

deployment_ready() {
  local deployment="$1"
  local timeout_secs="${2:-5}"
  kubectl -n "${SOLO_NAMESPACE}" rollout status "deployment/${deployment}" --timeout="${timeout_secs}s" >/dev/null 2>&1
}

required_mirror_services_ready() {
  local deployment=""
  local deployments=(mirror-1-rest mirror-1-grpc mirror-1-importer mirror-1-monitor mirror-1-web3)

  for deployment in "${deployments[@]}"; do
    deployment_ready "${deployment}" 5 || return 1
  done
}

wait_for_required_mirror_services_ready() {
  local timeout_secs="${1:-600}"
  local start_ts
  start_ts="$(date +%s)"

  while true; do
    if required_mirror_services_ready; then
      return 0
    fi
    if (( $(date +%s) - start_ts >= timeout_secs )); then
      return 1
    fi
    sleep 5
  done
}

mirror_node_failed_only_on_restjava() {
  kubectl -n "${SOLO_NAMESPACE}" get deployment mirror-1-restjava >/dev/null 2>&1 || return 1
  required_mirror_services_ready || return 1
  deployment_ready mirror-1-restjava 5 && return 1
  return 0
}

cleanup_record_stream_files_only() {
  mkdir -p "${RECORD_STREAMS_DIR}"
  if [[ -d "${RECORD_STREAMS_DIR}" ]]; then
    find "${RECORD_STREAMS_DIR}" \
      -type f \
      -path "${RECORD_STREAMS_DIR}/record0.0.*/*" \
      \( -name "*.rcd" -o -name "*.rcd.gz" -o -name "*.rcd_sig" -o -name "*.rcs_sig" \) \
      -delete || true
  fi
}

# Poll the kind apiserver until /readyz returns ok twice in a row (or timeout).
# After Solo's bulk rolling restarts (4 CN StatefulSets x 5 containers), the single
# control plane is briefly slow enough that kubectl's default 10s TLS-handshake
# budget expires — surfaces as "net/http: TLS handshake timeout" further down the
# script and looks like a Docker crash. Two consecutive OKs ride out a single
# flap so we do not race the apiserver right as it stabilises.
wait_for_apiserver_ready() {
  local timeout_secs="${1:-120}"
  local deadline=$((SECONDS + timeout_secs))
  local consec_ok=0
  while (( SECONDS < deadline )); do
    if kubectl --request-timeout=10s get --raw=/readyz >/dev/null 2>&1; then
      ((consec_ok++))
      if (( consec_ok >= 2 )); then
        return 0
      fi
    else
      consec_ok=0
    fi
    sleep 2
  done
  echo "apiserver did not return /readyz=ok within ${timeout_secs}s" >&2
  return 1
}

# Long --request-timeout on the kubectl waits so a single momentary apiserver
# slowdown during the rolling-restart burst does not abort the script. The
# inner --timeout=Xs is the wait-for-condition deadline; --request-timeout is
# the per-RPC TLS-handshake-and-response budget.
wait_for_consensus_pods_ready() {
  local timeout_secs="${1:-600}"
  local pod=""
  local nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  for pod in "${nodes[@]}"; do
    kubectl --request-timeout=60s -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/network-${pod}-0" --timeout="${timeout_secs}s"
  done
}

wait_for_haproxy_ready() {
  local timeout_secs="${1:-600}"
  local proxy
  local node_alias
  local node_aliases
  local proxies=()
  IFS=',' read -r -a node_aliases <<< "${NODE_ALIASES}"
  for node_alias in "${node_aliases[@]}"; do
    proxies+=("haproxy-${node_alias}")
  done

  for proxy in "${proxies[@]}"; do
    kubectl --request-timeout=60s -n "${SOLO_NAMESPACE}" rollout status "deployment/${proxy}" --timeout="${timeout_secs}s"
  done
}

# Scale MinIO down to zero so the mirror importer's RECORD source (S3 polling
# of .rcd.gz files) cannot resolve — leaving block-node-${BLOCK_NODE_ID} as the
# only viable source the importer's CompositeBlockSource can serve from. Used
# by Step 9 to prove the BN -> importer hop actually works end-to-end; under
# normal conditions cheetah uploads .rcd.gz files to MinIO faster than the BN
# can stream the matching WRB, so the records path consistently wins the race
# and the BN path is never exercised even though it is the configured-primary.
#
# Owner inference: solo's default deploy is a plain StatefulSet behind
# pod/minio-pool-1-0; we read the pod's ownerReference and scale that.
#
# Restored by reconnect_importer_to_minio. The cleanup trap also calls the
# reconnect helper unconditionally so a script abort mid-Step-9 does not leave
# MinIO down for subsequent re-runs.
MINIO_DISCONNECTED_OWNER_KIND=""
MINIO_DISCONNECTED_OWNER_NAME=""

disconnect_importer_from_minio() {
  local owner_kind owner_name
  owner_kind="$(kubectl --request-timeout=10s -n "${MINIO_NAMESPACE}" get pod minio-pool-1-0 \
    -o jsonpath='{.metadata.ownerReferences[0].kind}' 2>/dev/null || true)"
  owner_name="$(kubectl --request-timeout=10s -n "${MINIO_NAMESPACE}" get pod minio-pool-1-0 \
    -o jsonpath='{.metadata.ownerReferences[0].name}' 2>/dev/null || true)"
  if [[ -z "${owner_kind}" || -z "${owner_name}" ]]; then
    echo "WARNING: could not find MinIO pod owner in namespace ${MINIO_NAMESPACE}; skipping disconnect" >&2
    return 1
  fi
  MINIO_DISCONNECTED_OWNER_KIND="${owner_kind}"
  MINIO_DISCONNECTED_OWNER_NAME="${owner_name}"
  echo "Scaling ${owner_kind}/${owner_name} in namespace ${MINIO_NAMESPACE} to 0 replicas — severing importer RECORD path"
  if ! kubectl --request-timeout=30s -n "${MINIO_NAMESPACE}" scale "${owner_kind}/${owner_name}" --replicas=0; then
    echo "WARNING: scaling ${owner_kind}/${owner_name} to 0 failed; importer's RECORD path may still be live" >&2
    MINIO_DISCONNECTED_OWNER_KIND=""
    MINIO_DISCONNECTED_OWNER_NAME=""
    return 1
  fi
  echo "Waiting up to 60s for minio-pool-1-0 to terminate"
  kubectl --request-timeout=10s -n "${MINIO_NAMESPACE}" wait --for=delete pod/minio-pool-1-0 --timeout=60s >/dev/null 2>&1 || true
}

reconnect_importer_to_minio() {
  if [[ -z "${MINIO_DISCONNECTED_OWNER_KIND}" || -z "${MINIO_DISCONNECTED_OWNER_NAME}" ]]; then
    return 0
  fi
  echo "Scaling ${MINIO_DISCONNECTED_OWNER_KIND}/${MINIO_DISCONNECTED_OWNER_NAME} in namespace ${MINIO_NAMESPACE} back to 1 replica"
  kubectl --request-timeout=30s -n "${MINIO_NAMESPACE}" scale \
    "${MINIO_DISCONNECTED_OWNER_KIND}/${MINIO_DISCONNECTED_OWNER_NAME}" --replicas=1 || true
  # Give MinIO time to recover before later steps read block-stream objects from the bucket.
  echo "Waiting up to 180s for minio-pool-1-0 to become Ready after reconnect"
  if ! kubectl --request-timeout=10s -n "${MINIO_NAMESPACE}" wait --for=condition=ready pod/minio-pool-1-0 --timeout=180s >/dev/null 2>&1; then
    echo "WARNING: minio-pool-1-0 did not reach Ready within 180s after reconnect; downstream MinIO reads may need retries" >&2
  fi
  MINIO_DISCONNECTED_OWNER_KIND=""
  MINIO_DISCONNECTED_OWNER_NAME=""
}

# CN gRPC / Mirror REST port-forwards target Kubernetes *Services*, so kubectl re-resolves endpoints
# across an upgrade's pod + HAProxy rolls and the tunnels keep working on their own (verified
# end-to-end across the 0.74/0.75/0.76/0.77 upgrades: every post-upgrade cryptoCreate succeeded with
# NO re-establishment). The legacy refresh below (kill all stale forwards + re-spawn) was therefore
# unnecessary -- and worse, that kill/respawn storm raced the post-upgrade container+network churn
# through Docker Desktop's gvisor stack and crashed the VM right after the 0.76/0.77 cutover. So we
# leave healthy forwards untouched and only fall through to the destructive re-establishment when a
# forward is actually DOWN.
restart_post_upgrade_port_forwards() {
  local cn_log="${WORK_DIR}/port-forward-cn.log"
  local mirror_log="${WORK_DIR}/port-forward-mirror.log"

  local cn_ok=false mirror_ok=false
  wait_for_tcp_open "127.0.0.1" "${CN_GRPC_LOCAL_PORT}" 1 1 && cn_ok=true
  if mirror_rest_service_exists; then
    wait_for_tcp_open "127.0.0.1" "${MIRROR_REST_LOCAL_PORT}" 1 1 && mirror_ok=true
  else
    mirror_ok=true
  fi
  if [[ "${cn_ok}" == "true" && "${mirror_ok}" == "true" ]]; then
    echo "  CN gRPC (:${CN_GRPC_LOCAL_PORT}) and Mirror REST (:${MIRROR_REST_LOCAL_PORT}) forwards survived the upgrade; skipping destructive re-establishment"
    return 0
  fi
  echo "  Post-upgrade forward health check: cn_ok=${cn_ok} mirror_ok=${mirror_ok} -- a forward is down, re-establishing it"

  # Don't even attempt to talk to the apiserver while it's still flapping from
  # the post-upgrade rolling-restart burst; the get-endpoints poll below would
  # otherwise time out on a TLS handshake and return empty.
  wait_for_apiserver_ready 180 || return 1

  if [[ -n "${CN_PORT_FORWARD_PID}" ]]; then
    kill "${CN_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    CN_PORT_FORWARD_PID=""
  fi
  if [[ -n "${MIRROR_PORT_FORWARD_PID}" ]]; then
    kill "${MIRROR_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    MIRROR_PORT_FORWARD_PID=""
  fi
  cleanup_stale_port_forwards
  kill_processes_on_local_port "${CN_GRPC_LOCAL_PORT}"
  kill_processes_on_local_port "${MIRROR_REST_LOCAL_PORT}"
  sleep 1

  # Confirm the haproxy service has endpoints before starting the port-forward.
  # Solo's freeze-restart can leave svc/haproxy-node1-svc temporarily without
  # endpoints if the haproxy pod is still rolling — kubectl port-forward picks
  # up no target and never binds the local port, then wait_for_tcp_open below
  # times out with no visible reason. Wait up to 60s for an endpoint IP.
  #
  # Distinguish three failure modes:
  #   1. kubectl unreachable (Docker daemon died / kind cluster gone) — surface
  #      kubectl's actual stderr so the operator knows it's an environment issue.
  #   2. Endpoint present but empty (selector mismatch, pod still terminating).
  #   3. Endpoint populated — proceed.
  local svc_endpoint_deadline=$((SECONDS + 60))
  local svc_endpoint=""
  local kubectl_stderr="${WORK_DIR}/restart-port-forward-kubectl.err"
  : > "${kubectl_stderr}"
  while (( SECONDS < svc_endpoint_deadline )); do
    svc_endpoint="$(kubectl --request-timeout=30s -n "${SOLO_NAMESPACE}" get endpoints haproxy-node1-svc \
      -o jsonpath='{.subsets[0].addresses[0].ip}' 2>"${kubectl_stderr}")" || true
    [[ -n "${svc_endpoint}" ]] && break
    sleep 2
  done
  if [[ -z "${svc_endpoint}" ]]; then
    local kubectl_err_tail
    kubectl_err_tail="$(tail -n 3 "${kubectl_stderr}" 2>/dev/null)"
    if [[ -n "${kubectl_err_tail}" ]]; then
      echo "kubectl could not reach the apiserver while polling svc/haproxy-node1-svc endpoints:" >&2
      printf '%s\n' "${kubectl_err_tail}" | sed 's/^/    /' >&2
      echo "  This is almost always Docker Desktop crashing under load — check 'docker info' and Docker Desktop's resource limits." >&2
    else
      echo "svc/haproxy-node1-svc has no endpoints after 60s (kubectl reachable but endpoint set empty); cannot port-forward to ${CN_GRPC_LOCAL_PORT}" >&2
      echo "  Snapshot of svc + endpoints + matching pods:" >&2
      kubectl -n "${SOLO_NAMESPACE}" get svc haproxy-node1-svc -o yaml >&2 2>/dev/null || true
      kubectl -n "${SOLO_NAMESPACE}" get endpoints haproxy-node1-svc -o yaml >&2 2>/dev/null || true
      kubectl -n "${SOLO_NAMESPACE}" get pods -l app=haproxy-node1 -o wide --show-labels >&2 2>/dev/null || true
    fi
    return 1
  fi
  echo "  svc/haproxy-node1-svc endpoint ${svc_endpoint} ready; opening port-forward to localhost:${CN_GRPC_LOCAL_PORT} (kubectl log: ${cn_log})"
  : > "${cn_log}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >"${cn_log}" 2>&1 < /dev/null &
  CN_PORT_FORWARD_PID="$!"
  disown "${CN_PORT_FORWARD_PID}" 2>/dev/null || true

  if mirror_rest_service_exists; then
    # Space the second port-forward handshake out from the first so the apiserver
    # is not asked for two new TLS sessions in the same millisecond. Cheap insurance.
    sleep 2
    echo "  svc/${MIRROR_REST_SERVICE} present; opening port-forward to localhost:${MIRROR_REST_LOCAL_PORT} (kubectl log: ${mirror_log})"
    : > "${mirror_log}"
    nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${MIRROR_REST_SERVICE}" "${MIRROR_REST_LOCAL_PORT}:http" >"${mirror_log}" 2>&1 < /dev/null &
    MIRROR_PORT_FORWARD_PID="$!"
    disown "${MIRROR_PORT_FORWARD_PID}" 2>/dev/null || true
  fi
  sleep 2

  if ! wait_for_tcp_open "127.0.0.1" "${CN_GRPC_LOCAL_PORT}" 20 1; then
    echo "Consensus gRPC port-forward did not become reachable on localhost:${CN_GRPC_LOCAL_PORT}" >&2
    if kill -0 "${CN_PORT_FORWARD_PID}" 2>/dev/null; then
      echo "  kubectl process is still alive (PID ${CN_PORT_FORWARD_PID}); last 20 log lines:" >&2
    else
      echo "  kubectl process died (PID ${CN_PORT_FORWARD_PID}); last 20 log lines:" >&2
    fi
    tail -n 20 "${cn_log}" >&2 2>/dev/null || true
    return 1
  fi
  if [[ -n "${MIRROR_PORT_FORWARD_PID}" ]] && ! wait_for_tcp_open "127.0.0.1" "${MIRROR_REST_LOCAL_PORT}" 20 1; then
    echo "Mirror REST port-forward did not become reachable on localhost:${MIRROR_REST_LOCAL_PORT}" >&2
    if kill -0 "${MIRROR_PORT_FORWARD_PID}" 2>/dev/null; then
      echo "  kubectl process is still alive (PID ${MIRROR_PORT_FORWARD_PID}); last 20 log lines:" >&2
    else
      echo "  kubectl process died (PID ${MIRROR_PORT_FORWARD_PID}); last 20 log lines:" >&2
    fi
    tail -n 20 "${mirror_log}" >&2 2>/dev/null || true
    return 1
  fi
}

minio_discover_service() {
  local ns="$1"
  local svc
  if [[ -n "${MINIO_SERVICE_NAME}" ]]; then
    echo "${MINIO_SERVICE_NAME}"
    return 0
  fi
  if kubectl -n "${ns}" get svc minio >/dev/null 2>&1; then
    echo "minio"
    return 0
  fi
  if kubectl -n "${ns}" get svc minio-hl >/dev/null 2>&1; then
    echo "minio-hl"
    return 0
  fi
  svc="$(kubectl -n "${ns}" get svc -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("minio"; "i"))
    | select(test("console"; "i") | not)
    | select(test("headless"; "i") | not)
  ' | head -n 1)"
  if [[ -z "${svc}" ]]; then
    return 1
  fi
  echo "${svc}"
}

minio_discover_service_port() {
  local ns="$1"
  local svc="$2"
  local port
  # Prefer the service port that targets container port 9000.
  port="$(kubectl -n "${ns}" get svc "${svc}" -o json 2>/dev/null | jq -r '
    first(.spec.ports[] | select((.targetPort|tostring) == "9000") | .port // empty)
  ')"
  if [[ -z "${port}" || "${port}" == "null" ]]; then
    port="$(kubectl -n "${ns}" get svc "${svc}" -o json 2>/dev/null | jq -r '.spec.ports[0].port // empty')"
  fi
  [[ -n "${port}" && "${port}" != "null" ]] || return 1
  echo "${port}"
}

minio_discover_pod_credentials() {
  local ns="$1"
  local pod u p cfg
  pod="$(kubectl -n "${ns}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("^minio-"))
  ' | head -n 1)"
  [[ -n "${pod}" ]] || return 1

  # ${MINIO_CONFIG_ENV_FILE} is intentionally expanded inside the minio container (not by the host shell),
  # since the env var is set by the minio chart on the pod. Single quotes around the sh -lc payload are required.
  # shellcheck disable=SC2016
  cfg="$(kubectl -n "${ns}" exec "${pod}" -c minio -- sh -c 'cat "${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}" 2>/dev/null || true' 2>/dev/null || true)"
  if [[ -n "${cfg}" ]]; then
    u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_USER=//p' | head -1 | tr -d '\r')"
    p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_PASSWORD=//p' | head -1 | tr -d '\r')"
    if [[ -z "${u}" || -z "${p}" ]]; then
      u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ACCESS_KEY=//p' | head -1 | tr -d '\r')"
      p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_SECRET_KEY=//p' | head -1 | tr -d '\r')"
    fi
  fi
  u="${u//$'\r'/}"
  p="${p//$'\r'/}"
  u="${u%\"}"
  u="${u#\"}"
  p="${p%\"}"
  p="${p#\"}"
  if [[ -n "${u}" && -n "${p}" ]]; then
    printf '%s\n' "${u}" "${p}"
    return 0
  fi
  return 1
}

minio_discover_secret_env_credentials() {
  local ns="$1"
  local secret="$2"
  local cfg u p
  cfg="$(kubectl -n "${ns}" get secret "${secret}" -o jsonpath='{.data.config\.env}' 2>/dev/null | base64 -d 2>/dev/null || true)"
  [[ -n "${cfg}" ]] || return 1
  u="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_USER=//p' | head -1 | tr -d '\r')"
  p="$(echo "${cfg}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_ROOT_PASSWORD=//p' | head -1 | tr -d '\r')"
  u="${u%\"}"
  u="${u#\"}"
  p="${p%\"}"
  p="${p#\"}"
  if [[ -n "${u}" && -n "${p}" ]]; then
    printf '%s\n' "${u}" "${p}"
    return 0
  fi
  return 1
}

download_solo_record_streams_via_pod_mc() {
  local names_file="$1"
  local svc="$2"
  local svc_port="$3"
  local pod endpoint creds_tmp all_objects creds_file
  local wanted_timestamps selected_objects
  local u p selected_u selected_p remote subpath dest
  local server_url cfg_full
  local list_ok=0 endpoint_try
  local wanted_count
  local found=0 sig_found=0 failed=0

  pod="$(kubectl -n "${MINIO_NAMESPACE}" get pods -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("^minio-"))
  ' | head -n 1)"
  [[ -n "${pod}" ]] || {
    echo "Could not find MinIO pod in namespace ${MINIO_NAMESPACE}" >&2
    return 1
  }

  creds_file="$(mktemp)"
  creds_tmp="$(mktemp)"
  if minio_discover_pod_credentials "${MINIO_NAMESPACE}" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  : >"${creds_tmp}"
  if minio_discover_secret_env_credentials "${MINIO_NAMESPACE}" "minio-secrets" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  : >"${creds_tmp}"
  if minio_discover_secret_env_credentials "${MINIO_NAMESPACE}" "myminio-env-configuration" >"${creds_tmp}"; then
    paste -sd '\t' "${creds_tmp}" >>"${creds_file}"
  fi
  rm -f "${creds_tmp}"
  if [[ ! -s "${creds_file}" ]]; then
    rm -f "${creds_file}"
    echo "Could not discover any MinIO root credentials in namespace ${MINIO_NAMESPACE}" >&2
    return 1
  fi

  cfg_full="$(kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -c \
    "cat \"\${MINIO_CONFIG_ENV_FILE:-/tmp/minio/config.env}\" 2>/dev/null || true" 2>/dev/null || true)"
  server_url="$(echo "${cfg_full}" | sed -n -E 's/^(export[[:space:]]+)?MINIO_SERVER_URL=//p' | head -1 | tr -d '"\r')"

  all_objects="$(mktemp)"
  # Retries plus alternate in-cluster endpoints avoid transient DNS/service hiccups during upgrade.
  for _ in 1 2 3 4 5 6; do
    for endpoint_try in \
      "${server_url}" \
      "http://${svc}.${MINIO_NAMESPACE}.svc.cluster.local:${svc_port}" \
      "http://minio-hl.${MINIO_NAMESPACE}.svc.cluster.local:9000"; do
      [[ -n "${endpoint_try}" ]] || continue
      endpoint="${endpoint_try}"
      while IFS=$'\t' read -r u p; do
        [[ -n "${u}" && -n "${p}" ]] || continue
        if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -c \
          "mc alias set local '${endpoint}' '${u}' '${p}' >/dev/null 2>&1; mc find local/${MINIO_BUCKET}/recordstreams --name '*.rcd*'" \
          >"${all_objects}" 2>/tmp/inpod-mc-list.err; then
          selected_u="${u}"
          selected_p="${p}"
          list_ok=1
          break
        fi
      done < "${creds_file}"
      (( list_ok == 1 )) && break
    done
    (( list_ok == 1 )) && break
    sleep 2
  done
  rm -f "${creds_file}" >/dev/null 2>&1 || true
  if (( list_ok == 0 )); then
    rm -f "${all_objects}"
    echo "Failed to list MinIO objects via in-pod mc" >&2
    return 1
  fi

  wanted_timestamps="$(mktemp)"
  selected_objects="$(mktemp)"
  awk '{
    f = $0;
    sub(/^.*\//, "", f);
    if (match(f, /Z/)) {
      print substr(f, 1, RSTART);
    }
  }' "${names_file}" | sort -u > "${wanted_timestamps}"
  wanted_count="$(wc -l < "${wanted_timestamps}" | tr -d ' ')"
  if [[ "${wanted_count}" == "0" ]]; then
    rm -f "${wanted_timestamps}" "${selected_objects}" "${all_objects}" >/dev/null 2>&1 || true
    echo "Could not derive wanted timestamps from mirror names file" >&2
    return 1
  fi

  awk 'NR == FNR { wanted[$1] = 1; next }
    {
      bn = $0;
      sub(/^.*\//, "", bn);
      if (match(bn, /Z/)) {
        ts = substr(bn, 1, RSTART);
        if (wanted[ts]) {
          print $0;
        }
      }
    }' "${wanted_timestamps}" "${all_objects}" | sort -u > "${selected_objects}"

  while IFS= read -r remote; do
    [[ -z "${remote}" ]] && continue

    subpath="${remote#local/"${MINIO_BUCKET}"/recordstreams/}"
    if [[ "${subpath}" == "${remote}" ]]; then
      subpath="$(basename "${remote}")"
    fi
    dest="${RECORD_STREAMS_DIR}/${subpath}"
    mkdir -p "$(dirname "${dest}")"

    local copied=0
    for _ in 1 2 3; do
      if kubectl -n "${MINIO_NAMESPACE}" exec "${pod}" -c minio -- sh -c \
        "mc alias set local '${endpoint}' '${selected_u}' '${selected_p}' >/dev/null 2>&1; mc cat '${remote}'" \
        >"${dest}" 2>/dev/null; then
        copied=1
        break
      fi
      sleep 1
    done
    if (( copied == 1 )); then
      found=$((found + 1))
      if [[ "${remote}" == *.rcd_sig || "${remote}" == *.rcs_sig ]]; then
        sig_found=$((sig_found + 1))
      fi
    else
      rm -f "${dest}" >/dev/null 2>&1 || true
      failed=$((failed + 1))
    fi
  done < "${selected_objects}"

  rm -f "${wanted_timestamps}" >/dev/null 2>&1 || true
  rm -f "${selected_objects}" >/dev/null 2>&1 || true
  rm -f "${all_objects}" >/dev/null 2>&1 || true

  if (( found == 0 )); then
    return 1
  fi
  if (( sig_found == 0 )); then
    echo "No signature files were downloaded from MinIO for selected timestamps" >&2
    return 1
  fi
  return 0
}

# Mirror may return an absolute URL or a path-only next link.
mirror_resolve_next_url() {
  local base="$1"
  local next="$2"
  if [[ -z "${next}" ]]; then
    echo ""
    return 0
  fi
  if [[ "${next}" == http://* || "${next}" == https://* ]]; then
    echo "${next}"
    return 0
  fi
  if [[ "${next}" == /* ]]; then
    local origin
    origin="$(echo "${base}" | sed -E 's|(https?://[^/]+).*|\1|')"
    echo "${origin}${next}"
    return 0
  fi
  echo "${base%/}/${next}"
}

# Paginate mirror /api/v1/blocks (ascending), collect unique record file basenames for blocks with number <= max_block.
collect_record_filenames_up_to_block() {
  local mirror_base="$1"
  local max_block="$2"
  local out_file="$3"
  local next_url="${mirror_base%/}/api/v1/blocks?order=asc&limit=100"
  local j last_num count
  : >"${out_file}"
  while [[ -n "${next_url}" ]]; do
    j="$(curl -sf "${next_url}")" || return 1
    count="$(echo "${j}" | jq '.blocks | length')"
    if [[ "${count}" == "0" || "${count}" == "null" ]]; then
      break
    fi
    echo "${j}" | jq -r --argjson max "${max_block}" '
      .blocks[]
      | select(.number <= $max)
      | (.name // empty)
      | split("/")
      | last
      | select(length > 0)
    ' >>"${out_file}"
    last_num="$(echo "${j}" | jq -r '.blocks[-1].number')"
    if [[ "${last_num}" == "null" ]]; then
      break
    fi
    if (( last_num >= max_block )); then
      break
    fi
    next_url="$(mirror_resolve_next_url "${mirror_base}" "$(echo "${j}" | jq -r '.links.next // empty')")"
  done
  sort -u "${out_file}" -o "${out_file}"
}

# Download record stream objects from the Solo MinIO bucket (default solo-streams) whose basenames appear
# on blocks <= max_block in the mirror (same names as /api/v1/blocks[].name).
download_solo_minio_record_streams() {
  local max_block="$1"
  local mirror_base="$2"
  local names_file svc svc_port nfiles

  mkdir -p "${RECORD_STREAMS_DIR}"
  names_file="$(mktemp)"
  log "Collecting record stream file names from mirror for blocks <= ${max_block}"
  collect_record_filenames_up_to_block "${mirror_base}" "${max_block}" "${names_file}" || {
    echo "Failed to list blocks from mirror for record file discovery" >&2
    rm -f "${names_file}"
    return 1
  }
  if [[ ! -s "${names_file}" ]]; then
    log "No record file names from mirror (empty result); skipping MinIO download"
    rm -f "${names_file}"
    return 0
  fi
  nfiles="$(wc -l < "${names_file}" | tr -d ' ')"
  log "Found ${nfiles} unique record stream file name(s) to resolve in MinIO"

  svc="$(minio_discover_service "${MINIO_NAMESPACE}")" || {
    echo "Could not find a MinIO Service in namespace ${MINIO_NAMESPACE}" >&2
    rm -f "${names_file}"
    return 1
  }
  svc_port="$(minio_discover_service_port "${MINIO_NAMESPACE}" "${svc}")" || {
    echo "Could not resolve service port for MinIO service ${svc}" >&2
    rm -f "${names_file}"
    return 1
  }

  if ! download_solo_record_streams_via_pod_mc "${names_file}" "${svc}" "${svc_port}"; then
    echo "Unable to download from in-pod MinIO fallback in namespace ${MINIO_NAMESPACE}" >&2
    rm -f "${names_file}"
    return 1
  fi
  rm -f "${names_file}"
}

local_build_implementation_version() {
  unzip -p "${LOCAL_BUILD_PATH}/apps/HederaNode.jar" META-INF/MANIFEST.MF 2>/dev/null \
    | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1
}

consensus_pod_implementation_version() {
  local pod="$1"
  # MANIFEST.MF uses CRLF line endings — strip the trailing \r so callers can safely
  # embed the result in echo lines without it rewinding the cursor mid-print.
  kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
    "unzip -p /opt/hgcapp/services-hedera/HapiApp2.0/data/apps/HederaNode.jar META-INF/MANIFEST.MF 2>/dev/null \
      | sed -n 's/^Implementation-Version: //p' | tr -d '\r' | head -n 1"
}

verify_local_build_on_consensus_nodes() {
  local node pod
  local nodes=()
  local local_version=""
  local pod_version=""

  local_version="$(local_build_implementation_version)"
  [[ -n "${local_version}" ]] || { echo "Unable to determine local build version for verification" >&2; return 1; }

  echo "Verifying local-build version on each consensus node (expected ${local_version})"
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

# Verifies each consensus node runs a release whose Implementation-Version contains the expected
# substring (e.g. "0.76"). Used for the 0.76 step, which upgrades to a published release image
# rather than the local build, so the local-build version check does not apply.
verify_release_version_on_consensus_nodes() {
  local expected_substr="$1"
  local node pod pod_version
  local nodes=()

  echo "Verifying release version on each consensus node (expected to contain '${expected_substr}')"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    pod_version="$(consensus_pod_implementation_version "${pod}" || true)"
    if [[ "${pod_version}" == *"${expected_substr}"* ]]; then
      echo "  ${pod}: ${pod_version} OK"
    else
      echo "  ${pod}: expected version containing '${expected_substr}', found ${pod_version:-unknown}" >&2
      return 1
    fi
  done
}

run_command_with_timeout() {
  local timeout_secs="$1"
  shift

  local cmd_pid=""
  local start_ts
  local elapsed

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

run_075_upgrade() {
  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --upgrade-version "${UPGRADE_075_VERSION}"
    --application-properties "${TMP_075_UPGRADE_APP_PROPS}"
    --quiet-mode
    --force
  )

  run_step "Upgrading consensus network to ${UPGRADE_075_VERSION} (jumpstart)" \
    run_command_with_timeout "${SOLO_075_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
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

verify_wraps_on_consensus_nodes() {
  local wraps_dir=""
  local expected_wraps=""
  local timeout_secs="${1:-600}"
  local deadline=0
  local node=""
  local pod=""
  local found_env=""
  local found_wraps=""
  local ready_for_proof=false
  local nodes=()

  wraps_dir="$(configured_wraps_artifacts_container_dir)"
  # The CN downloads + extracts the WRAPS archive itself into ${wraps_dir}; we expect at least
  # this many artifact files to land there. No local copy is downloaded by this script.
  expected_wraps="${WRAPS_REQUIRED_FILE_COUNT}"

  echo "Verifying WRAPS runtime on each consensus node (env=${wraps_dir}, expecting >=${expected_wraps} self-downloaded artifact files, up to ${timeout_secs}s/node for env+artifacts+proof construction)"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    deadline=$((SECONDS + timeout_secs))

    # Phase 1: poll for TSS_LIB_WRAPS_ARTIFACTS_PATH to be set in the JVM
    # env AND for WrapsProvingKeyVerification to finish extracting the
    # proving-key archive. Both happen asynchronously after the pod reports
    # Ready, so a single sample at t=0 may catch the JVM mid-extract with
    # only a partial file count — poll rather than failing fast.
    # "WRAPS library is not ready" is a retryable transient now (see WrapsHistoryProver),
    # so we only fail on the outer timeout, not on that log line appearing.
    ready_for_proof=false
    found_env=""
    found_wraps=""
    while (( SECONDS < deadline )); do
      found_env="$(consensus_pod_wraps_env "${pod}" || true)"
      found_wraps="$(consensus_pod_wraps_file_count "${pod}" "${wraps_dir}" || true)"
      if [[ "${found_env}" == "${wraps_dir}" && "${found_wraps:-0}" -ge "${expected_wraps}" ]]; then
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
      if wraps_proof_present_in_log "${pod}"; then
        echo "  ${pod}: WRAPS proof construction detected"
        break
      fi
      # Every ~30s (6 ticks of 5s) emit a heartbeat — proof construction can take a while.
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

# Wraps remedy strategy:
# 1. Each CN downloads + extracts the WRAPS proving-key archive itself from the
#    tss.wrapsProvingKeyDownloadUrl set in resources/0.76|0.77 application.properties.
#    No host-side pre-download or local nginx server is involved; to use a different
#    URL, point APP_PROPS_076_FILE/APP_PROPS_077_FILE at edited copies.
# 2. Inject TSS_LIB_WRAPS_ARTIFACTS_PATH directly into each network-nodeX
#    StatefulSet's container spec via `kubectl set env`. This is the only path
#    we've confirmed actually reaches the JVM `/proc/$pid/environ`. Solo's
#    --application-env drops the file at /etc/network-node/env/application.env
#    but the container entrypoint never sources it. Setting via the spec also
#    survives subsequent pod restarts (kubectl delete pod, freeze-upgrades,
#    container crashes), which the ephemeral kubectl-cp + entrypoint patch
#    approach did NOT survive.
# 3. After Solo's upgrade returns (success OR timeout), recover any CN that
#    failed to reach ACTIVE/CHECKING/OBSERVING. Jars + state live on the PVC,
#    so a `kubectl delete pod` re-rolls the JVM from a settled disk and
#    sidesteps the "jars still copying" startup race that intermittently kills
#    one or two nodes per upgrade.

inject_wraps_env_into_statefulsets() {
  local node sts log_file
  local wraps_dir nodes=()
  wraps_dir="$(configured_wraps_artifacts_container_dir)"
  log_file="${WORK_DIR}/inject-wraps-env.log"
  : > "${log_file}"

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  local -a wraps_env_args=("TSS_LIB_WRAPS_ARTIFACTS_PATH=${wraps_dir}")
  if [[ "${WRAPS_NUM_CORES}" =~ ^[1-9][0-9]*$ ]]; then
    wraps_env_args+=("TSS_LIB_NUM_OF_CORES=${WRAPS_NUM_CORES}")
  fi
  echo "Injecting ${wraps_env_args[*]} into ${#nodes[@]} consensus StatefulSets (log: ${log_file})"

  # `kubectl set env` emits a wave of duplicate-port warnings on every call
  # because Solo's pod template has `pprof`/`stats` named ports duplicated
  # across containers — and `kubectl rollout status` chats incrementally. Both
  # streams are noise the operator can read from the log file if needed; the
  # script just emits one summary line per node.
  for node in "${nodes[@]}"; do
    sts="network-${node}"
    {
      echo "=== set env statefulset/${sts} ==="
      kubectl -n "${SOLO_NAMESPACE}" set env "statefulset/${sts}" -c root-container \
        "${wraps_env_args[@]}" 2>&1
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

run_076_upgrade() {
  # The CN pulls the WRAPS proving key itself from the tss.wrapsProvingKeyDownloadUrl in the
  # 0.76 properties (tss.wrapsProvingKeyDownloadEnabled flow).

  # Inject TSS_LIB_WRAPS_ARTIFACTS_PATH into each StatefulSet's container spec
  # BEFORE Solo's upgrade fires. The rolling restart triggered here runs against
  # the 0.75 binary, which doesn't use the env var, so it's harmless. Solo's
  # subsequent freeze-restart is coordinated across all 4 nodes (they all stop
  # at the same consensus round and resume at the same round) and the env we
  # pre-injected is preserved through helm's strategic-merge upgrade, so the
  # 0.76 JVMs all initialize WRAPS in lockstep.
  #
  # If we instead inject AFTER Solo's upgrade, kubectl set env triggers a
  # rolling restart on each StatefulSet independently — one pod finishes WRAPS
  # init and publishes a proof key while others are still on the old config,
  # which causes a SELF_ISS catastrophic failure on every node.
  inject_wraps_env_into_statefulsets

  # Note: --wraps-key-path intentionally omitted. Solo only honors it on
  # `consensus network deploy`; on upgrade it's silently dropped.
  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --upgrade-version "${UPGRADE_076_VERSION}"
    --application-properties "${APP_PROPS_076_FILE}"
    --application-env "${APP_ENV_076_FILE}"
    --quiet-mode
    --force
  )

  # With hiero-ledger/solo#4440 in place, the upgrade stops the JVMs before
  # the JAR cp and restarts them after, so the previous JAR-staging race that
  # forced the stuck-pod recovery dance is gone. We let any non-zero Solo exit
  # (timeout, deploy validation, ACTIVE check failure) propagate via set -e.
  run_step "Upgrading consensus network to ${UPGRADE_076_VERSION} (release image, 0.76 properties)" \
    run_command_with_timeout "${SOLO_076_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"

  echo "--- Step 10 check 1/4: wait for consensus pods + haproxy + verify 0.76 release version ---"
  # Solo's `consensus network upgrade` rolls haproxy via its chart upgrade but
  # doesn't wait for the rollout — explicitly wait here so the next port-forward
  # step finds populated endpoints.
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  verify_release_version_on_consensus_nodes "0.76"

  # The TSS ceremony (proof key publication → CRS contribution → adoption →
  # proof construction) stalls without new rounds, and rounds don't advance
  # without transactions. Re-establish the CN/mirror port-forwards and submit
  # a cryptoCreate to drive consensus forward; otherwise verify_wraps will
  # time out waiting for "Constructing genesis WRAPS proof with:" on a totally
  # idle network.
  echo "--- Step 10 check 2/4: re-establish CN gRPC + Mirror REST port-forwards ---"
  restart_post_upgrade_port_forwards
  echo "  Waiting for Mirror REST to serve /api/v1/blocks on http://127.0.0.1:${MIRROR_REST_LOCAL_PORT} (up to 3m)"
  wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1" 36 5
  echo "  Mirror REST responding"
  wait_for_sdk_responsive 180

  echo "--- Step 10 check 3/4: submit cryptoCreate to nudge consensus + confirm mirror sees the new account ---"
  export MIRROR_ACCOUNT_WAIT_MS="${MIRROR_ACCOUNT_WAIT_MS:-180000}"
  node "${NODE_SCRIPT}"

  echo "--- Step 10 check 4/4: verify WRAPS runtime + proof construction on every consensus node ---"
  verify_wraps_on_consensus_nodes 600

  report_wraps_download_times
  echo "--- Step 10 all checks passed ---"
}

# Report, per consensus node, how long the CN itself spent downloading + extracting + verifying
# the WRAPS proving-key archive from tss.wrapsProvingKeyDownloadUrl. Parsed from hgcaa.log:
#   start: "WrapsProvingKeyVerification - ... Initiating download"
#   end:   "WrapsProvingKeyVerification - Successfully downloaded and verified WRAPS proving key"
# Duration is computed in pure awk (ms-of-day delta) so it works on both GNU and BSD date hosts.
report_wraps_download_times() {
  local node pod start_line end_line dur
  local nodes=()
  local hgcaa="${HAPI_PATH}/output/hgcaa.log"

  echo "WRAPS proving-key self-download times (per consensus node, from hgcaa.log):"
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    pod="network-${node}-0"
    start_line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "grep -aE 'WrapsProvingKeyVerification - .*Initiating download' '${hgcaa}' | head -n 1" 2>/dev/null || true)"
    end_line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
      "grep -aE 'WrapsProvingKeyVerification - Successfully downloaded and verified WRAPS proving key' '${hgcaa}' | head -n 1" 2>/dev/null || true)"
    if [[ -z "${start_line}" ]]; then
      echo "  ${pod}: no self-download observed (proving key already present, or not started)"
      continue
    fi
    if [[ -z "${end_line}" ]]; then
      echo "  ${pod}: download started but completion not yet logged"
      continue
    fi
    # Each hgcaa.log line begins with 'YYYY-MM-DD HH:MM:SS.mmm'; split on space/colon/dot and
    # take the wall-clock-of-day delta in milliseconds (guarding a midnight rollover).
    dur="$(awk -v a="${start_line}" -v b="${end_line}" '
      function ms(t,   x){ split(t, x, /[ :.]/); return ((x[2]*3600)+(x[3]*60)+x[4])*1000 + x[5] }
      BEGIN { d = ms(b) - ms(a); if (d < 0) d += 86400000; printf "%.3f", d/1000 }')"
    echo "  ${pod}: ${dur}s"
  done
}

run_077_upgrade() {
  # 0.77 BLOCKS-only cutover. WRAPS env + on-disk artifacts carry forward from Step 10; the
  # 0.77 properties keep the same download URL so any restarted pod re-fetches from it.
  local upgrade_cmd=(
    solo consensus network upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --node-aliases "${NODE_ALIASES}"
    --upgrade-version "${UPGRADE_077_VERSION}"
    --local-build-path "${LOCAL_BUILD_PATH}"
    --application-properties "${APP_PROPS_077_FILE}"
    --quiet-mode
    --force
  )

  run_step "Upgrading consensus network to ${UPGRADE_077_VERSION} (local build, 0.77 BLOCKS-only cutover)" \
    run_command_with_timeout "${SOLO_077_UPGRADE_TIMEOUT_SECS}" "${upgrade_cmd[@]}"

  echo "--- Step 11 check 1/4: wait for consensus pods + haproxy + verify local-build version ---"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  verify_local_build_on_consensus_nodes

  echo "--- Step 11 check 2/4: re-establish CN gRPC + Mirror REST port-forwards ---"
  restart_post_upgrade_port_forwards
  echo "  Waiting for Mirror REST to serve /api/v1/blocks on http://127.0.0.1:${MIRROR_REST_LOCAL_PORT} (up to 3m)"
  wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1" 36 5
  echo "  Mirror REST responding"
  wait_for_sdk_responsive 180

  echo "--- Step 11 check 3/4: submit cryptoCreate post-cutover and confirm mirror sees the new account ---"
  export MIRROR_ACCOUNT_WAIT_MS="${MIRROR_ACCOUNT_WAIT_MS:-180000}"
  node "${NODE_SCRIPT}"

  echo "--- Step 11 check 4/4: verify WRAPS runtime + real (non-mock) proof construction ---"
  verify_wraps_on_consensus_nodes 600
  echo "--- Step 11 all checks passed ---"
}

create_temp_075_upgrade_properties() {
  cp "${APP_PROPS_075_FILE}" "${TMP_075_UPGRADE_APP_PROPS}"
  {
    echo ""
    echo "# Added by solo-e2e-block-stream-cutover.sh from jumpstart.bin"
    echo "blockStream.jumpstart.blockNum=${JUMPSTART_BLOCK_NUMBER}"
    echo "blockStream.jumpstart.previousWrappedRecordBlockHash=${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}"
    echo "blockStream.jumpstart.consensusTimestampHash=${JUMPSTART_CONSENSUS_TIMESTAMP_HASH}"
    echo "blockStream.jumpstart.outputItemsTreeRootHash=${JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH}"
    echo "blockStream.jumpstart.streamingHasherLeafCount=${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}"
    echo "blockStream.jumpstart.streamingHasherHashCount=${JUMPSTART_STREAMING_HASHER_HASH_COUNT}"
    echo "blockStream.jumpstart.streamingHasherSubtreeHashes=${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES}"
    echo ""
    echo "# WRB streaming to the Solo-deployed Block Node (Step 7 deployment)."
    echo "blockNode.blockNodeConnectionFileDir=data/config"
  } >> "${TMP_075_UPGRADE_APP_PROPS}"
}

discover_grafana_service_name() {
  local svc="${GRAFANA_SERVICE_NAME}"

  if kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get svc "${svc}" >/dev/null 2>&1; then
    printf '%s\n' "${svc}"
    return 0
  fi
  if kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get svc grafana >/dev/null 2>&1; then
    printf '%s\n' "grafana"
    return 0
  fi

  svc="$(kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get svc -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("grafana"; "i"))
  ' | head -n 1)"
  [[ -n "${svc}" ]] || return 1
  printf '%s\n' "${svc}"
}

wait_for_grafana_service_endpoints() {
  local service_name="$1"
  local max_attempts="${2:-60}"
  local attempt=1

  while (( attempt <= max_attempts )); do
    if kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get endpoints "${service_name}" -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null | grep -qE '^[0-9]'; then
      return 0
    fi
    sleep 5
    ((attempt++))
  done
  return 1
}

start_grafana_port_forward() {
  local attempt=1
  local max_attempts=60
  local grafana_service=""

  if wait_for_http_ok "http://127.0.0.1:${GRAFANA_LOCAL_PORT}/api/health" 1 1; then
    echo "Grafana port-forward is active on http://127.0.0.1:${GRAFANA_LOCAL_PORT}"
    return 0
  fi

  log "Waiting for Grafana service to become available"
  while (( attempt <= max_attempts )); do
    if grafana_service="$(discover_grafana_service_name)"; then
      break
    fi
    sleep 5
    ((attempt++))
  done

  if (( attempt > max_attempts )); then
    echo "Timed out waiting for Grafana service in namespace ${SOLO_CLUSTER_SETUP_NAMESPACE} (tried ${GRAFANA_SERVICE_NAME} and auto-discovery)" >&2
    return 1
  fi

  if ! wait_for_grafana_service_endpoints "${grafana_service}" 60; then
    echo "Grafana service ${grafana_service} found but has no ready endpoints in namespace ${SOLO_CLUSTER_SETUP_NAMESPACE}" >&2
    return 1
  fi
  ACTIVE_GRAFANA_SERVICE_NAME="${grafana_service}"

  local pf_attempt=1
  local pf_max_attempts=6
  kill_processes_on_local_port "${GRAFANA_LOCAL_PORT}"
  while (( pf_attempt <= pf_max_attempts )); do
    nohup kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" port-forward "svc/${grafana_service}" "${GRAFANA_LOCAL_PORT}:80" >/dev/null 2>&1 < /dev/null &
    GRAFANA_PORT_FORWARD_PID="$!"
    disown "${GRAFANA_PORT_FORWARD_PID}" 2>/dev/null || true

    sleep 2
    if kill -0 "${GRAFANA_PORT_FORWARD_PID}" >/dev/null 2>&1 \
      && wait_for_http_ok "http://127.0.0.1:${GRAFANA_LOCAL_PORT}/api/health" 10 1; then
      echo "Grafana port-forward established on http://127.0.0.1:${GRAFANA_LOCAL_PORT}"
      return 0
    fi

    if [[ -n "${GRAFANA_PORT_FORWARD_PID}" ]]; then
      kill "${GRAFANA_PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    fi
    GRAFANA_PORT_FORWARD_PID=""
    sleep 2
    ((pf_attempt++))
  done

  echo "Failed to establish Grafana port-forward on localhost:${GRAFANA_LOCAL_PORT}" >&2
  return 1
}

ensure_grafana_port_forward() {
  if start_grafana_port_forward; then
    return 0
  fi

  if [[ "${ALLOW_GRAFANA_PORT_FORWARD_FAILURE}" == "true" ]]; then
    echo "WARNING: Grafana port-forward is unavailable; continuing without Grafana tunnel" >&2
    return 0
  fi

  echo "Grafana port-forward is required but unavailable." >&2
  return 1
}

start_explorer_ingress_port_forward() {
  local ns svc

  if wait_for_tcp_open "127.0.0.1" "${EXPLORER_INGRESS_LOCAL_PORT}" 1 1; then
    echo "Explorer ingress port-forward is active on localhost:${EXPLORER_INGRESS_LOCAL_PORT}"
    return 0
  fi

  ns="${SOLO_NAMESPACE}"
  svc="${EXPLORER_INGRESS_SERVICE_NAME}"
  if ! kubectl -n "${ns}" get svc "${svc}" >/dev/null 2>&1; then
    echo "Explorer service not found: ${ns}/${svc}" >&2
    return 1
  fi

  ACTIVE_INGRESS_NAMESPACE="${ns}"
  ACTIVE_INGRESS_SERVICE_NAME="${svc}"
  ACTIVE_INGRESS_REMOTE_PORT="80"

  kill_processes_on_local_port "${EXPLORER_INGRESS_LOCAL_PORT}"
  nohup kubectl -n "${ns}" port-forward "svc/${svc}" "${EXPLORER_INGRESS_LOCAL_PORT}:80" >/dev/null 2>&1 < /dev/null &
  EXPLORER_INGRESS_PORT_FORWARD_PID="$!"
  disown "${EXPLORER_INGRESS_PORT_FORWARD_PID}" 2>/dev/null || true
  sleep 2
  if wait_for_tcp_open "127.0.0.1" "${EXPLORER_INGRESS_LOCAL_PORT}" 20 1; then
    echo "Explorer UI port-forward established: http://127.0.0.1:${EXPLORER_INGRESS_LOCAL_PORT} -> ${ns}/${svc}:80"
    return 0
  fi
  echo "Failed to establish explorer UI port-forward on localhost:${EXPLORER_INGRESS_LOCAL_PORT}" >&2
  return 1
}

ensure_solo_service_monitor_for_prometheus() {
  local attempt=1
  local max_attempts=20

  while (( attempt <= max_attempts )); do
    if kubectl -n "${SOLO_NAMESPACE}" get servicemonitor solo-service-monitor >/dev/null 2>&1; then
      break
    fi
    sleep 3
    ((attempt++))
  done

  if (( attempt > max_attempts )); then
    echo "WARNING: solo-service-monitor not found in namespace ${SOLO_NAMESPACE}; consensus metrics may be missing in Grafana." >&2
    return 0
  fi

  if ! kubectl -n "${SOLO_NAMESPACE}" label servicemonitor solo-service-monitor release=kube-prometheus-stack --overwrite >/dev/null 2>&1; then
    echo "WARNING: Failed to add release label to solo-service-monitor." >&2
    return 0
  fi

  if ! kubectl -n "${SOLO_NAMESPACE}" patch servicemonitor solo-service-monitor --type merge -p \
    '{"spec":{"selector":{"matchLabels":{"solo.hedera.com/type":"network-node-svc"}}}}' \
    >/dev/null 2>&1; then
    echo "WARNING: Failed to patch solo-service-monitor selector for network-node metrics." >&2
    return 0
  fi
}

start_port_forward_watchdog() {
  local grafana_service="${ACTIVE_GRAFANA_SERVICE_NAME:-${GRAFANA_SERVICE_NAME}}"
  local ingress_ns="${ACTIVE_INGRESS_NAMESPACE}"
  local ingress_svc="${ACTIVE_INGRESS_SERVICE_NAME}"
  local ingress_remote_port="${ACTIVE_INGRESS_REMOTE_PORT}"

  cat > "${PORT_FORWARD_WATCHDOG_SCRIPT}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
while true; do
  if ! curl -sf "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1" >/dev/null 2>&1; then
    pkill -f "port-forward svc/${MIRROR_REST_SERVICE} .*${MIRROR_REST_LOCAL_PORT}:http" >/dev/null 2>&1 || true
    nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${MIRROR_REST_SERVICE}" "${MIRROR_REST_LOCAL_PORT}:http" >/dev/null 2>&1 < /dev/null &
  fi

  if command -v nc >/dev/null 2>&1; then
    nc -z "127.0.0.1" "${CN_GRPC_LOCAL_PORT}" >/dev/null 2>&1 || {
      pkill -f "port-forward svc/haproxy-node1-svc .*${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >/dev/null 2>&1 || true
      nohup kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >/dev/null 2>&1 < /dev/null &
    }
  else
    (: </dev/tcp/127.0.0.1/${CN_GRPC_LOCAL_PORT}) >/dev/null 2>&1 || {
      pkill -f "port-forward svc/haproxy-node1-svc .*${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >/dev/null 2>&1 || true
      nohup kubectl -n "${SOLO_NAMESPACE}" port-forward svc/haproxy-node1-svc "${CN_GRPC_LOCAL_PORT}:non-tls-grpc-client-port" >/dev/null 2>&1 < /dev/null &
    }
  fi

  if ! curl -sf "http://127.0.0.1:${GRAFANA_LOCAL_PORT}/api/health" >/dev/null 2>&1; then
    pkill -f "port-forward svc/.*grafana .*${GRAFANA_LOCAL_PORT}:80" >/dev/null 2>&1 || true
    nohup kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" port-forward "svc/${grafana_service}" "${GRAFANA_LOCAL_PORT}:80" >/dev/null 2>&1 < /dev/null &
  fi

  if [[ -n "${ingress_svc}" ]]; then
    if command -v nc >/dev/null 2>&1; then
      nc -z "127.0.0.1" "${EXPLORER_INGRESS_LOCAL_PORT}" >/dev/null 2>&1 || {
        pkill -f "port-forward svc/${ingress_svc} .*${EXPLORER_INGRESS_LOCAL_PORT}:80" >/dev/null 2>&1 || true
        nohup kubectl -n "${ingress_ns}" port-forward "svc/${ingress_svc}" "${EXPLORER_INGRESS_LOCAL_PORT}:${ingress_remote_port}" >/dev/null 2>&1 < /dev/null &
      }
    else
      (: </dev/tcp/127.0.0.1/${EXPLORER_INGRESS_LOCAL_PORT}) >/dev/null 2>&1 || {
        pkill -f "port-forward svc/${ingress_svc} .*${EXPLORER_INGRESS_LOCAL_PORT}:80" >/dev/null 2>&1 || true
        nohup kubectl -n "${ingress_ns}" port-forward "svc/${ingress_svc}" "${EXPLORER_INGRESS_LOCAL_PORT}:${ingress_remote_port}" >/dev/null 2>&1 < /dev/null &
      }
    fi
  fi

  sleep 10
done
EOF
  chmod +x "${PORT_FORWARD_WATCHDOG_SCRIPT}"
  nohup bash "${PORT_FORWARD_WATCHDOG_SCRIPT}" >"${PORT_FORWARD_WATCHDOG_LOG}" 2>&1 < /dev/null &
  PORT_FORWARD_WATCHDOG_PID="$!"
}

start_post_run_keepalive() {
  if [[ "${KEEP_NETWORK}" != "true" ]]; then
    return 0
  fi

  if [[ "${KEEP_PORT_FORWARD_WATCHDOG}" == "true" ]]; then
    start_port_forward_watchdog
    echo "Started post-run port-forward watchdog (pid=${PORT_FORWARD_WATCHDOG_PID}, log=${PORT_FORWARD_WATCHDOG_LOG})"
  fi
}

discover_prometheus_service_name() {
  local svc=""

  if kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get svc kube-prometheus-stack-prometheus >/dev/null 2>&1; then
    printf '%s\n' "kube-prometheus-stack-prometheus"
    return 0
  fi

  svc="$(kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get svc -o json 2>/dev/null | jq -r '
    .items[].metadata.name
    | select(test("prometheus"; "i"))
    | select(test("alertmanager|operator|node-exporter|kube-state-metrics|grafana|pushgateway"; "i") | not)
  ' | head -n 1)"
  [[ -n "${svc}" ]] || return 1
  printf '%s\n' "${svc}"
}

discover_prometheus_service_port() {
  local svc="$1"
  local port=""
  port="$(kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get svc "${svc}" -o json 2>/dev/null | jq -r '
    first(.spec.ports[] | select(.port == 9090) | .port // empty)
  ')"
  if [[ -z "${port}" || "${port}" == "null" ]]; then
    port="$(kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get svc "${svc}" -o json 2>/dev/null | jq -r '
      first(.spec.ports[] | select((.name // "") | test("http|web"; "i")) | .port // empty)
    ')"
  fi
  if [[ -z "${port}" || "${port}" == "null" ]]; then
    port="$(kubectl -n "${SOLO_CLUSTER_SETUP_NAMESPACE}" get svc "${svc}" -o json 2>/dev/null | jq -r '.spec.ports[0].port // empty')"
  fi
  [[ -n "${port}" && "${port}" != "null" ]] || return 1
  printf '%s\n' "${port}"
}

print_end_of_run_diagnostics() {
  local grafana_health_json=""
  local grafana_status="unreachable"
  local ingress_local_status="down"
  local ingress_target_ns=""
  local ingress_target_svc=""
  local ingress_target_port=""
  local prometheus_svc=""
  local prometheus_port=""
  local targets_json=""
  local active_targets=""
  local up_targets=""
  local down_targets=""
  local dropped_targets=""
  local down_target_lines=""
  local prom_sum_up=""
  local prom_count_up=""

  echo
  echo "-------------------- End-of-run diagnostics --------------------"

  if grafana_health_json="$(curl -sf "http://127.0.0.1:${GRAFANA_LOCAL_PORT}/api/health" 2>/dev/null)"; then
    grafana_status="$(echo "${grafana_health_json}" | jq -r '.database // "ok"')"
    echo "Grafana: reachable on http://127.0.0.1:${GRAFANA_LOCAL_PORT} (database=${grafana_status})"
  else
    echo "Grafana: unreachable on http://127.0.0.1:${GRAFANA_LOCAL_PORT}"
  fi

  if wait_for_tcp_open "127.0.0.1" "${EXPLORER_INGRESS_LOCAL_PORT}" 1 1; then
    ingress_local_status="up"
  fi
  ingress_target_ns="${ACTIVE_INGRESS_NAMESPACE:-unknown}"
  ingress_target_svc="${ACTIVE_INGRESS_SERVICE_NAME:-unknown}"
  ingress_target_port="${ACTIVE_INGRESS_REMOTE_PORT:-unknown}"
  echo "Explorer UI tunnel: local=${EXPLORER_INGRESS_LOCAL_PORT} status=${ingress_local_status} target=${ingress_target_ns}/${ingress_target_svc}:${ingress_target_port}"

  if prometheus_svc="$(discover_prometheus_service_name)" && prometheus_port="$(discover_prometheus_service_port "${prometheus_svc}")"; then
    if targets_json="$(kubectl get --raw "/api/v1/namespaces/${SOLO_CLUSTER_SETUP_NAMESPACE}/services/http:${prometheus_svc}:${prometheus_port}/proxy/api/v1/targets" 2>/dev/null)"; then
      active_targets="$(echo "${targets_json}" | jq '[.data.activeTargets[]?] | length')"
      up_targets="$(echo "${targets_json}" | jq '[.data.activeTargets[]? | select(.health == "up")] | length')"
      down_targets="$(echo "${targets_json}" | jq '[.data.activeTargets[]? | select(.health != "up")] | length')"
      dropped_targets="$(echo "${targets_json}" | jq '[.data.droppedTargets[]?] | length')"
      echo "Prometheus targets: up=${up_targets} down=${down_targets} active=${active_targets} dropped=${dropped_targets} (svc=${SOLO_CLUSTER_SETUP_NAMESPACE}/${prometheus_svc}:${prometheus_port})"
      if [[ "${down_targets}" != "0" ]]; then
        down_target_lines="$(echo "${targets_json}" | jq -r '
          [.data.activeTargets[]? | select(.health != "up")]
          | .[0:8]
          | .[]
          | "  - job=\(.labels.job // "unknown") instance=\(.labels.instance // .discoveredLabels.__address__ // "unknown") state=\(.health // "unknown") lastError=\((.lastError // "none") | tostring)"
        ')"
        if [[ -n "${down_target_lines}" ]]; then
          echo "Prometheus down targets (up to 8):"
          echo "${down_target_lines}"
        fi
      fi
      prom_sum_up="$(kubectl get --raw "/api/v1/namespaces/${SOLO_CLUSTER_SETUP_NAMESPACE}/services/http:${prometheus_svc}:${prometheus_port}/proxy/api/v1/query?query=sum(up)" 2>/dev/null | jq -r '.data.result[0].value[1] // "n/a"')"
      prom_count_up="$(kubectl get --raw "/api/v1/namespaces/${SOLO_CLUSTER_SETUP_NAMESPACE}/services/http:${prometheus_svc}:${prometheus_port}/proxy/api/v1/query?query=count(up)" 2>/dev/null | jq -r '.data.result[0].value[1] // "n/a"')"
      echo "Prometheus query check: sum(up)=${prom_sum_up}, count(up)=${prom_count_up}"
    else
      echo "Prometheus targets: query failed via service proxy (svc=${SOLO_CLUSTER_SETUP_NAMESPACE}/${prometheus_svc}:${prometheus_port})"
    fi
  else
    echo "Prometheus targets: service discovery failed in namespace ${SOLO_CLUSTER_SETUP_NAMESPACE}"
  fi

  echo "----------------------------------------------------------------"
}

write_sdk_verifier() {
  cat > "${NODE_SCRIPT}" <<'EOF'
const {
  Client,
  AccountCreateTransaction,
  Hbar,
  PrivateKey,
  Status,
  TransferTransaction,
} = require("@hashgraph/sdk");

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

// Submits a tiny self-transfer so CN finalises the previous record-stream
// file and uploads it to MinIO. Without this nudge the cryptoCreate above
// sits in an unfinished record block forever; mirror only sees a tx after
// the *next* tx arrives.
//
// We sleep for >1 logPeriod (hedera.recordStream.logPeriod = 1s in the deploy
// app props) before the flush so its consensus timestamp lands in the
// *next* record block — which is what forces the cryptoCreate's block to
// close and upload. An immediate flush would just land in the same block.
async function flushRecordStream(client, operatorAccountId) {
  await sleep(3000);
  const flush = new TransferTransaction()
    .addHbarTransfer(operatorAccountId, Hbar.fromTinybars(-1))
    .addHbarTransfer(operatorAccountId, Hbar.fromTinybars(1))
    .setMaxTransactionFee(new Hbar(1));
  const flushResp = await flush.execute(client);
  const flushReceipt = await flushResp.getReceipt(client);
  if (flushReceipt.status !== Status.Success) {
    throw new Error(`Flush tx returned non-success status: ${flushReceipt.status.toString()}`);
  }
}

async function main() {
  const grpcEndpoint = process.env.GRPC_ENDPOINT || "127.0.0.1:50211";
  const mirrorUrl = process.env.MIRROR_REST_URL || "http://127.0.0.1:5551";
  const mirrorAccountWaitMs = Number(process.env.MIRROR_ACCOUNT_WAIT_MS || "180000");
  const operatorAccountId = process.env.OPERATOR_ACCOUNT_ID || "0.0.2";
  const operatorPrivateKey = process.env.OPERATOR_PRIVATE_KEY;
  // Number of cryptoCreate txns to submit + verify. Defaults to 1 (legacy
  // single-tx behavior). Step 9 sets this higher so we prove the BN -> importer
  // hop serves several independent fresh blocks while MinIO (the RECORD source)
  // is scaled to zero — one lucky block could be a fluke, several cannot.
  const txCount = Number(process.env.VALIDATION_TX_COUNT || "1");
  if (!operatorPrivateKey) {
    throw new Error("OPERATOR_PRIVATE_KEY is required");
  }

  const client = Client.forNetwork({ [grpcEndpoint]: "0.0.3" });
  client.setOperator(operatorAccountId, PrivateKey.fromString(operatorPrivateKey));
  client.setMaxAttempts(1);
  client.setRequestTimeout(15000);

  const accountIds = [];
  for (let i = 1; i <= txCount; i++) {
    const tx = new AccountCreateTransaction()
      .setInitialBalance(new Hbar(1))
      .setKey(PrivateKey.generateED25519().publicKey)
      .setMaxTransactionFee(new Hbar(5));
    const response = await tx.execute(client);
    const receipt = await response.getReceipt(client);

    if (receipt.status !== Status.Success) {
      throw new Error(`tx ${i}/${txCount}: expected SUCCESS status but got ${receipt.status.toString()}`);
    }
    const accountId = receipt.accountId ? receipt.accountId.toString() : "";
    if (!accountId) {
      throw new Error(`tx ${i}/${txCount}: receipt did not include a new accountId`);
    }
    accountIds.push(accountId);
    console.log(`  submitted cryptoCreate ${i}/${txCount} -> ${accountId}`);

    // Flush after each create so its block closes and is streamed to the BN
    // (and would have been uploaded to MinIO, were MinIO up).
    await flushRecordStream(client, operatorAccountId);
  }

  for (const accountId of accountIds) {
    await ensureAccountVisibleInMirror(mirrorUrl, accountId, mirrorAccountWaitMs);
    console.log(`  mirror node sees account ${accountId}`);
  }
  console.log(`PASS: crypto create succeeded and mirror node sees account ${accountIds.join(", ")}`);
  await client.close();
}

main().catch((err) => {
  console.error(`FAIL: ${err.message}`);
  process.exit(1);
});
EOF
}

write_sdk_network_probe() {
  cat > "${NETWORK_PROBE_SCRIPT}" <<'EOF'
const { Client, AccountBalanceQuery, PrivateKey } = require("@hashgraph/sdk");

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

  const balance = await new AccountBalanceQuery().setAccountId(operatorAccountId).execute(client);
  console.log(`[sdk-probe] PASS endpoint=${grpcEndpoint} operator=${operatorAccountId} balance=${balance.hbars.toString()}`);
  await client.close();
}

main().catch((err) => {
  const details = err && err.stack ? err.stack : String(err);
  console.error(`[sdk-probe] FAIL endpoint=${process.env.GRPC_ENDPOINT || "127.0.0.1:50211"} details=${details}`);
  process.exit(1);
});
EOF
}

write_jumpstart_parser() {
  cat > "${JUMPSTART_PARSE_SCRIPT}" <<'EOF'
const fs = require("fs");

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

const file = process.argv[2];
if (!file) fail("Missing jumpstart.bin path argument");

let b;
try {
  b = fs.readFileSync(file);
} catch (e) {
  fail(`Unable to read jumpstart file '${file}': ${e.message}`);
}

// Layout:
//   [0..7]    blockNumber                (long, 8 bytes)
//   [8..55]   previousWrappedBlockHash   (SHA-384, 48 bytes)
//   [56..103] consensusTimestampHash     (SHA-384, 48 bytes)
//   [104..151] outputItemsTreeRootHash   (SHA-384, 48 bytes)
//   [152..159] streamingHasherLeafCount  (long, 8 bytes)
//   [160..163] streamingHasherHashCount  (int, 4 bytes)
//   [164..]   streamingHasher subtree hashes (48 bytes each)
const HEADER_SIZE = 164;
const HASH_BYTES = 48;

if (b.length < HEADER_SIZE) {
  fail(`jumpstart.bin too small: ${b.length} bytes (expected at least ${HEADER_SIZE})`);
}

const blockNum = b.readBigInt64BE(0);
const prevHash = b.subarray(8, 56).toString("hex");
const consensusTimestampHash = b.subarray(56, 104).toString("hex");
const outputItemsTreeRootHash = b.subarray(104, 152).toString("hex");
const leafCount = b.readBigInt64BE(152);
const hashCount = b.readInt32BE(160);
if (hashCount < 0) {
  fail(`Invalid negative hashCount ${hashCount}`);
}

const expected = HEADER_SIZE + (hashCount * HASH_BYTES);
if (b.length !== expected) {
  fail(`jumpstart.bin size mismatch: got ${b.length}, expected ${expected} (hashCount=${hashCount})`);
}

const subtreeHashes = [];
let offset = HEADER_SIZE;
for (let i = 0; i < hashCount; i += 1) {
  subtreeHashes.push(b.subarray(offset, offset + HASH_BYTES).toString("hex"));
  offset += HASH_BYTES;
}

console.log(`JUMPSTART_BLOCK_NUMBER=${blockNum.toString()}`);
console.log(`JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH=${prevHash}`);
console.log(`JUMPSTART_CONSENSUS_TIMESTAMP_HASH=${consensusTimestampHash}`);
console.log(`JUMPSTART_OUTPUT_ITEMS_TREE_ROOT_HASH=${outputItemsTreeRootHash}`);
console.log(`JUMPSTART_STREAMING_HASHER_LEAF_COUNT=${leafCount.toString()}`);
console.log(`JUMPSTART_STREAMING_HASHER_HASH_COUNT=${hashCount}`);
console.log(`JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES=${subtreeHashes.join(",")}`);
EOF
}

write_mirror_metadata_generator() {
  cat > "${MIRROR_METADATA_SCRIPT}" <<'EOF'
const fs = require("fs");
const path = require("path");

const FIRST_BLOCK_TIME = "2019-09-13T21:53:51.396440Z";

function fail(msg) {
  console.error(`FAIL: ${msg}`);
  process.exit(1);
}

function parseTimestampToEpochNanos(tsLike) {
  const ts = String(tsLike).replace(/_/g, ":");
  const m = ts.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/
  );
  if (!m) {
    throw new Error(`Invalid timestamp format: ${tsLike}`);
  }
  const [
    ,
    y,
    mo,
    d,
    h,
    mi,
    s,
    fracRaw = "",
  ] = m;
  const ms = Date.UTC(
    Number(y),
    Number(mo) - 1,
    Number(d),
    Number(h),
    Number(mi),
    Number(s)
  );
  const epochSeconds = BigInt(Math.floor(ms / 1000));
  const fracNanos = BigInt((fracRaw + "000000000").slice(0, 9));
  return (epochSeconds * 1_000_000_000n) + fracNanos;
}

function recordNameToEpochNanos(recordName) {
  const base = path.basename(String(recordName));
  const z = base.indexOf("Z");
  if (z < 0) {
    throw new Error(`Record file name does not include Z timestamp: ${recordName}`);
  }
  const ts = base.slice(0, z + 1);
  return parseTimestampToEpochNanos(ts);
}

function resolveNextUrl(base, next) {
  if (!next) {
    return "";
  }
  if (next.startsWith("http://") || next.startsWith("https://")) {
    return next;
  }
  if (next.startsWith("/")) {
    return `${base}${next}`;
  }
  return `${base}/${next}`;
}

async function fetchAllBlocksUpTo(mirrorBase, maxBlock) {
  const blocks = [];
  let nextUrl = `${mirrorBase}/api/v1/blocks?order=asc&limit=100`;
  while (nextUrl) {
    const response = await fetch(nextUrl);
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} from ${nextUrl}`);
    }
    const body = await response.json();
    const page = Array.isArray(body.blocks) ? body.blocks : [];
    if (page.length === 0) {
      break;
    }

    for (const b of page) {
      const n = Number(b.number);
      if (!Number.isFinite(n)) {
        continue;
      }
      if (n > maxBlock) {
        return blocks;
      }
      blocks.push({
        number: n,
        name: b.name || "",
        hash: String(b.hash || "").replace(/^0x/i, ""),
      });
    }

    const lastNumber = Number(page[page.length - 1].number);
    if (Number.isFinite(lastNumber) && lastNumber >= maxBlock) {
      break;
    }
    nextUrl = resolveNextUrl(mirrorBase, body.links && body.links.next);
  }
  return blocks;
}

function ensureNoBlockGaps(sortedBlocks) {
  if (sortedBlocks.length < 2) {
    return;
  }
  for (let i = 1; i < sortedBlocks.length; i += 1) {
    const expected = sortedBlocks[i - 1].number + 1;
    const actual = sortedBlocks[i].number;
    if (actual !== expected) {
      throw new Error(`Gap in mirror blocks: expected ${expected}, got ${actual}`);
    }
  }
}

function dayFromRecordName(recordName) {
  const base = path.basename(String(recordName));
  const z = base.indexOf("Z");
  if (z < 0) {
    throw new Error(`Record file name does not include Z timestamp: ${recordName}`);
  }
  const ts = base.slice(0, z + 1).replace(/_/g, ":");
  return ts.slice(0, 10);
}

async function main() {
  const mirrorBase = String(process.env.MIRROR_REST_URL || "http://127.0.0.1:5551").replace(/\/$/, "");
  const maxBlockRaw = process.env.MIRROR_BLOCK_NUMBER;
  const blockTimesFile = process.env.BLOCK_TIMES_FILE;
  const dayBlocksFile = process.env.DAY_BLOCKS_FILE;
  if (!maxBlockRaw) fail("MIRROR_BLOCK_NUMBER is required");
  if (!blockTimesFile) fail("BLOCK_TIMES_FILE is required");
  if (!dayBlocksFile) fail("DAY_BLOCKS_FILE is required");

  const maxBlock = Number(maxBlockRaw);
  if (!Number.isInteger(maxBlock) || maxBlock < 0) {
    fail(`Invalid MIRROR_BLOCK_NUMBER: ${maxBlockRaw}`);
  }

  const blocks = await fetchAllBlocksUpTo(mirrorBase, maxBlock);
  if (blocks.length === 0) {
    fail("Mirror returned no blocks for metadata generation");
  }
  blocks.sort((a, b) => a.number - b.number);
  ensureNoBlockGaps(blocks);
  const highest = blocks[blocks.length - 1].number;
  if (highest < maxBlock) {
    fail(`Mirror highest fetched block ${highest} is below requested ${maxBlock}`);
  }

  const firstEpochNanos = parseTimestampToEpochNanos(FIRST_BLOCK_TIME);
  const buf = Buffer.alloc((maxBlock + 1) * 8);
  const byDay = new Map();

  for (const b of blocks) {
    const epochNanos = recordNameToEpochNanos(b.name);
    const blockTime = epochNanos - firstEpochNanos;
    if (blockTime < 0n) {
      fail(`Negative block time for block ${b.number} (${b.name})`);
    }
    buf.writeBigInt64BE(blockTime, b.number * 8);

    const day = dayFromRecordName(b.name);
    const [year, month, dayNum] = day.split("-").map(Number);
    const prev = byDay.get(day);
    if (!prev) {
      byDay.set(day, {
        year,
        month,
        day: dayNum,
        firstBlockNumber: b.number,
        firstBlockHash: b.hash,
        lastBlockNumber: b.number,
        lastBlockHash: b.hash,
      });
    } else {
      prev.lastBlockNumber = b.number;
      prev.lastBlockHash = b.hash;
    }
  }

  fs.mkdirSync(path.dirname(blockTimesFile), { recursive: true });
  fs.mkdirSync(path.dirname(dayBlocksFile), { recursive: true });
  fs.writeFileSync(blockTimesFile, buf);

  const dayBlocks = Array.from(byDay.values()).sort((a, b) => {
    if (a.year !== b.year) return a.year - b.year;
    if (a.month !== b.month) return a.month - b.month;
    return a.day - b.day;
  });
  fs.writeFileSync(dayBlocksFile, `${JSON.stringify(dayBlocks, null, 2)}\n`);

  console.log(
    `PASS: generated ${blockTimesFile} (${maxBlock + 1} entries) and ${dayBlocksFile} (${dayBlocks.length} day entries)`
  );
}

main().catch((err) => {
  console.error(`FAIL: ${err.message}`);
  process.exit(1);
});
EOF
}

generate_block_node_metadata_from_mirror() {
  local max_block="$1"
  write_mirror_metadata_generator

  export MIRROR_BLOCK_NUMBER="${max_block}"
  export BLOCK_TIMES_FILE
  export DAY_BLOCKS_FILE
  export MIRROR_REST_URL

  if ! node "${MIRROR_METADATA_SCRIPT}" >/dev/null; then
    echo "Mirror metadata generation failed (stderr shown above)" >&2
    return 1
  fi
}

prepare_wrap_day_archives_from_record_streams() {
  local account_dir account_id src base ts day
  local out_dir out_file stem stem_no_ext
  local primary_records=0
  local other_records=0
  local sig_files=0
  local tar_count=0

  rm -rf "${WRAP_DAYS_SRC_DIR}" "${WRAP_COMPRESSED_DAYS_DIR}" >/dev/null 2>&1 || true
  mkdir -p "${WRAP_DAYS_SRC_DIR}" "${WRAP_COMPRESSED_DAYS_DIR}"

  shopt -s nullglob
  for account_dir in "${RECORD_STREAMS_DIR}"/record0.0.*; do
    [[ -d "${account_dir}" ]] || continue
    account_id="${account_dir##*/record}"
    for src in "${account_dir}"/*; do
      [[ -f "${src}" ]] || continue
      base="$(basename "${src}")"
      [[ "${base}" == *Z* ]] || continue
      ts="${base%%Z*}Z"
      day="${ts%%T*}"
      out_dir="${WRAP_DAYS_SRC_DIR}/${day}/${ts}"
      mkdir -p "${out_dir}"

      case "${base}" in
        *.rcd.gz)
          stem="${base%.gz}"
          stem_no_ext="${stem%.rcd}"
          if [[ "${stem_no_ext}" == "${ts}" && "${account_id}" == "0.0.3" && ! -f "${out_dir}/${ts}.rcd" ]]; then
            gzip -dc "${src}" > "${out_dir}/${ts}.rcd"
            primary_records=$((primary_records + 1))
          else
            out_file="${out_dir}/${stem_no_ext}_node_${account_id}.rcd"
            gzip -dc "${src}" > "${out_file}"
            other_records=$((other_records + 1))
          fi
          ;;
        *.rcd)
          stem_no_ext="${base%.rcd}"
          if [[ "${stem_no_ext}" == "${ts}" && "${account_id}" == "0.0.3" && ! -f "${out_dir}/${ts}.rcd" ]]; then
            cp -f "${src}" "${out_dir}/${ts}.rcd"
            primary_records=$((primary_records + 1))
          else
            cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcd"
            other_records=$((other_records + 1))
          fi
          ;;
        *.rcd_sig)
          stem_no_ext="${base%.rcd_sig}"
          cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcd_sig"
          sig_files=$((sig_files + 1))
          ;;
        *.rcs_sig)
          stem_no_ext="${base%.rcs_sig}"
          cp -f "${src}" "${out_dir}/${stem_no_ext}_node_${account_id}.rcs_sig"
          sig_files=$((sig_files + 1))
          ;;
      esac
    done
  done
  shopt -u nullglob

  if (( primary_records == 0 )); then
    echo "No primary record files prepared for wrap input under ${WRAP_DAYS_SRC_DIR}" >&2
    return 1
  fi
  if (( sig_files == 0 )); then
    echo "No signature files prepared for wrap input under ${WRAP_DAYS_SRC_DIR}" >&2
    return 1
  fi

  if ! run_step "Building wrap input archives (gradle :tools:run days compress)" \
      bash -c "cd '${BLOCK_NODE_REPO_PATH}' && ./gradlew :tools:run --args='days compress -o ${WRAP_COMPRESSED_DAYS_DIR} ${WRAP_DAYS_SRC_DIR}'"; then
    echo "Failed to build .tar.zstd wrap input archives" >&2
    return 1
  fi

  tar_count="$(find "${WRAP_COMPRESSED_DAYS_DIR}" -type f -name '*.tar.zstd' | wc -l | tr -d ' ')"
  if [[ "${tar_count}" == "0" ]]; then
    echo "days compress produced no .tar.zstd files under ${WRAP_COMPRESSED_DAYS_DIR}" >&2
    return 1
  fi
}

run_block_node_wrap_tool() {
  local records_dir="$1"
  local wrapped_dir="$2"
  local wrap_args jumpstart_file

  if [[ "${USE_BLOCK_NODE_JUMPSTART}" != "true" ]]; then
    log "USE_BLOCK_NODE_JUMPSTART=false; skipping Block Node wrap tool and using configured jumpstart env values"
    return 0
  fi

  if ! validate_block_node_repo; then
    return 1
  fi
  if [[ ! -d "${records_dir}" ]]; then
    echo "recordStreams directory not found: ${records_dir}" >&2
    return 1
  fi
  if ! ensure_zstd_command_for_block_node; then
    echo "Failed to provide a working zstd command for Block Node wrapping." >&2
    return 1
  fi

  mkdir -p "${wrapped_dir}"
  wrap_args="blocks wrap -i ${records_dir} -o ${wrapped_dir} --blocktimes-file ${BLOCK_TIMES_FILE} --day-blocks ${DAY_BLOCKS_FILE}"
  if [[ -n "${BLOCKS_WRAP_EXTRA_ARGS}" ]]; then
    wrap_args="${wrap_args} ${BLOCKS_WRAP_EXTRA_ARGS}"
  fi

  if ! run_step "Running block-node wrap tool (gradle :tools:run blocks wrap)" \
      bash -c "cd '${BLOCK_NODE_REPO_PATH}' && ./gradlew :tools:run --args='${wrap_args}'"; then
    echo "Block Node wrap command failed" >&2
    return 1
  fi

  if [[ -n "${JUMPSTART_BIN_PATH}" ]]; then
    jumpstart_file="${JUMPSTART_BIN_PATH}"
  else
    jumpstart_file="$(find "${wrapped_dir}" -type f -name "jumpstart.bin" | head -n 1)"
  fi
  if [[ -z "${jumpstart_file}" || ! -f "${jumpstart_file}" ]]; then
    echo "jumpstart.bin not found under ${wrapped_dir}. Override with JUMPSTART_BIN_PATH." >&2
    return 1
  fi

  export JUMPSTART_BIN_PATH="${jumpstart_file}"
}

load_jumpstart_env_from_bin() {
  local jumpstart_file="$1"
  local k v

  [[ -f "${jumpstart_file}" ]] || { echo "jumpstart.bin not found: ${jumpstart_file}" >&2; return 1; }
  write_jumpstart_parser

  while IFS='=' read -r k v; do
    case "${k}" in
      JUMPSTART_BLOCK_NUMBER) JUMPSTART_BLOCK_NUMBER="${v}" ;;
      JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH) JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH="${v}" ;;
      JUMPSTART_STREAMING_HASHER_LEAF_COUNT) JUMPSTART_STREAMING_HASHER_LEAF_COUNT="${v}" ;;
      JUMPSTART_STREAMING_HASHER_HASH_COUNT) JUMPSTART_STREAMING_HASHER_HASH_COUNT="${v}" ;;
      JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES) JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES="${v}" ;;
    esac
  done < <(node "${JUMPSTART_PARSE_SCRIPT}" "${jumpstart_file}")

  [[ -n "${JUMPSTART_BLOCK_NUMBER}" ]] || { echo "Failed to parse JUMPSTART_BLOCK_NUMBER from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}" ]] || { echo "Failed to parse previous hash from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}" ]] || { echo "Failed to parse leaf count from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_STREAMING_HASHER_HASH_COUNT}" ]] || { echo "Failed to parse hash count from ${jumpstart_file}" >&2; return 1; }
  [[ -n "${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES}" || "${JUMPSTART_STREAMING_HASHER_HASH_COUNT}" == "0" ]] || {
    echo "Failed to parse subtree hashes from ${jumpstart_file}" >&2
    return 1
  }

  export JUMPSTART_BLOCK_NUMBER
  export JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH
  export JUMPSTART_STREAMING_HASHER_LEAF_COUNT
  export JUMPSTART_STREAMING_HASHER_HASH_COUNT
  export JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES

}

# Migration vote values parsed from hgcaa.log on the consensus pods (Step 7 validation).
MIGRATION_BLOCK_NUMBER=""
MIGRATION_PREV_HASH=""
MIGRATION_INTERMEDIATE_HASHES=""
MIGRATION_LEAF_COUNT=""

normalize_hash_list() {
  local input="$1"
  echo "${input}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]' | sed 's/^,//; s/,$//; s/,,*/,/g'
}

parse_migration_vote_from_hgcaa() {
  local node pod line="" queued_line="" vote_pod=""
  local attempt=1 max_attempts=36
  local nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"

  while (( attempt <= max_attempts )); do
    for node in "${nodes[@]}"; do
      pod="network-${node}-0"
      line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${pod}" -c root-container -- sh -lc \
        "awk '/Finalized migration root hash vote values:/{last=\$0} END{if (last) print last}' /opt/hgcapp/services-hedera/HapiApp2.0/output/hgcaa.log 2>/dev/null" || true)"
      if [[ -n "${line}" ]]; then
        vote_pod="${pod}"
        break
      fi
    done
    if [[ -n "${line}" ]]; then
      break
    fi
    sleep 5
    ((attempt++))
  done

  [[ -n "${line}" ]] || {
    echo "Could not find migration vote finalization log line in hgcaa.log within $((max_attempts * 5))s" >&2
    return 1
  }

  if [[ "${line}" =~ Block[[:space:]]+([0-9]+)[[:space:]]+previousWrappedRecordBlockRootHash=([0-9a-fA-F]+),[[:space:]]*wrappedIntermediatePreviousBlockRootHashes=\[([^]]*)\],[[:space:]]*wrappedIntermediateBlockRootsLeafCount=([0-9]+) ]]; then
    MIGRATION_BLOCK_NUMBER="${BASH_REMATCH[1]}"
    MIGRATION_PREV_HASH="${BASH_REMATCH[2]}"
    MIGRATION_INTERMEDIATE_HASHES="${BASH_REMATCH[3]}"
    MIGRATION_LEAF_COUNT="${BASH_REMATCH[4]}"
  elif [[ "${line}" =~ previousWrappedRecordBlockRootHash=([0-9a-fA-F]+),[[:space:]]*wrappedIntermediatePreviousBlockRootHashes=\[([^]]*)\],[[:space:]]*wrappedIntermediateBlockRootsLeafCount=([0-9]+) ]]; then
    MIGRATION_PREV_HASH="${BASH_REMATCH[1]}"
    MIGRATION_INTERMEDIATE_HASHES="${BASH_REMATCH[2]}"
    MIGRATION_LEAF_COUNT="${BASH_REMATCH[3]}"
    queued_line="$(kubectl -n "${SOLO_NAMESPACE}" exec "${vote_pod}" -c root-container -- sh -lc \
      "awk '/Applied queued hash for block[0-9]+:/{last=\$0} END{if (last) print last}' /opt/hgcapp/services-hedera/HapiApp2.0/output/hgcaa.log 2>/dev/null" || true)"
    if [[ "${queued_line}" =~ block([0-9]+): ]]; then
      MIGRATION_BLOCK_NUMBER="${BASH_REMATCH[1]}"
    else
      MIGRATION_BLOCK_NUMBER="${JUMPSTART_BLOCK_NUMBER}"
    fi
  else
    echo "Migration vote line did not match expected format: ${line}" >&2
    return 1
  fi
  MIGRATION_PREV_HASH="$(echo "${MIGRATION_PREV_HASH}" | tr '[:upper:]' '[:lower:]')"
  MIGRATION_INTERMEDIATE_HASHES="$(normalize_hash_list "${MIGRATION_INTERMEDIATE_HASHES}")"
  echo "Parsed migration vote values: block=${MIGRATION_BLOCK_NUMBER}, leafCount=${MIGRATION_LEAF_COUNT}"
}

# Re-run the offline wrap from records 0..to_block and load the resulting
# jumpstart.bin into JUMPSTART_* env (overwriting the Step-5 input values).
run_replay_wrap_to_075() {
  local mirror_base="$1"
  local to_block="$2"
  local prev_bin_path="${JUMPSTART_BIN_PATH}"

  rm -rf "${RECORD_STREAMS_DIR}" "${REPLAY_WRAPPED_BLOCKS_DIR}" >/dev/null 2>&1 || true
  mkdir -p "${RECORD_STREAMS_DIR}" "${REPLAY_WRAPPED_BLOCKS_DIR}"

  download_solo_minio_record_streams "${to_block}" "${mirror_base}" || return 1
  generate_block_node_metadata_from_mirror "${to_block}" || return 1
  prepare_wrap_day_archives_from_record_streams || return 1

  # Force wrap tool to re-discover jumpstart.bin under the replay output directory.
  unset JUMPSTART_BIN_PATH
  if ! run_block_node_wrap_tool "${WRAP_COMPRESSED_DAYS_DIR}" "${REPLAY_WRAPPED_BLOCKS_DIR}"; then
    export JUMPSTART_BIN_PATH="${prev_bin_path}"
    return 1
  fi
  load_jumpstart_env_from_bin "${JUMPSTART_BIN_PATH}"
}

compare_replay_to_migration_vote() {
  local replay_prev replay_leaf replay_hashes
  local mismatch=0
  replay_prev="$(echo "${JUMPSTART_PREV_WRAPPED_RECORD_BLOCK_HASH}" | tr '[:upper:]' '[:lower:]')"
  replay_leaf="${JUMPSTART_STREAMING_HASHER_LEAF_COUNT}"
  replay_hashes="$(normalize_hash_list "${JUMPSTART_STREAMING_HASHER_SUBTREE_HASHES}")"

  mkdir -p "$(dirname "${MIGRATION_COMPARE_LOG}")"
  {
    echo "migration.block=${MIGRATION_BLOCK_NUMBER}"
    echo "migration.prevHash=${MIGRATION_PREV_HASH}"
    echo "migration.intermediateHashes=${MIGRATION_INTERMEDIATE_HASHES}"
    echo "migration.leafCount=${MIGRATION_LEAF_COUNT}"
    echo "replay.block=${JUMPSTART_BLOCK_NUMBER}"
    echo "replay.prevHash=${replay_prev}"
    echo "replay.intermediateHashes=${replay_hashes}"
    echo "replay.leafCount=${replay_leaf}"
  } > "${MIGRATION_COMPARE_LOG}"

  echo "--------------------------------------------------------------------"
  echo "Migration vs Replay Comparison"
  echo "  blockNumber:"
  echo "    migration = ${MIGRATION_BLOCK_NUMBER}"
  echo "    replay    = ${JUMPSTART_BLOCK_NUMBER}"
  echo "  previousWrappedRecordBlockRootHash:"
  echo "    migration = ${MIGRATION_PREV_HASH}"
  echo "    replay    = ${replay_prev}"
  echo "  wrappedIntermediateBlockRootsLeafCount:"
  echo "    migration = ${MIGRATION_LEAF_COUNT}"
  echo "    replay    = ${replay_leaf}"
  echo "  wrappedIntermediatePreviousBlockRootHashes:"
  echo "    migration = [${MIGRATION_INTERMEDIATE_HASHES}]"
  echo "    replay    = [${replay_hashes}]"

  if [[ "${MIGRATION_PREV_HASH}" != "${replay_prev}" ]]; then
    mismatch=1
    echo "  mismatch: previousWrappedRecordBlockRootHash differs"
  fi
  if [[ "${MIGRATION_INTERMEDIATE_HASHES}" != "${replay_hashes}" ]]; then
    mismatch=1
    echo "  mismatch: wrappedIntermediatePreviousBlockRootHashes differ"
  fi
  if [[ "${MIGRATION_LEAF_COUNT}" != "${replay_leaf}" ]]; then
    mismatch=1
    echo "  mismatch: wrappedIntermediateBlockRootsLeafCount differs"
  fi

  if (( mismatch == 0 )); then
    echo "  result: MATCH"
  else
    echo "  result: MISMATCH"
  fi
  echo "--------------------------------------------------------------------"
  echo "Comparison log: ${MIGRATION_COMPARE_LOG}"

  (( mismatch == 0 ))
}

prepare_js_sdk_runtime() {
  write_sdk_verifier
  write_sdk_network_probe
  cd "${WORK_DIR}"
  npm init -y >/dev/null 2>&1
  npm install --no-fund --no-audit @hashgraph/sdk >/dev/null 2>&1

  export GRPC_ENDPOINT="127.0.0.1:${CN_GRPC_LOCAL_PORT}"
  export MIRROR_REST_URL="http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
  export OPERATOR_ACCOUNT_ID
  export OPERATOR_PRIVATE_KEY
}

write_mirror_node_values_override() {
  cat > "${MIRROR_NODE_VALUES_FILE}" <<EOF
restjava:
  resources:
    requests:
      memory: ${MIRROR_RESTJAVA_MEMORY_REQUEST}
    limits:
      memory: ${MIRROR_RESTJAVA_MEMORY_LIMIT}
importer:
  resources:
    requests:
      memory: ${MIRROR_IMPORTER_MEMORY_REQUEST}
    limits:
      memory: ${MIRROR_IMPORTER_MEMORY_LIMIT}
EOF
}

deploy_mirror_node_for_cutover() {
  local ec=0
  write_mirror_node_values_override
  if run_step "Deploying mirror node" \
    solo mirror node add \
    --deployment "${SOLO_DEPLOYMENT}" \
    --mirror-node-version "${MIRROR_NODE_VERSION}" \
    --enable-ingress \
    --force-port-forward false \
    --values-file "${MIRROR_NODE_VALUES_FILE}"; then
    return 0
  fi
  ec=$?

  if ! mirror_node_failed_only_on_restjava; then
    return "${ec}"
  fi

  log "Mirror node add failed only on REST Java readiness; waiting for required mirror services"
  wait_for_required_mirror_services_ready 600 || return "${ec}"
  log "Required mirror services are ready; continuing without REST Java"
}

build_default_block_node_priority_mapping() {
  local node mapping=""
  local nodes=()
  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    [[ -n "${mapping}" ]] && mapping+=","
    mapping+="${node}=1"
  done
  echo "${mapping}"
}

# Builds the BN RSA bootstrap roster file (PBJ JSON of NodeAddressBook) by
# extracting the X.509 SubjectPublicKeyInfo from each consensus node's gossip
# certificate (s-public-node{N}.pem) and hex-encoding the DER bytes.
#
# Why this exists: the v0.34.0-rc1 BN chart's plugins.names list does NOT
# include `roster-bootstrap-rsa`, and `org.hiero.block-node:roster-bootstrap-rsa:0.34.0-rc1`
# is not published to Maven Central, so the Maven init container never resolves
# the RsaRosterBootstrapPlugin jar and BN boots without it. With no plugin to
# fetch the address book from the Mirror Node, BN falls back to reading
# `app.state.rsaBootstrapFilePath` directly in BlockNodeApp.loadApplicationState()
# (BEFORE plugin init). Pre-seeding that file is the only way to populate the
# address book for the WRB verifier in this rc.
#
# Using the local mirror REST `/api/v1/network/nodes` is not viable here either:
# the running mirror returns 404 for that endpoint until the importer ingests an
# AddressBookUpdate event, which doesn't happen on a fresh local solo deploy.
# CN's gossip cert files are the authoritative source.
#
# Format consumed by RsaKeyDecoder.buildKeyMap (block-node/verification):
#   rsaPubKey = hex of DER X.509 SubjectPublicKeyInfo (no 0x prefix)
# PBJ JSON shape (verified empirically against NodeAddressBook.JSON.toBytes):
#   { "nodeAddress": [ {"RSAPubKey": "..."}, {"nodeId": "1", "RSAPubKey": "..."}, ... ] }
# Note: nodeId is a STRING and is omitted when 0 (proto default).
generate_rsa_bootstrap_roster_json() {
  require_cmd openssl
  require_cmd xxd
  local node node_idx node_id pem hex
  local nodes=()
  local cn_pod="network-node1-0"
  local entries=""

  IFS=',' read -r -a nodes <<< "${NODE_ALIASES}"
  for node in "${nodes[@]}"; do
    node_idx="${node#node}"             # node3 -> 3 (PEM filename suffix is 1-based)
    node_id="$((node_idx - 1))"         # JSON nodeId is 0-based
    pem="$(kubectl -n "${SOLO_NAMESPACE}" exec "${cn_pod}" -c root-container -- \
      cat "/opt/hgcapp/services-hedera/HapiApp2.0/data/keys/s-public-node${node_idx}.pem" 2>/dev/null || true)"
    if [[ -z "${pem}" ]]; then
      echo "Failed to read s-public-node${node_idx}.pem from ${cn_pod}" >&2
      return 1
    fi
    hex="$(printf '%s' "${pem}" \
      | openssl x509 -pubkey -noout 2>/dev/null \
      | openssl pkey -pubin -outform DER 2>/dev/null \
      | xxd -p | tr -d '\n')"
    if [[ -z "${hex}" ]]; then
      echo "Failed to extract X.509 SPKI hex for node${node_idx}" >&2
      return 1
    fi
    [[ -n "${entries}" ]] && entries+=","
    if [[ "${node_id}" == "0" ]]; then
      entries+=$'\n    {"RSAPubKey": "'"${hex}"'"}'
    else
      entries+=$'\n    {"nodeId": "'"${node_id}"'", "RSAPubKey": "'"${hex}"'"}'
    fi
  done

  cat > "${RSA_BOOTSTRAP_ROSTER_FILE}" <<EOF
{
  "nodeAddress": [${entries}
  ]
}
EOF
  echo "Generated RSA bootstrap roster (${#nodes[@]} entries): ${RSA_BOOTSTRAP_ROSTER_FILE}"
}

# Writes a helm values file that sets:
#   * BLOCK_NODE_EARLIEST_MANAGED_BLOCK → NodeConfig.earliestManagedBlock
#       (verification + persistence boundary; bootstraps chain hash from the
#        first incoming publisher footer instead of demanding ZERO_BLOCK_HASH)
#   * BACKFILL_START_BLOCK → BackfillConfiguration.startBlock
#       (backfill plugin floor; below this the BN doesn't try to backfill)
#   * APP_STATE_RSA_BOOTSTRAP_FILE_PATH → ApplicationStateConfig.rsaBootstrapFilePath
#       (relocated to the live-storage PVC so an init container can seed it
#        before BlockNodeApp.loadApplicationState() reads it on startup)
#   * blockNode.initContainers (overridden)
#       Preserves the chart-default init-storage-dirs step (Helm replaces lists
#       on values merge, so we must keep it verbatim) and appends a
#       seed-rsa-bootstrap-roster step that bakes the rsa-bootstrap-roster.json
#       content into /live-pvc/live-data/ via a quoted-delimiter heredoc. Both
#       containers mount the live-storage PVC at /live-pvc so writes survive
#       the BN container's own restart cycles (the container's writable layer
#       is volatile but the live-data PVC subpath is persistent).
#
# All keys under blockNode.config: are rendered into the chart ConfigMap
# (charts/block-node-server/templates/configmap.yaml) and envFrom'd into the
# pod (charts/block-node-server/templates/statefulset.yaml). Env-var naming
# follows AutomaticEnvironmentVariableConfigSource: dots->_, uppercased;
# camelCase → upper with `_` before each capital.
write_block_node_cutover_values() {
  local roster_indented
  # Indent the JSON body by 10 spaces so it lines up under the YAML `|` block
  # scalar of the init container's command. The chart's toYaml re-encoder
  # preserves multi-line string values; the heredoc terminator (ROSTER) appears
  # at column 0 in the *parsed* YAML string (after the block-scalar's common
  # indent prefix is stripped), which is exactly where bash needs it.
  roster_indented="$(sed 's/^/          /' "${RSA_BOOTSTRAP_ROSTER_FILE}")"

  cat > "${BLOCK_NODE_CUTOVER_VALUES_FILE}" <<EOF
blockNode:
  config:
    BLOCK_NODE_EARLIEST_MANAGED_BLOCK: "${BLOCK_NODE_CUTOVER_START_BLOCK}"
    BACKFILL_START_BLOCK: "${BLOCK_NODE_CUTOVER_START_BLOCK}"
    # Relocate the RSA bootstrap file to the live-data PVC subpath so our
    # seed-rsa-bootstrap-roster init container (below) can write it in.
    APP_STATE_RSA_BOOTSTRAP_FILE_PATH: "/opt/hiero/block-node/data/live/rsa-bootstrap-roster.json"
    # RSA roster bootstrap env vars (BN >= 0.34). Harmless in this rc because
    # the RsaRosterBootstrapPlugin jar isn't shipped (chart's plugins.names
    # doesn't list roster-bootstrap-rsa, and the artifact isn't on Maven Central
    # for v0.34.0-rc1). Left here so that any future rc shipping the plugin
    # picks them up automatically.
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_BASE_URL}"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_CONNECT_TIMEOUT_SECONDS}"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_READ_TIMEOUT_SECONDS}"
    ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE: "${ROSTER_BOOTSTRAP_RSA_MIRROR_NODE_PAGE_SIZE}"
  initContainers:
    # Verbatim copy of the chart-default init-storage-dirs (charts/block-node-server/values.yaml).
    # Helm replaces lists on values merge — if we don't preserve this here, the
    # BN container's writable PVC subpaths never get created/chowned and the
    # main process fails on its first write to /opt/hiero/block-node/data/live.
    - name: init-storage-dirs
      image: busybox
      command:
        - sh
        - -c
        - |
          mkdir -p /live-pvc/live-data && \\
          chown 2000:2000 /live-pvc/live-data && \\
          chmod 700 /live-pvc/live-data && \\
          mkdir -p /archive-pvc/archive-data && \\
          chown 2000:2000 /archive-pvc/archive-data && \\
          chmod 700 /archive-pvc/archive-data && \\
          chown 2000:2000 /verification-pvc && \\
          chmod 700 /verification-pvc
      volumeMounts:
        - name: live-storage
          mountPath: /live-pvc
        - name: archive-storage
          mountPath: /archive-pvc
        - name: verification-storage
          mountPath: /verification-pvc
    # Seeds the RSA address book file the BN reads at startup. Quoted-delimiter
    # heredoc (<<'ROSTER') prevents both shell- and YAML-side substitution of
    # the JSON payload. Runs after init-storage-dirs (which created live-data
    # with mode 700/uid 2000) so we can chmod 644 here for read access.
    - name: seed-rsa-bootstrap-roster
      image: busybox
      command:
        - sh
        - -c
        - |
          cat > /live-pvc/live-data/rsa-bootstrap-roster.json <<'ROSTER'
${roster_indented}
          ROSTER
          chown 2000:2000 /live-pvc/live-data/rsa-bootstrap-roster.json
          chmod 644 /live-pvc/live-data/rsa-bootstrap-roster.json
          echo "Seeded rsa-bootstrap-roster.json:"
          ls -la /live-pvc/live-data/rsa-bootstrap-roster.json
      volumeMounts:
        - name: live-storage
          mountPath: /live-pvc
EOF
}

deploy_block_node_for_cutover() {
  local add_args=(
    solo block node add
    --deployment "${SOLO_DEPLOYMENT}"
    --cluster-ref "${CLUSTER_REF}"
    --quiet-mode
    --force-port-forward false
  )
  [[ -z "${BLOCK_NODE_PRIORITY_MAPPING}" ]] && BLOCK_NODE_PRIORITY_MAPPING="$(build_default_block_node_priority_mapping)"
  add_args+=(--priority-mapping "${BLOCK_NODE_PRIORITY_MAPPING}")
  [[ -n "${BLOCK_NODE_CHART_DIR}" ]] && add_args+=(--block-node-chart-dir "${BLOCK_NODE_CHART_DIR}")
  [[ -n "${BLOCK_NODE_CHART_VERSION}" ]] && add_args+=(--chart-version "${BLOCK_NODE_CHART_VERSION}")
  [[ -n "${BLOCK_NODE_RELEASE_TAG}" ]] && add_args+=(--release-tag "${BLOCK_NODE_RELEASE_TAG}")
  [[ -n "${BLOCK_NODE_IMAGE_TAG}" ]] && add_args+=(--image-tag "${BLOCK_NODE_IMAGE_TAG}")

  # Default cutover start block to JUMPSTART_BLOCK_NUMBER+1000. JUMPSTART_BLOCK_NUMBER
  # is the boundary the wrap tool produced in Step 5 (carried into the 0.75 upgrade
  # via blockStream.jumpstart.blockNum). The +1000 margin keeps the BN's
  # earliestManagedBlock ABOVE CN's current block-stream block number, so BN's
  # catch-up path (streamBeforeEmbOrElse) snaps nextUnstreamed down to whatever
  # CN first publishes — instead of SEND_BEHIND-ing forever.
  # Allow user override via env.
  if [[ -z "${BLOCK_NODE_CUTOVER_START_BLOCK}" ]]; then
    if [[ -n "${JUMPSTART_BLOCK_NUMBER}" && "${JUMPSTART_BLOCK_NUMBER}" =~ ^[0-9]+$ ]]; then
      BLOCK_NODE_CUTOVER_START_BLOCK="$((JUMPSTART_BLOCK_NUMBER + 1000))"
    else
      echo "Cannot derive BLOCK_NODE_CUTOVER_START_BLOCK: JUMPSTART_BLOCK_NUMBER unset/invalid" >&2
      echo "Set BLOCK_NODE_CUTOVER_START_BLOCK explicitly, or run from Step 5 so it gets populated." >&2
      return 1
    fi
  fi
  generate_rsa_bootstrap_roster_json
  write_block_node_cutover_values
  echo "BLOCK_NODE_EARLIEST_MANAGED_BLOCK=${BLOCK_NODE_CUTOVER_START_BLOCK} and BACKFILL_START_BLOCK=${BLOCK_NODE_CUTOVER_START_BLOCK} (BN joins mid-chain at this block)"

  # Solo's --values-file accepts a comma-separated list; layer our cutover
  # values on top of any user-supplied BLOCK_NODE_VALUES_FILE so user overrides
  # can still win for non-cutover keys.
  local values_files="${BLOCK_NODE_CUTOVER_VALUES_FILE}"
  [[ -n "${BLOCK_NODE_VALUES_FILE}" ]] && values_files="${BLOCK_NODE_VALUES_FILE},${BLOCK_NODE_CUTOVER_VALUES_FILE}"
  add_args+=(--values-file "${values_files}")

  echo "Deploying Block Node ${BLOCK_NODE_ID} and routing consensus nodes with priority mapping '${BLOCK_NODE_PRIORITY_MAPPING}'"
  run_step "Deploying Block Node ${BLOCK_NODE_ID}" \
    "${add_args[@]}"

  # Override solo's 160Mi BN memory default + give the JVM a real heap (see BLOCK_NODE_MEMORY_* above).
  # Patch the deployed statefulset directly so we don't depend on helm/solo value precedence; this
  # rolls the fresh (block-less) BN pod once, before any blocks are streamed.
  # Heap goes in JAVA_OPTS (the BN's heap knob); JAVA_TOOL_OPTIONS is logging-only and putting -Xmx
  # there collides with the image's default -Xms16G (Xms>Xmx -> VM won't start).
  echo "Bumping Block Node memory (limit=${BLOCK_NODE_MEMORY_LIMIT}, request=${BLOCK_NODE_MEMORY_REQUEST}) and JVM heap (JAVA_OPTS='${BLOCK_NODE_HEAP_OPTS}')"
  kubectl -n "${SOLO_NAMESPACE}" set resources "statefulset/block-node-${BLOCK_NODE_ID}" -c block-node-server \
    --limits="memory=${BLOCK_NODE_MEMORY_LIMIT}" --requests="memory=${BLOCK_NODE_MEMORY_REQUEST}" || true
  kubectl -n "${SOLO_NAMESPACE}" set env "statefulset/block-node-${BLOCK_NODE_ID}" -c block-node-server \
    "JAVA_OPTS=${BLOCK_NODE_HEAP_OPTS}" || true
  kubectl -n "${SOLO_NAMESPACE}" rollout status "statefulset/block-node-${BLOCK_NODE_ID}" --timeout=300s || true

  kubectl -n "${SOLO_NAMESPACE}" wait --for=condition=ready "pod/block-node-${BLOCK_NODE_ID}-0" --timeout="${BLOCK_NODE_READY_TIMEOUT_SECS}s"
}

# Confirm CN is actually streaming blocks into the BN by polling the BN's
# BlockNodeService/serverStatus until lastAvailableBlock > 0. Without this
# the cutover script can sail past step 7 with a healthy-looking BN pod that
# has persisted nothing, and the failure surfaces much later (or not at all).
verify_block_node_has_blocks() {
  local timeout_secs="${1:-120}"
  local svc="block-node-${BLOCK_NODE_ID}"
  local remote_port="${BLOCK_NODE_GRPC_PORT:-40840}"
  local local_port="${BLOCK_NODE_GRPC_LOCAL_PORT:-40840}"
  local pf_log="${WORK_DIR}/port-forward-block-node-status.log"
  local pf_pid=""

  # The BN server doesn't enable gRPC reflection, so grpcurl needs the proto
  # file + import paths. node_service.proto imports services/basic_types.proto
  # which in turn lives under a sibling proto tree in the block-node repo.
  local proto_api_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto"
  local proto_services_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/block-node-protobuf"
  local proto_file="block-node/api/node_service.proto"
  if [[ ! -f "${proto_api_root}/${proto_file}" ]]; then
    echo "verify_block_node_has_blocks: expected proto not found at ${proto_api_root}/${proto_file}" >&2
    echo "  Ensure BLOCK_NODE_REPO_PATH points at a hiero-block-node checkout." >&2
    return 1
  fi

  kill_processes_on_local_port "${local_port}"
  : > "${pf_log}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${svc}" "${local_port}:${remote_port}" >"${pf_log}" 2>&1 < /dev/null &
  pf_pid=$!
  disown "${pf_pid}" 2>/dev/null || true

  if ! wait_for_tcp_open "127.0.0.1" "${local_port}" 20 1 \
        "Waiting for BN gRPC port-forward on 127.0.0.1:${local_port}"; then
    kill "${pf_pid}" >/dev/null 2>&1 || true
    echo "Could not establish port-forward to ${svc} (last 20 lines of ${pf_log}):" >&2
    tail -n 20 "${pf_log}" >&2 2>/dev/null || true
    return 1
  fi

  local deadline=$((SECONDS + timeout_secs))
  local last_available=""
  local raw=""
  local grpc_err=""
  echo "Polling ${svc} BlockNodeService/serverStatus for lastAvailableBlock > 0 (up to ${timeout_secs}s)"
  while (( SECONDS < deadline )); do
    grpc_err="${WORK_DIR}/bn-status-grpcurl.err"
    raw="$(grpcurl -plaintext \
            -import-path "${proto_api_root}" \
            -import-path "${proto_services_root}" \
            -proto "${proto_file}" \
            -d '{}' \
            "127.0.0.1:${local_port}" \
            org.hiero.block.api.BlockNodeService/serverStatus 2>"${grpc_err}")" || true
    last_available="$(echo "${raw}" | jq -r '.lastAvailableBlock // empty' 2>/dev/null || true)"
    if [[ -n "${last_available}" && "${last_available}" =~ ^[0-9]+$ && "${last_available}" -gt 0 ]]; then
      echo "verify_block_node_has_blocks: lastAvailableBlock=${last_available} on ${svc} (firstAvailableBlock=$(echo "${raw}" | jq -r '.firstAvailableBlock // "?"'))"
      kill "${pf_pid}" >/dev/null 2>&1 || true
      return 0
    fi
    sleep 5
  done

  echo "BN ${svc} did not report lastAvailableBlock > 0 within ${timeout_secs}s" >&2
  echo "  last serverStatus response: ${raw:-<empty>}" >&2
  [[ -s "${grpc_err}" ]] && { echo "  last grpcurl stderr:" >&2; sed 's/^/    /' "${grpc_err}" >&2; }
  kill "${pf_pid}" >/dev/null 2>&1 || true
  return 1
}

# After seeding rolls the BN, wait for it to resume ingesting the live stream before the 0.77 upgrade
# restarts the CN (which RESETS the CN's in-memory block buffer to the post-restart tip). Otherwise
# the blocks produced during the roll are orphaned: the BN's wanted block falls below the reset CN
# buffer floor ("block out of range"), the publisher never reconnects, and the BN stalls. We require
# the BN's lastAvailableBlock to advance again (publisher reconnected + streaming the post-roll blocks).
wait_for_block_node_caught_up() {
  local timeout_secs="${1:-300}"
  local svc="block-node-${BLOCK_NODE_ID}"
  local remote_port="${BLOCK_NODE_GRPC_PORT:-40840}"
  local local_port="${BLOCK_NODE_GRPC_LOCAL_PORT:-40840}"
  local pf_log="${WORK_DIR}/port-forward-bn-catchup.log"
  local pf_pid=""
  local proto_api_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/src/main/proto"
  local proto_services_root="${BLOCK_NODE_REPO_PATH}/protobuf-sources/block-node-protobuf"
  local proto_file="block-node/api/node_service.proto"
  local cn_pod="network-${NODE_ALIASES%%,*}-0"
  local comms_log="/opt/hgcapp/services-hedera/HapiApp2.0/output/block-node-comms.log"

  kill_processes_on_local_port "${local_port}"
  : > "${pf_log}"
  nohup kubectl -n "${SOLO_NAMESPACE}" port-forward "svc/${svc}" "${local_port}:${remote_port}" >"${pf_log}" 2>&1 < /dev/null &
  pf_pid=$!
  disown "${pf_pid}" 2>/dev/null || true
  if ! wait_for_tcp_open "127.0.0.1" "${local_port}" 20 1 "Waiting for BN gRPC port-forward on 127.0.0.1:${local_port}"; then
    kill "${pf_pid}" >/dev/null 2>&1 || true
    echo "catchup: could not port-forward to ${svc}" >&2
    return 1
  fi

  # Wait until the CN reports the Block Node in-range for streaming ("available for streaming
  # (wantedBlock: N)") after the seed roll, before the 0.77 cutover. The seed restart transiently
  # drops the CN<->BN stream; this confirms the CN has re-selected the BN as a streaming target.
  # Failure signature is "block out of range" / "No block nodes available". On timeout we warn and
  # proceed -- the cutover re-establishes the stream and the freeze-time ack-wait drains the lag.
  echo "Waiting for the CN to report the Block Node in-range for streaming after the seed roll (up to ${timeout_secs}s) before the 0.77 cutover"
  local prev="" cur raw cn_view
  local deadline=$((SECONDS + timeout_secs))
  while (( SECONDS < deadline )); do
    raw="$(grpcurl -plaintext -import-path "${proto_api_root}" -import-path "${proto_services_root}" \
            -proto "${proto_file}" -d '{}' "127.0.0.1:${local_port}" \
            org.hiero.block.api.BlockNodeService/serverStatus 2>/dev/null)" || true
    cur="$(echo "${raw}" | jq -r '.lastAvailableBlock // empty' 2>/dev/null || true)"
    cn_view="$(kubectl -n "${SOLO_NAMESPACE}" exec "${cn_pod}" -c root-container -- sh -c \
      "grep -aE 'available for streaming \(wantedBlock|block out of range|No block nodes available for streaming' '${comms_log}' 2>/dev/null | tail -1" 2>/dev/null || true)"
    case "${cn_view}" in
      *"available for streaming (wantedBlock"*)
        echo "Block Node in-range for streaming (BN lastAvailableBlock=${cur:-?}); safe to cut over"
        kill "${pf_pid}" >/dev/null 2>&1 || true
        return 0
        ;;
    esac
    if [[ "${cur}" =~ ^[0-9]+$ && "${cur}" != "${prev}" ]]; then
      echo "  BN lastAvailableBlock=${cur} (CN view: ${cn_view:-pending})"
      prev="${cur}"
    fi
    sleep 5
  done
  echo "WARN catchup: CN did not report the BN in-range within ${timeout_secs}s (last BN lastAvailableBlock=${prev:-?}); proceeding with the cutover anyway" >&2
  kill "${pf_pid}" >/dev/null 2>&1 || true
  return 1
}

write_mirror_node_block_cutover_values() {
  # Enable block-stream ingestion and point the importer at the Block Node. Per Mirror Node
  # guidance: in v0.154 hiero.mirror.importer.block.enabled defaults FALSE so it MUST be set true,
  # and there is NO block.cutover.hapiVersion — the importer auto-switches to blockstream when no
  # record-stream file arrives within block.cutover.threshold (~16s), which is exactly what happens
  # at the 0.77 cutover (BLOCKS-only, gRPC-only, no MinIO record files). Once it reads one block
  # from the BN the switch is permanent. (hapiVersion exists only in 0.155+.)
  cat > "${MIRROR_NODE_CUTOVER_VALUES_FILE}" <<EOF
restjava:
  resources:
    requests:
      memory: ${MIRROR_RESTJAVA_MEMORY_REQUEST}
    limits:
      memory: ${MIRROR_RESTJAVA_MEMORY_LIMIT}
importer:
  resources:
    requests:
      memory: ${MIRROR_IMPORTER_MEMORY_REQUEST}
    limits:
      memory: ${MIRROR_IMPORTER_MEMORY_LIMIT}
  env:
    HIERO_MIRROR_IMPORTER_BLOCK_ENABLED: 'true'
    HIERO_MIRROR_IMPORTER_BLOCK_NODES_0_HOST: 'block-node-${BLOCK_NODE_ID}.${SOLO_NAMESPACE}.svc.cluster.local'
EOF
  if [[ -n "${MIRROR_BLOCK_CUTOVER_HAPIVERSION}" ]]; then
    # 0.155+ only: pin the HAPI version at which to switch from record to block stream.
    printf '    HIERO_MIRROR_IMPORTER_BLOCK_CUTOVER_HAPIVERSION: %s\n' \
      "'${MIRROR_BLOCK_CUTOVER_HAPIVERSION}'" >> "${MIRROR_NODE_CUTOVER_VALUES_FILE}"
  fi
}

# Reconfigures the already-deployed mirror node to read from the Block Node
# and enable block-cutover stages. Uses `solo mirror node upgrade` (NOT `add`)
# because `add` reinstalls the ingress Helm chart with a new release name and
# collides with the existing haproxy-ingress-1 ownership of the ingress
# ServiceAccount. `upgrade` reuses the existing release and only diffs values.
# Also omits --enable-ingress for the same reason; the Step 3 deploy already
# created it.
#
# `--force` is required to bypass Solo's three version gates for block node
# integration (CN >= v0.72.0, BN >= 0.29.0, MN >= 0.150.0). Without it Solo
# silently strips the BN/cutover values from the helm upgrade and logs:
#   "Mirror node will remain configured to pull from consensus node because
#    version requirements were not met"
# even when the deployed versions actually satisfy the gates. `--mirror-node-version`
# is required because Solo's baked-in default (v0.152.0) does not recognize the
# HIERO_MIRROR_IMPORTER_BLOCK_CUTOVER_* env keys.
#
# Note: if importer becomes wedged after this, the documented hack is to patch
# mirrornode config, scale down importer, clean up its database, and scale it
# back up.
update_mirror_node_for_block_cutover() {
  local upgrade_args=(
    solo mirror node upgrade
    --deployment "${SOLO_DEPLOYMENT}"
    --force
    --mirror-node-version "${MIRROR_NODE_VERSION}"
    --values-file "${MIRROR_NODE_CUTOVER_VALUES_FILE}"
  )

  write_mirror_node_block_cutover_values
  echo "Upgrading mirror node to ${MIRROR_NODE_VERSION} reading from block-node-${BLOCK_NODE_ID} (block.enabled=true, auto-cutover, version gates bypassed)"
  "${upgrade_args[@]}"
}

# kind is only needed for the local ephemeral cluster; the remote runner has no kind binary.
if [[ "${CLUSTER_TARGET}" == "kind" ]]; then
  require_cmd kind
fi
require_cmd kubectl
require_cmd solo
require_cmd npm
require_cmd node
require_cmd curl
require_cmd jq
require_cmd java
require_cmd grpcurl

if [[ "${USE_BLOCK_NODE_JUMPSTART}" == "true" ]]; then
  if ! validate_block_node_repo; then
    exit 1
  fi
fi

if [[ ! -f "${LOG4J2_XML_PATH}" ]]; then
  echo "log4j2 config not found: ${LOG4J2_XML_PATH}" >&2
  exit 1
fi
if [[ ! -f "${APP_PROPS_073_FILE}" ]]; then
  echo "application.properties file not found: ${APP_PROPS_073_FILE}" >&2
  exit 1
fi
if [[ ! -f "${APP_PROPS_074_FILE}" ]]; then
  echo "application.properties file not found: ${APP_PROPS_074_FILE}" >&2
  exit 1
fi
if [[ ! -f "${APP_PROPS_075_FILE}" ]]; then
  echo "application.properties file not found: ${APP_PROPS_075_FILE}" >&2
  exit 1
fi
if [[ ! -f "${APP_PROPS_076_FILE}" ]]; then
  echo "application.properties file not found: ${APP_PROPS_076_FILE}" >&2
  exit 1
fi
if [[ ! -x "${REPO_ROOT}/gradlew" ]]; then
  echo "Missing executable gradlew: ${REPO_ROOT}/gradlew" >&2
  exit 1
fi

if should_run_step 1; then
  # Full reset: clear ALL stray port-forwards + helper procs left over from previous runs before
  # recreating the cluster (prevents back-to-back accumulation of forwards/watchdogs/FDs).
  preflight_kill_stale_port_forwards
  print_banner "Step 1/12: Create fresh kind cluster and Solo deployment"
  if [[ "${CLUSTER_TARGET}" == "kind" ]]; then
    kind delete cluster -n "${SOLO_CLUSTER_NAME}" >/dev/null 2>&1 || true
  fi
  cleanup_record_stream_files_only
  rm -rf "${WRAPPED_BLOCKS_DIR}" >/dev/null 2>&1 || true

  if [[ "${CLUSTER_TARGET}" == "kind" ]]; then
    run_step "Creating kind cluster ${SOLO_CLUSTER_NAME}" \
      kind create cluster -n "${SOLO_CLUSTER_NAME}"
  fi

  run_step "Connecting Solo to cluster (cluster-ref=${CLUSTER_REF}, context=${KUBE_CONTEXT})" \
    solo cluster-ref config connect --cluster-ref "${CLUSTER_REF}" --context "${KUBE_CONTEXT}"
  solo deployment config delete --deployment "${SOLO_DEPLOYMENT}" --quiet-mode >/dev/null 2>&1 || true
  run_step "Creating Solo deployment ${SOLO_DEPLOYMENT}" \
    solo deployment config create -n "${SOLO_NAMESPACE}" --deployment "${SOLO_DEPLOYMENT}"
  run_step "Attaching cluster to deployment" \
    solo deployment cluster attach --deployment "${SOLO_DEPLOYMENT}" --cluster-ref "${CLUSTER_REF}" --num-consensus-nodes "${CONSENSUS_NODE_COUNT}"
  if [[ "${CLUSTER_TARGET}" == "remote" ]]; then
    # Mark local-path default, tear down any prior network, re-establish the deployment config the
    # destroy removes, and run cluster-ref setup only if missing (shared helper). Then start the
    # toleration patcher so the mirror/block-node/shared-resources pods can schedule on the tainted
    # nodes throughout the multi-step flow; cleanup() stops it.
    remote_reset_and_prepare_deployment
    start_remote_toleration_patcher
  elif [[ "${ENABLE_MONITORING}" == "true" ]]; then
    run_step "Installing Solo cluster prerequisites (Prometheus + MinIO)" \
      solo cluster-ref config setup -s "${SOLO_CLUSTER_SETUP_NAMESPACE}" --prometheus-stack true
    ensure_grafana_port_forward
  else
    run_step "Installing Solo cluster prerequisites (MinIO only; monitoring disabled)" \
      solo cluster-ref config setup -s "${SOLO_CLUSTER_SETUP_NAMESPACE}" --prometheus-stack false
  fi
  print_step_complete "Step 1/12"
else
  print_banner "Resume mode: START_STEP=${START_STEP}; assuming cluster matches end of step $((START_STEP - 1))"
  prepare_js_sdk_runtime
  restart_post_upgrade_port_forwards
  [[ "${ENABLE_MONITORING}" == "true" ]] && ensure_grafana_port_forward
  STEP_START_TS=""
fi

if should_run_step 2; then
  print_banner "Step 2/12: Deploy consensus network at ${INITIAL_RELEASE_TAG} (v0.73.0)"
  run_step "Generating consensus keys (gossip + tls)" \
    solo keys consensus generate --gossip-keys --tls-keys --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}"
  # --service-monitor needs the ServiceMonitor CRD, which only exists when the
  # kube-prometheus-stack is installed; gate it on ENABLE_MONITORING.
  service_monitor_flag="false"
  [[ "${ENABLE_MONITORING}" == "true" ]] && service_monitor_flag="true"
  # Deploy with PVCs on BOTH targets. Step 10 (run_076_upgrade) injects the WRAPS env via
  # `kubectl set env`, which rolls the consensus StatefulSets BEFORE Solo's upgrade fires. With the
  # old remote `--pvcs false` (emptyDir), that roll wiped the local-build jars, so the rolled pod
  # could not restart and Solo's upgrade SDK-ping failed (its retries never found a serving node).
  # On remote we now use PVCs backed by the local-path StorageClass (made default in
  # remote_reset_and_prepare_deployment): local-path PVs are node-pinned, so the rolled pod
  # reschedules to the same node and its data survives the roll. Remote also needs the
  # scheduling/storage value overrides.
  cutover_deploy=(solo consensus network deploy --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" \
    --application-properties "${APP_PROPS_073_FILE}" --log4j2-xml "${LOG4J2_XML_PATH}" \
    --service-monitor "${service_monitor_flag}" --pod-log true --release-tag "${INITIAL_RELEASE_TAG}" --pvcs true)
  if [[ "${CLUSTER_TARGET}" == "remote" ]]; then
    cutover_deploy+=(--values-file "${REMOTE_CLUSTER_NETWORK_VALUES}")
  fi
  run_step "Deploying consensus network at ${INITIAL_RELEASE_TAG}" \
    "${cutover_deploy[@]}"
  run_step "Setting up consensus nodes (${INITIAL_RELEASE_TAG})" \
    solo consensus node setup --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --release-tag "${INITIAL_RELEASE_TAG}"
  run_step "Starting consensus nodes" \
    solo consensus node start --deployment "${SOLO_DEPLOYMENT}" -i "${NODE_ALIASES}" --force-port-forward false
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  [[ "${ENABLE_MONITORING}" == "true" ]] && ensure_solo_service_monitor_for_prometheus
  print_step_complete "Step 2/12"
fi

if should_run_step 3; then
  print_banner "Step 3/12: Deploy mirror/explorer and validate baseline transactions"
  deploy_mirror_node_for_cutover
  run_step "Deploying explorer node" \
    solo explorer node add --deployment "${SOLO_DEPLOYMENT}" --force-port-forward false
  if ! start_explorer_ingress_port_forward; then
    echo "WARNING: Explorer UI tunnel is unavailable; explorer may be inaccessible after run." >&2
  fi

  restart_post_upgrade_port_forwards

  wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1" 36 5
  prepare_js_sdk_runtime

  echo "Testing mirror-node readiness via a simple cryptoCreate (wait up to ${MIRROR_ACCOUNT_WAIT_MS:-180000}ms for mirror visibility)"
  export MIRROR_ACCOUNT_WAIT_MS="${MIRROR_ACCOUNT_WAIT_MS:-180000}"
  node "${NODE_SCRIPT}"
  sleep 45
  print_step_complete "Step 3/12"
fi

if should_run_step 4; then
  print_banner "Step 4/12: Upgrade consensus network to ${UPGRADE_074_RELEASE_TAG} with 0.74 properties"
  run_step "Upgrading consensus network to ${UPGRADE_074_RELEASE_TAG}" \
    solo consensus network upgrade --deployment "${SOLO_DEPLOYMENT}" --node-aliases "${NODE_ALIASES}" --upgrade-version "${UPGRADE_074_RELEASE_TAG}" --quiet-mode --force --application-properties "${APP_PROPS_074_FILE}"

  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600

  restart_post_upgrade_port_forwards
  wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1" 36 5
  wait_for_sdk_responsive 180

  echo "Testing mirror-node readiness via a simple cryptoCreate after the 0.74 upgrade (wait up to ${MIRROR_ACCOUNT_WAIT_MS:-180000}ms)"
  export MIRROR_ACCOUNT_WAIT_MS="${MIRROR_ACCOUNT_WAIT_MS:-180000}"
  node "${NODE_SCRIPT}"

  sleep 5
  print_step_complete "Step 4/12"
fi

if should_run_step 5; then
  print_banner "Step 5/12: Generate jumpstart data via wrapped record block tooling"
  MIRROR_BLOCKS_JSON="$(curl -sf "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?order=desc&limit=1")" || {
    echo "Failed to GET /api/v1/blocks from mirror REST" >&2
    exit 1
  }
  MIRROR_BLOCK_NUMBER="$(echo "${MIRROR_BLOCKS_JSON}" | jq -r '.blocks[0].number')"
  if [[ -z "${MIRROR_BLOCK_NUMBER}" || "${MIRROR_BLOCK_NUMBER}" == "null" ]]; then
    echo "Could not parse latest block number from mirror response" >&2
    exit 1
  fi
  export MIRROR_BLOCK_NUMBER

  download_solo_minio_record_streams "${MIRROR_BLOCK_NUMBER}" "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}"
  prepare_wrap_day_archives_from_record_streams
  generate_block_node_metadata_from_mirror "${MIRROR_BLOCK_NUMBER}"
  run_block_node_wrap_tool "${WRAP_COMPRESSED_DAYS_DIR}" "${WRAPPED_BLOCKS_DIR}"

  if [[ "${USE_BLOCK_NODE_JUMPSTART}" == "true" ]]; then
    load_jumpstart_env_from_bin "${JUMPSTART_BIN_PATH}"
  else
    export JUMPSTART_BLOCK_NUMBER="${MIRROR_BLOCK_NUMBER}"
  fi
  print_step_complete "Step 5/12"
fi

if should_run_step 6; then
  print_banner "Step 6/12: Build temp 0.75 properties from jumpstart.bin and upgrade local build as ${UPGRADE_075_VERSION} (WRB streaming on)"
  create_temp_075_upgrade_properties
  sleep 5
  run_075_upgrade
  print_step_complete "Step 6/12"
fi

if should_run_step 7; then
  print_banner "Step 7/12: Deploy Block Node ${BLOCK_NODE_ID} and link to consensus nodes"
  deploy_block_node_for_cutover
  verify_block_node_has_blocks 120
  print_step_complete "Step 7/12"
fi

if should_run_step 8; then
  print_banner "Step 8/12: Validate 0.75 jumpstart by replay vs migration vote"
  parse_migration_vote_from_hgcaa
  run_replay_wrap_to_075 "${MIRROR_REST_URL}" "${MIGRATION_BLOCK_NUMBER}"
  [[ "${JUMPSTART_BLOCK_NUMBER}" == "${MIGRATION_BLOCK_NUMBER}" ]] || {
    echo "Replay jumpstart block number (${JUMPSTART_BLOCK_NUMBER}) did not match migration block (${MIGRATION_BLOCK_NUMBER})" >&2
    exit 1
  }
  compare_replay_to_migration_vote || {
    echo "Jumpstart validation failed: migration vote does not match offline replay (see ${MIGRATION_COMPARE_LOG})" >&2
    exit 1
  }
  print_step_complete "Step 8/12"
fi

if should_run_step 9; then
  print_banner "Step 9/12: Update mirror node to read from block-node-${BLOCK_NODE_ID}"
  update_mirror_node_for_block_cutover
  echo "Restarting consensus and mirror REST port-forwards"
  restart_post_upgrade_port_forwards
  echo "Waiting for mirror REST to respond on http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1 (up to 3m)"
  wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1" 36 5
  wait_for_sdk_responsive 180

  # Sever the importer's RECORD path (S3 to MinIO) so the validation cryptoCreate
  # can only reach mirror REST via the BN -> importer hop. Without this, cheetah
  # uploads the matching .rcd.gz to MinIO faster than the BN streams the WRB and
  # the importer ingests via records, never exercising the configured-primary BN
  # source. The cleanup trap unconditionally restores MinIO so an abort mid-window
  # does not leave it down for re-runs.
  echo "Disconnecting importer from MinIO so block-node-${BLOCK_NODE_ID} is the only viable source"
  disconnect_importer_from_minio || echo "WARNING: MinIO disconnect failed; validation may still pass via records" >&2

  # Submit several cryptoCreate txns via SDK and assert each new account appears
  # in mirror REST. With MinIO down, success here proves block-node-${BLOCK_NODE_ID}
  # delivered multiple independent fresh blocks all the way to the importer — a
  # single block reaching mirror could be a cache fluke, several cannot.
  echo "Submitting ${STEP9_VALIDATION_TX_COUNT:-5} cryptoCreate txns via SDK and validating mirror visibility via BN (mirror wait up to 3m)"
  export MIRROR_ACCOUNT_WAIT_MS="${MIRROR_ACCOUNT_WAIT_MS:-180000}"
  export VALIDATION_TX_COUNT="${STEP9_VALIDATION_TX_COUNT:-5}"
  step9_rc=0
  node "${NODE_SCRIPT}" || step9_rc=$?
  unset VALIDATION_TX_COUNT

  echo "Restoring MinIO so subsequent steps (and CN-side record-stream uploaders) recover"
  reconnect_importer_to_minio

  if (( step9_rc != 0 )); then
    echo "Step 9 validation submit failed (rc=${step9_rc})" >&2
    exit "${step9_rc}"
  fi

  print_step_complete "Step 9/12"
fi

# FUTURE enable when TSS support is ready and tested
if should_run_step 10; then
  print_banner "Step 10/12: Upgrade to ${UPGRADE_076_VERSION} release image with 0.76 properties"
  sleep 5
  # Still streaming WRBs but TSS is enabled, force mock signatures
  run_076_upgrade
  print_step_complete "Step 10/12"
fi

if should_run_step 11; then
  print_banner "Step 11/12: Upgrade local build with 0.77 properties as ${UPGRADE_077_VERSION} (BLOCKS-only cutover, real TSS signatures)"
  sleep 5
  # Pre-cutover: bootstrap the Block Node with the network's TSS ledger id so it can
  # verify the real-TSS-signed blocks the 0.77 cutover starts producing. The ledger id
  # was published mid-chain during the 0.76 step; the BN (deployed mid-chain) never
  # picked it up on its own. See seed_block_node_tss_parameters for why this may become
  # unnecessary. Run AFTER Step 10 (publication exists) and BEFORE run_077_upgrade.
  seed_block_node_tss_parameters
  # The seed rolled the BN; let it re-catch-up to the live stream on 0.76 before run_077_upgrade
  # restarts the CN (which resets the CN block buffer), otherwise the gap blocks are orphaned and the
  # BN stalls (mirror then can't get the post-cutover blocks).
  wait_for_block_node_caught_up 180 || echo "WARN: BN not reported in-range within timeout after seeding; proceeding with the 0.77 cutover anyway" >&2
  run_077_upgrade
  print_step_complete "Step 11/12"
fi

if should_run_step 12; then
  print_banner "Step 12/12: Post-upgrade readiness and end-to-end transaction verification"
  wait_for_consensus_pods_ready 600
  wait_for_haproxy_ready 600
  restart_post_upgrade_port_forwards
  wait_for_http_ok "http://127.0.0.1:${MIRROR_REST_LOCAL_PORT}/api/v1/blocks?limit=1" 36 5
  wait_for_sdk_responsive 180
  echo "Testing mirror-node readiness via a simple cryptoCreate at end-of-run (wait up to ${MIRROR_ACCOUNT_WAIT_MS:-180000}ms)"
  export MIRROR_ACCOUNT_WAIT_MS="${MIRROR_ACCOUNT_WAIT_MS:-180000}"
  node "${NODE_SCRIPT}"
  print_step_complete "Step 12/12"
fi
start_post_run_keepalive
print_end_of_run_diagnostics
print_banner "Completed: block stream cutover scenario finished successfully"
