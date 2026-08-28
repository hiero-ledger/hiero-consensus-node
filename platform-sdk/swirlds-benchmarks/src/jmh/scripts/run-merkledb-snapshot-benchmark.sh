#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Compares forced, unforced, and hash-cache-overlapped snapshots of a 100M-leaf MerkleDB.

set -euo pipefail

if (( $# != 0 )); then
    echo "Usage: $0" >&2
    exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
REPO_ROOT="$(git -C "${MODULE_DIR}" rev-parse --show-toplevel)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"

# Scratch holds the reusable fixture and all snapshot output; only results survive the run.
SCRATCH_PARENT="${MODULE_DIR}/build/tmp/merkledb-snapshot-campaign"
RESULTS_PARENT="${MODULE_DIR}/build/results/jmh/merkledb-snapshot-campaign"
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

# Remove the large fixture and temporary targets on success, failure, or interruption.
trap 'cd "${MODULE_DIR}"; remove_scratch_directory "${SCRATCH_DIR}"' EXIT

# Run from scratch so BaseBench keeps its data and generated settings outside the repository.
cp "${MODULE_DIR}/settings.txt" "${SCRATCH_DIR}/settings.txt"

# Preserve the software and machine details needed to interpret the measurements.
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
    command -v lscpu >/dev/null && lscpu || echo "lscpu is unavailable"
    echo
    command -v free >/dev/null && free -h || echo "free is unavailable"
    echo
    command -v findmnt >/dev/null && findmnt -T "${SCRATCH_DIR}" -o TARGET,SOURCE,FSTYPE,OPTIONS \
        || echo "findmnt is unavailable"
    echo
    df -hT "${SCRATCH_DIR}"
    echo
    command -v lsblk >/dev/null && lsblk -o NAME,MODEL,SIZE,ROTA,TRAN,TYPE,MOUNTPOINTS \
        || echo "lsblk is unavailable"
} >"${RESULTS_DIR}/environment.txt" 2>&1
cp "${BASH_SOURCE[0]}" "${RESULTS_DIR}/runner.sh"
cp "${SCRATCH_DIR}/settings.txt" "${RESULTS_DIR}/settings.txt"

# Build and select only the JMH artifact from this revision.
rm -f -- "${MODULE_DIR}"/build/libs/swirlds-benchmarks-*-jmh.jar
"${REPO_ROOT}/gradlew" :swirlds-benchmarks:jmhJar --console=plain 2>&1 | tee "${RESULTS_DIR}/build.log"

shopt -s nullglob
jmh_jars=("${MODULE_DIR}"/build/libs/swirlds-benchmarks-*-jmh.jar)
shopt -u nullglob
if (( ${#jmh_jars[@]} != 1 )); then
    echo "Expected one swirlds-benchmarks JMH JAR, found ${#jmh_jars[@]}" >&2
    exit 1
fi
JMH_JAR="${jmh_jars[0]}"

cd "${SCRATCH_DIR}"

run_block() {
    local block="$1"
    local snapshot_modes="$2"
    local disk_modes="$3"
    local writer_counts="$4"
    local result_name="merkledb-snapshot-block-${block}"

    echo
    echo "Running ${result_name}"
    java -jar "${JMH_JAR}" MerkleDbSnapshotBenchmark.snapshot \
        -p "snapshotMode=${snapshot_modes}" \
        -p "useDiskIndices=${disk_modes}" \
        -p "threadsPerLongList=${writer_counts}" \
        -p "numFiles=1000" \
        -p "numRecords=100000" \
        -p "maxKey=100000000" \
        -p "keySize=32" \
        -p "recordSize=128" \
        -p "numThreads=32" \
        -t 1 \
        -bm ss \
        -tu ms \
        -wi 1 \
        -i 3 \
        -f 1 \
        -to 60m \
        -foe true \
        -rf json \
        -rff "${RESULTS_DIR}/${result_name}.json" \
        -jvmArgs "-Xms4g -Xmx32g -XX:MaxDirectMemorySize=16g" \
        2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
}

# P=1, P=2, and P=8 allow up to 3, 6, and 24 LongList writers across the three indices.
# Reorder each axis so machine drift does not consistently favor one configuration.
run_block A \
    "FORCED,UNFORCED,FORCED_OVERLAP,UNFORCED_OVERLAP" \
    "false,true" \
    "1,2,8"
run_block B \
    "UNFORCED_OVERLAP,FORCED_OVERLAP,UNFORCED,FORCED" \
    "true,false" \
    "8,1,2"
run_block C \
    "UNFORCED,FORCED_OVERLAP,FORCED,UNFORCED_OVERLAP" \
    "false,true" \
    "2,8,1"

cp "${SCRATCH_DIR}/settingsUsed.txt" "${RESULTS_DIR}/settingsUsed.txt"
# MerkleDB phase timings are written to marker-specific log files rather than the console.
if [[ -d "${SCRATCH_DIR}/output" ]]; then
    cp -R "${SCRATCH_DIR}/output" "${RESULTS_DIR}/logs"
fi
echo "Finished: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >>"${RESULTS_DIR}/environment.txt"

# Keep the measurements, logs, environment, settings, and exact runner together.
tar -C "${RESULTS_PARENT}" -czf "${ARCHIVE}" "${RUN_ID}"
echo
echo "Results archive: ${ARCHIVE}"
