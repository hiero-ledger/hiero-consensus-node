#!/bin/bash
# Find benchmark metric runs in build/container and interactively select which
# ones to import into VictoriaMetrics via import-metrics.sh.
#
# Usage: find-metrics.sh [--build-dir DIR]
#
# Defaults:
#   --build-dir  <scripts directory>/../../../build/container

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/../../../build/container"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-dir) BUILD_DIR="$2"; shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

if [ ! -d "$BUILD_DIR" ]; then
  echo "⚠️  Build directory not found: $BUILD_DIR"
  exit 1
fi

# Discover runs: directories that contain at least one metrics*.txt file.
# A "run" is the grandparent of the node-* directories
# (build/container/<test>/<run>/node-*/data/stats/metrics.txt -> <run>).
RUN_DIRS=()
while IFS= read -r dir; do
  RUN_DIRS+=("$dir")
done < <(
  find "$BUILD_DIR" -name "metrics*.txt" -print0 \
    | xargs -0 -I{} dirname {} \
    | xargs -I{} dirname {} \
    | xargs -I{} dirname {} \
    | xargs -I{} dirname {} \
    | sort -u
)

if [ ${#RUN_DIRS[@]} -eq 0 ]; then
  echo "⚠️  No metrics files found under $BUILD_DIR"
  exit 1
fi

echo "Available benchmark runs:"
echo ""
for i in "${!RUN_DIRS[@]}"; do
  run="${RUN_DIRS[$i]}"
  rel="${run#$BUILD_DIR/}"
  file_count=$(find "$run" -name "metrics*.txt" | wc -l | tr -d ' ')
  printf "  [%2d] %s  (%s file%s)\n" "$((i+1))" "$rel" "$file_count" "$( [ "$file_count" -eq 1 ] && echo "" || echo "s" )"
done

echo ""
echo "Enter run numbers to import (e.g. 1  or  1 3  or  all):"
read -r selection

# Resolve selection to a list of run indices
SELECTED=()
if [ "$selection" = "all" ]; then
  for i in "${!RUN_DIRS[@]}"; do SELECTED+=("$i"); done
else
  for token in $selection; do
    if [[ "$token" =~ ^[0-9]+$ ]] && [ "$token" -ge 1 ] && [ "$token" -le "${#RUN_DIRS[@]}" ]; then
      SELECTED+=("$((token-1))")
    else
      echo "⚠️  Ignoring invalid selection: $token"
    fi
  done
fi

if [ ${#SELECTED[@]} -eq 0 ]; then
  echo "No valid runs selected, exiting."
  exit 0
fi

# Collect all metrics files from the selected runs
METRICS_FILES=()
for idx in "${SELECTED[@]}"; do
  run="${RUN_DIRS[$idx]}"
  while IFS= read -r -d '' file; do
    METRICS_FILES+=("$file")
  done < <(find "$run" -name "metrics*.txt" -print0 | sort -z)
done

echo ""
echo "Importing ${#METRICS_FILES[@]} file(s) from ${#SELECTED[@]} run(s)..."
"$SCRIPT_DIR/import-metrics.sh" "${METRICS_FILES[@]}"
