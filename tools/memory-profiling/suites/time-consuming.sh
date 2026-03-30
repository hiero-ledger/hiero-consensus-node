#!/usr/bin/env bash
# Time Consuming suite - runs all subtasks sequentially (matches CI: hl-cn-hapi-lin-lg)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" time-consuming-full \
  './gradlew hapiTestTimeConsuming --rerun-tasks --no-build-cache && ./gradlew hapiTestTimeConsumingSerial --rerun-tasks --no-build-cache'
