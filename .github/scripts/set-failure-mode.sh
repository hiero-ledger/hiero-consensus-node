#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

trim() { echo "$1" | xargs; }
lowercase() { echo "$1" | tr '[:upper:]' '[:lower:]'; }

failure_mode="none"
cancelled_is_workflow=$(lowercase "$(trim "${CANCELLED_IS_WORKFLOW:-}")")

while IFS= read -r line; do
  outcome=$(trim "$line")
  [[ -z "$outcome" ]] && continue
  [[ "$outcome" == "failure" ]] && failure_mode="test"
done <<< "${TEST_OUTCOMES:-}"

while IFS= read -r line; do
  outcome=$(trim "$line")
  [[ -z "$outcome" ]] && continue
  [[ "$outcome" == "failure" ]] && failure_mode="workflow"
done <<< "${WORKFLOW_OUTCOMES:-}"

if [[ "$cancelled_is_workflow" == "true" ]]; then
  while IFS= read -r line; do
    outcome=$(trim "$line")
    [[ -z "$outcome" ]] && continue
    [[ "$outcome" == "cancelled" ]] && failure_mode="workflow"
  done <<< "${TEST_OUTCOMES:-}"
fi

echo "failure-mode=${failure_mode}" >> "${GITHUB_OUTPUT}"
