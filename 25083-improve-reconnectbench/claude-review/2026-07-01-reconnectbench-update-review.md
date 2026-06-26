# ReconnectBench Update — Review Notes

Date: `2026-07-01`
Reviewer: Claude (Opus 4.8)
Scope reviewed:

- Design docs in `25083-improve-reconnectbench/design-and-implementation/` (reviewed first, as instructed).
- Commit `8457507309b469de4015ed6aed0586fe93265552` ("Sync with main") — the ReconnectBench rewrite in
  `platform-sdk/swirlds-benchmarks` (simulated network, paired streams, harness, stats, tests, build).
- The working-tree state on top of that commit, and evidence under `25083-improve-reconnectbench/evidence-and-calibration/`.

Method: manual deep-read of every changed Java file and the design/evidence docs, plus an adversarial
multi-agent verification pass (30 candidate findings → 22 confirmed/plausible, 8 refuted). Refuted items are
listed at the end so the team doesn't re-investigate them. No source or design files were edited in this session.

---

## 0. Design review verdict (did the foundation hold?)

Instruction was: review the design first, and **stop** if there are foundational design problems.

**Verdict: the MVP design is internally coherent and adequate for its stated goal, so I proceeded to the code review.**
The MVP ([mvp-design.md](../design-and-implementation/ReconnectBench-traversal-comparison-mvp-design.md)) deliberately
narrows the [original spec](../design-and-implementation/ReconnectBench-original-design-specification.md): it removes the
broken per-message `Thread.sleep` knobs (correct — the original spec's argument at §"Why the current delay mechanism
doesn't help" is sound), keeps the real synchronizers/traversal orders, and inserts one byte-level network model below
them. That is the right shape for a relative traversal-comparison tool.

There is **one significant design risk** (P1 below) and **two design/scope tensions** (P3) that the team should hold in
mind, but none rise to "the design is wrong, stop and redo it." They are acknowledged limitations with mitigation paths,
and the project's own [bugs-and-improvements inventory](../reconnectbench-bugs-and-improvements.md) already records most
of them. This review verifies them against the code and adds a few new ones.

---

## P1 — High: the in-memory transport can bias the very thing the benchmark measures

**Finding.** The MVP replaces the original spec's *real loopback TCP socket* transport
([original-design-specification.md §1.1](../design-and-implementation/ReconnectBench-original-design-specification.md))
with a fully in-memory `SimulatedNetworkChannel` whose delivery timing is realized by a single `ReentrantLock` +
`Condition` per direction ([SimulatedNetworkChannel.java:56](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java),
`:59`). Every byte handoff goes through `lock` → `stateChanged.awaitNanos(...)`/`await()` → `signalAll()`
([:288](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java),
`:360`, `:367`, `:380`).

Why this matters specifically for *this* benchmark: its entire justification (the original spec's "speculation
amplification" insight) is that traversal ranking is sensitive to **when a response becomes visible relative to when the
next request is sent**, and that sensitivity is on the order of the modeled one-way latency. But condition-variable
wakeup + lock reacquisition on a loaded machine is itself on the order of microseconds — i.e. *comparable to* a
data-center one-way latency (500 µs is 500× that, but the calibration runs also probe the 68–270 µs regime, where it is
not). The two traversal modes have different reader/writer wakeup and in-flight patterns, so the transport's own
scheduling overhead does not necessarily cancel between variants. Net: at low latency the transport can nudge the
ranking that the benchmark exists to produce.

Two code-level facets confirm the mechanism (both individually low severity, grouped here because they are the same root
cause):

- Latency is applied while holding the shared write lock's condition, not as an off-critical-path reader park. The
  original spec §2.4 explicitly argued correctness on the grounds that latency is "a delivery-time shift applied by
  parking the reader thread … not on a pipeline's critical path." The MVP's single-lock realization does not preserve
  that guarantee — the writer's `writeRange` and its capacity wait contend on the same lock the reader parks on.
- `signalAll()` on every accepted range and every read wakes a reader that is parked purely for a *timed* arrival, which
  re-checks and re-waits; this is harmless to correctness but is extra churn precisely on the hot path.

This is **not a stop-ship design flaw** — the MVP is explicitly scoped as a relative tool with calibration caveats — but
it is the single most important validity risk and should gate how much the team trusts low-latency ranking gaps.

**Recommended action (already in the inventory, endorsed):** add a benchmark-only real-loopback-socket transport variant
and A/B it against `SimulatedNetworkChannel` on the same saved state; prefer higher-latency / large-window profiles where
the transport artifact is proportionally small; treat sub-millisecond-latency rankings as suspect until the socket
cross-check exists. Source: [bugs-and-improvements.md §"Critical Calibration Risks" #2](../reconnectbench-bugs-and-improvements.md).

---

## P2 — Medium

### 2.1 Correctness — verification is toothless, so a wrong reconnect can post a green time
`VirtualMapBaseBench.verifyMap(...)` counts bad/missing keys and, on mismatch, only calls
`logger.error("FAIL ...")` then returns normally — it never throws
([VirtualMapBaseBench.java:205](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/VirtualMapBaseBench.java)).
`ReconnectBench.onInvocationTearDown` calls it purely for the side effect
([ReconnectBench.java:162](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java)),
and `benchmark.verifyResult` is frequently disabled during calibration. Combined with `SingleShotTime` and a small
iteration count, a traversal mode that reconstructs the learner map incorrectly still contributes a fast wall time to the
comparison. For a tool whose only output is a relative ranking, an unverified/incorrect run must be *failed data*, not
a data point.
**Fix:** make `verifyMap` return counts (or add a reconnect-specific verifier) and have `ReconnectBench` throw when
`verifyResult=true`; keep correctness spot-checks in the loop until strict verification is cheap enough to leave on.

### 2.2 Reproducibility — the shared `jmhReconnect` task is not portable
Two independent portability breakers were introduced into the shared task in this commit
([build.gradle.kts:78-85](../../platform-sdk/swirlds-benchmarks/build.gradle.kts)):

- An **absolute, user-specific GC-log path**: `-Xlog:gc*:file=/Users/thenswan/Work/LimeChain/.../reconnectbench-gc.log`
  (`:83`). On any other machine or CI runner that directory does not exist; the forked JVM's log target fails to open.
- **`-Xms24g -Xmx24g -XX:+AlwaysPreTouch`** (`:78`) — a 24 GiB heap *floor* (not a ceiling like the sibling `jmh*`
  tasks' `-Xmx16g`) that is committed and pre-touched at startup, so the JVM fails to start on any host with < ~24 GiB.

**Fix:** keep `jmhReconnect` portable/modest by default; move machine-specific heap/GC-log settings behind an opt-in
gradle property or a local-only run script under `25083-improve-reconnectbench`. Source: [inventory §"Move local
calibration settings out of the generic jmhReconnect task"](../reconnectbench-bugs-and-improvements.md).

### 2.3 Reproducibility — gradle defaults silently diverge from the source/design baseline
The `jmhReconnect` task's default JMH params override the source `@Param` defaults, so `./gradlew … jmhReconnect` does
**not** measure the documented 500 µs / 128 KiB data-center baseline unless every property is passed explicitly
([build.gradle.kts:86-108](../../platform-sdk/swirlds-benchmarks/build.gradle.kts) vs
[ReconnectBench.java:53-63](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java)):
`networkLatencyMicroseconds` 75000 vs 500 (150×; 270 is the commented-out alternative), `networkInflightBytesLimit`
16 MiB vs 128 KiB (128×), `networkBandwidthMegabitsPerSecond` 200 vs 1000. A reader comparing results to the design docs
will mis-attribute the profile. **Fix:** align the task defaults with the documented baseline, or log a one-line
"effective profile" banner and keep the divergent values in a named calibration profile.

### 2.4 Correctness — `int` overflow in the verification-array sizing
`teacherData = new long[numRecords * numFiles * 2]` computes the length in `int` arithmetic (both fields are `int`)
([ReconnectBench.java:151](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java)),
even though the same method builds the state at `long` scale: `buildState(random, (long) numRecords * numFiles, …)`
(`:117`). Above `numRecords*numFiles ≈ 1.07e9` (reachable at cluster-scale states) the product wraps to a negative/small
value → `NegativeArraySizeException` or a silently-too-small verify array. **Fix:** cast to `long` and bound-check (and
note the array is 800 MB already at 50 M records — arguably verification at cluster scale needs a different approach).

### 2.5 Correctness/repro — reused learner map is mutated after iteration 1 (bites multi-iteration runs)
`learnerMap` is built once at trial setup and passed unchanged into `hashAndTestSynchronization` on **every** measurement
invocation ([ReconnectBench.java:212](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java)).
Reconnect's `VirtualMapLearner` calls `originalMap.getDataSource().stopAndDisableBackgroundCompaction()` and
`detachAsDataSourceCopy()` on that shared map. So iterations 2…N run against a learner data source whose compaction was
permanently disabled by iteration 1 — a systematic difference between the first and later iterations.
With the committed `@Measurement(iterations = 1)` this is inert, but the **working tree sets `@Measurement(iterations = 10)`**
([ReconnectBench.java:31](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java);
was `1` in the reviewed commit), where it can bias the very means being compared. **Fix:** either keep it to one
measured invocation per fork, or rebuild/restore the learner map per invocation; and don't commit the local
`iterations = 10` as the default.

### 2.6 Evidence — local ranking rests on a state shaped only for one cluster anchor + an unvalidated "trend"
Two related interpretation risks, verified against the notes:

- The fixed local saved state is calibrated to the accepted `pullTopToBottom` cluster artifact (state-gap within
  ~0.43%), but the same state is then used to rank `parallelSync` and `twoPhase`, whose leaf-work deltas are ~-57% to
  -60% versus the cluster anchor — i.e. the state is a poor work-shape match for the non-top-to-bottom modes.
  ([2026-06-26-cluster-evidence-profile-run.md](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-06-26-cluster-evidence-profile-run.md)).
- The [2026-06-30 small-state note](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-06-30-small-state-local-runs.md)
  repeatedly measures runs against "the current high-state trend `pullTopToBottom < pullTwoPhasePessimistic <
  pullParallelSync`" as if that three-mode ordering were established, but **no** source in the evidence set validates it —
  every cluster batch explicitly says "No three-mode traversal ordering should be derived from this batch." To the note's
  credit it is honest that local runs *don't* reproduce it; the risk is treating an unvalidated ordering as ground truth.

Also note the local same-JVM teacher is **idle** (no workload) over the in-memory transport, while cluster runs are
7-node clusters under sustained TPS on real TCP; the local notes rarely surface this as a threat to validity when they
state a ranking. **Fix:** label the reference ordering as hypothesis-not-baseline; add per-mode work-shape validation
(clean/dirty leaf counters) before ranking; state the idle-teacher/in-memory-transport caveat next to each conclusion.

---

## P3 — Low / minor

- **Interrupted reconnect can be recorded as success.** In `testSynchronization`, an `InterruptedException` from
  `waitForTermination()` is caught, `workGroup.shutdown()` (graceful, does not interrupt workers) is called, the flag is
  re-set, and control **falls through** to the normal return; the synchronizer threads also swallow `InterruptedException`
  by merely re-interrupting ([MerkleBenchmarkUtils.java:83-121](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java)).
  If `hasExceptions()` is false at that moment, a partial/empty map is returned as a valid timed run. Abort the learner
  and throw on interruption instead.
- **Diagnostics are dropped on the failure path.** When `hasExceptions()` is true, `testSynchronization` throws before
  constructing `ReconnectBenchmarkResult`, so the network + reconnect stats are never captured
  ([MerkleBenchmarkUtils.java:90-99](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java));
  `ReconnectBench` only logs them after a successful return. Failures are exactly when byte/in-flight counters are most
  useful — snapshot and log them in a `finally`/before the throw.
- **`StateBuilder` change is a no-op with a leftover FIXME.** `storageOptimizer.accept(i)` → `accept(i + size)` with a
  committed `// FIXME: redundant copies of learner map`
  ([StateBuilder.java:121-122](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/StateBuilder.java)).
  Because `size = (long)numRecords*numFiles` is an exact multiple of `numRecords`, `(i+size) % numRecords == i % numRecords`,
  so the copy-trigger cadence is unchanged; only the logged index shifts. Either remove the change/comment or actually
  address the redundant learner-map copy the FIXME describes. (Debugging artifact — should not have been committed.)
- **Diagnostic wait counters conflate scheduling overhead with modeled delay.** `capacityWaitNanos`,
  `emptyReadWaitNanos`, `arrivalWaitNanos` sum wall-clock `await` intervals (incl. wakeup latency, lock reacquisition,
  spurious wakeups), and the reader's loop splits one modeled delay across many `awaitNanos` calls
  ([SimulatedNetworkChannel.java:408,421-431](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java)).
  Impact is limited to *log lines* (these never feed JMH timing), but label them "observed blocking time," and note the
  `arrivalWaitCount` over-counts because `signalAll` wakes timed-arrival waiters early.
- **Symmetric fixed in-flight cap vs. asymmetric cluster evidence.** Both directions use the same
  `NetworkSimulationConfig` ([PairedStreams.java:40-41](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/PairedStreams.java)),
  and in-flight is modeled as an app-read cap, while the project's own cluster evidence shows directional throughput
  asymmetry (learner→teacher ~233–270 Mbps vs teacher→learner ~108–192 Mbps). Fine for the MVP; add per-direction
  bandwidth/cap if cluster asymmetry keeps mattering. Source: [inventory §"Critical Risks" #3](../reconnectbench-bugs-and-improvements.md).
- **MVP-omits-workload vs. the causal chain it inherits.** The original spec makes teacher workload a first-class driver
  of the same speculation dynamic; the MVP makes it a non-goal and runs an idle teacher. Legitimate scoping, but worth
  stating explicitly wherever a result is claimed to "transfer" to a loaded cluster teacher.

## Nitpicks

- `teacherMapCopy = teacherMap.copy()` is only used to freeze `teacherMap`; the "keep the copy as the mutable head"
  comment is misleading — the copy is never mutated/hashed/used and just holds a live data source for the trial
  ([ReconnectBench.java:141](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java)).
  Rename/clarify or drop it once workload lands.
- `PairedStreams` javadoc ("for synchronization tests") and its `close()` comment ("this is the test code") are stale —
  it is now benchmark infrastructure, not test code.
- Several local comparisons state a direction where the mean gap is inside the reported error band (e.g. 10M/270µs
  top-to-bottom vs parallel: 0.823 s/op gap vs ±5.6 / ±4.7 s/op). The notes mostly flag this themselves; keep directional
  language out of within-error results. This is the practical face of a broader **signal-to-noise** concern: at the
  current default state sizes and iteration counts the three modes land within ±10–30% error — larger than the ~20%
  effect the benchmark is meant to resolve. Calibrating state size / iteration count up until the effect clears the noise
  is arguably the highest-value calibration task remaining.

---

## Verified NOT issues (refuted during the pass — don't re-investigate)

- Simulator timing math is sound: `readableAtNanos` vs `availableBytes` offset handling is self-consistent; the
  `Math.max(1, …)` floor in `availableBytes` does **not** bypass bandwidth (per-range serialization via
  `nextTransmissionAvailableAtNanos` holds); `transmitDurationNanos`'s `double` math is exact for ranges ≤ 8192 B.
- `LOOPBACK` **is** a genuine fast path per the design's narrow definition (no latency/bandwidth/in-flight waits) — the
  resolve() branch zeroes latency, sets unlimited bandwidth, and disables the cap.
- Reconnect metrics do **not** accumulate across iterations: `ReconnectMapMetrics` are `LongGauge`s reset to 0 in its
  constructor, and a fresh `LearningSynchronizer` (hence fresh reset) is built per invocation.
- Gradle `@Param` sweeps are still possible via comma-separated `-P` values; the single-element `listProperty` wrapping
  is not a blocker. The shared `results-reconnect.txt` path is a `.convention()` default (overridable), not a forced
  collision.
- Two-phase's rejected cluster run is **not** improperly carried into the reference trend — the batch summaries keep it
  diagnostic-only as intended.

---

## Suggested priority order for fixes

1. **P2.1 + P3 (verification + interrupt handling):** make incorrect/aborted reconnects fail the run. Cheap, high value —
   protects every downstream comparison. (`verifyMap` throw; interrupt → abort+throw; preserve diagnostics on failure.)
2. **P2.2 + P2.3 (build task portability & default divergence):** unbreak `jmhReconnect` for anyone but the author and
   stop the silent profile substitution. Cheap.
3. **P2.4 + P2.5 (int overflow + reused learner map):** small correctness fixes; do 2.5 before trusting any
   `iterations > 1` run.
4. **P1 (transport A/B) + P2.6 (evidence framing):** the real research work — stand up a socket-transport control and
   tighten how local rankings are stated relative to cluster anchors.
5. **P3 cleanups / nitpicks:** fold in opportunistically (StateBuilder FIXME, counter labels, stale comments).
