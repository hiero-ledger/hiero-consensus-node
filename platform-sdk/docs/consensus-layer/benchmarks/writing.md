---
type: benchmark-guide
title: Writing a benchmark
last_reviewed: TBD
---

# Writing a Benchmark

How to write a JMH benchmark for a consensus-layer module so that it measures the code under test and reports how
long its calls take, the slow ones included. [calibration.md](calibration.md) sets how long it runs;
[operations.md](operations.md) tracks it. The [definition of done](README.md#definition-of-done) lists what a finished
benchmark has.

The examples come from
[`ConsensusImplBenchmark`](../../../consensus-hashgraph-impl/src/jmh/java/org/hiero/consensus/hashgraph/ConsensusImplBenchmark.java),
which measures the consensus algorithm, `ConsensusImpl#addEvent`, and follows these conventions.

## Module setup

- Benchmarks live in the module's `src/jmh` source set, which the convention plugin `org.hiero.gradle.feature.benchmark`
  adds.
- The shared Java tooling lives in [`consensus-benchmark-tools`](../../../consensus-benchmark-tools/README.md). Its
  README states who may depend on it.
- **The jmh source set's module descriptor must require the module under test.** The benchmark jar contains the
  module's classes only if `jmhModuleInfo` requires the module, directly or through its test fixtures. Without it, the
  benchmark compiles but fails at run time with `NoClassDefFoundError`.
  - For example, `consensus-benchmark-tools` requires its own module
    ([`build.gradle.kts`](../../../consensus-benchmark-tools/build.gradle.kts)), and `consensus-hashgraph-impl`
    reaches it through its test fixtures ([`build.gradle.kts`](../../../consensus-hashgraph-impl/build.gradle.kts)).
- **A jar without a module descriptor needs an `extraJavaModuleInfo` patch** in the rules block of the root
  [`settings.gradle.kts`](../../../../settings.gradle.kts), as `jmh-core` has. There, every module gets the same patch.
- **Shared tooling adds no third-party dependency besides JMH.**
- **Tooling outside a jmh source set that uses JMH** declares `requires transitive jmh.core`, not `requires static`:
  its classes cannot run without JMH, and their public methods use JMH types
  ([`module-info.java`](../../../consensus-benchmark-tools/src/main/java/module-info.java)).

## Inputs and the measured region

The measured region is the part of the benchmark method that is timed: the calls to the code under test.

- **The inputs are the same in every run.** Seed every random generator, and generate the data outside the measured
  region.
- **Generate the data once per trial** (per fork and parameter set) in `@Setup(Level.Trial)`. The example generates its
  event graph in
  [`ConsensusImplBenchmark#generateEvents`](../../../consensus-hashgraph-impl/src/jmh/java/org/hiero/consensus/hashgraph/ConsensusImplBenchmark.java#generateEvents).
- **If the code under test changes its input, every call of the benchmark method gets fresh copies,** prepared in
  `@Setup(Level.Invocation)`. One call of the benchmark method, an invocation, then processes all the copies; this
  guide calls it a pass.
  - JMH's overhead per invocation does not matter when a pass takes a fraction of a second or more.
  - In the example, `ConsensusImpl` changes the events it processes, so `prepareInvocation` copies and relinks the
    generated events for every pass.
- **Check the output after every invocation** against an exact expected value, in `@TearDown(Level.Invocation)`. With
  the same inputs, every pass gives the same value.
  - In the example, `verifyInvocation` checks the number of decided rounds.
  - Without the check, a bug can make the benchmark measure code that does nothing, and the smoke run still passes.
  - The check also catches a change to shared test fixtures that changes the benchmark's work: the expected value
    then has to change in the same diff. Results before and after such a change are not comparable
    ([operations.md](operations.md#baseline-breaks)).
- **Keep the setup's garbage out of the measured region.** If the per-invocation setup allocates, end it with
  `System.gc()`.
- **Give the code under test its production collaborators,** such as the metrics provider and the intake counter, not
  no-op or fake ones: a no-op hides a cost that a later change adds to the measured path. Turn off only output to the
  outside, such as a Prometheus port or CSV files.

## Latency

### A single call

Record the time of every call in a histogram with
[`LatencyRecorder`](../../../consensus-benchmark-tools/src/main/java/org/hiero/consensus/benchmark/tools/histogram/LatencyRecorder.java).
Its Javadoc shows the pattern: `startMeasurement()`, `lap()` after each call, `stopMeasurement()`.

- **Read the clock once per call.** `lap()` records the time since the previous `lap()`, so no time between calls is
  lost, and the recorded times add up to the whole pass. Each recorded time also includes the benchmark's work between
  two calls; keep that work small.
- **Pause the recorder around work that must not count,** such as calls of the code under test that another benchmark
  method measures: `pause()` right after a `lap()`, `resume()` right before the next measured call. The paused work
  counts neither in a latency nor in `alloc.norm`, but JMH's primary score includes it.
- **Do not use JMH's `Mode.SampleTime`.** It gets the slow calls wrong, and they matter most:
  - it times at most about 20 calls per millisecond, a random selection, which adds noise to the slow end;
  - when its buffer is full, it halves every count but keeps counts of 1, so rare slow calls weigh too much, and p99.99
    and max come out too high.
- **JMH's own result, the primary score,** uses `Mode.AverageTime` with `@OperationsPerInvocation` set to the number of
  calls per pass, so that it is the mean time per call. In the example, `calculateConsensus` sets it to the number of
  events in the graph.

### A pipeline

For code that passes items between threads, record the time of each item on entry and on exit, and add the difference
to a
[`LatencyHistogram`](../../../consensus-benchmark-tools/src/main/java/org/hiero/consensus/benchmark/tools/histogram/LatencyHistogram.java),
one per thread.

- **Submit the items at a fixed rate, whether or not earlier items are done.** A benchmark that submits N items and
  waits for them stops submitting while the code is slow, which hides the time items wait in queues (an error known as
  coordinated omission).

### Reporting

[`LatencyProfiler`](../../../consensus-benchmark-tools/src/main/java/org/hiero/consensus/benchmark/tools/histogram/LatencyProfiler.java)
turns the recorded times into JMH results. Its Javadoc lists them and how JMH combines them over iterations and forks.

- **Report percentiles through a JMH profiler (`InternalProfiler`), never through `@AuxCounters`.** JMH adds up
  `@AuxCounters` values over all iterations and forks and gives no error margin, so a percentile reported that way is
  meaningless. JMH averages a profiler's results with `AggregationPolicy.AVG`, gives them an error margin, and writes
  them to the JSON under `secondaryMetrics`, with the value of every iteration in `rawData`. `AggregationPolicy.SUM` is
  right for totals, such as the number of recorded calls.
- **Reported percentiles:** p50, p90, p99, p99.9, p99.99, and max.
  - The slow calls matter: a node handles thousands of events per second, so even the p99.9 time is exceeded several
    times per second. A round waits for all of its events, so one slow event delays the whole round.
  - Nothing beyond p99.99: the rarest slow calls mostly come from the JVM and the operating system, not from our code.
  - p99.99 needs about 10⁶ recorded calls per iteration, because `LatencyProfiler` computes the percentiles of each
    iteration separately. Calibration sets the iteration length for it
    ([calibration.md](calibration.md#iterations-and-samples)).
  - `LatencyProfiler` skips a percentile in an iteration with fewer than 100 calls above it, and `latency.skipped`
    names the percentile and the calls it needs.
- **Track `latency.mean`, not the primary score.** The primary score also includes the passes discarded because a
  garbage collection ran in them (see below).
  - The median (p50) does not replace the mean: when most calls are fast and a few are slow, the median shows only the
    fast ones. The mean is the total cost divided by the number of calls.
- **Report a metric only if someone acts on it.** Each metric is either tracked (`latency.*`, `alloc.norm`) or helps to
  find a problem (`measured.gc.discardedPasses`). JMH's `-prof gc` shows more about garbage collection when needed.

## Allocation and garbage collection

- **Measure allocation around the measured calls, not with `-prof gc`.** `-prof gc` counts everything the iteration
  allocates, the setup included. `LatencyRecorder` reads the bytes the thread has allocated
  (`com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()`, which requires `jdk.management`) before and
  after the calls, and `LatencyProfiler` reports the bytes per call as `alloc.norm`.
- **The bytes allocated per call hardly vary between runs.** They show the work the code makes for the garbage
  collector, which the latency leaves out (see the next point). A rise in allocation is the earliest sign that garbage
  collection will make the slow calls slower.
- **A garbage collection during the measured calls discards the pass; it never fails the run.** Whether a collection
  falls into the calls varies from run to run, because the G1 collector adjusts the size of its young generation while
  the program runs. `LatencyRecorder` drops the pass's times, counts the pass in `measured.gc.discardedPasses`, and the
  next invocation replaces it.
  - A GC log cannot do this check: it does not show whether a collection fell into the setup or into the measured
    calls.
  - The price is the time of the `System.gc()` and the setup of every pass, which calibration includes.
  - Frequent discards mean the heap is too small ([calibration.md](calibration.md#heap)).

## JVM arguments

- **A benchmark sets its JVM options in `jvmArgsAppend` of `@Fork`:** its heap and any other setting it needs.
- **A benchmark must not set `jvmArgsPrepend`.** The nightly driver uses it to pass the processor count
  ([operations.md](operations.md#the-processor-count)).
- **On the command line, `-jvmArgsAppend` replaces the annotation's `jvmArgsAppend`;** it does not add to it. The smoke
  run uses this to limit the heap of every benchmark in the jar. `-jvmArgsPrepend` on the command line keeps the
  annotation's `jvmArgsAppend`; the `jvmArgs` in the JSON results show both.

## Custom profilers

- **A benchmark and a profiler can share data only through static methods or fields.** JMH creates the profiler itself
  and gives the benchmark no reference to it. Keep this to a single static method of the profiler, as
  `LatencyProfiler#recorder` is.
- **A profiler runs before and after the benchmark threads.** `beforeIteration` runs before the benchmark threads
  start, and `afterIteration` after they finish. The profiler therefore sees what the threads wrote, even into plain
  (non-volatile) fields.
- **A benchmark that needs a profiler fails without it.** `LatencyProfiler#recorder` throws when the profiler is not
  running, and the benchmark gets its recorder in a setup method, so a run without `-prof` fails at the first
  invocation. The profiler forgets its recorders after every iteration, so a later benchmark in the same JVM cannot pass
  this check by accident.
- **`-prof` applies to every benchmark in the run.** A benchmark that records nothing still reports
  `measured.gc.discardedPasses`, as zero. That metric alone does not show that a benchmark records latency.

## Profiling

A profiled run proves that the benchmark measures the code under test.

1. **Record** a CPU profile with async-profiler, in collapsed format and with `-XX:+DebugNonSafepoints`. The header of
   [`profile-regions.sh`](../../../consensus-benchmark-tools/scripts/profile-regions.sh) has the JMH arguments.
2. **Split the profile** with `profile-regions.sh`: how much time goes to the code under test, to the benchmark's own
   code, to each setup method, and to other threads.
3. **Check** that:
   - the methods that take the most time belong to the code under test, not to the setup, the data generation, the
     timing code, or JMH itself;
   - the timing code (`lap()`) takes well under a percent of the measured region;
   - the measured region shows no unexpected locks, waiting, or handing of work to other threads;
   - the bytes allocated per call match what the production code should allocate.
4. **Clock reads, logging, and locks in the code under test** can be a real cost in production too; then the benchmark
   is right to measure them. In the example, the metric timing in
   [`ConsensusImpl#timedStronglySeeP`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#timedStronglySeeP)
   is such a cost.

If `alloc.norm` takes a few distinct values that differ between forks, the JIT compiler compiled the code differently
in different forks; [calibration.md](calibration.md#fork-modes) explains how to find out why.

## Measuring locally

- **Use local runs to check that results are plausible; calibrate on the benchmark machine.** Results on a laptop
  drift a lot.
- **Compare two versions in alternating short runs** (old, new, old, new), and compare each run with its neighbor, not
  two long runs.
- **Timer resolution:** `System.nanoTime` advances in steps of about 42 ns on Apple Silicon, so local latencies below a
  few µs are coarse (`LatencyRecorder` Javadoc). This does not apply on Linux x86.
- **Judge run time by JMH's "Total time"**, not by its progress estimate or Gradle's build time.
- **Run the jar directly** for quick checks: `java -jar <module>/build/libs/*-jmh-merged.jar <pattern>` after
  `./gradlew :<module>:jmhJarWithMergedServiceFiles`, with `-prof` for each profiler the benchmark needs.
