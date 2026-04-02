#!/usr/bin/env bash
# Smart Contracts suite - runs all subtasks sequentially (matches CI: hl-cn-hapi-lin-lg)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" smart-contracts-full \
  './gradlew hapiTestSmartContract --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestSmartContractSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
