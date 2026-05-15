#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Aggregates "failure-mode" outputs from multiple parallel jobs into a single
# value written to $GITHUB_OUTPUT. Inputs are two parallel newline-separated
# lists: JOB_RESULTS (success/failure/cancelled/skipped) and JOB_FAILURE_MODES
# (the failure-mode output each job reported). Lines are paired by position.
#
# Severity order: none < test < workflow. Once "workflow" is reached it sticks.

# tr-based helpers keep this compatible with bash 3.2 (macOS default).
trim() { echo "$1" | xargs; }
lowercase() { echo "$1" | tr '[:upper:]' '[:lower:]'; }

# Default: no failure detected.
failure_mode="none"

# Normalize the flag to lowercase so "True"/"TRUE" comparisons work.
handle_cancelled=$(lowercase "$(trim "${HANDLE_CANCELLED:-}")")

# Read each newline-separated list into an array.
# Using a while-read loop instead of mapfile for bash 3.2 compatibility.
result_lines=()
while IFS= read -r line; do result_lines+=("$line"); done <<< "${JOB_RESULTS:-}"

mode_lines=()
while IFS= read -r line; do mode_lines+=("$line"); done <<< "${JOB_FAILURE_MODES:-}"

# Iterate over each job by index so result and mode stay in sync.
for i in "${!result_lines[@]}"; do
  status=$(trim "${result_lines[$i]}")
  [[ -z "$status" ]] && continue

  # Fall back to "none" if the corresponding mode line is missing or blank.
  mode=$(trim "${mode_lines[$i]:-}")
  [[ -z "$mode" ]] && mode="none"

  if [[ "$status" == "failure" ]]; then
    if [[ "$failure_mode" == "none" ]]; then
      # First failure seen — adopt whatever mode this job reported.
      failure_mode="$mode"
    elif [[ "$mode" == "workflow" ]]; then
      # A later job escalates to "workflow" — override any earlier "test".
      failure_mode="workflow"
    fi
  elif [[ "$handle_cancelled" == "true" && "$status" == "cancelled" ]]; then
    # A cancelled job means the run was cut short; treat as workflow failure
    # unless we already know it's a workflow failure.
    [[ "$failure_mode" != "workflow" ]] && failure_mode="workflow"
  fi
done

# Append the result to the GitHub Actions step-output file.
echo "failure-mode=${failure_mode}" >> "${GITHUB_OUTPUT}"
