#!/usr/bin/env bash
# HAPI Tests (Misc Records, Crypto & Misc Serial) - matches CI job hapi-tests-misc-records-crypto-and-serial
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$SCRIPT_DIR/run-test.sh" misc-records-crypto-full \
  './gradlew hapiTestMiscRecords --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestMiscRecordsSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestCrypto --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestCryptoEmbedded --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestCryptoSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache && ./gradlew hapiTestMiscSerial --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache'
