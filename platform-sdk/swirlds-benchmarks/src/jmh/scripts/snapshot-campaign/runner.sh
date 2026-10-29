#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# MerkleDB snapshot campaign: main, the range approach and the shared pool, with all five LongList
# implementations, on a saved 1B-leaf fixture that --generate-fixture creates first. Run on Linux;
# --dry-run prints the plan anywhere.
set -euo pipefail
export LC_ALL=C TZ=UTC

# Copies sampler output, stamping each sample that starts with a line matching $2 with the time and
# the current cell. Bash reads a pipe line by line; mawk would hold lines back until its buffer fills.
stamp_samples() {
    local marker="$1" sample_start="$2" line cell
    while IFS= read -r line; do
        if [[ "${line}" =~ ${sample_start} ]]; then
            cell=unknown
            [[ ! -r "${marker}" ]] || read -r cell <"${marker}" || cell=unknown
            printf '# sample_utc=%(%Y-%m-%dT%H:%M:%SZ)T cell=%s\n' -1 "${cell}"
        fi
        printf '%s\n' "${line}"
    done
}

# Internal sampler modes run in their own process groups, owned by this runner.
if [[ "${1:-}" == --sample ]]; then
    (( $# == 3 )) || exit 2
    case "$2" in
        top)
            stdbuf -oL top -b -d 5 -o %CPU -w 180 | stamp_samples "$3" '^top - '
            ;;
        vmstat)
            stdbuf -oL vmstat -w -t 1 | stamp_samples "$3" '^[[:space:]]*[0-9]'
            ;;
        diskstats)
            while true; do
                cell=unknown
                if [[ -r "$3" ]]; then
                    read -r cell <"$3" || cell=unknown
                fi
                printf '# sample_utc=%s cell=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${cell}"
                cat /proc/diskstats
                sleep 5
            done
            ;;
        meminfo)
            # Dirty and Writeback show how much snapshot data is still waiting for the disk.
            while true; do
                cell=unknown
                if [[ -r "$3" ]]; then
                    read -r cell <"$3" || cell=unknown
                fi
                printf '# sample_utc=%s cell=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${cell}"
                grep -E '^(MemFree|MemAvailable|Buffers|Cached|Dirty|Writeback):' /proc/meminfo
                sleep 1
            done
            ;;
        *) exit 2 ;;
    esac
    exit 0
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SCRIPT_PATH="${SCRIPT_DIR}/$(basename -- "${BASH_SOURCE[0]}")"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"
MODULE_DIR="${REPO_ROOT}/platform-sdk/swirlds-benchmarks"
BENCHMARK_SOURCE_DIR=platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark
SCRATCH_PARENT="${MODULE_DIR}/build/tmp/merkledb-snapshot-1b-campaign"
FIXTURE_ROOT="${SCRATCH_PARENT}/saved-fixture"
FIXTURE_NAME=fixture-1000000000-k32-r128-cap1000000000-h6-dSHA_256
FIXTURE_DIR="${FIXTURE_ROOT}/${FIXTURE_NAME}"
RESULTS_PARENT="${MODULE_DIR}/build/results/jmh/merkledb-snapshot-1b-campaign/snapshot-campaign"
NUM_FILES=10000
NUM_RECORDS=100000
MAX_KEY=1000000000
NUM_THREADS=32
# main and range build from MAIN_REF; range adds range.patch, the range approach of the old PR.
# pool is the shared-pool branch merged with MAIN_REF.
MAIN_REF=32784e212fe86a84a780be980ea9c4c277a6174f
POOL_REF=1bc9b3200171dacfa49e51fc25bdbb4a1705091d
# Every build gets the LongList implementation selector.
COMMON_PATCHES=(selector.patch)
CAMPAIGN_SOURCES=(MerkleDbSnapshotBenchmark.java SnapshotFailureCheckBenchmark.java)
VARIANTS=(main range pool)
IMPLEMENTATIONS=(SEGMENT DISK HEAP OFF_HEAP DISK_SEGMENT)
# variant:longListWriteThreads. range counts threads per LongList file (three files), pool counts
# threads per snapshot, and main ignores the value. Each block rotates this interleaved order.
SETTINGS=(main:1 pool:1 range:1 pool:2 range:2 pool:3 pool:4 range:4 pool:6 pool:8 range:8 pool:12 pool:16 pool:24)
BLOCKS=(A B C)
# Profiling runs: range with 12 threads in total, the shared pool with the same 12, and with 24.
JFR_SETTINGS=(range:4 pool:12 pool:24)
MICRO_THREADS=(1 24)
JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
JFR_TOOL="${JAVA_HOME:+${JAVA_HOME}/bin/}jfr"
JAVA_VERSION_TEXT=""
BENCHMARK='com.swirlds.benchmark.MerkleDbSnapshotBenchmark.snapshot$'
MICRO_BENCHMARK='com.swirlds.benchmark.SnapshotFailureCheckBenchmark'
JVM_ARGS='-Xms4g -Xmx32g -XX:MaxDirectMemorySize=16g -Dbenchmark.requireExistingFixture=true'
# Generating the October 1B fixture took 2 to 9 hours, so allow 12. JMH takes minutes, not hours.
GENERATION_TIMEOUT=720m
GENERATION_JVM_ARGS='-Xms4g -Xmx32g -XX:MaxDirectMemorySize=16g'
# Profiling runs only: the recording slows the run, so these never enter the timing tables. JDK 25
# records positional FileChannel writes only while jdk.FileRead is enabled; its 1 s threshold keeps
# reads out of the recording.
JFR_EVENT_SETTINGS=jdk.ExecutionSample#period=1ms,jdk.NativeMethodSample#period=2ms,jdk.JavaMonitorEnter#threshold=0ms,jdk.JavaMonitorWait#threshold=0ms,jdk.ThreadPark#threshold=0ms,jdk.FileWrite#threshold=0ms,jdk.FileRead#enabled=true,jdk.FileRead#threshold=1s,jdk.FileForce#threshold=0ms,jdk.DirectBufferStatistics#period=1s,jdk.ThreadCPULoad#period=1s,jdk.CPULoad#period=1s
JFR_VIEWS=(hot-methods contention-by-site contention-by-class file-writes-by-path thread-cpu-load gc-pauses blocked-by-system-gc allocation-by-site native-methods)
DRY_RUN=false
RESUME_RUN=""
GENERATE_FIXTURE=false
FIXTURE_MODE=reuse
if (( $# == 1 )) && [[ "$1" == --dry-run ]]; then
    DRY_RUN=true
elif (( $# == 1 )) && [[ "$1" == --generate-fixture ]]; then
    GENERATE_FIXTURE=true
    FIXTURE_MODE=fresh
elif (( $# == 2 )) && [[ "$1" == --resume && "$2" =~ ^snapshot-campaign-[A-Za-z0-9._-]+$ ]]; then
    RESUME_RUN="$2"
elif (( $# != 0 )); then
    echo "Usage: bash $0 [--generate-fixture | --dry-run | --resume RUN_ID]" >&2
    exit 2
fi
EXPECTED_TIMING=$(( ${#BLOCKS[@]} * ${#SETTINGS[@]} * ${#IMPLEMENTATIONS[@]} ))
EXPECTED_JFR=$(( ${#JFR_SETTINGS[@]} * ${#IMPLEMENTATIONS[@]} ))
EXPECTED_CELLS=$(( EXPECTED_TIMING + EXPECTED_JFR + ${#MICRO_THREADS[@]} ))
RUN_ID="${RESUME_RUN:-snapshot-campaign-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
RESULTS_DIR="${RESULTS_PARENT}/${RUN_ID}"
FROZEN_DIR="${RESULTS_DIR}/frozen"
ARCHIVE="${RESULTS_DIR}.tar.gz"
SCRATCH_DIR=""
BUILD_DIR=""
ACTIVE_PID=""
ACTIVE_BUILD=false
CONSOLE_PID=""
MONITOR_PIDS=()
STOP_GRACE_SECONDS=5
STOP_KILL_SECONDS=2
SCRATCH_PRESERVED=false
BUILD_PRESERVED=false
CURRENT_CELL=""
CURRENT_CELL_DIR=""
CELL_ALREADY_COMPLETE=false
PHASE=initialization
COMPLETED_CELLS=0
CAMPAIGN_DONE=false
INTERRUPTED=false

fail() { echo "ERROR: $*" >&2; exit 1; }
timestamp() { date -u +%Y-%m-%dT%H:%M:%SZ; }
file_hash() { sha256sum -- "$1" | awk '{print $1}'; }
hash_directory() {
    (cd -- "$1" && find . -type f ! -path ./SHA256SUMS -print0 | sort -z |
        while IFS= read -r -d '' file; do sha256sum -- "${file}" || exit 1; done) >"$1/SHA256SUMS"
}
# Benchmark settings for generation and every fork. BaseBench reads them from the working directory;
# the fixture must survive between forks, and CSV metric writes would add unrelated I/O.
expected_settings() {
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
    ' "${MODULE_DIR}/settings.txt"
}
fixture_key() {
    printf '%s\n' layout_version=1 leaf_count=$(( NUM_FILES * NUM_RECORDS )) num_files="${NUM_FILES}" \
        num_records="${NUM_RECORDS}" key_size=32 record_size=128 num_threads="${NUM_THREADS}"
}
fixture_inventory() {
    (cd -- "$1" && find . -type f -printf '%P\t%s\t%T@\n' | sort)
}
# Checks the saved fixture against the settings, workload and file list recorded when it was generated.
validate_fixture() {
    local destination="$1" file
    [[ -d "${FIXTURE_ROOT}" && ! -L "${FIXTURE_ROOT}" && -d "${FIXTURE_DIR}" ]] \
        || fail "The saved fixture is missing: ${FIXTURE_DIR}. Generate one with --generate-fixture."
    [[ -z "$(find "${FIXTURE_ROOT}" -type l -print -quit)" ]] \
        || fail "The saved fixture must not contain symbolic links."
    for file in settings.txt fixture-key.txt inventory.txt origin.txt; do
        [[ -f "${FIXTURE_ROOT}/${file}" ]] || fail "The saved fixture is incomplete: ${file} is missing."
    done
    expected_settings | cmp -s - "${FIXTURE_ROOT}/settings.txt" \
        || fail "The saved fixture was generated with other benchmark settings."
    fixture_key | cmp -s - "${FIXTURE_ROOT}/fixture-key.txt" || fail "The saved fixture workload key differs."
    fixture_inventory "${FIXTURE_DIR}" >"${destination}"
    if ! cmp -s "${FIXTURE_ROOT}/inventory.txt" "${destination}"; then
        diff "${FIXTURE_ROOT}/inventory.txt" "${destination}" | head -20 >&2 || true
        fail "Saved fixture files changed since it was generated (differences above)."
    fi
}
setting_label() {
    case "$1" in
        main) echo main ;;
        range) echo "range-f$2" ;;
        pool) echo "pool-t$2" ;;
    esac
}
write_manifest() {
    {
        echo "status=$1"
        echo "exit_code=$2"
        echo "run_id=${RUN_ID}"
        echo "updated_at=$(timestamp)"
        echo "phase=${PHASE}"
        echo "current_cell=${CURRENT_CELL}"
        echo "completed_cells=${COMPLETED_CELLS}"
        echo "expected_cells=${EXPECTED_CELLS}"
        echo "expected_timing_forks=${EXPECTED_TIMING}"
        echo "expected_jfr_forks=${EXPECTED_JFR}"
        echo "expected_micro_runs=${#MICRO_THREADS[@]}"
        echo "main_ref=${MAIN_REF}"
        echo "pool_ref=${POOL_REF}"
        echo "fixture_mode=${FIXTURE_MODE}"
        echo "snapshot_force=false"
        echo "scratch_preserved=${SCRATCH_PRESERVED}"
        echo "build_preserved=${BUILD_PRESERVED}"
        [[ "${SCRATCH_PRESERVED}" != true ]] || echo "retained_scratch=${SCRATCH_DIR}"
        [[ "${BUILD_PRESERVED}" != true ]] || echo "retained_build=${BUILD_DIR}"
    } >"${RESULTS_DIR}/manifest.txt"
}
# Columns: phase, block, variant, longListWriteThreads, implementation.
write_plan() {
    local block_index impl_offset setting_offset implementation setting
    for block_index in "${!BLOCKS[@]}"; do
        for impl_offset in "${!IMPLEMENTATIONS[@]}"; do
            implementation="${IMPLEMENTATIONS[$(( (impl_offset + 2 * block_index) % ${#IMPLEMENTATIONS[@]} ))]}"
            for setting_offset in "${!SETTINGS[@]}"; do
                setting="${SETTINGS[$(( (setting_offset + 5 * block_index + 3 * impl_offset) % ${#SETTINGS[@]} ))]}"
                printf 'timing\t%s\t%s\t%s\t%s\n' "${BLOCKS[${block_index}]}" "${setting%%:*}" "${setting##*:}" \
                    "${implementation}"
            done
        done
    done
    for implementation in "${IMPLEMENTATIONS[@]}"; do
        for setting in "${JFR_SETTINGS[@]}"; do
            printf 'jfr\tJ\t%s\t%s\t%s\n' "${setting%%:*}" "${setting##*:}" "${implementation}"
        done
    done
    for setting in "${MICRO_THREADS[@]}"; do
        printf 'micro\tM\tpool\t%s\t-\n' "${setting}"
    done
}
remove_owned_directory() {
    local directory="$1" prefix="$2"
    [[ -n "${directory}" ]] || return 0
    [[ -d "${directory}" && ! -L "${directory}" \
        && "$(dirname -- "${directory}")" == "${SCRATCH_PARENT}" \
        && "$(basename -- "${directory}")" == "${prefix}".* \
        && -f "${directory}/.campaign-owner" \
        && "$(<"${directory}/.campaign-owner")" == "${RUN_ID}" \
        && "$(cd -- "${directory}" && pwd -P)" == "${directory}" ]] \
        || { echo "Refusing to remove unverified scratch: ${directory}" >&2; return 1; }
    rm -rf -- "${directory}"
}
remove_owned_build() {
    [[ -n "${BUILD_DIR}" ]] || return 0
    remove_owned_directory "${BUILD_DIR}" campaign-build || return 1
    BUILD_DIR=""
}
collect_cell_logs() {
    [[ -n "${SCRATCH_DIR}" && -d "${SCRATCH_DIR}" && -n "${CURRENT_CELL_DIR}" ]] || return 0
    [[ ! -f "${SCRATCH_DIR}/settingsUsed.txt" ]] \
        || cp -- "${SCRATCH_DIR}/settingsUsed.txt" "${CURRENT_CELL_DIR}/settingsUsed.txt"
    if [[ -d "${SCRATCH_DIR}/output" ]]; then
        mkdir -p -- "${CURRENT_CELL_DIR}/logs"
        cp -R -- "${SCRATCH_DIR}/output/." "${CURRENT_CELL_DIR}/logs/"
    fi
}
owned_group_alive() {
    # Include the direct child during the brief window before setsid establishes its group.
    kill -0 -- "-$1" 2>/dev/null || kill -0 "$1" 2>/dev/null
}
stop_owned_group() {
    local pid="$1" label="$2" deadline
    # Callers pass only group leaders created by this runner with setsid.
    [[ "${pid}" =~ ^[0-9]+$ && "${pid}" -gt 1 && "${pid}" != "$$" ]] || return 1
    if owned_group_alive "${pid}"; then
        kill -TERM -- "-${pid}" 2>/dev/null || true
        kill -TERM "${pid}" 2>/dev/null || true
        deadline=$((SECONDS + STOP_GRACE_SECONDS))
        while owned_group_alive "${pid}" && (( SECONDS < deadline )); do sleep 0.1; done
        if owned_group_alive "${pid}"; then
            echo "Stopping remaining owned ${label} processes with KILL (group ${pid})." >&2
            kill -KILL -- "-${pid}" 2>/dev/null || true
            kill -KILL "${pid}" 2>/dev/null || true
            deadline=$((SECONDS + STOP_KILL_SECONDS))
            while owned_group_alive "${pid}" && (( SECONDS < deadline )); do sleep 0.1; done
        fi
    fi
    if owned_group_alive "${pid}"; then
        echo "Owned ${label} process group ${pid} is still present; preserving its files." >&2
        return 1
    fi
    wait "${pid}" 2>/dev/null || true
}
stop_monitoring() {
    local pid rc=0
    local -a remaining=()
    for pid in "${MONITOR_PIDS[@]}"; do
        if ! stop_owned_group "${pid}" sampler; then
            remaining+=("${pid}")
            rc=1
        fi
    done
    MONITOR_PIDS=("${remaining[@]}")
    return "${rc}"
}
finish_run() {
    local rc=$? status=failed processes_stopped=true
    trap - EXIT INT TERM
    set +e
    if [[ -n "${ACTIVE_PID}" ]]; then
        stop_owned_group "${ACTIVE_PID}" command || { rc=1; processes_stopped=false; }
    fi
    if [[ -n "${CONSOLE_PID}" ]]; then
        stop_owned_group "${CONSOLE_PID}" console || { rc=1; processes_stopped=false; }
    fi
    stop_monitoring || { rc=1; processes_stopped=false; }
    cd -- "${REPO_ROOT}" || rc=1
    if [[ "${processes_stopped}" == true ]]; then
        collect_cell_logs || rc=1
        remove_owned_directory "${SCRATCH_DIR}" run || rc=1
    elif [[ -n "${SCRATCH_DIR}" ]]; then
        SCRATCH_PRESERVED=true
        echo "Scratch retained for inspection: ${SCRATCH_DIR}" >&2
    fi
    if [[ -n "${BUILD_DIR}" && ( "${ACTIVE_BUILD}" == true || "${processes_stopped}" != true ) ]]; then
        # Even --no-daemon may use a detached single-use Gradle daemon outside our group.
        BUILD_PRESERVED=true
        echo "Build directory retained for inspection: ${BUILD_DIR}" >&2
        echo "A detached Gradle worker may still be using it; no unrelated process was stopped." >&2
    else
        remove_owned_build || rc=1
    fi
    if [[ "${INTERRUPTED}" == true ]]; then
        status=interrupted
    elif [[ "${CAMPAIGN_DONE}" == true && "${rc}" == 0 ]]; then
        status=complete
        PHASE=complete
    elif (( rc == 0 )); then
        rc=1
    fi
    if [[ -n "${CURRENT_CELL_DIR}" && -d "${CURRENT_CELL_DIR}" ]]; then
        printf 'status=%s\nexit_code=%s\nupdated_at=%s\n' "${status}" "${rc}" "$(timestamp)" \
            >"${CURRENT_CELL_DIR}/attempt-status.txt"
    fi
    write_manifest "${status}" "${rc}"
    printf '%s\t%s\t%s\n' "$(timestamp)" "${status}" "${rc}" >>"${RESULTS_DIR}/sessions.tsv"
    if [[ "${processes_stopped}" != true ]]; then
        echo "Live owned processes remain. Results were retained at ${RESULTS_DIR}; no unstable archive was created." >&2
        exit "${rc}"
    fi
    if ! hash_directory "${RESULTS_DIR}"; then
        rc=1
        status=failed
        write_manifest "${status}" "${rc}"
    fi
    if tar -C "${RESULTS_PARENT}" -czf "${ARCHIVE}.partial.$$" "${RUN_ID}" \
        && mv -- "${ARCHIVE}.partial.$$" "${ARCHIVE}"; then
        echo "Results archive (${status}): ${ARCHIVE}"
        echo "Copy it to your computer with:"
        echo "  scp ${USER:-$(id -un)}@$(hostname -f):${ARCHIVE} ."
        if [[ "${status}" != complete ]]; then
            if [[ -d "${FIXTURE_DIR}" && -f "${FROZEN_DIR}/SHA256SUMS" ]]; then
                echo "To continue the measurement: bash ${SCRIPT_PATH} --resume ${RUN_ID}"
            else
                echo "This run cannot be resumed; fix the error above and start a new run."
            fi
        fi
    else
        echo "Archive creation failed; results remain at ${RESULTS_DIR}" >&2
        rc=1
        write_manifest failed "${rc}"
        hash_directory "${RESULTS_DIR}"
    fi
    exit "${rc}"
}
run_logged() {
    local log="$1" rc=0
    shift
    setsid "$@" >"${log}" 2>&1 &
    ACTIVE_PID=$!
    setsid tail --pid="${ACTIVE_PID}" -n +1 -f "${log}" &
    CONSOLE_PID=$!
    wait "${ACTIVE_PID}" || rc=$?
    # A reaped leader alone is insufficient: descendants can keep this group alive.
    stop_owned_group "${ACTIVE_PID}" command || return 1
    ACTIVE_PID=""
    wait "${CONSOLE_PID}" || rc=1
    CONSOLE_PID=""
    return "${rc}"
}
# Builds one variant from an exact commit: a git archive copy, the variant's patches, the campaign
# benchmark sources, then the JMH jar. Benchmarks only ever run the frozen copy of that jar.
build_artifact() {
    local variant="$1" ref="${MAIN_REF}" source artifact patch file
    local -a patches=() jars=()
    [[ "${variant}" != pool ]] || ref="${POOL_REF}"
    [[ "${variant}" != range ]] || patches+=(range.patch)
    patches+=("${COMMON_PATCHES[@]}")
    PHASE="build-${variant}"
    write_manifest running 0
    BUILD_DIR="$(mktemp -d "${SCRATCH_PARENT}/campaign-build.XXXXXX")"
    printf '%s\n' "${RUN_ID}" >"${BUILD_DIR}/.campaign-owner"
    source="${BUILD_DIR}/source"
    artifact="${BUILD_DIR}/artifact"
    mkdir -- "${source}" "${artifact}"
    git -C "${REPO_ROOT}" archive --format=tar "${ref}" | tar -x -C "${source}"
    # The copy lies inside this clone; the ceiling keeps git apply and the build out of the clone's repository.
    for patch in "${patches[@]}"; do
        (cd -- "${source}" && GIT_CEILING_DIRECTORIES="${BUILD_DIR}" git apply --check "${FROZEN_DIR}/${patch}") \
            || fail "${patch} does not apply to ${ref}."
        (cd -- "${source}" && GIT_CEILING_DIRECTORIES="${BUILD_DIR}" git apply "${FROZEN_DIR}/${patch}")
        cp -- "${FROZEN_DIR}/${patch}" "${artifact}/"
    done
    for file in "${CAMPAIGN_SOURCES[@]}"; do
        cp -- "${FROZEN_DIR}/${file}" "${source}/${BENCHMARK_SOURCE_DIR}/${file}"
    done
    printf '%s\n' "${ref}" >"${artifact}/source-ref.txt"
    printf '%s\n' "${patches[@]}" >"${artifact}/patches.txt"
    printf '%s\n' "${JAVA_VERSION_TEXT}" >"${artifact}/java-version.txt"
    local -a command=(env "GIT_CEILING_DIRECTORIES=${BUILD_DIR}" "${source}/gradlew" -p "${source}"
        --no-daemon --console=plain :swirlds-benchmarks:jmhJar)
    printf '%q ' "${command[@]}" >"${artifact}/build-command.txt"
    printf '\n' >>"${artifact}/build-command.txt"
    echo "Building ${variant} from ${ref} with ${patches[*]}."
    ACTIVE_BUILD=true
    run_logged "${RESULTS_DIR}/build-${variant}.log" "${command[@]}" || fail "The ${variant} build failed."
    ACTIVE_BUILD=false
    cp -- "${RESULTS_DIR}/build-${variant}.log" "${artifact}/build.log"
    shopt -s nullglob
    jars=("${source}/platform-sdk/swirlds-benchmarks/build/libs/"*-jmh.jar)
    shopt -u nullglob
    (( ${#jars[@]} == 1 )) || fail "Expected exactly one newly built JMH jar for ${variant}."
    cp -- "${jars[0]}" "${artifact}/jmh.jar"
    "${JAVA}" -jar "${artifact}/jmh.jar" -lp >"${artifact}/benchmarks.txt" 2>&1
    grep -q "^${BENCHMARK%\$}\$" "${artifact}/benchmarks.txt" \
        && grep -q "^${MICRO_BENCHMARK}.atomicReferenceCheck\$" "${artifact}/benchmarks.txt" \
        && grep -q 'param "longListImplementation" = {SEGMENT, DISK, HEAP, OFF_HEAP, DISK_SEGMENT}' \
            "${artifact}/benchmarks.txt" \
        && grep -q 'param "longListWriteThreads"' "${artifact}/benchmarks.txt" \
        || fail "The ${variant} jar is missing the campaign benchmarks or parameters."
    hash_directory "${artifact}"
    find "${artifact}" -type f -exec chmod a-w {} +
    mv -- "${artifact}" "${FROZEN_DIR}/${variant}"
    printf '%s\t%s\t%s\t%s\n' "${variant}" "${ref}" "${patches[*]}" "$(file_hash "${FROZEN_DIR}/${variant}/jmh.jar")" \
        >>"${FROZEN_DIR}/artifacts.tsv"
    remove_owned_build
}
validate_cell_json() {
    local json="$1" implementation="$2" threads="$3"
    jq -e --arg implementation "${implementation}" --arg threads "${threads}" --arg numFiles "${NUM_FILES}" \
        --arg numRecords "${NUM_RECORDS}" --arg maxKey "${MAX_KEY}" --arg numThreads "${NUM_THREADS}" '
        type == "array" and length == 1 and
        (.[0] as $r |
            $r.benchmark == "com.swirlds.benchmark.MerkleDbSnapshotBenchmark.snapshot" and
            $r.params.longListImplementation == $implementation and $r.params.longListWriteThreads == $threads and
            $r.params.prepareFixtureOnly == "false" and
            $r.params.numFiles == $numFiles and $r.params.numRecords == $numRecords and
            $r.params.maxKey == $maxKey and $r.params.keySize == "32" and $r.params.recordSize == "128" and
            $r.params.numThreads == $numThreads and
            ($r.jvmArgs | index("-Dbenchmark.requireExistingFixture=true")) != null and
            $r.mode == "ss" and $r.threads == 1 and $r.forks == 1 and
            $r.warmupIterations == 1 and $r.measurementIterations == 3 and
            $r.primaryMetric.scoreUnit == "ms/op" and
            ($r.primaryMetric.rawData | length) == 1 and
            ($r.primaryMetric.rawData[0] | length) == 3 and
            all($r.primaryMetric.rawData[0][]; type == "number" and isfinite and . > 0))
    ' "${json}" >/dev/null
}
validate_micro_json() {
    local json="$1" threads="$2"
    jq -e --argjson threads "${threads}" --arg prefix "${MICRO_BENCHMARK}." '
        type == "array" and length == 2 and
        ([.[].benchmark] | sort) == [$prefix + "atomicReferenceCheck", $prefix + "plainFieldCheck"] and
        all(.[]; .threads == $threads and .mode == "avgt" and .forks == 3 and
            .primaryMetric.scoreUnit == "ns/op" and (.primaryMetric.score | type == "number" and isfinite))
    ' "${json}" >/dev/null
}
start_monitoring() {
    local sampler
    mkdir -p -- "${RESULTS_DIR}/sampling"
    printf '%s\n' between-cells >"${RESULTS_DIR}/sampling/current-cell.txt"
    for sampler in top vmstat diskstats meminfo; do
        printf '# session_started_utc=%s\n' "$(timestamp)" >>"${RESULTS_DIR}/sampling/${sampler}.log"
        setsid bash "${FROZEN_DIR}/runner.sh" --sample "${sampler}" \
            "${RESULTS_DIR}/sampling/current-cell.txt" >>"${RESULTS_DIR}/sampling/${sampler}.log" 2>&1 &
        MONITOR_PIDS+=("$!")
    done
    sleep 1
    check_monitoring
}
check_monitoring() {
    local pid
    for pid in "${MONITOR_PIDS[@]}"; do
        kill -0 "${pid}" 2>/dev/null || fail "A sampler stopped; inspect sampling logs."
    done
}
publish_completed_attempt() {
    local cell_root="$1" attempt_dir="${CURRENT_CELL_DIR}"
    hash_directory "${attempt_dir}"
    find "${attempt_dir}" -type f -exec chmod a-w {} +
    # The exit trap must never rewrite a finalized, checksummed attempt.
    CURRENT_CELL_DIR=""
    printf '%s\n' "$(basename -- "${attempt_dir}")" >"${cell_root}/complete.pending.$$"
    mv -- "${cell_root}/complete.pending.$$" "${cell_root}/complete"
}
# Sets CELL_ALREADY_COMPLETE for a cell whose completed attempt still validates; otherwise prepares
# a new attempt directory.
begin_cell() {
    local cell_root="$1" validator="$2" attempt
    shift 2
    CELL_ALREADY_COMPLETE=false
    mkdir -p -- "${cell_root}"
    if [[ -f "${cell_root}/complete" ]]; then
        attempt="$(<"${cell_root}/complete")"
        [[ "${attempt}" =~ ^attempt\.[A-Za-z0-9]+$ ]] || fail "Invalid completed attempt for ${CURRENT_CELL}."
        (cd -- "${cell_root}/${attempt}" && sha256sum --check --quiet SHA256SUMS) \
            || fail "Completed cell checksum failure: ${CURRENT_CELL}"
        "${validator}" "${cell_root}/${attempt}/result.json" "$@" \
            || fail "Completed cell JSON is invalid: ${CURRENT_CELL}"
        COMPLETED_CELLS=$((COMPLETED_CELLS + 1))
        CELL_ALREADY_COMPLETE=true
        echo "Already complete: ${CURRENT_CELL}"
        return 0
    fi
    CURRENT_CELL_DIR="$(mktemp -d "${cell_root}/attempt.XXXXXX")"
    printf 'status=running\ncell=%s\nstarted_at=%s\n' "${CURRENT_CELL}" "$(timestamp)" \
        >"${CURRENT_CELL_DIR}/attempt-status.txt"
    printf '%s\tstart\t%s\n' "$(timestamp)" "${CURRENT_CELL}" >>"${RESULTS_DIR}/sampling/cell-boundaries.tsv"
    printf '%s\n' "${CURRENT_CELL}" >"${RESULTS_DIR}/sampling/current-cell.txt"
    PHASE="cell-${CURRENT_CELL}"
    write_manifest running 0
    echo "Running ${CURRENT_CELL} ($((COMPLETED_CELLS + 1))/${EXPECTED_CELLS}) — $(timestamp)"
}
finish_cell() {
    local cell_root="$1"
    printf 'status=complete\nexit_code=0\nupdated_at=%s\n' "$(timestamp)" >"${CURRENT_CELL_DIR}/attempt-status.txt"
    publish_completed_attempt "${cell_root}"
    COMPLETED_CELLS=$((COMPLETED_CELLS + 1))
    printf '%s\tcomplete\t%s\n' "$(timestamp)" "${CURRENT_CELL}" >>"${RESULTS_DIR}/sampling/cell-boundaries.tsv"
    printf '%s\n' between-cells >"${RESULTS_DIR}/sampling/current-cell.txt"
    check_monitoring
}
# Writes text summaries next to a recording; the raw recording stays the source of truth.
summarize_recording() {
    local recording="$1" view
    "${JFR_TOOL}" summary "${recording}" >"${CURRENT_CELL_DIR}/jfr-summary.txt" 2>&1 || true
    for view in "${JFR_VIEWS[@]}"; do
        "${JFR_TOOL}" view --width 250 "${view}" "${recording}" >"${CURRENT_CELL_DIR}/jfr-view-${view}.txt" 2>&1 || true
    done
}
run_cell() {
    local phase="$1" block="$2" variant="$3" threads="$4" implementation="$5"
    local cell_root jvm_args="${JVM_ARGS}"
    CURRENT_CELL="${block}-$(setting_label "${variant}" "${threads}")-${implementation}"
    cell_root="${RESULTS_DIR}/cells/${CURRENT_CELL}"
    begin_cell "${cell_root}" validate_cell_json "${implementation}" "${threads}"
    [[ "${CELL_ALREADY_COMPLETE}" == false ]] || return 0
    SCRATCH_DIR="$(mktemp -d "${SCRATCH_PARENT}/run.XXXXXX")"
    printf '%s\n' "${RUN_ID}" >"${SCRATCH_DIR}/.campaign-owner"
    printf '%s\n' "${SCRATCH_DIR}" >"${CURRENT_CELL_DIR}/scratch-path.txt"
    cp -- "${FROZEN_DIR}/settings.txt" "${SCRATCH_DIR}/settings.txt"
    mkdir -p -- "${SCRATCH_DIR}/data/MerkleDbSnapshotBenchmark"
    cp -al -- "${FIXTURE_DIR}" "${SCRATCH_DIR}/data/MerkleDbSnapshotBenchmark/"
    [[ -d "${SCRATCH_DIR}/data/MerkleDbSnapshotBenchmark/${FIXTURE_NAME}" ]] \
        || fail "The fixture link was not installed; refusing to launch JMH."
    if [[ "${phase}" == jfr ]]; then
        jvm_args+=" -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"
        jvm_args+=" -XX:FlightRecorderOptions:repository=${SCRATCH_DIR}/jfr-repository"
        jvm_args+=" -XX:StartFlightRecording:filename=${CURRENT_CELL_DIR}/recording.jfr,settings=profile"
        jvm_args+=",maxsize=4g,dumponexit=true,${JFR_EVENT_SETTINGS}"
    fi
    local -a command=("${JAVA}" "-Djava.io.tmpdir=${SCRATCH_PARENT}/jmh-tmp"
        -jar "${FROZEN_DIR}/${variant}/jmh.jar" "${BENCHMARK}"
        -p "longListImplementation=${implementation}" -p "longListWriteThreads=${threads}"
        -p "numFiles=${NUM_FILES}" -p "numRecords=${NUM_RECORDS}" -p "maxKey=${MAX_KEY}"
        -p keySize=32 -p recordSize=128 -p "numThreads=${NUM_THREADS}"
        -t 1 -bm ss -tu ms -wi 1 -i 3 -f 1 -to 60m -foe true -rf json
        -rff "${CURRENT_CELL_DIR}/result.json" -jvmArgs "${jvm_args}")
    printf '%q ' "${command[@]}" >"${CURRENT_CELL_DIR}/command.txt"
    printf '\n' >>"${CURRENT_CELL_DIR}/command.txt"
    cat "${CURRENT_CELL_DIR}/command.txt" >>"${RESULTS_DIR}/commands.txt"
    cd -- "${SCRATCH_DIR}"
    run_logged "${CURRENT_CELL_DIR}/console.log" "${command[@]}" || fail "JMH failed for ${CURRENT_CELL}."
    cd -- "${REPO_ROOT}"
    validate_cell_json "${CURRENT_CELL_DIR}/result.json" "${implementation}" "${threads}" \
        || fail "Invalid result JSON for ${CURRENT_CELL}."
    if [[ "${phase}" == jfr ]]; then
        [[ -s "${CURRENT_CELL_DIR}/recording.jfr" ]] || fail "No flight recording for ${CURRENT_CELL}."
        summarize_recording "${CURRENT_CELL_DIR}/recording.jfr"
    fi
    collect_cell_logs
    remove_owned_directory "${SCRATCH_DIR}" run
    SCRATCH_DIR=""
    finish_cell "${cell_root}"
}
run_micro() {
    local threads="$1" cell_root
    CURRENT_CELL="M-micro-t${threads}"
    cell_root="${RESULTS_DIR}/cells/${CURRENT_CELL}"
    begin_cell "${cell_root}" validate_micro_json "${threads}"
    [[ "${CELL_ALREADY_COMPLETE}" == false ]] || return 0
    local -a command=("${JAVA}" -jar "${FROZEN_DIR}/pool/jmh.jar" "${MICRO_BENCHMARK}" -t "${threads}"
        -rf json -rff "${CURRENT_CELL_DIR}/result.json" -jvmArgs "-Xms1g -Xmx1g")
    printf '%q ' "${command[@]}" >"${CURRENT_CELL_DIR}/command.txt"
    printf '\n' >>"${CURRENT_CELL_DIR}/command.txt"
    cat "${CURRENT_CELL_DIR}/command.txt" >>"${RESULTS_DIR}/commands.txt"
    cd -- "${SCRATCH_PARENT}/jmh-tmp"
    run_logged "${CURRENT_CELL_DIR}/console.log" "${command[@]}" || fail "JMH failed for ${CURRENT_CELL}."
    cd -- "${REPO_ROOT}"
    validate_micro_json "${CURRENT_CELL_DIR}/result.json" "${threads}" || fail "Invalid result JSON for ${CURRENT_CELL}."
    finish_cell "${cell_root}"
}
# Generates the fixture with the main build, then publishes it with the settings, workload key and
# file list that later runs check. Nothing is published unless generation and validation succeed.
generate_fixture() {
    local pending="${FIXTURE_ROOT}.pending" generated started_at
    PHASE=fixture-generation
    CURRENT_CELL=fixture-generation
    write_manifest running 0
    [[ ! -e "${FIXTURE_ROOT}" && ! -L "${FIXTURE_ROOT}" ]] \
        || fail "A saved fixture already exists at ${FIXTURE_ROOT}; nothing was generated."
    [[ ! -e "${pending}" && ! -L "${pending}" ]] \
        || fail "An unpublished fixture is left at ${pending}; inspect and remove it before generating."
    CURRENT_CELL_DIR="${RESULTS_DIR}/fixture-generation"
    mkdir -- "${CURRENT_CELL_DIR}"
    SCRATCH_DIR="$(mktemp -d "${SCRATCH_PARENT}/run.XXXXXX")"
    printf '%s\n' "${RUN_ID}" >"${SCRATCH_DIR}/.campaign-owner"
    printf '%s\n' "${SCRATCH_DIR}" >"${CURRENT_CELL_DIR}/scratch-path.txt"
    cp -- "${FROZEN_DIR}/settings.txt" "${SCRATCH_DIR}/settings.txt"
    local -a command=("${JAVA}" "-Djava.io.tmpdir=${SCRATCH_PARENT}/jmh-tmp"
        -jar "${FROZEN_DIR}/main/jmh.jar" "${BENCHMARK}"
        -p longListImplementation=SEGMENT -p longListWriteThreads=1 -p prepareFixtureOnly=true
        -p "numFiles=${NUM_FILES}" -p "numRecords=${NUM_RECORDS}" -p "maxKey=${MAX_KEY}"
        -p keySize=32 -p recordSize=128 -p "numThreads=${NUM_THREADS}"
        -t 1 -bm ss -tu ms -wi 0 -i 1 -f 1 -to "${GENERATION_TIMEOUT}" -foe true -rf json
        -rff "${CURRENT_CELL_DIR}/result.json" -jvmArgs "${GENERATION_JVM_ARGS}")
    printf '%q ' "${command[@]}" >"${CURRENT_CELL_DIR}/command.txt"
    printf '\n' >>"${CURRENT_CELL_DIR}/command.txt"
    started_at="$(timestamp)"
    echo "Generating the fixture with the main build; October's took 2 to 9 hours — ${started_at}"
    cd -- "${SCRATCH_DIR}"
    run_logged "${CURRENT_CELL_DIR}/console.log" "${command[@]}" || fail "Fixture generation failed."
    cd -- "${REPO_ROOT}"
    generated="${SCRATCH_DIR}/data/MerkleDbSnapshotBenchmark/${FIXTURE_NAME}"
    [[ -d "${generated}" && ! -L "${generated}" ]] || fail "Generation did not produce ${FIXTURE_NAME}."
    collect_cell_logs
    mkdir -- "${pending}"
    mv -- "${generated}" "${pending}/"
    remove_owned_directory "${SCRATCH_DIR}" run
    SCRATCH_DIR=""
    cp -- "${FROZEN_DIR}/settings.txt" "${pending}/settings.txt"
    fixture_key >"${pending}/fixture-key.txt"
    fixture_inventory "${pending}/${FIXTURE_NAME}" >"${pending}/inventory.txt"
    {
        echo "run_id=${RUN_ID}"
        echo "generated_by=main build of ${MAIN_REF}"
        echo "jar_sha256=$(file_hash "${FROZEN_DIR}/main/jmh.jar")"
        echo "started_at=${started_at}"
        echo "finished_at=$(timestamp)"
    } >"${pending}/origin.txt"
    sync -f -- "${pending}"
    mv -T -- "${pending}" "${FIXTURE_ROOT}"
    cp -- "${FIXTURE_ROOT}/origin.txt" "${FIXTURE_ROOT}/inventory.txt" "${CURRENT_CELL_DIR}/"
    hash_directory "${CURRENT_CELL_DIR}"
    CURRENT_CELL_DIR=""
    CURRENT_CELL=""
    echo "Saved the fixture: ${FIXTURE_DIR} — $(timestamp)"
}
describe_plan() {
    echo "Builds: main = ${MAIN_REF}; range = main + range.patch; pool = ${POOL_REF}."
    echo "Every build also gets ${COMMON_PATCHES[*]} and the campaign benchmark sources."
    echo "With --generate-fixture, the main build first generates the fixture; October's generation took"
    echo "  2 to 9 hours. Without it, the saved fixture is reused."
    echo "Timing: ${#BLOCKS[@]} rotated blocks x ${#SETTINGS[@]} settings x ${#IMPLEMENTATIONS[@]} implementations"
    echo "  = ${EXPECTED_TIMING} forks, each 1 warmup + 3 measured snapshots of the 1B-leaf fixture."
    echo "Profiling: ${EXPECTED_JFR} JFR forks (${JFR_SETTINGS[*]}), kept out of the timing tables."
    echo "Microbenchmark: the snapshotFailure check with ${MICRO_THREADS[*]} threads."
    echo "Expect about 5.5 hours plus any generation: ~30 min builds, ~4.5 h timing, ~25 min profiling and"
    echo "  microbenchmark."
    echo "Saved fixture: ${FIXTURE_DIR}"
    echo "Plan columns: phase, block, variant, longListWriteThreads, implementation."
}
main() {
    if [[ "${DRY_RUN}" == true ]]; then
        echo "Plan only: no writes, builds or benchmarks."
        describe_plan
        write_plan
        return 0
    fi
    [[ "$(uname -s)" == Linux ]] || fail "Run on Linux; --dry-run is available elsewhere."
    local tool i version phase block variant threads implementation
    for tool in "${JAVA}" "${JFR_TOOL}" git sha256sum jq top vmstat stdbuf setsid tail flock sync tar gzip find sort cmp diff; do
        command -v "${tool}" >/dev/null || fail "Required command is missing: ${tool}"
    done
    JAVA_VERSION_TEXT="$("${JAVA}" -version 2>&1)"
    version="$(printf '%s\n' "${JAVA_VERSION_TEXT}" | awk -F'"' 'NR == 1 {print $2}')"
    [[ "${version}" == 25.0.2* ]] || fail "Java 25.0.2 is required; set JAVA_HOME."
    if [[ "${GENERATE_FIXTURE}" == true ]]; then
        [[ ! -e "${FIXTURE_ROOT}" && ! -L "${FIXTURE_ROOT}" ]] \
            || fail "A saved fixture already exists at ${FIXTURE_ROOT}; run without --generate-fixture to reuse it."
    else
        [[ -d "${FIXTURE_ROOT}" && ! -L "${FIXTURE_ROOT}" ]] \
            || fail "No saved fixture at ${FIXTURE_ROOT}; run with --generate-fixture to generate one."
    fi
    mkdir -p -- "${SCRATCH_PARENT}"
    SCRATCH_PARENT="$(cd -- "${SCRATCH_PARENT}" && pwd -P)"
    mkdir -p -- "${RESULTS_PARENT}" "${SCRATCH_PARENT}/jmh-tmp"
    exec 9>"${SCRATCH_PARENT}/snapshot-campaign.lock"
    flock -n 9 || fail "Another campaign runner owns the lock. Nothing was stopped."
    if [[ -n "${RESUME_RUN}" ]]; then
        [[ -d "${RESULTS_DIR}" && ! -L "${RESULTS_DIR}" && -f "${FROZEN_DIR}/SHA256SUMS" ]] \
            || fail "No frozen inputs for run: ${RUN_ID}"
        (cd -- "${FROZEN_DIR}" && sha256sum --check --quiet SHA256SUMS) || fail "Frozen input checksum failure."
        cmp -s "${SCRIPT_PATH}" "${FROZEN_DIR}/runner.sh" || fail "Resume with the unchanged runner recorded by this run."
        printf '%s\n' "${JAVA_VERSION_TEXT}" | cmp -s - "${FROZEN_DIR}/java-version.txt" \
            || fail "The current JDK differs from this run's frozen JDK. Select its recorded JDK before resuming."
        write_plan | cmp -s - "${FROZEN_DIR}/plan.tsv" || fail "The plan differs from this run's frozen plan."
    else
        for i in "${MAIN_REF}" "${POOL_REF}"; do
            git -C "${REPO_ROOT}" cat-file -e "${i}^{commit}" \
                || fail "Commit ${i} is unavailable; fetch the campaign branch first."
        done
        for i in range.patch "${COMMON_PATCHES[@]}" "${CAMPAIGN_SOURCES[@]}" check-system.sh; do
            [[ -f "${SCRIPT_DIR}/${i}" ]] || fail "Missing campaign input: ${SCRIPT_DIR}/${i}"
        done
        mkdir -- "${RESULTS_DIR}"
        mkdir -- "${FROZEN_DIR}"
    fi
    trap finish_run EXIT
    trap 'INTERRUPTED=true; exit 130' INT
    trap 'INTERRUPTED=true; exit 143' TERM
    printf '%s\tstarted\t%s\n' "$(timestamp)" "${RESUME_RUN:-new}" >>"${RESULTS_DIR}/sessions.tsv"
    write_manifest running 0
    if [[ -z "${RESUME_RUN}" ]]; then
        [[ "${GENERATE_FIXTURE}" == true ]] || validate_fixture "${RESULTS_DIR}/fixture-inventory-before.txt"
        expected_settings >"${FROZEN_DIR}/settings.txt"
        cp -- "${SCRIPT_PATH}" "${FROZEN_DIR}/runner.sh"
        for i in range.patch "${COMMON_PATCHES[@]}" "${CAMPAIGN_SOURCES[@]}" check-system.sh; do
            cp -- "${SCRIPT_DIR}/${i}" "${FROZEN_DIR}/"
        done
        printf '%s\n' "${JAVA_VERSION_TEXT}" >"${FROZEN_DIR}/java-version.txt"
        write_plan >"${FROZEN_DIR}/plan.tsv"
        {
            echo "Recorded at: $(timestamp)"
            echo "Launcher checkout: $(git -C "${REPO_ROOT}" rev-parse HEAD)"
            "${JAVA}" -version
            uname -a
            lscpu
            free -g
            lsblk -o NAME,MODEL,SIZE,ROTA,TRAN,TYPE,MOUNTPOINTS
            findmnt -T "${SCRATCH_PARENT}" -o TARGET,SOURCE,FSTYPE,OPTIONS
            df -hT "${SCRATCH_PARENT}"
            sysctl vm.dirty_ratio vm.dirty_background_ratio vm.dirty_bytes vm.dirty_background_bytes \
                vm.dirty_expire_centisecs vm.dirty_writeback_centisecs vm.swappiness
            echo "transparent_hugepage: $(cat /sys/kernel/mm/transparent_hugepage/enabled)"
            echo "cpu0 governor: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
            ulimit -a
        } >"${FROZEN_DIR}/environment.txt" 2>&1 || true
        # Check only before builds and before measurement, never during benchmark load.
        bash "${FROZEN_DIR}/check-system.sh" "${MODULE_DIR}" "${FIXTURE_MODE}" \
            | tee "${RESULTS_DIR}/pre-build-system-check.txt"
        for variant in "${VARIANTS[@]}"; do build_artifact "${variant}"; done
        hash_directory "${FROZEN_DIR}"
        find "${FROZEN_DIR}" -type f -exec chmod a-w {} +
        if [[ "${GENERATE_FIXTURE}" == true ]]; then
            generate_fixture
            validate_fixture "${RESULTS_DIR}/fixture-inventory-before.txt"
        fi
        mkdir -- "${RESULTS_DIR}/fixture"
        cp -- "${FIXTURE_ROOT}/fixture-key.txt" "${FIXTURE_ROOT}/origin.txt" "${FIXTURE_ROOT}/inventory.txt" \
            "${RESULTS_DIR}/fixture/"
    fi
    validate_fixture "${RESULTS_DIR}/fixture-inventory-session-before.txt"
    PHASE=measurement-system-check
    write_manifest running 0
    sync -f -- "${RESULTS_DIR}"
    bash "${FROZEN_DIR}/check-system.sh" "${MODULE_DIR}" reuse | tee -a "${RESULTS_DIR}/measurement-system-check.txt"
    start_monitoring
    while IFS=$'\t' read -r phase block variant threads implementation; do
        case "${phase}" in
            timing | jfr) run_cell "${phase}" "${block}" "${variant}" "${threads}" "${implementation}" ;;
            micro) run_micro "${threads}" ;;
            *) fail "Unknown plan phase: ${phase}" ;;
        esac
    done <"${FROZEN_DIR}/plan.tsv"
    stop_monitoring
    CURRENT_CELL=""
    PHASE=fixture-verification
    validate_fixture "${RESULTS_DIR}/fixture-inventory-after.txt"
    (( COMPLETED_CELLS == EXPECTED_CELLS )) || fail "Expected ${EXPECTED_CELLS} completed cells; found ${COMPLETED_CELLS}."
    CAMPAIGN_DONE=true
}

main
