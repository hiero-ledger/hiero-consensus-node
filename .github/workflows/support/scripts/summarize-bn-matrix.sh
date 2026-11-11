#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints one markdown line per leg of the Block Node version matrix (906) with the conclusion of its Block Node
# regression (821) job and its BN communication (844) job, taken from the jobs of the workflow run.
# env:
#   MATRIX  the resolved matrix: JSON array of {"cn-ref","bn-version","bn-version-override",...} (required)
#   JOBS    JSON array of the run's jobs, each with "name" and "conclusion" (required)
#
# A reusable workflow job is named "<caller job name> / <called job name>", so the names below mirror the job names in
# 906 (matrix legs), 821 (custom-job-name) and 844 (job name). A leg whose job is missing fails the script, except the
# BN communication job of a ref without the BLOCK_NODE_VERSION override, which 906 skips on purpose.
set -euo pipefail

: "${MATRIX:?MATRIX must be set}"
: "${JOBS:?JOBS must be set}"

# conclusion of the job with exactly this name; fails if there is none
conclusion() { # job-name
  local c
  c=$(echo "${JOBS}" | jq -er --arg n "$1" '[.[] | select(.name == $n)] | if length == 1 then .[0].conclusion else error("expected exactly one job named \($n), found \(length)") end') || return 1
  echo "${c}"
}

icon() { # conclusion
  case "$1" in
    success) echo ":white_check_mark:" ;;
    skipped) echo ":fast_forward:" ;;
    *) echo ":x:" ;;
  esac
}

while IFS=$'\t' read -r ref bn override; do
  regression=$(conclusion "CN ${ref} / BN ${bn} / Block Node ${bn} on CN ${ref}")
  if [[ "${override}" == "true" ]]; then
    comms=$(conclusion "BN Comms CN ${ref} / BN ${bn} / HAPI Tests (BN Comms)")
    comms_text="$(icon "${comms}") ${comms}"
  else
    comms_text=":fast_forward: skipped (ref has no BLOCK_NODE_VERSION override)"
  fi
  echo "- CN \`${ref}\` / BN \`${bn}\`: regression $(icon "${regression}") ${regression}, BN Comms ${comms_text}"
done < <(echo "${MATRIX}" | jq -r '.[] | [."cn-ref", ."bn-version", ."bn-version-override"] | @tsv')
