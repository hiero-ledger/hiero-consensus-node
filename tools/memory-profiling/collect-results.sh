#!/usr/bin/env bash
# Collects all results from results/ directory into a summary table.
# Output is both printed to terminal and saved to results/SUMMARY.csv
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
SUMMARY="$RESULTS_DIR/SUMMARY.csv"

if [ ! -d "$RESULTS_DIR" ] || [ -z "$(ls "$RESULTS_DIR"/*.txt 2>/dev/null)" ]; then
    echo "No results found in $RESULTS_DIR"
    exit 0
fi

# CSV header
echo "test_name,peak_mib,peak_gib,current_mib,duration_secs,exit_code,timestamp,git_sha" > "$SUMMARY"

# Collect all results
for f in "$RESULTS_DIR"/*.txt; do
    test_name=$(grep '^test_name=' "$f" 2>/dev/null | cut -d= -f2)
    peak_mib=$(grep '^peak_mib=' "$f" 2>/dev/null | cut -d= -f2)
    peak_gib=$(grep '^peak_gib=' "$f" 2>/dev/null | cut -d= -f2)
    current_mib=$(grep '^current_mib=' "$f" 2>/dev/null | cut -d= -f2)
    duration_secs=$(grep '^duration_secs=' "$f" 2>/dev/null | cut -d= -f2)
    exit_code=$(grep '^exit_code=' "$f" 2>/dev/null | cut -d= -f2)
    timestamp=$(grep '^timestamp=' "$f" 2>/dev/null | cut -d= -f2)
    git_sha=$(grep '^git_sha=' "$f" 2>/dev/null | cut -d= -f2)
    echo "${test_name},${peak_mib},${peak_gib},${current_mib},${duration_secs:-},${exit_code},${timestamp},${git_sha}" >> "$SUMMARY"
done

# Print table
echo "================================================================"
echo "  MEMORY PROFILING RESULTS SUMMARY"
echo "================================================================"
printf "%-45s %10s %10s %10s %6s\n" "TEST" "PEAK(MiB)" "PEAK(GiB)" "TIME(s)" "EXIT"
printf "%-45s %10s %10s %10s %6s\n" "----" "---------" "---------" "-------" "----"

sort "$SUMMARY" | tail -n +2 | while IFS=, read -r name pmib pgib cmib dur ecode ts sha; do
    printf "%-45s %10s %10s %10s %6s\n" "$name" "$pmib" "$pgib" "${dur:-n/a}" "$ecode"
done

echo ""
echo "Full CSV: $SUMMARY"
echo "Individual results: $RESULTS_DIR/*.txt"
echo "================================================================"
