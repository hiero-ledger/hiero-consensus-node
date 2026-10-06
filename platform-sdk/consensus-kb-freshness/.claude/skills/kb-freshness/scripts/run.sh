#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs the deterministic kb-freshness engine and prints the output directory. Locates the repo root
# by walking up from this script until it finds `gradlew`, so it works regardless of the caller's CWD.
#
# Usage: run.sh [kb-root] [out-dir] [baseline]
set -euo pipefail

start="${CLAUDE_SKILL_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
dir="$start"
while [ "$dir" != "/" ] && [ ! -x "$dir/gradlew" ]; do
  dir="$(dirname "$dir")"
done
if [ ! -x "$dir/gradlew" ]; then
  echo "error: could not locate repo root (no gradlew found above $start)" >&2
  exit 1
fi
repo="$dir"

kb="${1:-platform-sdk/docs/consensus-layer}"
out="${2:-$repo/build/kb-freshness}"
baseline="${3:-platform-sdk/consensus-kb-freshness/baseline/kb-freshness-baseline.tsv}"

# Re-running regenerates every artifact in $out, which destroys a report a curator may be
# mid-remediation on. Never silently clobber it: copy any existing, non-empty output to a
# timestamped sibling backup first, so an accidental re-run is always recoverable.
if [ -d "$out" ] && [ -n "$(ls -A "$out" 2>/dev/null)" ]; then
  bak="$out.bak.$(date +%Y%m%d-%H%M%S)"
  cp -R "$out" "$bak"
  echo "note: backed up previous report to $bak" >&2
fi

# Pass the run date so newly-seen findings get a first_seen in the proposed baseline; findings.json
# stays byte-identical (dates live only in the baseline).
"$repo/gradlew" -q -p "$repo" :consensus-kb-freshness:run \
  --args="--kb $kb --repo $repo --out $out --baseline $baseline --date $(date +%F)" >&2

echo "$out"
