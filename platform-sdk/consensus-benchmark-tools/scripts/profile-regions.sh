#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks that a JMH benchmark measures the code it is meant to measure.
#
# What it is for: a benchmark times one method, the benchmark method, which calls the code under test. Its time can
# also go elsewhere: into the benchmark's own work (the timer, the histogram of latencies, the loop), into preparing
# and checking each call, or into other threads of the JVM, such as the garbage collector and the JIT compiler. This
# script reads a CPU profile of a benchmark run and shows where the time went. In a sound benchmark, nearly all the
# time of the benchmark method is spent in the code under test, and the benchmark's own work is well under a percent.
# Run it when you write a benchmark or change what it measures (docs/consensus-layer/benchmarks/writing.md, section
# "Profiling").
#
# The profile comes from async-profiler (https://github.com/async-profiler/async-profiler), a sampling profiler: many
# times a second, it records the call stack of each running thread. In its "collapsed" format, each line is one call
# stack and the number of samples taken in it. A method's share of the samples is its share of the CPU time.
#
# Usage: profile-regions.sh <collapsed-cpu.csv> <Benchmark.method> <CodeUnderTest.method> [setupMethod...]
#
# The setup methods are the benchmark's methods that run outside the measured calls, for example to prepare the input
# of each call or to check its result.
#
# Example:
#   profile-regions.sh collapsed-cpu.csv ConsensusImplBenchmark.calculateConsensus ConsensusImpl.addEvent \
#     prepareInvocation verifyInvocation
#
# The output has three parts:
#   - the benchmark method's share of all samples, and how its samples split into the code under test, each of the
#     benchmark's own frames, and the benchmark method itself (its loop);
#   - the share of the benchmark method's samples in which the thread waits: parks, waits for a monitor, or takes a
#     lock. In a single-threaded benchmark, it should be zero;
#   - the share of each setup method, of the garbage collector's threads, of the JIT compiler's threads, and of the
#     rest in all samples. With thread names in the profile (threads=true below), the rest is split by thread.
#
# To record the profile, add these arguments to the JMH command line. The library is libasyncProfiler.dylib on macOS
# and libasyncProfiler.so on Linux. -jvmArgsAppend replaces the JVM arguments of the benchmark's @Fork, so repeat its
# heap settings; -XX:+DebugNonSafepoints makes the recorded stacks accurate.
#   -prof "async:libPath=<dir>/lib/libasyncProfiler.dylib;output=collapsed;dir=<out>;event=cpu;threads=true"
#   -jvmArgsAppend "<heap settings> -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"
# async-profiler writes collapsed-cpu.csv into a directory per benchmark and parameter set below <out>.
file="$1"; bench="$2"; cut="$3"; shift 3
setups="$*"
awk -v bench="$bench" -v cut="$cut" -v setups="$setups" '
  BEGIN {
    ns = split(setups, setup, " ")
    # frames of parking, waiting for a monitor, and locking, by their names on macOS and Linux
    waits = "Unsafe\\.park|LockSupport\\.park|Object\\.wait|ObjectMonitor|ObjectSynchronizer|AbstractQueuedSynchronizer"
    waits = waits "|ReentrantLock|pthread_mutex|pthread_cond|__psynch|futex|lll_lock"
  }
  {
    n = $NF; stack = $0; sub(/ [0-9]+$/, "", stack); total += n
    gsub(/\//, ".", stack)
    # with threads=true, the first frame names the thread: [Java: GC Thread#2 tid=43011]. The name of the benchmark
    # thread contains the benchmark method, so the frame is removed before the stack is matched.
    thread = ""
    if (substr(stack, 1, 1) == "[") {
      thread = substr(stack, 2, index(stack, "]") - 2)
      stack = substr(stack, index(stack, "]") + 2)
      sub(/ tid=[0-9]+$/, "", thread); sub(/^Java: /, "", thread); sub(/#?[0-9]+$/, "", thread)
      if (thread ~ /-jmh-worker-$/) thread = "benchmark thread, outside the benchmark method and the setup methods"
    }
    i = index(stack, bench)
    if (i > 0) {
      meas += n
      if (stack ~ waits) waiting += n
      rest = substr(stack, i + length(bench)); sub(/^;/, "", rest)
      split(rest, f, ";")
      if (index(f[1], cut) > 0) k = "code under test: " cut
      else if (rest == "") k = "benchmark method itself (loop)"
      else k = "benchmark frame: " f[1]
      m[k] += n
      next
    }
    for (j = 1; j <= ns; j++) if (index(stack, setup[j]) > 0) { s["setup: " setup[j]] += n; next }
    if (stack ~ /G1|GC Thread|GCTask|VM_GC|gc/) s["GC threads"] += n
    else if (stack ~ /Compile|C2Compiler|C1/) s["JIT"] += n
    else if (thread != "") s["other: " thread] += n
    else s["other"] += n
  }
  END {
    printf "total samples %d; benchmark method %.1f%%\n", total, 100 * meas / total
    print "-- share of the benchmark method:"
    for (k in m) printf "%7.2f%%  %s\n", 100 * m[k] / meas, k | "sort -rn"; close("sort -rn")
    printf "-- waiting (park, wait, lock) in the benchmark method: %.2f%%\n", 100 * waiting / meas
    print "-- share of all samples:"
    for (k in s) printf "%7.2f%%  %s\n", 100 * s[k] / total, k | "sort -rn"; close("sort -rn")
  }
' "$file"
