#!/usr/bin/env bash
set -uo pipefail

# Reports the SDCT suite status to Chewie via POST api/v1/suites/results.
# Never fails the caller: every error is a warning and the script always exits 0.
#
# Usage: report-suite-result.sh <disposition> <start-time> [end-time]
#   No end-time sends "end_time": null (the running report).
#
# Environment: CHEWIE_HOST, CHEWIE_KEY (repository identity key), REF, BUILD_TAG, GH_RUN_ID, GH_RUN_ATTEMPT.
# CHEWIE_DRY_RUN=true (mock runs): print the request instead of sending it; no token request, no POST.
# The key and the token are passed to curl as header files, so they are not exposed in the process list.

readonly DISPOSITIONS="running passed failed performance_issue isolated_issue cancelled not_run"

warn() {
  echo "::warning::Chewie report failed: $1"
  echo "- ⚠️ Chewie report failed: $1" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
}

report_suite_result() {
  local disposition=${1:-} start_time=${2:-} end_time=${3:-}

  [[ " ${DISPOSITIONS} " == *" ${disposition} "* ]] || warn "unknown disposition '${disposition}'"
  [[ -n "${start_time}" ]] || warn "no start time"
  [[ "${GH_RUN_ID:-}" =~ ^[0-9]+$ && "${GH_RUN_ATTEMPT:-}" =~ ^[0-9]+$ ]] \
    || warn "invalid GH_RUN_ID '${GH_RUN_ID:-}' or GH_RUN_ATTEMPT '${GH_RUN_ATTEMPT:-}'"

  # main: the nightly build number without leading zeros (build-00401 -> 401); other refs: the build tag as typed
  local build_number=${BUILD_TAG:-}
  local tag_number=${build_number#build-}
  if [[ "${REF:-}" =~ ^[Mm][Aa][Ii][Nn]$ && "${tag_number}" =~ ^[0-9]+$ ]]; then
    build_number=$((10#${tag_number}))
  fi
  [[ -n "${build_number}" ]] || warn "BUILD_TAG not set"

  local payload
  payload=$(jq -cn --arg build_number "${build_number}" --arg disposition "${disposition}" \
                   --arg start "${start_time}" --arg end "${end_time}" \
                   --argjson run_id "${GH_RUN_ID}" --argjson attempt "${GH_RUN_ATTEMPT}" \
                   '{build_number: $build_number, suite_type: "sdct", disposition: $disposition,
                     start_time: $start, end_time: (if $end == "" then null else $end end),
                     workflow_run_id: $run_id, run_attempt: $attempt}')

  if [[ "${CHEWIE_DRY_RUN:-false}" == "true" ]]; then
    echo "Chewie report (mock: not executed): POST ${CHEWIE_HOST:-<CHEWIE_HOST>}/api/v1/suites/results ${payload}"
    echo "- Chewie report (mock: not executed): \`${payload}\`" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
    return 0
  fi

  [[ -n "${CHEWIE_HOST:-}" && -n "${CHEWIE_KEY:-}" ]] || warn "CHEWIE_HOST or CHEWIE_KEY not set"

  # The token is minted here, right before it is used: a final report can be a day after the start
  local token
  token=$(curl -sf --max-time 60 -X POST "${CHEWIE_HOST}/api/v1/auth/token" \
            -H "Content-Type: application/json" \
            -H @<(printf 'Authorization: %s\n' "${CHEWIE_KEY}") | jq -r '.token // empty' 2>/dev/null)
  [[ -n "${token}" ]] || warn "no token from api/v1/auth/token"
  # Guarded so a local run does not print the token via the mask command itself
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::add-mask::${token}"
  fi

  curl -sf --max-time 60 -o /dev/null -X POST "${CHEWIE_HOST}/api/v1/suites/results" \
       -H "Content-Type: application/json" \
       -H @<(printf 'Authorization: Bearer %s\n' "${token}") \
       -d "${payload}" || warn "POST api/v1/suites/results (disposition ${disposition})"

  echo "Chewie report sent: ${payload}"
  echo "- Chewie report sent: \`${disposition}\` for build \`${build_number}\`" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
}

report_suite_result "$@"
exit 0
