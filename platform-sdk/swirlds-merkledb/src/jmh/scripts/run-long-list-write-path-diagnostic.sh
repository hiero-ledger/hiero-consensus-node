#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Separates target FileChannel.write() calls from the rest of the P=8 pre-force phase.

set -euo pipefail

if (( $# != 0 )); then
    echo "Usage: $0" >&2
    exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
REPO_ROOT="$(git -C "${MODULE_DIR}" rev-parse --show-toplevel)"
JFR_CONFIG="${SCRIPT_DIR}/long-list-write-path.jfc"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"

SCRATCH_PARENT="${MODULE_DIR}/build/tmp/long-list-write-path-diagnostic"
RESULTS_PARENT="${MODULE_DIR}/build/results/jmh/long-list-write-path-diagnostic"
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

# Fixtures and target files can be large, so remove them on every exit.
trap 'cd "${MODULE_DIR}"; remove_scratch_directory "${SCRATCH_DIR}"' EXIT

# Keep enough environment data to compare this run with the phase-breakdown campaign.
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
cp "${JFR_CONFIG}" "${RESULTS_DIR}/long-list-write-path.jfc"

# Remove stale benchmark JARs before selecting the one built from this revision.
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
# Short workDir values keep JMH's generated JFR paths below filesystem limits.
cd "${SCRATCH_DIR}"

run_filechannel_control() {
    local block="$1"
    local result_name="filechannel-block-${block}"

    echo
    echo "Running ${result_name}, writerThreads=8"
    java -jar "${JMH_JAR}" FileChannelWriteBenchmark.writePreparedFile \
        -p "bodySizeBytes=8000000000" \
        -p "writerThreads=8" \
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
        -prof "jfr:dir=${RESULTS_DIR}/jfr/${result_name};configName=${JFR_CONFIG}" \
        -rf json \
        -rff "${RESULTS_DIR}/${result_name}.json" \
        2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
}

run_long_lists() {
    local block="$1"
    local implementations="$2"
    local result_name="long-list-block-${block}"

    echo
    echo "Running ${result_name}, threadsPerLongList=8"
    java -jar "${JMH_JAR}" LongListSnapshotBenchmark.writeToFile \
        -p "listImpl=${implementations}" \
        -p "threadsPerLongList=8" \
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
        -prof "jfr:dir=${RESULTS_DIR}/jfr/${result_name};configName=${JFR_CONFIG}" \
        -rf json \
        -rff "${RESULTS_DIR}/${result_name}.json" \
        -jvmArgs "-Xms512m -Xmx16g -XX:MaxDirectMemorySize=16g" \
        2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
}

# Reorder implementations and control placement so machine drift remains visible.
for block in A B C; do
    case "${block}" in
        A)
            run_filechannel_control "${block}"
            run_long_lists "${block}" \
                "LongListHeap,LongListOffHeap,LongListSegment,LongListDisk,LongListDiskSegment"
            ;;
        B)
            run_long_lists "${block}" \
                "LongListDiskSegment,LongListDisk,LongListSegment,LongListOffHeap,LongListHeap"
            run_filechannel_control "${block}"
            ;;
        C)
            run_filechannel_control "${block}"
            run_long_lists "${block}" \
                "LongListOffHeap,LongListDiskSegment,LongListHeap,LongListSegment,LongListDisk"
            ;;
    esac
done

tar -C "${RESULTS_PARENT}" -czf "${ARCHIVE}" "${RUN_ID}"
echo
echo "Results archive: ${ARCHIVE}"
