#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints, as a compact JSON array, the legs of the Block Node matrix: one object per CN ref x BN version, each
# holding that ref's config plus "bn-version".
# env:
#   CONFIGS        JSON array from resolve-cn-configs.sh (required); every element needs "bn-pinned-version"
#   BN_VERSIONS    JSON array of Block Node versions from resolve-bn-versions.sh, or empty to detect them (optional:
#                  empty means "not specified")
#   LATEST_STABLE  newest stable Block Node release, e.g. v0.44.3 (required when BN_VERSIONS is empty)
#
# Given BN_VERSIONS every ref is tested with every version. Without it each ref is tested with the version it pins and,
# if different, the newest stable release.
set -euo pipefail

: "${CONFIGS:?CONFIGS must be set}"

if [[ -n "${BN_VERSIONS:-}" ]]; then
  jq -nc --argjson cfgs "${CONFIGS}" --argjson bns "${BN_VERSIONS}" \
    '[$cfgs[] as $c | $bns[] | . as $b | $c + {"bn-version": $b}]'
else
  : "${LATEST_STABLE:?LATEST_STABLE must be set when BN_VERSIONS is empty}"
  jq -nc --argjson cfgs "${CONFIGS}" --arg latest "${LATEST_STABLE}" \
    '[$cfgs[] as $c | ([$c["bn-pinned-version"]] + (if $latest == $c["bn-pinned-version"] then [] else [$latest] end)) | .[] | . as $b | $c + {"bn-version": $b}]'
fi
