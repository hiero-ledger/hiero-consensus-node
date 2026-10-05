#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Complete 1B-leaf MerkleDB snapshot campaign: force off, hash-cache overlap always on.
# Run explicitly on the Linux benchmark machine. --dry-run only prints the plan.
# --retry RUN_ID restarts with that run's frozen JAR and settings, without rebuilding.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
REPO_ROOT="$(git -C "${MODULE_DIR}" rev-parse --show-toplevel)"
RUNNER_SOURCE="${SCRIPT_DIR}/$(basename -- "${BASH_SOURCE[0]}")"
PREFLIGHT_SOURCE="${SCRIPT_DIR}/check-merkledb-snapshot-benchmark-system.sh"
CAMPAIGN_LABEL="${CAMPAIGN_LABEL:-baseline}"
PREBUILT_JMH_JAR="${PREBUILT_JMH_JAR:-}"
if [[ ! "${CAMPAIGN_LABEL}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
    echo "CAMPAIGN_LABEL must contain 1-64 letters, digits, dots, underscores, or hyphens, starting with a letter/digit." >&2
    exit 2
fi

DRY_RUN=false
RETRY_RUN=""
while (( $# > 0 )); do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --retry)
            if (( $# < 2 )) || [[ ! "$2" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ || -n "${RETRY_RUN}" ]]; then
                echo "--retry requires one run ID from the campaign results directory." >&2
                exit 2
            fi
            RETRY_RUN="$2"
            shift 2
            ;;
        *)
            echo "Usage: $0 [--dry-run] [--retry RUN_ID] (optional environment: CAMPAIGN_LABEL, JAVA_HOME, PREBUILT_JMH_JAR)" >&2
            exit 2
            ;;
    esac
done

# Ten thousand 100,000-leaf copies retain the original fixture creation cadence.
LEAF_COUNT=1000000000
NUM_FILES=10000
NUM_RECORDS=100000
JVM_ARGS="-Xms4g -Xmx32g -XX:MaxDirectMemorySize=16g"
BENCHMARK='com.swirlds.benchmark.MerkleDbSnapshotBenchmark.snapshot$'
RUN_ID="${CAMPAIGN_LABEL}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
SCRATCH_PARENT="${MODULE_DIR}/build/tmp/merkledb-snapshot-1b-campaign"
JMH_TMP_DIR="${SCRATCH_PARENT}/jmh-tmp"
RESULTS_PARENT="${MODULE_DIR}/build/results/jmh/merkledb-snapshot-1b-campaign"
RESULTS_DIR="${RESULTS_PARENT}/${RUN_ID}"
ARCHIVE="${RESULTS_PARENT}/${RUN_ID}.tar.gz"
JMH_JAR="${RESULTS_DIR}/jmh.jar"
SETTINGS_SOURCE="${MODULE_DIR}/settings.txt"
if [[ -n "${RETRY_RUN}" ]]; then
    [[ -z "${PREBUILT_JMH_JAR}" ]] || { echo "Use either --retry or PREBUILT_JMH_JAR, not both." >&2; exit 2; }
    PREBUILT_JMH_JAR="${RESULTS_PARENT}/${RETRY_RUN}/jmh.jar"
    SETTINGS_SOURCE="${RESULTS_PARENT}/${RETRY_RUN}/settings.txt"
    [[ -f "${PREBUILT_JMH_JAR}" && -r "${PREBUILT_JMH_JAR}" && -f "${SETTINGS_SOURCE}" && -r "${SETTINGS_SOURCE}" ]] \
        || { echo "Cannot read the frozen JAR and settings for run: ${RETRY_RUN}" >&2; exit 2; }
fi
SCRATCH_DIR=""
PHASE=initialization
COMPLETED_BLOCKS=0
CAMPAIGN_FINISHED=false
INTERRUPTED=false
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA="${JAVA_HOME}/bin/java"
else
    JAVA=java
fi

write_manifest() {
    local status="$1" exit_code="$2"
    {
        echo "status=${status}"
        echo "exit_code=${exit_code}"
        echo "campaign_label=${CAMPAIGN_LABEL}"
        echo "run_id=${RUN_ID}"
        echo "retry_of=${RETRY_RUN}"
        echo "phase=${PHASE}"
        echo "started_at=${STARTED_AT}"
        echo "updated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "completed_blocks=${COMPLETED_BLOCKS}"
        echo "expected_configurations=25"
        echo "expected_rows=75"
        echo "expected_samples=225"
        echo "leaf_count=${LEAF_COUNT}"
        echo "snapshot_force=false"
        echo "hash_cache_overlap=true"
    } >"${RESULTS_DIR}/manifest.txt"
}

remove_owned_scratch() {
    # Delete only the exact mktemp directory, with an ownership marker and canonical parent.
    if [[ -z "${SCRATCH_DIR}" ]]; then
        return 0
    fi
    if [[ ! -d "${SCRATCH_DIR}" || -L "${SCRATCH_DIR}" \
        || "$(dirname -- "${SCRATCH_DIR}")" != "${SCRATCH_PARENT}" \
        || "$(basename -- "${SCRATCH_DIR}")" != run.* \
        || ! -f "${SCRATCH_DIR}/.campaign-owner" \
        || "$(<"${SCRATCH_DIR}/.campaign-owner")" != "${RUN_ID}" \
        || "$(cd -- "${SCRATCH_DIR}" && pwd -P)" != "${SCRATCH_DIR}" ]]; then
        echo "Refusing to remove unverified benchmark scratch: ${SCRATCH_DIR}" >&2
        return 1
    fi
    rm -rf -- "${SCRATCH_DIR}"
}

finish_run() {
    local exit_code=$? status=failed diagnostics_ok=true
    trap - EXIT INT TERM
    set +e
    cd -- "${MODULE_DIR}" || diagnostics_ok=false
    if [[ -n "${SCRATCH_DIR}" && -d "${SCRATCH_DIR}" ]]; then
        if [[ -f "${SCRATCH_DIR}/settingsUsed.txt" ]]; then
            cp -- "${SCRATCH_DIR}/settingsUsed.txt" "${RESULTS_DIR}/settingsUsed.txt" || diagnostics_ok=false
        fi
        # Marker-specific phase timings are not all emitted to the JMH console.
        if [[ -d "${SCRATCH_DIR}/output" ]]; then
            cp -R -- "${SCRATCH_DIR}/output" "${RESULTS_DIR}/logs" || diagnostics_ok=false
        fi
    fi
    remove_owned_scratch || diagnostics_ok=false
    if [[ "${diagnostics_ok}" != true && "${exit_code}" == 0 ]]; then
        exit_code=1
    fi
    if [[ "${INTERRUPTED}" == true ]]; then
        status=interrupted
    elif [[ "${CAMPAIGN_FINISHED}" == true && "${exit_code}" == 0 ]]; then
        status=complete
        PHASE=complete
    fi
    write_manifest "${status}" "${exit_code}"
    echo "Finished: $(date -u +%Y-%m-%dT%H:%M:%SZ); status=${status}; exit_code=${exit_code}" \
        >>"${RESULTS_DIR}/environment.txt"
    # Hash frozen inputs and all result/log files. The archive sits outside this directory.
    (
        cd -- "${RESULTS_DIR}" || exit 1
        find . -type f ! -name SHA256SUMS -print0 | sort -z | \
            while IFS= read -r -d '' file; do sha256sum -- "${file}" || exit 1; done
    ) >"${RESULTS_DIR}/SHA256SUMS"
    if (( $? != 0 )); then
        exit_code=1
        status=failed
        write_manifest "${status}" "${exit_code}"
        echo "Unable to checksum all diagnostics; results remain at ${RESULTS_DIR}" >&2
    fi
    if tar -C "${RESULTS_PARENT}" -czf "${ARCHIVE}.partial" "${RUN_ID}" \
        && mv -- "${ARCHIVE}.partial" "${ARCHIVE}"; then
        echo "Results archive (${status}): ${ARCHIVE}"
    else
        exit_code=1
        write_manifest failed "${exit_code}"
        echo "Archive creation failed; diagnostics remain at ${RESULTS_DIR}" >&2
    fi
    exit "${exit_code}"
}

run_jmh() {
    local result_name="$1" implementations="$2" writer_counts="$3" prepare="$4" warmups="$5" iterations="$6" timeout="$7"
    local -a command=("${JAVA}" "-Djava.io.tmpdir=${JMH_TMP_DIR}" -jar "${JMH_JAR}" "${BENCHMARK}"
        -p "longListImplementation=${implementations}"
        -p "threadsPerLongList=${writer_counts}"
        -p "prepareFixtureOnly=${prepare}"
        -p "numFiles=${NUM_FILES}" -p "numRecords=${NUM_RECORDS}"
        -p "maxKey=${LEAF_COUNT}" -p keySize=32 -p recordSize=128 -p numThreads=32
        -t 1 -bm ss -tu ms -wi "${warmups}" -i "${iterations}" -f 1
        -to "${timeout}" -foe true -rf json -rff "${RESULTS_DIR}/${result_name}.json"
        -jvmArgs "${JVM_ARGS}")
    if [[ "${DRY_RUN}" == true ]]; then
        printf '%q ' "${command[@]}"
        printf '\n'
    else
        PHASE="${result_name}"
        write_manifest running 0
        printf '%q ' "${command[@]}" >>"${RESULTS_DIR}/commands.txt"
        printf '\n' >>"${RESULTS_DIR}/commands.txt"
        "${command[@]}" 2>&1 | tee "${RESULTS_DIR}/${result_name}.log"
    fi
}

run_blocks() {
    # Rotate both axes across blocks to distribute ordering and machine drift.
    # Every cell receives one unmeasured warmup and three measured snapshots per block.
    run_jmh merkledb-snapshot-block-A "SEGMENT,DISK,HEAP,OFF_HEAP,DISK_SEGMENT" "1,2,8,16,32" false 1 3 60m
    COMPLETED_BLOCKS=1
    run_jmh merkledb-snapshot-block-B "DISK_SEGMENT,OFF_HEAP,HEAP,DISK,SEGMENT" "32,16,8,2,1" false 1 3 60m
    COMPLETED_BLOCKS=2
    run_jmh merkledb-snapshot-block-C "HEAP,DISK_SEGMENT,SEGMENT,OFF_HEAP,DISK" "8,16,32,1,2" false 1 3 60m
    COMPLETED_BLOCKS=3
}

main() {
    if [[ "${DRY_RUN}" == true ]]; then
        echo "Plan only: no build, fixture, benchmark, or filesystem writes."
        echo "Campaign ${CAMPAIGN_LABEL}: 1B leaves, key=32 B, record=128 B, force off, overlap always on."
        echo "5 implementations x 5 writer counts = 25 configurations; 3 blocks = 75 rows / 225 measured samples."
        echo "Each configuration has 1 warmup + 3 measurements per block (9 measured snapshots total)."
        echo "Check host activity and leftover fixtures before building and again before JMH; stop if not ready."
        if [[ -n "${PREBUILT_JMH_JAR}" ]]; then
            echo "Reuse JAR (no Gradle build): ${PREBUILT_JMH_JAR}"
        else
            echo "Build: ${REPO_ROOT}/gradlew :swirlds-benchmarks:jmhJar --console=plain"
        fi
        echo "Freeze JAR, runner, preflight, settings, and software metadata in ${RESULTS_DIR} before JMH."
        echo "Working directory: a unique mktemp run.* under ${SCRATCH_PARENT}"
        run_jmh fixture-preparation SEGMENT 1 true 0 1 720m
        run_blocks
        echo "Archive after success/failure: ${ARCHIVE} (check manifest.txt status)."
        return 0
    fi

    [[ "$(uname -s)" == Linux ]] || { echo "The full campaign requires Linux; use --dry-run locally." >&2; return 1; }
    command -v "${JAVA}" >/dev/null || { echo "Java not found: ${JAVA}" >&2; return 1; }
    local java_version
    java_version="$("${JAVA}" -version 2>&1 | awk -F'"' 'NR == 1 { print $2 }')"
    [[ "${java_version}" == 25.0.2* ]] || { echo "Java 25.0.2 required; found ${java_version}" >&2; return 1; }
    command -v sha256sum >/dev/null || { echo "sha256sum is required." >&2; return 1; }
    if [[ -n "${PREBUILT_JMH_JAR}" ]]; then
        [[ -f "${PREBUILT_JMH_JAR}" && -r "${PREBUILT_JMH_JAR}" ]] \
            || { echo "Cannot read prebuilt JMH JAR: ${PREBUILT_JMH_JAR}" >&2; return 1; }
        PREBUILT_JMH_JAR="$(cd -- "$(dirname -- "${PREBUILT_JMH_JAR}")" && pwd -P)/$(basename -- "${PREBUILT_JMH_JAR}")"
    fi

    # Do not build or create another fixture while the host is busy or prior scratch remains.
    bash "${PREFLIGHT_SOURCE}" "${MODULE_DIR}"

    mkdir -p -- "${SCRATCH_PARENT}" "${RESULTS_PARENT}" "${JMH_TMP_DIR}"
    # Fail before building if this checkout's JMH lock is not writable.
    : >>"${JMH_TMP_DIR}/jmh.lock"
    SCRATCH_PARENT="$(cd -- "${SCRATCH_PARENT}" && pwd -P)"
    [[ ! -e "${RESULTS_DIR}" && ! -e "${ARCHIVE}" ]] || { echo "Run ID already exists: ${RUN_ID}" >&2; return 1; }
    mkdir -- "${RESULTS_DIR}"
    trap finish_run EXIT
    trap 'INTERRUPTED=true; exit 130' INT
    trap 'INTERRUPTED=true; exit 143' TERM
    SCRATCH_DIR="$(mktemp -d "${SCRATCH_PARENT}/run.XXXXXX")"
    printf '%s\n' "${RUN_ID}" >"${SCRATCH_DIR}/.campaign-owner"
    write_manifest running 0

    cp -- "${RUNNER_SOURCE}" "${RESULTS_DIR}/runner.sh"
    cp -- "${PREFLIGHT_SOURCE}" "${RESULTS_DIR}/preflight.sh"
    cp -- "${SETTINGS_SOURCE}" "${RESULTS_DIR}/settings-source.txt"
    # Keep one effective occurrence of each campaign setting, even if the source has duplicates.
    # BaseBench must preserve the fixture between forks; CSV writes would add unrelated I/O.
    awk -F, '
        {
            key = $1
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
            if (key != "benchmark.benchmarkData" && key != "benchmark.saveDataDirectory" \
                    && key != "benchmark.verifyResult" && key != "benchmark.csvWriteFrequency") print
        }
        END {
            print "benchmark.benchmarkData, data"
            print "benchmark.saveDataDirectory, true"
            print "benchmark.verifyResult, true"
            print "benchmark.csvWriteFrequency, 0"
        }
    ' "${RESULTS_DIR}/settings-source.txt" >"${RESULTS_DIR}/settings.txt"
    cp -- "${RESULTS_DIR}/settings.txt" "${SCRATCH_DIR}/settings.txt"
    {
        echo "Started: ${STARTED_AT}"
        echo "Campaign label: ${CAMPAIGN_LABEL}"
        echo "Git revision: $(git -C "${REPO_ROOT}" rev-parse HEAD)"
        echo "JAVA_HOME: ${JAVA_HOME:-unset}"
        echo "Java command: ${JAVA}"
        echo "Scratch: ${SCRATCH_DIR}"
        echo "JVM arguments: ${JVM_ARGS}"
        "${JAVA}" -version
        git -C "${REPO_ROOT}" status --short --branch
        if command -v lscpu >/dev/null; then lscpu; fi
        if command -v lsblk >/dev/null; then lsblk -o NAME,MODEL,SIZE,ROTA,TRAN,TYPE,MOUNTPOINTS; fi
    } >"${RESULTS_DIR}/environment.txt" 2>&1
    git -C "${REPO_ROOT}" diff HEAD --binary >"${RESULTS_DIR}/source.diff"

    local artifact="${PREBUILT_JMH_JAR}"
    if [[ -n "${artifact}" ]]; then
        printf 'Reusing prebuilt JAR; Gradle build skipped: %s\n' "${artifact}" | tee "${RESULTS_DIR}/build.log"
    else
        PHASE=build
        write_manifest running 0
        cd -- "${REPO_ROOT}"
        "${REPO_ROOT}/gradlew" :swirlds-benchmarks:jmhJar --console=plain 2>&1 | tee "${RESULTS_DIR}/build.log"
        # Do not delete prior build outputs: ambiguity fails safely instead of selecting a stale JAR.
        local -a jmh_jars
        shopt -s nullglob
        jmh_jars=("${MODULE_DIR}"/build/libs/swirlds-benchmarks-*-jmh.jar)
        shopt -u nullglob
        if (( ${#jmh_jars[@]} != 1 )); then
            echo "Expected exactly one JMH JAR after build, found ${#jmh_jars[@]}; inspect build/libs manually." >&2
            return 1
        fi
        artifact="${jmh_jars[0]}"
    fi
    # A build may take a while; check again just before preparing the fixture.
    PHASE=system-check
    write_manifest running 0
    bash "${RESULTS_DIR}/preflight.sh" "${MODULE_DIR}" "${SCRATCH_DIR}" \
        | tee "${RESULTS_DIR}/system-check.txt"
    cp -- "${artifact}" "${JMH_JAR}"
    printf 'Benchmark artifact source: %s\n' "${artifact}" >>"${RESULTS_DIR}/environment.txt"
    (cd -- "${RESULTS_DIR}" && sha256sum jmh.jar runner.sh preflight.sh settings.txt) \
        >"${RESULTS_DIR}/frozen-inputs.sha256"
    chmod a-w -- "${JMH_JAR}" "${RESULTS_DIR}/runner.sh" "${RESULTS_DIR}/preflight.sh" "${RESULTS_DIR}/settings.txt"

    # All forks use the frozen artifact, not build/libs, while development can continue.
    cd -- "${SCRATCH_DIR}"
    run_jmh fixture-preparation SEGMENT 1 true 0 1 720m
    local -a fixture_dirs
    shopt -s nullglob
    fixture_dirs=("${SCRATCH_DIR}"/data/MerkleDbSnapshotBenchmark/fixture-*)
    shopt -u nullglob
    if (( ${#fixture_dirs[@]} != 1 )) || [[ ! -d "${fixture_dirs[0]}" ]]; then
        echo "Expected exactly one reusable fixture directory, found ${#fixture_dirs[@]}" >&2
        return 1
    fi
    local fixture_dir="${fixture_dirs[0]}" available_kib
    {
        echo "Fixture: ${fixture_dir}"
        echo "Physical size:"
        du -sh -- "${fixture_dir}"
        echo "Apparent size:"
        du -sh --apparent-size -- "${fixture_dir}"
        df -hT -- "${fixture_dir}"
    } | tee "${RESULTS_DIR}/fixture-storage.txt"
    available_kib="$(df -Pk "${fixture_dir}" | awk 'NR == 2 { print $4 }')"
    if [[ ! "${available_kib}" =~ ^[0-9]+$ ]] || (( available_kib < 32 * 1024 * 1024 )); then
        echo "At least 32 GiB must remain after fixture preparation; available KiB=${available_kib}" >&2
        return 1
    fi
    run_blocks
    CAMPAIGN_FINISHED=true
}

# Function bodies are fully parsed before execution, so editing the source runner
# during a long campaign cannot alter the active shell's later benchmark commands.
main
