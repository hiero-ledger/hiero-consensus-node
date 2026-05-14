#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

trim() { echo "$1" | xargs; }
lowercase() { echo "$1" | tr '[:upper:]' '[:lower:]'; }

failure_mode="none"
handle_cancelled=$(lowercase "$(trim "${HANDLE_CANCELLED:-}")")

result_lines=()
while IFS= read -r line; do result_lines+=("$line"); done <<< "${JOB_RESULTS:-}"

mode_lines=()
while IFS= read -r line; do mode_lines+=("$line"); done <<< "${JOB_FAILURE_MODES:-}"

for i in "${!result_lines[@]}"; do
  status=$(trim "${result_lines[$i]}")
  [[ -z "$status" ]] && continue
  mode=$(trim "${mode_lines[$i]:-}")
  [[ -z "$mode" ]] && mode="none"

  if [[ "$status" == "failure" ]]; then
    if [[ "$failure_mode" == "none" ]]; then
      failure_mode="$mode"
    elif [[ "$mode" == "workflow" ]]; then
      failure_mode="workflow"
    fi
  elif [[ "$handle_cancelled" == "true" && "$status" == "cancelled" ]]; then
    [[ "$failure_mode" != "workflow" ]] && failure_mode="workflow"
  fi
done

echo "failure-mode=${failure_mode}" >> "${GITHUB_OUTPUT}"
