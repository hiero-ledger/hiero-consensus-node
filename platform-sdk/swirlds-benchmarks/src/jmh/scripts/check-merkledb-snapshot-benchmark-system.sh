#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Advisory system checks for the complete 1B-leaf MerkleDB snapshot campaign.

set -u

if (( $# > 1 )); then
    echo "Usage: $0 [benchmark-filesystem-path]" >&2
    exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
CHECK_PATH="${1:-${MODULE_DIR}}"
if [[ ! -d "${CHECK_PATH}" ]]; then
    echo "Not a directory: ${CHECK_PATH}" >&2
    exit 2
fi

echo "Complete MerkleDB snapshot benchmark system check"
echo "Operating system: $(uname -a)"
if [[ "$(uname -s)" != Linux ]]; then
    echo "WARNING: The full campaign is intended to run on Linux."
fi

echo
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA="${JAVA_HOME}/bin/java"
else
    JAVA="java"
fi
if command -v "${JAVA}" >/dev/null; then
    java_version="$("${JAVA}" -version 2>&1 | awk -F'"' 'NR == 1 { print $2 }')"
    echo "Java: ${java_version} (${JAVA})"
    if [[ "${java_version}" != 25.0.2* ]]; then
        echo "WARNING: Java 25.0.2 is required for the campaign."
    fi
else
    echo "WARNING: Java not found; set JAVA_HOME to the JDK 25.0.2 installation."
fi

echo
if [[ -r /proc/meminfo ]]; then
    ram_kib="$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)"
    echo "RAM: $((ram_kib / 1024 / 1024)) GiB"
    if (( ram_kib < 64 * 1024 * 1024 )); then
        echo "WARNING: At least 64 GiB RAM is recommended (32 GiB heap, 16 GiB direct memory, plus OS/cache)."
    fi
else
    echo "RAM: unavailable (no /proc/meminfo)"
fi

echo
echo "Benchmark filesystem: ${CHECK_PATH}"
if command -v findmnt >/dev/null; then
    findmnt -T "${CHECK_PATH}" -o TARGET,SOURCE,FSTYPE,OPTIONS
fi
if [[ "$(uname -s)" == Linux ]]; then
    df -hT "${CHECK_PATH}"
else
    df -h "${CHECK_PATH}"
fi
disk_kib="$(df -Pk "${CHECK_PATH}" | awk 'NR == 2 { print $4 }')"
if [[ "${disk_kib}" =~ ^[0-9]+$ ]] && (( disk_kib < 400 * 1024 * 1024 )); then
    echo "WARNING: At least 400 GiB free is recommended for fixture generation, indexes, snapshot, and frozen JAR."
fi
echo "The runner also requires 32 GiB free after fixture preparation."

echo
echo "CPU (up to 96 LongList writers at P=32):"
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
echo "System check complete; warnings do not prevent the runner from starting."
