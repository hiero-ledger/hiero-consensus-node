#!/usr/bin/env bash
set -euo pipefail

decode_jwt() {
  local b64="${1:-}"

  if [[ -z "${b64}" ]]; then
    echo "Error: No base64-encoded JWT provided." >&2
    return 1
  fi

  local jwt
  local jwt_b64
  jwt_b64=$(echo -n "${b64}" | base64 -d) || { echo "Error: Failed to decode outer base64 layer of JWT." >&2; return 1; }
  jwt=$(echo -n "${jwt_b64}" | base64 -d) || { echo "Error: Failed to decode inner base64 layer of JWT." >&2; return 1; }

  if [[ -z "${jwt}" ]] || [[ "${jwt}" == "null" ]]; then
    echo "Error: Decoded JWT is empty. Please check the Chewie token." >&2
    return 1
  fi

  echo -n "${jwt}"
}

decode_jwt "$@"
