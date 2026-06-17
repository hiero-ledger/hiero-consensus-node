#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Validates all gomplate templates in .github/workflows/templates/:
#   1. Each template renders successfully when all required vars are set.
#   2. Each template fails when any single required var is empty,
#      confirming that `required` guards are actually enforced.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
TEMPLATES_DIR="${REPO_ROOT}/.github/workflows/templates"

if ! command -v gomplate &>/dev/null; then
  echo "ERROR: gomplate is not installed or not in PATH"
  exit 1
fi

if [[ ! -d "${TEMPLATES_DIR}" ]]; then
  echo "ERROR: templates directory not found: ${TEMPLATES_DIR}"
  exit 1
fi

FAILED=0
TOTAL=0
PASSED=0

# extract the required vars from the template file
extract_required_vars() {
  local template="$1"
  # Matches: 'getenv "VAR_NAME" | required'
  # check each line for 'getenv' varname followed by '| required'
  # strip the 'getenv' prefix and the '| required' suffix, leaving just the var name
  # sort and de-duplicate the results
  grep -o 'getenv "[^"]*" | required' "${template}" \
    | sed 's/getenv "//;s/" | required//' \
    | sort -u
}

for template in "${TEMPLATES_DIR}"/*.tpl; do
  [[ -f "${template}" ]] || continue
  name="$(basename "${template}")"
  TOTAL=$((TOTAL + 1))
  template_failed=0

  required_vars=()
  while IFS= read -r var; do
    [[ -n "${var}" ]] && required_vars+=("${var}")
  done < <(extract_required_vars "${template}")

  if [[ ${#required_vars[@]} -eq 0 ]]; then
    echo "SKIP: ${name} (no required vars found)"
    PASSED=$((PASSED + 1))
    continue
  fi

  # Phase 1: all required vars set — must succeed
  full_env=()
  for var in "${required_vars[@]}"; do
    full_env+=("${var}=test-value")
  done

  if ! env "${full_env[@]}" gomplate -f "${template}" -o /dev/null 2>/dev/null; then
    echo "FAIL: ${name} — did not render with all required vars set"
    template_failed=1
  fi

  # Phase 2: each required var empty in turn — must fail
  for empty_var in "${required_vars[@]}"; do
    partial_env=()
    for var in "${required_vars[@]}"; do
      if [[ "${var}" == "${empty_var}" ]]; then
        partial_env+=("${var}=")
      else
        partial_env+=("${var}=test-value")
      fi
    done

    if env "${partial_env[@]}" gomplate -f "${template}" -o /dev/null 2>/dev/null; then
      echo "FAIL: ${name} — rendered successfully with ${empty_var} empty (required not enforced)"
      template_failed=1
    fi
  done

  if [[ "${template_failed}" -eq 0 ]]; then
    echo "PASS: ${name} (${#required_vars[@]} required vars: ${required_vars[*]})"
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
  fi
done

echo ""
echo "Results: ${PASSED} passed, ${FAILED} failed (${TOTAL} total)"
echo "faking a fail"
FAILED=1
exit "${FAILED}"
