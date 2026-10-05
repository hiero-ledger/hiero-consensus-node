#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Records the parts of the machine that decide how stable benchmark results are: CPU model and topology, the CPUs and
# memory the container may use, CPU throttling, the CPU frequency, the node, and the load that other workloads put on
# the host.
#
# Usage:
#   record-benchmark-environment.sh                     prints a snapshot; run it before and after a benchmark run
#   record-benchmark-environment.sh --monitor SECONDS   prints one CSV line of counters every SECONDS until killed
#
# The snapshot starts with "key=value" lines between "== summary" and "== end summary", for tools; the sections after
# it hold the raw sources. The difference of the throttling counters before and after a run shows whether the run was
# throttled. /proc/stat, /proc/loadavg, /proc/pressure, /proc/meminfo and the boot id are not namespaced, so inside a
# container they describe the whole host: host CPU time minus the container's own CPU time is what other workloads use.
set -o pipefail

CGROUP=/sys/fs/cgroup

read_first() {
  # prints the first line of the first readable file, or nothing
  local file
  for file in "$@"; do
    if [[ -r "${file}" ]]; then
      head -n 1 "${file}" 2> /dev/null
      return
    fi
  done
}

cgroup_stat() {
  # prints the value of a key in the cgroup's cpu.stat (v2) or cpu.stat/cpuacct (v1)
  local key="$1"
  if [[ -r "${CGROUP}/cpu.stat" ]]; then
    awk -v k="${key}" '$1 == k { print $2 }' "${CGROUP}/cpu.stat"
  elif [[ -r "${CGROUP}/cpu/cpu.stat" ]]; then
    case "${key}" in
      nr_periods | nr_throttled) awk -v k="${key}" '$1 == k { print $2 }' "${CGROUP}/cpu/cpu.stat" ;;
      throttled_usec) awk '$1 == "throttled_time" { print int($2 / 1000) }' "${CGROUP}/cpu/cpu.stat" ;;
      usage_usec) awk '{ print int($1 / 1000) }' "${CGROUP}/cpuacct/cpuacct.usage" 2> /dev/null ;;
    esac
  fi
}

expand_cpu_list() {
  # prints one CPU number per line for a list such as "0-5,24-29"
  tr ',' '\n' <<< "$1" | awk -F- '{ for (c = $1; c <= ($2 == "" ? $1 : $2); c++) print c }'
}

hot_cpu_columns() {
  # The CPU that the busiest thread of this container runs on (the benchmark thread, while a benchmark runs), its
  # clock, how busy its hyperthread sibling and the CPUs sharing its L3 cache were in the last interval, and the
  # highest clock of any CPU. $1 holds the busy fraction of every CPU in the last interval, one "cpu fraction" per line.
  local busy="$1" cpu sibling l3 topology=/sys/devices/system/cpu
  cpu=$(ps -eLo psr=,pcpu= --sort=-pcpu 2> /dev/null | awk 'NR == 1 { print $1 }')
  if [[ -z "${cpu}" || ! -r "${topology}/cpu${cpu}/topology/thread_siblings_list" ]]; then
    echo ",,,,,"
    return
  fi
  sibling=$(expand_cpu_list "$(cat "${topology}/cpu${cpu}/topology/thread_siblings_list")" | grep -vx "${cpu}" | head -n 1)
  l3=$(expand_cpu_list "$(cat "${topology}/cpu${cpu}/cache/index3/shared_cpu_list" 2> /dev/null)")
  awk -v cpu="${cpu}" -v sibling="${sibling}" -v l3="$(tr '\n' ' ' <<< "${l3}")" '
    BEGIN { n = split(l3, members, " "); for (i = 1; i <= n; i++) in_l3[members[i]] = 1 }
    NR == FNR { fraction[$1] = $2; next }
    /^processor/ { p = $3 }
    /^cpu MHz/ { mhz = $4 + 0; if (p == cpu) own = mhz; if (mhz > max) max = mhz }
    END {
      for (c in in_l3) if (c != cpu) l3_busy += fraction[c]
      printf "%s,%.0f,%.3f,%.3f,%d,%.0f\n", cpu, own, fraction[sibling], l3_busy, n, max
    }' <(echo "header 0"; echo "${busy}") /proc/cpuinfo
}

monitor() {
  local interval="$1" previous="" current busy
  echo "epoch,cpu_user,cpu_nice,cpu_system,cpu_idle,cpu_iowait,cpu_irq,cpu_softirq,cpu_steal,procs_running,load1,host_cpu_some_avg10,cg_usage_usec,cg_nr_periods,cg_nr_throttled,cg_throttled_usec,cg_cpu_some_avg10,mhz_mean,hot_cpu,hot_cpu_mhz,sibling_busy,l3_others_busy,l3_cpus,mhz_max"
  while true; do
    local stat procs load host_psi cg_psi mhz
    stat=$(awk '$1 == "cpu" { print $2 "," $3 "," $4 "," $5 "," $6 "," $7 "," $8 "," $9; exit }' /proc/stat 2> /dev/null)
    procs=$(awk '$1 == "procs_running" { print $2 }' /proc/stat 2> /dev/null)
    load=$(cut -d ' ' -f 1 /proc/loadavg 2> /dev/null || sysctl -n vm.loadavg 2> /dev/null | cut -d ' ' -f 2)
    host_psi=$(awk '$1 == "some" { sub("avg10=", "", $2); print $2 }' /proc/pressure/cpu 2> /dev/null)
    cg_psi=$(awk '$1 == "some" { sub("avg10=", "", $2); print $2 }' "${CGROUP}/cpu.pressure" 2> /dev/null)
    mhz=$(awk -F: '/^cpu MHz/ { s += $2; n++ } END { if (n) printf "%.0f", s / n }' /proc/cpuinfo 2> /dev/null)
    # busy and total jiffies of every CPU, and the busy fraction of every CPU since the previous sample
    current=$(awk '/^cpu[0-9]/ { print substr($1, 4), $2 + $3 + $4 + $7 + $8, $2 + $3 + $4 + $5 + $6 + $7 + $8 + $9 }' \
      /proc/stat 2> /dev/null)
    busy=$(awk 'NR == FNR { b[$1] = $2; t[$1] = $3; next }
      ($1 in t) && $3 > t[$1] { printf "%s %.3f\n", $1, ($2 - b[$1]) / ($3 - t[$1]) }' \
      <(echo "${previous}") <(echo "${current}") 2> /dev/null)
    previous="${current}"
    echo "${EPOCHREALTIME:-$(date +%s)},${stat:-,,,,,,,},${procs},${load},${host_psi},$(cgroup_stat usage_usec),$(cgroup_stat nr_periods),$(cgroup_stat nr_throttled),$(cgroup_stat throttled_usec),${cg_psi},${mhz},$(hot_cpu_columns "${busy}")"
    sleep "${interval}"
  done
}

if [[ "$1" == "--monitor" ]]; then
  monitor "${2:-5}"
  exit 0
fi

java_cmd="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
available_processors=""
if command -v "${java_cmd}" > /dev/null; then
  probe_dir=$(mktemp -d)
  echo 'class P { public static void main(String[] a) { System.out.println(Runtime.getRuntime().availableProcessors()); } }' \
    > "${probe_dir}/P.java"
  available_processors=$("${java_cmd}" "${probe_dir}/P.java" 2> /dev/null)
  rm -rf "${probe_dir}"
fi

# CPUs this process may run on, and their hyperthread siblings
allowed_cpus=$(awk '/^Cpus_allowed_list/ { print $2 }' /proc/self/status 2> /dev/null)
siblings=""
if [[ -n "${allowed_cpus}" ]]; then
  siblings=$(for cpu in /sys/devices/system/cpu/cpu[0-9]*/topology/thread_siblings_list; do cat "${cpu}"; done 2> /dev/null \
    | sort -u | tr '\n' ' ')
fi

section() {
  echo
  echo "== $1"
}

show_file() {
  if [[ -r "$1" ]]; then
    echo "-- $1"
    cat "$1"
  fi
}

section "summary"
echo "date=$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
echo "hostname=$(hostname)"
echo "boot_id=$(read_first /proc/sys/kernel/random/boot_id)"
echo "host_boot_epoch=$(awk '$1 == "btime" { print $2 }' /proc/stat 2> /dev/null)"
echo "kernel=$(uname -r)"
if command -v lscpu > /dev/null; then
  echo "cpu_model=$(lscpu | awk -F: '/^Model name/ { gsub(/^ +/, "", $2); print $2; exit }')"
  echo "host_cpus=$(lscpu | awk -F: '/^CPU\(s\)/ { gsub(/ /, "", $2); print $2; exit }')"
  echo "threads_per_core=$(lscpu | awk -F: '/^Thread\(s\) per core/ { gsub(/ /, "", $2); print $2; exit }')"
  echo "sockets=$(lscpu | awk -F: '/^Socket\(s\)/ { gsub(/ /, "", $2); print $2; exit }')"
  echo "hypervisor=$(lscpu | awk -F: '/^Hypervisor vendor/ { gsub(/ /, "", $2); print $2; exit }')"
else
  echo "cpu_model=$(sysctl -n machdep.cpu.brand_string 2> /dev/null)"
  echo "host_cpus=$(sysctl -n hw.logicalcpu 2> /dev/null)"
fi
echo "smt_active=$(read_first /sys/devices/system/cpu/smt/active)"
echo "nproc=$(nproc 2> /dev/null || sysctl -n hw.ncpu 2> /dev/null)"
echo "jvm_available_processors=${available_processors}"
echo "allowed_cpus=${allowed_cpus}"
echo "cpu_governor=$(read_first /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
echo "cpu_max_khz=$(read_first /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq)"
echo "intel_no_turbo=$(read_first /sys/devices/system/cpu/intel_pstate/no_turbo)"
echo "cpufreq_boost=$(read_first /sys/devices/system/cpu/cpufreq/boost)"
echo "dmi_product=$(read_first /sys/class/dmi/id/product_name)"
echo "dmi_vendor=$(read_first /sys/class/dmi/id/sys_vendor)"
echo "host_mem_kb=$(awk '$1 == "MemTotal:" { print $2 }' /proc/meminfo 2> /dev/null)"
echo "cgroup_cpu_max=$(read_first "${CGROUP}/cpu.max")"
echo "cgroup_cfs_quota_us=$(read_first "${CGROUP}/cpu/cpu.cfs_quota_us")"
echo "cgroup_cpu_weight=$(read_first "${CGROUP}/cpu.weight")"
echo "cgroup_cpuset=$(read_first "${CGROUP}/cpuset.cpus.effective" "${CGROUP}/cpuset/cpuset.effective_cpus")"
echo "cgroup_memory_max=$(read_first "${CGROUP}/memory.max" "${CGROUP}/memory/memory.limit_in_bytes")"
echo "cgroup_memory_peak=$(read_first "${CGROUP}/memory.peak" "${CGROUP}/memory/memory.max_usage_in_bytes")"
echo "cgroup_usage_usec=$(cgroup_stat usage_usec)"
echo "cgroup_nr_periods=$(cgroup_stat nr_periods)"
echo "cgroup_nr_throttled=$(cgroup_stat nr_throttled)"
echo "cgroup_throttled_usec=$(cgroup_stat throttled_usec)"
echo "load=$(cut -d ' ' -f 1-3 /proc/loadavg 2> /dev/null || sysctl -n vm.loadavg 2> /dev/null)"
echo "steal_ticks=$(awk '$1 == "cpu" { print $9; exit }' /proc/stat 2> /dev/null)"
section "end summary"

section "kernel"
uname -a

section "cpu"
if command -v lscpu > /dev/null; then
  lscpu
  echo "-- thread siblings: ${siblings}"
else
  sysctl -n machdep.cpu.brand_string hw.physicalcpu hw.logicalcpu hw.perflevel0.physicalcpu hw.perflevel1.physicalcpu \
    2> /dev/null
fi
for file in scaling_governor scaling_driver scaling_cur_freq scaling_min_freq scaling_max_freq cpuinfo_max_freq; do
  show_file "/sys/devices/system/cpu/cpu0/cpufreq/${file}"
done
show_file /sys/devices/system/cpu/intel_pstate/status
show_file /sys/devices/system/cpu/intel_pstate/no_turbo
show_file /sys/devices/system/cpu/cpufreq/boost
if [[ -r /proc/cpuinfo ]]; then
  echo "-- cpu MHz of every CPU (/proc/cpuinfo)"
  awk -F: '/^cpu MHz/ { printf "%.0f ", $2 } END { print "" }' /proc/cpuinfo
fi

section "cgroup"
show_file /proc/self/cgroup
for file in cpu.max cpu.weight cpuset.cpus.effective cpu.stat cpu.pressure memory.max memory.high memory.current \
  memory.peak cpu/cpu.cfs_quota_us cpu/cpu.cfs_period_us cpu/cpu.shares cpu/cpu.stat cpuset/cpuset.effective_cpus \
  memory/memory.limit_in_bytes; do
  show_file "${CGROUP}/${file}"
done
show_file /proc/self/status

section "host"
show_file /proc/loadavg
show_file /proc/pressure/cpu
show_file /proc/pressure/memory
show_file /proc/stat
show_file /proc/meminfo
show_file /proc/uptime
uptime

section "environment variable names"
# names only: the values may hold credentials
env | cut -d = -f 1 | sort | tr '\n' ' '
echo

section "java"
"${java_cmd}" -XshowSettings:system -version 2>&1
