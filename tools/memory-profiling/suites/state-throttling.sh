#!/usr/bin/env bash
# State Throttling suite - single task (matches CI: hl-cn-hapi-lin-md)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" state-throttling-full \
  './gradlew hapiTestStateThrottling --rerun-tasks --no-build-cache'
