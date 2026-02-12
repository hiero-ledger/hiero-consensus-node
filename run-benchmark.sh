#!/usr/bin/env bash
set -euo pipefail

# Configuration
# For local testing, PROJECT_DIR points to the local repo.
# When switching to SSH, wrap the remote commands in: ssh maxi@186.233.187.171 '...'
PROJECT_DIR="."
LOG_FILE="/tmp/benchmark-$(date +%Y%m%d-%H%M%S).log"
RESULTS_BASE_DIR="$HOME/benchmark-results"

echo "=== Step b: Assembling project (one-time) ==="
cd "$PROJECT_DIR"
./gradlew assemble

echo "=== Step c: Running performance test ==="
echo "Log file: $LOG_FILE"
./gradlew :consensus-otter-tests:testPerformance --rerun 2>&1 | tee "$LOG_FILE"

echo "=== Step d: Extracting Avg value from results ==="
# Find the data line right after the TSV header and grab the first number (Avg)
AVG_VALUE=$(awk '
    /^# Copy below for spreadsheet/ { found=1; next }
    found && /^Avg/ { next }
    found { print $1; exit }
' "$LOG_FILE")

if [[ -z "$AVG_VALUE" ]]; then
    echo "ERROR: Could not find benchmark Avg value in log. Check $LOG_FILE"
    exit 1
fi
echo "Avg value found: $AVG_VALUE"

echo "=== Step e: Copying selected artifacts ==="
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
DEST_DIR="${RESULTS_BASE_DIR}/${TIMESTAMP}_avg-${AVG_VALUE}"
mkdir -p "$DEST_DIR"

BENCHMARK_DIR="${PROJECT_DIR}/platform-sdk/consensus-otter-tests/build/container/ConsensusLayerBenchmark/benchmark"
STATS_DIR="${DEST_DIR}/stats"
mkdir -p "$STATS_DIR"

for NODE_DIR in "$BENCHMARK_DIR"/node-*; do
    NODE_NAME=$(basename "$NODE_DIR")
    # swirlds.log -> swirlds-node-X.log
    [[ -f "$NODE_DIR/output/swirlds.log" ]] && cp "$NODE_DIR/output/swirlds.log" "$DEST_DIR/swirlds-${NODE_NAME}.log"
    # otter.log -> otter-node-X.log
    [[ -f "$NODE_DIR/output/otter.log" ]] && cp "$NODE_DIR/output/otter.log" "$DEST_DIR/otter-${NODE_NAME}.log"
    # MainNetStats*.csv -> stats/ (keep original name, already contains node id)
    for CSV in "$NODE_DIR"/data/stats/MainNetStats*.csv; do
        [[ -f "$CSV" ]] && cp "$CSV" "$STATS_DIR/"
    done
    echo "  Copied artifacts for $NODE_NAME"
done

echo "=== Step f: Generating combined stats PDF ==="
RTRN_DIR=$(pwd)
cd "$DEST_DIR"
fsts_insight -r . `find stats -type f -name 'MainNetStats*.csv' -print | perl -pne '~s/\bstats/ -f stats/g; ~s/\n//g;'` -o stats/combined.pdf
echo "PDF generated: $STATS_DIR/combined.pdf"
cd $RTRN_DIR

echo "=== Done ==="
echo "Log:     $LOG_FILE"
echo "Results: $DEST_DIR"