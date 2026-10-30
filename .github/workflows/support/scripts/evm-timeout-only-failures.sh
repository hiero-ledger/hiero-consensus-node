#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Decides whether a failed hardhat (Mocha) run of the EVM functional tests may be retried: only when the run
# reported at least one failure and every failure is a Mocha timeout of the given length. A run without a Mocha
# "N failing" summary (a compile error, a crash) is never retryable.
#
# Workaround for hiero-ledger/hiero-json-rpc-relay#5933, used by 871-call-evm-functional-tests.yaml. Remove it with
# the retry there once citr-json-rpc-relay-version moves to a relay release that includes the fix.
#
# Usage: evm-timeout-only-failures.sh <hardhat-log> [timeout-ms (default 120000)]
#
# Prints the full title of each timed-out test, one per line.
# Exit status: 0 retryable, 1 not retryable, 2 usage error.
set -eo pipefail

LOG_FILE="${1}"
TIMEOUT_MS="${2:-120000}"

if [[ -z "${LOG_FILE}" || ! -r "${LOG_FILE}" ]]; then
  echo "Usage: ${0##*/} <hardhat-log> [timeout-ms]" >&2
  exit 2
fi
if [[ ! "${TIMEOUT_MS}" =~ ^[0-9]+$ ]]; then
  echo "ERROR: timeout-ms must be a whole number of milliseconds, got '${TIMEOUT_MS}'" >&2
  exit 2
fi

# Mocha's spec reporter ends a failed run with an epilogue such as:
#
#     1 failing
#
#     1) Outer describe
#          inner test:
#        Error: Timeout of 120000ms exceeded. For async tests and hooks, ensure "done()" is called; ...
#
# Each failure is a numbered title (its describe path, one line per level, the last ending in ':') followed by the
# error message. The last "N failing" summary in the log wins. A failure whose title or message cannot be read
# counts as not a timeout, so a misparse never causes a retry.
awk -v timeout_message="Timeout of ${TIMEOUT_MS}ms exceeded" '
  {
    gsub(/\033\[[0-9;]*[A-Za-z]/, "")
    sub(/\r$/, "")
  }
  /^[[:space:]]*[0-9]+ failing[[:space:]]*$/ {
    failing = $1 + 0
    entries = 0
    timeouts = 0
    state = "list"
    next
  }
  state == "list" && /^[[:space:]]+[0-9]+\) / {
    entries++
    title = $0
    sub(/^[[:space:]]+[0-9]+\) /, "", title)
    state = (title ~ /:$/) ? "message" : "title"
    next
  }
  state == "title" {
    line = $0
    sub(/^[[:space:]]+/, "", line)
    title = title " " line
    if (line ~ /:$/) state = "message"
    next
  }
  state == "message" && /[^[:space:]]/ {
    sub(/:$/, "", title)
    if (index($0, timeout_message) > 0) timed_out[++timeouts] = title
    state = "list"
    next
  }
  END {
    for (i = 1; i <= timeouts; i++) print timed_out[i]
    exit (failing > 0 && entries == failing && timeouts == failing) ? 0 : 1
  }
' "${LOG_FILE}"
