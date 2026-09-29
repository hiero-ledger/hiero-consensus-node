#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

# Tracks an SDCT Jenkins build started by sdct.sh until it produces a verdict.
# See the "Jenkins Job Contract" in .github/workflows/docs/citr-test-config.md.

# ANSI Colors
#
readonly RED=$'\e[31m'
readonly GREEN=$'\e[32m'
readonly YELLOW=$'\e[33m'
readonly RESET=$'\e[0m'

# Logging
#
log()  { printf '%b[%s]%b %s\n' "$2" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$RESET" "$1"; }
die()  { log "$1" "$RED"; exit "${2:-1}"; }

# Usage
#
Usage() {
cat <<EOF
Usage: $0 wait <queue-id>
       $0 stop <build-number>
       $0 cancel

wait:   follows the Jenkins queue item and build, writes build-number, build-url,
        jenkins-result, verdict (pass|fail|infra) and reason to GITHUB_OUTPUT,
        exits 0 only on pass.
stop:   aborts the Jenkins build.
cancel: aborts whatever a previous wait left behind in SDCT_OUT_DIR: the build if
        it started, otherwise the queue item.

Environment variables required:
  USERNAME      Jenkins user
  PASSWORD      Jenkins token
  SERVER        Jenkins base URL (https://jenkins.example.com)
  GH_RUN_ID     GitHub workflow run id (wait only)
  SDCT_OUT_DIR  Directory for sdct-result.json, console-tail.log, queue-id and build-number
EOF
exit 1
}

# Timings in seconds, overridable for testing
#
readonly QUEUE_INTERVAL=${QUEUE_INTERVAL:-60}
readonly QUEUE_TIMEOUT=${QUEUE_TIMEOUT:-3600}         # 60 min in the Jenkins queue
readonly POLL_INTERVAL=${POLL_INTERVAL:-300}          # 5 min between build polls
readonly FAIL_GRACE=${FAIL_GRACE:-1800}               # 30 min after a FAIL marker
readonly HANG_TIMEOUT=${HANG_TIMEOUT:-3600}           # 60 min without console output
readonly MAX_HTTP_ERRORS=${MAX_HTTP_ERRORS:-6}        # consecutive failed polls
readonly CONSOLE_TAIL_LINES=5000
readonly JOB_PATH="job/nightly/job/sdct"

# Preflight Checks
#
[[ $# -eq 2 || ( $# -eq 1 && "${1}" == "cancel" ) ]] || Usage
[[ -v USERNAME     && -n ${USERNAME}     ]] || die "USERNAME not set" 2
[[ -v PASSWORD     && -n ${PASSWORD}     ]] || die "PASSWORD not set" 2
[[ -v SERVER       && -n ${SERVER}       ]] || die "SERVER not set" 2
[[ -v SDCT_OUT_DIR && -n ${SDCT_OUT_DIR} ]] || die "SDCT_OUT_DIR not set" 2

command -v curl >/dev/null || die "❌ curl is not installed"
command -v jq >/dev/null || die "❌ jq is not installed"

readonly MODE=${1}
readonly USERPASSWORD="${USERNAME}:${PASSWORD}"
mkdir -p "${SDCT_OUT_DIR}"

TMPDIR_SDCT="$(mktemp -d -t sdct.XXXXXXXXX)"
trap 'rm -rf "${TMPDIR_SDCT}"' EXIT INT TERM HUP

jenkins_get() {
  curl --no-progress-meter -f --max-time 60 -u "${USERPASSWORD}" "$@"
}

stop_build() {
  local number=$1 crumb
  local cookiejar="${TMPDIR_SDCT}/cookies"
  crumb=$(jenkins_get --cookie-jar "${cookiejar}" \
          "${SERVER}/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,%22:%22,//crumb)") \
    || { log "⚠️  Failed to fetch Jenkins crumb, build ${number} not stopped" "$YELLOW"; return 1; }
  curl --no-progress-meter -f --max-time 60 -X POST -u "${USERPASSWORD}" --cookie "${cookiejar}" \
       -H "${crumb}" -o /dev/null "${SERVER}/${JOB_PATH}/${number}/stop" \
    || { log "⚠️  Failed to stop Jenkins build ${number}" "$YELLOW"; return 1; }
  log "🛑 Jenkins build ${number} stop requested" "$YELLOW"
}

set_output() {
  local value=${2//$'\r'/ }
  echo "$1=${value//$'\n'/ }" >> "${GITHUB_OUTPUT:-/dev/null}"
}

cancel_queue_item() {
  local id=$1 crumb
  local cookiejar="${TMPDIR_SDCT}/cookies"
  crumb=$(jenkins_get --cookie-jar "${cookiejar}" \
          "${SERVER}/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,%22:%22,//crumb)") \
    || { log "⚠️  Failed to fetch Jenkins crumb, queue item ${id} not cancelled" "$YELLOW"; return 1; }
  curl --no-progress-meter -f --max-time 60 -X POST -u "${USERPASSWORD}" --cookie "${cookiejar}" \
       -H "${crumb}" -o /dev/null "${SERVER}/queue/cancelItem?id=${id}" \
    || { log "⚠️  Failed to cancel Jenkins queue item ${id}" "$YELLOW"; return 1; }
  log "🛑 Jenkins queue item ${id} cancel requested" "$YELLOW"
}

if [[ "${MODE}" == "stop" ]]; then
  [[ "${2}" =~ ^[0-9]+$ ]] || die "Invalid build number: ${2}" 2
  stop_build "${2}"
  exit $?
fi

if [[ "${MODE}" == "cancel" ]]; then
  if [[ -s "${SDCT_OUT_DIR}/build-number" ]]; then
    stop_build "$(cat "${SDCT_OUT_DIR}/build-number")"
    exit $?
  fi
  if [[ ! -s "${SDCT_OUT_DIR}/queue-id" ]]; then
    log "No Jenkins build or queue item recorded, nothing to cancel" "$RESET"
    exit 0
  fi
  queue_id=$(cat "${SDCT_OUT_DIR}/queue-id")
  [[ "${queue_id}" =~ ^[0-9]+$ ]] || die "Invalid queue id: ${queue_id}" 2
  # The item may have become a build since the last poll: stop the build in that case
  if item=$(jenkins_get "${SERVER}/queue/item/${queue_id}/api/json?tree=executable%5Bnumber%5D"); then
    number=$(jq -r '.executable.number // empty' <<< "${item}")
    if [[ -n "${number}" ]]; then
      stop_build "${number}"
      exit $?
    fi
  fi
  cancel_queue_item "${queue_id}"
  exit $?
fi

[[ "${MODE}" == "wait" ]] || Usage
[[ -v GH_RUN_ID && -n ${GH_RUN_ID} ]] || die "GH_RUN_ID not set" 2
readonly QUEUE_ID=${2}
[[ "${QUEUE_ID}" =~ ^[0-9]+$ ]] || die "Invalid queue id: ${QUEUE_ID}" 2
echo "${QUEUE_ID}" > "${SDCT_OUT_DIR}/queue-id"

VERDICT=""
REASON=""
JENKINS_RESULT=""
BUILD_NUMBER=""
BUILD_URL=""

finish() {
  set_output "build-number" "${BUILD_NUMBER}"
  set_output "build-url" "${BUILD_URL}"
  set_output "jenkins-result" "${JENKINS_RESULT}"
  set_output "verdict" "${VERDICT}"
  set_output "reason" "${REASON}"

  local result_file="${SDCT_OUT_DIR}/sdct-result.json"
  {
    echo "## SDCT Jenkins Build"
    echo "- **Jenkins Build**: ${BUILD_URL:-N/A}"
    echo "- **Jenkins Result**: ${JENKINS_RESULT:-N/A}"
    echo "- **Verdict**: ${VERDICT}"
    [[ -n "${REASON}" ]] && echo "- **Reason**: ${REASON}"
    if [[ -s "${result_file}" ]] && jq -e '.tests | type == "array"' "${result_file}" >/dev/null 2>&1; then
      echo
      echo "| Test | TPS | p50 (ms) | p99 (ms) | SLA Met | Result |"
      echo "|------|-----|----------|----------|---------|--------|"
      jq -r '.tests[] | "| \(.name) | \(.tps) | \(.p50_ms) | \(.p99_ms) | \(.sla_met) | \(.result) |"' "${result_file}"
    fi
  } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

  case "${VERDICT}" in
    pass)
      if [[ -n "${REASON}" ]]; then
        log "⚠️  SDCT passed with warning: ${REASON}" "$YELLOW"
      else
        log "✅ SDCT passed" "$GREEN"
      fi
      exit 0
      ;;
    fail)  log "❌ SDCT failed: ${REASON}" "$RED"; exit 1 ;;
    *)     log "❌ SDCT infrastructure failure: ${REASON}" "$RED"; exit 1 ;;
  esac
}

# Phase A: wait for the queue item to become a build
#
log "⏳ Waiting for Jenkins queue item ${QUEUE_ID} to start" "$RESET"
queue_start=$(date +%s)
errors=0
while :; do
  if item=$(jenkins_get "${SERVER}/queue/item/${QUEUE_ID}/api/json?tree=cancelled,why,executable%5Bnumber,url%5D"); then
    errors=0
    BUILD_NUMBER=$(jq -r '.executable.number // empty' <<< "${item}")
    if [[ -n "${BUILD_NUMBER}" ]]; then
      BUILD_URL=$(jq -r '.executable.url // empty' <<< "${item}")
      break
    fi
    if [[ "$(jq -r '.cancelled // false' <<< "${item}")" == "true" ]]; then
      VERDICT="infra"; REASON="Jenkins queue item ${QUEUE_ID} was cancelled"; finish
    fi
    why=$(jq -r '.why // "unknown"' <<< "${item}")
  else
    errors=$((errors + 1))
    why="queue poll failed (${errors}/${MAX_HTTP_ERRORS})"
    if (( errors >= MAX_HTTP_ERRORS )); then
      VERDICT="infra"; REASON="Jenkins queue item ${QUEUE_ID} unreachable after ${errors} attempts"; finish
    fi
  fi
  if (( $(date +%s) - queue_start >= QUEUE_TIMEOUT )); then
    VERDICT="infra"; REASON="Build not started after $((QUEUE_TIMEOUT / 60)) min in the Jenkins queue: ${why}"; finish
  fi
  log "⏳ Still queued: ${why}" "$RESET"
  sleep "${QUEUE_INTERVAL}"
done

echo "${BUILD_NUMBER}" > "${SDCT_OUT_DIR}/build-number"
BUILD_API="${SERVER}/${JOB_PATH}/${BUILD_NUMBER}"
log "▶️  Jenkins build ${BUILD_NUMBER} started: ${BUILD_URL}" "$GREEN"

# Phase B: poll the build until it completes
#
# No overall deadline: the test driver bounds the SDCT run time, a silent build is caught as hung
last_growth=$(date +%s)
offset=0
errors=0
fail_seen_at=""
pass_seen=false
verified=false
building="true"
forced=""
while :; do
  now=$(date +%s)
  poll_ok=true

  if status=$(jenkins_get "${BUILD_API}/api/json?tree=building,result,actions%5Bparameters%5Bname,value%5D%5D"); then
    building=$(jq -r '.building' <<< "${status}")
    JENKINS_RESULT=$(jq -r '.result // empty' <<< "${status}")
    if [[ "${verified}" == "false" ]]; then
      run_id=$(jq -r '[.actions[]?.parameters[]? | select(.name == "GH_RUN_ID") | .value][0] // empty' <<< "${status}")
      if [[ "${run_id}" != "${GH_RUN_ID}" ]]; then
        VERDICT="infra"; REASON="Jenkins build ${BUILD_NUMBER} has GH_RUN_ID '${run_id}', expected '${GH_RUN_ID}'"; finish
      fi
      verified=true
    fi
  else
    poll_ok=false
  fi

  chunk="${TMPDIR_SDCT}/chunk"
  headers="${TMPDIR_SDCT}/headers"
  if jenkins_get -D "${headers}" -o "${chunk}" "${BUILD_API}/logText/progressiveText?start=${offset}"; then
    size=$(tr -d '\r' < "${headers}" | sed -n -E 's/^[Xx]-[Tt]ext-[Ss]ize: *([0-9]+).*/\1/p' | tail -1)
    if [[ -n "${size}" ]] && (( size > offset )); then
      offset=${size}
      last_growth=${now}
    fi
    grep -E 'SDCT-(STATUS|HEARTBEAT):' "${chunk}" || true
    if [[ -z "${fail_seen_at}" ]] && fail_line=$(grep -m1 -E 'SDCT-STATUS: FAIL' "${chunk}"); then
      fail_seen_at=${now}
      REASON=$(sed -n -E 's/.*SDCT-STATUS: FAIL( reason=(.*))?.*$/\2/p' <<< "${fail_line}")
      REASON=${REASON:-"test driver reported FAIL"}
      log "❌ Fail-fast marker seen: ${REASON}" "$RED"
    fi
    grep -q -E 'SDCT-STATUS: PASS' "${chunk}" && pass_seen=true
  else
    poll_ok=false
  fi

  if [[ "${poll_ok}" == "true" ]]; then
    errors=0
  else
    errors=$((errors + 1))
    log "⚠️  Jenkins poll failed (${errors}/${MAX_HTTP_ERRORS})" "$YELLOW"
    if (( errors >= MAX_HTTP_ERRORS )); then
      forced="infra"; REASON="Jenkins build ${BUILD_NUMBER} unreachable for ${errors} consecutive polls"; break
    fi
  fi

  [[ "${building}" == "false" ]] && break

  if [[ -n "${fail_seen_at}" ]] && (( now - fail_seen_at >= FAIL_GRACE )); then
    forced="fail"; REASON="${REASON} (build still running $((FAIL_GRACE / 60)) min after FAIL)"; break
  fi
  if (( now - last_growth >= HANG_TIMEOUT )); then
    stop_build "${BUILD_NUMBER}" || true
    forced="infra"; REASON="No console output for $((HANG_TIMEOUT / 60)) min, build considered hung"; break
  fi

  sleep "${POLL_INTERVAL}"
done

# Phase C: collect results and decide the verdict
#
jenkins_get -o "${SDCT_OUT_DIR}/sdct-result.json" "${BUILD_API}/artifact/sdct-result.json" \
  || { rm -f "${SDCT_OUT_DIR}/sdct-result.json"; log "⚠️  sdct-result.json not available" "$YELLOW"; }
(jenkins_get --max-time 600 "${BUILD_API}/consoleText" | tail -n "${CONSOLE_TAIL_LINES}" > "${SDCT_OUT_DIR}/console-tail.log") \
  || log "⚠️  Console log not available" "$YELLOW"

JSON_RESULT=""
if [[ -s "${SDCT_OUT_DIR}/sdct-result.json" ]]; then
  JSON_RESULT=$(jq -r '.result // "INVALID"' "${SDCT_OUT_DIR}/sdct-result.json" 2>/dev/null || echo "INVALID")
  if [[ "${JSON_RESULT}" != "PASS" && -z "${REASON}" ]]; then
    REASON=$(jq -r '.reason // empty' "${SDCT_OUT_DIR}/sdct-result.json" 2>/dev/null || true)
  fi
fi

if [[ "${pass_seen}" == "true" && -z "${fail_seen_at}" ]]; then
  # The test passed; a Jenkins failure after the PASS marker (post-test processing) is only a warning
  VERDICT="pass"
  if [[ -n "${forced}" || "${JENKINS_RESULT}" != "SUCCESS" ]]; then
    REASON="Jenkins result ${JENKINS_RESULT:-unknown} after SDCT-STATUS: PASS${REASON:+: ${REASON}}"
  fi
elif [[ -n "${forced}" ]]; then
  VERDICT=${forced}
elif [[ "${JSON_RESULT}" == "ERROR" ]]; then
  # The test driver or pipeline classified the failure as infrastructure/setup
  VERDICT="infra"; REASON=${REASON:-"sdct-result.json result is ERROR"}
else
  case "${JENKINS_RESULT}" in
    SUCCESS)
      if [[ -n "${fail_seen_at}" ]]; then
        VERDICT="fail"
      elif [[ -z "${JSON_RESULT}" || "${JSON_RESULT}" == "PASS" ]]; then
        VERDICT="pass"
      else
        VERDICT="fail"; REASON=${REASON:-"sdct-result.json result is ${JSON_RESULT}"}
      fi
      ;;
    FAILURE|UNSTABLE)
      VERDICT="fail"; REASON=${REASON:-"Jenkins result ${JENKINS_RESULT}"}
      ;;
    *)
      VERDICT="infra"; REASON="Jenkins result ${JENKINS_RESULT:-unknown}${REASON:+: ${REASON}}"
      ;;
  esac
fi

finish
