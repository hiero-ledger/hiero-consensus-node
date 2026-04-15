#!/usr/bin/env bash
# HAPI Tests (Restart) - matches CI job hapi-tests-restart
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" restart-full \
  './gradlew hapiTestRestart --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
