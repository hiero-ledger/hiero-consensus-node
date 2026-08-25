#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs the full LongList snapshot campaign, writing about 37 TB cumulatively across all configurations.
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
    if command -v findmnt >/dev/null; then
        findmnt -T "${MODULE_DIR}" -o TARGET,SOURCE,FSTYPE,OPTIONS
    else
        echo "findmnt unavailable"
    fi
    echo
    df -hT "${MODULE_DIR}"
    echo
    if command -v lsblk >/dev/null; then
        lsblk -o NAME,MODEL,SIZE,ROTA,TRAN,TYPE,MOUNTPOINTS
    else
        echo "lsblk unavailable"
    fi
} >"${RESULTS_DIR}/environment.txt" 2>&1
cp "${BASH_SOURCE[0]}" "${RESULTS_DIR}/runner.sh"

# Remove artifacts left by older project versions, then build and select the current JMH JAR.
rm -f -- "${MODULE_DIR}"/build/libs/swirlds-merkledb-*-jmh.jar
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

# Scale JVM memory with the dense source index and its chunk-dependent retained footprint.
jvm_args() {
    local leaf_count="$1"
    local chunk_size="$2"
    if (( leaf_count <= 100000000 )); then
        echo "-Xms512m -Xmx4g -XX:MaxDirectMemorySize=4g"
    elif (( leaf_count <= 1000000000 )); then
        echo "-Xms512m -Xmx16g -XX:MaxDirectMemorySize=16g"
    elif (( chunk_size == 262144 )); then
        echo "-Xms512m -Xmx48g -XX:MaxDirectMemorySize=48g"
    elif (( chunk_size == 1048576 )); then
        echo "-Xms512m -Xmx64g -XX:MaxDirectMemorySize=64g"
    else
        echo "-Xms512m -Xmx96g -XX:MaxDirectMemorySize=96g"
    fi
}

run_jmh() {
    local result_name="$1"
    local implementations="$2"
    local threads="$3"
    local leaf_count="$4"
    local chunk_size="$5"
    local measurement_iterations="$6"
    local disk_cache_state="${7:-UNCHANGED}"
    local size_directory="${SCRATCH_DIR}/leaves-${leaf_count}"
    local java_options
    java_options="$(jvm_args "${leaf_count}" "${chunk_size}")"

    echo
    echo "Running ${result_name}, threadsPerLongList={${threads}}"
    java -jar "${JMH_JAR}" LongListSnapshotBenchmark.writeToFile \
        -p "listImpl=${implementations}" \
        -p "threadsPerLongList=${threads}" \
        -p "leafCount=${leaf_count}" \
        -p "longListChunkSize=${chunk_size}" \
        -p "workDir=${size_directory}" \
        -p "verify=false" \
        -p "diskCacheState=${disk_cache_state}" \
        -t 1 \
        -bm ss \
        -tu ms \
        -wi 1 \
        -i "${measurement_iterations}" \
        -f 1 \
        -to 60m \
        -foe true \
        -rf json \
        -rff "${RESULTS_DIR}/${result_name}.json" \
        -jvmArgs "${java_options}" \
        2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
}

# The first JMH fork creates the fixture; every remaining configuration for that leaf count reuses it.
for leaf_count in "${LEAF_COUNTS[@]}"; do
    size_directory="${SCRATCH_DIR}/leaves-${leaf_count}"
    mkdir "${size_directory}"

    for block in "${BLOCKS[@]}"; do
        set_block_order "${block}"
        for chunk_size in "${CHUNK_SIZES[@]}"; do
            threads="$(thread_values "${leaf_count}" "${chunk_size}" "${block}")"
            result_name="leaves-${leaf_count}-chunk-${chunk_size}-block-${block}"

            # One fork performs one warmup and two measured single-shot writes, saving JSON and a readable log.
            run_jmh "${result_name}" "${IMPLEMENTATIONS}" "${threads}" "${leaf_count}" "${chunk_size}" 2
        done
    done

    # Compare verified warm and cold LongListDisk sources without adding cache state to the full matrix.
    if (( leaf_count == 1000000000 )); then
        chunk_size=1048576
        for block in "${BLOCKS[@]}"; do
            case "${block}" in
                A)
                    threads="1,2,8"
                    cache_states="WARM,COLD"
                    ;;
                B)
                    threads="8,2,1"
                    cache_states="COLD,WARM"
                    ;;
                C)
                    threads="2,8,1"
                    cache_states="WARM,COLD"
                    ;;
            esac
            result_name="disk-cache-leaves-${leaf_count}-chunk-${chunk_size}-block-${block}"
            run_jmh "${result_name}" "LongListDisk" "${threads}" "${leaf_count}" "${chunk_size}" 2 "${cache_states}"
        done
    fi

    # Give the shortlisted production comparison 15 measurements per cell without expanding the broad matrix.
    if (( leaf_count >= 1000000000 )); then
        chunk_size=1048576
        for block in "${BLOCKS[@]}"; do
            case "${block}" in
                A)
                    implementations="LongListSegment,LongListDisk"
                    threads="1,2"
                    ;;
                B)
                    implementations="LongListDisk,LongListSegment"
                    threads="2,1"
                    ;;
                C)
                    implementations="LongListSegment,LongListDisk"
                    threads="2,1"
                    ;;
            esac
            result_name="confirmation-leaves-${leaf_count}-chunk-${chunk_size}-block-${block}"

            # Five measurements in each reordered block give every shortlisted cell 15 samples.
            run_jmh "${result_name}" "${implementations}" "${threads}" "${leaf_count}" "${chunk_size}" 5
        done
    fi

    # All configurations for this leaf count are complete; delete its fixture to bound peak disk usage.
    remove_scratch_directory "${size_directory}"
done

# Archive the retained results, environment metadata, and exact runner; the trap removes scratch data.
tar -C "${RESULTS_PARENT}" -czf "${ARCHIVE}" "${RUN_ID}"
echo
echo "Results archive: ${ARCHIVE}"
