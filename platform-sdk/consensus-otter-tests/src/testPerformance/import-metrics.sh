#!/bin/bash
# Import Prometheus metrics files into VictoriaMetrics.
# Usage: import-metrics.sh [--vm-url URL] [paths...]
#
# Defaults:
#   --vm-url  http://localhost:8428
#   paths     build/container/ConsensusLayerBenchmark/benchmark/node-*/data/stats/metrics.txt
#
# paths can be individual files, directories (searched for metrics*.txt),
# or glob patterns.

set -e

VM_URL="http://localhost:8428"
METRICS_ARGS=()

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --vm-url) VM_URL="$2"; shift 2 ;;
    *)        METRICS_ARGS+=("$1"); shift ;;
  esac
done

# Default path if none provided
if [ ${#METRICS_ARGS[@]} -eq 0 ]; then
  METRICS_ARGS=(build/container/ConsensusLayerBenchmark/benchmark/node-*/data/stats/metrics.txt)
fi

# Expand arguments to actual files (handles directories, globs, and individual files)
METRICS_FILES=()
for arg in "${METRICS_ARGS[@]}"; do
  if [ -d "$arg" ]; then
    while IFS= read -r -d '' file; do
      METRICS_FILES+=("$file")
    done < <(find "$arg" -maxdepth 1 -type f -name 'metrics*.txt' -print0)
  elif [ -f "$arg" ]; then
    METRICS_FILES+=("$arg")
  else
    for file in $arg; do
      [ -f "$file" ] && METRICS_FILES+=("$file")
    done
  fi
done

echo "Importing metrics into $VM_URL..."
echo "Metrics files to import: ${#METRICS_FILES[@]}"

imported_count=0
total_lines=0
for metrics_file in "${METRICS_FILES[@]}"; do
  if [ -f "$metrics_file" ]; then
    echo "  Importing $(basename "$metrics_file")..."
    line_count=$(wc -l < "$metrics_file")
    # Timestamps in the metrics files are milliseconds; the Prometheus import
    # endpoint expects seconds, so divide the third field by 1000.
    awk 'NF==3 { $3 = int($3/1000) } 1' "$metrics_file" \
      | curl -s -X POST "$VM_URL/api/v1/import/prometheus" --data-binary @-
    if [ $? -eq 0 ]; then
      ((imported_count++))
      ((total_lines+=line_count))
      echo "    ✓ Imported $line_count lines"
    else
      echo "    ⚠️  Failed to import"
    fi
  fi
done

if [ $imported_count -eq 0 ]; then
  echo "⚠️  No metrics imported."
else
  echo "✓ Imported $imported_count files successfully ($total_lines total lines)"
fi
