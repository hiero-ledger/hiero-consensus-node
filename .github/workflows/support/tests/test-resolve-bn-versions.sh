#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests .github/workflows/support/scripts/resolve-bn-versions.sh against canned release listings.
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

# newest first, as the releases API returns them
RELEASES_PRE_NEWEST='[
 {"tag_name":"v0.45.0-rc1","draft":false,"prerelease":true},
 {"tag_name":"v0.44.1","draft":false,"prerelease":false},
 {"tag_name":"v0.44.0","draft":false,"prerelease":false},
 {"tag_name":"v0.43.0","draft":false,"prerelease":false}]'
RELEASES_STABLE_NEWEST='[
 {"tag_name":"v0.44.1","draft":false,"prerelease":false},
 {"tag_name":"v0.44.1-rc1","draft":false,"prerelease":true},
 {"tag_name":"v0.44.0","draft":false,"prerelease":false}]'
RELEASES_DRAFT_NEWEST='[
 {"tag_name":"v0.46.0","draft":true,"prerelease":false},
 {"tag_name":"v0.44.1","draft":false,"prerelease":false}]'

check "pinned + 2 newest stable + newest prerelease" \
  '["v0.44.1-rc1","v0.44.1","v0.44.0","v0.45.0-rc1"]' \
  "$(echo "${RELEASES_PRE_NEWEST}" | PINNED=v0.44.1-rc1 "${RESOLVE}")"

check "pinned equal to a stable release is de-duplicated" \
  '["v0.44.1","v0.44.0","v0.45.0-rc1"]' \
  "$(echo "${RELEASES_PRE_NEWEST}" | PINNED=v0.44.1 "${RESOLVE}")"

check "prerelease older than the newest stable is not added" \
  '["v0.44.1-rc1","v0.44.1","v0.44.0"]' \
  "$(echo "${RELEASES_STABLE_NEWEST}" | PINNED=v0.44.1-rc1 "${RESOLVE}")"

check "drafts are ignored" \
  '["v0.44.0","v0.44.1"]' \
  "$(echo "${RELEASES_DRAFT_NEWEST}" | PINNED=v0.44.0 "${RESOLVE}")"

check "STABLE_COUNT limits stable releases" \
  '["v0.44.1","v0.45.0-rc1"]' \
  "$(echo "${RELEASES_PRE_NEWEST}" | PINNED=v0.44.1 STABLE_COUNT=1 "${RESOLVE}")"

check "override is used as-is, trimmed and de-duplicated, releases ignored" \
  '["v0.41.0-alpha1","v0.43.0"]' \
  "$(echo "not json" | OVERRIDE=' v0.41.0-alpha1, v0.43.0 ,v0.43.0' "${RESOLVE}")"

if echo "${RELEASES_PRE_NEWEST}" | "${RESOLVE}" 2>/dev/null; then
  echo "FAIL: missing PINNED should fail"
  failures=$((failures + 1))
else
  echo "PASS: missing PINNED fails"
fi

if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} test(s) failed"
  exit 1
fi
echo "All tests passed"
