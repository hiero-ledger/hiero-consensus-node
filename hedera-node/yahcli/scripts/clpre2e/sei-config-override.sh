#!/usr/bin/env sh
#
# Wrapper around Sei localnode config generation for CLPR local testing.
set -eu

ORIGINAL_CONFIG_SCRIPT="${SEI_ORIGINAL_CONFIG_SCRIPT:-/sei-protocol/sei-chain/docker/localnode/scripts/step4_config_override.sh}"

"${ORIGINAL_CONFIG_SCRIPT}"

APP_TOML="${HOME}/.sei/config/app.toml"
MAX_INFLIGHT="${SEI_HISTORICAL_PROOF_MAX_INFLIGHT:-16}"
RATE_LIMIT="${SEI_HISTORICAL_PROOF_RATE_LIMIT:-0}"
BURST="${SEI_HISTORICAL_PROOF_BURST:-64}"

if [ ! -f "${APP_TOML}" ]; then
  echo "missing Sei app config: ${APP_TOML}" >&2
  exit 1
fi

tmp="${APP_TOML}.tmp"
awk \
  -v max_inflight="${MAX_INFLIGHT}" \
  -v rate_limit="${RATE_LIMIT}" \
  -v burst="${BURST}" '
  BEGIN {
    in_state_commit = 0
    emitted = 0
    seen_max_inflight = 0
    seen_rate_limit = 0
    seen_burst = 0
  }
  function emit_missing() {
    if (emitted) {
      return
    }
    if (!seen_max_inflight) {
      print "sc-historical-proof-max-inflight = " max_inflight
    }
    if (!seen_rate_limit) {
      print "sc-historical-proof-rate-limit = " rate_limit
    }
    if (!seen_burst) {
      print "sc-historical-proof-burst = " burst
    }
    emitted = 1
  }
  /^\[state-commit\]$/ {
    if (in_state_commit) {
      emit_missing()
    }
    in_state_commit = 1
    print
    next
  }
  /^\[/ && in_state_commit {
    emit_missing()
    in_state_commit = 0
  }
  in_state_commit && /^sc-historical-proof-max-inflight[[:space:]]*=/ {
    print "sc-historical-proof-max-inflight = " max_inflight
    seen_max_inflight = 1
    next
  }
  in_state_commit && /^sc-historical-proof-rate-limit[[:space:]]*=/ {
    print "sc-historical-proof-rate-limit = " rate_limit
    seen_rate_limit = 1
    next
  }
  in_state_commit && /^sc-historical-proof-burst[[:space:]]*=/ {
    print "sc-historical-proof-burst = " burst
    seen_burst = 1
    next
  }
  { print }
  END {
    if (in_state_commit) {
      emit_missing()
    }
  }
' "${APP_TOML}" > "${tmp}"
mv "${tmp}" "${APP_TOML}"

echo "Configured Sei historical proof limits: max-inflight=${MAX_INFLIGHT}, rate-limit=${RATE_LIMIT}, burst=${BURST}"
