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
not configure an independent in-flight byte limit; kernel socket buffers provide backpressure.

## Run with Gradle

From the repository root:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect
```

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
