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
# All parameters can be overridden by setting environment variables before
# running the script. Example:
#   ORIGIN_ROUND=211155071 TARGET_ROUND=211422945 ./validate-replay.sh

set -euo pipefail

#  Parameters


ORIGIN_ROUND="${ORIGIN_ROUND:-4211110}"
TARGET_ROUND="${TARGET_ROUND:-4250525}"

JAR="${JAR:-./hedera-state-validator-0.77.jar}"

# GCP block stream source (gs:// URI or local path)
BLOCK_STREAM_DIR="${BLOCK_STREAM_DIR:-gs://hedera-preview-testnet-streams/block-preview/previewnet-2026-07-22T22:19/0/0}"
#BLOCK_STREAM_DIR="${BLOCK_STREAM_DIR:-state-validator-blocks-253689003-to-254469719-rna26-s0}"
BILLING_PROJECT="${BILLING_PROJECT:-hedera-regression}"

# GCP bucket from which state snapshots are downloaded.
# The snapshot for round R is expected at <SNAPSHOT_BUCKET>/R.
# Set to empty string to skip download (if snapshots are already on disk).
SNAPSHOT_BUCKET="${SNAPSHOT_BUCKET:-gs://preview-testnet-backup/previewnet-node00}"

# Where replay-pces writes block stream files
BLOCK_OUTPUT_DIR="${BLOCK_OUTPUT_DIR:-/opt/hgcapp/blockStreams/block-0.0.3}"

NODE_ID="${NODE_ID:-0}"

# Root output directory for all artifacts produced by the script
OUT="${OUT:-./out}"

# Fields known to diverge due to unsigned-event reconstruction (legacyRunningEventHash
# and its downstream hash chain). These are structurally expected differences, not bugs.
IGNORE_FIELDS="${IGNORE_FIELDS:-trailingBlockHashes,startOfBlockStateHash,rightmostPrecedingStateChangesTreeHashes,intermediatePreviousBlockRootHashes,legacyRunningEventHash}"

# Derived paths

# replay-pces writes PCES files under a date-stamped subdirectory
TODAY="$(date +%Y/%m/%d)"
PCES_SUBDIR="pces-${ORIGIN_ROUND}-${TARGET_ROUND}"
PCES_DIR="${OUT}/${PCES_SUBDIR}/${TODAY}"

ORIGIN_STATE_DIR="./${ORIGIN_ROUND}"
TARGET_STATE_DIR="./${TARGET_ROUND}"
RESULTING_STATE="${OUT}/resulting_state"

# Helpers

info()  { echo "[INFO]  $*"; }
error() { echo "[ERROR] $*" >&2; }

require_file() {
    [[ -f "$1" ]] || { error "Required file not found: $1"; exit 1; }
}

# Preflight checks

require_file "$JAR"

if ! command -v java &>/dev/null; then
    error "java not found on PATH"
    exit 1
fi

if [[ "$BLOCK_STREAM_DIR" == gs://* ]] && ! command -v gsutil &>/dev/null; then
    error "gsutil not found on PATH (required for GCS block stream download)"
    exit 1
fi

#  Step 1: Download state snapshots

info "=== Step 1: State snapshots ==="

download_snapshot() {
    local round="$1"
    local dest="./${round}"
    if [[ -d "$dest" ]]; then
        info "  Snapshot for round ${round} already present at ${dest}, skipping download."
        return
    fi
    if [[ -z "$SNAPSHOT_BUCKET" ]]; then
        error "Snapshot for round ${round} not found at ${dest} and SNAPSHOT_BUCKET is not set."
        error "Either download the snapshot manually or set SNAPSHOT_BUCKET to a GCS URI."
        exit 1
    fi
    local src="${SNAPSHOT_BUCKET%/}/${round}"
    info "  Downloading snapshot for round ${round} from ${src} ..."
    mkdir -p "$dest"                          #  add this
    gsutil -m cp -r "$src" "$dest/"            # trailing slash is safest
    info "  Done — snapshot at ${dest}"
}
download_snapshot "$ORIGIN_ROUND"
download_snapshot "$TARGET_ROUND"

#  Step 2: Reconstruct PCES files from block stream

info "=== Step 2: blocks-to-pces (origin=${ORIGIN_ROUND}, target=${TARGET_ROUND}) ==="

mkdir -p "$OUT"

java -jar "$JAR" blocks-to-pces \
    --block-stream-dir  "$BLOCK_STREAM_DIR" \
    --origin-round      "$ORIGIN_ROUND" \
    --target-round      "$TARGET_ROUND" \
    --out               "$OUT" \
    --billing-project   "$BILLING_PROJECT"

if [[ ! -d "${OUT}/${PCES_SUBDIR}" ]]; then
    error "blocks-to-pces completed but expected output directory not found: ${OUT}/${PCES_SUBDIR}"
    exit 1
fi

# Locate the actual PCES subdirectory — the date component may differ if the
# run crosses midnight, so fall back to a glob if the expected path is absent.
if [[ -d "$PCES_DIR" ]]; then
    ACTUAL_PCES_DIR="$PCES_DIR"
else
    ACTUAL_PCES_DIR="$(find "${OUT}/${PCES_SUBDIR}" -mindepth 3 -maxdepth 3 -type d | head -1)"
    if [[ -z "$ACTUAL_PCES_DIR" ]]; then
        error "Could not locate PCES output under ${OUT}/${PCES_SUBDIR}"
        exit 1
    fi
    info "  Note: PCES directory resolved to ${ACTUAL_PCES_DIR} (date differed from ${TODAY})"
fi

info "  PCES files written to ${ACTUAL_PCES_DIR}"

#  Step 3: Replay PCES

info "=== Step 3: replay-pces ==="

java -jar "$JAR" replay-pces \
    --state-dir "$ORIGIN_STATE_DIR" \
    --pces-dir  "$ACTUAL_PCES_DIR" \
    --target-round  "$TARGET_ROUND"


if [[ -z "$(find "$BLOCK_OUTPUT_DIR" -name '*.blk.gz' -newer "$JAR" 2>/dev/null | head -1)" ]]; then
    info "  Warning: no new block files detected in ${BLOCK_OUTPUT_DIR}. Check replay-pces logs."
fi

info "  Block files available at ${BLOCK_OUTPUT_DIR}"

# -- Step 4: Apply blocks to origin state

info "=== Step 4: apply-blocks (target-round=${TARGET_ROUND}) ==="

mkdir -p "$RESULTING_STATE"

java -jar "$JAR" "$ORIGIN_STATE_DIR" apply-blocks \
    --block-stream-dir  "$BLOCK_OUTPUT_DIR" \
    --node-id           "$NODE_ID" \
    --target-round      "$TARGET_ROUND" \
    --out               "$RESULTING_STATE"

info "  Resulting state written to ${RESULTING_STATE}"

# --- Step 5: Diff resulting state against expected state

info "=== Step 5: diff (expected=${TARGET_STATE_DIR}, resulting=${RESULTING_STATE}) ==="

set +e
java -jar "$JAR" "$TARGET_STATE_DIR" diff "$RESULTING_STATE" \
    --out           "./" \
    --ignore-field  "$IGNORE_FIELDS"
DIFF_EXIT=$?
set -e

case "$DIFF_EXIT" in
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
        exit "$DIFF_EXIT"
        ;;
esac