#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
"$SCRIPT_DIR/run-test.sh" token-hapiTestTokenSerial \
  './gradlew hapiTestTokenSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
