#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Reads step outcomes from environment variables and writes a single
# "failure-mode" value to $GITHUB_OUTPUT. Possible output values:
#   none     - no failures detected
#   test     - at least one test step failed
#   workflow - at least one workflow/infrastructure step failed, or a
#              cancelled test counts as a workflow failure

# tr-based helpers keep this compatible with bash 3.2 (macOS default).
trim() { echo "$1" | xargs; }
lowercase() { echo "$1" | tr '[:upper:]' '[:lower:]'; }

# Default: no failure detected.
failure_mode="none"

# Normalize the flag to lowercase so "True"/"TRUE" comparisons work.
cancelled_is_workflow=$(lowercase "$(trim "${CANCELLED_IS_WORKFLOW:-}")")

# TEST_OUTCOMES is a newline-separated list of step outcomes (e.g. "success",
# "failure", "skipped"). Any "failure" line promotes failure_mode to "test".
while IFS= read -r line; do
  outcome=$(trim "$line")
  [[ -z "$outcome" ]] && continue
  [[ "$outcome" == "failure" ]] && failure_mode="test"
done <<< "${TEST_OUTCOMES:-}"

# WORKFLOW_OUTCOMES covers infrastructure steps (checkout, setup, upload, etc.).
# A failure here is more severe, so it overrides "test".
while IFS= read -r line; do
  outcome=$(trim "$line")
  [[ -z "$outcome" ]] && continue
  [[ "$outcome" == "failure" ]] && failure_mode="workflow"
done <<< "${WORKFLOW_OUTCOMES:-}"

# When CANCELLED_IS_WORKFLOW=true, a cancelled test step is treated the same
# as a workflow failure (e.g. the run was aborted before tests could finish).
if [[ "$cancelled_is_workflow" == "true" ]]; then
  while IFS= read -r line; do
    outcome=$(trim "$line")
    [[ -z "$outcome" ]] && continue
    [[ "$outcome" == "cancelled" ]] && failure_mode="workflow"
  done <<< "${TEST_OUTCOMES:-}"
fi

# Append the result to the GitHub Actions step-output file.
echo "failure-mode=${failure_mode}" >> "${GITHUB_OUTPUT}"
