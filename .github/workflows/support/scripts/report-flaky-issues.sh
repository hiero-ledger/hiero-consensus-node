#!/usr/bin/env bash
set -eo pipefail

# Creates/manages GitHub Issues for flaky tests detected by collect-flaky-tests.sh.
# Outputs an augmented JSON file with issue URLs and status.
#
# Usage: report-flaky-issues.sh <flaky-tests-json> <config-file> <workflow-name> <run-number> <run-url> [pr-number]
# Output: flaky-issues.json (augmented JSON with issue_number, issue_url, is_new, title fields)

FLAKY_TESTS_JSON="${1}"
CONFIG_FILE="${2}"
WORKFLOW_NAME="${3}"
RUN_NUMBER="${4}"
RUN_URL="${5}"
PR_NUMBER="${6:-}"

REPO="${GITHUB_REPOSITORY}"
OUTPUT_FILE="flaky-issues.json"
TODAY=$(date -u +%Y-%m-%d)

if [[ ! -f "${FLAKY_TESTS_JSON}" ]]; then
    echo "::error::Flaky tests JSON file not found: ${FLAKY_TESTS_JSON}"
    exit 1
fi

count=$(jq 'length' "${FLAKY_TESTS_JSON}")
if [[ "${count}" -eq 0 ]]; then
    echo "No flaky tests to process."
    echo '[]' > "${OUTPUT_FILE}"
    exit 0
fi

echo "Processing ${count} flaky test(s)..."

# Read config file for manager lookup
if [[ ! -f "${CONFIG_FILE}" ]]; then
    echo "::warning::Config file not found: ${CONFIG_FILE}. Issues will be created without assignees."
fi

# Function to look up managers by artifact name from config
lookup_managers() {
    local artifact_name="${1}"
    local managers=""

    if [[ ! -f "${CONFIG_FILE}" ]]; then
        return
    fi

    # Parse the YAML config to extract artifact-pattern and managers pairs
    # Use a simple approach: iterate through categories
    local current_pattern=""
    local current_managers=""
    local matched=false

    while IFS= read -r line; do
        # Match artifact-pattern lines
        if [[ "${line}" =~ artifact-pattern:\ *\"(.*)\" ]]; then
            current_pattern="${BASH_REMATCH[1]}"
        elif [[ "${line}" =~ artifact-pattern:\ *\'(.*)\'  ]]; then
            current_pattern="${BASH_REMATCH[1]}"
        elif [[ "${line}" =~ artifact-pattern:\ +(.*) ]]; then
            current_pattern="${BASH_REMATCH[1]}"
            # Remove surrounding quotes if present
            current_pattern="${current_pattern%\"}"
            current_pattern="${current_pattern#\"}"
            current_pattern="${current_pattern%\'}"
            current_pattern="${current_pattern#\'}"
        fi

        # Match managers lines
        if [[ "${line}" =~ managers:\ *\[(.*)\] ]]; then
            current_managers="${BASH_REMATCH[1]}"
            # Remove spaces around commas
            current_managers=$(echo "${current_managers}" | tr -d ' ')

            # Check if artifact name matches the pattern (glob matching)
            # shellcheck disable=SC2053
            if [[ -n "${current_pattern}" ]] && [[ "${artifact_name}" == ${current_pattern} ]]; then
                managers="${current_managers}"
                matched=true
                break
            fi
        fi
    done < "${CONFIG_FILE}"

    if [[ "${matched}" == "false" ]]; then
        echo "::warning::No matching artifact-pattern found for '${artifact_name}' in ${CONFIG_FILE}" >&2
    fi

    echo "${managers}"
}

# Initialize output array
echo '[]' > "${OUTPUT_FILE}"

# Fetch all existing flaky-test issues once upfront.
# Uses the GraphQL-backed `gh issue list` which returns real-time data,
# unlike `gh search issues` which suffers from search-index delay and
# caused duplicate issue creation.
echo "Fetching existing flaky-test issues..."
ALL_FLAKY_ISSUES=$(gh api \
    --paginate \
    "/repos/${REPO}/issues?labels=flaky-test&state=all&per_page=100" \
    --jq '[.[] | select(.pull_request == null) | {number: .number, state: (.state | ascii_upcase), title: .title, url: .html_url, closedAt: .closed_at}]' \
    | jq -s 'add // []' || echo '[]')
echo "Found $(echo "${ALL_FLAKY_ISSUES}" | jq 'length') existing flaky-test issue(s)"

# Process each flaky test
for i in $(seq 0 $((count - 1))); do
    entry=$(jq -r ".[$i]" "${FLAKY_TESTS_JSON}")
    module=$(echo "${entry}" | jq -r '.module')
    class=$(echo "${entry}" | jq -r '.class')
    method=$(echo "${entry}" | jq -r '.method')
    stack_trace=$(echo "${entry}" | jq -r '.stackTrace')
    artifact_name=$(echo "${entry}" | jq -r '.artifactName')

    # Build canonical title
    TITLE="[Flaky Test] ${class}#${method}"
    echo "Processing: ${TITLE}"

    # Look up existing issues from the pre-fetched cache (exact title match)
    exact_matches=$(echo "${ALL_FLAKY_ISSUES}" | jq --arg title "${TITLE}" '[.[] | select(.title == $title)]')
    open_matches=$(echo "${exact_matches}" | jq '[.[] | select(.state == "OPEN")]')
    closed_matches=$(echo "${exact_matches}" | jq '[.[] | select(.state == "CLOSED")]')

    open_count=$(echo "${open_matches}" | jq 'length')
    closed_count=$(echo "${closed_matches}" | jq 'length')

    is_new="false"
    issue_number=""
    issue_url=""

    if [[ "${open_count}" -gt 0 ]]; then
        # Use existing open issue
        issue_number=$(echo "${open_matches}" | jq -r '.[0].number')
        issue_url=$(echo "${open_matches}" | jq -r '.[0].url')
        is_new="false"
        echo "  Found existing open issue: #${issue_number}"
    else
        # Need to create a new issue (whether or not closed ones exist)
        is_new="true"

        # Look up managers for this artifact
        managers_csv=$(lookup_managers "${artifact_name}")

        # Build assignee flags
        assignee_args=""
        if [[ -n "${managers_csv}" ]]; then
            IFS=',' read -ra manager_array <<< "${managers_csv}"
            for mgr in "${manager_array[@]}"; do
                assignee_args="${assignee_args} --assignee ${mgr}"
            done
        fi

        # Build issue body
        body="## Flaky Test Detected

| Field | Value |
|---|---|
| Module | \`${module}\` |
| Class | \`${class}\` |
| Method | \`${method}\` |
| First detected | ${TODAY} |
| Workflow | [${WORKFLOW_NAME} #${RUN_NUMBER}](${RUN_URL}) |

### Failure Detail (first occurrence)
\`\`\`
${stack_trace}
\`\`\`"

        # Add previous tickets section if closed issues exist
        if [[ "${closed_count}" -gt 0 ]]; then
            body="${body}

### Previous Tickets"
            for j in $(seq 0 $((closed_count - 1))); do
                closed_num=$(echo "${closed_matches}" | jq -r ".[$j].number")
                closed_at=$(echo "${closed_matches}" | jq -r ".[$j].closedAt" | cut -d'T' -f1)
                body="${body}
- #${closed_num} (closed ${closed_at})"
            done

            echo "  Found ${closed_count} closed issue(s), creating new issue with links"
        else
            echo "  No existing issues found, creating new issue"
        fi

        # Create the issue
        # shellcheck disable=SC2086
        create_result=$(gh issue create \
            --repo "${REPO}" \
            --title "${TITLE}" \
            --label "flaky-test" \
            ${assignee_args} \
            --body "${body}" \
            2>&1)

        # Extract issue URL from creation output (gh issue create prints the URL)
        issue_url="${create_result}"
        issue_number=$(echo "${issue_url}" | grep -oE '[0-9]+$')
        echo "  Created issue: #${issue_number}"

        # Add newly created issue to the local cache to prevent self-duplication
        # within the same run (e.g. if collect produced duplicate entries)
        new_cache_entry=$(jq -n \
            --arg number "${issue_number}" \
            --arg title "${TITLE}" \
            --arg url "${issue_url}" \
            '{number: ($number | tonumber), state: "OPEN", title: $title, url: $url, closedAt: null}')
        ALL_FLAKY_ISSUES=$(echo "${ALL_FLAKY_ISSUES}" | jq --argjson entry "${new_cache_entry}" '. += [$entry]')
    fi

    # Update occurrence tracking comment
    if [[ -n "${issue_number}" ]]; then
        # Search for existing tracking comment
        comments=$(gh api "/repos/${REPO}/issues/${issue_number}/comments" \
            --paginate \
            --jq '.[] | select(.body | contains("<!-- flaky-test-occurrences -->")) | {id: .id, body: .body}' \
            2>/dev/null || echo "")

        pr_ref="N/A"
        if [[ -n "${PR_NUMBER}" ]]; then
            pr_ref="#${PR_NUMBER}"
        fi

        new_row="| {N} | ${TODAY} | [${WORKFLOW_NAME} #${RUN_NUMBER}](${RUN_URL}) | ${pr_ref} |"

        if [[ -n "${comments}" ]]; then
            # Update existing tracking comment
            comment_id=$(echo "${comments}" | head -1 | jq -r '.id')
            existing_body=$(echo "${comments}" | head -1 | jq -r '.body')

            # Count existing rows to determine occurrence number
            row_count=$(echo "${existing_body}" | grep -cE '^\| [0-9]+ \|' || true)
            next_num=$((row_count + 1))
            new_row="${new_row//\{N\}/${next_num}}"

            updated_body="${existing_body}
${new_row}"

            gh api "/repos/${REPO}/issues/comments/${comment_id}" \
                --method PATCH \
                --field body="${updated_body}" \
                > /dev/null 2>&1
            echo "  Updated occurrence tracking comment (occurrence #${next_num})"
        else
            # Create new tracking comment
            new_row="${new_row//\{N\}/1}"
            tracking_body="<!-- flaky-test-occurrences -->
## Occurrence History

| # | Date | Workflow Run | PR |
|---|------|--------------|----|
${new_row}"

            gh api "/repos/${REPO}/issues/${issue_number}/comments" \
                --method POST \
                --field body="${tracking_body}" \
                > /dev/null 2>&1
            echo "  Created occurrence tracking comment"
        fi
    fi

    # Add augmented entry to output
    augmented=$(echo "${entry}" | jq \
        --arg issue_number "${issue_number}" \
        --arg issue_url "${issue_url}" \
        --arg is_new "${is_new}" \
        --arg title "${TITLE}" \
        '. + {issue_number: ($issue_number | tonumber), issue_url: $issue_url, is_new: ($is_new == "true"), title: $title}')

    jq --argjson entry "${augmented}" '. += [$entry]' "${OUTPUT_FILE}" > "${OUTPUT_FILE}.tmp" \
        && mv "${OUTPUT_FILE}.tmp" "${OUTPUT_FILE}"
done

new_count=$(jq '[.[] | select(.is_new == true)] | length' "${OUTPUT_FILE}")
existing_count=$(jq '[.[] | select(.is_new == false)] | length' "${OUTPUT_FILE}")
echo "Done: ${new_count} new issue(s), ${existing_count} existing issue(s)"
