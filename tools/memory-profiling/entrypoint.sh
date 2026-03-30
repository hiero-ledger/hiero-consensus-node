#!/usr/bin/env bash
set -euo pipefail

# Remap builder UID/GID to match host user (runs once on container start)
if [[ -n "${HOST_UID:-}" ]]; then
  if ! id builder >/dev/null 2>&1; then
    echo "ERROR: 'builder' user does not exist in container — check Dockerfile" >&2
    exit 1
  fi
  usermod -u "$HOST_UID" builder 2>/dev/null || echo "WARN: failed to set builder UID to $HOST_UID" >&2
  groupmod -g "${HOST_GID:-20}" builder 2>/dev/null || echo "WARN: failed to set builder GID to ${HOST_GID:-20}" >&2
  chown -R builder:builder /home/builder 2>/dev/null || true
fi

exec "$@"
