#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks the recommended system resources for the LongList snapshot benchmark.
# Warnings do not prevent the benchmark runner from starting.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"

echo "LongList snapshot benchmark system check"
echo

operating_system="$(uname -s)"
echo "Operating system: $(uname -a)"
if [[ "${operating_system}" != "Linux" ]]; then
    echo "WARNING: Linux is recommended"
fi

echo
if command -v java >/dev/null; then
    java_version="$(java -version 2>&1 | awk -F'"' 'NR == 1 { print $2 }')"
    echo "Java: ${java_version}"
    if [[ "${java_version}" != 25.0.2* ]]; then
        echo "WARNING: Java 25.0.2 is required by this branch"
    fi
else
    echo "Java: not found"
    echo "WARNING: Java 25 is required to build and run the benchmark"
fi

echo
if [[ -r /proc/meminfo ]]; then
    ram_kib="$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)"
    echo "RAM: $((ram_kib / 1024 / 1024)) GiB"
    if (( ram_kib < 110 * 1024 * 1024 )); then
        echo "WARNING: At least 110 GiB RAM is recommended for the 5B largest-chunk case"
    fi
else
    echo "RAM: unavailable"
    echo "WARNING: Could not determine total RAM"
fi

echo
echo "Benchmark filesystem (must support sparse files):"
if command -v findmnt >/dev/null; then
    findmnt -T "${MODULE_DIR}" -o TARGET,SOURCE,FSTYPE,OPTIONS
else
    echo "findmnt unavailable"
fi
if [[ "${operating_system}" == "Linux" ]]; then
    df -hT "${MODULE_DIR}"
else
    df -h "${MODULE_DIR}"
fi
disk_kib="$(df -Pk "${MODULE_DIR}" | awk 'NR == 2 { print $4 }')"
if (( disk_kib * 1024 < 140000000000 )); then
    echo "WARNING: At least 140 GB free disk space is recommended"
fi

echo
echo "CPU:"
if command -v lscpu >/dev/null; then
    lscpu
else
    echo "lscpu unavailable"
fi

echo
echo "Storage devices:"
if command -v lsblk >/dev/null; then
    lsblk -o NAME,MODEL,SIZE,ROTA,TRAN,TYPE,MOUNTPOINTS
else
    echo "lsblk unavailable"
fi

echo
echo "LongListDisk cache diagnostic:"
if command -v python3 >/dev/null \
    && python3 -c 'import os,sys; sys.exit(not hasattr(os,"posix_fadvise") or not hasattr(os,"POSIX_FADV_DONTNEED"))'; then
    echo "Python supports per-file cache eviction"
else
    echo "WARNING: Python does not support POSIX_FADV_DONTNEED"
fi
if command -v fincore >/dev/null; then
    echo "fincore: $(command -v fincore)"
else
    echo "WARNING: fincore is required to verify cache eviction"
fi

echo
echo "System check complete; warnings do not prevent the benchmark runner from starting."
