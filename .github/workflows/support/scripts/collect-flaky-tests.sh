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
    grep -B5 "flakyFailure" "${xml_file}" 2>/dev/null | \
        grep 'testcase.*classname=' | \
        sed -n 's/.*classname="\([^"]*\)".*name="\([^"]*\)".*/\1#\2/p' >> "${OUTPUT_FILE}" || true
done < <(find "${SEARCH_DIR}" -name "TEST-*.xml" -type f 2>/dev/null)

if [[ -s "${OUTPUT_FILE}" ]]; then
    sort -u -o "${OUTPUT_FILE}" "${OUTPUT_FILE}"
fi

count=$(wc -l < "${OUTPUT_FILE}" | tr -d ' ')
echo "Found ${count} unique flaky test(s)"
