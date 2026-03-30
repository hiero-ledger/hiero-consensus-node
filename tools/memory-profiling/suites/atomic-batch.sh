#!/usr/bin/env bash
# Atomic Batch suite - runs all subtasks sequentially (matches CI: hl-cn-hapi-lin-lg)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" atomic-batch-full \
  './gradlew hapiTestAtomicBatch --no-daemon --rerun-tasks --no-build-cache && ./gradlew hapiTestAtomicBatchSerial --no-daemon --rerun-tasks --no-build-cache && ./gradlew hapiTestAtomicBatchEmbedded --no-daemon --rerun-tasks --no-build-cache'
