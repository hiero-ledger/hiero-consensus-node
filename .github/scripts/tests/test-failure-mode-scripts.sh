#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Plain-bash unit tests for set-failure-mode.sh and aggregate-failure-mode.sh.
# Run from any directory: bash .github/scripts/tests/test-failure-mode-scripts.sh

set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SET_SCRIPT="${SCRIPTS_DIR}/set-failure-mode.sh"
AGG_SCRIPT="${SCRIPTS_DIR}/aggregate-failure-mode.sh"
GITHUB_OUTPUT="$(mktemp)"
trap 'rm -f "${GITHUB_OUTPUT}"' EXIT

pass=0
fail=0

# Run one test case. Args: description, expected output, env var pairs...
# The script writes "failure-mode=<value>" to GITHUB_OUTPUT; we extract the value.
run() {
  local description="$1"
  local script="$2"
  local expected="$3"
  shift 3

  > "${GITHUB_OUTPUT}"
  env "$@" GITHUB_OUTPUT="${GITHUB_OUTPUT}" bash "${script}" 2>/dev/null
  local actual
  actual=$(grep '^failure-mode=' "${GITHUB_OUTPUT}" | cut -d= -f2)

  if [[ "$actual" == "$expected" ]]; then
    echo "  PASS: ${description}"
    (( pass++ )) || true
  else
    echo "  FAIL: ${description}"
    echo "        expected: ${expected}"
    echo "        actual:   ${actual}"
    (( fail++ )) || true
  fi
}

# ---------------------------------------------------------------------------
echo "set-failure-mode.sh"
# ---------------------------------------------------------------------------

run "no outcomes → none" "${SET_SCRIPT}" "none" \
  TEST_OUTCOMES="" WORKFLOW_OUTCOMES="" CANCELLED_IS_WORKFLOW=""

run "single test failure → test" "${SET_SCRIPT}" "test" \
  TEST_OUTCOMES="failure" WORKFLOW_OUTCOMES="" CANCELLED_IS_WORKFLOW=""

run "multiple test outcomes, one failure → test" "${SET_SCRIPT}" "test" \
  TEST_OUTCOMES="$(printf 'success\nfailure\nsuccess')" WORKFLOW_OUTCOMES="" CANCELLED_IS_WORKFLOW=""

run "workflow failure alone → workflow" "${SET_SCRIPT}" "workflow" \
  TEST_OUTCOMES="" WORKFLOW_OUTCOMES="failure" CANCELLED_IS_WORKFLOW=""

run "workflow failure overrides test failure → workflow" "${SET_SCRIPT}" "workflow" \
  TEST_OUTCOMES="failure" WORKFLOW_OUTCOMES="failure" CANCELLED_IS_WORKFLOW=""

run "cancelled + CANCELLED_IS_WORKFLOW=true → workflow" "${SET_SCRIPT}" "workflow" \
  TEST_OUTCOMES="cancelled" WORKFLOW_OUTCOMES="" CANCELLED_IS_WORKFLOW="true"

run "cancelled + CANCELLED_IS_WORKFLOW=false → none" "${SET_SCRIPT}" "none" \
  TEST_OUTCOMES="cancelled" WORKFLOW_OUTCOMES="" CANCELLED_IS_WORKFLOW="false"

run "cancelled + CANCELLED_IS_WORKFLOW unset → none" "${SET_SCRIPT}" "none" \
  TEST_OUTCOMES="cancelled" WORKFLOW_OUTCOMES="" CANCELLED_IS_WORKFLOW=""

run "skipped outcomes only → none" "${SET_SCRIPT}" "none" \
  TEST_OUTCOMES="$(printf 'skipped\nskipped')" WORKFLOW_OUTCOMES="" CANCELLED_IS_WORKFLOW=""

run "CANCELLED_IS_WORKFLOW=TRUE (uppercase) → workflow" "${SET_SCRIPT}" "workflow" \
  TEST_OUTCOMES="cancelled" WORKFLOW_OUTCOMES="" CANCELLED_IS_WORKFLOW="TRUE"

# ---------------------------------------------------------------------------
echo "aggregate-failure-mode.sh"
# ---------------------------------------------------------------------------

run "all success → none" "${AGG_SCRIPT}" "none" \
  JOB_RESULTS="$(printf 'success\nsuccess')" JOB_FAILURE_MODES="$(printf 'none\nnone')" HANDLE_CANCELLED="false"

run "one failure with mode=test → test" "${AGG_SCRIPT}" "test" \
  JOB_RESULTS="failure" JOB_FAILURE_MODES="test" HANDLE_CANCELLED="false"

run "one failure with mode=workflow → workflow" "${AGG_SCRIPT}" "workflow" \
  JOB_RESULTS="failure" JOB_FAILURE_MODES="workflow" HANDLE_CANCELLED="false"

run "first failure=test, second failure=workflow → workflow" "${AGG_SCRIPT}" "workflow" \
  JOB_RESULTS="$(printf 'failure\nfailure')" JOB_FAILURE_MODES="$(printf 'test\nworkflow')" HANDLE_CANCELLED="false"

run "first failure=workflow, second failure=test → workflow (no downgrade)" "${AGG_SCRIPT}" "workflow" \
  JOB_RESULTS="$(printf 'failure\nfailure')" JOB_FAILURE_MODES="$(printf 'workflow\ntest')" HANDLE_CANCELLED="false"

run "success + failure=test in middle → test" "${AGG_SCRIPT}" "test" \
  JOB_RESULTS="$(printf 'success\nfailure\nsuccess')" JOB_FAILURE_MODES="$(printf 'none\ntest\nnone')" HANDLE_CANCELLED="false"

run "cancelled + HANDLE_CANCELLED=true → workflow" "${AGG_SCRIPT}" "workflow" \
  JOB_RESULTS="cancelled" JOB_FAILURE_MODES="none" HANDLE_CANCELLED="true"

run "cancelled + HANDLE_CANCELLED=false → none" "${AGG_SCRIPT}" "none" \
  JOB_RESULTS="cancelled" JOB_FAILURE_MODES="none" HANDLE_CANCELLED="false"

run "failure=test then cancelled (handle=true) → workflow" "${AGG_SCRIPT}" "workflow" \
  JOB_RESULTS="$(printf 'failure\ncancelled')" JOB_FAILURE_MODES="$(printf 'test\nnone')" HANDLE_CANCELLED="true"

run "already workflow + cancelled (handle=true) stays workflow" "${AGG_SCRIPT}" "workflow" \
  JOB_RESULTS="$(printf 'failure\ncancelled')" JOB_FAILURE_MODES="$(printf 'workflow\nnone')" HANDLE_CANCELLED="true"

run "missing mode line defaults to none" "${AGG_SCRIPT}" "none" \
  JOB_RESULTS="success" JOB_FAILURE_MODES="" HANDLE_CANCELLED="false"

run "HANDLE_CANCELLED=TRUE (uppercase) → workflow" "${AGG_SCRIPT}" "workflow" \
  JOB_RESULTS="cancelled" JOB_FAILURE_MODES="none" HANDLE_CANCELLED="TRUE"

# ---------------------------------------------------------------------------
echo ""
echo "Results: ${pass} passed, ${fail} failed"
[[ "${fail}" -eq 0 ]]
