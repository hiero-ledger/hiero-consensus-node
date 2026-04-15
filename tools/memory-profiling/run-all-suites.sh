#!/usr/bin/env bash
# Runs all HAPI CI suites sequentially, collecting peak memory for each.
# Results are saved to results/ directory and summarized at the end.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SUITES=(misc misc-records-crypto token-time-consuming simple-fees-nd-reconnect smart-contracts-iss restart atomic-batch state-throttling)
FAILED=()

for suite in "${SUITES[@]}"; do
    echo ""
    echo "################################################################"
    echo "  STARTING SUITE: $suite"
    echo "################################################################"
    echo ""
    bash "suites/${suite}.sh" || FAILED+=("$suite")
done

echo ""
echo ""
bash collect-results.sh

if [ ${#FAILED[@]} -gt 0 ]; then
    echo ""
    echo "FAILED SUITES: ${FAILED[*]}"
    exit 1
fi
