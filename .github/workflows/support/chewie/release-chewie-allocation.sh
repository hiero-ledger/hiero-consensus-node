#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Releases a Chewie compute allocation via DELETE /<api-endpoint>/<allocation-id>.
# The double-base64 encoded JWT is read from the CHEWIE_JWT_B64 environment variable
# rather than an option so it is not exposed in the process list.
release_chewie_allocation() {
  local HOST=""
  local API_ENDPOINT="api/v1/compute/allocation"
  local ALLOCATION_ID=0
  while getopts "o:e:i:" arg; do
    case $arg in
      o) HOST="${OPTARG}" ;;
      e) API_ENDPOINT="${OPTARG}" ;;
      i) ALLOCATION_ID=${OPTARG} ;;
      *)
        echo "Error: Invalid option" >&2
        echo "Usage: ${0} -o <host> -i <allocation_id> [-e <api_endpoint>]" >&2
        return 1
        ;;
    esac
  done

  if [[ -z "${HOST}" ]] || [[ "${ALLOCATION_ID}" == "0" ]]; then
    echo "Error: Missing required options" >&2
    return 1
  fi

  local CHEWIE_JWT
  CHEWIE_JWT=$("${SCRIPT_DIR}/decode-b64-jwt.sh" "${CHEWIE_JWT_B64:-}")
  if [[ "${CHEWIE_JWT}" == Error* ]]; then
    echo "Failed to decode Chewie JWT: ${CHEWIE_JWT}" >&2
    return 1
  fi

  # Log masking is exact-string: the caller's double-base64 form being a registered secret does
  # not cover the decoded token, which is a different string. Register it before it is used.
  # Guarded so a local run does not print the token via the mask command itself.
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::add-mask::${CHEWIE_JWT}"
  fi

  local response_and_code
  response_and_code=$(curl -sS -X DELETE "${HOST}/${API_ENDPOINT}/${ALLOCATION_ID}" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${CHEWIE_JWT}" \
    -w '\n%{http_code}')

  local status_code="${response_and_code##*$'\n'}"
  local response_body="${response_and_code%$'\n'*}"
  local step_summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

  # Valid status codes from Chewie's DELETE /compute/allocation/:id:
  # 204: Released. A pending allocation was cancelled; an approved one had its resources freed.
  # 400: The allocation id was not a positive integer.
  # 401: The JWT was missing, malformed, or not scoped to this repository.
  # 404: No allocation exists with that id.
  # 410: The allocation was already terminal (denied, cancelled, released, or expired). Chewie
  #      still frees the lease of an expired allocation whose namespace was preserved by the
  #      reaper (DeleteNamespaceOnExpiry=false), so this is a successful no-op, not a failure.
  # 500: Chewie failed internally.
  case "${status_code}" in
    204)
      echo "Chewie allocation ${ALLOCATION_ID} released."
      echo "Released Chewie allocation \`${ALLOCATION_ID}\`." >> "${step_summary}"
      ;;
    410)
      local detail
      detail=$(jq -r '.error // "already in a terminal state"' <<< "${response_body}")
      echo "::notice title=Nothing to Release::Chewie allocation ${ALLOCATION_ID}: ${detail}"
      echo "Chewie allocation \`${ALLOCATION_ID}\` needed no release: ${detail}." >> "${step_summary}"
      ;;
    404)
      echo "::error title=Allocation Not Found::No Chewie allocation with id ${ALLOCATION_ID}. A JWT is scoped to a single repository, so an allocation owned by another repository also reports as not found."
      echo "Response: ${response_body}" >&2
      return 1
      ;;
    *)
      echo "::error title=Release Failed::Chewie allocation ${ALLOCATION_ID} could not be released. HTTP status: ${status_code}"
      echo "Response: ${response_body}" >&2
      return 1
      ;;
  esac
}

release_chewie_allocation "$@"
