#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints, as a compact JSON array, the Block Node versions to test.
# env:
#   BN_VERSIONS  comma-separated Block Node versions (required)
#
# Entries are trimmed, a missing leading v is added (BN tags all start with v and the value is used as a checkout ref),
# and duplicates are dropped. Every entry must look like vX.Y.Z[-suffix] because it ends up in scripts and artifact
# names; anything else, or an empty list, fails the script.
set -euo pipefail

VERSION_RE='^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$'

: "${BN_VERSIONS:?BN_VERSIONS must be set}"

list=$(echo "${BN_VERSIONS}" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//;s/^[0-9]/v&/' | awk 'NF && !seen[$0]++')
if [[ -z "${list}" ]]; then
  echo "::error::No Block Node versions given" >&2
  exit 1
fi
while IFS= read -r v; do
  if [[ ! "${v}" =~ ${VERSION_RE} ]]; then
    echo "::error::Invalid Block Node version '${v}' (expected vX.Y.Z or vX.Y.Z-suffix)" >&2
    exit 1
  fi
done <<< "${list}"

echo "${list}" | jq -R . | jq -sc .
