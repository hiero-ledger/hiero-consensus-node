#!/usr/bin/env bash
set -euo pipefail

# Detect cgroup version
if [[ -f /sys/fs/cgroup/memory.peak ]]; then
  CGROUP_VERSION="v2"
  PEAK_FILE="/sys/fs/cgroup/memory.peak"
  END_FILE="/sys/fs/cgroup/memory.current"
elif [[ -f /sys/fs/cgroup/memory/memory.max_usage_in_bytes ]]; then
  CGROUP_VERSION="v1"
  PEAK_FILE="/sys/fs/cgroup/memory/memory.max_usage_in_bytes"
  END_FILE="/sys/fs/cgroup/memory/memory.usage_in_bytes"
else
  echo "ERROR: Cannot find cgroup memory files" >&2
  exit 1
fi

echo "Using cgroup $CGROUP_VERSION for memory measurement"

# Reset peak counter (cgroup v1 only; v2 starts fresh per container)
if [[ "$CGROUP_VERSION" == "v1" ]]; then
  echo 0 > "$PEAK_FILE" 2>/dev/null || true
fi

# Record wall-clock start time
START_EPOCH=$(date +%s)

# Run the command as builder user
runuser -u builder -- "$@"
EXIT_CODE=$?

# Record wall-clock end time
END_EPOCH=$(date +%s)
DURATION_SECS=$((END_EPOCH - START_EPOCH))
DURATION_MIN=$((DURATION_SECS / 60))
DURATION_SEC_REM=$((DURATION_SECS % 60))

# Read peak memory
PEAK_BYTES=$(cat "$PEAK_FILE" 2>/dev/null || echo 0)
END_BYTES=$(cat "$END_FILE" 2>/dev/null || echo 0)

PEAK_MIB=$((PEAK_BYTES / 1024 / 1024))
PEAK_GIB_INT=$((PEAK_BYTES / 1024 / 1024 / 1024))
PEAK_GIB_FRAC=$(( (PEAK_BYTES / 1024 / 1024 % 1024) * 10 / 1024 ))
END_MIB=$((END_BYTES / 1024 / 1024))
END_GIB_INT=$((END_BYTES / 1024 / 1024 / 1024))
END_GIB_FRAC=$(( (END_BYTES / 1024 / 1024 % 1024) * 10 / 1024 ))

# Write results to a file if RESULT_FILE is set
if [[ -n "${RESULT_FILE:-}" ]]; then
  {
    echo "test_name=${TEST_NAME:-unknown}"
    echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git_sha=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "cgroup_version=${CGROUP_VERSION}"
    echo "peak_bytes=${PEAK_BYTES}"
    echo "peak_mib=${PEAK_MIB}"
    echo "peak_gib=${PEAK_GIB_INT}.${PEAK_GIB_FRAC}"
    echo "end_mib=${END_MIB}"
    echo "end_gib=${END_GIB_INT}.${END_GIB_FRAC}"
    echo "duration_secs=${DURATION_SECS}"
    echo "exit_code=${EXIT_CODE}"
  } > "${RESULT_FILE}"
fi

echo ""
echo "======== CGROUP MEMORY MEASUREMENT ========"
echo "Cgroup version: $CGROUP_VERSION"
echo "Peak memory:    ${PEAK_MIB} MiB (~${PEAK_GIB_INT}.${PEAK_GIB_FRAC} GiB)"
echo "End memory:     ${END_MIB} MiB (~${END_GIB_INT}.${END_GIB_FRAC} GiB)"
echo "Raw peak bytes: ${PEAK_BYTES}"
echo "Duration:       ${DURATION_MIN}m ${DURATION_SEC_REM}s (${DURATION_SECS}s)"
echo "Exit code:      $EXIT_CODE"
echo "============================================"

exit $EXIT_CODE
