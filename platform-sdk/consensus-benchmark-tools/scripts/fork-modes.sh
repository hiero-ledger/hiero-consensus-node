#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Records what alloc-sites.py and inline-tree.py need to find the cause of fork modes.
#
# What it is for: each fork of a JMH benchmark is a new JVM, in which the JIT compiler compiles the code anew, and not
# always the same way. Forks with differently compiled code allocate a different number of bytes per call (alloc.norm)
# and can run at different speeds: these are fork modes (docs/consensus-layer/benchmarks/calibration.md, section
# "Fork modes"). This script runs a benchmark in single forks, one JVM each, and records in every fork an allocation
# profile and the JIT compiler's log. It prints each fork's alloc.norm and groups the forks by it. alloc-sites.py then
# compares the allocation profiles of forks from different modes, and inline-tree.py their compile logs.
#
# Usage: fork-modes.sh <out> <forks> <jar> <benchmark> [JMH arguments...]
#
# <jar> is the module's benchmark jar (*-jmh-merged.jar). The JMH arguments select one parameter set, since both
# scripts compare runs of one parameter set, and the iterations, for example -p numNodes=4 -wi 1 -w 5s -i 2 -r 5s.
# The script sets the forks (-f 1), the profilers, and -jvmArgsAppend.
#
# Environment:
#   AP    the directory of async-profiler (https://github.com/async-profiler/async-profiler); required
#   HEAP  the benchmark's own JVM arguments from @Fork(jvmArgsAppend), which -jvmArgsAppend replaces
#         (default: -Xms2g -Xmx2g -XX:+AlwaysPreTouch)
#
# Each fork writes <out>/fork-<n>/: results.json, collapsed-alloc.csv in a directory below it, comp.log, and jmh.log.
# The forks run one after another. A mode that a fifth of the forks get shows up in about two of ten forks. Uses java
# from JAVA_HOME if set, otherwise from PATH.
#
# Example, and the next steps with two forks of different modes:
#   AP=~/async-profiler fork-modes.sh build/modes 10 "$JAR" EventSignatureValidatorBenchmark \
#     -p signingSchema=ED25519 -wi 1 -w 5s -i 2 -r 5s
#   alloc-sites.py --depth 3 EventSignatureValidatorBenchmark.validateSignatures build/modes/fork-01 build/modes/fork-04
#   inline-tree.py build/modes/fork-01/comp.log list toMemorySegment
set -euo pipefail
if [ $# -lt 4 ]; then
  sed -n '3,/^set -/p' "$0" | sed '$d; s/^# \{0,1\}//'
  exit 1
fi
: "${AP:?set AP to the directory of async-profiler}"
out="$1"; forks="$2"; jar="$3"; shift 3
heap="${HEAP:--Xms2g -Xmx2g -XX:+AlwaysPreTouch}"
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [ "$(uname)" = Darwin ]; then lib="$AP/lib/libasyncProfiler.dylib"; else lib="$AP/lib/libasyncProfiler.so"; fi
mkdir -p "$out"

for n in $(seq -w 1 "$forks"); do
  dir="$out/fork-$n"
  mkdir -p "$dir"
  status=0
  # the benchmarks that record latency fail without LatencyProfiler; it does not affect the allocation profile
  "$java" -jar "$jar" "$@" -foe true -f 1 \
    -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler \
    -prof "async:libPath=$lib;output=collapsed;dir=$dir;event=alloc;interval=16384;rawCommand=total" \
    -jvmArgsAppend "$heap -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$dir/comp.log" \
    -rf json -rff "$dir/results.json" > "$dir/jmh.log" 2>&1 || status=$?
  if [ "$status" -ne 0 ]; then
    echo "fork-$n: JMH exited with $status, see $dir/jmh.log"
    continue
  fi
  if [ "$(jq length "$dir/results.json")" -ne 1 ]; then
    echo "fork-$n: more than one parameter set; select one with -p" >&2
    exit 1
  fi
  jq -r --arg n "fork-$n" '.[0].secondaryMetrics["alloc.norm"].rawData[0] | map(round)
    | "\($n)  alloc.norm per iteration (B/op): \(join(", "))"' "$dir/results.json"
done

echo
echo "forks by alloc.norm of their last iteration (B/op):"
for dir in "$out"/fork-*; do
  [ -f "$dir/results.json" ] || continue
  jq -r --arg n "${dir##*/}" '"\(.[0].secondaryMetrics["alloc.norm"].rawData[0][-1] | round) \($n)"' \
    "$dir/results.json"
done | sort -n | awk '{ if ($1 != last) { if (NR > 1) print line; line = sprintf("%10d: %s", $1, $2) }
                       else line = line " " $2; last = $1 }
                     END { if (NR > 0) print line }'
