#!/usr/bin/env bash
set -eo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
RESULTS_BASE_DIR="$HOME/benchmark-results"
CLEAN_BETWEEN_RUNS=true

# Available experiments (name:ClassName pairs)
EXPERIMENTS="
maxotherparents:MaxOtherParentsExperiment
antiselfishness:AntiSelfishnessExperiment
maxcreationrate:MaxCreationRateExperiment
signature:SignatureSchemeExperiment
combined:CombinedOptimizationsExperiment
benchmark:ConsensusLayerBenchmark
"

# Parse arguments
EXPERIMENT_NAME="${1:-all}"
NUM_RUNS="${2:-1}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to get class name for experiment
get_class_name() {
    local exp_name="$1"
    echo "$EXPERIMENTS" | grep "^${exp_name}:" | cut -d: -f2 | tr -d ' '
}

# Function to get all experiment names
get_all_experiment_names() {
    echo "$EXPERIMENTS" | grep -v '^$' | cut -d: -f1 | tr -d ' '
}

# Validate experiment name
if [[ "$EXPERIMENT_NAME" != "all" ]]; then
    CLASS_NAME=$(get_class_name "$EXPERIMENT_NAME")
    if [[ -z "$CLASS_NAME" ]]; then
        echo "Error: Invalid experiment name: $EXPERIMENT_NAME"
        echo ""
        echo "Usage: $0 [experiment] [num_runs]"
        echo "  experiment: Which experiment to run (default: all)"
        echo "    - maxotherparents: Test maxOtherParents configuration"
        echo "    - antiselfishness: Test antiSelfishnessFactor configuration"
        echo "    - maxcreationrate: Test maxCreationRate configuration"
        echo "    - signature: Test signature scheme (RSA vs ED25519)"
        echo "    - combined: Test all optimizations together"
        echo "    - all: Run all experiments"
        echo "  num_runs: Number of times to run the experiment(s) (default: 1)"
        exit 1
    fi
fi

# Validate num_runs
if ! [[ "$NUM_RUNS" =~ ^[0-9]+$ ]] || [ "$NUM_RUNS" -lt 1 ]; then
    echo "Error: num_runs must be a positive integer"
    echo ""
    echo "Usage: $0 [experiment] [num_runs]"
    exit 1
fi

# Determine which experiments to run
if [[ "$EXPERIMENT_NAME" == "all" ]]; then
    EXPERIMENTS_TO_RUN=($(get_all_experiment_names))
else
    EXPERIMENTS_TO_RUN=("$EXPERIMENT_NAME")
fi

echo -e "${BLUE}=== Configuration ===${NC}"
echo "Experiment(s): ${EXPERIMENTS_TO_RUN[*]}"
echo "Number of runs per experiment: $NUM_RUNS"
echo "Results directory: $RESULTS_BASE_DIR"
echo "Clean between runs: $CLEAN_BETWEEN_RUNS"
echo ""

echo -e "${BLUE}=== Assembling project (one-time) ===${NC}"
cd "$PROJECT_DIR"
if ! ./gradlew assemble; then
    echo -e "${RED}ERROR: Project assembly failed${NC}"
    exit 1
fi

# Track overall progress
TOTAL_EXPERIMENTS=${#EXPERIMENTS_TO_RUN[@]}
TOTAL_RUNS=$((TOTAL_EXPERIMENTS * NUM_RUNS))
CURRENT_RUN=0
OVERALL_START=$(date +%s)

# Run experiments
for EXPERIMENT in "${EXPERIMENTS_TO_RUN[@]}"; do
    EXPERIMENT_CLASS=$(get_class_name "$EXPERIMENT")

    echo ""
    echo -e "${BLUE}======================================================================${NC}"
    echo -e "${BLUE}=== Experiment: $EXPERIMENT (${EXPERIMENT_CLASS}) ===${NC}"
    echo -e "${BLUE}======================================================================${NC}"

    # Run this experiment multiple times
    for RUN in $(seq 1 "$NUM_RUNS"); do
        CURRENT_RUN=$((CURRENT_RUN + 1))
        RUN_START=$(date +%s)

        echo ""
        echo -e "${GREEN}######################################################################${NC}"
        echo -e "${GREEN}=== Run $CURRENT_RUN of $TOTAL_RUNS (Experiment: $EXPERIMENT, iteration $RUN/$NUM_RUNS) ===${NC}"
        echo -e "${GREEN}######################################################################${NC}"

        # Clean container directory if requested
        if [[ "$CLEAN_BETWEEN_RUNS" == "true" ]]; then
            CONTAINER_DIR="${PROJECT_DIR}/platform-sdk/consensus-otter-tests/build/container/${EXPERIMENT_CLASS}"
            if [[ -d "$CONTAINER_DIR" ]]; then
                echo -e "${YELLOW}Cleaning previous results from $CONTAINER_DIR${NC}"
                rm -rf "$CONTAINER_DIR"
            fi
        fi

        LOG_FILE="/tmp/benchmark-${EXPERIMENT}-run${RUN}-$(date +%Y%m%d-%H%M%S).log"

        echo "=== Running performance tests for ${EXPERIMENT_CLASS} ==="
        echo "Log file: $LOG_FILE"

        if ! ./gradlew :consensus-otter-tests:testPerformance --tests "*${EXPERIMENT_CLASS}" 2>&1 | tee "$LOG_FILE"; then
            echo -e "${RED}ERROR: Test execution failed for ${EXPERIMENT_CLASS}${NC}"
            echo "Check log file: $LOG_FILE"
            continue
        fi

        echo ""
        echo "=== Processing results from container directories ==="

        CONTAINER_DIR="${PROJECT_DIR}/platform-sdk/consensus-otter-tests/build/container/${EXPERIMENT_CLASS}"

        if [[ ! -d "$CONTAINER_DIR" ]]; then
            echo -e "${RED}ERROR: Container directory not found: $CONTAINER_DIR${NC}"
            echo "Test may have failed - check log: $LOG_FILE"
            continue
        fi

        # Count test directories
        TEST_COUNT=$(find "$CONTAINER_DIR" -mindepth 1 -maxdepth 1 -type d | wc -l)
        echo "Found $TEST_COUNT test result(s)"

        # Process each test directory
        TEST_NUM=0
        for TEST_DIR in "$CONTAINER_DIR"/*; do
            if [[ ! -d "$TEST_DIR" ]]; then
                continue
            fi

            TEST_NUM=$((TEST_NUM + 1))
            TEST_NAME=$(basename "$TEST_DIR")
            echo ""
            echo -e "${YELLOW}--- Processing $TEST_NUM/$TEST_COUNT: $TEST_NAME ---${NC}"

            # Extract avg value for this test from log
            # Look for "[testName] Benchmark complete" followed by avg line
            AVG_VALUE=$(awk -v test="$TEST_NAME" '
                $0 ~ "\\[" test "\\] Benchmark complete" { in_test = 1; next }
                in_test && /^# Copy below for spreadsheet/ { found = 1; next }
                found && /^Avg/ { next }
                found && NF > 0 { print $1; exit }
            ' "$LOG_FILE")

            if [[ -z "$AVG_VALUE" ]]; then
                echo -e "${YELLOW}WARNING: Could not find avg value for $TEST_NAME${NC}"
                AVG_VALUE="UNKNOWN"
            else
                echo -e "${GREEN}Avg: $AVG_VALUE μs${NC}"
            fi

            # Create results directory (experiment name appended to folder)
            TIMESTAMP=$(date +%Y%m%d-%H%M%S)
            DEST_DIR="${RESULTS_BASE_DIR}/${TIMESTAMP}_${TEST_NAME}_${EXPERIMENT}_avg-${AVG_VALUE}"
            mkdir -p "$DEST_DIR"
            STATS_DIR="${DEST_DIR}/stats"
            mkdir -p "$STATS_DIR"

            # Copy artifacts with progress
            ARTIFACT_COUNT=0
            for NODE_DIR in "$TEST_DIR"/node-*; do
                [[ ! -d "$NODE_DIR" ]] && continue
                NODE_NAME=$(basename "$NODE_DIR")
                if [[ -f "$NODE_DIR/output/swirlds.log" ]]; then
                    cp "$NODE_DIR/output/swirlds.log" "$DEST_DIR/swirlds-${NODE_NAME}.log"
                    ((ARTIFACT_COUNT++))
                fi
                if [[ -f "$NODE_DIR/output/otter.log" ]]; then
                    cp "$NODE_DIR/output/otter.log" "$DEST_DIR/otter-${NODE_NAME}.log"
                    ((ARTIFACT_COUNT++))
                fi
                for CSV in "$NODE_DIR"/data/stats/MainNetStats*.csv; do
                    if [[ -f "$CSV" ]]; then
                        cp "$CSV" "$STATS_DIR/"
                        ((ARTIFACT_COUNT++))
                    fi
                done
                if [[ -f "$NODE_DIR/data/stats/metrics.txt" ]]; then
                    cp "$NODE_DIR/data/stats/metrics.txt" "$STATS_DIR/metrics-${NODE_NAME}.txt"
                    ((ARTIFACT_COUNT++))
                fi
            done

            # Copy log
            cp "$LOG_FILE" "$DEST_DIR/benchmark.log"

            echo "Copied $ARTIFACT_COUNT artifact(s)"
            echo -e "${GREEN}Saved: $DEST_DIR${NC}"
        done

        # Calculate run time
        RUN_END=$(date +%s)
        RUN_TIME=$((RUN_END - RUN_START))
        echo ""
        echo -e "${GREEN}=== Run $RUN completed for experiment: $EXPERIMENT (took ${RUN_TIME}s) ===${NC}"
    done

    echo ""
    echo -e "${BLUE}=== Experiment $EXPERIMENT completed ===${NC}"
done

# Calculate total time
OVERALL_END=$(date +%s)
OVERALL_TIME=$((OVERALL_END - OVERALL_START))
OVERALL_MINUTES=$((OVERALL_TIME / 60))
OVERALL_SECONDS=$((OVERALL_TIME % 60))

echo ""
echo -e "${GREEN}######################################################################${NC}"
echo -e "${GREEN}=== All experiments completed ===${NC}"
echo -e "${GREEN}######################################################################${NC}"
echo "Total runs: $TOTAL_RUNS"
echo "Total time: ${OVERALL_MINUTES}m ${OVERALL_SECONDS}s"
echo "Results directory: $RESULTS_BASE_DIR"
echo ""
echo -e "${BLUE}Summary by experiment:${NC}"
for EXPERIMENT in "${EXPERIMENTS_TO_RUN[@]}"; do
    RESULT_COUNT=$(find "$RESULTS_BASE_DIR" -mindepth 1 -maxdepth 1 -type d -name "*_${EXPERIMENT}_*" 2>/dev/null | wc -l || echo 0)
    echo "  $EXPERIMENT: $RESULT_COUNT result(s)"
done