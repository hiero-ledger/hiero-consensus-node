#!/usr/bin/env bash
# HAPI Tests (Smart Contracts & ISS) - matches CI job hapi-tests-smart-contracts-and-iss
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" smart-contracts-iss-full \
  './gradlew hapiTestSmartContract --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestSmartContractSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestIss --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
