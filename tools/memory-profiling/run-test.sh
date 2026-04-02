#!/usr/bin/env bash
# Usage: ./run-test.sh <test_name> <gradle_command>
# Runs a single test in a fresh Docker container and saves cgroup peak memory to results/
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ $# -lt 2 ]; then
    echo "Usage: $0 <test_name> <test_command>"
    exit 1
fi

TEST_NAME="$1"
shift
TEST_CMD="$*"
RESULT_FILE="/results/${TEST_NAME}.txt"
LOG_FILE="$SCRIPT_DIR/results/${TEST_NAME}.log"

export HOST_UID=$(id -u)
export HOST_GID=$(id -g)

# Fresh container for each test (clean cgroup peak)
docker compose down 2>/dev/null || true
# Only rebuild if image doesn't exist or Dockerfile/scripts changed
docker compose build --quiet 2>/dev/null || docker compose build
docker compose up -d || { echo "Failed to start container"; exit 1; }

# Wait for container to be running
for i in 1 2 3 4 5; do
  if docker compose exec -T memory-measure true 2>/dev/null; then
    break
  fi
  if [ "$i" -eq 5 ]; then
    echo "ERROR: Container failed to become ready after 5s" >&2
    docker compose down
    exit 1
  fi
  sleep 1
done

echo "================================================================"
echo "  TEST: $TEST_NAME"
echo "  CMD:  $TEST_CMD"
echo "================================================================"
echo ""

# Run test inside container, passing command via env var to avoid shell injection
# Capture all output to log file while streaming to terminal
docker compose exec -T \
  -e TEST_NAME="$TEST_NAME" \
  -e RESULT_FILE="$RESULT_FILE" \
  -e TEST_CMD="$TEST_CMD" \
  memory-measure bash -c '
    /usr/local/bin/measure-cgroup-peak.sh bash -lc "$TEST_CMD"
' 2>&1 | tee "$LOG_FILE"
EXIT_CODE=${PIPESTATUS[0]}

# If the result file was written (test completed far enough for measurement),
# remove the log to keep results/ clean. Otherwise keep it for debugging.
if [ -f "$SCRIPT_DIR/results/${TEST_NAME}.txt" ] && [ $EXIT_CODE -eq 0 ]; then
    rm -f "$LOG_FILE"
else
    # Prepend failure metadata to the log
    {
        echo "======== FAILURE LOG ========"
        echo "Test:      $TEST_NAME"
        echo "Command:   $TEST_CMD"
        echo "Exit code: $EXIT_CODE"
        echo "Time:      $(date '+%Y-%m-%d %H:%M:%S')"
        echo "============================="
        echo ""
        cat "$LOG_FILE"
    } > "${LOG_FILE}.tmp"
    mv "${LOG_FILE}.tmp" "$LOG_FILE"
    echo ""
    echo "  Failure log saved to: results/${TEST_NAME}.log"
fi

echo ""
echo "================================================================"
echo "  DONE: $TEST_NAME (exit code: $EXIT_CODE)"
if [ -f "$SCRIPT_DIR/results/${TEST_NAME}.txt" ]; then
    echo "  Results saved to: results/${TEST_NAME}.txt"
else
    echo "  No results file - check: results/${TEST_NAME}.log"
fi
echo "================================================================"

docker compose down

exit $EXIT_CODE
