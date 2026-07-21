#!/usr/bin/env bash
set -euo pipefail

decode_jwt() {
  local b64="${1:-}"

  if [[ -z "${b64}" ]]; then
    echo "Error: No base64-encoded JWT provided." >&2
  fi

  local jwt
  jwt_b64=$(echo -n "${b64}" | base64 -d)
  jwt=$(echo -n "${jwt_b64}" | base64 -d)

  if [[ -z "${jwt}" ]]; then
    echo "Error: Decoded JWT is empty. Please check the Chewie token." >&2
  fi

  echo -n "${jwt}"
}

decode_jwt "$@"
