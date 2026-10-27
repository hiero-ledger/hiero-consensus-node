---
type: benchmark-guide
title: Background for benchmarks
last_reviewed: TBD
---

# Background for Benchmarks

This file explains the terms, mechanics, and tools that the other files of this guide and the developer scripts in
[`consensus-benchmark-tools/scripts/`](../../../consensus-benchmark-tools/scripts) take for granted. It is written for
a Java developer who has not used JMH, the JIT compiler, or a profiler. Each section says what a thing is, why it
matters for a benchmark, and where the guide uses it. The rules are in [writing.md](writing.md),
[calibration.md](calibration.md), and [operations.md](operations.md).

Facts about this repository link to its classes and scripts. Facts about JMH and the HotSpot JVM link to their own
documentation: the [JMH samples](https://github.com/openjdk/jmh/tree/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples)
and JMH's Javadoc, and the JDK 25 documentation, mainly of the
[`java` command](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html). A link to a JVM option's entry
there is a source, not a request to set the option.

This file does not explain Gradle, GitHub Actions, Kubernetes, or Grafana. The parts of
[operations.md](operations.md) on the machine's setup, the build logic, and the workflows assume them. The section
[The infrastructure](#the-infrastructure) names only what a benchmark author meets.

## JMH

[JMH](https://github.com/openjdk/jmh), the Java Microbenchmark Harness, is the OpenJDK tool for measuring how long a
piece of Java code takes. It generates the code that calls a benchmark, starts fresh JVMs to run it, and computes the
results.

### The benchmark method and the operation

- **The benchmark method** is a public method annotated with `@Benchmark`. JMH calls it again and again for a fixed
  time
  ([JMHSample_01](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_01_HelloWorld.java)).
  One call of it is an invocation. In `Mode.Throughput` and `Mode.AverageTime` ([below](#modes-and-the-primary-score)),
  JMH reads the clock only before the first and after the last invocation, and divides by their number
  ([`BenchmarkGenerator`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/generators/core/BenchmarkGenerator.java)).
- **JMH calls it on threads of its own, the benchmark threads:** one by default, more with `@Threads` or `-t`
  ([`Defaults`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/Defaults.java),
  `THREADS`).
- **JMH reports its results per operation.** By default, an operation is one invocation.
  `@OperationsPerInvocation(n)` says that one invocation performs n operations, and JMH divides the time by n
  ([`OperationsPerInvocation`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/annotations/OperationsPerInvocation.java)).
- In [`ConsensusImplBenchmark`](../../../consensus-hashgraph-impl/src/jmh/java/org/hiero/consensus/hashgraph/ConsensusImplBenchmark.java),
  one invocation of `calculateConsensus` adds every event of a generated graph, and the operation is one call of
  `ConsensusImpl#addEvent`. The guide calls such an invocation a pass
  ([writing.md](writing.md#inputs-and-the-measured-region)).
- **The code under test** is the production code that the benchmark method calls; everything else in the benchmark is
  there to feed it and to measure it. In this guide, "a call" means one call of the code under test, that is, one
  operation; a call of the benchmark method is an invocation.

### State and setup levels

- **A state object holds the data a benchmark works on.** Its class is annotated with `@State`, and JMH creates it and
  passes it to the benchmark method
  ([JMHSample_03](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_03_States.java)).
  The benchmark class can be its own state, as `ConsensusImplBenchmark` is
  ([JMHSample_04](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_04_DefaultState.java)).
  The scope says who shares it: with `Scope.Thread`, each benchmark thread has its own; with `Scope.Benchmark`, all
  threads share one.
- **Setup and teardown methods** (`@Setup`, `@TearDown`) prepare the state and check it. Their time does not count in
  the results
  ([JMHSample_05](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_05_StateFixtures.java),
  [JMHSample_06](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_06_FixtureLevel.java)).
- **The level says how often they run**
  ([`Level`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/annotations/Level.java)):
  - `Level.Trial`: once per trial. A trial is all iterations of one benchmark method with one parameter set in one
    fork ([below](#forks-and-iterations)).
  - `Level.Iteration`: before or after every iteration.
  - `Level.Invocation`: before or after every invocation. JMH then has to read the clock around every invocation to
    leave the setup out. Its Javadoc allows this level only for invocations that take more than a millisecond; the
    longer the invocation, the smaller the share of this overhead.
- **A parameter** is a field annotated with `@Param` and a list of values. Each combination of the values is a
  parameter set, which JMH runs in trials of its own; `-p name=value` chooses the values on the command line
  ([JMHSample_27](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_27_Params.java)).
- **Why it matters:** the level decides what the measured calls see. Data made in a trial setup is the same in every
  iteration of a fork; data that the code under test changes needs fresh copies for every invocation
  ([writing.md](writing.md#inputs-and-the-measured-region)).

### Forks and iterations

- **A fork is a fresh JVM** that JMH starts to run one benchmark method with one parameter set. JMH forks by default,
  so that benchmarks do not change each other's compiled code
  ([JMHSample_12](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_12_Forking.java)),
  and so that the results show how much JVMs differ from each other
  ([JMHSample_13](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_13_RunToRun.java)).
  `-f 0` runs every benchmark of the run in JMH's own JVM instead, one after another.
- **JMH runs the forks one after another,** so one forked JVM runs at a time, next to JMH's own JVM
  ([`Runner`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/Runner.java),
  `runSeparate`). A fork sends its results to JMH's JVM over a local network connection
  ([`BinaryLinkClient`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/link/BinaryLinkClient.java)).
- **Each fork runs warmup iterations, then measurement iterations.** An iteration lasts a fixed time, during which JMH
  calls the benchmark method again and again. JMH checks the time between invocations, so the running invocation
  always finishes, and an iteration shorter than one invocation still runs one
  ([`BenchmarkGenerator`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/generators/core/BenchmarkGenerator.java):
  `while(!control.isDone)`). During the warmup, the JIT compiler compiles the code
  ([below](#interpreter-c1-and-c2)); JMH discards the warmup's results.
- **A parameter set takes about forks × (warmup time + measurement time),** plus, in every fork, a few seconds to start
  the JVM and the time of the trial setup, such as generating the inputs.
- **A per-invocation setup runs within the iteration's time.** It does not count in the score, but it leaves time for
  fewer invocations: in the generated code, it runs inside the loop that `control.isDone` ends (`BenchmarkGenerator`).
  [writing.md](writing.md#allocation-and-garbage-collection) counts this price.
- **Why it matters:** calibration chooses these numbers ([calibration.md](calibration.md#choosing-the-configuration)),
  and the smoke run uses the smallest ones ([operations.md](operations.md#the-smoke-run)).
- **A fork's JVM options come from three lists,** in this order: `jvmArgsPrepend`, `jvmArgs`, and `jvmArgsAppend`.
  Each list comes from the command line if it is given there, and otherwise from `@Fork`; without `jvmArgs`, a fork
  gets the options of JMH's own JVM
  ([`Runner`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/Runner.java)).
  So `-jvmArgsAppend` on the command line replaces the annotation's `jvmArgsAppend`, and leaves its other lists alone
  ([writing.md](writing.md#jvm-arguments)).
- **Annotations set the defaults; options on the command line override them**
  ([JMHSample_20](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_20_Annotations.java)).
  `java -jar <benchmark jar> [options] <pattern>` runs the benchmarks whose names match the pattern, a regular
  expression; `-h` lists every option. The ones the guide uses:

|   Annotation   |      Option      |                                     Meaning                                     |
|----------------|------------------|---------------------------------------------------------------------------------|
| `@Fork`        | `-f`             | the number of forks                                                             |
| `@Fork`        | `-jvmArgsAppend` | JVM options of the forks ([writing.md](writing.md#jvm-arguments))               |
| `@Warmup`      | `-wi`, `-w`      | the number of warmup iterations, and their length                               |
| `@Measurement` | `-i`, `-r`       | the number of measurement iterations, and their length                          |
| `@Param`       | `-p`             | the values of a parameter                                                       |
|                | `-prof`          | a profiler ([below](#profilers))                                                |
|                | `-rf`, `-rff`    | the format and the file of the results ([below](#the-results))                  |
|                | `-foe true`      | fail when a benchmark throws ([operations.md](operations.md#failing-on-errors)) |

### Modes and the primary score

- **The mode says what JMH measures** (`@BenchmarkMode`;
  [JMHSample_02](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_02_BenchmarkModes.java),
  [`Mode`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/annotations/Mode.java)):
  - `Mode.Throughput`: operations per unit of time;
  - `Mode.AverageTime`: the average time per operation;
  - `Mode.SampleTime`: the times of a random selection of calls, from which JMH computes percentiles
    ([below](#mean-and-percentiles));
  - `Mode.SingleShotTime`: the time of a single call, for code that is measured before the JIT compiler has compiled
    it.
- **The primary score is JMH's own result** for a benchmark method and parameter set, in the unit of its mode. JMH
  averages it over the measurement iterations of all forks and gives an error margin, `scoreError`: the half-width of a
  99.9% confidence interval
  ([`Result#getScoreError`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/results/Result.java)).
- **Why it matters:** a benchmark that follows this guide uses `Mode.AverageTime` and takes its percentiles from
  `LatencyProfiler`, not from `Mode.SampleTime` ([writing.md](writing.md#a-single-call)).

### The Blackhole

- **The JIT compiler removes computations whose results nobody uses** (dead-code elimination), so a benchmark can end
  up measuring nothing
  ([JMHSample_08](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_08_DeadCode.java)).
- **A `Blackhole` uses a value in a way the compiler cannot see through.** The benchmark method either returns its
  result, which JMH then passes to a blackhole, or passes the result to `Blackhole#consume` itself
  ([JMHSample_09](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_09_Blackholes.java)).
  `ConsensusImplBenchmark#calculateConsensus` consumes the rounds of every call.
- **The output check is a different guard:** it catches code under test that does nothing because of a bug, which no
  blackhole can see ([writing.md](writing.md#inputs-and-the-measured-region)).

### Profilers

- **A JMH profiler runs next to the benchmark and adds results of its own,** the secondary results, which JMH prints
  below the primary score. `-prof <name>` turns one on, `-lprof` lists them, and `-prof <name>:help` shows their
  options
  ([JMHSample_35](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java)).
  - `-prof gc` adds the allocation per operation (`gc.alloc.rate.norm`) and the number and time of the garbage
    collections. It counts all allocation of the iteration, the setup included
    ([writing.md](writing.md#allocation-and-garbage-collection)).
  - `-prof async` runs [async-profiler](#async-profiler).
- **`@AuxCounters` is JMH's other way to add results:** it marks a state class whose public fields JMH reports as
  results of their own, meant for counting events
  ([JMHSample_23](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_23_AuxCounters.java)).
  [writing.md](writing.md#reporting) says why percentiles cannot go that way.
- **A custom profiler is a class that implements JMH's `InternalProfiler`,** passed with
  `-prof <fully qualified class name>`. JMH calls it before and after every iteration.
  [`LatencyProfiler`](../../../consensus-benchmark-tools/src/main/java/org/hiero/consensus/benchmark/tools/histogram/LatencyProfiler.java)
  is one; its Javadoc describes how it works, and [writing.md](writing.md#custom-profilers) gives the rules for such
  profilers.

### The results

- **`-rf json -rff <file>` writes the results as JSON:** one entry per benchmark method and parameter set, with
  - `params` and `jvmArgs`: how it ran;
  - `primaryMetric`: the primary score, its `scoreError`, and `rawData`, one list per fork with the value of every
    measurement iteration;
  - `secondaryMetrics`: the profilers' results in the same shape, `latency.mean` and `alloc.norm` among them.
- **JMH combines a secondary result over iterations and forks by the result's aggregation policy:** it averages a
  result with `AggregationPolicy.AVG` and gives it an error margin, and it adds up a result with
  `AggregationPolicy.SUM`. The Javadoc of `LatencyProfiler` says which of its results use which policy.
- **On the console,** JMH prints its progress with an estimate of the time left (`# Run progress: … ETA …`), and at
  the end the time the run took (`# Run complete. Total time: …`)
  ([`BaseRunner`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/runner/BaseRunner.java)).
- **Who reads the JSON:** the CI scripts of the nightly run and of calibration
  ([`run_benchmarks.py`](../../../../.github/workflows/support/scripts/benchmarks/run_benchmarks.py),
  [`analyze_calibration.py`](../../../../.github/workflows/support/scripts/benchmarks/analyze_calibration.py),
  [`publish_results.py`](../../../../.github/workflows/support/scripts/benchmarks/publish_results.py)), and the
  developer scripts `fork-modes.sh` and `alloc-sites.py`.

### alloc.norm

- **`alloc.norm` is the number of bytes that the measured calls allocate, per operation,** as `LatencyProfiler`
  reports it. It is not JMH's `gc.alloc.rate.norm`.
- **Where:** [writing.md](writing.md#allocation-and-garbage-collection) says how it is measured and what it shows,
  [calibration.md](calibration.md#fork-modes) why it detects fork modes, and [operations.md](operations.md#alerts) how
  alerts use it.

## The JVM at run time

JVM options of the form `-XX:+Name` turn an option on, `-XX:-Name` turn it off, and `-XX:Name=value` set it
([overview of options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#overview-of-java-options)).

### Interpreter, C1, and C2

- **The JVM starts by interpreting the bytecode,** one instruction at a time
  ([interpreter](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#interpreter)). Methods that run often are
  compiled to machine code while the program runs, by the JIT (just-in-time) compilers
  ([JIT compilers](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#JITCompilers)).
- **HotSpot has two JIT compilers:** C1 compiles fast and optimizes little; C2 takes longer and optimizes much more
  ([C1](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#C1Compiler),
  [C2](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#C2Compiler)).
- **A method moves up in tiers:** interpreted first, then compiled by C1 with code that records a profile of how it
  runs, such as which branches it takes, then compiled by C2 from that profile
  ([tiered compilation](https://docs.oracle.com/en/java/javase/25/vm/java-hotspot-virtual-machine-performance-enhancements.html#GUID-85BA7DE7-4AF9-47D9-BFCF-379230C66412)).
  This profile is the JVM's own record; a profiler's output ([below](#the-tools)) is also called a profile.
- **Compilation runs in the background,** on compiler threads, while the program goes on running the older code (the
  entry for `-Xbatch` in the
  [extra options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#extra-options-for-java)). The
  JVM chooses the number of compiler threads by the number of processors (the entry for `-XX:CICompilerCount` in the
  [JIT compiler options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#advanced-jit-compiler-options-for-java)).
  That number counts C1 and C2 threads together; about a third of them are C1 threads
  ([`compilationPolicy.cpp`](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/hotspot/share/compiler/compilationPolicy.cpp),
  `CompilationPolicy::initialize`).
- **Why it matters:** the first seconds of a fork run slower code, which is why a fork needs a warmup
  ([calibration.md](calibration.md#the-analysis)). What the measurement times is mostly C2 code. The speed can keep
  changing after the hot methods have C2 code: compilations are discarded and redone ([below](#discarded-compilations)),
  and G1 adapts its young generation ([below](#allocation-and-garbage-collection)). Calibration finds when the
  changes stop.

### Inlining and its limits

- **Inlining copies the body of a called method into the caller,** so that the compiler optimizes both as one: the
  compiled code of a method includes the methods inlined into it
  ([nmethod](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#nmethod)). Later optimizations build on it,
  escape analysis among them ([below](#escape-analysis)).
- **C2 limits what it inlines**
  ([JIT compiler options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#advanced-jit-compiler-options-for-java)):
  - A method larger than `MaxInlineSize` bytes of bytecode is inlined only where it is called often ("hot"), and then
    only up to `FreqInlineSize` bytes.
  - A method that already has C2 code larger than `InlineSmallCode` bytes of machine code is not inlined: the caller
    calls that code instead
    ([`ciMethod.cpp`](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/hotspot/share/ci/ciMethod.cpp),
    `inline_instructions_size`).
  - A compilation stops inlining when it has grown too large, at `NodeCountInliningCutoff`, a flag that only debug
    builds of the JVM can change
    ([`c2_globals.hpp`](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/hotspot/share/opto/c2_globals.hpp)). C2
    measures this size in the nodes of the graph it builds from the code
    ([C2](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#C2Compiler): "sea of nodes"), not in bytes of
    bytecode, so everything inlined counts toward it.
  - A recursive method is inlined into itself one level deep by default (`MaxRecursiveInlineLevel` in
    `c2_globals.hpp`). Each level copies the whole body, so a large recursive method brings its compilation close to
    the cutoff.
- **The compile log gives the reason for every decision** ([below](#the-compile-log)), for example "too big", "hot
  method too big", "already compiled into a big method", or "NodeCountInliningCutoff".
- **Why it matters:** one inlining decision can decide whether an allocation stays, and how fast the code runs.
  [calibration.md](calibration.md#fork-modes) traces fork modes to such decisions.

### Escape analysis

- **C2 checks whether a new object escapes the compiled code:** whether it is stored in a field, returned, or passed
  to a method that is not inlined. For an object that does not escape, C2 can remove the allocation and keep the
  object's fields in registers (scalar replacement)
  ([escape analysis](https://docs.oracle.com/en/java/javase/25/vm/java-hotspot-virtual-machine-performance-enhancements.html#GUID-6BD8FCB5-995B-4AE9-BFAA-B2C7DE2BA5CD)).
- **A typical case is the iterator of a for-each loop.** If C2 inlines `iterator()`, which allocates it, and its
  `hasNext` and `next`, the iterator does not escape, and C2 can remove its allocation. In `ConsensusImpl#stronglySeeP`,
  before the split that [calibration.md](calibration.md#fork-modes) describes, the iterator was removed in some forks
  and allocated in others.
- **Why it matters:** escape analysis changes `alloc.norm`, and with it the work of the garbage collector. A lost
  inlining decision shows as more bytes per call.

### Discarded compilations

- **C2 compiles for the common case.** It leaves out code that the profile shows as never run and puts an uncommon trap
  in its place ([uncommon trap](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#uncommonTrap)). The
  trap for a branch that was never taken is called `unstable_if`
  ([`deoptimization.hpp`](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/hotspot/share/runtime/deoptimization.hpp),
  `Reason_unstable_if`).
- **When the code reaches a trap, the JVM discards the compiled code** and goes on in the interpreter
  ([deoptimization](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#deoptimization)). The compile log
  says that the code was "made not entrant": new calls no longer enter it, and calls already in it finish
  ([`nmethod.hpp`](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/hotspot/share/code/nmethod.hpp), `not_entrant`).
  The method is compiled again later, with the new profile.
- **Why it matters:** early compilations are often discarded. The ones that run during most of the measurement are
  those that were never discarded, so the guide compares only those
  ([calibration.md](calibration.md#fork-modes)).

### Fork modes

- **Each fork compiles the code anew, and the result can differ.** Most decisions follow the profile, which is nearly
  the same in every fork with the same inputs. But compilation runs in the background, so some decisions depend on
  timing. C2 reads the profile when the compilation runs, so a branch first taken just before or just after that
  moment changes what it sees, and can cause an early `unstable_if` trap ([above](#discarded-compilations)). And
  whether a called method already has C2 code larger than `InlineSmallCode` depends on which compiler thread finished
  first.
- **A fork mode is one such outcome.** Forks in different modes have different `alloc.norm` values and run at
  different speeds. [calibration.md](calibration.md#fork-modes) describes how to detect, explain, and handle them;
  the scripts `fork-modes.sh`, `alloc-sites.py`, and `inline-tree.py` do the work ([below](#which-script-reads-what)).
- **How many forks show a mode:** a mode that a share p of the forks get is missing from n forks with the probability
  (1 − p)ⁿ. For p = 0.2, that is 11% with 10 forks and 1% with 20.
- **How much a mode mix adds to the noise:** whether a fork lands in a mode is a yes-or-no outcome with probability
  p, whose standard deviation is √(p(1 − p)). So two modes whose speeds differ by a share Δ add a CV of about
  Δ × √(p(1 − p)) to a fork ([below](#the-coefficient-of-variation)), which combines with the fork's other noise as a
  variance ([below](#noise-and-its-three-components)).

### Allocation and garbage collection

- **Allocation itself is cheap:** a thread takes the memory for a new object from a buffer of its own
  ([TLAB](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#TLAB)). The cost comes later, from the garbage
  collector, which finds the objects still in use and frees the memory of the others.
- **The heap is split into generations:** new objects go into the young generation, and objects that survive several
  collections move into the old generation. A young collection runs when the young generation is full
  ([generations](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-collector-implementation.html#GUID-16166ED9-32C6-402D-BB22-FD85BCB04E57)).
  So the more a pass allocates, the larger a young generation it needs to finish without a collection.
- **G1, the default collector, collects the young generation often, in short stop-the-world pauses,** during which
  every application thread waits
  ([G1 basic concepts](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html#GUID-E9CB81BC-92E5-489E-8A2E-760691A41CDF)).
  It sizes the young generation while the program runs, to meet its goal for pause times
  ([young generation sizing](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html#GUID-C268549C-7D95-499C-9B24-A6670B44E49C)),
  so when a collection happens differs from run to run.
- **Heap options**
  ([extra options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#extra-options-for-java),
  [GC options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#advanced-garbage-collection-options-for-java)):
  `-Xms` is the initial heap size, `-Xmx` the maximum; `-XX:+AlwaysPreTouch` makes the JVM touch every page of the heap
  at start, instead of on first use. Between `-Xms` and `-Xmx`, G1 grows and shrinks the heap while the program runs
  ([Java heap sizing](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html#GUID-3F6AE9BA-32AF-4C36-B8C0-721EE45D17A7));
  with both equal, the heap has a fixed size. With `-Xms` equal to `-Xmx` and `-XX:+AlwaysPreTouch`, neither a resize
  of the heap nor the first use of a page falls into the measured calls.
- **`System.gc()` asks the JVM to collect now**
  ([`System#gc`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.html#gc%28%29)). By
  default, the JVM processes every such call (the entry for `-XX:+DisableExplicitGC` in the GC options).
- **A JVM needs memory beyond its heap,** for example for the metadata of its classes (the entry for
  `-XX:MaxMetaspaceSize` in the GC options) and for its compiled code
  ([code cache](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#codeCache)).
- **The GC log** is the JVM's record of every collection, turned on with `-Xlog:gc`
  ([unified logging](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#enable-logging-with-the-jvm-unified-logging-framework)).
- **Why it matters:** a pause during a measured call makes that call slow, and its latency then measures the collector,
  not the code. The guide keeps collections out of the measured calls, discards a pass that has one, and uses
  `alloc.norm` for the cost that the collector adds later
  ([writing.md](writing.md#allocation-and-garbage-collection), [calibration.md](calibration.md#heap)).

## Measuring latency

### Mean and percentiles

- **The latency of a call of the code under test is the time it takes.** The mean is the total time divided by the
  number of calls.
- **A percentile is the latency that a share of the calls stays at or below:** 50% for p50 (the median), 99% for p99,
  99.99% for p99.99, which only one call in 10,000 exceeds. The max is the slowest call.
- **A sample, in the latency results, is one recorded call:** `latency.samples` counts them, and the calibration
  block's "samples per iteration" and the `--samples` of `analyze_calibration.py` count the same. In a profile, a
  sample is one recorded call stack ([below](#async-profiler)).
- **A histogram records latencies as counts in buckets,** so that it can compute percentiles without keeping every
  value
  ([`LatencyHistogram`](../../../consensus-benchmark-tools/src/main/java/org/hiero/consensus/benchmark/tools/histogram/LatencyHistogram.java)).
- **Why it matters:** the mean is the cost of the work; the percentiles show the slow calls, which the mean hides.
  [writing.md](writing.md#reporting) says which ones the benchmarks report and why.

### Why p99.99 needs about a million samples

- **A percentile is estimated from the calls above it.** Of 10⁶ calls, 100 lie above p99.99; of 10⁴ calls, only one,
  so a single unusual call decides the value.
- **The count of calls above a percentile varies from run to run by about its square root,** like any count of rare
  events: about ±10 for 100 calls, about ±1 for one call. With 100 calls above it, the estimate is stable; with one, it
  is chance.
- **Where:** [writing.md](writing.md#reporting) says how many calls an iteration needs for each percentile, and
  [calibration.md](calibration.md#iterations-and-samples) how calibration sets the iteration length for them.

### Noise and its three components

- **Noise is the difference between measurements of the same code.** `analyze_calibration.py` splits it into three
  components ([calibration.md](calibration.md#the-analysis); the script's docstring has the method):
  - **between the iterations of one fork:** interruptions by the operating system, other processes, the timing of
    garbage collections;
  - **between the forks of one run:** each JVM starts anew, and parts of it, such as its compilations, do not run the
    same way twice
    ([JMHSample_13](https://github.com/openjdk/jmh/blob/1.37/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_13_RunToRun.java));
    fork modes are the extreme case;
  - **between whole runs:** the state of the machine, its other workloads, or another machine.
- **Averaging lowers noise by the square root of the count:** the mean of n independent values varies √n times less
  than one value. More iterations average out only the first component; more forks the first two; nothing in one run
  averages out the third.
- **The components add up as variances,** the squares of their standard deviations, each divided by the count that
  averages it out; a longer iteration averages out more of its own noise. The docstring of `analyze_calibration.py`
  has the formula that predicts the noise of a run this way.
- **Noise measured from a few values is itself uncertain:** from three or four runs, the noise between runs can come
  out far too small. Forks and iterations come by the dozen, so their noise is measured much better. That is why the
  guide takes the noise between runs from the nightly history, which has many runs
  ([calibration.md](calibration.md#the-noise-between-runs)).
- **Why it matters:** a regression smaller than the noise cannot be told from chance. The noise sets how many forks
  and iterations a benchmark needs and how small a regression an alert can detect
  ([operations.md](operations.md#alerts)).

### The coefficient of variation

- **The coefficient of variation (CV) is the standard deviation divided by the mean,** given as a percentage. The
  standard deviation is the typical distance of a value from the mean; a CV of 0.5% means that values typically lie
  about 0.5% from the mean. As a share of the mean, it compares benchmarks of very different speeds.
- **`analyze_calibration.py` reports each noise component as a CV.** It computes on the natural logarithms of the
  scores, so that a standard deviation of 0.01 is about 1% of the value (its docstring).

### Confidence intervals and correlation

- **A confidence interval** is a range around a measured value, computed so that in 95 of 100 repetitions (for a 95%
  interval) it contains the true value. If the noise follows the normal distribution, the 95% interval reaches 1.96
  standard deviations to each side of the value; that reach is its half-width. JMH's `scoreError` is such a
  half-width ([above](#modes-and-the-primary-score)).
- **How often noise alone causes a large deviation:** with normal noise, a value lies more than 2 standard deviations
  above the mean in about 2.3% of cases, and more than 3 above it in about 0.13%; counting both sides doubles these
  shares. An alert on regressions looks at one side only. For n independent values in a row on that side, the chance
  is the one-sided share to the power of n.
- **The calibration block's "expected detectable regression"** is 1.96 times the predicted CV of one run's result: the
  half-width of that result's 95% confidence interval
  ([`analyze_calibration.py`](../../../../.github/workflows/support/scripts/benchmarks/analyze_calibration.py#calibration_block)).
  - A threshold at that size catches a regression of exactly that size in only about half of the runs. It is the size
    that stands out from the noise, not one that is reliably caught.
  - The difference of two single runs, each with its own noise, varies √2 times as much as one run. That applies when
    two runs are compared, as in an A/B test; the block's number does not include it. Against the mean of many
    nights, which has little noise of its own, the factor nearly disappears.
- **A correlation** says how closely two quantities move together along a straight line, from −1 to 1. Near 1, one
  rises almost exactly in step with the other, as the latency did with the load in
  [operations.md](operations.md#quiet-slots).

### The benchmark machine

- **Other work on the machine slows a benchmark, on its own core and on others.** A core can run two threads at once
  (SMT, simultaneous multithreading), which share its execution units and caches; the operating system counts each as
  a CPU. Many cores share the L3 cache, a fast memory on the processor that holds recently used data, and all share
  the main memory. [operations.md](operations.md#the-benchmark-machine) says what slowed the benchmark machine most.
- **The benchmark machine is shared,** not dedicated to the benchmarks; [operations.md](operations.md#quiet-slots) says
  which results count.
- **Load is counted in CPUs:** one CPU busy all the time is a load of 1 CPU; 0.5 CPU is one CPU busy half the time,
  or two CPUs a quarter of the time.
- **The JVM sizes its own threads by the number of processors it sees:** its garbage collector threads and its
  compiler threads (the entries for `-XX:ActiveProcessorCount` in the
  [runtime options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#advanced-runtime-options-for-java)
  and for `-XX:CICompilerCount` in the JIT compiler options). In a container, the JVM takes that number from the
  container (`UseContainerSupport`, on by default, in the runtime options), so it follows the size of the runner's pod
  ([below](#the-infrastructure)).
- **So the driver pins the processor count** ([operations.md](operations.md#the-processor-count)). Otherwise a larger
  pod would give the forks more compiler threads, a different timing of their compilations, and possibly different fork
  modes ([above](#fork-modes)).

## The tools

### async-profiler

- **async-profiler is a sampling profiler for the JVM:** many times a second, it records the call stack of each
  running thread, Java, JVM, and native frames alike
  ([CPU profiling](https://github.com/async-profiler/async-profiler/blob/v4.2/docs/ProfilingModes.md#cpu-profiling)).
  A method's share of the samples is its share of the CPU time. Its own time is its share of the samples in which it is
  the innermost frame; its total time, its callees included, is its share of the samples whose stack contains it. The
  garbage collector's and the JIT compiler's threads show up too. Download a release from its
  [GitHub page](https://github.com/async-profiler/async-profiler).
- **Two modes matter here** (`event=` in JMH's `-prof async`):
  - `cpu`: where the CPU time goes;
  - `alloc`: which code allocates, sampled from the allocations that really happen, so it sees what escape analysis
    left
    ([allocation profiling](https://github.com/async-profiler/async-profiler/blob/v4.2/docs/ProfilingModes.md#allocation-profiling)).
    With `total` (`rawCommand=total` through JMH), the samples are weighted by bytes instead of counted
    ([options](https://github.com/async-profiler/async-profiler/blob/v4.2/docs/ProfilerOptions.md)).
- **JMH's `-prof async` takes its options after a colon, separated by semicolons:** `libPath`, the profiler's library
  file; `event`; `output`, such as `collapsed` or `flamegraph`; `dir`, the output directory; and `rawCommand`, which
  passes options to async-profiler itself. `-prof async:help` lists them all
  ([`AsyncProfiler`](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/profile/AsyncProfiler.java)).
- **A CPU profile shows little of waiting.** A thread that waits uses no CPU, so the profile shows only the CPU time
  around the wait, in frames such as `Unsafe.park` or those of a lock; `profile-regions.sh` counts those frames.
  Wall-clock mode (`event=wall`) samples every thread, running or waiting
  ([wall-clock profiling](https://github.com/async-profiler/async-profiler/blob/v4.2/docs/ProfilingModes.md#wall-clock-profiling)).
- **`-XX:+DebugNonSafepoints` makes the stacks accurate.** Without it, small inlined methods are often missing from the
  profile
  ([troubleshooting](https://github.com/async-profiler/async-profiler/blob/v4.2/docs/Troubleshooting.md)). A
  safepoint is a point in the code where the JVM can stop a thread
  ([safepoint](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#safepoint)); the flag makes compiled code
  carry the debugging information that maps it back to methods between those points too
  ([`globals.hpp`](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/hotspot/share/runtime/globals.hpp)). It needs
  `-XX:+UnlockDiagnosticVMOptions`.
- **Collapsed stacks** are the profile as text: one line per distinct call stack, the frames separated by semicolons,
  then the number of samples
  ([output formats](https://github.com/async-profiler/async-profiler/blob/v4.2/docs/OutputFormats.md)). Scripts read
  this format.
- **A flame graph draws the collapsed stacks:** each box is a method, its width is its share of the samples, and the
  methods it calls sit on top of it. The horizontal axis is not time
  ([flame graphs](https://github.com/async-profiler/async-profiler/blob/v4.2/docs/FlamegraphInterpretation.md)).
  `output=flamegraph` writes one as HTML, for looking around; the scripts give the numbers that the guide checks.
- **Where:** [writing.md](writing.md#profiling) (CPU) and [calibration.md](calibration.md#fork-modes) (allocation);
  the headers of `profile-regions.sh` and `alloc-sites.py` have the JMH arguments.

### JFR

- **Java Flight Recorder (JFR) is the profiler built into the JVM.** `-XX:StartFlightRecording` starts a recording
  ([runtime options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#advanced-runtime-options-for-java))
  into a `.jfr` file, which holds CPU samples among many other events. The `jfr` tool prints and summarizes it
  ([`jfr`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jfr.html)).
- **Why it matters:** a JMH benchmark measures one piece of code alone; JFR shows where a whole running node spends its
  time. [`jfr-components.sh`](../../../consensus-benchmark-tools/scripts/jfr-components.sh) reads recordings of
  running nodes, for example from [Sloth](../../../consensus-sloth), which runs a small network of consensus nodes
  under load.

### The compile log

- **`-XX:+LogCompilation` writes an XML log of every compilation** to the file that `-XX:LogFile` names. It needs
  `-XX:+UnlockDiagnosticVMOptions`
  ([JIT compiler options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#advanced-jit-compiler-options-for-java)).
  The log holds each compilation's inlining decisions with their reasons, the allocations that escape analysis removed,
  the traps, and when the code was made not entrant.
- **`-XX:+PrintInlining` prints the same inlining decisions as text,** but the compiler threads mix their output
  ([calibration.md](calibration.md#fork-modes)).
- **Diagnostic options are not for production:** `-XX:+UnlockDiagnosticVMOptions` turns on options that can change or
  disappear without notice
  ([advanced options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#advanced-options-for-java)).
- **Two flags change the compilation, to test a cause:** `-Xbatch` compiles in the foreground, so that a method waits
  for its compiled code instead of going on in older code
  ([extra options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#extra-options-for-java)), and
  `-XX:CompileCommand` gives the compiler an instruction for one method, such as `dontinline`
  ([JIT compiler options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#advanced-jit-compiler-options-for-java)).
  [calibration.md](calibration.md#fork-modes) says why they diagnose fork modes but must not remove them.
- **Where:** `inline-tree.py` reads the log; `fork-modes.sh` records it.

### Which script reads what

The developer scripts are in [`consensus-benchmark-tools/scripts/`](../../../consensus-benchmark-tools/scripts); each
script's header gives its usage, and the
[module README](../../../consensus-benchmark-tools/README.md#scripts) lists them.

|                                        Script                                         |                  Input                   |                       Made with                        |                                            Answers                                             |
|---------------------------------------------------------------------------------------|------------------------------------------|--------------------------------------------------------|------------------------------------------------------------------------------------------------|
| [`profile-regions.sh`](../../../consensus-benchmark-tools/scripts/profile-regions.sh) | collapsed CPU stacks                     | async-profiler, `event=cpu`                            | Is the time spent in the code under test?                                                      |
| [`fork-modes.sh`](../../../consensus-benchmark-tools/scripts/fork-modes.sh)           | the benchmark jar                        |                                                        | Which forks fall into which mode? Records the inputs of `alloc-sites.py` and `inline-tree.py`. |
| [`alloc-sites.py`](../../../consensus-benchmark-tools/scripts/alloc-sites.py)         | JMH JSON and collapsed allocation stacks | `-rf json`; async-profiler, `event=alloc` with `total` | Which code allocates how many bytes per call?                                                  |
| [`inline-tree.py`](../../../consensus-benchmark-tools/scripts/inline-tree.py)         | the compile log                          | `-XX:+LogCompilation`                                  | What did C2 inline, and why?                                                                   |
| [`jfr-components.sh`](../../../consensus-benchmark-tools/scripts/jfr-components.sh)   | JFR recordings of running nodes          | JFR, for example in a Sloth run                        | Which component of a node takes the CPU time?                                                  |
| [`sloth-stages.sh`](../../../consensus-benchmark-tools/scripts/sloth-stages.sh)       | the metrics of a Sloth run               | the nodes of the run                                   | How long do events take through each stage of a node?                                          |
| [`event-shapes.py`](../../../consensus-benchmark-tools/scripts/event-shapes.py)       | the block files of one mainnet node      | mainnet's block stream                                 | What shape do mainnet events have, as `EventShape.MAINNET` holds it?                           |

## The infrastructure

This section names the pieces of the pipeline ([README.md](README.md#the-pipeline)) that a benchmark author meets.

- **The benchmark jar,** `<module>-<version>-jmh-merged.jar` in the module's `build/libs`, holds the benchmarks, the
  classes they need, and JMH as its main class, so that `java -jar` runs it alone. Its task,
  `jmhJarWithMergedServiceFiles`, merges the service registrations (`META-INF/services`) of the jars it packs
  ([`org.hiero.gradle.feature.benchmark`](https://github.com/hiero-ledger/hiero-gradle-conventions/blob/v0.7.11/src/main/kotlin/org.hiero.gradle.feature.benchmark.gradle.kts)).
- **The workflows are named by number:**
  [`007`](../../../../.github/workflows/007-user-benchmark-calibration.yaml) calibrates one benchmark when started by
  hand, [`812`](../../../../.github/workflows/812-call-benchmark-smoke.yaml) is the smoke run, which
  [`800`](../../../../.github/workflows/800-call-mats-tests.yaml) calls among the tests of every pull request (MATS,
  the [Minimal Acceptable Test Suite](../../../../docs/continuous-integration-testing-overview.md#definitions)), and
  [`905`](../../../../.github/workflows/905-cron-nightly-benchmarks.yaml) measures every night. A workflow run shows
  its job summary on its GitHub page, and keeps its files as artifacts to download.
- **A runner** is where GitHub Actions runs a job; a job names its runner by a label. The benchmark jobs run in a
  Kubernetes pod, a container with a reserved share of the CPUs and memory, on one machine, the benchmark machine
  ([operations.md](operations.md#the-benchmark-machine)).
- **The driver,**
  [`run_benchmarks.py`](../../../../.github/workflows/support/scripts/benchmarks/run_benchmarks.py), runs JMH from the
  benchmark jar once per parameter set, waits for a quiet machine, and repeats a parameter set measured while the
  machine was busy. Its docstring lists its options, such as `--manifest` for the tracked benchmarks and `--jar` for
  one benchmark.
- **The results go to Grafana:**
  [`publish_results.py`](../../../../.github/workflows/support/scripts/benchmarks/publish_results.py) pushes the
  metrics to Mimir, a store for time series in Prometheus's format, and one record per result to Loki, a store for log
  lines. The dashboard,
  [`grafana-dashboard.json`](../../../../.github/workflows/support/scripts/benchmarks/grafana-dashboard.json), shows
  both. Every series carries labels, such as the benchmark and the commit, by which a query selects it.
