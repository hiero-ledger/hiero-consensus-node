# Steps 1–3: Fix, Latency, Profile

Rules: [writing.md](/platform-sdk/docs/consensus-layer/benchmarks/writing.md). The example is `ConsensusImplBenchmark`
in `consensus-hashgraph-impl`. The commands run from the repository root, with these variables:

```bash
MODULE=consensus-hashgraph-impl
BENCH=ConsensusImplBenchmark
PROFILER=org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler
OUT=build/benchmark-work       # any scratch directory outside the source tree
PARAMS="-p numNodes=4"         # one parameter set of the benchmark
./gradlew ":${MODULE}:jmhJarWithMergedServiceFiles"
JAR=$(ls platform-sdk/${MODULE}/build/libs/${MODULE}-*-jmh-merged.jar)
```

Write `${MODULE}` with braces: in zsh, `$MODULE:s` is a substitution modifier. Rebuild the jar after every change. Run
the jar directly, not Gradle's `jmh` task: only the jar takes `-foe true`
([failing on errors](/platform-sdk/docs/consensus-layer/benchmarks/operations.md#failing-on-errors)). Each run below
takes about a minute for the example's two parameter sets; `$PARAMS` runs one.

## Before step 1

- **Create the work package's worktree** from `origin/main`, with a branch named after the issue:
  `git worktree add --no-track -b <issue>-<slug> <path> origin/main`. All work happens there; the developer commits
  and pushes it.
- **Read the benchmark and the code under test.** For a benchmark that is replaced, read the old one for what it got
  wrong, then write the new one from the guide.
- **Resolve every "thing to check" of the issue:** confirmed, refuted, or harmless, each with the line of code or the
  run that shows it. They come from a first read of the code; several may be wrong.
- **Check the module's setup** ([module setup](/platform-sdk/docs/consensus-layer/benchmarks/writing.md#module-setup)):
  the jmh module info requires the module under test and `org.hiero.consensus.benchmark.tools`.

## Step 1: fix

Apply [inputs and the measured region](/platform-sdk/docs/consensus-layer/benchmarks/writing.md#inputs-and-the-measured-region):
seeded inputs, generated in `@Setup(Level.Trial)`; fresh copies per pass in `@Setup(Level.Invocation)` if the code
under test changes its input, ending with `System.gc()`; an exact output check in `@TearDown(Level.Invocation)`.
Remove embedded results and comments about removed parameters.

**Check:** two forks, each running the trial setup and several passes, all pass the output check.

```bash
java -jar "$JAR" "$BENCH" -foe true -prof "$PROFILER" -f 2 -wi 0 -i 2 -r 2s -rf json -rff "$OUT/step1.json"
echo "exit $?"     # 0; a failed output check throws, and -foe true turns it into exit 1
```

The expected value is a constant in the benchmark, so a seed that does not make the input the same in every fork
fails here.

## Step 2: latency

Apply [latency](/platform-sdk/docs/consensus-layer/benchmarks/writing.md#latency) and
[allocation](/platform-sdk/docs/consensus-layer/benchmarks/writing.md#allocation-and-garbage-collection):

- **A single call:** obtain the recorder with `LatencyProfiler.recorder()` in the per-invocation setup, and follow the
  pattern in the Javadoc of `LatencyRecorder`: `startMeasurement()`, `lap()` after each call, `stopMeasurement()`.
  `@BenchmarkMode(Mode.AverageTime)` with `@OperationsPerInvocation` set to the calls per pass.
- **A pipeline,** where items pass between threads: the tooling does not exist yet. `LatencyProfiler` reports
  `LatencyRecorder`s, which time calls on one thread. Agree on the design with the developer, and build it in
  `consensus-benchmark-tools` first, in its own pull request
  ([a pipeline](/platform-sdk/docs/consensus-layer/benchmarks/writing.md#a-pipeline)).
- The heap goes into `@Fork(jvmArgsAppend = ...)`, never into `jvmArgsPrepend`
  ([JVM arguments](/platform-sdk/docs/consensus-layer/benchmarks/writing.md#jvm-arguments)).

**Check:** the results of step 1's run hold every metric, and a run without the profiler fails.

```bash
jq -r '.[] | "\(.params) " + (.secondaryMetrics | to_entries | map("\(.key)=\(.value.score)") | join(" "))' \
  "$OUT/step1.json"
java -jar "$JAR" "$BENCH" -foe true -f 1 -wi 0 -i 1 -r 100ms $PARAMS > "$OUT/no-profiler.txt"
echo "exit $?"     # 1
```

- Every parameter set has `latency.mean`, `latency.p50`, `latency.p90`, `latency.p99`, `latency.p99.9`,
  `latency.p99.99`, `latency.max`, `latency.min`, `latency.samples`, `alloc.norm`, and `measured.gc.discardedPasses`.
- `measured.gc.discardedPasses` is 0. If it is not, the heap is too small
  ([heap](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md#heap)).
- `alloc.norm` matches what the production code should allocate per call.
- `latency.samples` divided by the iterations shows how long an iteration must be for p99.99, about 10⁶ calls
  ([reporting](/platform-sdk/docs/consensus-layer/benchmarks/writing.md#reporting)); step 6 sets it.

## Step 3: profile

Apply [profiling](/platform-sdk/docs/consensus-layer/benchmarks/writing.md#profiling). async-profiler is not part of
the repository: download a release from https://github.com/async-profiler/async-profiler and set `AP` to its
directory. The library is `lib/libasyncProfiler.dylib` on macOS and `lib/libasyncProfiler.so` on Linux.
`-jvmArgsAppend` replaces the `@Fork`'s, so repeat its heap.

```bash
java -jar "$JAR" "$BENCH" -foe true -prof "$PROFILER" \
  -prof "async:libPath=$AP/lib/libasyncProfiler.dylib;output=collapsed;dir=$OUT/profile;event=cpu;threads=true" \
  -jvmArgsAppend "-Xms2g -Xmx2g -XX:+AlwaysPreTouch -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints" \
  -f 1 -wi 2 -i 3 -r 5s
for csv in "$OUT"/profile/*/collapsed-cpu.csv; do
  echo "== $csv"
  platform-sdk/consensus-benchmark-tools/scripts/profile-regions.sh "$csv" \
    ConsensusImplBenchmark.calculateConsensus ConsensusImpl.addEvent prepareInvocation verifyInvocation
done
```

The script's arguments are the benchmark method, the entry point of the code under test, and the benchmark's setup
and teardown methods.

**Check,** for every parameter set:

- the code under test takes nearly all of the benchmark method's samples (the example on a laptop: 99.3% and 99.5%
  for 4 and 10 nodes);
- the timing, `LatencyRecorder.lap`, and the benchmark's other frames take under 1% (the example: 0.8% and 0.5%, from
  about 1,000 samples each, so about 0.1% per sample);
- the hot frames of the code under test are the methods the benchmark is meant to measure; "waiting" is 0% and no
  thread under "other" does the work instead;
- clock reads, logging, or locks inside the code under test are production cost, measured rightly; tell the developer.

If `alloc.norm` takes a few distinct values that differ between forks, the forks run differently compiled code:
[fork modes](/platform-sdk/docs/consensus-layer/benchmarks/calibration.md#fork-modes). Tell the developer and go on:
calibration (step 6) measures how much the modes differ in latency.
