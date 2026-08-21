#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs the full LongList snapshot campaign, writing about 33 TB cumulatively across all configurations.
# One fixture is reused for every configuration at a leaf count and then deleted, bounding peak disk usage.
# Raw results and environment metadata are retained.
# Run check-long-list-snapshot-benchmark-system.sh first to review the recommended system resources.

# Build, benchmark, and result-writing failures stop the campaign.
set -euo pipefail

if (( $# != 0 )); then
    echo "Usage: $0" >&2
    exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
REPO_ROOT="$(git -C "${MODULE_DIR}" rev-parse --show-toplevel)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"

# Scratch contains reusable fixtures and is always deleted; results are retained under a unique run ID.
SCRATCH_PARENT="${MODULE_DIR}/build/tmp/long-list-snapshot-campaign"
RESULTS_PARENT="${MODULE_DIR}/build/results/jmh/long-list-snapshot-campaign"
RESULTS_DIR="${RESULTS_PARENT}/${RUN_ID}"
ARCHIVE="${RESULTS_PARENT}/${RUN_ID}.tar.gz"

mkdir -p "${SCRATCH_PARENT}" "${RESULTS_DIR}"
SCRATCH_DIR="$(mktemp -d "${SCRATCH_PARENT}/run.XXXXXX")"

remove_scratch_directory() {
    local directory="$1"
    if [[ "${directory}" != "${SCRATCH_PARENT}/"* ]]; then
        echo "Refusing to remove path outside benchmark scratch: ${directory}" >&2
        exit 1
    fi
    rm -rf -- "${directory}"
}

cleanup() {
    if [[ -d "${SCRATCH_DIR}" ]]; then
        remove_scratch_directory "${SCRATCH_DIR}"
    fi
}

# Remove generated fixtures after success, failure, or interruption.
trap cleanup EXIT

# Record enough source, runtime, and hardware context to reproduce and interpret the raw results.
{
    echo "Started: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Git revision: $(git -C "${REPO_ROOT}" rev-parse HEAD)"
    echo
    git -C "${REPO_ROOT}" status --short --branch
    echo
    java -version
    echo
    uname -a
    echo
    if command -v lscpu >/dev/null; then
        lscpu
    else
        echo "lscpu unavailable"
    fi
    echo
    if command -v free >/dev/null; then
        free -h
    else
        echo "free unavailable"
    fi
    echo
    df -hT "${MODULE_DIR}"
    echo
    if command -v lsblk >/dev/null; then
        lsblk
    else
        echo "lsblk unavailable"
    fi
} >"${RESULTS_DIR}/environment.txt" 2>&1
cp "${BASH_SOURCE[0]}" "${RESULTS_DIR}/runner.sh"

# Build and select the single runnable JMH artifact used by the entire campaign.
"${REPO_ROOT}/gradlew" :swirlds-merkledb:jmhJar --console=plain 2>&1 | tee "${RESULTS_DIR}/build.log"

shopt -s nullglob
jmh_jars=("${MODULE_DIR}"/build/libs/swirlds-merkledb-*-jmh.jar)
shopt -u nullglob
if (( ${#jmh_jars[@]} != 1 )); then
    echo "Expected one MerkleDB JMH JAR, found ${#jmh_jars[@]}" >&2
    exit 1
fi
JMH_JAR="${jmh_jars[0]}"

# Repeat the matrix in three parameter orders to reduce bias from machine drift over the long campaign.
# Chunk sizes are 2^18, the production default 2^20, and 2^22 longs.
LEAF_COUNTS=(10000000 100000000 1000000000 5000000000)
BLOCKS=(A B C)

set_block_order() {
    case "$1" in
        A)
            IMPLEMENTATIONS="LongListHeap,LongListOffHeap,LongListSegment,LongListDisk,LongListDiskSegment"
            CHUNK_SIZES=(262144 1048576 4194304)
            ;;
        B)
            IMPLEMENTATIONS="LongListDiskSegment,LongListDisk,LongListSegment,LongListOffHeap,LongListHeap"
            CHUNK_SIZES=(4194304 1048576 262144)
            ;;
        C)
            IMPLEMENTATIONS="LongListOffHeap,LongListDiskSegment,LongListHeap,LongListSegment,LongListDisk"
            CHUNK_SIZES=(1048576 4194304 262144)
            ;;
    esac
}

# Return the comma-separated thread counts JMH should run for this leaf count and chunk size.
thread_values() {
    local leaf_count="$1"
    local chunk_size="$2"
    local block="$3"
    local threads=()

    # Settings above the active chunk count repeat the same effective concurrency.
    # P=10 and P=24 are the even near-maximum cases.
    case "${leaf_count}:${chunk_size}" in
        10000000:1048576) threads=(1 2 8 10) ;;       # 11 active chunks
        10000000:4194304) threads=(1 2) ;;            # 3 active chunks
        100000000:4194304) threads=(1 2 8 16 24) ;;   # 25 active chunks
        *) threads=(1 2 8 16 32) ;;
    esac

    local ordered=()
    local index
    case "${block}" in
        A)
            ordered=("${threads[@]}")
            ;;
        B)
            for ((index = ${#threads[@]} - 1; index >= 0; index--)); do
                ordered+=("${threads[index]}")
            done
            ;;
        C)
            local midpoint
            midpoint=$((${#threads[@]} / 2))
            ordered=("${threads[@]:midpoint}" "${threads[@]:0:midpoint}")
            ;;
    esac

    local IFS=,
    echo "${ordered[*]}"
}

# Scale JVM memory with the N * 8-byte dense source index; these limits are not benchmark parameters.
jvm_args() {
    local leaf_count="$1"
    if (( leaf_count <= 100000000 )); then
        echo "-Xms512m -Xmx4g -XX:MaxDirectMemorySize=4g"
    elif (( leaf_count <= 1000000000 )); then
        echo "-Xms512m -Xmx16g -XX:MaxDirectMemorySize=16g"
    else
        echo "-Xms512m -Xmx48g -XX:MaxDirectMemorySize=48g"
    fi
}

# The first JMH fork creates the fixture; every remaining configuration for that leaf count reuses it.
for leaf_count in "${LEAF_COUNTS[@]}"; do
    size_directory="${SCRATCH_DIR}/leaves-${leaf_count}"
    mkdir "${size_directory}"
    java_options="$(jvm_args "${leaf_count}")"

    for block in "${BLOCKS[@]}"; do
        set_block_order "${block}"
        for chunk_size in "${CHUNK_SIZES[@]}"; do
            threads="$(thread_values "${leaf_count}" "${chunk_size}" "${block}")"
            result_name="leaves-${leaf_count}-chunk-${chunk_size}-block-${block}"

            echo
            echo "Running ${result_name}, threadsPerLongList={${threads}}"
            # One fork performs one warmup and two measured single-shot writes, saving JSON and a readable log.
            java -jar "${JMH_JAR}" LongListSnapshotBenchmark.writeToFile \
                -p "listImpl=${IMPLEMENTATIONS}" \
                -p "threadsPerLongList=${threads}" \
                -p "leafCount=${leaf_count}" \
                -p "longListChunkSize=${chunk_size}" \
                -p "workDir=${size_directory}" \
                -p "verify=false" \
                -t 1 \
                -bm ss \
                -tu ms \
                -wi 1 \
                -i 2 \
                -f 1 \
                -to 60m \
                -foe true \
                -rf json \
                -rff "${RESULTS_DIR}/${result_name}.json" \
                -jvmArgs "${java_options}" \
                2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
        done
    done

    # All configurations for this leaf count are complete; delete its fixture to bound peak disk usage.
    remove_scratch_directory "${size_directory}"
done

# Archive the retained results, environment metadata, and exact runner; the trap removes scratch data.
tar -C "${RESULTS_PARENT}" -czf "${ARCHIVE}" "${RUN_ID}"
echo
echo "Results archive: ${ARCHIVE}"
