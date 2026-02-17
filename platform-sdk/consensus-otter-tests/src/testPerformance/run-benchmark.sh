#!/usr/bin/env bash
set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
RESULTS_BASE_DIR="$HOME/benchmark-results"
BENCHMARK_CLASS="ConsensusLayerBenchmark"

# Parse number of runs (default to 1)
NUM_RUNS=${1:-1}

if ! [[ "$NUM_RUNS" =~ ^[0-9]+$ ]] || [ "$NUM_RUNS" -lt 1 ]; then
    echo "Usage: $0 [num_runs]"
    echo "  num_runs: Number of times to run all benchmarks (default: 1)"
    exit 1
fi

echo "=== Configuration ==="
echo "Number of runs: $NUM_RUNS"
echo ""

echo "=== Assembling project (one-time) ==="
cd "$PROJECT_DIR"
./gradlew assemble

# Run benchmark multiple times
for RUN in $(seq 1 "$NUM_RUNS"); do
    echo ""
    echo "######################################################################"
    echo "=== Run $RUN of $NUM_RUNS ==="
    echo "######################################################################"

    LOG_FILE="/tmp/benchmark-run${RUN}-$(date +%Y%m%d-%H%M%S).log"

    echo "=== Running all performance tests ==="
    echo "Log file: $LOG_FILE"
    ./gradlew :consensus-otter-tests:testPerformance --rerun 2>&1 | tee "$LOG_FILE"

    echo ""
    echo "=== Processing results from container directories ==="

    CONTAINER_DIR="${PROJECT_DIR}/platform-sdk/consensus-otter-tests/build/container/${BENCHMARK_CLASS}"

    if [[ ! -d "$CONTAINER_DIR" ]]; then
        echo "ERROR: Container directory not found: $CONTAINER_DIR"
        exit 1
    fi

    # Process each test directory
    for TEST_DIR in "$CONTAINER_DIR"/*; do
        if [[ ! -d "$TEST_DIR" ]]; then
            continue
        fi

        TEST_NAME=$(basename "$TEST_DIR")
        echo ""
        echo "--- Processing: $TEST_NAME ---"

        # Extract avg value for this test from log
        # Look for "[testName] Benchmark complete" followed by avg line
        AVG_VALUE=$(awk -v test="$TEST_NAME" '
            $0 ~ "\\[" test "\\] Benchmark complete" { in_test = 1; next }
            in_test && /^# Copy below for spreadsheet/ { found = 1; next }
            found && /^Avg/ { next }
            found && NF > 0 { print $1; exit }
        ' "$LOG_FILE")

        if [[ -z "$AVG_VALUE" ]]; then
            echo "WARNING: Could not find avg value for $TEST_NAME"
            AVG_VALUE="UNKNOWN"
        else
            echo "Avg: $AVG_VALUE μs"
        fi

        # Create results directory
        TIMESTAMP=$(date +%Y%m%d-%H%M%S)
        DEST_DIR="${RESULTS_BASE_DIR}/${TIMESTAMP}_${TEST_NAME}_avg-${AVG_VALUE}"
        mkdir -p "$DEST_DIR"
        STATS_DIR="${DEST_DIR}/stats"
        mkdir -p "$STATS_DIR"

        # Copy artifacts
        for NODE_DIR in "$TEST_DIR"/node-*; do
            [[ ! -d "$NODE_DIR" ]] && continue
            NODE_NAME=$(basename "$NODE_DIR")
            [[ -f "$NODE_DIR/output/swirlds.log" ]] && cp "$NODE_DIR/output/swirlds.log" "$DEST_DIR/swirlds-${NODE_NAME}.log"
            [[ -f "$NODE_DIR/output/otter.log" ]] && cp "$NODE_DIR/output/otter.log" "$DEST_DIR/otter-${NODE_NAME}.log"
            for CSV in "$NODE_DIR"/data/stats/MainNetStats*.csv; do
                [[ -f "$CSV" ]] && cp "$CSV" "$STATS_DIR/"
            done
            [[ -f "$NODE_DIR/data/stats/metrics.txt" ]] && cp "$NODE_DIR/data/stats/metrics.txt" "$STATS_DIR/metrics-${NODE_NAME}.txt"
        done

        # Copy log
        cp "$LOG_FILE" "$DEST_DIR/benchmark.log"

        echo "Saved: $DEST_DIR"
    done

    echo ""
    echo "=== Run $RUN completed ==="
done

echo ""
echo "######################################################################"
echo "=== All runs completed ==="
echo "######################################################################"
echo "Results directory: $RESULTS_BASE_DIR"