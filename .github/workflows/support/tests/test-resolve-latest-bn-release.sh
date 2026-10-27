#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

# Tests .github/workflows/support/scripts/resolve-latest-bn-release.sh with canned GitHub release listings.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LATEST="$(cd "${SCRIPT_DIR}/../scripts" && pwd)/resolve-latest-bn-release.sh"

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

rel() { # tag prerelease draft
  echo "{\"tag_name\":\"$1\",\"prerelease\":$2,\"draft\":${3:-false}}"
}
list() { echo "[$(IFS=,; echo "$*")]"; }

# the API lists by creation date: a backport (v0.43.1) can come after v0.44.2, an rc after both
check "newest stable by version, not by listing order" 'v0.44.2' \
  "$(list "$(rel v0.45.0-rc1 true)" "$(rel v0.43.1 false)" "$(rel v0.44.2 false)" "$(rel v0.44.10-x true)" | "${LATEST}")"
check "two-digit patch beats one-digit" 'v0.44.10' "$(list "$(rel v0.44.9 false)" "$(rel v0.44.10 false)" | "${LATEST}")"
check "drafts ignored" 'v0.44.2' "$(list "$(rel v0.44.3 false true)" "$(rel v0.44.2 false)" | "${LATEST}")"

check_fails "only prereleases" bash -c "echo '[{\"tag_name\":\"v0.45.0-rc1\",\"prerelease\":true,\"draft\":false}]' | '${LATEST}'"
check_fails "empty listing" bash -c "echo '[]' | '${LATEST}'"

finish
