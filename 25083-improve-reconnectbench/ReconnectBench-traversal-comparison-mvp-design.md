# ReconnectBench Traversal-Comparison MVP Design

## Status

This document revises `25083-improve-reconnectbench/ReconnectBench-original-design-specification.md` against the current codebase. It intentionally narrows the first implementation to a traversal-comparison MVP.

The original document remains useful background, especially its explanation of why per-message sleeps make the benchmark untrustworthy. This document is the implementation target for the MVP.

## Purpose

`ReconnectBench` should become a benchmark that can compare virtual map reconnect traversal modes on a developer machine.

The expected workflow is simple:

1. Configure benchmark data persistence with `benchmark.saveDataDirectory` and `benchmark.benchmarkData`.
2. Run `ReconnectBench` with one `virtualMap.reconnectMode`.
3. Change only `virtualMap.reconnectMode`.
4. Run `ReconnectBench` again against the same saved teacher and learner state.
5. Compare wall time and reconnect statistics.

The benchmark will not enforce that runs use identical state. That remains the user's responsibility. The benchmark should log enough setup information to make accidental regeneration visible.

The target runtime for one serious MVP run is about 5-10 minutes on a capable laptop. The exact `numFiles` and `numRecords` defaults may need calibration during implementation.

## Current Codebase Facts

The active benchmark is `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java`.

Traversal mode is configured by `VirtualMapConfig.reconnectMode` in `platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/config/VirtualMapConfig.java`. Supported modes are:

- `pullTopToBottom`
- `pullTwoPhasePessimistic`
- `pullParallelSync`

Benchmark persistence is configured by `BenchmarkConfig`:

- `benchmark.saveDataDirectory=false`: use a temporary benchmark directory.
- `benchmark.saveDataDirectory=true`: preserve generated data under `benchmark.benchmarkData`.

`BaseBench` owns JMH lifecycle annotations and requires subclasses to use hook methods such as `onTrialSetup()` and `onInvocationTearDown()`. `ReconnectBench` should continue following that pattern.

The current delay implementation is not suitable:

- `delayStorageMicroseconds` delays `AsyncOutputStream.sendAsync()` on the caller thread.
- `delayNetworkMicroseconds` delays `AsyncOutputStream.writeMessage()` on the writer thread.
- Both are applied once per message and use thread sleeping.

These knobs distort reconnect by turning asynchronous pipelines into scheduler-gated serial paths. They should be removed, together with `BenchmarkSlowLearningSynchronizer`, `BenchmarkSlowTeachingSynchronizer`, `BenchmarkSlowAsyncOutputStream`, and `LongFuzzer`.

Existing reconnect stats already expose useful traversal-comparison signals through `ReconnectMapStats`, including transfer counts and clean hash/data counts. The MVP should reuse these stats instead of inventing a parallel clean/dirty counter system.

## Goals

- Keep `ReconnectBench` as the single benchmark entry point.
- Keep traversal selection in `VirtualMapConfig.reconnectMode`.
- Keep the existing state generation and saved-state restore workflow.
- Remove the broken storage/network delay knobs.
- Always run the real `LearningSynchronizer` and `TeachingSynchronizer`.
- Add a byte-level simulated network below the synchronizers.
- Provide two network presets:
  - realistic network, for normal traversal comparison;
  - loopback diagnostic, for isolating non-network behavior.
- Report JMH wall time and existing reconnect stats as the primary comparison data.
- Log network profile and diagnostic byte counters.
- Keep the first useful implementation narrow enough to validate and calibrate.

## Non-Goals

- No teacher workload simulation in the MVP.
- No divergence-pattern controls in the MVP. Keep `StateBuilder` behavior as it is.
- No special comparison mode.
- No duplicate JMH parameter for traversal mode.
- No attempt to predict absolute production reconnect time.
- No storage delay simulator.
- No network jitter, packet loss, TCP slow start, or congestion-control modeling.
- No rich benchmark result diff tool.

## Architecture

`ReconnectBench` continues to build or restore teacher and learner `VirtualMap` instances at trial setup.

During each benchmark invocation:

1. The learner and teacher maps are hashed as needed.
2. A benchmark-local reconnect stats collector is reset.
3. A paired stream object is created using the selected network profile.
4. `MerkleBenchmarkUtils` constructs the real `LearningSynchronizer` and `TeachingSynchronizer`.
5. Reconnect runs to completion.
6. The reconnected map is verified when `benchmark.verifyResult` is enabled.
7. Reconnect stats and network diagnostics are logged.
8. The reconnected map is released.

The transport stack becomes:

```text
Reconnect traversal and synchronizer logic       real production code
AsyncInputStream / AsyncOutputStream             real production code
DataInputStream / DataOutputStream framing       real Java stream behavior
BufferedInputStream / BufferedOutputStream       real Java stream behavior
SimulatedNetworkChannel                          benchmark network model
```

The MVP should not use loopback sockets for the simulated network. A socket wrapper creates avoidable ambiguity around kernel socket buffers and OS backpressure. The benchmark already exercises serialization through `DataOutputStream` and `DataInputStream`; the useful missing behavior is controlled network timing and backpressure, not an actual local TCP stack.

## Network Model

The network simulator should be implemented as two independent in-memory byte channels:

- teacher to learner
- learner to teacher

Each channel exposes an `OutputStream` to the sender and an `InputStream` to the receiver. `PairedStreams` wraps those streams in the same buffered/data stream types used today, so synchronizers do not need API changes.

### Channel State

Each `SimulatedNetworkChannel` owns:

- a FIFO of queued byte ranges;
- total bytes written;
- total bytes read;
- current in-flight bytes;
- maximum in-flight bytes observed;
- `nextTransmissionAvailableAtNanos`;
- a closed flag;
- an aborted flag;
- one lock/condition pair for reader/writer coordination.

Timing must use `System.nanoTime()`, not wall-clock time.

### Write Behavior

When the sender writes bytes, the channel copies those bytes and assigns a transmission schedule. It does not sleep once per reconnect message.

For a byte range, the channel computes:

```text
sendStart = max(now, nextTransmissionAvailableAtNanos)
sendEnd = sendStart + transmitDuration(bytes, bandwidth)
arrivalStart = sendStart + oneWayLatency
arrivalEnd = sendEnd + oneWayLatency
nextTransmissionAvailableAtNanos = sendEnd
```

The reader may consume bytes progressively as they arrive. It does not need to wait until the full write chunk has arrived. This matters because `BufferedOutputStream.flush()` may pass a large buffer containing many length-prefixed reconnect messages to the simulated stream in one write call. The first message's length prefix should become readable before the entire buffer has finished transmitting.

Writes are subject to `networkInflightBytesLimit`. Bytes are considered in flight after being accepted by the simulated channel and before being read by the receiving side's stream. If accepting more bytes would exceed the cap, the writer blocks until the reader consumes bytes.

Large writes must not deadlock. If a write is larger than the in-flight cap, the channel must internally split it into smaller ranges and make progress as the reader drains data.

### Read Behavior

The receiver blocks until at least one byte is both queued and past its simulated arrival time. Then it returns available bytes in FIFO order.

If no bytes are available but the sender has closed normally, the stream returns EOF after all queued bytes are drained.

If the channel is aborted by `disconnect()`, blocked readers and writers wake and fail with `IOException`.

`BufferedInputStream` prefetch is acceptable. It models receiver-side stream buffering. In-flight bytes are released when the receiving side's Java stream reads from the simulated channel, not when reconnect logic later processes the resulting message. This matches the existing `AsyncInputStream` architecture, which already has its own queue threshold.

### Flush And Close Semantics

`flush()` should preserve normal stream behavior. Bytes sitting in `BufferedOutputStream` are not on the simulated wire until the buffer writes them to the channel.

`close()` should preserve normal EOF semantics where possible. Reconnect normally sends an explicit `-1` stream marker before close, so EOF is a fallback rather than the normal completion signal.

`disconnect()` is different from close. It is an emergency abort used by reconnect failure paths and should wake blocked threads immediately with an I/O failure.

### Profiles And Parameters

Network behavior is configured by `ReconnectBench` JMH parameters. These are benchmark run-shape parameters, not production node configuration.

```java
@Param({"REALISTIC"})
public NetworkProfile networkProfile;

@Param({"500"})
public long networkLatencyMicroseconds;

@Param({"1000"})
public long networkBandwidthMegabitsPerSecond;

@Param({"131072"})
public int networkInflightBytesLimit;
```

`REALISTIC` uses the configured latency, bandwidth, and in-flight cap.

`LOOPBACK` forces zero latency, unlimited bandwidth, and disables the in-flight cap. The other network params may still exist as fields but are ignored in this profile and should be logged as ignored.

The initial realistic defaults are intentionally conservative:

- `500us` one-way latency means roughly `1ms` RTT.
- `1000 Mbps` means `1 Gbps`, a conservative data-center baseline.
- `131072` bytes means `128 KiB`, approximately one bandwidth-delay product for `1 Gbps` and `1ms` RTT.

The bandwidth-delay-product calculation is:

```text
1 Gbps = 125,000,000 bytes/sec
125,000,000 bytes/sec * 0.001 sec RTT = 125,000 bytes
125,000 bytes ~= 122 KiB, rounded to 128 KiB
```

These values are MVP defaults, not calibrated production truth. Users should be able to sweep them when needed.

### Why This Model Is Enough For The MVP

Traversal comparisons need realistic response visibility and backpressure. The simulator provides:

- latency, so responses do not become visible instantly;
- bandwidth, so large responses take time to transfer;
- in-flight cap, so senders cannot run arbitrarily far ahead of receivers.

It intentionally does not model jitter, packet loss, retransmission, TCP slow start, congestion control, or OS socket buffers. Those would add noise or complexity without improving the MVP's main question: how traversal modes compare under stable network constraints.

### Simulator Overhead And Sanity Expectations

The simulator will add overhead. That is acceptable only if the overhead is bounded, visible, and does not dominate the traversal signal.

Expected overhead sources:

- copying bytes into the simulated channel;
- lock and condition coordination between stream readers and writers;
- timer checks for latency and bandwidth scheduling;
- occasional thread parking when the model intentionally blocks a reader or writer.

The implementation should avoid per-reconnect-message sleeps and should not create one queued object per byte. It should work at byte-range level, split large writes only when required for backpressure/progressive delivery, and make the `LOOPBACK` profile a cheap fast path: no latency waits, no bandwidth waits, and no in-flight cap waits.

Validation must include overhead sanity checks:

- A `LOOPBACK` reconnect should be in the same broad runtime range as the current zero-delay benchmark. If the in-memory simulator is dramatically slower than the old loopback-socket path, fix the simulator before trusting traversal comparisons.
- In `REALISTIC`, changing bandwidth or latency should move wall time in the expected direction. Lower bandwidth or higher latency should not make the same run faster except within measurement noise.
- Existing traversal expectations should hold. `PULL_TOP_TO_BOTTOM` is the current fastest traversal mode; `PULL_TWO_PHASE_PESSIMISTIC` and `PULL_PARALLEL_SYNC` are legacy modes and should be slower under comparable state and network settings. If a benchmark run reports the opposite, treat it as a signal to inspect the benchmark setup, simulator overhead, and logs before drawing product conclusions.
- Compare traversal modes only within the same benchmark implementation, network profile, state, JVM settings, and machine class. The MVP is a relative comparison tool, not an absolute production-time predictor.

## Reconnect Stats And Diagnostics

Primary comparison data:

- JMH wall time.
- Existing reconnect stats from `ReconnectMapStats`.

The benchmark should add a small atomic `ReconnectMapStats` implementation for final reporting. It can be used directly, or as the aggregate stats delegate behind `ReconnectMapMetrics` so existing metrics registration still works.

Counters to report:

- transfers from teacher;
- transfers from learner;
- internal hashes;
- clean internal hashes;
- leaf hashes;
- clean leaf hashes;
- internal data;
- clean internal data;
- leaf data;
- clean leaf data.

Network counters are diagnostic, not primary benchmark metrics:

- bytes written teacher to learner;
- bytes read teacher to learner;
- bytes written learner to teacher;
- bytes read learner to teacher;
- maximum in-flight bytes per direction.

The benchmark should log a compact end-of-invocation summary containing:

- traversal mode from `VirtualMapConfig`;
- whether teacher and learner state were restored or generated;
- teacher and learner snapshot paths when available;
- state size from `numFiles * numRecords`;
- random seed;
- network profile and resolved network values;
- reconnect stats;
- network diagnostics.

## JMH Shape

Reconnect is a long-running operation where one invocation is the unit of measurement. The benchmark should use `Mode.SingleShotTime`.

The source defaults should bias toward one serious run rather than many repeated invocations:

```java
@BenchmarkMode(Mode.SingleShotTime)
@Fork(value = 1)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
```

Users who want tighter confidence can override measurement iterations from the command line. The first MVP implementation should be calibrated so the default state size and realistic network profile produce roughly a 5-10 minute measured reconnect on a capable developer laptop.

## Workflow

Example traversal comparison workflow:

1. Set `benchmark.saveDataDirectory=true`.
2. Set `benchmark.benchmarkData` to a stable directory.
3. Set `virtualMap.reconnectMode=pullTopToBottom`.
4. Run `jmhReconnect` with `networkProfile=REALISTIC`.
5. Set `virtualMap.reconnectMode=pullTwoPhasePessimistic`.
6. Run `jmhReconnect` again with the same benchmark data directory and network params.
7. Compare wall time and reconnect stats.

The spec does not mandate exact Gradle syntax because the implementation should verify the current JMH plugin parameter-passing behavior and document concrete commands after the code exists.

## Validation

Even though this is benchmark code, the network simulator must have focused correctness tests. If the simulator is wrong, the benchmark can produce misleading traversal conclusions.

Required simulator tests:

- byte ordering through `DataOutputStream` and `DataInputStream`;
- first-byte visibility is delayed by configured latency;
- full-stream delivery is limited by configured bandwidth;
- backpressure blocks writers until readers consume bytes;
- writes larger than `networkInflightBytesLimit` do not deadlock;
- normal close drains queued bytes then returns EOF;
- disconnect wakes blocked readers and writers with `IOException`;
- loopback profile behaves like a fast in-memory stream.

Tests should be small and deterministic. They should test the simulator, not benchmark performance.

Manual validation for the benchmark:

- run a small reconnect in `LOOPBACK`;
- run a small reconnect in `REALISTIC`;
- run a medium reconnect twice against the same saved state with two traversal modes;
- confirm output includes wall time, reconnect stats, network profile, and state restore/generation information.

Calibration:

- measure the default state size under `REALISTIC`;
- adjust `numFiles` and `numRecords` defaults if one run is outside the 5-10 minute target;
- document the observed machine and JVM settings used for calibration.

## Implementation Notes

`MerkleBenchmarkUtils.hashAndTestSynchronization()` should be simplified:

- remove delay parameters;
- remove branching between fast and slow synchronizers;
- accept the network configuration or a prebuilt paired-stream factory;
- construct real `LearningSynchronizer` and `TeachingSynchronizer` only.

`PairedStreams` can either be rewritten in place for the benchmark module or replaced with a clearer class name such as `SimulatedPairedStreams`. The implementation should keep the public methods used by `MerkleBenchmarkUtils`:

- `getTeacherOutput()`
- `getTeacherInput()`
- `getLearnerOutput()`
- `getLearnerInput()`
- `disconnect()`
- `close()`

If unit test source-set visibility is awkward for classes under `src/jmh/java`, place the simulator in `src/main/java` and keep `ReconnectBench` itself under `src/jmh/java`.

The old lag package should be deleted after the new network simulator is wired in.

## Deferred Work

Deferred from the original design:

- teacher workload simulation;
- configurable divergence patterns;
- production telemetry calibration of workload assumptions;
- asymmetric network links;
- jitter and tail-latency studies;
- richer result diff tooling;
- two-process teacher/learner harness.

These may be valuable later, but they should not block the traversal-comparison MVP.
