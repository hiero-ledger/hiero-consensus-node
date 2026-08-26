#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Compares the prepared-memory FileChannel control with all LongList implementations at P=1 and P=8.
# JFR records the final force in every measured write so the remaining time can be derived from JMH's total.

set -euo pipefail

if (( $# != 0 )); then
    echo "Usage: $0" >&2
    exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
REPO_ROOT="$(git -C "${MODULE_DIR}" rev-parse --show-toplevel)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"

# Scratch holds the reusable fixture and temporary targets; results and JFR recordings are retained.
SCRATCH_PARENT="${MODULE_DIR}/build/tmp/long-list-phase-breakdown"
RESULTS_PARENT="${MODULE_DIR}/build/results/jmh/long-list-phase-breakdown"
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

# Remove generated fixtures and targets after success, failure, or interruption.
trap 'cd "${MODULE_DIR}"; remove_scratch_directory "${SCRATCH_DIR}"' EXIT

# Record the source revision and the hardware and filesystem used for the measurement.
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

mkdir "${SCRATCH_DIR}/filechannel" "${SCRATCH_DIR}/long-list"
# Short workDir parameters keep JMH's parameter-derived JFR directory names within filesystem limits.
cd "${SCRATCH_DIR}"

run_filechannel_control() {
    local block="$1"
    local threads="$2"
    local result_name="filechannel-block-${block}"
    local jfr_directory="${RESULTS_DIR}/jfr/${result_name}"

    echo
    echo "Running ${result_name}, writerThreads={${threads}}"
    java -jar "${JMH_JAR}" FileChannelWriteBenchmark.writePreparedFile \
        -p "bodySizeBytes=8000000000" \
        -p "writerThreads=${threads}" \
        -p "workDir=filechannel" \
        -p "verify=false" \
        -t 1 \
        -bm ss \
        -tu ms \
        -wi 1 \
        -i 5 \
        -f 1 \
        -to 60m \
        -foe true \
        -prof "jfr:dir=${jfr_directory};configName=default" \
        -rf json \
        -rff "${RESULTS_DIR}/${result_name}.json" \
        2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
}

run_long_lists() {
    local block="$1"
    local implementations="$2"
    local threads="$3"
    local result_name="long-list-block-${block}"
    local jfr_directory="${RESULTS_DIR}/jfr/${result_name}"

    echo
    echo "Running ${result_name}, threadsPerLongList={${threads}}"
    java -jar "${JMH_JAR}" LongListSnapshotBenchmark.writeToFile \
        -p "listImpl=${implementations}" \
        -p "threadsPerLongList=${threads}" \
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
        -prof "jfr:dir=${jfr_directory};configName=default" \
        -rf json \
        -rff "${RESULTS_DIR}/${result_name}.json" \
        -jvmArgs "-Xms512m -Xmx16g -XX:MaxDirectMemorySize=16g" \
        2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
}

# Reorder implementations, thread counts, and control placement so machine drift is visible.
for block in A B C; do
    case "${block}" in
        A)
            implementations="LongListHeap,LongListOffHeap,LongListSegment,LongListDisk,LongListDiskSegment"
            threads="1,8"
            run_filechannel_control "${block}" "${threads}"
            run_long_lists "${block}" "${implementations}" "${threads}"
            ;;
        B)
            implementations="LongListDiskSegment,LongListDisk,LongListSegment,LongListOffHeap,LongListHeap"
            threads="8,1"
            run_long_lists "${block}" "${implementations}" "${threads}"
            run_filechannel_control "${block}" "${threads}"
            ;;
        C)
            implementations="LongListOffHeap,LongListDiskSegment,LongListHeap,LongListSegment,LongListDisk"
            threads="8,1"
            run_filechannel_control "${block}" "${threads}"
            run_long_lists "${block}" "${implementations}" "${threads}"
            ;;
    esac
done

# Keep the raw timings, JFR recordings, environment metadata, and exact runner in one transferable file.
tar -C "${RESULTS_PARENT}" -czf "${ARCHIVE}" "${RUN_ID}"
echo
echo "Results archive: ${ARCHIVE}"
