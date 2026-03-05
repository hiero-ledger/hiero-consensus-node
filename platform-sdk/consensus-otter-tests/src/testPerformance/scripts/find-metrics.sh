#!/bin/bash
# Find benchmark metric runs in build/container and ~/home/benchmark-results,
# and interactively select which ones to import into VictoriaMetrics via import-metrics.sh.
#
# Usage: find-metrics.sh [--build-dir DIR] [--benchmark-dir DIR]
#
# Defaults:
#   --build-dir      <scripts directory>/../../../build/container
#   --benchmark-dir  ~/benchmark-results

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/../../../build/container"
BENCHMARK_DIR="$HOME/benchmark-results/remote"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-dir)      BUILD_DIR="$2";      shift 2 ;;
    --benchmark-dir)  BENCHMARK_DIR="$2";  shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

# Discover metric run directories under a given root.
# A "run" is the grandparent of the node-* directories
# (root/<test>/<run>/node-*/data/stats/metrics.txt -> 4 levels up = <run>).
discover_build_runs() {
  local root="$1"
  if [ ! -d "$root" ]; then
    return
  fi
  find "$root" -name "metrics*.txt" -print0 \
    | xargs -0 -I{} dirname {} \
    | xargs -I{} dirname {} \
    | xargs -I{} dirname {} \
    | xargs -I{} dirname {} \
    | sort -u
}

# Discover runs from the benchmark-results directory.
# Each immediate subdirectory of root is treated as one run
# (root/<run>/node-*/data/stats/metrics.txt -> 3 levels up = <run>).
# Only subdirectories that contain at least one metrics file are included.
discover_benchmark_runs() {
  local root="$1"
  if [ ! -d "$root" ]; then
    return
  fi
  for dir in "$root"/*/; do
    [ -d "$dir" ] || continue
    if find "$dir" -name "metrics*.txt" -print -quit 2>/dev/null | grep -q .; then
      echo "${dir%/}"
    fi
  done | sort -u
}

BUILD_RUN_DIRS=()
while IFS= read -r dir; do
  BUILD_RUN_DIRS+=("$dir")
done < <(discover_build_runs "$BUILD_DIR")

BENCHMARK_RUN_DIRS=()
while IFS= read -r dir; do
  BENCHMARK_RUN_DIRS+=("$dir")
done < <(discover_benchmark_runs "$BENCHMARK_DIR")

if [ ${#BUILD_RUN_DIRS[@]} -eq 0 ] && [ ${#BENCHMARK_RUN_DIRS[@]} -eq 0 ]; then
  echo "⚠️  No metrics files found under:"
  echo "     $BUILD_DIR"
  echo "     $BENCHMARK_DIR"
  exit 1
fi

# Combined list for unified numbering; entries are parallel arrays.
RUN_DIRS=()
RUN_ROOTS=()

print_category() {
  local label="$1"
  local root="$2"
  local array_name="$3"  # name of the source array (eval-based, bash 3.2 compatible)
  local start_idx="$4"   # current global index offset

  local count
  eval "count=\${#${array_name}[@]}"

  if [ "$count" -eq 0 ]; then
    echo "$label  ($root)"
    echo "  (none found)"
    echo ""
    return
  fi

  echo "$label  ($root)"
  local i=0
  while [ "$i" -lt "$count" ]; do
    local run
    eval "run=\${${array_name}[$i]}"
    local rel="${run#$root/}"
    local file_count
    file_count=$(find "$run" -name "metrics*.txt" | wc -l | tr -d ' ')
    local global_num=$(( start_idx + i + 1 ))
    printf "  [%2d] %s  (%s file%s)\n" \
      "$global_num" "$rel" "$file_count" "$( [ "$file_count" -eq 1 ] && echo "" || echo "s" )"
    RUN_DIRS+=("$run")
    RUN_ROOTS+=("$root")
    i=$(( i + 1 ))
  done
  echo ""
}

echo "Available benchmark runs:"
echo ""

if [ ! -d "$BUILD_DIR" ]; then
  echo "Build directory  ($BUILD_DIR)"
  echo "  ⚠️  Directory not found"
  echo ""
else
  print_category "Build directory" "$BUILD_DIR" BUILD_RUN_DIRS 0
fi

if [ ! -d "$BENCHMARK_DIR" ]; then
  echo "Benchmark results  ($BENCHMARK_DIR)"
  echo "  ⚠️  Directory not found"
  echo ""
else
  print_category "Benchmark results" "$BENCHMARK_DIR" BENCHMARK_RUN_DIRS "${#BUILD_RUN_DIRS[@]}"
fi

if [ ${#RUN_DIRS[@]} -eq 0 ]; then
  echo "No runs available to select."
  exit 1
fi

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
