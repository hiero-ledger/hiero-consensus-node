#!/usr/bin/env bash
# validate-replay.sh — end-to-end block stream equivalence validation
#
# Steps:
#   1. Download state snapshots from GCP (skipped if already present locally)
#   2. Reconstruct PCES files from block stream (blocks-to-pces)
#   3. Replay PCES through a real platform node (replay-pces)
#   4. Apply resulting blocks back to the origin state (apply-blocks)
#   5. Diff the resulting state against the expected state (diff)
#
# By default all five steps run in order. To run a subset, set STEPS to a single
# step or an inclusive range:
#   STEPS=3        run only step 3
#   STEPS=2-4      run steps 2 through 4
#   STEPS=1-5      the default (all steps)
#
# Each step consumes the on-disk artifacts produced by the earlier steps, so a
# partial range assumes the outputs of any skipped-but-earlier steps are already
# present (e.g. STEPS=3-5 reuses the snapshots under ./${ORIGIN_ROUND} and the
# PCES files under ${OUT}). When a required upstream artifact is missing, the
# selected step fails fast with a message naming the step that produces it.
#
# Required environment variables (no defaults — the caller must set them):
#   ORIGIN_ROUND, TARGET_ROUND, JAR                    (always)
#   SNAPSHOT_BUCKET                                    (only when step 1 runs)
#   BLOCK_STREAM_DIR, BILLING_PROJECT                  (only when step 2 runs)
#
# In CI the reusable workflow (zxc-diff-testing-replay-block-stream.yaml)
# supplies all of these. For a local run:
#   ORIGIN_ROUND=211155071 TARGET_ROUND=211422945 JAR=./validator.jar \
#   BLOCK_STREAM_DIR=gs://… BILLING_PROJECT=hedera-regression \
#   SNAPSHOT_BUCKET=gs://… ./validate-replay.sh
#
#   # re-run only the diff after tweaking --ignore-field, reusing prior outputs:
#   ORIGIN_ROUND=211155071 TARGET_ROUND=211422945 JAR=./validator.jar \
#   STEPS=5 ./validate-replay.sh

set -Eeuo pipefail

# ─── Helpers ──────────────────────────────────────────────────────────────────

info()  { echo "[INFO]  ${*}"; }
error() { echo "[ERROR] ${*}" >&2; }

# Fail loudly instead of silently. With `set -e`, any command that returns
# non-zero aborts the script with no output; this trap reports the line, exit
# code, and the offending command first, so a failure inside a step is never a
# mystery. (Commands whose failure is handled — those in if/while/&&/|| tests
# or followed by `|| …` — are exempt, as usual, so this only fires on genuinely
# unhandled errors.)
trap 'rc=$?; error "aborted at line ${LINENO} (exit ${rc}): ${BASH_COMMAND}"; exit "${rc}"' ERR

require_file() {
    [[ -f "${1}" ]] || { error "Required file not found: ${1}${2:+ (${2})}"; exit 1; }
}

require_dir() {
    [[ -d "${1}" ]] || { error "Required directory not found: ${1}${2:+ (${2})}"; exit 1; }
}

# ─── Step selection ───────────────────────────────────────────────────────────
#
# STEPS selects which steps run: a single step ("3") or an inclusive range
# ("2-4"), within 1..5. Defaults to the full pipeline.

STEPS="${STEPS:-1-5}"
if [[ "${STEPS}" =~ ^([1-5])$ ]]; then
    START_STEP="${BASH_REMATCH[1]}"
    END_STEP="${BASH_REMATCH[1]}"
elif [[ "${STEPS}" =~ ^([1-5])-([1-5])$ ]]; then
    START_STEP="${BASH_REMATCH[1]}"
    END_STEP="${BASH_REMATCH[2]}"
else
    error "Invalid STEPS='${STEPS}'. Use a single step (e.g. STEPS=3) or a range (e.g. STEPS=2-4), within 1..5."
    exit 1
fi
if (( START_STEP > END_STEP )); then
    error "Invalid STEPS='${STEPS}': start step ${START_STEP} is after end step ${END_STEP}."
    exit 1
fi

# True when step N falls within the selected [START_STEP, END_STEP] range.
should_run_step() { (( ${1} >= START_STEP && ${1} <= END_STEP )); }

# ─── Announce the plan ────────────────────────────────────────────────────────
#
# Print the effective selection up front. Besides being useful, this surfaces a
# common gotcha: setting STEPS as a shell variable without exporting it
# (`STEPS=5` on its own line, then `./validate-replay.sh`) means the child
# process never sees it, and this banner shows the default "1-5" instead of the
# intended "5". Pass it on the same line (`STEPS=5 ./validate-replay.sh`) or
# `export STEPS=5` first.

declare -a STEP_NAMES=(
    [1]="Download state snapshots"
    [2]="blocks-to-pces"
    [3]="replay-pces"
    [4]="apply-blocks"
    [5]="diff"
)

info "Plan (STEPS=${STEPS} → running steps ${START_STEP}-${END_STEP}):"
for n in 1 2 3 4 5; do
    if should_run_step "${n}"; then
        info "  [RUN ] ${n}. ${STEP_NAMES[$n]}"
    else
        info "  [skip] ${n}. ${STEP_NAMES[$n]}"
    fi
done

# ─── Required parameters (must be set by the caller) ─────────────────────────
#
# Only the parameters used by the selected steps are required, so a partial run
# (e.g. STEPS=5) does not demand GCS/billing config it will never use.

missing=()
[[ -z "${ORIGIN_ROUND:-}" ]] && missing+=("ORIGIN_ROUND")
[[ -z "${TARGET_ROUND:-}" ]] && missing+=("TARGET_ROUND")
[[ -z "${JAR:-}" ]]         && missing+=("JAR")

# Step 1 (snapshot download) needs the bucket.
should_run_step 1 && [[ -z "${SNAPSHOT_BUCKET:-}" ]] && missing+=("SNAPSHOT_BUCKET")

# Step 2 (blocks-to-pces) needs the block stream location and billing project.
if should_run_step 2; then
    [[ -z "${BLOCK_STREAM_DIR:-}" ]] && missing+=("BLOCK_STREAM_DIR")
    [[ -z "${BILLING_PROJECT:-}" ]]  && missing+=("BILLING_PROJECT")
fi

if (( ${#missing[@]} > 0 )); then
    error "The following required environment variables are not set for STEPS=${STEPS}: ${missing[*]}"
    exit 1
fi

# ─── Optional parameters (sensible defaults) ─────────────────────────────────

NODE_ID="${NODE_ID:-0}"

# How often (in seconds) to print a snapshot-download progress heartbeat. gsutil runs quietly and
# this is the only progress output, so the CI log stays readable instead of thousands of per-file lines.
HEARTBEAT_SECS="${HEARTBEAT_SECS:-5}"

# Root output directory for all artifacts produced by the script
OUT="${OUT:-./out}"

# Fields known to diverge due to unsigned-event reconstruction (legacyRunningEventHash
# and its downstream hash chain). These are structurally expected differences, not bugs.
IGNORE_FIELDS="${IGNORE_FIELDS:-trailingBlockHashes,startOfBlockStateHash,rightmostPrecedingStateChangesTreeHashes,intermediatePreviousBlockRootHashes,legacyRunningEventHash}"

# ─── Derived paths ────────────────────────────────────────────────────────────

# blocks-to-pces writes its output under this subdirectory of ${OUT} (it may contain a
# further date-stamped subtree, which the consolidation step below collapses to one leaf)
PCES_SUBDIR="pces-${ORIGIN_ROUND}-${TARGET_ROUND}"

ORIGIN_STATE_DIR="./${ORIGIN_ROUND}"
TARGET_STATE_DIR="./${TARGET_ROUND}"
RESULTING_STATE="${OUT}/resulting_state"

# replay-pces writes its state snapshot and (via the -D overrides below) its block/event/record
# streams under this directory, so nothing lands in the root-owned production default (/opt/hgcapp).
REPLAY_OUT="${OUT}/replay"

# ─── Preflight checks ─────────────────────────────────────────────────────────

require_file "${JAR}"

if ! command -v java &>/dev/null; then
    error "java not found on PATH"
    exit 1
fi

# gsutil is used by the snapshot download in step 1.
if should_run_step 1 && [[ "${SNAPSHOT_BUCKET:-}" == gs://* ]] && ! command -v gsutil &>/dev/null; then
    error "gsutil not found on PATH (required for GCS snapshot download in step 1)"
    exit 1
fi

# ─── Step 1: Download state snapshots ─────────────────────────────────────────

download_snapshot() {
    local round="${1}"
    local dest="./${round}"
    if [[ -d "${dest}" ]]; then
        info "  Snapshot for round ${round} already present at ${dest}, skipping download."
        return
    fi
    if [[ -z "${SNAPSHOT_BUCKET:-}" ]]; then
        error "Snapshot for round ${round} not found at ${dest} and SNAPSHOT_BUCKET is not set."
        error "Either download the snapshot manually or set SNAPSHOT_BUCKET to a GCS URI."
        exit 1
    fi
    local src="${SNAPSHOT_BUCKET%/}/${round}"
    info "  Downloading snapshot for round ${round} from ${src} ..."
    mkdir -p "${dest}"
    # rsync mirrors the remote round directory *into* ${dest} with no extra
    # nesting, so state files land directly under ./${round} — which is what
    # replay-pces/apply-blocks expect for --state-dir. (`cp -r <src> <dest>/`
    # would create ./${round}/${round}/… because <dest> already exists.)
    # -R recursive, -P preserve POSIX metadata, -c checksum-based verification.
    #
    # -q silences gsutil's per-file "Copying …" lines and progress-bar redraws (thousands of lines
    # that overwhelm the CI log viewer and truncate later output). We run it in the background and
    # print our own progress heartbeat every ${HEARTBEAT_SECS}s, measured from ${dest}, so there is
    # some liveness without the flood. Set HEARTBEAT_SECS=0 to disable the heartbeat entirely.
    gsutil -q -m rsync -R -P -c "${src}" "${dest}" &
    local gsutil_pid=$!
    while kill -0 "${gsutil_pid}" 2>/dev/null; do
        sleep "${HEARTBEAT_SECS:-5}"
        kill -0 "${gsutil_pid}" 2>/dev/null || break
        [[ "${HEARTBEAT_SECS}" == "0" ]] && continue
        local so_far files
        so_far=$(du -sh "${dest}" 2>/dev/null | cut -f1) || so_far="?"
        files=$(find "${dest}" -type f 2>/dev/null | wc -l) || files="?"
        info "    ... ${so_far} across ${files} file(s) so far"
    done
    if ! wait "${gsutil_pid}"; then
        error "snapshot download failed for round ${round}"
        exit 1
    fi
    info "  Done — snapshot at ${dest}"
}

if should_run_step 1; then
    info "=== Step 1: State snapshots ==="
    download_snapshot "${ORIGIN_ROUND}"
    download_snapshot "${TARGET_ROUND}"
else
    info "=== Step 1: skipped (STEPS=${STEPS}) — expecting snapshots at ${ORIGIN_STATE_DIR} and ${TARGET_STATE_DIR} ==="
fi

# ─── Step 2: Reconstruct PCES files from block stream ─────────────────────────

# resolve_pces_dir locates the single date leaf replay-pces should consume and
# sets ACTUAL_PCES_DIR. It runs after step 2 produces the files, and also on a
# cold entry to step 3 when step 2 was skipped this invocation.
#
# blocks-to-pces writes .pces files into a date-stamped leaf (<pces-root>/YYYY/MM/DD) and a
# correctness-critical sidecar (stale-parents.txt) at the *root* of the PCES tree. replay-pces
# resolves that sidecar by walking a fixed number of directories up from --pces-dir (the leaf
# is exactly three levels below the root), and it stages only the direct .pces children of the
# directory it's given. A run that crosses midnight (or a month/year rollover) writes into two
# sibling leaves, which breaks staging: replay would receive only one leaf's events.
#
# Do NOT flatten to a shallower directory — that would move --pces-dir off the leaf depth the
# sidecar resolution depends on, so a non-empty stale-parents.txt would silently stop loading
# and the orphan buffer would deadlock. Instead, consolidate into a single real date leaf at the
# original depth: pick the first leaf and move every other leaf's .pces files into it. This keeps
# the exact directory shape replay-pces expects (sidecar still resolves, staging finds the full
# set) while guaranteeing the complete event sequence. Filenames carry a monotonic sequence
# number, so they are unique within a run and never collide when merged. The merge is idempotent:
# a prior step-2 run already collapsed the leaves, so a re-entry finds one leaf and moves nothing.
resolve_pces_dir() {
    if [[ ! -d "${OUT}/${PCES_SUBDIR}" ]]; then
        error "Expected PCES output directory not found: ${OUT}/${PCES_SUBDIR}"
        error "It is produced by step 2 (blocks-to-pces); run that step or include it in STEPS."
        exit 1
    fi
    mapfile -t pces_leaves < <(find "${OUT}/${PCES_SUBDIR}" -type f -name '*.pces' -printf '%h\n' | sort -u)
    if [[ "${#pces_leaves[@]}" -eq 0 ]]; then
        error "No .pces files found under ${OUT}/${PCES_SUBDIR}"
        exit 1
    fi
    ACTUAL_PCES_DIR="${pces_leaves[0]}"
    local leaf
    for leaf in "${pces_leaves[@]:1}"; do
        info "  Merging PCES files from ${leaf} into ${ACTUAL_PCES_DIR} (conversion spanned multiple dates)"
        find "${leaf}" -maxdepth 1 -type f -name '*.pces' -exec mv -t "${ACTUAL_PCES_DIR}" {} +
    done
    local pces_count
    pces_count=$(find "${ACTUAL_PCES_DIR}" -maxdepth 1 -type f -name '*.pces' | wc -l)
    info "  Using PCES leaf ${ACTUAL_PCES_DIR} (${pces_count} file(s))"
}

if should_run_step 2; then
    info "=== Step 2: blocks-to-pces (origin=${ORIGIN_ROUND}, target=${TARGET_ROUND}) ==="

    mkdir -p "${OUT}"

    java --enable-native-access=ALL-UNNAMED \
        --sun-misc-unsafe-memory-access=allow \
        -jar "${JAR}" blocks-to-pces \
        --block-stream-dir  "${BLOCK_STREAM_DIR}" \
        --origin-round      "${ORIGIN_ROUND}" \
        --target-round      "${TARGET_ROUND}" \
        --out               "${OUT}" \
        --billing-project   "${BILLING_PROJECT}"

    resolve_pces_dir
else
    info "=== Step 2: skipped (STEPS=${STEPS}) ==="
fi

# ─── Step 3: Replay PCES ──────────────────────────────────────────────────────

# resolve_block_output_dir locates the block-<selfNodeAccountId> leaf replay-pces
# wrote under ${REPLAY_OUT}/blockstream, so a different self-id still resolves. An
# explicit BLOCK_OUTPUT_DIR env still wins. Sets BLOCK_OUTPUT_DIR.
resolve_block_output_dir() {
    BLOCK_OUTPUT_DIR="${BLOCK_OUTPUT_DIR:-$(find "${REPLAY_OUT}/blockstream" -maxdepth 1 -type d -name 'block-*' 2>/dev/null | head -1)}"
    if [[ -z "${BLOCK_OUTPUT_DIR}" ]]; then
        error "No block output found under ${REPLAY_OUT}/blockstream"
        error "It is produced by step 3 (replay-pces); run that step or include it in STEPS."
        exit 1
    fi
}

if should_run_step 3; then
    info "=== Step 3: replay-pces ==="

    require_dir "${ORIGIN_STATE_DIR}" "produced by step 1; include step 1 in STEPS or provide the snapshot"
    # If step 2 didn't run this invocation, ACTUAL_PCES_DIR isn't set yet — resolve it
    # from the PCES files an earlier step 2 left on disk (also re-consolidates if needed).
    [[ -n "${ACTUAL_PCES_DIR:-}" ]] || resolve_pces_dir

    # Point every platform-managed stream directory under ${REPLAY_OUT} instead of the production
    # default (/opt/hgcapp, root-owned and unwritable on a CI runner). These mirror the system
    # properties ReplayPcesCommand would otherwise set in code; driving them from here as -D flags
    # keeps the command at production defaults for standalone use. --out is aligned with the same base
    # so the values agree whether or not the command also sets them itself. buildPlatformConfig() reads
    # system properties (SystemPropertiesConfigSource), so -D takes effect at platform build time.
    #   * allowUnsignedPcesEvents / forceMockSignatures / forceIgnorePcesSignatures — required for the
    #     unsigned reconstructed events and offline (no live TSS) signing to pass intake.
    java --enable-native-access=ALL-UNNAMED \
        --sun-misc-unsafe-memory-access=allow \
        -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
        -Devent.preconsensus.intake.allowUnsignedPcesEvents=true \
        -Dtss.forceMockSignatures=true \
        -Dpces.forceIgnorePcesSignatures=true \
        -Devent.preconsensus.copyRecentStreamToStateSnapshots=false \
        -Devent.preconsensus.limitReplayFrequency=false \
        -Dstate.saveStatePeriod=3600 \
        -DblockStream.writerMode=FILE \
        -DblockStream.blockFileDir="${REPLAY_OUT}/blockstream" \
        -Devent.eventsLogDir="${REPLAY_OUT}/eventsStreams" \
        -Devent.enableEventStreaming=false \
        -Dhedera.recordStream.logDir="${REPLAY_OUT}" \
        -Dhedera.recordStream.sidecarDir="${REPLAY_OUT}" \
        -jar "${JAR}" replay-pces \
        --state-dir "${ORIGIN_STATE_DIR}" \
        --pces-dir  "${ACTUAL_PCES_DIR}" \
        --out       "${REPLAY_OUT}" \
        --target-round  "${TARGET_ROUND}"

    resolve_block_output_dir

    if [[ -z "$(find "${BLOCK_OUTPUT_DIR}" -name '*.blk.gz' -newer "${JAR}" 2>/dev/null | head -1)" ]]; then
        info "  Warning: no new block files detected in ${BLOCK_OUTPUT_DIR}. Check replay-pces logs."
    fi

    info "  Block files available at ${BLOCK_OUTPUT_DIR}"
else
    info "=== Step 3: skipped (STEPS=${STEPS}) ==="
fi

# ─── Step 4: Apply blocks to origin state ─────────────────────────────────────

if should_run_step 4; then
    info "=== Step 4: apply-blocks (target-round=${TARGET_ROUND}) ==="

    require_dir "${ORIGIN_STATE_DIR}" "produced by step 1; include step 1 in STEPS or provide the snapshot"
    # If step 3 didn't run this invocation, BLOCK_OUTPUT_DIR isn't set — resolve it
    # from the blocks an earlier step 3 left under ${REPLAY_OUT}/blockstream.
    [[ -n "${BLOCK_OUTPUT_DIR:-}" ]] || resolve_block_output_dir

    # Remove any zero-sized blk.gz files that can break apply-blocks. This is a
    # cosmetic pre-clean, so tolerate a hiccup (with `pipefail`, a non-zero find —
    # e.g. `-delete` misbehaving on a drvfs/WSL mount under /mnt/c — would otherwise
    # promote to a fatal error and abort the whole run) rather than failing here.
    deleted=$(find "${BLOCK_OUTPUT_DIR}" -type f -name "*.blk.gz" -size 0 -print -delete 2>/dev/null | wc -l) || deleted=0
    if [[ "${deleted}" -gt 0 ]]; then
        info "Removed ${deleted} zero-sized blk.gz file(s)"
    fi

    mkdir -p "${RESULTING_STATE}"

    java --enable-native-access=ALL-UNNAMED \
        --sun-misc-unsafe-memory-access=allow \
        -jar "${JAR}" "${ORIGIN_STATE_DIR}" apply-blocks \
        --block-stream-dir  "${BLOCK_OUTPUT_DIR}" \
        --node-id           "${NODE_ID}" \
        --target-round      "${TARGET_ROUND}" \
        --out               "${RESULTING_STATE}"

    info "  Resulting state written to ${RESULTING_STATE}"
else
    info "=== Step 4: skipped (STEPS=${STEPS}) ==="
fi

# ─── Step 5: Diff resulting state against expected state ──────────────────────

if should_run_step 5; then
    info "=== Step 5: diff (expected=${TARGET_STATE_DIR}, resulting=${RESULTING_STATE}) ==="

    require_dir "${TARGET_STATE_DIR}" "produced by step 1; include step 1 in STEPS or provide the snapshot"
    require_dir "${RESULTING_STATE}"  "produced by step 4; include step 4 in STEPS"

    # A non-zero exit here is expected (1 = states differ) and handled below, so
    # place the command in an exempt position (`|| …`) — this keeps both `set -e`
    # and the ERR trap from firing on it, without toggling `set +e/-e`.
    DIFF_EXIT=0
    java  --enable-native-access=ALL-UNNAMED \
        --sun-misc-unsafe-memory-access=allow \
        -jar "${JAR}" "${TARGET_STATE_DIR}" diff "${RESULTING_STATE}" \
        --out           "./" \
        --ignore-field  "${IGNORE_FIELDS}" || DIFF_EXIT=$?

    case "${DIFF_EXIT}" in
        0)
            info "=== RESULT: SUCCESS — states are equivalent ==="
            exit 0
            ;;
        1)
            error "=== RESULT: FAILURE — states differ (see diff output above) ==="
            exit 1
            ;;
        *)
            error "=== RESULT: ERROR — diff command exited with unexpected code ${DIFF_EXIT} ==="
            exit "${DIFF_EXIT}"
            ;;
    esac
else
    info "=== Step 5: skipped (STEPS=${STEPS}) ==="
fi