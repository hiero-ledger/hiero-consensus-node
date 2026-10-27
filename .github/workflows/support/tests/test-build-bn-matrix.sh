#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

# Tests .github/workflows/support/scripts/build-bn-matrix.sh.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$(cd "${SCRIPT_DIR}/../scripts" && pwd)/build-bn-matrix.sh"

if ! command -v jq &>/dev/null; then
  echo "ERROR: jq is not installed or not in PATH"
  exit 1
fi

failures=0
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then
    echo "PASS: $1"
  else
    echo "FAIL: $1 (expected '$2', got '$3')"
    failures=$((failures + 1))
  fi
}
check_fails() { # name command...
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "FAIL: ${name} should fail"
    failures=$((failures + 1))
  else
    echo "PASS: ${name} fails"
  fi
}
finish() {
  if [[ "${failures}" -gt 0 ]]; then
    echo "${failures} test(s) failed"
    exit 1
  fi
  echo "All tests passed"
}

CONFIGS='[{"cn-ref":"main","bn-pinned-version":"v0.45.0-tssupdate"},{"cn-ref":"release/0.78","bn-pinned-version":"v0.44.3"}]'
legs() { jq -r '[.[] | ."cn-ref" + "@" + ."bn-version"] | join(" ")'; }

check "explicit versions: full cross product" 'main@v0.44.0 main@v0.45.0 release/0.78@v0.44.0 release/0.78@v0.45.0' \
  "$(CONFIGS="${CONFIGS}" BN_VERSIONS='["v0.44.0","v0.45.0"]' "${BUILD}" | legs)"
check "detected: pinned plus newest stable per ref, no duplicate when equal" \
  'main@v0.45.0-tssupdate main@v0.44.3 release/0.78@v0.44.3' \
  "$(CONFIGS="${CONFIGS}" LATEST_STABLE=v0.44.3 "${BUILD}" | legs)"
check "config fields are kept" 'v0.44.3' \
  "$(CONFIGS="${CONFIGS}" BN_VERSIONS='["v0.44.0"]' "${BUILD}" | jq -r '.[1]."bn-pinned-version"')"

check_fails "detection without latest stable" env CONFIGS="${CONFIGS}" "${BUILD}"
check_fails "unset configs" env -u CONFIGS BN_VERSIONS='["v0.44.0"]' "${BUILD}"

finish
