#!/usr/bin/env bash
# Runs every individual subtask in its own fresh container.
# This gives you per-subtask peak memory (more granular than suite-level).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

FAILED=()

for script in subtasks/*/*.sh; do
    name=$(basename "$script" .sh)
    suite=$(basename "$(dirname "$script")")
    echo ""
    echo "################################################################"
    echo "  STARTING: $suite / $name"
    echo "################################################################"
    echo ""
    bash "$script" || FAILED+=("$suite/$name")
done

echo ""
echo ""
bash collect-results.sh

if [ ${#FAILED[@]} -gt 0 ]; then
    echo ""
    echo "FAILED SUBTASKS: ${FAILED[*]}"
    exit 1
fi
