# ReconnectBench

`ReconnectBench` measures virtual-map reconnect between learner and teacher states in one JVM. The synchronizers
communicate through a real TCP connection bound to `127.0.0.1`; there is no in-memory simulated transport in this
benchmark variant.

## Network profiles

The `networkProfile` parameter selects how the loopback socket input streams behave:

- `REALISTIC` applies read-side latency and bandwidth pacing. The configured latency is one-way latency; the pacing
  window period is twice that value. Its live window size comes from the sender's socket send buffer plus the
  receiver's socket receive buffer.
- `LOOPBACK` uses the same loopback TCP transport without pacing. It is the raw local-socket floor.

Both profiles create and configure their sockets through the production `SocketFactory` helper. ReconnectBench does
not configure an independent in-flight byte limit; kernel socket buffers provide backpressure. The transport also
constructs its Java streams through the production `SyncInputStream` and `SyncOutputStream` factories, so
`socket.bufferSize` and `socket.gzipCompression` have the same effect as they do on consensus-node connections.

## Run with Gradle

From the repository root:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect
```

This is a calibrated large-state run, not a smoke test. Its defaults are:

| Parameter | Default |
|---|---:|
| JMH measurement iterations | `1` single-shot iteration |
| `numFiles` | `7500` |
| `numRecords` | `10000` |
| `recordSize` | `128` bytes |
| `teacherAddProbability` | `0.09` |
| `teacherRemoveProbability` | `0.0` |
| `teacherModifyProbability` | `0.40` |
| `networkLatencyMicroseconds` | `270` |
| `networkBandwidthMegabitsPerSecond` | `200` |
| Benchmark JVM | `-Xms24g -Xmx24g -XX:+AlwaysPreTouch` |

The `7500 * 10000` parameters generate a 75-million-record base state before the teacher/learner divergence is
applied. These defaults track the cluster-calibration state shape and the state used by the recorded large-state
socket runs. Those restored-state socket runs took roughly 20–30 minutes per invocation, so allow additional time
when the state must first be generated.

For a quick functional smoke, override both the state size and heap:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect \
  -PnumFiles=10 \
  -PnumRecords=100 \
  -PreconnectMinHeap=2g \
  -PreconnectMaxHeap=2g \
  -PreconnectAlwaysPreTouch=false
```

The calibrated heap remains the default. The three `reconnect*` Gradle properties above exist only to make
deliberately smaller local runs practical.

The task defaults to `REALISTIC`. Select the raw profile with:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=LOOPBACK
```

The socket shaping properties are:

| Gradle property | Default | Meaning |
|---|---:|---|
| `networkProfile` | `REALISTIC` | `REALISTIC` or `LOOPBACK` |
| `networkLatencyMicroseconds` | `270` | One-way latency used by the realistic profile |
| `networkBandwidthMegabitsPerSecond` | `200` | Per-direction bandwidth used by the realistic profile |

The task also exposes the state-shape and benchmark parameters already used by ReconnectBench, including
`randomSeed`, the three `teacher*Probability` values, `numFiles`, `numRecords`, `maxKey`, `keySize`, `recordSize`, and
`numThreads`.

When `benchmark.saveDataDirectory=true`, a later run restores the named teacher and learner maps if both exist.
The JMH parameters describe the requested run but do not fingerprint an already saved state; use a separate
`benchmark.benchmarkData` location when changing the state shape.

## Diagnostics

Each invocation logs:

- the selected profile and resolved latency and bandwidth;
- effective stream and kernel socket buffer sizes;
- whether latency and bandwidth pacing are active;
- end-of-run pacing window counts, the last live window size, and total parked time for each direction;
- bytes written and read in the teacher-to-learner and learner-to-teacher directions;
- reconnect map statistics.

The effective kernel buffer sizes are OS readbacks and may be clamped or autotuned. The end-of-run pacing summary
reports the live values observed during the run.

## Scope and limitations

Teacher and learner run in the same JVM and therefore share the garbage collector, CPU caches, scheduler, and host
network stack. The loopback transport exercises real serialization, TCP buffering, backpressure, and socket
configuration, but it does not reproduce two-host scheduling or physical-network behavior. Use it for controlled
local comparisons and validate final conclusions with cluster reconnect evidence.
