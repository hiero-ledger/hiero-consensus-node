#!/usr/bin/env bash
set -uo pipefail

# Reports the SDCT suite status to Chewie via POST api/v1/suites/results.
# Never fails the caller: every error is a warning and the script always exits 0.
#
# Usage: report-suite-result.sh <disposition> <start-time> [end-time]
#   No end-time sends "end_time": null (the running report).
#
# Environment: CHEWIE_HOST, CHEWIE_KEY (repository identity key), REF, BUILD_TAG, COMMIT (40-character head commit
# of REF), GH_RUN_ID, GH_RUN_ATTEMPT.
# CHEWIE_DRY_RUN=true (mock runs): print the request instead of sending it; no token request, no POST.
# The key and the token are passed to curl as header files, so they are not exposed in the process list.
# No retries: Chewie appends results without deduplication, a retried POST could record the result twice.

readonly DISPOSITIONS="running passed failed performance_issue isolated_issue cancelled not_run"

warn() {
  echo "::warning::Chewie report failed: $1"
  echo "- ⚠️ Chewie report failed: $1" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
}

note() {
  echo "::warning::Chewie report: $1"
  echo "- ⚠️ Chewie report: $1" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
}

report_suite_result() {
  local disposition=${1:-} start_time=${2:-} end_time=${3:-}

  [[ " ${DISPOSITIONS} " == *" ${disposition} "* ]] || warn "unknown disposition '${disposition}'"
  [[ -n "${start_time}" ]] || warn "no start time"
  [[ "${GH_RUN_ID:-}" =~ ^[0-9]+$ && "${GH_RUN_ATTEMPT:-}" =~ ^[0-9]+$ ]] \
    || warn "invalid GH_RUN_ID '${GH_RUN_ID:-}' or GH_RUN_ATTEMPT '${GH_RUN_ATTEMPT:-}'"

  # main with a build-NNNNN tag: build_number (integer, no leading zeros); any other ref (branch, release tag,
  # release branch), or main without a build-NNNNN tag: branch (the ref as typed) and commit (its head commit)
  local ident tag_number=${BUILD_TAG:-}
  tag_number=${tag_number#build-}
  if [[ "${REF:-}" =~ ^[Mm][Aa][Ii][Nn]$ && "${tag_number}" =~ ^[0-9]+$ ]]; then
    ident=$(jq -cn --argjson build_number "$((10#${tag_number}))" '{build_number: $build_number}')
  else
    # Either one alone identifies the run: an invalid or empty one is logged and left out, the report goes on
    local branch=${REF:-} commit=${COMMIT:-}
    if [[ -z "${branch}" ]]; then
      note "REF not set, reporting by commit only"
    fi
    if [[ ! "${commit}" =~ ^[0-9a-f]{40}$ ]]; then
      note "no 40-character commit for branch '${branch}' (COMMIT '${commit}'), reporting by branch only"
      commit=""
    fi
    [[ -n "${branch}${commit}" ]] || warn "neither a branch (REF '${REF:-}') nor a 40-character commit (COMMIT '${COMMIT:-}') to report"
    ident=$(jq -cn --arg branch "${branch}" --arg commit "${commit}" \
                   '{} + (if $branch != "" then {branch: $branch} else {} end) + (if $commit != "" then {commit: $commit} else {} end)')
  fi

  # Jenkins (start) and runner (end) clocks differ: Chewie refuses an end before the start
  local rfc3339='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
  if [[ "${start_time}" =~ ${rfc3339} && "${end_time}" =~ ${rfc3339} && "${end_time}" < "${start_time}" ]]; then
    end_time=${start_time}
  fi

  # No jq keywords as variable names (e.g. $end): jq 1.6, on the runners, rejects them
  local payload
  payload=$(jq -cn --argjson ident "${ident}" --arg disposition "${disposition}" \
                   --arg start_time "${start_time}" --arg end_time "${end_time}" \
                   --argjson run_id "${GH_RUN_ID}" --argjson attempt "${GH_RUN_ATTEMPT}" \
                   '$ident + {suite_type: "sdct", disposition: $disposition, start_time: $start_time, end_time: (if $end_time == "" then null else $end_time end), workflow_run_id: $run_id, run_attempt: $attempt}') \
    && [[ -n "${payload}" ]] || warn "could not build the payload"

  if [[ "${CHEWIE_DRY_RUN:-false}" == "true" ]]; then
    echo "Chewie report (mock: not executed): POST ${CHEWIE_HOST:-<CHEWIE_HOST>}/api/v1/suites/results ${payload}"
    echo "- Chewie report (mock: not executed): \`${payload}\`" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
    return 0
  fi

  [[ -n "${CHEWIE_HOST:-}" && -n "${CHEWIE_KEY:-}" ]] || warn "CHEWIE_HOST or CHEWIE_KEY not set"

  # The token is minted here, right before it is used: a final report can be a day after the start
  chewie_post api/v1/auth/token "Authorization: ${CHEWIE_KEY}"
  local token=""
  [[ "${STATUS}" == 200 ]] && token=$(jq -r '.token // empty' <<< "${BODY}" 2>/dev/null)
  [[ -n "${token}" ]] || warn "no token from api/v1/auth/token: $(chewie_error)"
  # Guarded so a local run does not print the token via the mask command itself
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::add-mask::${token}"
  fi

  chewie_post api/v1/suites/results "Authorization: Bearer ${token}" "${payload}"
  [[ "${STATUS}" == 201 ]] || warn "POST api/v1/suites/results (disposition ${disposition}): $(chewie_error)"

  echo "Chewie report sent: ${payload}"
  echo "- Chewie report sent: \`${payload}\`" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
}

# chewie_post PATH AUTH_HEADER [JSON]: POST to Chewie, sets STATUS (000 if Chewie was not reached), BODY and
# CURL_ERROR (curl's own error, e.g. "curl: (28) Operation timed out ...")
chewie_post() {
  local data=()
  [[ $# -ge 3 ]] && data=(-d "$3")
  local out err_file
  err_file=$(mktemp) || err_file=/dev/null
  # The header file is opened on the curl command line itself: a process substitution does not outlive its command
  out=$(curl -sS --max-time 60 -X POST "${CHEWIE_HOST}/$1" -H "Content-Type: application/json" \
             -H @<(printf '%s\n' "$2") -w '\n%{http_code}' ${data[@]+"${data[@]}"} 2>"${err_file}")
  CURL_ERROR=$(tr -s '\r\n' ' ' < "${err_file}" 2>/dev/null)
  CURL_ERROR=${CURL_ERROR% }
  [[ "${err_file}" == /dev/null ]] || rm -f "${err_file}"
  STATUS=${out##*$'\n'}
  BODY=${out%$'\n'*}
  [[ "${out}" == *$'\n'* ]] || BODY=""
}

# chewie_error: the failure as "HTTP <status> <code>: <detail>" from Chewie's JSON error body
chewie_error() {
  [[ "${STATUS}" == 000 ]] && { echo "Chewie not reached${CURL_ERROR:+: ${CURL_ERROR}}"; return; }
  local code detail
  code=$(jq -r '.code // empty' <<< "${BODY}" 2>/dev/null)
  detail=$(jq -r '.detail // .error // empty' <<< "${BODY}" 2>/dev/null)
  if [[ -n "${code}${detail}" ]]; then
    echo "HTTP ${STATUS} ${code}${code:+: }${detail}"
  else
    echo "HTTP ${STATUS} ${BODY:0:200}"
  fi
}

report_suite_result "$@"
exit 0
