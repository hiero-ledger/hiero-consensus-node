#!/usr/bin/env bash
# HAPI Tests (Atomic Batch) - matches CI job hapi-tests-atomic-batch
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" atomic-batch-full \
  './gradlew hapiTestAtomicBatch --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestAtomicBatchSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestAtomicBatchEmbedded --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
