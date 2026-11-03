#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests .github/workflows/support/scripts/evm-timeout-only-failures.sh against Mocha spec reporter output: a run is
# retryable only when it reports failures and every one is a 120 s timeout.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSIFIER="$(cd "${SCRIPT_DIR}/../scripts" && pwd)/evm-timeout-only-failures.sh"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

FAILURES=0

# expect <case> <expected exit status> <expected stdout> <classifier args...>
expect() {
  local name="${1}" want_status="${2}" want_output="${3}" output status
  shift 3
  set +e
  output="$(bash "${CLASSIFIER}" "${@}" 2>/dev/null)"
  status=$?
  set -e
  if [[ "${status}" == "${want_status}" && "${output}" == "${want_output}" ]]; then
    echo "PASS: ${name}"
  else
    echo "FAIL: ${name}"
    echo "  expected status ${want_status}, output:"
    printf '%s\n' "${want_output}" | sed 's/^/    /'
    echo "  actual status ${status}, output:"
    printf '%s\n' "${output}" | sed 's/^/    /'
    FAILURES=$((FAILURES + 1))
  fi
}

TIMEOUT_MESSAGE='Error: Timeout of 120000ms exceeded. For async tests and hooks, ensure "done()" is called; if returning a Promise, ensure it resolves.'

cat > "${WORK_DIR}/all-timeouts.log" <<EOF
  HIP-1215
    Schedule call
      ✔ schedules a call (5123ms)
      1) emits the scheduled event
    2) "after all" hook for "emits the scheduled event"

  HTS transfer events
    ✔ emits a transfer event

  2 passing (4m)
  2 failing

  1) HIP-1215
       Schedule call
         emits the scheduled event:
     ${TIMEOUT_MESSAGE} (/work/test/hip-1215/schedule.test.js)
      at listOnTimeout (node:internal/timers:581:17)
      at process.processTimers (node:internal/timers:519:7)

  2) HIP-1215
       "after all" hook for "emits the scheduled event":
     ${TIMEOUT_MESSAGE} (/work/test/hip-1215/schedule.test.js)
      at listOnTimeout (node:internal/timers:581:17)

EOF
expect "every failure is a 120 s timeout" 0 \
  'HIP-1215 Schedule call emits the scheduled event
HIP-1215 "after all" hook for "emits the scheduled event"' \
  "${WORK_DIR}/all-timeouts.log"

cat > "${WORK_DIR}/mixed.log" <<EOF
  1 passing (3m)
  2 failing

  1) HIP-632
       returns the alias:
     ${TIMEOUT_MESSAGE} (/work/test/hip-632/hip-632.test.js)
      at listOnTimeout (node:internal/timers:581:17)

  2) HIP-632
       rejects an unknown account:
     AssertionError: expected '0x1' to equal '0x0'
      + expected - actual

      -0x1
      +0x0

      at Context.<anonymous> (test/hip-632/hip-632.test.js:42:12)

EOF
expect "a timeout and an assertion failure" 1 'HIP-632 returns the alias' "${WORK_DIR}/mixed.log"

sed 's/120000ms/2000ms/' "${WORK_DIR}/all-timeouts.log" > "${WORK_DIR}/other-timeout.log"
expect "timeouts of another length" 1 '' "${WORK_DIR}/other-timeout.log"
expect "timeouts of an explicitly given length" 0 \
  'HIP-1215 Schedule call emits the scheduled event
HIP-1215 "after all" hook for "emits the scheduled event"' \
  "${WORK_DIR}/other-timeout.log" 2000

cat > "${WORK_DIR}/passing.log" <<'EOF'
  HIP-632
    ✔ returns the alias

  1 passing (12s)

EOF
expect "a passing run" 1 '' "${WORK_DIR}/passing.log"

cat > "${WORK_DIR}/crash.log" <<'EOF'
Error HH700: Artifact for contract "Missing" not found.
EOF
expect "a run without a Mocha summary" 1 '' "${WORK_DIR}/crash.log"

# The epilogue lists fewer failures than the summary counts (for example a truncated log).
head -n 18 "${WORK_DIR}/all-timeouts.log" > "${WORK_DIR}/truncated.log"
expect "fewer listed failures than counted" 1 'HIP-1215 Schedule call emits the scheduled event' \
  "${WORK_DIR}/truncated.log"

# Colored output with CRLF line endings.
printf '  1 passing (1m)\r\n  \033[31m1 failing\033[0m\r\n\r\n  \033[31m1) SDK\r\n       creates an account:\033[0m\r\n     \033[31m%s\033[0m\r\n' \
  "${TIMEOUT_MESSAGE}" > "${WORK_DIR}/colored.log"
expect "colored output with CRLF line endings" 0 'SDK creates an account' "${WORK_DIR}/colored.log"

expect "a missing log file" 2 '' "${WORK_DIR}/does-not-exist.log"
expect "a malformed timeout" 2 '' "${WORK_DIR}/all-timeouts.log" 120s

if [[ "${FAILURES}" -gt 0 ]]; then
  echo "${FAILURES} test(s) failed"
  exit 1
fi
echo "All tests passed"
