#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Compares forced and unforced LongList snapshot return times at P=1 and P=8.

set -euo pipefail

if (( $# != 0 )); then
    echo "Usage: $0" >&2
    exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
REPO_ROOT="$(git -C "${MODULE_DIR}" rev-parse --show-toplevel)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"

# Scratch contains the reusable fixture and temporary targets; results are retained separately.
SCRATCH_PARENT="${MODULE_DIR}/build/tmp/long-list-force-comparison"
RESULTS_PARENT="${MODULE_DIR}/build/results/jmh/long-list-force-comparison"
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

# Remove the multi-gigabyte fixture and targets after success, failure, or interruption.
trap 'cd "${MODULE_DIR}"; remove_scratch_directory "${SCRATCH_DIR}"' EXIT

# Preserve enough environment data to compare this campaign with the earlier Linux results.
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
    lscpu
    echo
    free -h
    echo
    findmnt -T "${MODULE_DIR}" -o TARGET,SOURCE,FSTYPE,OPTIONS
    echo
    df -hT "${MODULE_DIR}"
    echo
    lsblk -o NAME,MODEL,SIZE,ROTA,TRAN,TYPE,MOUNTPOINTS
} >"${RESULTS_DIR}/environment.txt" 2>&1
cp "${BASH_SOURCE[0]}" "${RESULTS_DIR}/runner.sh"

# Remove stale JMH JARs before selecting the artifact built from this revision.
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

mkdir "${SCRATCH_DIR}/long-list"
# A short workDir keeps JMH's parameter-derived JFR paths below filesystem limits.
cd "${SCRATCH_DIR}"

run_block() {
    local block="$1"
    local implementations="$2"
    local threads="$3"
    local force_modes="$4"
    local result_name="long-list-block-${block}"

    echo
    echo "Running ${result_name}, threadsPerLongList={${threads}}, forceToDisk={${force_modes}}"
    java -jar "${JMH_JAR}" LongListSnapshotBenchmark.writeToFile \
        -p "listImpl=${implementations}" \
        -p "threadsPerLongList=${threads}" \
        -p "forceToDisk=${force_modes}" \
        -p "leafCount=1000000000" \
        -p "longListChunkSize=1048576" \
        -p "workDir=long-list" \
        -p "verify=false" \
        -p "diskCacheState=UNCHANGED" \
        -t 1 \
        -bm ss \
        -tu ms \
        -wi 1 \
        -i 5 \
        -f 1 \
        -to 60m \
        -foe true \
        -prof "jfr:dir=${RESULTS_DIR}/jfr/${result_name};configName=default" \
        -rf json \
        -rff "${RESULTS_DIR}/${result_name}.json" \
        -jvmArgs "-Xms512m -Xmx16g -XX:MaxDirectMemorySize=16g" \
        2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
}

# Reverse implementations, writer counts, and force modes so machine drift remains visible.
run_block A \
    "LongListHeap,LongListOffHeap,LongListSegment,LongListDisk,LongListDiskSegment" \
    "1,8" \
    "true,false"
run_block B \
    "LongListDiskSegment,LongListDisk,LongListSegment,LongListOffHeap,LongListHeap" \
    "8,1" \
    "false,true"
run_block C \
    "LongListOffHeap,LongListDiskSegment,LongListHeap,LongListSegment,LongListDisk" \
    "8,1" \
    "true,false"

# Keep timings, JFR recordings, environment metadata, and the exact runner together.
tar -C "${RESULTS_PARENT}" -czf "${ARCHIVE}" "${RUN_ID}"
echo
echo "Results archive: ${ARCHIVE}"
