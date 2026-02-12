#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="."
RESULTS_BASE_DIR="$HOME/benchmark-results"
NUM_RUNS=10

cd "$PROJECT_DIR"

# Step b: Assemble once
echo "=== Assembling project (one-time) ==="
./gradlew assemble

declare -a AVG_VALUES=()
declare -a DEST_DIRS=()

for i in $(seq 1 "$NUM_RUNS"); do
    echo ""
    echo "========================================="
    echo "=== Run $i of $NUM_RUNS ==="
    echo "========================================="

    LOG_FILE="/tmp/benchmark-run${i}-$(date +%Y%m%d-%H%M%S).log"

    echo "=== Running performance test ==="
    echo "Log file: $LOG_FILE"
    ./gradlew :consensus-otter-tests:testPerformance --rerun 2>&1 | tee "$LOG_FILE"

    echo "=== Extracting Avg value ==="
    AVG_VALUE=$(awk '
        /^# Copy below for spreadsheet/ { found=1; next }
        found && /^Avg/ { next }
        found { print $1; exit }
    ' "$LOG_FILE")

    if [[ -z "$AVG_VALUE" ]]; then
        echo "WARNING: Could not find Avg value in run $i. Skipping."
        continue
    fi
    echo "Run $i — Avg: $AVG_VALUE"

    TIMESTAMP=$(date +%Y%m%d-%H%M%S)
    DEST_DIR="${RESULTS_BASE_DIR}/${TIMESTAMP}_avg-${AVG_VALUE}"
    mkdir -p "$DEST_DIR"

    BENCHMARK_DIR="platform-sdk/consensus-otter-tests/build/container/ConsensusLayerBenchmark/benchmark"
    STATS_DIR="${DEST_DIR}/stats"
    mkdir -p "$STATS_DIR"

    for NODE_DIR in "$BENCHMARK_DIR"/node-*; do
        NODE_NAME=$(basename "$NODE_DIR")
        [[ -f "$NODE_DIR/output/swirlds.log" ]] && cp "$NODE_DIR/output/swirlds.log" "$DEST_DIR/swirlds-${NODE_NAME}.log"
        [[ -f "$NODE_DIR/output/otter.log" ]] && cp "$NODE_DIR/output/otter.log" "$DEST_DIR/otter-${NODE_NAME}.log"
        for CSV in "$NODE_DIR"/data/stats/MainNetStats*.csv; do
            [[ -f "$CSV" ]] && cp "$CSV" "$STATS_DIR/"
        done
    done

    RTRN_DIR=$(pwd)
    cd "$DEST_DIR"
    fsts_insight -r . $(find stats -type f -name 'MainNetStats*.csv' -print | perl -pne 's/\bstats/ -f stats/g; s/\n//g;') -o stats/combined.pdf
    cd "$RTRN_DIR"

    # Save log file into the results directory
    cp "$LOG_FILE" "$DEST_DIR/benchmark.log"

    AVG_VALUES+=("$AVG_VALUE")
    DEST_DIRS+=("$DEST_DIR")

    echo "Run $i complete. Results: $DEST_DIR"
done

echo ""
echo "========================================="
echo "=== All runs complete ==="
echo "========================================="
echo ""

# Print summary
echo "Avg values collected:"
for i in "${!AVG_VALUES[@]}"; do
    echo "  Run $((i+1)): ${AVG_VALUES[$i]}  ->  ${DEST_DIRS[$i]}"
done

echo ""
echo "=== Done ==="
echo "Results in: $RESULTS_BASE_DIR"