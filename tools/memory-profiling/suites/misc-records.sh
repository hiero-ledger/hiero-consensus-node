#!/usr/bin/env bash
# Misc Records suite - runs all subtasks sequentially (matches CI: hl-cn-hapi-lin-xl)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" misc-records-full \
  './gradlew hapiTestMiscRecords --rerun-tasks --no-build-cache && ./gradlew hapiTestMiscRecordsSerial --rerun-tasks --no-build-cache'
