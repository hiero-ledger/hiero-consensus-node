---
type: benchmark-guide
title: Calibrating a benchmark
last_reviewed: TBD
---

# Calibrating a Benchmark

How to choose a benchmark's number of forks (`@Fork`), warmup (`@Warmup`), measurement iterations (`@Measurement`),
and heap, and how to record the choice. Choose them on the benchmark machine: values found elsewhere, such as on a
laptop, do not carry over.

The examples come from
[`ConsensusImplBenchmark`](../../../consensus-hashgraph-impl/src/jmh/java/org/hiero/consensus/hashgraph/ConsensusImplBenchmark.java),
which measures the consensus algorithm, `ConsensusImpl#addEvent`, and follows these conventions.

## The calibration run

- **Run the calibration workflow**
  [`007-user-benchmark-calibration.yaml`](../../../../.github/workflows/007-user-benchmark-calibration.yaml). It runs
  one benchmark on the benchmark machine with the driver
  [`run_benchmarks.py`](../../../../.github/workflows/support/scripts/benchmarks/run_benchmarks.py), writes the
  analysis to the job summary, and keeps the results as an artifact. Its inputs name the module, the benchmark, and the
  JMH arguments.
- **Measure generously and without warmup iterations:** many forks, many measurement iterations, and `-wi 0`. JMH
  leaves warmup iterations out of its JSON results and, for profiler results, out of its log too. With `-wi 0`, the
  analysis sees every iteration and finds out itself how long the warmup takes.
- **Only results from a quiet machine count**, in calibration as in the nightly run. The driver repeats a parameter set
  that was measured while other workloads kept the machine busy, and the analysis leaves such results out
  ([operations.md](operations.md#quiet-slots)).
- **Start a new workflow run for every repetition, not a GitHub rerun.** After a rerun, the GitHub API lists only the
  artifacts of the latest attempt. A rerun also measures the same code only if the checkout uses `github.sha`, not the
  branch.
- **Start all repetitions at once.** The workflow never runs two calibrations at the same time: it queues up to 100
  runs, of any benchmark, and runs them one after another. GitHub starts them in the order they began to wait, but does
  not guarantee it.

## The analysis

[`analyze_calibration.py`](../../../../.github/workflows/support/scripts/benchmarks/analyze_calibration.py) analyzes
one or more calibration runs together and prints the calibration block ([below](#the-calibration-record)). Its
docstring describes the method.

- **The warmup ends where the scores stop drifting in one direction.** The script looks for this point over all forks
  of all runs together: in a single run, a change in the machine's load can look like a warmup that has not finished.
  `--warmup` overrides a detected value that looks wrong.
- **The script splits the noise into three parts:** how much the iterations of one fork differ, how much the forks of
  one run differ, and how much whole runs differ. No number of forks or iterations reduces the differences between
  whole runs.
- **From these parts, it predicts the noise of a run** for any number of forks, iterations, and iteration length, and
  recommends the cheapest configuration that reaches the target, with a margin.
- **Analyze results measured in different ways in separate calls:** calibration runs, nightly runs, and laptop runs.

## Choosing the configuration

### Iterations and samples

- **Each iteration records enough calls for the highest reported percentile.** p99.99 is the time that only 0.01% of
  the calls exceed. With about 10⁶ calls per iteration, it rests on the 100 slowest calls (the script's `--samples`).
  If that makes the run too long, `LatencyProfiler` skips p99.99 ([writing.md](writing.md#reporting)).
- **Make the iterations longer rather than combining the histograms of several iterations.** Longer iterations need no
  change to `LatencyProfiler`.
- **More iterations help only if the iterations of a fork differ more than the forks do.** If the forks differ more,
  only more forks help.
- **At least two measurement iterations,** so that the script can still measure how much the iterations of a fork
  differ.

### Forks

- **If the forks differ more than their iterations, keep each fork short.** A fork costs its warmup, its iterations,
  and a few seconds to start the JVM. An iteration that does not lower the predicted noise is wasted time.
- **Choose the cheapest configuration that gives good results.** Start from two iterations and the forks the predicted
  noise needs; add forks, iterations, or seconds only where the predicted noise drops noticeably. About 10 minutes per
  benchmark on average is an orientation, not a budget to fill.
- **If whole runs differ a lot, use fewer forks and average over several nights instead.** More forks do not reduce the
  differences between runs.
- **Check for fork modes before choosing the number of forks** ([below](#fork-modes)). With fork modes, a benchmark
  needs many more forks.

### The noise between runs

- **Take it from the nightly history, not from calibration.** A few calibration runs on a quiet machine can agree with
  each other where nightly runs differ. Calibration is reliable for choosing forks and iterations, because those rest on
  dozens of forks.
- **The calibration block's "expected detectable regression" is therefore the best case.** The real value can be
  larger, until the nightly history replaces it.

## Fork modes

Each fork is a new JVM, in which the JIT compiler compiles the code anew. It does not always reach the same result:
some forks get one version of the compiled code, others another, and the versions run at different speeds. Each such
outcome is a fork mode. A benchmark with fork modes gives results that jump between forks.

- **Detect modes by `alloc.norm`.** With inputs that are the same in every run, the bytes allocated per call are the
  same to the byte in every iteration of a fork. If they differ between forks, the forks run differently compiled code;
  nothing else causes that. `analyze_calibration.py` groups the forks into modes by `alloc.norm` and reports how much
  their latency differs.
- **Find the code whose allocation differs.** Profile a few single-fork runs of each mode with async-profiler in
  allocation mode with byte totals; [`fork-modes.sh`](../../../consensus-benchmark-tools/scripts/fork-modes.sh) records
  them. [`alloc-sites.py`](../../../consensus-benchmark-tools/scripts/alloc-sites.py)
  shows, side by side and in bytes per call, which code allocates in each run.
- **Find out why the compiled code differs with `-XX:+LogCompilation`, not `-XX:+PrintInlining`.** Several compiler
  threads write `PrintInlining` output at the same time, which mixes it up beyond use.
  [`inline-tree.py`](../../../consensus-benchmark-tools/scripts/inline-tree.py) reads the XML log of
  `-XX:+LogCompilation`: for each C2 compilation, which calls it inlined and why, which allocations it removed, and
  when the JVM discarded it. Compare the compilations of two forks that the JVM never discarded; the discarded ones do
  not matter.
- **The first iteration does not show what the optimizations remove.** With `-wi 0`, the code runs unoptimized
  (interpreted or compiled by C1) for only a small part of an iteration that lasts seconds. Profile the modes instead.
- **The compiled code can depend on timing, not only on how the code ran so far.**
  - C2 does not inline a called method that already has C2 code larger than `InlineSmallCode`.
  - Whether it already has such code depends on which compiler thread finishes first, and on early `unstable_if`
    traps: the compiled code taking a branch that the compiler assumed it would never take. The same history of the
    run can therefore give different inlining decisions.
  - A large method close to C2's size limit (`NodeCountInliningCutoff`) then loses later inlining, and with it escape
    analysis.
- **Such timing effects happen in production too; they are not a flaw of the benchmark.** The benchmark reports them.
- **If the trial setup runs the code under test, the setup decides the mode.** The compilations that decide it then
  happen before the first measured call. Warmup cannot change the mode; only more forks give a fair sample of the
  modes.
- **Do not remove modes with JVM flags that change what is measured.** `-Xbatch` makes all forks the same, but with
  compiled code that normal forks never get. `CompileCommand` inlining directives force a choice that production does
  not make. Both help to find the cause: a flag that makes the modes disappear proves it.
- **Fix the modes in the code under test when their latency effect matters** (the script's "mode effect"); this is
  often cheap.
  - A recursive method that caches its results, with the cache check and the computation in one method, makes C2
    inline the computation into its own recursion. Splitting the check from the computation keeps what C2 inlines
    small. In the example,
    [`ConsensusImpl#stronglySeeP`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#stronglySeeP)
    holds the check and `ConsensusImpl#calculateStronglySeeP` the computation.
  - Prove such a fix three ways: a single `alloc.norm` value in 20 or more forks, compile logs without
    `NodeCountInliningCutoff`, and latency measured in alternating runs of the old and the fixed code.
- **If the modes stay, the benchmark needs many forks, or an average per mode.**
  - Which mode a fork gets is a matter of chance, so the share of slow forks varies from run to run, and with it the
    run's average. For example, with two modes 10% apart and half of the forks in each, the mode alone makes the result
    of a single fork vary by about 5% (10% × √(0.5 × 0.5)). To bring that down to 1% for the run takes (5 / 1)² = 25
    forks.
  - An average per mode avoids this: sort the forks into modes by `alloc.norm`, average the forks of each mode, and
    give each mode the same weight. The chance share of each mode then no longer moves the result. Track the share of
    each mode as a metric of its own, so that a change in how often a mode occurs does not look like a change in speed.
  - Find the modes as groups of close `alloc.norm` values, not with fixed limits: the values change with every change
    to the code that allocates.

## Heap

- **Choose the heap by `measured.gc.discardedPasses`, not by caution.** A pass is discarded when a garbage collection
  runs during its measured calls ([writing.md](writing.md#allocation-and-garbage-collection)). Lower `-Xms` and `-Xmx`
  step by step, take the smallest heap at which no pass is discarded, and add a margin. Below it, discarded passes
  become frequent; when every pass is discarded, the latency results are missing.
- **The heap needed depends on one pass, not on the iteration length,** because `System.gc()` runs before every pass.
  Longer iterations do not need a larger heap.
- **Set `-Xms` equal to `-Xmx`, with `-XX:+AlwaysPreTouch`,** in the `jvmArgsAppend` of `@Fork`.
- **The runner needs about the heap plus a few hundred MB per forked JVM,** plus the JVM of JMH itself and, if the same
  job builds, Gradle.
- **Measure memory use on Linux.** On macOS, the peak resident memory (RSS) shows less than a pre-touched heap uses.
- **The calibration run checks the heap again:** it discards no pass.

## The calibration record

- **The values live only in the annotations.** `@Fork`, `@Warmup`, and `@Measurement` on the benchmark class hold
  them, and nothing else repeats them. The `jmh` task and the nightly run take their configuration from them.
- **A block in a fixed format in the class Javadoc records how the values were found.** It sits next to the
  annotations, so that a change to the values and to their reasons lands in the same diff.
  - `analyze_calibration.py` prints it (`calibration_block`). For a configuration other than the recommended one, pass
    it with `--forks`, `--iterations`, `--length`, and `--warmup-iterations`.
  - The example's block is in the Javadoc of `ConsensusImplBenchmark`.
  - The block holds the values and the numbers behind them only; this file and the script describe the method.
- **The raw data is not committed.** The block links the calibration runs, whose artifacts hold the data. GitHub
  deletes artifacts after their retention period; download them to keep them.
- **Calibration does not set alert thresholds.** They come from the nightly history
  ([operations.md](operations.md#alerts)).

## When to recalibrate

When the benchmark, the machine, the JDK, or the fixed processor count changes significantly. A new machine type also
needs a new nightly history for the alerts.
