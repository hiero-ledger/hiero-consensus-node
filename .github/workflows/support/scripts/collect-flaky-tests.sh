#!/usr/bin/env bash
set -eo pipefail

# Extracts flaky tests from JUnit XML files produced by the Gradle Test Retry plugin.
# A flaky test has a <flakyFailure> element inside its <testcase>.
#
# Usage: collect-flaky-tests.sh <search-dir> <output-json-file>
# Output: JSON array of objects with fields: artifactName, module, class, method, stackTrace

SEARCH_DIR="${1:-.}"
OUTPUT_FILE="${2:-flaky-tests.json}"

# Initialize empty JSON array
echo '[]' > "${OUTPUT_FILE}"

while IFS= read -r xml_file; do
    # Determine artifact name: the immediate subdirectory under search dir
    # e.g. test-reports/HAPI Test (Crypto) Report/some/path/TEST-foo.xml → "HAPI Test (Crypto) Report"
    relative_path="${xml_file#"${SEARCH_DIR}"/}"
    artifact_name="${relative_path%%/*}"

    # Determine module: strip artifact dir prefix, take segments before /build/test-results/
    # e.g. "HAPI Test (Crypto) Report/hedera-node/test-clients/build/test-results/testHapiCrypto/TEST-foo.xml"
    # path_after_artifact = "hedera-node/test-clients/build/test-results/testHapiCrypto/TEST-foo.xml"
    path_after_artifact="${relative_path#"${artifact_name}"/}"
    module_path="${path_after_artifact%%/build/test-results/*}"
    if [[ "${module_path}" == "${path_after_artifact}" ]]; then
        # No /build/test-results/ found; use the directory containing the XML
        module_path="$(dirname "${path_after_artifact}")"
    fi
    # Use the last path segment as module name
    module="${module_path##*/}"

    # Extract testcases with flakyFailure using xmlstarlet
    flaky_count=$(xmlstarlet sel -t -v "count(//testcase[flakyFailure])" "${xml_file}" 2>/dev/null || echo "0")

    for ((i=1; i<=flaky_count; i++)); do
        classname=$(xmlstarlet sel -t -v "//testcase[flakyFailure][$i]/@classname" "${xml_file}")
        testname=$(xmlstarlet sel -t -v "//testcase[flakyFailure][$i]/@name" "${xml_file}")
        stack_trace=$(xmlstarlet sel -t -v "//testcase[flakyFailure][$i]/flakyFailure[1]/stackTrace" "${xml_file}")

        # Truncate stack trace to 2000 chars
        if [[ ${#stack_trace} -gt 2000 ]]; then
            stack_trace="${stack_trace:0:2000}... (truncated)"
        fi

        # Use jq to safely build JSON with proper escaping
        entry=$(jq -n \
            --arg artifactName "${artifact_name}" \
            --arg moduleName "${module}" \
            --arg class "${classname}" \
            --arg method "${testname}" \
            --arg stackTrace "${stack_trace}" \
            '{artifactName: $artifactName, module: $moduleName, class: $class, method: $method, stackTrace: $stackTrace}')

        # Append to output file
        jq --argjson entry "${entry}" '. += [$entry]' "${OUTPUT_FILE}" > "${OUTPUT_FILE}.tmp" \
            && mv "${OUTPUT_FILE}.tmp" "${OUTPUT_FILE}"
    done
done < <(find "${SEARCH_DIR}" -name "TEST-*.xml" -type f 2>/dev/null)

# Deduplicate by class+method (keep first occurrence)
jq 'unique_by(.class + "#" + .method)' "${OUTPUT_FILE}" > "${OUTPUT_FILE}.tmp" \
    && mv "${OUTPUT_FILE}.tmp" "${OUTPUT_FILE}"

count=$(jq 'length' "${OUTPUT_FILE}")
echo "Found ${count} unique flaky test(s)"
