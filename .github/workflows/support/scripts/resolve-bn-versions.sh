#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints the Block Node versions to regression-test as a compact JSON array (e.g. ["v0.44.1","v0.44.0"]).
#
# stdin:  the JSON response of GET /repos/hiero-ledger/hiero-block-node/releases (newest first)
# env:
#   OVERRIDE      comma-separated explicit versions; when set, used as-is and the releases are ignored
#   PINNED        the version pinned in .citr-env (always tested)
#   STABLE_COUNT  how many of the newest stable releases to test (default 2)
#
# Without an override the set is: PINNED + the newest STABLE_COUNT stable releases + the newest release if it
# is a prerelease (alpha/rc), de-duplicated in that order. Tag-only builds that have no GitHub release can only
# be tested through OVERRIDE. Fails if the resulting set is empty.
set -euo pipefail

STABLE_COUNT="${STABLE_COUNT:-2}"

if [[ -n "${OVERRIDE:-}" ]]; then
  candidates=$(echo "${OVERRIDE}" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
else
  releases=$(cat)
  stable=$(echo "${releases}" | jq -r --argjson n "${STABLE_COUNT}" \
    '[.[] | select(.draft == false and .prerelease == false)] | .[:$n] | .[].tag_name')
  newest_pre=$(echo "${releases}" | jq -r \
    '[.[] | select(.draft == false)] | .[0] | select(. != null and .prerelease == true) | .tag_name')
  candidates=$(printf '%s\n%s\n%s\n' "${PINNED:?PINNED must be set}" "${stable}" "${newest_pre}")
fi

versions=$(echo "${candidates}" | awk 'NF && !seen[$0]++' | jq -R . | jq -sc .)

if [[ "${versions}" == "[]" ]]; then
  echo "::error::No Block Node versions resolved" >&2
  exit 1
fi
echo "${versions}"
