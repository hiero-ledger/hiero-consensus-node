#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests .github/workflows/support/scripts/resolve-bn-versions.sh.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVE="$(cd "${SCRIPT_DIR}/../scripts" && pwd)/resolve-bn-versions.sh"

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

check "trimmed and in order" '["v0.44.2","v0.45.0-rc1"]' "$(BN_VERSIONS=' v0.44.2 , v0.45.0-rc1 ' "${RESOLVE}")"
check "missing v added" '["v0.44.0"]' "$(BN_VERSIONS='0.44.0' "${RESOLVE}")"
check "duplicates dropped after adding v" '["v0.44.0","v0.45.0-alpha1"]' \
  "$(BN_VERSIONS='v0.44.0,0.44.0,v0.45.0-alpha1,,v0.44.0' "${RESOLVE}")"

check_fails "empty list" env BN_VERSIONS=" , " "${RESOLVE}"
check_fails "unset list" env -u BN_VERSIONS "${RESOLVE}"
for bad in 'latest' 'v0.44' 'v0.44.0;id' 'v0.44.0 x' 'v0.44.0-' '$(id)' 'v0.44.0/x'; do
  check_fails "invalid version '${bad}'" env BN_VERSIONS="${bad}" "${RESOLVE}"
done

if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} test(s) failed"
  exit 1
fi
echo "All tests passed"
