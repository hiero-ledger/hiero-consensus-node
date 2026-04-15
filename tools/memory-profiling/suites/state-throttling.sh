#!/usr/bin/env bash
# HAPI Tests (State Throttling) - matches CI job hapi-tests-state-throttling
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" state-throttling-full \
  './gradlew hapiTestStateThrottling --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
