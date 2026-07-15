# ReconnectBench

`ReconnectBench` measures virtual-map synchronization from a learner state to a teacher state.

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

### Gradle

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

### IDE Main Method

For a local IDE run or profiling session, run the `main` method in `ReconnectBench`. Set the run configuration's
working directory to `<repo>/platform-sdk/swirlds-benchmarks` so the benchmark loads this module's `settings.txt`.
This entry point runs without a JMH fork so that an IDE profiler attaches directly to the benchmark workload.

### JMH JAR

Build the JMH uber JAR from the repository root:

```shell
./gradlew :swirlds-benchmarks:jmhJar
```

Then run it from the benchmark module directory so `settings.txt` and relative paths resolve correctly:

```shell
cd platform-sdk/swirlds-benchmarks
java -jar build/libs/*-jmh.jar ReconnectBench
```

Use JMH CLI options such as `-p` to override benchmark parameters for a JAR run.

The network parameters are:

|              Parameter              |                                          Meaning                                           |
|-------------------------------------|--------------------------------------------------------------------------------------------|
| `networkProfile`                    | `REALISTIC` or `LOOPBACK`                                                                  |
| `networkLatencyMicroseconds`        | One-way simulated latency in microseconds                                                  |
| `networkBandwidthMegabitsPerSecond` | Symmetric bandwidth limit in decimal megabits per second                                   |
| `networkInflightBytesLimit`         | Per-direction limit on bytes accepted from the sender but not yet consumed by the receiver |

Latency and bandwidth determine when queued bytes become readable, but they do not prevent the sender from queuing
arbitrarily far ahead of the receiver. `networkInflightBytesLimit` provides finite buffering and backpressure so that
the simulated channel does not grow without bound and reconnect readers and writers remain paced by one another. When
one direction reaches the limit, writes in that direction block until the receiver consumes enough bytes to free
capacity. The limit approximates finite transport buffering; it is not a bandwidth limit, a combined limit for both
directions, or a detailed TCP-window model.

The state is generated from `numFiles * numRecords`. `randomSeed`, `teacherAddProbability`,
`teacherRemoveProbability`, and `teacherModifyProbability` control the deterministic divergence between learner and
teacher. Set `virtualMap.reconnectMode` in `platform-sdk/swirlds-benchmarks/settings.txt` to select
`pullTopToBottom`, `pullTwoPhasePessimistic`, or `pullParallelSync`.

Run from `platform-sdk/swirlds-benchmarks` when using the JMH JAR or an IDE so that `settings.txt` is loaded from the
expected directory. The module [README](../README.md) describes the working-directory requirement and general JMH
usage.

### Saved-State Preservation

`ReconnectBench` enforces `benchmark.saveDataDirectory=true` regardless of the value in `settings.txt`. It restores both
teacher and learner maps from the configured benchmark data directory when they are present, and it retains newly
generated maps after teardown. `settingsUsed.txt` reports the enforced value.

Generation parameters do not alter an already saved state. Use a new benchmark data directory or deliberately remove
the old `ReconnectBench` state before changing state size, record size, seed, or divergence probabilities.

## Large-State Local Calibration

> **This calibration was performed at the beginning of July 2026.**
>
> Traversal order was selected as the calibration variable because it is a clear reconnect-level difference whose
> directional effect can be compared between local and cluster environments. This calibration choice does not limit
> `ReconnectBench` to traversal-order comparisons.
>
> Small states are useful for smoke testing, but they have not been shown to preserve traversal-mode trends, for example.
> For local calibration, the following parameters were used to replicate the cluster run at its roughly 100-million-entity
> scale and divergence profile:

|              Parameter              |                   Value |
|-------------------------------------|------------------------:|
| `teacherAddProbability`             |                  `0.09` |
| `teacherRemoveProbability`          |                   `0.0` |
| `teacherModifyProbability`          |                  `0.40` |
| `numFiles`                          |                  `7409` |
| `numRecords`                        |                 `10000` |
| `maxKey`                            |              `10000000` |
| `keySize`                           |                    `32` |
| `recordSize`                        |                   `128` |
| `numThreads`                        |                    `32` |
| `networkProfile`                    |             `REALISTIC` |
| `networkLatencyMicroseconds`        |                   `263` |
| `networkBandwidthMegabitsPerSecond` |                   `200` |
| `networkInflightBytesLimit`         | `134217728` (`128 MiB`) |

The generated base size was `74,090,000` entities. Because generation uses keys in `[1, size)`, the exact learner size
was `74,089,999`; the exact teacher size after divergence was `81,767,068`, for a `7,677,069` entity size gap. These
exact values are more precise than describing the run as a literal 100-million-entity state.

The calibration used Java 25, JMH single-shot time, one fork, no warmup, one measurement iteration, and a fixed `24 GiB`
heap (`-Xms24g -Xmx24g -XX:+AlwaysPreTouch`). State generation and correctness verification were performed before
timing. Measurements restored the same saved state with verification disabled so the three traversal modes compared
identical learner and teacher maps.

### Local Results

|      Traversal mode       | Average reconnect time |
|---------------------------|-----------------------:|
| `pullTopToBottom`         |            `527.114 s` |
| `pullTwoPhasePessimistic` |            `574.149 s` |
| `pullParallelSync`        |            `765.266 s` |

All accepted results used the same saved state and network profile and produced a stable directional ordering:

1. `pullTopToBottom`
2. `pullTwoPhasePessimistic`
3. `pullParallelSync`

## Cluster Results

> **These runs were performed at the beginning of July 2026.**

Cluster reconnect for each traversal mode started with approximately 295 million learner
entities and approximately 320 million teacher entities. The table includes both the first reconnect iteration and the
entire catch-up episode through the final successful learner reconnect.

|      Traversal mode       | Learner start | First teacher target |    First gap | First iteration | Episode iterations | Complete catch-up |
|---------------------------|--------------:|---------------------:|-------------:|----------------:|-------------------:|------------------:|
| `pullTopToBottom`         | `294,610,462` |        `320,220,709` | `25,610,247` |     `527.032 s` |                `3` |       `766.522 s` |
| `pullTwoPhasePessimistic` | `294,615,073` |        `320,694,320` | `26,079,247` |     `560.853 s` |               `28` |    `13,601.006 s` |
| `pullParallelSync`        | `294,620,525` |        `319,844,811` | `25,224,286` |     `716.087 s` |                `8` |     `1,776.607 s` |

## Local-to-Cluster Correspondence

> **This calibration was performed at the beginning of July 2026.**

The comparable directional signal is the first reconnect iteration:

|      Traversal mode       | Cluster first iteration | Local average |
|---------------------------|------------------------:|--------------:|
| `pullTopToBottom`         |             `527.032 s` |   `527.114 s` |
| `pullTwoPhasePessimistic` |             `560.853 s` |   `574.149 s` |
| `pullParallelSync`        |             `716.087 s` |   `765.266 s` |

Both environments produced the same first-iteration ordering: top-to-bottom, then two-phase pessimistic, then parallel
sync. This supports using the simulated channel and a large state to evaluate traversal-order changes directionally. It
does not mean that local elapsed time predicts cluster elapsed time exactly, nor that a local single reconnect predicts the
number of iterations in a live catch-up episode.
