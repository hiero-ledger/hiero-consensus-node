#!/usr/bin/env bash
# HAPI Tests (Simple Fees & ND Reconnect) - matches CI job hapi-tests-simple-fees-and-nd-reconnect
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" simple-fees-nd-reconnect-full \
  './gradlew hapiTestSimpleFees --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestSimpleFeesEmbedded --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestSimpleFeesSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestNDReconnect --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
