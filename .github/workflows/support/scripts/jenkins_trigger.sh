#!/usr/bin/env bash
# jenkins_trigger.sh - Jenkins API client for GitHub Actions
# Handles CRUMB authentication, job triggering, and status polling

set -euo pipefail

# Usage information
usage() {
    cat <<EOF
Usage: $0 <action> <jenkins_url> <job_name> [options...]

Actions:
  trigger       - Trigger a Jenkins build with parameters
  wait-start    - Wait for queued job to start, return build number
  status        - Get build status (SUCCESS/FAILURE/null)
  console       - Get console output

Examples:
  # Trigger build
  $0 trigger "https://jenkins.example.com" "nightly-jenkins" \\
    --user "user" --token "token" \\
    --param "TEST_LIST=crypto" \\
    --param "FAIL_FAST=true" \\
    --param "BUILD_TAG=build-123"

  # Wait for build to start
  $0 wait-start "https://jenkins.example.com" "nightly-jenkins" \\
    --user "user" --token "token" --queue-id "12345"

  # Get build status
  $0 status "https://jenkins.example.com" "nightly-jenkins" "42" \\
    --user "user" --token "token"

Environment Variables:
  JENKINS_USER      - Jenkins username (alternative to --user)
  JENKINS_TOKEN     - Jenkins API token (alternative to --token)
  DEBUG             - Set to 1 for verbose output
EOF
    exit 1
}

# Debug logging
debug() {
    if [[ "${DEBUG:-0}" == "1" ]]; then
        echo "[DEBUG] $*" >&2
    fi
}

# Error handling
error() {
    echo "[ERROR] $*" >&2
    exit 1
}

# Get CRUMB token for CSRF protection
get_crumb() {
    local jenkins_url="$1"
    local user="$2"
    local token="$3"

    debug "Getting CRUMB token from Jenkins"

    local crumb_response
    crumb_response=$(curl -s -u "${user}:${token}" \
        "${jenkins_url}/crumbIssuer/api/json")

    if [[ -z "$crumb_response" ]]; then
        error "Failed to get CRUMB token"
    fi

    local crumb_field crumb_value
    crumb_field=$(echo "$crumb_response" | jq -r '.crumbRequestField // empty')
    crumb_value=$(echo "$crumb_response" | jq -r '.crumb // empty')

    if [[ -z "$crumb_field" || -z "$crumb_value" ]]; then
        debug "CRUMB not required or not available"
        echo ""
    else
        debug "CRUMB: ${crumb_field}=${crumb_value}"
        echo "-H ${crumb_field}:${crumb_value}"
    fi
}

# Trigger Jenkins job with parameters
trigger_build() {
    local jenkins_url="$1"
    local job_name="$2"
    local user="$3"
    local token="$4"
    shift 4

    local params=()
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --param)
                params+=("$2")
                shift 2
                ;;
            *)
                error "Unknown parameter: $1"
                ;;
        esac
    done

    debug "Triggering build for job: ${job_name}"
    debug "Parameters: ${params[*]}"

    # Get CRUMB
    local crumb_header
    crumb_header=$(get_crumb "$jenkins_url" "$user" "$token")

    # Build form data
    local form_data=""
    for param in "${params[@]}"; do
        IFS='=' read -r key value <<< "$param"
        form_data="${form_data} -F json={\"parameter\":[{\"name\":\"${key}\",\"value\":\"${value}\"}]}"
    done

    # Trigger build
    local trigger_url="${jenkins_url}/job/${job_name}/buildWithParameters"
    debug "Trigger URL: ${trigger_url}"

    local response
    response=$(curl -s -i -u "${user}:${token}" \
        ${crumb_header} \
        -X POST \
        ${form_data} \
        "${trigger_url}")

    # Extract queue ID from Location header
    local queue_url
    queue_url=$(echo "$response" | grep -i "^Location:" | awk '{print $2}' | tr -d '\r')

    if [[ -z "$queue_url" ]]; then
        debug "Response: $response"
        error "Failed to trigger build - no Location header"
    fi

    # Extract queue ID
    local queue_id
    queue_id=$(echo "$queue_url" | grep -oP 'queue/item/\K\d+' || echo "")

    if [[ -z "$queue_id" ]]; then
        error "Failed to extract queue ID from: ${queue_url}"
    fi

    debug "Build queued with ID: ${queue_id}"
    echo "$queue_id"
}

# Wait for queued job to start and return build number
wait_for_start() {
    local jenkins_url="$1"
    local job_name="$2"
    local user="$3"
    local token="$4"
    local queue_id="$5"
    local max_attempts="${6:-120}"  # 2 minutes default (120 * 1s)

    debug "Waiting for queue item ${queue_id} to start (max ${max_attempts}s)"

    local attempts=0
    while [[ $attempts -lt $max_attempts ]]; do
        local queue_item_url="${jenkins_url}/queue/item/${queue_id}/api/json"
        local queue_info
        queue_info=$(curl -s -u "${user}:${token}" "$queue_item_url" || echo "")

        if [[ -z "$queue_info" ]]; then
            debug "Queue item not found or error - attempt $attempts"
            sleep 1
            ((attempts++))
            continue
        fi

        # Check if build has started
        local build_number
        build_number=$(echo "$queue_info" | jq -r '.executable.number // empty')

        if [[ -n "$build_number" ]]; then
            debug "Build started with number: ${build_number}"
            echo "$build_number"
            return 0
        fi

        # Check if cancelled
        local cancelled
        cancelled=$(echo "$queue_info" | jq -r '.cancelled // false')
        if [[ "$cancelled" == "true" ]]; then
            error "Build was cancelled"
        fi

        sleep 1
        ((attempts++))
    done

    error "Timeout waiting for build to start after ${max_attempts}s"
}

# Get build status
get_status() {
    local jenkins_url="$1"
    local job_name="$2"
    local build_number="$3"
    local user="$4"
    local token="$5"

    debug "Getting status for build #${build_number}"

    local build_url="${jenkins_url}/job/${job_name}/${build_number}/api/json"
    local build_info
    build_info=$(curl -s -u "${user}:${token}" "$build_url" || echo "")

    if [[ -z "$build_info" ]]; then
        error "Failed to get build info"
    fi

    local result building
    result=$(echo "$build_info" | jq -r '.result // "null"')
    building=$(echo "$build_info" | jq -r '.building // false')

    if [[ "$building" == "true" ]]; then
        echo "BUILDING"
    else
        echo "$result"
    fi
}

# Get console output
get_console() {
    local jenkins_url="$1"
    local job_name="$2"
    local build_number="$3"
    local user="$4"
    local token="$5"

    debug "Getting console output for build #${build_number}"

    local console_url="${jenkins_url}/job/${job_name}/${build_number}/consoleText"
    curl -s -u "${user}:${token}" "$console_url"
}

# Parse command line arguments
if [[ $# -lt 3 ]]; then
    usage
fi

ACTION="$1"
JENKINS_URL="$2"
JOB_NAME="$3"
shift 3

# Get credentials
USER="${JENKINS_USER:-}"
TOKEN="${JENKINS_TOKEN:-}"

# Parse remaining arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --user)
            USER="$2"
            shift 2
            ;;
        --token)
            TOKEN="$2"
            shift 2
            ;;
        --queue-id)
            QUEUE_ID="$2"
            shift 2
            ;;
        --param)
            PARAMS+=("$2")
            shift 2
            ;;
        *)
            # For positional arguments in status/console actions
            BUILD_NUMBER="${1:-}"
            shift
            ;;
    esac
done

# Validate credentials
if [[ -z "$USER" || -z "$TOKEN" ]]; then
    error "Jenkins credentials required (--user/--token or JENKINS_USER/JENKINS_TOKEN)"
fi

# Execute action
case "$ACTION" in
    trigger)
        PARAMS_ARGS=()
        for param in "${PARAMS[@]:-}"; do
            PARAMS_ARGS+=("--param" "$param")
        done
        trigger_build "$JENKINS_URL" "$JOB_NAME" "$USER" "$TOKEN" "${PARAMS_ARGS[@]}"
        ;;

    wait-start)
        if [[ -z "${QUEUE_ID:-}" ]]; then
            error "Queue ID required for wait-start action (--queue-id)"
        fi
        wait_for_start "$JENKINS_URL" "$JOB_NAME" "$USER" "$TOKEN" "$QUEUE_ID"
        ;;

    status)
        if [[ -z "${BUILD_NUMBER:-}" ]]; then
            error "Build number required for status action"
        fi
        get_status "$JENKINS_URL" "$JOB_NAME" "$BUILD_NUMBER" "$USER" "$TOKEN"
        ;;

    console)
        if [[ -z "${BUILD_NUMBER:-}" ]]; then
            error "Build number required for console action"
        fi
        get_console "$JENKINS_URL" "$JOB_NAME" "$BUILD_NUMBER" "$USER" "$TOKEN"
        ;;

    *)
        error "Unknown action: $ACTION"
        ;;
esac
