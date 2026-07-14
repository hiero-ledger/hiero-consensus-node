# ReconnectBench

`ReconnectBench` measures virtual-map reconnect from a learner state to a teacher state. Its primary use is comparing
reconnect traversal modes under the same state divergence and simulated network conditions. It is intended to predict
the direction of traversal-mode changes, not absolute production reconnect time.

## Simulated Network

The benchmark creates one `SimulatedNetworkChannel` in each direction between the teacher and learner. Each channel
models:

- one-way latency before bytes become visible to the receiver;
- bandwidth by scheduling progressive byte arrival;
- backpressure by blocking the sender when accepted-but-unread bytes reach the in-flight limit.

Both directions use the same network parameters. `REALISTIC` applies the configured latency, bandwidth, and in-flight
limit. `LOOPBACK` disables shaping by resolving to zero latency, unlimited bandwidth, and an unlimited in-flight limit.
This is a byte-stream model, not a kernel TCP connection.

Network statistics report observed traffic and wall-clock blocking. In particular, `emptyReadWaitNanos` measures time
waiting for the peer to produce bytes, while `arrivalWaitNanos` includes scheduler overhead in addition to the modeled
arrival schedule. These counters are diagnostics and are not pure configured network delay.

## Running the Benchmark

From the repository root, run:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect
```

The task accepts Gradle properties for all parameters needed to reproduce a state and network profile. For example, a
small unshaped smoke run can use:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect \
  -PnetworkProfile=LOOPBACK \
  -PnumFiles=1 \
  -PnumRecords=1000
```

The network parameters are:

| Parameter | Meaning |
|---|---|
| `networkProfile` | `REALISTIC` or `LOOPBACK` |
| `networkLatencyMicroseconds` | One-way simulated latency in microseconds |
| `networkBandwidthMegabitsPerSecond` | Symmetric bandwidth limit in decimal megabits per second |
| `networkInflightBytesLimit` | Maximum accepted-but-unread bytes in each direction |

The state is generated from `numFiles * numRecords`. `randomSeed`, `teacherAddProbability`,
`teacherRemoveProbability`, and `teacherModifyProbability` control the deterministic divergence between learner and
teacher. Set `virtualMap.reconnectMode` in `platform-sdk/swirlds-benchmarks/settings.txt` to select
`pullTopToBottom`, `pullTwoPhasePessimistic`, or `pullParallelSync`.

Run from `platform-sdk/swirlds-benchmarks` when using the JMH JAR or an IDE so that `settings.txt` is loaded from the
expected directory. The module [README](README.md) describes the working-directory requirement and general JMH usage.

### Saved-State Caution

When `benchmark.saveDataDirectory=true`, `ReconnectBench` restores both teacher and learner maps from the configured
benchmark data directory if they are present. Generation parameters do not alter an already saved state. Use a new
benchmark data directory or remove the old `ReconnectBench` state before changing state size, record size, seed, or
divergence probabilities.

## Large-State Local Calibration

Small states are useful for smoke testing, but they have not been shown to preserve traversal-mode trends. Use a state
on the order of 100 million entities for a serious comparison. The June 26, 2026 local calibration was in this scale
class, with the following exact profile:

| Parameter | Value |
|---|---:|
| `randomSeed` | `9823452658` |
| `teacherAddProbability` | `0.09` |
| `teacherRemoveProbability` | `0.0` |
| `teacherModifyProbability` | `0.40` |
| `numFiles` | `7409` |
| `numRecords` | `10000` |
| `maxKey` | `10000000` |
| `keySize` | `32` |
| `recordSize` | `128` |
| `numThreads` | `32` |
| `networkProfile` | `REALISTIC` |
| `networkLatencyMicroseconds` | `263` |
| `networkBandwidthMegabitsPerSecond` | `200` |
| `networkInflightBytesLimit` | `134217728` (`128 MiB`) |

The benchmark parameters can be supplied to the Gradle task as follows:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect \
  -PnetworkProfile=REALISTIC \
  -PnetworkLatencyMicroseconds=263 \
  -PnetworkBandwidthMegabitsPerSecond=200 \
  -PnetworkInflightBytesLimit=134217728 \
  -PrandomSeed=9823452658 \
  -PteacherAddProbability=0.09 \
  -PteacherRemoveProbability=0.0 \
  -PteacherModifyProbability=0.40 \
  -PnumFiles=7409 \
  -PnumRecords=10000 \
  -PmaxKey=10000000 \
  -PkeySize=32 \
  -PrecordSize=128 \
  -PnumThreads=32
```

The generated base size was `74,090,000` entities. Because generation uses keys in `[1, size)`, the exact learner size
was `74,089,999`; the exact teacher size after divergence was `81,767,068`, for a `7,677,069` entity size gap. These
exact values are more precise than describing the run as a literal 100-million-entity state.

The runs used Java 25, JMH single-shot time, one fork, no warmup, one measurement iteration, and a fixed `24 GiB` heap
(`-Xms24g -Xmx24g -XX:+AlwaysPreTouch`). The first run generated and verified the state. Later runs restored the same
saved state with verification disabled so the three traversal modes compared identical learner and teacher maps.

### Local Results

| Run | Traversal mode | Reconnect time | Verification | Note |
|---|---|---:|---|---|
| R1 | `pullTopToBottom` | `480.446 s` | Passed, `81,767,068` keys | First pass |
| R2 | `pullParallelSync` | `765.266 s` | Disabled | First pass |
| R3 | `pullTwoPhasePessimistic` | `580.738 s` | Disabled | First pass |
| R4 | `pullTopToBottom` | `527.114 s` | Disabled | Repeat |
| R5 | `pullParallelSync` | `953.710 s` | Disabled | Repeat; affected by noisy host conditions |
| R6 | `pullTwoPhasePessimistic` | `567.559 s` | Disabled | Repeat; close to R3 |

All six runs used the same saved state and network profile. Their stable directional result was
`pullTopToBottom` faster than `pullTwoPhasePessimistic`, which was faster than `pullParallelSync`. R5 should not be used
as a precise estimate because its state shape and reconnect work matched R2 while its host and scheduling conditions
were noisier.

## Cluster Calibration

The first July 1, 2026 cluster reconnect for each traversal mode started with approximately 295 million learner
entities and approximately 320 million teacher entities. The table includes both the first reconnect iteration and the
entire catch-up episode through the final successful learner reconnect.

| Traversal mode | Learner start | First teacher target | First gap | First iteration | Episode iterations | Complete catch-up | Divergence shape | Calibration status |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `pullTopToBottom` | `294,610,462` | `320,220,709` | `25,610,247` | `527.032 s` | `3` | `766.522 s` | Modify-heavy plus append/growth-heavy, remove-light | Accepted |
| `pullTwoPhasePessimistic` | `294,615,073` | `320,694,320` | `26,079,247` | `560.853 s` | `28` | `13,601.006 s` | Long growth-heavy catch-up ending in convergence | Diagnostic for full network calibration |
| `pullParallelSync` | `294,620,525` | `319,844,811` | `25,224,286` | `716.087 s` | `8` | `1,776.607 s` | Growth-heavy repeated incremental catch-up | Accepted |

The top-to-bottom and parallel episodes had complete workload and network evidence. The two-phase episode completed,
but passive TCP/window evidence did not cover its later iterations, workload ended before final completion, and four
teacher windows were unavailable. Its first-iteration timing and state evidence remain useful, but its complete episode
is not a full network-calibration anchor.

These were independent live-state histories. Complete catch-up time includes different iteration counts and continuing
state growth, so it must not be treated as a controlled three-mode benchmark ranking.

## Local-to-Cluster Correspondence

The comparable directional signal is the first reconnect iteration:

| Traversal mode | Cluster first iteration | Local results |
|---|---:|---:|
| `pullTopToBottom` | `527.032 s` | `480.446 s`, `527.114 s` |
| `pullTwoPhasePessimistic` | `560.853 s` | `580.738 s`, `567.559 s` |
| `pullParallelSync` | `716.087 s` | `765.266 s`, `953.710 s` |

Both environments produced the same first-iteration ordering: top-to-bottom, then two-phase pessimistic, then parallel
sync. This supports using the simulated channel and a large state to evaluate traversal-order changes directionally. It
does not mean that local elapsed time predicts cluster elapsed time, nor that a local single reconnect predicts the
number of iterations in a live catch-up episode.

### Calibration Provenance

The simulated channel's executable timing and backpressure logic is unchanged from the version used for the June local
calibration; its move into the JMH source set changed only its location and documentation. Both the local runs and the
July 1 cluster evidence predate a later refactor of reconnect hashing, however. The tables above therefore document a
valid historical local-to-cluster comparison, but they are not a fresh performance validation of the current reconnect
implementation. Re-run the large-state profile before using current-code timings or traversal ordering as a release
claim. A small smoke state is useful for correctness only and is not a substitute for that calibration.
