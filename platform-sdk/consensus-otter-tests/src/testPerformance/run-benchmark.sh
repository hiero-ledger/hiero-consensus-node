#!/usr/bin/env bash
set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
RESULTS_BASE_DIR="$HOME/benchmark-results"

# Parse number of runs (default to 1)
NUM_RUNS=${1:-1}

if ! [[ "$NUM_RUNS" =~ ^[0-9]+$ ]] || [ "$NUM_RUNS" -lt 1 ]; then
    echo "Usage: $0 [num_runs]"
    echo "  num_runs: Number of times to run the benchmark (default: 1)"
    exit 1
fi

echo "=== Configuration ==="
echo "Number of runs: $NUM_RUNS"
echo ""

echo "=== Step b: Assembling project (one-time) ==="
cd "$PROJECT_DIR"
./gradlew assemble

# Run benchmark multiple times
for RUN in $(seq 1 "$NUM_RUNS"); do
    echo ""
    echo "=========================================="
    echo "=== Run $RUN of $NUM_RUNS ==="
    echo "=========================================="

    LOG_FILE="/tmp/benchmark-run${RUN}-$(date +%Y%m%d-%H%M%S).log"

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
        # metrics.txt -> stats/metrics-node-X.txt
        [[ -f "$NODE_DIR/data/stats/metrics.txt" ]] && cp "$NODE_DIR/data/stats/metrics.txt" "$STATS_DIR/metrics-${NODE_NAME}.txt"
        echo "  Copied artifacts for $NODE_NAME"
    done

    echo "=== Run $RUN completed ==="
    echo "Log:     $LOG_FILE"
    echo "Results: $DEST_DIR"
done

echo ""
echo "=========================================="
echo "=== All runs completed ($NUM_RUNS total) ==="
echo "=========================================="
echo "Results directory: $RESULTS_BASE_DIR"