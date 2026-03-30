#!/usr/bin/env bash
# Crypto suite - runs all subtasks sequentially (matches CI: hl-cn-hapi-lin-lg)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" crypto-full \
  './gradlew hapiTestCrypto --no-daemon --rerun-tasks --no-build-cache && ./gradlew hapiTestCryptoEmbedded --no-daemon --rerun-tasks --no-build-cache && ./gradlew hapiTestCryptoSerial --no-daemon --rerun-tasks --no-build-cache'
