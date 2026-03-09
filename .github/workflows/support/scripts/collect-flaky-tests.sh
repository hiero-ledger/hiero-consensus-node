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

    # Extract testcases with flakyFailure using awk
    # For each testcase with a flakyFailure, extract classname, name, and stack trace
    awk '
    /<testcase / {
        # Reset for new testcase
        in_testcase = 1
        testcase_line = $0
        in_flaky = 0
        flaky_content = ""
        classname = ""
        testname = ""

        # May span multiple lines; accumulate until >
        while (testcase_line !~ />/) {
            getline
            testcase_line = testcase_line " " $0
        }

        # Extract classname
        match(testcase_line, /classname="[^"]*"/)
        if (RSTART > 0) {
            classname = substr(testcase_line, RSTART + 11, RLENGTH - 12)
        }

        # Extract name
        match(testcase_line, /[[:space:]]name="[^"]*"/)
        if (RSTART > 0) {
            testname = substr(testcase_line, RSTART + 7, RLENGTH - 8)
        }
    }

    /<flakyFailure/ && in_testcase {
        in_flaky = 1
        flaky_content = ""
        next
    }

    /<\/flakyFailure>/ && in_flaky {
        in_flaky = 0
        # Print the extracted data as tab-separated values
        if (classname != "" && testname != "") {
            printf "%s\t%s\t%s\n", classname, testname, flaky_content
        }
        # Only capture first flakyFailure per testcase
        in_testcase = 0
        next
    }

    in_flaky {
        if (flaky_content != "") {
            flaky_content = flaky_content "\n" $0
        } else {
            flaky_content = $0
        }
    }

    /<\/testcase>/ {
        in_testcase = 0
    }
    ' "${xml_file}" | while IFS=$'\t' read -r classname testname stack_trace; do
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
    done || true
done < <(find "${SEARCH_DIR}" -name "TEST-*.xml" -type f 2>/dev/null)

# Deduplicate by class+method (keep first occurrence)
jq 'unique_by(.class + "#" + .method)' "${OUTPUT_FILE}" > "${OUTPUT_FILE}.tmp" \
    && mv "${OUTPUT_FILE}.tmp" "${OUTPUT_FILE}"

count=$(jq 'length' "${OUTPUT_FILE}")
echo "Found ${count} unique flaky test(s)"
