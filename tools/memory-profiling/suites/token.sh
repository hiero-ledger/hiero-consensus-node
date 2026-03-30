#!/usr/bin/env bash
# Token suite - runs all subtasks sequentially (matches CI: hl-cn-hapi-lin-xl)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" token-full \
  './gradlew hapiTestToken --no-daemon --rerun-tasks --no-build-cache && ./gradlew hapiTestTokenSerial --no-daemon --rerun-tasks --no-build-cache'
