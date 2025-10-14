#!/usr/bin/env bash
# gcs_download.sh - Download test results and marker files from GCS
# Returns exit code based on test pass/fail marker files

set -euo pipefail

# Usage information
usage() {
    cat <<EOF
Usage: $0 <bucket> <build_tag> <test_name> <dest_dir>

Arguments:
  bucket      - GCS bucket name (without gs:// prefix)
  build_tag   - Build tag identifier (e.g., build-123)
  test_name   - Test name (e.g., crypto, hcs)
  dest_dir    - Local destination directory

Description:
  Downloads test results from gs://{bucket}/citr/{build_tag}/
  Looks for marker files: {test_name}.pass or {test_name}.fail
  Returns exit code based on marker file found:
    0 - Test passed ({test_name}.pass exists)
    1 - Test failed ({test_name}.fail exists)
    2 - No marker file found (indeterminate)

Examples:
  # Download crypto test results
  $0 "my-bucket" "build-123" "crypto" "./results"

  # Use in conditional
  if $0 "my-bucket" "build-123" "crypto" "./results"; then
      echo "Test passed"
  else
      echo "Test failed"
  fi

Environment Variables:
  DEBUG             - Set to 1 for verbose output
  GSUTIL_TIMEOUT    - Timeout for gsutil commands (default: 60s)
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
    exit 2
}

# Info logging
info() {
    echo "[INFO] $*" >&2
}

# Check if gsutil is available
check_gsutil() {
    if ! command -v gsutil &> /dev/null; then
        error "gsutil command not found. Please install Google Cloud SDK."
    fi
    debug "gsutil found: $(which gsutil)"
}

# Download marker file and determine test status
check_marker_file() {
    local gcs_path="$1"
    local test_name="$2"
    local dest_dir="$3"

    local pass_marker="${test_name}.pass"
    local fail_marker="${test_name}.fail"

    debug "Checking for marker files in: ${gcs_path}"

    # Try to download pass marker
    if gsutil -q stat "${gcs_path}/${pass_marker}" 2>/dev/null; then
        info "Found pass marker: ${pass_marker}"
        gsutil -q cp "${gcs_path}/${pass_marker}" "${dest_dir}/" || true
        return 0
    fi

    # Try to download fail marker
    if gsutil -q stat "${gcs_path}/${fail_marker}" 2>/dev/null; then
        info "Found fail marker: ${fail_marker}"
        gsutil -q cp "${gcs_path}/${fail_marker}" "${dest_dir}/" || true
        return 1
    fi

    # No marker found
    info "No marker file found for test: ${test_name}"
    return 2
}

# Download all test artifacts
download_artifacts() {
    local gcs_path="$1"
    local dest_dir="$2"

    debug "Downloading artifacts from: ${gcs_path}"

    # Check if path exists
    if ! gsutil -q stat "${gcs_path}/*" 2>/dev/null; then
        info "No artifacts found at: ${gcs_path}"
        return 0
    fi

    # Download all files
    local timeout="${GSUTIL_TIMEOUT:-60}"
    if timeout "${timeout}s" gsutil -m cp -r "${gcs_path}/*" "${dest_dir}/" 2>/dev/null; then
        debug "Successfully downloaded artifacts"
        return 0
    else
        debug "Partial or no artifacts downloaded (may be expected if test just started)"
        return 0
    fi
}

# List available files in GCS path (for debugging)
list_gcs_files() {
    local gcs_path="$1"

    debug "Listing files in: ${gcs_path}"
    if gsutil ls "${gcs_path}/" 2>/dev/null; then
        return 0
    else
        debug "No files found or path doesn't exist"
        return 1
    fi
}

# Main execution
main() {
    # Parse arguments
    if [[ $# -ne 4 ]]; then
        usage
    fi

    local bucket="$1"
    local build_tag="$2"
    local test_name="$3"
    local dest_dir="$4"

    # Validate inputs
    if [[ -z "$bucket" || -z "$build_tag" || -z "$test_name" || -z "$dest_dir" ]]; then
        error "All arguments are required"
    fi

    # Remove gs:// prefix if present
    bucket="${bucket#gs://}"

    # Construct GCS path
    local gcs_path="gs://${bucket}/citr/${build_tag}"

    info "Downloading test results for: ${test_name}"
    info "GCS path: ${gcs_path}"
    info "Destination: ${dest_dir}"

    # Check prerequisites
    check_gsutil

    # Create destination directory
    mkdir -p "$dest_dir"

    # List files if debug enabled
    if [[ "${DEBUG:-0}" == "1" ]]; then
        list_gcs_files "$gcs_path"
    fi

    # Download artifacts first (may fail if test just started)
    download_artifacts "$gcs_path" "$dest_dir"

    # Check marker file and return appropriate exit code
    if check_marker_file "$gcs_path" "$test_name" "$dest_dir"; then
        info "Test ${test_name}: PASSED"
        exit 0
    else
        local marker_result=$?
        if [[ $marker_result -eq 1 ]]; then
            info "Test ${test_name}: FAILED"
            exit 1
        else
            info "Test ${test_name}: INDETERMINATE (no marker file)"
            exit 2
        fi
    fi
}

# Run main function
main "$@"
