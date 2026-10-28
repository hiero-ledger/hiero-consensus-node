# Steps 4–6: Tracking, Smoke Run, Calibration

Rules: [operations.md](/platform-sdk/docs/consensus-layer/benchmarks/operations.md), sections "Tracking a benchmark",
"The smoke run", "The nightly run", and "The benchmark machine", and
[calibration.md](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md). Variables as in
[steps-1-3.md](steps-1-3.md). The scripts need Python 3.10 or newer (macOS ships 3.9) and `JAVA_HOME` set to the
build's JDK.

## Step 4: track

Apply [tracking a benchmark](/platform-sdk/docs/consensus-layer/benchmarks/operations.md#tracking-a-benchmark). In the
module's `build.gradle.kts`, as in `consensus-hashgraph-impl`:

```kotlin
plugins {
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.benchmark-tracking")
}

jmh {
    // the benchmarks that are tracked; -PjmhTests=<pattern> runs others instead
    includes.convention(listOf("ConsensusImplBenchmark"))
}
```

- A module that already applies the plugin only adds the benchmark to the list.
- The smoke run runs every benchmark of the module, tracked or not. If another benchmark of the module fails or hangs
  in it, tell the developer before applying the plugin.
- A benchmark with an expensive setup, or one that needs more than the plugin's default smoke heap, sets
  `benchmarkTracking { smokeJvmArgs = "..."; smokeArgs = listOf("-p", "<one representative parameter set>") }`.

**Check:** the manifest names the benchmark, and the nightly driver runs every parameter set from it.

```bash
./gradlew ":${MODULE}:jmhTracked"
cat "platform-sdk/${MODULE}/build/jmh/tracked.properties"      # includes=... names the benchmark
python3 .github/workflows/support/scripts/benchmarks/run_benchmarks.py \
  --manifest "platform-sdk/${MODULE}/build/jmh/tracked.properties" --out "$OUT/driver" -- -f 1 -wi 0 -i 1 -r 1s
jq -r '.attempts[] | "\(.benchmark) \(.params) exit=\(.exit_code)"' "$OUT/driver/runs.json"
```

Every parameter set has an attempt with exit code 0, and the forks' `jvmArgs` in its JSON start with
`-XX:ActiveProcessorCount=12`, followed by the benchmark's heap. Outside Linux the driver has no load monitor: it waits
about 20 seconds per parameter set and reports the slot as unknown, which is expected locally.

## Step 5: smoke run

Apply [the smoke run](/platform-sdk/docs/consensus-layer/benchmarks/operations.md#the-smoke-run).

**Check:** it passes, a rerun is up to date, and without its output it comes from the build cache. `--rerun` makes the
first run execute even when the cache already holds a result for the same jar.

```bash
./gradlew ":${MODULE}:jmhSmoke" --rerun --console=plain | grep -E "jmhSmoke|BUILD"   # executes, BUILD SUCCESSFUL
./gradlew ":${MODULE}:jmhSmoke" --console=plain | grep jmhSmoke                     # UP-TO-DATE
rm -rf "platform-sdk/${MODULE}/build/results/jmh-smoke"
./gradlew ":${MODULE}:jmhSmoke" --console=plain | grep jmhSmoke                     # FROM-CACHE
jq -r '.[] | "\(.benchmark) \(.params) " + (.secondaryMetrics | keys | join(","))' \
  "platform-sdk/${MODULE}/build/results/jmh-smoke/results.json"
```

The results hold the benchmark's metrics for every parameter set it ran with. A smoke run checks only what the output
check of step 1 checks.

## Step 6: calibrate

Apply [the calibration run](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md#the-calibration-run). The
workflow checks out `ref` and takes its scripts from there too. So the developer pushes the work package's branch
first (created [before step 1](steps-1-3.md#before-step-1)); no pull request yet.

The benchmark machine is one shared machine, `hl-cn-benchmark-lin`. A run can wait in GitHub's queue for hours, and
the nightly job runs there from 17:00 UTC. Ask the developer before starting a run.

```bash
BRANCH=<the pushed branch>
gh workflow run 007-user-benchmark-calibration.yaml --ref main \
  -f module="$MODULE" -f benchmark="$BENCH" -f ref="$BRANCH" -f jmh-args="-f 10 -wi 0 -i 20 -r 5s"
gh run list --workflow 007-user-benchmark-calibration.yaml --limit 4     # the runs' ids
gh run download <run id> -n benchmark-calibration -D "$OUT/calibration/<run id>"   # once the run is done
```

- **`--ref main` selects the workflow's definition; the `ref` input selects the code** that the run builds and
  measures. GitHub therefore lists the run under `main`; the measured commit is in its `run-info.properties`.
- **The default arguments** take about 40 minutes for the example's two parameter sets, after the queue. The other
  inputs (`profilers`, `max-load`, `attempts`, `deadline-minutes`) rarely change.
- **Repeat with a new workflow run,** never `gh run rerun`. Analyze several runs together; the pilot used three to four
  quiet runs. Dispatch them at once: the workflow queues them and runs them one after another, behind any other
  benchmark's calibration. The noise between runs comes from the nightly history later, not from calibration
  ([the noise between runs](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md#the-noise-between-runs)).
- **Keep the downloaded runs:** the artifacts expire after 90 days, and the block links them.

**Analyze** all runs together
([the analysis](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md#the-analysis)):

```bash
python3 .github/workflows/support/scripts/benchmarks/analyze_calibration.py "$OUT"/calibration/*
```

The output has, per parameter set, the warmup, the noise of iterations, forks, and runs, the alloc.norm modes, and a
recommendation; then one configuration for all parameter sets with its runtime and the calibration block.

- **Choose the configuration** with the developer
  ([choosing the configuration](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md#choosing-the-configuration)):
  the cheapest one whose predicted noise is good enough. Justify every fork, iteration, or second beyond it with the
  predicted noise. The nightly time agreed for the issue is an orientation, not a budget to fill. `--forks`,
  `--iterations`, `--length`, `--warmup-iterations`, and `--warmup-length` print the noise and runtime of any
  configuration.
- **Fork modes** (more than one alloc.norm mode): the table's "mode effect" is their difference in latency. Tell the
  developer. Find their cause ([fork modes](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md#fork-modes);
  `fork-modes.sh` records the allocation profiles and compile logs) only if the effect is comparable to the expected
  detectable regression.
- **Discarded passes:** raise the heap and calibrate again
  ([heap](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md#heap)).

**Record** the choice ([the calibration record](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md#the-calibration-record)):
print the block for it and paste it unchanged into the class Javadoc; set the annotations to the same values. A warmup
iteration shorter than the measurement iterations takes `--warmup-length`.

```bash
python3 .github/workflows/support/scripts/benchmarks/analyze_calibration.py \
  --forks 5 --iterations 2 --length 15 --warmup-iterations 1 "$OUT"/calibration/*
```

```java
@Fork(value = 5, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 15)
@Measurement(iterations = 2, time = 15)
```

**Check:** the annotations and the block equal the script's output for the chosen configuration, and the smoke run
passes again ([step 5](#step-5-smoke-run)).
