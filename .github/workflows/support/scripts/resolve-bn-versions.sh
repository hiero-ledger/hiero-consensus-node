#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints the Block Node versions to regression-test as a compact JSON array (e.g. ["v0.44.1","v0.44.0"]).
#
# stdin:  the JSON response of GET /repos/hiero-ledger/hiero-block-node/releases (any order)
# env:
#   OVERRIDE      comma-separated explicit versions; when set, used as-is and the releases are ignored
#   PINNED        the version pinned in .citr-env (tested unless a stable release has replaced it)
#   STABLE_COUNT  how many of the newest stable releases to test (default 2)
#
# Without an override the set is: PINNED + the newest STABLE_COUNT stable releases + the newest release if it
# is a prerelease (alpha/rc), de-duplicated in that order. "Newest" is by semver, not by the creation order of
# the API listing. PINNED is skipped when it is a prerelease that a stable release on the same major.minor line
# (patch >= its own) has already replaced. Tag-only builds that have no GitHub release can only be tested
# through OVERRIDE. Every version must look like vX.Y.Z[-suffix]. Fails if the resulting set is empty.
set -euo pipefail

STABLE_COUNT="${STABLE_COUNT:-2}"
VERSION_RE='^v?[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$'

validate() {
  while IFS= read -r v; do
    if [[ -n "${v}" && ! "${v}" =~ ${VERSION_RE} ]]; then
      echo "::error::Invalid Block Node version '${v}' (expected ${VERSION_RE})" >&2
      exit 1
    fi
  done <<< "$1"
}

if [[ -n "${OVERRIDE:-}" ]]; then
  candidates=$(echo "${OVERRIDE}" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
else
  PINNED="${PINNED:?PINNED must be set}"
  validate "${PINNED}"
  # non-draft releases, sorted newest first by semver (stable ranks above a prerelease of the same x.y.z)
  sorted=$(cat | jq -c '
    [.[] | select(.draft == false)
      | . as $r
      | (.tag_name | ltrimstr("v")
          | capture("^(?<maj>[0-9]+)\\.(?<min>[0-9]+)\\.(?<pat>[0-9]+)(-(?<pre>.+))?$")) as $v
      | {tag_name: .tag_name, prerelease: $r.prerelease, maj: ($v.maj|tonumber), min: ($v.min|tonumber),
         pat: ($v.pat|tonumber), pre: ($v.pre // "")}]
    | sort_by([.maj, .min, .pat, (if .prerelease then 0 else 1 end), .pre]) | reverse')
  stable=$(echo "${sorted}" | jq -r --argjson n "${STABLE_COUNT}" \
    '[.[] | select(.prerelease == false)] | .[:$n] | .[].tag_name')
  newest_pre=$(echo "${sorted}" | jq -r \
    '.[0] | select(. != null and .prerelease == true) | .tag_name')
  pinned=$(echo "${sorted}" | jq -r --arg pinned "${PINNED}" '
    ($pinned | ltrimstr("v")
      | capture("^(?<maj>[0-9]+)\\.(?<min>[0-9]+)\\.(?<pat>[0-9]+)(-(?<pre>.+))?$")) as $p
    | if ($p.pre != null) and any(.[]; .prerelease == false and .maj == ($p.maj|tonumber)
        and .min == ($p.min|tonumber) and .pat >= ($p.pat|tonumber))
      then empty else $pinned end')
  candidates=$(printf '%s\n%s\n%s\n' "${pinned}" "${stable}" "${newest_pre}")
fi

validate "${candidates}"
versions=$(echo "${candidates}" | awk 'NF && !seen[$0]++' | jq -R . | jq -sc .)

if [[ "${versions}" == "[]" ]]; then
  echo "::error::No Block Node versions resolved" >&2
  exit 1
fi
echo "${versions}"
