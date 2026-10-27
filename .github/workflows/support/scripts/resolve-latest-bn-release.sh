#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints the newest stable Block Node release tag (e.g. v0.44.3).
#
# stdin: the JSON response of GET /repos/hiero-ledger/hiero-block-node/releases (any order). The API lists releases by
# creation date, not by version, so a backport patch of an older line can be listed before a newer line; this sorts by
# version. Drafts and prereleases are ignored. Fails if there is no stable release.
set -euo pipefail

tag=$(jq -r '
  [.[] | select(.draft == false and .prerelease == false)
       | . as $r
       | (.tag_name | ltrimstr("v") | capture("^(?<maj>[0-9]+)\\.(?<min>[0-9]+)\\.(?<pat>[0-9]+)$")) as $v
       | {tag: $r.tag_name, key: [($v.maj|tonumber), ($v.min|tonumber), ($v.pat|tonumber)]}]
  | sort_by(.key) | last | .tag // empty')

if [[ -z "${tag}" ]]; then
  echo "::error::No stable Block Node release found" >&2
  exit 1
fi
echo "${tag}"
