#!/usr/bin/env bash
set -eo pipefail

# Extracts flaky tests from JUnit XML files produced by the Gradle Test Retry plugin.
# A flaky test has a <flakyFailure> element inside its <testcase>.
#
# Usage: collect-flaky-tests.sh <search-dir> <output-file>
# Output: One line per flaky test in format: classname#methodname

SEARCH_DIR="${1:-.}"
OUTPUT_FILE="${2:-flaky-tests.txt}"

> "${OUTPUT_FILE}"

while IFS= read -r xml_file; do
    # Extract testcase lines that precede a flakyFailure element.
    # Attribute order varies (name before classname or vice versa), so extract each independently.
    grep -B1 '<flakyFailure' "${xml_file}" 2>/dev/null | \
        grep '<testcase' | while IFS= read -r line; do
            classname=$(echo "${line}" | sed -n 's/.*classname="\([^"]*\)".*/\1/p')
            testname=$(echo "${line}" | sed -n 's/.*[[:space:]]name="\([^"]*\)".*/\1/p')
            if [[ -n "${classname}" && -n "${testname}" ]]; then
                echo "${classname}#${testname}"
            fi
        done >> "${OUTPUT_FILE}" || true
done < <(find "${SEARCH_DIR}" -name "TEST-*.xml" -type f 2>/dev/null)

if [[ -s "${OUTPUT_FILE}" ]]; then
    sort -u -o "${OUTPUT_FILE}" "${OUTPUT_FILE}"
fi

count=$(wc -l < "${OUTPUT_FILE}" | tr -d ' ')
echo "Found ${count} unique flaky test(s)"
