#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Measures the durable FileChannel write rate with prepared data and no LongList source work.
# The campaign writes about 360 GB cumulatively and keeps at most one 8 GB target at a time.

set -euo pipefail

if (( $# != 0 )); then
    echo "Usage: $0" >&2
    exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
REPO_ROOT="$(git -C "${MODULE_DIR}" rev-parse --show-toplevel)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"

# Scratch holds only temporary target files; the result directory is retained and archived.
SCRATCH_PARENT="${MODULE_DIR}/build/tmp/filechannel-write-reference"
RESULTS_PARENT="${MODULE_DIR}/build/results/jmh/filechannel-write-reference"
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

# Remove generated target files after success, failure, or interruption.
trap 'remove_scratch_directory "${SCRATCH_DIR}"' EXIT

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

# Change parameter order between blocks so host drift is visible in the results.
for block in A B C; do
    case "${block}" in
        A) writer_threads="1,2,8,16,32" ;;
        B) writer_threads="32,16,8,2,1" ;;
        C) writer_threads="8,16,32,1,2" ;;
    esac
    result_name="filechannel-write-reference-block-${block}"

    echo
    echo "Running ${result_name}, writerThreads={${writer_threads}}"
    # Each setting writes an 8 GB body once for warmup and twice for measurement.
    java -jar "${JMH_JAR}" FileChannelWriteBenchmark.writePreparedFile \
        -p "bodySizeBytes=8000000000" \
        -p "writerThreads=${writer_threads}" \
        -p "workDir=${SCRATCH_DIR}" \
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
        2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
done

# Keep the raw results, environment metadata, and exact runner in one transferable file.
tar -C "${RESULTS_PARENT}" -czf "${ARCHIVE}" "${RUN_ID}"
echo
echo "Results archive: ${ARCHIVE}"
