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

These values are MVP defaults, not calibrated production truth. They are best understood as a constrained-window
baseline, not as a universal data-center or mainnet profile.

The in-flight cap is not a provider setting. It is the benchmark's proxy for the effective amount of data a sender can
keep outstanding before network, socket, TCP window, receiver buffering, or application read behavior applies
backpressure. Providers such as Latitude expose topology and bandwidth characteristics, not a single "in-flight bytes"
number. A Kubernetes deployment on Latitude therefore does not determine this parameter by itself: same-node, same
location, paired-location, and cross-region runs can all have different RTT and effective window behavior. For cluster
calibration, use measured pod-to-pod RTT, sustained throughput, and, if possible, TCP diagnostics such as `ss -ti` to
derive or validate the cap.

Calibration on a `50M` saved state showed that the `128 KiB` in-flight cap can strongly affect traversal ordering.
With the same latency and bandwidth but a `16 MiB` in-flight cap, `pullTopToBottom` moved much closer to loopback time.
This means `networkInflightBytesLimit` is not just a harmless implementation detail. It models the amount of data the
sender can keep outstanding across network and buffering layers, and must be swept when the benchmark is used to reason
about real deployments.

For production-like studies, choose parameters from measured deployment data:

- one-way latency should be derived from measured RTT between the candidate teacher and learner;
- bandwidth should come from sustained throughput measurements, not nominal NIC speed alone;
- in-flight bytes should be at least the bandwidth-delay product for that path unless intentionally modeling a
  constrained receive/window/buffering scenario.

Example bandwidth-delay-product guide:

```text
1 Gbps, 1ms RTT    ~= 125 KiB
1 Gbps, 50ms RTT   ~= 6.25 MiB
1 Gbps, 100ms RTT  ~= 12.5 MiB
1 Gbps, 200ms RTT  ~= 25 MiB
10 Gbps, 100ms RTT ~= 125 MiB
```

Increasing latency without increasing the in-flight cap may model a small-window bottleneck more than a real
well-tuned WAN link. Conversely, using a large in-flight cap with a small latency mostly isolates traversal and storage
behavior from backpressure.

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
- Existing traversal expectations are network-shape dependent. `pullTopToBottom` may transfer less total work and win
  in low-latency/high-window environments, but `pullParallelSync` can win under constrained-window or higher
  round-trip-sensitive profiles because it keeps more requests in flight. If a benchmark run reports an unexpected
  ordering, inspect the benchmark setup, simulator overhead, wait counters, and logs before drawing product conclusions.
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

## Calibration Notes

### 2026-05-04

Machine:

- Model: Mac15,9
- CPU: Apple M3 Max
- Memory: 48 GiB
- JVM: Temurin OpenJDK 25.0.2+10 LTS
- JMH JVM args: `-Xmx16g`

Smoke commands:

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=LOOPBACK -PnumFiles=1 -PnumRecords=1000 --no-daemon
```

Result:

- Passed.
- Score: `0.153 s/op`.
- State size: `1 * 1000` requested; generated teacher size logged as `1022`, learner size logged as `999`.
- Traversal mode: `pullTopToBottom`.
- Network profile: `LOOPBACK`; resolved latency `0 ns`, bandwidth `Long.MAX_VALUE B/s`, in-flight limit `Integer.MAX_VALUE`.
- Reconnect stats: `transfersFromTeacher=1023`, `transfersFromLearner=1023`, `leafData=1022`, `leafCleanData=883`.
- Network stats: teacher-to-learner `33982` bytes; learner-to-teacher `64457` bytes.

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnetworkLatencyMicroseconds=500 -PnetworkBandwidthMegabitsPerSecond=1000 -PnetworkInflightBytesLimit=131072 -PnumFiles=1 -PnumRecords=1000 --no-daemon
```

Result:

- Passed.
- Score: `0.135 s/op`.
- State size: `1 * 1000` requested; generated teacher size logged as `1022`, learner size logged as `999`.
- Traversal mode: `pullTopToBottom`.
- Network profile: `REALISTIC`; resolved latency `500000 ns`, bandwidth `125000000 B/s`, in-flight limit `131072`.
- Reconnect stats: `transfersFromTeacher=1023`, `transfersFromLearner=1023`, `leafData=1022`, `leafCleanData=883`.
- Network stats: teacher-to-learner `33982` bytes; learner-to-teacher `64457` bytes.

The first attempted smoke run failed inside the forked JMH benchmark with `NoClassDefFoundError` for
`com.swirlds.benchmark.reconnect.network.NetworkProfile`. Root cause was that the JMH merged jar packaged `src/jmh`
classes but not the simulator classes under `src/main`. The build now adds `com/swirlds/benchmark/reconnect/network/**`
to `jmhJarWithMergedServiceFiles`.

Traversal sanity commands used temporary settings:

```text
benchmark.saveDataDirectory=true
benchmark.benchmarkData=/tmp/reconnectbench-comparison-20260504
virtualMap.reconnectMode=pullTopToBottom
```

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnumFiles=1 -PnumRecords=10000 --no-daemon
```

Then only `virtualMap.reconnectMode` changed to `pullTwoPhasePessimistic`, and the same command was rerun. Finally,
`pullTopToBottom` was run again against the already-restored state to avoid comparing generated-state setup with
restored-state setup.

Results:

- `pullTopToBottom`, first run, generated state under `/tmp/reconnectbench-comparison-20260504`: `0.551 s/op`.
- `pullTwoPhasePessimistic`, restored the same state: `0.306 s/op`.
- `pullTopToBottom`, restored the same state: `0.702 s/op`.

Notes:

- The restored `pullTwoPhasePessimistic` run was faster than the restored `pullTopToBottom` run at this small `10000`
  record size, despite `pullTopToBottom` transferring fewer items (`7353` teacher/learner transfers versus `10044`).
- This contradicts the expected broad traversal ordering and should not be treated as traversal evidence. It is a
  small-state sanity result only; use larger states and repeated runs before drawing conclusions.
- The benchmark now clearly logs whether state is generated or restored, the traversal mode, resolved network profile,
  reconnect stats, and network byte counters.

### 2026-05-05

The `10000` record calibration was too small to evaluate traversal ordering. A `500K` state was the first useful sanity
size, using `numFiles=50`, `numRecords=10000`, `networkProfile=REALISTIC`, `500us` one-way latency, `1 Gbps`, and
`128 KiB` in-flight limit.

Results on the same saved state:

- `pullTopToBottom`: `7.262 s/op`.
- `pullTwoPhasePessimistic`: `7.247 s/op`.
- `pullTopToBottom` repeat: `7.462 s/op`.

The wall times were effectively tied, but the stats showed `pullTopToBottom` doing less work:

- `pullTopToBottom`: `371215` teacher/learner transfers; teacher-to-learner `12196096` bytes; learner-to-teacher
  `23386553` bytes.
- `pullTwoPhasePessimistic`: `447747` teacher/learner transfers; teacher-to-learner `13400260` bytes;
  learner-to-teacher `28208069` bytes.

Verification was then disabled for larger timed runs because result verification consumed several seconds of the `500K`
run and would distort traversal timing.

The next useful calibration state was `50M`, generated with `numFiles=5000`, `numRecords=10000`, `keySize=32`,
`recordSize=128`, `benchmark.verifyResult=false`, and `-Xms24g -Xmx24g -XX:+AlwaysPreTouch`. The saved state was reused
from `platform-sdk/swirlds-benchmarks/data/ReconnectBench`.

Baseline `REALISTIC` results with `500us` one-way latency, `1 Gbps`, and `128 KiB` in-flight:

- `pullTopToBottom`: first run `209.591 s/op`; repeated measured iterations mean `199.220 s/op`.
- `pullParallelSync`: first run `137.628 s/op`; repeated measured iterations mean `148.145 s/op`.
- `pullTwoPhasePessimistic`: `320.868 s/op`.

The result was initially surprising because `pullTopToBottom` was expected to win. However, stats showed that
`pullTopToBottom` and `pullParallelSync` transferred nearly the same bytes:

- `pullTopToBottom`: teacher-to-learner about `1.175 GB`; learner-to-teacher about `2.176 GB`.
- `pullParallelSync`: teacher-to-learner about `1.166 GB`; learner-to-teacher about `2.164 GB`.

This ruled out "parallel sent far less data" as the explanation.

Loopback isolation on the same saved state:

- `pullTopToBottom`: `174.125 s/op`.
- `pullParallelSync`: `167.323 s/op`.

The large `REALISTIC` gap mostly collapsed under `LOOPBACK`. This suggested that the network shape, not basic benchmark
wiring, was driving most of the unexpected ordering.

Temporary wait diagnostics were added to `SimulatedNetworkStats` during investigation. With baseline `REALISTIC`:

- `pullTopToBottom`: `192.481 s/op`; cumulative empty-read wait was about `212.6s` across both directions.
- `pullParallelSync`: `144.576 s/op`; cumulative empty-read wait was about `107.1s` across both directions.

These wait numbers overlap across threads and directions and should not be added to wall-clock time. The useful signal is
the ratio: `pullTopToBottom` spends much more time waiting for peer responses. This matches the traversal algorithms:
top-to-bottom waits for parent responses before sending some descendants, while parallel sync keeps more chunks in
flight pessimistically.

Sensitivity checks:

```text
Profile                                         pullTopToBottom   pullParallelSync
REALISTIC, 500us one-way, 128 KiB in-flight      192.481 s/op      144.576 s/op
REALISTIC, 500us one-way, 16 MiB in-flight       171.917 s/op      152.291 s/op
REALISTIC, 0us one-way, 128 KiB in-flight        180.798 s/op      145.142 s/op
LOOPBACK                                         174.125 s/op      167.323 s/op
```

Interpretation:

- The `128 KiB` in-flight cap is a major part of the `pullTopToBottom` slowdown. Raising the cap to `16 MiB` moved
  `pullTopToBottom` close to loopback.
- Removing the `500us` latency helped `pullTopToBottom`, but less than increasing the in-flight cap.
- `pullParallelSync` remained faster under the baseline and zero-latency constrained-window profiles because it keeps
  the reconnect pipeline fuller.
- `pullTopToBottom` may still win in real low-latency/high-window data-center deployments because it transfers less
  work and is less redundant. A local Latitude-style deployment can have private or provider-local networking that is
  not equivalent to the benchmark's default constrained `128 KiB` window. Latitude documents both global locations and
  different private-network sharing behavior by location, so a "Latitude network" is not one uniform latency/window
  profile. See <https://www.latitude.sh/docs/regions-locations> and <https://www.latitude.sh/locations>.
- Hedera mainnet-style comparisons should not use the same assumptions as a single data-center run. Mainnet consensus
  nodes are distributed across operators and endpoints, and current node addresses should be taken from the live address
  book or Hashscan as described by Hedera docs: <https://docs.hedera.com/hedera/networks/mainnet/mainnet-nodes>.
  Cross-continent links have much larger bandwidth-delay products: at `1 Gbps`, `100ms` RTT implies about `12.5 MiB`
  in flight, and `200ms` RTT implies about `25 MiB`. A `128 KiB` cap on those paths would model a severe window
  bottleneck, not a well-tuned WAN link.

Additional mainnet-style bandwidth-delay-product checks used the same `50M` saved state and `1 Gbps` bandwidth. These
runs swept `100ms` and `200ms` RTT with `13 MiB` and `25 MiB` in-flight caps:

```text
Profile                         pullTopToBottom   pullParallelSync   Result
100ms RTT, 13 MiB in-flight       189.255 s/op      161.473 s/op     parallel by 14.7%
100ms RTT, 25 MiB in-flight       195.280 s/op      172.962 s/op     parallel by 11.4%
200ms RTT, 13 MiB in-flight       204.070 s/op      208.892 s/op     top-to-bottom by 2.3%
200ms RTT, 25 MiB in-flight       184.598 s/op      205.666 s/op     top-to-bottom by 10.2%
```

The traversal work pattern stayed stable across these runs. `pullTopToBottom` consistently transferred about
`1.175 GB` teacher-to-learner and `2.176 GB` learner-to-teacher, with `34.535M` transfers in each direction.
`pullParallelSync` consistently transferred about `1.166 GB` teacher-to-learner and `2.164 GB` learner-to-teacher,
with `34.357M` transfers in each direction. The difference was therefore not explained by one traversal sending much
less data.

The in-flight diagnostics showed a more useful distinction:

- `pullTopToBottom` is more window-sensitive. It reached the learner-to-teacher cap in the `100ms/13 MiB`,
  `200ms/13 MiB`, and `200ms/25 MiB` runs, and came close in the `100ms/25 MiB` run.
- `pullParallelSync` did not reach the learner-to-teacher cap in these runs. Its maximum learner-to-teacher in-flight
  bytes stayed around `11.6-12.4 MiB`.
- RTT changed the relative ordering more strongly than the cap did in this sample: `pullParallelSync` won both `100ms`
  runs, while `pullTopToBottom` won both `200ms` runs.

These runs suggest the benchmark needs a sweep of mainnet-style RTT/window profiles instead of a single `REALISTIC`
default. The crossover point between traversal modes is itself calibration evidence.

An additional diagnostic run used the same `50M` saved state and `1 Gbps` bandwidth, but raised
`networkInflightBytesLimit` to `128 MiB` (`134217728` bytes). This cap is intentionally much larger than the
bandwidth-delay product for both `100ms` and `200ms` RTT at `1 Gbps`, so it is useful for testing whether the cap is
driving the result. It should be treated as a diagnostic profile, not as a realistic default.

```text
Profile                          pullTopToBottom   pullParallelSync   Result
100ms RTT, 128 MiB in-flight       184.095 s/op      176.284 s/op     parallel by 4.2%
200ms RTT, 128 MiB in-flight       196.519 s/op      215.287 s/op     top-to-bottom by 8.7%
```

All channels in these runs reported `capacityWaitCount=0`, and maximum in-flight bytes stayed far below the
`128 MiB` cap. Maximum learner-to-teacher in-flight was about `26.5 MiB` for `pullTopToBottom` and `12.5 MiB` for
`pullParallelSync` at `100ms`, and about `31.4 MiB` for `pullTopToBottom` and `14.7 MiB` for `pullParallelSync` at
`200ms`.

This disproves the simpler hypothesis that making the cap effectively non-binding makes `pullTopToBottom` always win.
For this state shape, `pullParallelSync` still wins at `100ms` RTT without capacity waits, while `pullTopToBottom` wins
clearly at `200ms` RTT. The current best hypothesis is that traversal ordering is primarily RTT-driven for this
divergence shape; an undersized in-flight cap can amplify or distort the ordering, but it is not the only cause of the
observed crossover.

Follow-up calibration guidance:

- Treat `128 KiB` as a constrained-window profile.
- Use `LOOPBACK` only as the no-network storage/traversal baseline. To diagnose latency while removing cap effects, use
  `REALISTIC` with the target RTT, target bandwidth, and a large diagnostic cap such as `128 MiB`.
- For mainnet-style comparisons, sweep RTT and in-flight cap together. At `1 Gbps`, start with `100ms` RTT /
  `13 MiB`, `150ms` RTT / `19 MiB`, and `200ms` RTT / `25 MiB`, then add intermediate RTTs around the observed
  traversal crossover.
- If cluster data is available, use measured RTT and effective TCP window or `ss -ti` samples to narrow the sweep.
- If the temporary wait counters are useful, convert them into optional permanent diagnostics; otherwise remove them
  before finalizing the implementation.

### 2026-05-06

Additional `50M` saved-state runs compared `pullTopToBottom` and `pullParallelSync` at `50ms` and `200ms` RTT using
bandwidth-delay-product-sized in-flight caps for `1 Gbps`. These runs used the same saved state under
`platform-sdk/swirlds-benchmarks/data/ReconnectBench`, `numFiles=5000`, `numRecords=10000`, `keySize=32`,
`recordSize=128`, `benchmark.verifyResult=false`, and `-Xms24g -Xmx24g -XX:+AlwaysPreTouch`. The current worktree also
included the `SimulatedNetworkChannel` input-close, failed-write diagnostic, and progressive-read coalescing fixes.

Commands:

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnetworkLatencyMicroseconds=25000 -PnetworkBandwidthMegabitsPerSecond=1000 -PnetworkInflightBytesLimit=6553600 --no-daemon
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnetworkLatencyMicroseconds=100000 -PnetworkBandwidthMegabitsPerSecond=1000 -PnetworkInflightBytesLimit=26214400 --no-daemon
```

The traversal mode was changed in `platform-sdk/swirlds-benchmarks/settings.txt` between runs.

```text
Profile                         pullTopToBottom   pullParallelSync   Result
50ms RTT, 6.25 MiB in-flight      195.919 s/op      173.976 s/op     parallel by 11.2%
200ms RTT, 25 MiB in-flight       196.529 s/op      220.312 s/op     top-to-bottom by 10.8%
```

Reconnect stats and bytes were stable within each traversal:

```text
Traversal          Transfers each direction   Teacher->learner bytes   Learner->teacher bytes
pullTopToBottom             34.535787M             1.175008844 GB           2.175754589 GB
pullParallelSync            34.356617M             1.165671114 GB           2.164466879 GB
```

In-flight and capacity-wait diagnostics:

```text
Profile / Traversal             T->L max in-flight   L->T max in-flight   Capacity waits
50ms / pullTopToBottom               1,673,419 bytes      6,553,575 bytes   L->T 11295 waits, 5.951s
50ms / pullParallelSync              2,273,011 bytes      6,553,575 bytes   L->T 828 waits, 0.712s
200ms / pullTopToBottom              3,716,507 bytes     20,416,221 bytes   none
200ms / pullParallelSync             4,179,302 bytes     12,246,331 bytes   none
```

These runs reinforce the observed crossover behavior. With a BDP-sized `50ms` profile, `pullParallelSync` remained
faster, even though both traversals moved nearly the same total bytes. With a BDP-sized `200ms` profile,
`pullTopToBottom` was faster. The `50ms` results also show that a nominal BDP cap can still be binding on the
learner-to-teacher direction for this workload shape, especially for `pullTopToBottom`.
