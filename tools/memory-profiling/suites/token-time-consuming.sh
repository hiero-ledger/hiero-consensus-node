#!/usr/bin/env bash
# HAPI Tests (Token & Time Consuming) - matches CI job hapi-tests-token-and-time-consuming
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" token-time-consuming-full \
  './gradlew hapiTestToken --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestTokenSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestTimeConsuming --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestTimeConsumingSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
