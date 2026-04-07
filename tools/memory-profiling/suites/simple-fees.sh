#!/usr/bin/env bash
# Simple Fees suite - runs all subtasks sequentially (matches CI: hl-cn-hapi-lin-md)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" simple-fees-full \
  './gradlew hapiTestSimpleFees --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestSimpleFeesEmbedded --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestSimpleFeesSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
