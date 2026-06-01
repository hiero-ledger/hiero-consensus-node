# ReconnectBench Critical Bugs And Improvements

Date: `2026-05-28`

Purpose: filtered findings from the code-only re-review of why local ReconnectBench traversal ordering may disagree with
cluster behavior. This document intentionally keeps only issues that can plausibly invalidate the current comparison or
flip `pullTopToBottom` versus `pullParallelSync`.

Scope: benchmark code, benchmark tests/support code, benchmark documentation, and task-local docs only. Do not change
production/runtime consensus-node behavior for these items without explicit approval.

## Current Conclusion

The strongest current explanation is not the hard-coded `SimulatedNetworkChannel` constants by themselves.
`DEFAULT_RANGE_SIZE=8192` and `MIN_PROGRESSIVE_READ_BYTES=64` can add overhead, but the existing local counters show
`pullParallelSync` doing more write/read calls in the cluster-profile run while still winning. If simple per-call
simulator overhead were the primary cause, it should usually penalize `pullParallelSync`, not make it faster.

The critical risks are higher-level: the tested state shape is not proven cluster-shaped, the benchmark uses an in-memory
lock/condition transport instead of the planned real socket stack, the local teacher is idle and shares one JVM with the
learner, and run provenance/correctness checks are not strong enough for cluster-calibration claims.

## 1. Saved-State Shape Can Override Cluster-Like Parameters

`ReconnectBench.onTrialSetup()` restores saved teacher and learner maps whenever `benchmark.saveDataDirectory=true`.
Only if restore fails does it generate a new state from `teacherAddProbability`, `teacherRemoveProbability`, and
`teacherModifyProbability`.

Evidence:

- `ReconnectBench.java`: saved maps are restored before generation; generation is fallback only.
- `local-reconnectbench-averaged-cluster-profile-results.md`: the cluster-profile local run restored existing maps and
  explicitly notes that the `0.05/0.05/0.05` JMH probability parameters did not define the tested state.
- The restored local state has about `49,999,999` learner leaves and `50,121,146` teacher leaves, a net growth of only
  about `121k` leaves.
- `cluster-metrics-analysis.md` currently points to an append-heavy first approximation for cluster divergence:
  `teacherAddProbability ~= 0.0053`, `teacherRemoveProbability=0`, with modify rate still diagnostic.

Why this is critical: traversal ranking is state-shape sensitive. A broad synthetic historical divergence with random
adds, removes, and modifies can make `pullParallelSync` look better even if the real cluster divergence is more
append-heavy or differently clustered and favors `pullTopToBottom`.

Recommended action:

- Add a compact provenance header to each benchmark run: generated vs restored, saved-state paths, teacher/learner
  metadata, seed/probabilities only if generated, traversal mode, and resolved network config.
- Use separate saved-state directories per state profile so stale restored maps cannot masquerade as a new parameterized
  state.
- Add benchmark-only state-shape profiles before treating local ordering as cluster evidence: append-heavy, update-heavy,
  remove-heavy, and cluster-extracted/cluster-approximated.

## 2. The MVP Transport Is Not The Planned Socket-Based Transport

The original design describes a network simulation layer wrapping real loopback TCP socket streams. The current MVP
constructs two in-memory `SimulatedNetworkChannel`s directly and places `BufferedOutputStream`/`BufferedInputStream` and
`DataOutputStream`/`DataInputStream` on top.

Evidence:

- `ReconnectBench-original-design-specification.md` says the transport below the simulation should be a real loopback TCP
  socket preserving serialization costs.
- `PairedStreams.java` constructs `new SimulatedNetworkChannel(networkConfig)` for both directions.
- `SimulatedNetworkChannel` uses one `ReentrantLock` and one `Condition` per direction.
- Wait counters measure observed wall-clock condition wait time, including scheduling and lock reacquisition, not only
  modeled network delay.

Why this is critical: at low one-way latency, such as the `68us` cluster-profile run, Java lock/condition handoff and
scheduler effects can be comparable to the modeled latency. `pullTopToBottom` is more response-dependent, while
`pullParallelSync` keeps more work in flight. An in-memory condition-based transport can therefore bias low-latency
ordering differently from real sockets.

Recommended action:

- Add a benchmark-only loopback socket transport variant and compare it to `SimulatedNetworkChannel` on the same saved
  state.
- Add diagnostics that separate requested/scheduled wait time from observed wait time.
- For investigation runs, collect JFR thread park/monitor evidence around `LOOPBACK`, zero-latency large-cap
  `REALISTIC`, and cluster-latency large-cap `REALISTIC` profiles.

## 3. The Network Model Is Symmetric And Uses A Fixed App-Read In-Flight Cap

`PairedStreams` gives teacher-to-learner and learner-to-teacher the same `NetworkSimulationConfig`. The simulator also
models in-flight bytes as bytes accepted by the channel but not yet handed to the receiving application.

Evidence:

- `PairedStreams.java` constructs both directions with the same `NetworkSimulationConfig`.
- `SimulatedNetworkChannel.writeRange(...)` blocks when `inflightBytes + bytes.length` exceeds
  `networkInflightBytesLimit`.
- `SimulatedNetworkChannel.read(...)` frees in-flight capacity only when the receiver reads bytes.
- `cluster-metrics-analysis.md` reports directional observed traffic: learner-to-teacher around `233-270 Mbps`, and
  teacher-to-learner around `108-192 Mbps`.
- Current cluster-profile local runs used `128 MiB` and saw zero capacity waits for `pullTopToBottom` and
  `pullParallelSync`, which proves the cap was neutral for that local profile but does not prove it matches cluster TCP
  behavior.

Why this is critical: reconnect is not direction-neutral. The two traversals have different request/response burst
patterns. Real TCP autotuning, ACK pacing, socket buffers, and asymmetric throughput can expose a different bottleneck
than a symmetric fixed-cap model.

Recommended action:

- Sweep symmetric bandwidth profiles only as bracketing diagnostics: `150`, `200`, `300`, and `1000 Mbps`.
- Add benchmark-only support for separate teacher-to-learner and learner-to-teacher bandwidth/cap settings if cluster
  evidence continues to show asymmetric directions.
- Capture cluster TCP/window evidence during reconnect, for example `ss -ti` or equivalent send/receive queue and window
  samples, then compare with local `maxInflightBytes`.

## 4. The MVP Has No Live Teacher Workload And Shares One JVM

The design says teacher workload affects reconnect timing through shared CPU, heap, GC, storage, and response cadence. The
MVP explicitly excludes teacher workload simulation. In current `ReconnectBench`, `teacherMapCopy` is created and later
released, but reconnect reads the immutable teacher map and no concurrent workload is driven during `reconnect()`. The
teacher and learner synchronizers also run in one local JVM and one local process.

Evidence:

- `ReconnectBench-original-design-specification.md` calls teacher workload important because it affects response timing
  and speculation.
- `ReconnectBench-traversal-comparison-mvp-design.md` marks teacher workload simulation as a non-goal for the MVP.
- `ReconnectBench.java` creates `teacherMapCopy = teacherMap.copy()` but `reconnect()` calls
  `MerkleBenchmarkUtils.hashAndTestSynchronization(learnerMap, teacherMap, ...)`; no workload runs against
  `teacherMapCopy`.
- `MerkleBenchmarkUtils` starts teacher and learner synchronizers in a local `StandardWorkGroup`, sharing the same JVM,
  heap, GC, CPU, scheduler, and process-local memory/cache behavior.

Why this is critical: an idle local teacher can answer faster and with less jitter than a production teacher serving
reconnect while the node is still a live system. That can change how quickly clean/dirty responses arrive and therefore
how much speculative traversal work accumulates. Same-JVM execution can also create scheduler, memory-bandwidth, GC, and
cache effects that do not match two production nodes. The bias does not have to affect both traversal modes equally.

Recommended action:

- Add a benchmark-only teacher-load profile that drives the mutable teacher head while reconnect reads the detached
  teacher snapshot.
- As a cluster control, if operationally acceptable, compare one reconnect with normal load against one with workload
  reduced or paused.
- Correlate cluster reconnect windows with teacher-side CPU, GC, VirtualMap lifecycle, and MerkleDB metrics.
- Capture local JFR per traversal for allocation, GC pauses, thread parking, and runnable contention. If the mismatch
  persists, consider a benchmark-only two-process transport/harness as a later validation step.

## 5. Current Results Are Not Strongly Self-Validating

The current benchmark can produce a plausible score even when the result is not fully proven correct or when artifacts are
easy to misattribute.

Evidence:

- `ReconnectBench` uses `SingleShotTime`, one fork, zero warmup iterations, and one measurement iteration.
- The original design expected `@Warmup(iterations = 1)` and `@Measurement(iterations = 3)`.
- `settings.txt` currently disables verification with `benchmark.verifyResult=false`.
- Even if verification is enabled, `VirtualMapBaseBench.verifyMap(...)` logs `FAIL` but does not throw.
- `settings.txt` and `settingsUsed.txt` can disagree after manual traversal-mode changes; the live benchmark log line is
  more authoritative than stale config artifacts.

Why this is critical: when the local/cluster mismatch is only a traversal ranking question, a small number of expensive
runs can look decisive while still being affected by run order, page cache, stale saved states, GC, or an incorrect
reconnected map. A traversal that appears faster must first be proven to have done the right work on the intended state.

Recommended action:

- Make reconnect verification fail the benchmark when `benchmark.verifyResult=true`.
- Run correctness spot checks outside the primary timed comparison until strict verification is cheap enough to keep on.
- Preserve per-run artifacts by traversal mode and timestamp, including the live `ReconnectBench traversal mode=...` log
  line.
- For cluster-calibration runs, use ABBA or BAAB ordering on the same saved state before trusting a narrow ranking gap.
