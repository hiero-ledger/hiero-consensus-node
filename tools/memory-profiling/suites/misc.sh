#!/usr/bin/env bash
# HAPI Tests (Misc) - matches CI job hapi-tests-misc
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" misc-full \
  './gradlew hapiTestMisc --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestMiscEmbedded --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestMiscRepeatable --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew :yahcli:testSubprocess --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
