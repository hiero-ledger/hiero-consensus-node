---
type: benchmark-guide
title: Operating benchmarks
last_reviewed: TBD
---

# Operating Benchmarks

How a finished benchmark is tracked: a short smoke run in the CI of every pull request, a measurement every night on
the benchmark machine, the results in Grafana, and an alert.

## Tracking a benchmark

- **A module opts in** by applying
  [`org.hiero.gradle.feature.benchmark-tracking`](../../../../gradle/benchmark-tracking/src/main/kotlin/org.hiero.gradle.feature.benchmark-tracking.gradle.kts)
  after `org.hiero.gradle.feature.benchmark`. It names its tracked benchmarks with
  `jmh { includes.convention(listOf(...)) }`, for example in
  [`consensus-hashgraph-impl/build.gradle.kts`](../../../consensus-hashgraph-impl/build.gradle.kts). The plugin's
  header comment lists what it configures.
- **Apply the plugin only when all benchmarks of the module are finished.** The smoke run runs every benchmark of the
  module, and an unfinished one can make it fail or hang.
- **Name the tracked benchmarks with `includes.convention(...)`, not `includes.add(...)`.** The convention plugin adds
  `-PjmhTests=<pattern>` with `includes.add(...)`. With `convention`, the pattern replaces the module's list; with
  `add`, both would run.
- **Commands,** from the repository root:
  - `./gradlew :<module>:jmh` runs the tracked benchmarks with JSON output and the profilers; `-PjmhTests=<pattern>`
    runs others instead. Name the module: without a module path, `-PjmhTests` fails in every module that has no
    matching benchmark.
  - `./gradlew :<module>:jmhSmoke` is the smoke run.
  - `./gradlew jmhTracked` builds the jars and writes the manifests that the nightly run reads.

## The smoke run

Compiling the jmh source sets catches compile errors only. It does not catch hangs, exceptions during setup, problems
in packaging the benchmark jar, such as missing service registrations, or a benchmark whose output no longer passes its
check.

- **`jmhSmoke` runs every benchmark of the module once, briefly, from the packaged jar:** one fork, no warmup, one
  short iteration. Running the jar catches packaging problems too.
- **One heap for all benchmarks.** `benchmarkTracking { smokeJvmArgs }` replaces every benchmark's `jvmArgsAppend`, so
  one value limits the memory of all benchmarks in the jar.
- **One parameter set for an expensive setup.** `benchmarkTracking { smokeArgs }` passes, for example, `-p` with one
  representative set. The setup still runs in full, which is what the smoke run checks.
- **The result is cached by the jar's content.** The jar contains a `git.properties` with the commit id, which the
  plugin tells Gradle to ignore when it compares the classpath. A pull request that leaves the jar unchanged takes the
  result from the build cache.
- **In CI,** [`812-call-benchmark-smoke.yaml`](../../../../.github/workflows/812-call-benchmark-smoke.yaml) runs
  `./gradlew jmhSmoke` without a module path, so a module joins without a workflow change.
  [`800-call-mats-tests.yaml`](../../../../.github/workflows/800-call-mats-tests.yaml) calls it as the job "Benchmark
  Smoke": in pull requests with the other tests, and on every push to `main`, which fills the cache for the pull
  requests.
- **The job's `timeout-minutes` turns a hang into a failure.** On a task timeout or a cancelled build, Gradle kills the
  process of a `JavaExec` task with all its child processes, JMH's forks included. If only the JMH process were
  killed, a hung fork would keep running: a fork notices that JMH is gone only when it next reports to it.
- **Missing latency results never fail a smoke run.** It has a single pass, and a garbage collection can discard it.
- **A smoke run checks only what the benchmark's output check checks.** A benchmark that measures code that does
  nothing still passes.

## Failing on errors

- **Every JMH run passes `-foe true` (fail on error).** Without it, JMH logs an exception in a benchmark but still
  exits with 0, so the CI check passes.
- **Only `-foe true` works.** JMH parses the value with `Boolean.valueOf`, so `-foe 1` means false.
  - The JMH Gradle plugin passes `failOnError = true` as `-foe 1`
    ([melix/jmh-gradle-plugin#255](https://github.com/melix/jmh-gradle-plugin/issues/255)). The `jmh` task's
    `failOnError` therefore has no effect until a fixed plugin is released, and no build script can pass `-foe true`
    to it.
  - `jmhSmoke` and the nightly driver run the jar themselves and pass `-foe true`.
- **Prove that an option catches errors with a run that must fail.** A benchmark that records latency throws when run
  without `LatencyProfiler` ([writing.md](writing.md#custom-profilers)); run one without it and check the exit code
  with each value of the option. `--info` shows the arguments that a Gradle task passes.

## The nightly run

[`905-cron-nightly-benchmarks.yaml`](../../../../.github/workflows/905-cron-nightly-benchmarks.yaml) measures every
tracked benchmark on the benchmark machine and publishes the results.

1. `./gradlew jmhTracked` builds the benchmark jar of every module that applies the plugin and writes its manifest,
   `build/jmh/tracked.properties`. Then the job stops the Gradle daemon.
2. [`record-benchmark-environment.sh`](../../../../.github/workflows/support/scripts/record-benchmark-environment.sh)
   records the machine before and after the measurement.
3. The driver [`run_benchmarks.py`](../../../../.github/workflows/support/scripts/benchmarks/run_benchmarks.py) runs
   the benchmarks of every manifest.
4. [`publish_results.py`](../../../../.github/workflows/support/scripts/benchmarks/publish_results.py) publishes one
   result per parameter set.
5. The job uploads everything as the artifact `jmh-results`.

- **The build finds the benchmarks, and finding none is an error.** The workflow names no module or benchmark. It
  looks for the manifests and fails if there are none, instead of reporting a night without results.
- **The benchmarks run in the order they are found:** modules alphabetically, then each module's includes in order.
  The order matters only when the job runs out of time.
- **Benchmarks never run at the same time as each other or as the build.** The jars are built in a separate Gradle
  run, and the driver runs one JMH process at a time. The repository lets Gradle run tasks in parallel, so a Gradle run
  that measures could compile another module at the same time.
- **One JMH run per parameter set,** so that a parameter set measured on a busy machine can be repeated alone.
- **The driver runs the jar, not Gradle's `jmh` task.** Both give the same results within the noise, but only with the
  jar can the driver pass `-foe true` and repeat a single parameter set.
- **Set the time limit from measured times.** The job summary shows the minutes per attempt and in total, and
  `publish_results.py` publishes `jmh_run_seconds`. The job's `timeout-minutes` covers the run times of all benchmarks
  with their repeats, the driver's deadline, and the build.
- **Code that reads the JSON must accept `"NaN"`.** JMH writes `scoreError` as the string `"NaN"` when there are too
  few iterations for an error margin, as in a smoke run.

## The benchmark machine

- **One shared machine, the runner label `hl-cn-benchmark-lin`.** It is not dedicated to the benchmarks: other
  workloads can run on it, and a job can wait in GitHub's queue for hours. `timeout-minutes` does not count the queue;
  GitHub fails a job that waits 24 hours for a runner.
- **Kubernetes' "Guaranteed" class reserves CPU time, not cores.** A pod whose CPU requests equal its limits gets its
  share of CPU time without being slowed down, but it can still run on any CPU of the machine. Other pods then run on
  the second hardware thread (SMT) of its cores and share its L3 cache. `cpuset.cpus.effective` shows which CPUs a pod
  may use; cores of its own need Kubernetes' static CPU manager policy.
- **Cores of its own are not enough on a busy machine.** Other work on the second hardware thread of the benchmark's
  core or on its L3 cache slows the benchmark most, but work anywhere on the machine still slows it, probably through
  the shared memory. A benchmark that follows references through more data than the L3 cache holds needs a quiet
  machine.
- **The CPU's clock speed did not affect the results;** fixing the CPU frequency is not needed.

### Quiet slots

- **A result counts only if the machine was quiet while it was measured:** other workloads used fewer than
  `--max-load` CPUs on average while the parameter set was measured. The same check applies in the nightly run and in
  calibration; alert thresholds from quiet runs would raise false alerts on results from a busy machine.
  - Check the load during the measurement, not before it: it changes within minutes.
  - Repeat only the parameter set that was measured on a busy machine.
  - Do not correct results for the load with a formula. The effect of the load differs by parameter set and would
    differ by benchmark.
- **The limit rests on the measured effect of the load on**
  [`ConsensusImplBenchmark`](../../../consensus-hashgraph-impl/src/jmh/java/org/hiero/consensus/hashgraph/ConsensusImplBenchmark.java),
  the benchmark of the consensus algorithm. Its latency rose about 0.9% per CPU used by other workloads, consistently
  across runs (correlation 0.98). A limit of 0.5 CPU therefore makes its quiet results at most about 0.45% slower,
  less than the smallest regression it can detect.
- **The driver waits for the machine's actual use to drop, not for free reservations.** Before every JMH run, it waits
  for a quiet minute, as long as it takes, up to its deadline. After the deadline, it runs every parameter set still
  missing once without waiting, so that every set has at least its allocation measured. A quiet minute late in the
  night is as good as an early one.
- **A container can measure the load of other workloads.** `/proc/stat`, `/proc/loadavg`, the per-CPU counters, and
  `/proc/sys/kernel/random/boot_id` show the whole machine, not only the container. The machine's CPU time minus the
  container's own (`usage_usec` of its cgroup) is the load from other workloads, and the boot id identifies the
  machine. `record-benchmark-environment.sh --monitor` records both during the run.
- **`cpu MHz` in `/proc/cpuinfo` is wrong for a CPU that was briefly idle,** for example during a garbage collection:
  the kernel then reports the idle frequency. Keep only readings near the run's maximum, or read the value several
  times per sample.

### The processor count

- **The driver fixes the number of processors that the forked JVMs see** (`--active-processors`, passed as
  `-XX:ActiveProcessorCount` with `-jvmArgsPrepend`). Otherwise the JVMs size their garbage collector and compiler
  threads by the size of the runner's pod.
- **`-XX:ActiveProcessorCount` changes only the JVM's own threads; no thread pool of the consensus layer depends on
  it.** The wiring framework's default pool has `defaultPoolMultiplier` × processors + `defaultPoolConstant` threads
  ([`WiringConfig`](../../../consensus-wiring-framework/src/main/java/org/hiero/consensus/wiring/framework/WiringConfig.java)),
  which is 8 with the defaults on any machine. The benchmarks set the size of their pools themselves.
- **The number is 12.** It is enough for the most parallel benchmarks expected, event intake and the pipeline of a
  single node, with about 12 busy threads. On JDK 25 the JVM then starts 4 C2 compiler threads, as with 8 to 12
  processors; with 16 processors it starts 12, with 44 it starts 15. The timing of the compiler threads can decide a
  fork's mode ([calibration.md](calibration.md#fork-modes)).
- **A benchmark that needs more processors says so.** The number then changes for all benchmarks, which is a baseline
  break.

### Baseline breaks

A baseline break is a change after which a benchmark's results are no longer comparable with its earlier ones: the
history before it does not predict the values after it. Causes:

- a change of the fixed processor count, of the runner pod's size, or of the machine;
- a new JDK;
- a change to the benchmark or to shared test fixtures that changes the benchmark's work. The benchmark's output check
  makes it visible ([writing.md](writing.md#inputs-and-the-measured-region)).

`record-benchmark-environment.sh` records the machine, the pod's limits, and the processor count the JVM sees with every
run.

## Publishing and the dashboard

- **`publish_results.py` publishes one result per parameter set:** the attempt measured on a quiet machine, or else the
  attempt with the least load. Its docstring lists the metrics and the labels; every time series carries the commit,
  the JDK, the runner, and the node (the machine).
- **Latency only from quiet results; everything else always.** A latency measured on a busy machine says nothing about
  the code. Marking it with a label instead would require every query to filter on that label, which is easy to forget.
  Allocation and discarded passes do not depend on the load, and the load and the attempts explain it.
- **A night without a quiet result is a gap in the latency panels.** The line panels connect points across gaps
  shorter than 36 hours only, so the gap stays visible.
- **The commit comes from CI, not from the jar.** The task that builds the jar ignores `git.properties` when it checks
  whether its inputs changed. It does not rebuild the jar when only the commit changes, so the commit inside a jar can
  be old.
- **[`grafana-dashboard.json`](../../../../.github/workflows/support/scripts/benchmarks/grafana-dashboard.json) takes
  the choices in its benchmark and parameter drop-downs from the data,** so a new benchmark needs no change to it. Its data sources are
  variables, and it defines no folder; you choose both when you import it.
- **Loki rejects queries longer than 30 days by default** (`max_query_length`). A logs panel on a dashboard with a
  longer time range needs its own 30-day range.
- **Publishing needs no client library.** Prometheus remote write 1.0 is a small protobuf message, its snappy
  compression accepts the data uncompressed in a single block, and Loki takes JSON; `publish_results.py` encodes them
  itself. `--dry-run` prints the samples instead of pushing them.

## Alerts

**Not set up yet.** The alert rule follows once the nightly history is long enough. These principles hold for it:

- **Every regression is a mistake, so only the noise sets the threshold.** The threshold is the smallest change that
  stands out from the differences between the benchmark's nightly runs without frequent false alerts.
- **The noise comes from the nightly history on the benchmark machine,** not from calibration
  ([calibration.md](calibration.md#the-noise-between-runs)). Each benchmark gets its own threshold.
- **Plan the rate of false alerts for all benchmarks together.** Many series that each raise a false alert rarely
  still add up to frequent false alerts.
- **Requiring several nights to agree lowers the noise** and allows a tighter threshold, but detects a regression a few
  nights later. Options: a Grafana alert on "N nights in a row more than k standard deviations off", or change-point
  detection in the nightly job, which finds the night where a series shifts (for example
  [Apache Otava](https://otava.apache.org/)) and publishes a metric that a plain Grafana threshold watches.
- **Alerts start with the values that vary least:** `latency.mean`, p50, and p90. p99.99 and max stay on the dashboard
  only, until the history shows them stable.
- **`alloc.norm` hardly varies,** and a rise in it is an early sign that garbage collection will make the slow calls
  slower.
- **If a threshold is too loose, reduce the noise:** more forks, longer measurement, a quieter machine, or a job that
  measures the previous and the current commit alternately.
- **A baseline break restarts the history** ([above](#baseline-breaks)).

## Changing the build logic

The tracking plugin follows these points; keep them when changing it.

- **Check what the JMH plugin already does before writing a task.** Its `jmh` task runs the benchmark jar with the
  module's Java toolchain, takes `resultFormat`, `profilers`, `failOnError`, and `includes`, and runs only one `jmh`
  task at a time in a build.
- **The `jmh` task cannot be the smoke run.** The convention plugin marks it as never up to date, so it always runs.
  It also adds the module's jmh runtime classpath to the jar's. That does not change what a benchmark runs, because the
  jar holds identical copies of those classes.
- **A smoke run needs no task type of its own.** A `JavaExec` with the jar as its classpath and `org.openjdk.jmh.Main`
  as its main class is cacheable with `outputs.cacheIf { true }` and uses the module's toolchain.
- **Run JMH with `JavaExec`, not with a custom task.** On a timeout, Gradle stops a `JavaExec` with all its child
  processes. A custom task whose thread waits for the process output ignores the interrupt, and the forked JVM keeps
  running.
- **Keep absolute paths out of a cacheable task's arguments.** They differ between checkouts and prevent cache hits.
  Set the working directory to the result directory and pass a relative file name to `-rff`; Gradle does not count the
  working directory as an input.
- **JMH takes a lock for the whole machine in `java.io.tmpdir`.** A second JMH process with the same temporary
  directory fails at once. Every task that runs JMH uses the JMH plugin's build service,
  `ConcurrentExecutionControlBuildService`, which makes it wait for the `jmh` tasks and limits the memory of benchmark
  JVMs that run in parallel.
- **In a precompiled script plugin, lambdas must use local variables, not the script's properties.** A lambda that
  reads a property of the script breaks the configuration cache.

## Changing the workflows

- **Test a workflow on a throwaway branch before review.** A temporary `push` trigger runs the real job against the
  real endpoints; it publishes with `run_type=test`. Delete the branch afterwards. A manual start (`workflow_dispatch`)
  works only for workflows on the default branch.
  - The temporary trigger can make other workflows' test reports appear under it in the GitHub UI: GitHub shows reports
    created through the Checks API under the commit's earliest check suite.
- **Nothing checks a workflow before it runs.** There is no actionlint, locally or in CI. Parse the YAML locally, and
  prove the workflow with a run.
- **`runs-on` cannot read `env`.** It accepts the `matrix`, `vars`, `inputs`, and `needs` contexts only. A one-entry
  matrix defines the runner label once, for `runs-on` and for the results' runner label.
- **Do not use `setup-python` on the `hl-cn` runners.** It can pick a cached Python that needs a newer glibc than the
  runner image has. Install `python3` with `apt-get`, keep the scripts on its version (3.10 on Ubuntu 22.04), and test
  them with it; macOS ships 3.9.
