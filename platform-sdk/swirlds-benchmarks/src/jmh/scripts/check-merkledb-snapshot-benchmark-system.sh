#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Read-only readiness check for the 1B-leaf campaign. Requires standard Linux /proc and procps tools.
# Exit 1 means do not start yet; nothing is killed or deleted.

set -euo pipefail
export LC_ALL=C

if (( $# > 2 )); then
    echo "Usage: $0 [benchmark-module-directory [current-run-directory]]" >&2
    exit 2
fi
[[ "$(uname -s)" == Linux ]] || { echo "This check must run on the Linux benchmark machine." >&2; exit 2; }

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${1:-${SCRIPT_DIR}/../../..}" && pwd -P)"
SCRATCH_PARENT="${MODULE_DIR}/build/tmp/merkledb-snapshot-1b-campaign"
if [[ -d "${SCRATCH_PARENT}" ]]; then
    SCRATCH_PARENT="$(cd -- "${SCRATCH_PARENT}" && pwd -P)"
fi
CURRENT_RUN="${2:-}"
CHECK_PATH="${SCRATCH_PARENT}"
[[ -d "${CHECK_PATH}" ]] || CHECK_PATH="${MODULE_DIR}"
CHECK_FAILED=0
fail() { echo "NOT READY: $*"; CHECK_FAILED=1; }

# Only the runner's own marked directory may be excluded from the leftover check.
if [[ -n "${CURRENT_RUN}" ]]; then
    [[ -d "${CURRENT_RUN}" && ! -L "${CURRENT_RUN}" && -f "${CURRENT_RUN}/.campaign-owner" \
        && "$(dirname -- "${CURRENT_RUN}")" == "${SCRATCH_PARENT}" \
        && "$(basename -- "${CURRENT_RUN}")" == run.* ]] \
        || { echo "Invalid current-run directory: ${CURRENT_RUN}" >&2; exit 2; }
fi

echo "MerkleDB snapshot readiness check — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Host: $(hostname); $(uname -sr); $(getconf _NPROCESSORS_ONLN) logical CPUs"
JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
if command -v "${JAVA}" >/dev/null; then
    java_version="$("${JAVA}" -version 2>&1 | awk -F'"' 'NR == 1 { print $2 }')"
    echo "Java: ${java_version}"
    [[ "${java_version}" == 25.0.2* ]] || fail "Java 25.0.2 is required."
else
    fail "Java not found; set JAVA_HOME to JDK 25.0.2."
fi

echo
echo "Logged-in sessions (a session alone does not mean the machine is busy):"
who
echo
echo "Benchmark filesystem:"
findmnt -T "${CHECK_PATH}" -o TARGET,SOURCE,FSTYPE,OPTIONS || fail "Cannot describe the benchmark filesystem."
df -hT "${CHECK_PATH}"
disk_kib="$(df -Pk "${CHECK_PATH}" | awk 'NR == 2 { print $4 }')"
(( disk_kib >= 400 * 1024 * 1024 )) || fail "Need at least 400 GiB free before fixture generation."

echo
echo "Leftover campaign directories:"
shopt -s nullglob
leftovers=("${SCRATCH_PARENT}"/run.* "${MODULE_DIR}"/data/MerkleDbSnapshotBenchmark)
leftover_count=0
for directory in "${leftovers[@]}"; do
    [[ -e "${directory}" || -L "${directory}" ]] || continue
    [[ "${directory}" != "${CURRENT_RUN}" ]] || continue
    du -sh -- "${directory}"
    leftover_count=$((leftover_count + 1))
done
if (( leftover_count == 0 )); then
    echo "None. Saved results/JARs and jmh-tmp are intentionally kept."
else
    fail "Inspect these directories before retrying; they could belong to an active run. Nothing was deleted."
fi

echo
echo "Other Java/JMH benchmark processes:"
benchmark_processes="$(ps -eo pid=,user=,comm=,args= | awk '
    $3 == "java" && /ForkedMain|org[.]openjdk[.]jmh[.]Main|MerkleDbSnapshotBenchmark|jmh[.]jar/ {
        print $1, $2, $3
    }')"
if [[ -n "${benchmark_processes}" ]]; then
    printf '%s\n' "${benchmark_processes}"
    fail "A benchmark JVM is still running (PIDs above). Nothing was stopped."
else
    echo "None."
fi

# Compare counters over five seconds. ps %cpu is a lifetime average and can hide current activity.
cpu_counters() {
    awk '$1 == "cpu" { for (i=2; i<=9; i++) total += $i; printf "%.0f %.0f %.0f\n", total, $5, $6; exit }' /proc/stat
}
swap_counters() {
    awk '/^pswpin / { input=$2 } /^pswpout / { output=$2 } END { print input+0, output+0 }' /proc/vmstat
}
# Check the whole backing disk, including I/O from other partitions on the same device.
device_numbers=unavailable
# findmnt's formatted output can pad MAJ:MIN with spaces; never include them in the device path.
if ! filesystem_device="$(findmnt -rn -o MAJ:MIN -T "${CHECK_PATH}" | awk 'NF { print $1 }')" \
    || [[ ! "${filesystem_device}" =~ ^[0-9]+:[0-9]+$ ]]; then
    fail "Cannot identify the benchmark filesystem device; storage activity is unverified."
elif ! device_numbers="$(lsblk -snro MAJ:MIN,TYPE "/dev/block/${filesystem_device}" | awk '$2 == "disk" { print $1 }' | sort -u)" \
    || [[ ! "${device_numbers}" =~ ^[0-9]+:[0-9]+$ ]]; then
    fail "Cannot identify one backing disk; this storage layout needs a manual activity check."
    device_numbers=unavailable
fi
disk_counters() {
    awk -v device="${device_numbers}" '$1 ":" $2 == device { print $6, $10, $13; found=1; exit }
        END { if (!found) print "unavailable" }' /proc/diskstats
}
echo
echo "Sampling CPU, processes, swap and benchmark-disk activity for 5 seconds..."
read -r cpu_total_before cpu_idle_before cpu_wait_before < <(cpu_counters)
read -r swap_in_before swap_out_before < <(swap_counters)
disk_before="$(disk_counters)"
processes_before="$(ps -eo pid=,user=,cputimes=,rss=,comm=)"
read -r time_before _ < /proc/uptime
sleep 5
read -r time_after _ < /proc/uptime
processes_after="$(ps -eo pid=,user=,cputimes=,rss=,comm=)"
read -r cpu_total_after cpu_idle_after cpu_wait_after < <(cpu_counters)
read -r swap_in_after swap_out_after < <(swap_counters)
disk_after="$(disk_counters)"
elapsed="$(awk -v start="${time_before}" -v end="${time_after}" 'BEGIN { print end-start }')"
read -r busy_pct wait_pct < <(awk -v total="$((cpu_total_after - cpu_total_before))" \
    -v idle="$((cpu_idle_after - cpu_idle_before))" -v wait="$((cpu_wait_after - cpu_wait_before))" \
    'BEGIN { if (total <= 0) { print "100 100"; exit }; printf "%.1f %.1f\n", 100*(total-idle-wait)/total, 100*wait/total }')
echo "CPU: ${busy_pct}% busy, ${wait_pct}% waiting for I/O (whole machine)"
if awk -v busy="${busy_pct}" -v wait="${wait_pct}" 'BEGIN { exit !(busy >= 5 || wait >= 1) }'; then
    fail "CPU must be below 5% busy and 1% I/O wait for a quiet baseline."
fi
if (( swap_in_after != swap_in_before || swap_out_after != swap_out_before )); then
    fail "Swap activity occurred during the sample."
else
    echo "Swap activity: none (old occupied swap alone is not a problem)."
fi

process_usage="$(awk -v seconds="${elapsed}" '
    NR == FNR { cpu[$1]=$3; next }
    { used=$3-cpu[$1]; if (used < 0) used=0;
      printf "%8.1f %7s %-16s %9.1f %s\n", 100*used/seconds, $1, $2, $4/1024, $5 }
    ' <(printf '%s\n' "${processes_before}") <(printf '%s\n' "${processes_after}") | sort -k1,1nr)"
echo
echo "Top 5 sampled CPU consumers (approximate; 100% = one core):"
printf '%8s %7s %-16s %9s %s\n' '%CPU' PID USER RSS_MiB COMMAND
printf '%s\n' "${process_usage}" | sed -n '1,5p'
if awk '$1 >= 20 { busy=1 } END { exit !busy }' <<<"${process_usage}"; then
    # Whole-second process counters are coarse; the overall CPU check above controls readiness.
    echo "WARNING: Background CPU activity detected; review the processes above when comparing results."
fi
echo
echo "Top 5 memory consumers:"
printf '%7s %-16s %9s %s\n' PID USER RSS_MiB COMMAND
ps -eo pid=,user=,rss=,comm= --sort=-rss | awk 'NR <= 5 { printf "%7s %-16s %9.1f %s\n", $1, $2, $3/1024, $4 }'
read -r total_kib available_kib < <(awk '/^MemTotal:/ { total=$2 } /^MemAvailable:/ { available=$2 }
    END { print total, available }' /proc/meminfo)
awk -v total="${total_kib}" -v available="${available_kib}" \
    'BEGIN { printf "RAM: %.1f GiB available of %.1f GiB (reclaimable cache included)\n", available/1048576, total/1048576 }'
if (( available_kib < 64 * 1024 * 1024 || available_kib * 100 < total_kib * 80 )); then
    fail "Need at least 64 GiB and 80% of RAM available before starting."
fi

echo
if [[ "${disk_before}" == unavailable || "${disk_after}" == unavailable ]]; then
    fail "Cannot measure activity for benchmark device ${device_numbers}; inspect storage activity before starting."
else
    read -r read_before write_before io_before <<<"${disk_before}"
    read -r read_after write_after io_after <<<"${disk_after}"
    read -r io_mib io_pct < <(awk -v seconds="${elapsed}" \
        -v sectors="$((read_after - read_before + write_after - write_before))" -v ms="$((io_after - io_before))" \
        'BEGIN { printf "%.2f %.1f\n", sectors/2048/seconds, ms/10/seconds }')
    echo "Benchmark backing disk (${device_numbers}): ${io_mib} MiB/s read+write, ${io_pct}% busy"
    if awk -v rate="${io_mib}" -v busy="${io_pct}" 'BEGIN { exit !(rate >= 1 || busy >= 5) }'; then
        fail "Benchmark disk must be below 1 MiB/s and 5% busy before starting."
    fi
fi

echo
if (( CHECK_FAILED )); then
    echo "NOT READY — resolve the findings above, then check again."
    exit 1
fi
echo "READY NOW — resource and leftover-fixture checks passed; note any warnings above."
echo "This is not a reservation: keep the machine exclusive for the entire run."
