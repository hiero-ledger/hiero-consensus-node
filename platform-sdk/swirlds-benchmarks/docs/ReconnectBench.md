# ReconnectBench

`ReconnectBench` measures virtual-map reconnect between learner and teacher states in one JVM. The synchronizers
communicate through a real TCP connection bound to `127.0.0.1`; there is no in-memory simulated transport in this
benchmark variant.

## Network profiles

The `networkProfile` parameter selects one of three loopback-socket paths:

- `REALISTIC` installs refined-A1 sender observers and receiver gates in both directions. An observer records bounded
  compressed-payload ranges immediately before writing them to the raw socket. The opposite gate does not read a
  range prefix from the socket until its sender-relative one-way latency and progressive bandwidth schedule make that
  prefix eligible.
- `INSTRUMENTED_LOOPBACK` installs the same observers, gates, locks, metadata, and target-derived range splitting as
  `REALISTIC`, but applies zero modeled latency and unlimited modeled bandwidth. Compare it with `LOOPBACK` to measure
  the cost of the refined-A1 plumbing independently of the modeled network delay.
- `LOOPBACK` uses the raw loopback socket streams without observers, gates, range splitting, or timing. It is the
  socket-floor control.

`REALISTIC` uses one independent controller per direction. The configured latency is one-way latency: a small
uncongested request pays one latency on its way to the learner, and the response pays one latency on its way back.
The resulting modeled application round trip is approximately twice the configured latency, before processing and
scheduler overhead. There is not a second fixed delay in either direction.

Bandwidth is a continuous per-direction compressed-payload schedule. Adjacent writes share one serialization cursor,
and eligible bytes appear progressively; neither an idle interval nor a write boundary creates a free first chunk.
If the receiving application falls behind, it may later drain an already-eligible backlog faster than the configured
rate, just as an application may drain a filled receive buffer faster than a physical link. With an eager receiver
and continuous supply, long-run visibility approaches the configured bandwidth.

Refined A1 deliberately has no software window, periodic `W / RTT` release, shadow payload buffer, credit pool, or
initial tickets. The observer does not sleep before writing. The receiver gate is the only component that withholds
reads, so an ineligible byte remains in the receiving kernel buffer. Real OS socket capacity and autotuning therefore
decide when a writer blocks.

Both endpoints are created through the production `SocketFactory` helper. The transport constructs
`SyncInputStream` and `SyncOutputStream` through their production factories, including the production Java buffering,
compression option, and compressed socket-payload counters. The refined-A1 observer/gate pair sits below that
counting and compression stack, so `socket.bufferSize` and `socket.gzipCompression` retain their production meanings.

### Release granularity

Java cannot wake once per serialized byte, so the implementation uses a target-derived release quantum and splits
observed writes into bounded ranges. The quantum is at most `50 us` and, for a positive configured latency, at most
one tenth of that latency. A range is also capped at the amount of configured payload bandwidth that fits in one
quantum, with an absolute `8 KiB` ceiling.

At the calibrated `270 us / 200 Mbit/s` target, `B = 25 MB/s`, the release quantum is `27 us`, and the maximum
observed range is `675` bytes. `INSTRUMENTED_LOOPBACK` derives the same `27 us` and `675`-byte splitting from the
configured target even though it disables the corresponding timing. That makes it the appropriate control for
observer/gate, metadata, range-splitting, and syscall overhead.

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

The task defaults to `REALISTIC`. Run the instrumented pass-through control with:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=INSTRUMENTED_LOOPBACK
```

Select the raw socket-floor control with:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=LOOPBACK
```

The socket shaping properties are:

| Gradle property | Default | Meaning |
|---|---:|---|
| `networkProfile` | `REALISTIC` | `REALISTIC`, `INSTRUMENTED_LOOPBACK`, or `LOOPBACK` |
| `networkLatencyMicroseconds` | `270` | Target one-way latency; modeled only by `REALISTIC` |
| `networkBandwidthMegabitsPerSecond` | `200` | Target per-direction compressed-payload bandwidth; modeled only by `REALISTIC` |

The latency and bandwidth values remain required comparison targets under `INSTRUMENTED_LOOPBACK`: they determine
its release quantum and range splitting even though they do not delay byte visibility. `LOOPBACK` records the target
values for run context but does not instantiate refined-A1 components.

The task also exposes the state-shape and benchmark parameters already used by ReconnectBench, including
`randomSeed`, the three `teacher*Probability` values, `numFiles`, `numRecords`, `maxKey`, `keySize`, `recordSize`, and
`numThreads`.

When `benchmark.saveDataDirectory=true`, a later run restores the named teacher and learner maps if both exist.
The JMH parameters describe the requested run but do not fingerprint an already saved state; use a separate
`benchmark.benchmarkData` location when changing the state shape.

## Diagnostics

Each invocation logs the selected profile, configured comparison target, effective modeled latency and bandwidth,
release quantum, maximum observed-range size, active refined-A1 components, production stream-buffer size, TCP
options, and effective kernel socket-buffer readbacks. End-of-run visibility summaries are reported separately for
the teacher-to-learner and learner-to-teacher directions.

Inspect those summaries before trusting a shaped result. They distinguish:

- compressed bytes observed, scheduled, and returned;
- range count/size and peak pending metadata;
- maximum serialization backlog;
- latency and bandwidth eligibility wait;
- timed-wake release lateness and timing-wake behavior;
- sender-observation-to-first-raw-return latency for each consumed range;
- raw-input wait after bytes were already eligible;
- raw-output delegate duration and bytes exceeding the configured target's latency/serialization thresholds;
- disconnect, EOF, or failure outcome.

Observer-to-first-return is an end-to-end implementation diagnostic, not a pure network-delay sample: it also
includes any time the reconnect consumer was not asking for that range and any eligible raw-read wait. Timed-wake
release lateness is sampled only when the gate actually waited for an eligibility deadline, so an application read
that starts late is not mislabeled as scheduler error.

The benchmark also reports production socket-payload byte counters and reconnect map statistics. The counters measure
compressed payload when compression is enabled; configured bandwidth is therefore payload bandwidth, not Ethernet or
TCP/IP line rate.

Effective kernel buffer sizes are OS readbacks. The host may clamp them and may autotune capacity after construction;
they provide run context, not a configured refined-A1 window. Compare `LOOPBACK` with `INSTRUMENTED_LOOPBACK` under the
same target before attributing a traversal difference to modeled latency or bandwidth. A shaped result needs further
investigation if instrumentation overhead is material, release lateness is large relative to the configured latency,
observer-to-first-return latency materially exceeds its schedule, or raw writes block long enough that their pre-write
timestamps no longer approximate socket admission. `INSTRUMENTED_LOOPBACK` retains the configured target only for
range splitting and these diagnostic thresholds; it still applies no timing delay.

## Scope and limitations

Teacher and learner run in the same JVM and therefore share the garbage collector, CPU caches, scheduler, and host
network stack. The loopback transport exercises real reconnect serialization, production sync-stream
buffering/compression, TCP buffering, natural socket backpressure, and production socket configuration. Refined A1
controls when compressed payload becomes visible to reconnect; it does not put a simulated wire between the sockets.

In particular:

- payload bytes enter the receiver's kernel at loopback speed before the gate exposes them to the production input
  stack, and the receiver can acknowledge those bytes early;
- TCP acknowledgements and receive-window updates return at loopback speed, so congestion-window and advertised-window
  feedback do not experience the configured latency;
- the configured one-way latency is an observer-to-visibility minimum, not the RTT of the TCP connection;
- bandwidth limits compressed application payload, not headers, TLS records, retransmissions, or physical line rate;
- the connection is plain benchmark TCP, not the production TLS transport;
- loss, retransmission, jitter, packetization, queue disciplines, route behavior, and two-host scheduling are not
  modeled.

Consequently, do not describe `REALISTIC` as real TCP operating at the configured RTT and bandwidth. It is a hybrid
low-latency, lossless-path experiment intended for controlled reconnect comparisons while retaining natural loopback
socket occupancy and blocking. High-latency or socket-buffer stress runs remain useful diagnostics, but they do not
predict remote TCP flow-control behavior. Validate traversal conclusions with simulator and cluster evidence; use
separate end-to-end TCP network emulation when delayed ACK/control feedback is part of the required claim.
