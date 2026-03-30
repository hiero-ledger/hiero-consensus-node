#!/usr/bin/env bash
# Misc suite - runs all subtasks sequentially (matches CI: hl-cn-hapi-lin-md)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" misc-full \
  './gradlew hapiTestMisc --no-daemon --rerun-tasks --no-build-cache && ./gradlew hapiTestMiscEmbedded --no-daemon --rerun-tasks --no-build-cache && ./gradlew hapiTestMiscRepeatable --no-daemon --rerun-tasks --no-build-cache && ./gradlew hapiTestMiscSerial --no-daemon --rerun-tasks --no-build-cache'
