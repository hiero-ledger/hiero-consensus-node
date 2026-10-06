#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Sets failure-mode and failed-tests outputs.
#
# Step-outcome mode (INPUT_TEST_OUTCOMES / INPUT_WORKFLOW_OUTCOMES):
#   Checks space-separated step outcomes directly.
#   test failure > workflow failure > none.
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


# read -r: read input with no backslash interpretation; -a: store space-separated words into an array
# <<< feeds the variable as a one-line string ("success failure success" → arr=("success" "failure" "success"))
read -ra test_arr <<< "${INPUT_TEST_OUTCOMES}"
read -ra workflow_arr <<< "${INPUT_WORKFLOW_OUTCOMES}"

if has_failure "${workflow_arr[@]}"; then
  failure_mode="workflow"
fi
if has_failure "${test_arr[@]}"; then
  failure_mode="test"
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
    case "$mode" in
      # "test" failure is the most severe — once set it is never downgraded
      test) failure_mode="test" ;;
      # "workflow" failure is only promoted if the mode hasn't already reached "test"
      workflow) [[ "$failure_mode" != "test" ]] && failure_mode="workflow" ;;
    esac
    # Append this job's name to the comma-separated list; ":+" skips the separator when the list is still empty
    failed_tests="${failed_tests:+${failed_tests}, }${name}"
  fi
done

echo "failure-mode=${failure_mode}" >> "${GITHUB_OUTPUT}"
echo "failed-tests=${failed_tests}" >> "${GITHUB_OUTPUT}"
