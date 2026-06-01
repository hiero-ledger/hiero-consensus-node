#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Sets failure-mode and failed-tests outputs.
#
# Step-outcome mode (INPUT_TEST_OUTCOMES / INPUT_WORKFLOW_OUTCOMES):
#   Checks space-separated step outcomes directly.
#   workflow failure > test failure > cancelled > none.
#
# Job-aggregate mode (INPUT_JOB_STATUSES / INPUT_JOB_FAILURE_MODES / INPUT_JOB_NAMES):
#   Checks parallel arrays of job results and their failure-mode outputs.
#   Same priority ordering; populates failed-tests with names of failing jobs.
#
# Both modes may be combined; job-aggregate runs after step-outcome.
set -eo pipefail

failure_mode="none"
failed_tests=""

has_failure() {
  local arr=("$@")
  for status in "${arr[@]}"; do
    [[ "${status}" == "failure" ]] && return 0
  done
  return 1
}

has_cancellation() {
  local arr=("$@")
  for status in "${arr[@]}"; do
    [[ "${status}" == "cancelled" ]] && return 0
  done
  return 1
}

read -ra test_arr <<< "${INPUT_TEST_OUTCOMES}"
read -ra workflow_arr <<< "${INPUT_WORKFLOW_OUTCOMES}"

if has_failure "${test_arr[@]}"; then
  failure_mode="test"
fi
if has_failure "${workflow_arr[@]}"; then
  failure_mode="workflow"
fi
if [[ "${failure_mode}" == "none" ]]; then
  if has_cancellation "${workflow_arr[@]}" || has_cancellation "${test_arr[@]}"; then
    failure_mode="cancelled"
  fi
fi

# Split each whitespace-delimited input string into a Bash array (-r: no backslash escapes, -a: store as array)
read -ra job_status_arr <<< "${INPUT_JOB_STATUSES}"
read -ra job_failure_mode_arr <<< "${INPUT_JOB_FAILURE_MODES}"
read -ra job_names_arr <<< "${INPUT_JOB_NAMES}"

# "${!job_status_arr[@]}" expands to the list of array indices (0, 1, 2, …)
for i in "${!job_status_arr[@]}"; do
  status="${job_status_arr[$i]}"
  mode="${job_failure_mode_arr[$i]:-none}"   # default to "none" if the element is unset or empty
  name="${job_names_arr[$i]:-job-$i}"        # default to "job-<index>" if the element is unset or empty

  if [[ "$status" == "failure" ]]; then
    # A cancelled job that ultimately failed is treated as a full workflow failure
    [[ "$mode" == "cancelled" ]] && mode="workflow"
    case "$mode" in
      # "workflow" failure is the most severe — once set it is never downgraded
      workflow) failure_mode="workflow" ;;
      # "test" failure is only promoted if the mode hasn't already reached "workflow"
      test) [[ "$failure_mode" != "workflow" ]] && failure_mode="test" ;;
    esac
    # Append this job's name to the comma-separated list; ":+" skips the separator when the list is still empty
    failed_tests="${failed_tests:+${failed_tests}, }${name}"
  elif [[ "$status" == "cancelled" ]]; then
    # A standalone cancellation only sets failure_mode when nothing worse has been recorded yet
    [[ "$failure_mode" == "none" ]] && failure_mode="cancelled"
    failed_tests="${failed_tests:+${failed_tests}, }${name} (cancelled)"
  fi
done

echo "failure-mode=${failure_mode}" >> "${GITHUB_OUTPUT}"
echo "failed-tests=${failed_tests}" >> "${GITHUB_OUTPUT}"
