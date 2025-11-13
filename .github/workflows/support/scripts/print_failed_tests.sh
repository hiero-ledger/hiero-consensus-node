#!/usr/bin/env bash
set -eo pipefail

# Function to print failed tests/checks
function main() {
  local tests_output="$1"
  if [[ -n "${tests_output}" ]]; then
    IFS=',' read -ra TESTS <<< "${tests_output}"
    for test in "${TESTS[@]}"; do
      echo "  - ${test}"
    done
  fi

  return 0
}

echo "::group::Main Code Execution"
main
ec="${?}"
echo "Process Exit Code: ${ec}"
echo "::endgroup::"
exit "${ec}"