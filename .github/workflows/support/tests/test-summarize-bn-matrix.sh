#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests .github/workflows/support/scripts/summarize-bn-matrix.sh with canned matrix and job lists.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUMMARIZE="$(cd "${SCRIPT_DIR}/../scripts" && pwd)/summarize-bn-matrix.sh"

if ! command -v jq &>/dev/null; then
  echo "ERROR: jq is not installed or not in PATH"
  exit 1
fi

failures=0
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then
    echo "PASS: $1"
  else
    echo "FAIL: $1 (expected '$2', got '$3')"
    failures=$((failures + 1))
  fi
}
check_fails() { # name command...
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "FAIL: ${name} should fail"
    failures=$((failures + 1))
  else
    echo "PASS: ${name} fails"
  fi
}

MATRIX='[{"cn-ref":"main","bn-version":"v0.44.2","bn-version-override":true},
         {"cn-ref":"release/0.78","bn-version":"v0.44.2","bn-version-override":false}]'
JOBS='[{"name":"Resolve Consensus and Block Node Versions","conclusion":"success"},
       {"name":"CN main / BN v0.44.2 / Block Node v0.44.2 on CN main","conclusion":"failure"},
       {"name":"CN main / BN v0.44.2 / Block Node v0.44.2 on CN main Slack Report","conclusion":"success"},
       {"name":"BN Comms CN main / BN v0.44.2 / HAPI Tests (BN Comms)","conclusion":"success"},
       {"name":"CN release/0.78 / BN v0.44.2 / Block Node v0.44.2 on CN release/0.78","conclusion":"success"}]'

out="$(MATRIX="${MATRIX}" JOBS="${JOBS}" "${SUMMARIZE}")"
check "one line per leg" "2" "$(echo "${out}" | wc -l | tr -d ' ')"
check "failed regression and passed comms" \
  '- CN `main` / BN `v0.44.2`: regression :x: failure, BN Comms :white_check_mark: success' "$(echo "${out}" | sed -n 1p)"
check "comms skipped without override" \
  '- CN `release/0.78` / BN `v0.44.2`: regression :white_check_mark: success, BN Comms :fast_forward: skipped (ref has no BLOCK_NODE_VERSION override)' \
  "$(echo "${out}" | sed -n 2p)"

check_fails "missing regression job" env MATRIX="${MATRIX}" JOBS='[]' "${SUMMARIZE}"
check_fails "missing comms job for a ref with the override" env MATRIX="${MATRIX}" \
  JOBS="$(echo "${JOBS}" | jq -c 'map(select(.name | startswith("BN Comms") | not))')" "${SUMMARIZE}"
check_fails "unset matrix" env -u MATRIX JOBS="${JOBS}" "${SUMMARIZE}"
check_fails "unset jobs" env -u JOBS MATRIX="${MATRIX}" "${SUMMARIZE}"

if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} test(s) failed"
  exit 1
fi
echo "All tests passed"
