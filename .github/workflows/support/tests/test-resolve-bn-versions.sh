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

# newest first here, but the script must not depend on the order
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
  '["v0.42.0","v0.44.1","v0.44.0","v0.45.0-rc1"]' \
  "$(echo "${RELEASES_PRE_NEWEST}" | PINNED=v0.42.0 "${RESOLVE}")"

check "pinned prerelease replaced by a stable on the same line is skipped" \
  '["v0.44.1","v0.44.0","v0.45.0-rc1"]' \
  "$(echo "${RELEASES_PRE_NEWEST}" | PINNED=v0.44.1-rc1 "${RESOLVE}")"

check "pinned prerelease newer than any stable on its line is kept" \
  '["v0.45.0-rc1","v0.44.1","v0.44.0"]' \
  "$(echo "${RELEASES_PRE_NEWEST}" | PINNED=v0.45.0-rc1 "${RESOLVE}")"

check "pinned equal to a stable release is de-duplicated" \
  '["v0.44.1","v0.44.0","v0.45.0-rc1"]' \
  "$(echo "${RELEASES_PRE_NEWEST}" | PINNED=v0.44.1 "${RESOLVE}")"

check "prerelease older than the newest stable is not added" \
  '["v0.44.0","v0.44.1"]' \
  "$(echo "${RELEASES_STABLE_NEWEST}" | PINNED=v0.44.0 "${RESOLVE}")"

check "drafts are ignored" \
  '["v0.44.0","v0.44.1"]' \
  "$(echo "${RELEASES_DRAFT_NEWEST}" | PINNED=v0.44.0 "${RESOLVE}")"

check "STABLE_COUNT limits stable releases" \
  '["v0.44.1","v0.45.0-rc1"]' \
  "$(echo "${RELEASES_PRE_NEWEST}" | PINNED=v0.44.1 STABLE_COUNT=1 "${RESOLVE}")"

# API order is by creation date: a v0.43.1 backport published after v0.44.2, and a stable after an rc
RELEASES_UNORDERED='[
 {"tag_name":"v0.43.1","draft":false,"prerelease":false},
 {"tag_name":"v0.45.0-rc1","draft":false,"prerelease":true},
 {"tag_name":"v0.44.2","draft":false,"prerelease":false},
 {"tag_name":"v0.44.10","draft":false,"prerelease":false},
 {"tag_name":"v0.43.0","draft":false,"prerelease":false}]'

check "releases are sorted by semver, not API order" \
  '["v0.42.0","v0.44.10","v0.44.2","v0.45.0-rc1"]' \
  "$(echo "${RELEASES_UNORDERED}" | PINNED=v0.42.0 "${RESOLVE}")"

RELEASES_OLD_PATCH_LAST='[
 {"tag_name":"v0.44.0-rc1","draft":false,"prerelease":true},
 {"tag_name":"v0.43.1","draft":false,"prerelease":false}]'

check "a stable patch on an older line does not hide the newest prerelease" \
  '["v0.42.0","v0.43.1","v0.44.0-rc1"]' \
  "$(echo "${RELEASES_OLD_PATCH_LAST}" | PINNED=v0.42.0 "${RESOLVE}")"

check "override is used as-is, trimmed and de-duplicated, releases ignored" \
  '["v0.41.0-alpha1","v0.43.0"]' \
  "$(echo "not json" | OVERRIDE=' v0.41.0-alpha1, v0.43.0 ,v0.43.0' "${RESOLVE}")"

for bad in 'v0.44.0;rm -rf /' 'v0.44.0"; echo x' '$(id)' 'latest' 'v0.44' 'a/b:c'; do
  if echo "[]" | OVERRIDE="v0.43.0,${bad}" "${RESOLVE}" >/dev/null 2>&1; then
    echo "FAIL: override '${bad}' should be rejected"
    failures=$((failures + 1))
  else
    echo "PASS: override '${bad}' rejected"
  fi
done

if echo "${RELEASES_PRE_NEWEST}" | PINNED='v0.44.0;id' "${RESOLVE}" >/dev/null 2>&1; then
  echo "FAIL: invalid PINNED should fail"
  failures=$((failures + 1))
else
  echo "PASS: invalid PINNED fails"
fi

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
