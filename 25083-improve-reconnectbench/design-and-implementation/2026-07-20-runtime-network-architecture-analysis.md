g# ReconnectBench Runtime Network Architecture Analysis

Date: `2026-07-20`

Status: `cross-check draft; records the architecture analysis and conclusions from the current review session; no implementation direction is approved by this document`

Branches discussed:

- current branch: `25083-improve-reconnect-bench-socket-net`
- simulator branch: `25083-improve-reconnect-bench-sim-net`
- earlier backup/integration branch: `25083-improve-reconnect-bench-2-approaches`

## Purpose

This document records the complete runtime-network discussion used to compare the current socket-based ReconnectBench
with the `SimulatedNetworkChannel` approach. It is deliberately more detailed than a normal design summary so that a
different reviewer or AI agent can:

1. reconstruct the runtime data paths;
2. verify the bandwidth and latency claims against the code;
3. understand why several initially plausible conclusions were rejected or narrowed;
4. cross-check the interpretation of the 5M and 10M read-pacing experiments;
5. distinguish a raw socket benchmark, a controlled network simulator, and a hybrid socket experiment;
6. challenge the current recommendation before any implementation plan is written.

This is not an implementation plan. It does not authorize code changes and intentionally preserves the unresolved
product question at the end.

## Executive Summary

The two branches run the same reconnect algorithm and state workload through different byte transports:

- The simulator branch uses two in-memory `SimulatedNetworkChannel` instances. It controls when each written byte range
  becomes visible, progressively applies bandwidth, and blocks writers at an explicit in-flight limit. It does not use
  TCP sockets or model TCP internals.
- The current branch uses real loopback TCP sockets configured through production `SocketFactory`, production
  `SyncInputStream`/`SyncOutputStream`, compression when configured, real kernel buffers, and one
  `PacingInputStream` on each receiving direction. The pacer approximates sustained bandwidth and imposes a
  kernel-buffer-derived `W / RTT` throughput ceiling. It does **not** impose configured one-way propagation or
  first-byte latency.

The most important correction from the session is:

> The current socket `REALISTIC` profile does not simulate configured latency in the same sense as
> `SimulatedNetworkChannel`. Its latency parameter controls an RTT-sized throughput window. A small request can still
> become visible at loopback speed when window budget is available.

The current pacer does two valuable things:

1. it approximates a sustained bandwidth ceiling; and
2. by withholding reads, it fills real kernel receive buffers, closes the advertised TCP receive window, and makes
   socket-buffer configuration observable when `W / RTT` is the binding limit.

The 5M and 10M matrices validated that second mechanism. They did not validate first-byte latency simulation. At the
cluster-calibrated `270 us` one-way / `200 Mbit/s` profile, buffer configurations produced no repeatable wall-clock
separation because all tested windows were well above the required bandwidth-delay product. At `50 ms` one-way, the
32 KiB configuration became strongly window-bound while the unset and 1 MiB configurations generally did not.

The current recommendation is therefore not simply “delete the pacer” or “keep the socket branch as realistic.” The
clearest scientific separation is potentially three explicitly named roles:

1. `SIMULATED_NETWORK`: authoritative traversal comparison under controlled latency, bandwidth, and in-flight
   capacity;
2. `LOOPBACK_SOCKET`: raw production socket-stack integration/control measurement with no network-simulation claim;
3. `WINDOW_PACED_SOCKET`: optional socket-window/autotuning sensitivity experiment using the current pacer semantics,
   not advertised as faithful one-way latency simulation.

If only two roles are worth maintaining, the correct choice depends on whether demonstrating the effect of
`SocketFactory` buffer settings remains a required outcome. Removing `PacingInputStream` would preserve a useful raw
production integration baseline, but it would largely remove the current branch's original ability to expose socket
buffer settings in local wall-clock time.

## Scope And Fixed Constraints

The discussion assumed all of the following:

- The final benchmark remains a portable, unprivileged, single-JVM Gradle task.
- It must run without `tc netem`, dummynet, containers, root privileges, or OS-specific network-emulation setup.
- Teacher and learner remain in the same JVM.
- The real production `LearningSynchronizer` and `TeachingSynchronizer` remain the code under comparison.
- The benchmark is primarily intended to compare reconnect traversal approaches, not to become a complete TCP/IP
  emulator.
- Production consensus-node behavior must not be modified for the benchmark.
- A benchmark result must be described only by what the selected transport actually models.
- Absolute results from different transport models must not be treated as if they represented the same network merely
  because their numeric latency and bandwidth parameters have the same values.

These constraints exclude the most literal way to obtain real TCP with controlled link behavior: an external kernel
or network-namespace emulator.

## Branch Relationship And Shared Runtime Core

Both specialized branches descend from the earlier two-approach work and retain different sides of that experiment.
The current branch retained and expanded the socket transport; `25083-improve-reconnect-bench-sim-net` retained and
polished the simulator transport.

The runtime comparison is meaningful because the reconnect core is shared conceptually:

```text
ReconnectBench state and parameters
                |
                v
teacher VirtualMap + learner VirtualMap
                |
                v
LearningSynchronizer + TeachingSynchronizer
                |
                v
StandardWorkGroup and reconnect async streams
                |
                v
DataInputStream / DataOutputStream boundary
                |
                v
transport-specific byte bridge
```

In the current branch, this wiring can be checked in
[`MerkleBenchmarkUtils.java`](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java).
The transport is created before the same production learner and teacher synchronizers are forked into a
`StandardWorkGroup`.

The transport difference can still change important behavior above it. In particular, response visibility,
backpressure, buffering, compression, and how far the asynchronous sender can run ahead all influence reconnect
traversal and scheduling.

## Current Socket-Branch Runtime Architecture

### Socket creation

[`LoopbackSocketTransport.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java)
creates one server socket and one connected client/server socket pair:

```text
teacherSocket (client) <---------- loopback TCP ----------> learnerSocket (accepted server side)
```

The server and connecting socket are configured through production `SocketFactory`. The accepted socket then receives
the production-relevant `TCP_NODELAY` and timeout settings. The accepted socket's send buffer is deliberately not set,
matching production `SocketFactory` behavior.

There is one full-duplex TCP connection, but Java exposes it as separate input and output streams. Reconnect traffic is
bidirectional:

- teacher to learner;
- learner to teacher.

### Production stream stacks

For teacher-to-learner traffic, the current effective stacks are:

```text
Teacher write side

Reconnect / async output
        |
DataOutputStream
        |
SyncOutputStream
        |
BufferedOutputStream or DeflaterOutputStream
        |
production connection byte counter
        |
teacherSocket raw output
        |
loopback TCP


Learner read side

loopback TCP
        |
learnerSocket raw input
        |
PacingInputStream
        |
production input byte counter
        |
BufferedInputStream or InflaterInputStream
        |
SyncInputStream
        |
DataInputStream
        |
Reconnect / async input
```

Learner-to-teacher traffic has the symmetric stack with the other output and input streams.

Because compression is above the raw socket boundary, the pacer operates on wire-side compressed bytes when gzip is
enabled. The production counters also report bytes at the connection boundary rather than decompressed application
bytes.

### One pacer or two?

There are two `PacingInputStream` instances, one per receiving direction:

- `teacherToLearnerPacer` wraps `learnerSocket.getInputStream()`;
- `learnerToTeacherPacer` wraps `teacherSocket.getInputStream()`.

Each individual pacer is one-way. Together they shape both logical directions independently. There is no single
two-way pacer object.

### Why an input wrapper and not an output wrapper?

This was initially suspicious because network shaping is often imagined on the sender or “wire.” The current design
uses input-side withholding for a specific reason: it wants the real kernel buffers to fill.

If a write-side wrapper trickles bytes before they reach the socket:

```text
application -> slow output wrapper -> mostly empty kernel send buffer -> TCP
```

the kernel send buffer remains starved. Its configured size cannot become a bottleneck, so changing the
`SocketFactory` buffer setting has little or no wall-clock effect.

With a read-side gate:

```text
sender writes freely -> send buffer -> TCP -> receive buffer -> receiver deliberately stops reading
```

the receive buffer fills, the advertised TCP receive window shrinks or closes, the remote send buffer fills, and the
sender's real `write()` eventually blocks. This is genuine kernel-driven flow-control backpressure, even though the
cadence that causes it is benchmark-controlled.

### Why is it not “between the sockets”?

With one TCP connection, there is no portable Java insertion point inside the kernel-managed network path. User code
can wrap:

- the sender's socket output before bytes enter the kernel; or
- the receiver's socket input after bytes have reached the receiving kernel.

Putting a Java component literally between socket endpoints requires a relay and therefore two TCP connections, not
one end-to-end connection. The current pacer is intentionally placed directly above the raw receiver input so withheld
bytes remain in the receiving kernel rather than being drained into an unbounded Java buffer.

### What the current pacer controls

[`PacingInputStream.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/PacingInputStream.java)
combines two independent eligibility rules:

1. a bandwidth cursor; and
2. an RTT-window budget derived from live socket-buffer sizes.

Per direction, its effective window is:

```text
W = remote sender SO_SNDBUF + local receiver SO_RCVBUF
```

`W` is re-read when each RTT window opens, so OS receive-buffer autotuning can become visible during a run.

The `REALISTIC` socket profile passes:

```text
rttNanos = 2 * configured one-way latency
```

The first window opens immediately. The pacer may release at most `W` bytes during that window. Once it exhausts the
window, it waits for the next modeled RTT before opening another window.

### What the current pacer does not control

It does not control:

- when the sender called `write()`;
- when bytes entered the sender's kernel;
- when TCP segments crossed loopback;
- when the receiving kernel received them;
- when TCP ACKs were generated or returned;
- TCP congestion-window growth;
- packetization, loss, retransmission, reordering, or jitter.

It only controls when Java code above the receiving socket may drain bytes from the kernel.

The implementation also depends on the production input stack issuing array reads. The no-argument `read()` is not
gated, and inherited `skip()` and `available()` are not modeled. Existing reconnect usage and buffering make that
acceptable for the current measured path, but it is a maintenance assumption rather than a general-purpose stream
guarantee.

## Bandwidth Semantics

### Current `PacingInputStream`

The current bandwidth algorithm is “release, then wait.” For a read returning `n` bytes at configured bandwidth `B`,
the pacer:

1. gives those `n` bytes to its consumer immediately, provided the RTT window also permits them;
2. advances a cursor by approximately `n / B` seconds;
3. prevents the next read until that cursor becomes eligible.

At `200 Mbit/s`:

```text
200,000,000 bits/s / 8 = 25,000,000 bytes/s
8192 bytes / 25,000,000 bytes/s = 0.00032768 s
                                         = 327.68 us
```

An 8 KiB read can therefore arrive as an immediate burst, followed by about `328 us` of waiting. Over a long sustained
transfer, the average rate approaches the configured bandwidth, subject to scheduler overhead and other bottlenecks.

Verdict:

- sustained average bandwidth: reasonably modeled;
- progressive byte arrival within each released chunk: not modeled;
- short-transfer timing: may be optimistic by up to approximately one chunk's transmission duration;
- observed rate: can remain below the cap because reconnect, storage, hashing, compression, or protocol scheduling is
  slower than the configured network limit.

### `SimulatedNetworkChannel`

The simulator branch can be inspected without switching branches with:

```bash
git show 25083-improve-reconnect-bench-sim-net:platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java
```

It splits writes into ranges of at most 8 KiB and schedules each range:

```text
sendStart   = max(now, previousSendEnd)
sendEnd     = sendStart + bytes / bandwidth
arrivalStart = sendStart + oneWayLatency
arrivalEnd   = sendEnd + oneWayLatency
```

Between `arrivalStart` and `arrivalEnd`, bytes become readable progressively. The receiver waits for a small useful
amount of progress rather than receiving the whole range immediately.

For the same 8 KiB / 200 Mbit/s example, the full range still takes about `328 us` to transmit, but early bytes become
visible during that interval instead of all 8 KiB being released at its beginning.

### Can the pacer be changed to match simulator bandwidth?

For bandwidth alone, yes. A receiver-side gate can make only the number of bytes earned by elapsed transmit time
readable. It could use smaller release quanta or the same range schedule as `SimulatedNetworkChannel`.

However, doing only that was not recommended as a final tweak:

- microsecond-scale wakeups and smaller reads add scheduler and CPU overhead;
- the JDK cannot guarantee precise wakeups at those intervals;
- changing read chunking can change buffering, decompression, and reconnect thread scheduling;
- it would not fix missing sender-relative latency;
- existing 10M control runs achieved about `11.6 MB/s`, below the `25 MB/s` configured cap, so the benchmark was not
  bandwidth-bound in the ordinary calibrated cell;
- any benefit should first be demonstrated by an experiment showing that burst-vs-progressive delivery changes the
  traversal comparison.

If a coordinated hybrid scheduler is implemented for latency, bandwidth should probably be incorporated into that
single schedule. Independently polishing the current burst shape risks adding complexity without changing the
benchmark's main result.

## Latency Semantics

### The source of the earlier confusion

The current pacer can make a large transfer take approximately the time predicted by bandwidth and `W / RTT`. That can
look like correct latency simulation when observing only total wall-clock time.

But there are two different meanings of latency:

1. **Propagation or response-visibility latency:** a byte written at time `t` cannot be observed remotely before
   approximately `t + L`.
2. **RTT throughput consequence:** a sender with finite outstanding window `W` cannot sustain more than approximately
   `W / RTT` without acknowledgements or receiver-window progress.

The current pacer models the second consequence. It does not model the first.

### Small request/response example

Assume one-way latency is configured as `270 us` and the current RTT window still has budget:

```text
teacher writes a small request
        |
loopback transfers it in microseconds
        |
learner's PacingInputStream has available window budget
        |
learner reads immediately
```

The configured `270 us` may add no delay. If the learner responds immediately and the reverse pacer also has budget,
the entire request/response can occur near loopback speed rather than the intended approximately `540 us` network
round trip.

This matters because traversal approaches react to which responses have arrived and how much speculative work can be
kept in flight.

### Large continuous-transfer example

For a sustained transfer larger than `W`, the pacer releases `W`, waits an RTT, releases another `W`, and so on. The
long-run throughput approaches:

```text
min(configured bandwidth, W / RTT)
```

Total time can therefore resemble a network-limited result even though the first bytes and small interactions were
not delayed by configured one-way latency.

This is why “the total transfer looks right” and “the latency model is not equivalent” can both be true.

### Why a simple sleep in `PacingInputStream` is insufficient

Sleeping for `L` before each read would anchor latency to receiver read calls rather than sender writes:

- if the sender writes after the receiver begins sleeping, the data receives less than `L` of actual delay;
- if one logical message requires three reads, it may pay `3L`;
- if three messages are coalesced into one buffered read, they may pay only `L`;
- read chunking, compression, and buffering would silently redefine network latency;
- sleeping after reading would drain the kernel too early, weakening the socket-buffer/backpressure behavior the
  pacer was created to expose.

Network latency must be related to when ordered bytes are emitted, not merely to when a consumer happens to call
`read()`.

### The important correction: same-JVM coordination is possible

An earlier overstatement was that receiver-side latency could not be made reliable because the input stream does not
know sender write time. The benchmark controls both endpoints in the same JVM and shares `System.nanoTime()`, so it can
coordinate sender and receiver explicitly.

What is impossible is reliable sender-relative latency with **two independent input-only pacers**. A coordinated
output observer plus input gate can provide controlled application-visible timing.

## A Coordinated Hybrid Socket Design

The credible “best of both worlds” design discussed in the session would add one shared scheduler/coordinator per
direction:

```text
production output stack
        |
sender-side wire-range observer
        |       records ordered range metadata
        |------ {length, send time, scheduled visibility} ------|
        v                                                    v
real loopback socket                               receiver visibility gate
        |                                                    |
        +---------------------- TCP -------------------------+
                                                             |
                                                  production input stack
```

The output-side component would observe wire bytes below production buffering/compression. The input-side component
would use TCP byte ordering plus the recorded range lengths to prevent corresponding bytes from becoming visible
before their scheduled time.

The output observer would not necessarily pace writes. Preserving a raw write path allows the real socket buffers to
fill. The receiver gate would still avoid draining the kernel before bytes are eligible.

### What this hybrid could model

It could model, at the Java/reconnect boundary:

- sender-relative first-byte visibility;
- one-way application-visible latency in both directions;
- serialized per-direction bandwidth;
- progressive byte visibility;
- production compression, buffering, flushing, and wire-byte accounting;
- real loopback socket buffers and eventual TCP receive-window backpressure.

For a small ping-pong interaction, it could ensure:

```text
teacher request -> L -> learner application
learner response -> L -> teacher application
```

so reconnect sees approximately `2L` of round-trip response latency.

### Difficult details

The design is possible, but not trivial:

- Timestamping before a blocking raw socket write can schedule bytes earlier than they were actually accepted.
- Timestamping only after a large blocking write completes can make every byte in the range too late.
- Splitting writes into smaller ranges improves timing resolution but adds calls, metadata, and scheduling overhead.
- The gate must preserve ordering across production flush boundaries and compression output chunks.
- Shutdown must wake threads blocked in both timing waits and socket I/O.
- The scheduler must avoid making its own locks or queues the dominant backpressure mechanism.
- Tests need to distinguish intended timing from ordinary scheduler overshoot.

This would be a benchmark-controlled delivery model layered on real sockets, not a literal emulated network link.

## Fast Loopback TCP Acknowledgements

Even with a coordinated visibility gate, TCP ACKs would continue to traverse loopback at microsecond-scale latency.
The reconnect application and the TCP stack would therefore observe different timelines.

For configured `270 us` one-way latency:

```text
Coordinated loopback hybrid

t = 0 us       sender writes
t = a few us   receiver kernel receives; TCP ACK generated
t = a few us   sender kernel receives ACK
t = 270 us     Java visibility gate permits receiver application to read
```

A simplified real-network timeline is:

```text
Real network

t = 0 us       sender writes
t = 270 us     receiver kernel receives; receiver application may read
t = 540 us     ACK reaches sender
```

### Consequences of fast ACKs

Compared with a real network, loopback TCP can:

- grow its congestion window faster;
- drain the sender kernel buffer sooner;
- allow the application or async output writer to enqueue more data before blocking;
- change output queue peaks, memory residency, CPU overlap, and when backpressure reaches reconnect code;
- avoid real loss, retransmission, reordering, and congestion behavior.

The receiver gate can still make the receiving kernel fill. When it stops reading, the advertised receive window
eventually shrinks, the sender's send buffer fills, and a real socket write blocks. However, the amount and timing of
sender run-ahead are controlled by local send/receive buffers and loopback ACK behavior, not by the real path's
congestion window and bandwidth-delay product.

### Severity at the calibrated profile

At `200 Mbit/s`, decimal throughput is `25,000,000 B/s`. With `270 us` one-way latency, RTT is `0.00054 s`, giving:

```text
BDP = 25,000,000 B/s * 0.00054 s = 13,500 bytes ~= 13.2 KiB
```

This is a small bandwidth-delay product. On a healthy, low-loss data-center path, fast loopback ACKs are unlikely to
dominate the total time of a large reconnect if the application-visible scheduler correctly controls response timing
and sustained delivery. They may still change sender queueing and thread overlap.

The trust decreases for:

- high-RTT or high-BDP paths;
- loss or congestion studies;
- comparisons sensitive to how far the asynchronous output thread runs ahead;
- claims about kernel queue depth, congestion window, retransmissions, or exact write-block timing.

### Trust boundary for the hybrid

| Hybrid result | Appropriate trust |
|---|---|
| Relative traversal behavior under controlled application-visible latency | Reasonably strong after validation |
| Production serialization and compression cost | Strong |
| Production wire-byte counters | Strong |
| Approximate bandwidth-limited receive completion | Reasonably strong |
| Absolute reconnect time on a healthy low-latency network | Moderate, calibration-dependent |
| Sender queue depth and exact backpressure timing | Limited |
| TCP congestion, loss, retransmission, or high-BDP behavior | Weak |
| Equivalence to real TCP with the configured RTT | Invalid claim |

No universal percentage error can be assigned without validation. The most useful validation would compare traversal
ranking, work counters, output queue behavior, and sensitivity to socket-buffer sizes across simulator, hybrid, and
cluster evidence.

## The Relay Alternative

A relay is the only portable Java way to place a scheduling component conceptually between endpoints:

```text
Teacher <-> TCP connection A <-> relay scheduler/queue <-> TCP connection B <-> Learner
```

It can schedule latency and bandwidth using sender-facing bytes before forwarding them to the receiver. However, it
does not preserve one end-to-end TCP connection.

### Relay dilution

A relay adds:

- a second TCP connection;
- two additional socket endpoints;
- at least one forwarding thread per direction or an event-loop equivalent;
- additional kernel buffers;
- a relay queue and byte copies;
- additional syscalls and scheduling;
- two independent TCP ACK/congestion/window domains.

Each connection still ACKs locally at loopback speed. The relay controls forwarding, not the original end-to-end TCP
ACK clock.

### Why “unbounded sockets” do not solve the problem

Sockets are never truly unbounded. OS send and receive buffers are finite, clamped, and often autotuned. A very large
or unbounded Java relay queue would make the model less faithful:

```text
teacher rapidly dumps data into relay queue
relay accepts it without applying pressure
relay later dribbles it to learner
```

This hides sender backpressure and lets reconnect run ahead farther than either a real bounded network or the current
receiver-gated socket would permit.

A bounded relay queue is scientifically better because pressure propagates:

```text
connection B blocks -> relay queue fills -> relay stops reading connection A -> teacher eventually blocks
```

But the resulting outstanding capacity is a combination of connection A buffers, relay capacity, and connection B
buffers. It is not the capacity of one production connection.

### Relay conclusion

The relay provides a clean point for scheduling bytes, but it changes the transport topology more than the coordinated
receiver-gate hybrid. Under the portable single-JVM constraint, it was not the preferred approach for preserving the
meaning of the current socket experiment.

## The Fundamental “Best Of Both Worlds” Constraint

The discussion exposed a three-way tension:

1. controlled, deterministic network timing;
2. one untouched end-to-end production TCP connection;
3. portable, unprivileged, single-JVM execution.

The available designs emphasize different pairs:

| Design | Controlled timing | One end-to-end TCP connection | Portable/unprivileged/single JVM |
|---|---:|---:|---:|
| `SimulatedNetworkChannel` | Yes | No | Yes |
| Current loopback socket | No first-byte latency; approximate throughput controls | Yes | Yes |
| Coordinated socket hybrid | Application-visible timing, not TCP-internal timing | Yes | Yes |
| In-JVM relay | Controlled forwarding timing | No; two connections | Yes |
| OS/kernel network emulator | Closest to real TCP plus controlled link | Yes | No |

The hybrid is useful, but “best of both worlds” must be defined at the reconnect/application boundary. It cannot mean
that the loopback TCP stack itself behaves as though ACKs, congestion, loss, and retransmission crossed the configured
network.

## `SimulatedNetworkChannel` Architecture

The simulator uses two independent one-way channels:

```text
teacher output -> SimulatedNetworkChannel A -> learner input
learner output -> SimulatedNetworkChannel B -> teacher input
```

Each channel owns:

- a FIFO of copied byte ranges;
- a serialized transmission cursor;
- scheduled arrival start and end times;
- bytes-written and bytes-read counters;
- current and maximum in-flight bytes;
- a bounded capacity that blocks writers until readers consume data;
- lock/condition coordination for data, timing, capacity, close, and disconnect.

It models:

- first-byte one-way visibility delay;
- progressive per-direction bandwidth;
- ordered delivery;
- bounded application-level outstanding bytes/backpressure;
- normal drain-before-EOF close and abort semantics.

It intentionally does not model:

- sockets or kernel socket buffers;
- TCP ACKs or congestion control;
- packetization;
- retransmission, loss, reordering, or jitter;
- OS-specific autotuning.

Its key scientific advantage is explicit semantics. If a range is written at time `t`, its first byte is not readable
before the scheduled `t + L`, and its remaining bytes become readable according to configured bandwidth.

Its key limitation is that the backpressure is a benchmark-defined in-flight cap rather than an observed kernel TCP
window.

## Should The Simulated And Socket Approaches Be Used Together?

“Together” should not mean placing them both in one byte path. It can mean selectable instruments under the same
benchmark harness.

The useful comparison shape is:

```text
SIMULATED_NETWORK
    traversal A vs traversal B under identical controlled network settings

LOOPBACK_SOCKET
    traversal A vs traversal B through the production socket/stream stack
```

The meaningful comparison is primarily **within** each transport. Absolute seconds across transports are not directly
comparable because their network semantics and overhead differ.

Cross-transport observations can still increase confidence:

- If both transports rank traversal approaches the same way, the result is more robust to the transport model.
- If rankings differ, that is evidence of an interaction with response timing, in-flight capacity, compression,
  socket buffers, flushing, or thread scheduling. It is not a reason to average the results.
- The simulator can answer the controlled causal network question while the socket path acts as a production
  integration/control measurement.

To reduce unrelated differences, a future design could place the same production sync-stream factories above both
underlying byte transports. That possibility needs separate design and validation; the current simulator branch uses
ordinary buffered data streams, while the current socket branch now uses production sync streams.

## Is `PacingInputStream` Needed If Both Transports Exist?

The answer depends on the declared purpose of the socket mode.

### Simulated network mode

No pacer is needed. The simulator already owns controlled latency, bandwidth, and bounded in-flight behavior.

### Raw loopback socket mode

No pacer is needed if the mode's purpose is strictly:

> Exercise production socket creation, sync streams, compression, flushing, counters, close behavior, and real local
> kernel I/O without claiming to represent a configured network.

This raw mode is a production-integration baseline and performance floor.

### Socket-window sensitivity mode

The pacer is needed if the mode's purpose remains:

> Determine whether a production `SocketFactory` send/receive window would constrain reconnect at a modeled RTT and
> observe buffer pinning/autotuning effects.

Raw loopback RTT is so small that normal socket-buffer sizes are generally much larger than the local BDP. Without
deliberately withholding reads, those buffers usually do not remain full long enough for their configured size to
affect wall-clock time.

Therefore, the earlier statement “remove `PacingInputStream` in a two-mode design” is valid only when socket mode is
redefined as a raw baseline. It is too broad if socket-buffer sensitivity is still a required outcome.

## What Removing `PacingInputStream` Would Change

Removing it would not make the socket code worthless, but it would change the research question.

### Value retained by raw loopback

Raw loopback would still exercise:

- production `SocketFactory` connection setup;
- a real TCP connection and kernel copies;
- production `SyncInputStream` and `SyncOutputStream`;
- gzip compression/decompression when configured;
- production buffering and flush behavior;
- socket disconnect/close behavior;
- compressed connection-byte counters;
- traversal behavior in a near-zero-network-wait control environment;
- socket-specific integration regressions or deadlocks.

It can answer:

- Does reconnect behave correctly through the production stream stack?
- Does compression change traversal ranking or CPU time?
- What is the raw non-network floor on this machine?
- Do simulator and raw socket modes agree on qualitative traversal ranking?

### Value lost

It would generally lose the ability to answer locally:

- Does a 32 KiB versus 1 MiB configured socket window move wall-clock time at a modeled RTT?
- How does live receive-buffer autotuning change the effective window during a run?
- When does `W / RTT` become the limiting throughput rather than application work or configured bandwidth?

That loss is substantial because exposing socket-buffer configuration was the explicit historical purpose of the
read-pacing work.

### Maintenance-value question

If controlled network comparison is the only required benchmark result and simulator mode can also reuse the
production sync-stream stack, raw sockets may provide only secondary integration value. The project must decide
whether that value justifies the additional code, configuration, tests, documentation, and module-access complexity.

## Evidence From The 5M And 10M Experiments

### Governing condition

Under the current pacer:

```text
modeled throughput = min(configured bandwidth, W / RTT, application-produced/consumed rate)
```

Changing socket buffers affects wall-clock only when `W / RTT` is below the rate the rest of the system could
otherwise sustain.

Equivalently, the window can bind when:

```text
W < required throughput * RTT
```

### 5M smoke matrix

The
[`2026-07-08 Read-Pacing Smoke Matrix`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-08-read-pacing-smoke-matrix.md)
used `200 Mbit/s`, three buffer configurations, and two modeled latency legs.

Relevant approximate results:

| Leg | Unset | 32 KiB | 1 MiB |
|---|---:|---:|---:|
| `270 us` one-way control | warm about `50.8 s` | mean `53.6 s` | mean `40.1 s` |
| `50 ms` one-way binding | median `69.1 s` | median `94.9 s` | median `54.6 s` |

The small run had a large historical noise/confound floor, so the control differences were not treated as a reliable
buffer effect. The `50 ms` cell produced the physically ordered `32 KiB > unset > 1 MiB` trend, and live window
diagnostics showed the unset configuration growing through autotuning.

### 10M fresh paired matrix

The
[`2026-07-16 Read-Pacing 10M Matrix`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md)
was the stronger internal reproduction:

| Configuration | `270 us` one-way control mean | `50 ms` one-way mean |
|---|---:|---:|
| unset/autotuned | `75.824 s` | `78.610 s` |
| 32 KiB | `78.264 s` | `197.362 s` |
| 1 MiB | `78.702 s` | `75.686 s` |

Interpretation:

- At `270 us`, the largest mean spread was only `3.8%`. The window did not govern wall-clock.
- At `50 ms`, the 32 KiB configuration was about `2.53x` slower than its own control and about `2.61x` slower than
  the other `50 ms` configurations.
- The 32 KiB teacher-to-learner direction achieved about `4.45 MB/s`, near its approximately `5.23 MB/s` end-window
  divided by `100 ms` RTT ceiling.
- Unset and 1 MiB moved about `878 MB` teacher-to-learner in roughly `76 s`, about `11.6 MB/s`. Their windows allowed
  more than that achieved application rate, so neither remained the stable wall-clock bottleneck.
- Unset still showed a first-connection autotuning ramp, but the longer transfer amortized it.

The correct conclusion is narrow:

> The pacer makes an actually undersized socket window visible in reconnect wall-clock. It does not guarantee that
> every buffer configuration differs, because the window matters only when it is the binding constraint.

### What these experiments did not prove

They did not prove:

- that `50 ms` represents the calibrated cluster path;
- that per-message or first-byte latency is modeled correctly;
- that the same absolute seconds will reproduce on Linux or in the cluster;
- that a 1 MiB pin always beats autotuning for larger states;
- that current socket results and simulator results are comparable at the same numeric latency;
- that socket buffers are irrelevant when the control cell shows no separation.

They are strong mechanism evidence for the `W / RTT` window limiter and weaker evidence for production prediction.

### Production-cluster context

Extracted cluster socket evidence records low minimum RTTs—generally tens of microseconds—and multi-megabyte
`Recv-Q`, `Send-Q`, and `rwnd_limited` episodes during reconnect. This shows that real reconnect backpressure can also
come from application drain/queue behavior at low RTT, not only from a large propagation delay.

The pacer's `50 ms` binding experiment forces a window-limited regime through modeled RTT. It can reproduce the
general consequence “a small effective window constrains progress” while not necessarily reproducing the cluster's
exact cause or timeline.

### Production sync-stream and compression evidence

The uncommitted local
[`2026-07-16 Compression 10M Comparison`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-compression-10m-comparison.md)
reported that compression reduced combined wire bytes by `60.1%` but increased median reconnect time by `71.8%` in
that environment. This supports a separate value for production-stream testing: total reconnect time can be dominated
or materially changed by compression CPU, flush behavior, and protocol scheduling even when fewer wire bytes pass
through the pacer.

It also cautions against attributing every wall-clock change in the socket mode to network pacing.

## Architecture Options And Arguments

### Option A: Improve the socket approach into a coordinated hybrid

Add a sender-side wire-range observer and coordinated receiver visibility gate per direction. Use one shared schedule
for sender-relative latency and progressive bandwidth while retaining one real TCP connection and production streams.

Advantages:

- controlled application-visible latency and bandwidth;
- production socket, compression, buffering, and counters remain in the measured path;
- receiver withholding can preserve real kernel-buffer backpressure;
- potentially the richest single portable measurement.

Disadvantages:

- substantial implementation and test complexity;
- fast loopback ACKs still make TCP-internal behavior unrealistic;
- output timestamp semantics around blocking writes are subtle;
- added metadata, chunking, and timing coordination can influence the benchmark;
- the result is a hybrid model whose trust boundary must be documented carefully;
- it can duplicate much of `SimulatedNetworkChannel` while being less explicit.

Use this option only if a required benchmark claim is:

> Compare reconnect through the production socket stack while controlling what latency and bandwidth the reconnect
> application observes.

### Option B: Keep simulator and raw socket as two separate instruments

Use the simulator as the authoritative network comparison and raw sockets as a production integration/control mode.

Advantages:

- clearest separation of claims;
- simpler than a coordinated hybrid;
- simulator gives explicit, testable timing semantics;
- socket mode gives production stack and kernel integration coverage;
- disagreements become useful diagnostic evidence.

Disadvantages:

- no single result contains both controlled timing and real socket buffers;
- absolute results cannot be compared directly across modes;
- raw socket mode generally will not reveal buffer configuration locally;
- maintaining two modes still adds surface area;
- stream-stack differences must be minimized or documented.

This is the best option if controlled traversal comparison is primary and raw production integration is valuable but
secondary.

### Option C: Keep three explicit roles

Retain:

1. controlled simulator;
2. raw loopback socket baseline;
3. current window-paced socket sensitivity experiment.

Advantages:

- preserves the strongest use of each existing implementation;
- keeps the 5M/10M buffer work meaningful;
- avoids pretending the paced socket provides true one-way latency;
- supplies both raw and stressed socket controls;
- does not require immediately building the more complex coordinated hybrid.

Disadvantages:

- highest configuration/documentation burden short of the hybrid;
- easy for users to compare incompatible absolute values unless naming and output are explicit;
- more tests and Gradle parameter combinations;
- paced mode remains a model, not a real network;
- may be more experiment surface than the final benchmark needs.

This is the current conditional recommendation if socket-buffer sensitivity remains a required outcome.

### Option D: Keep only `SimulatedNetworkChannel`

Use one explicit network model and remove socket-specific benchmark code.

Advantages:

- smallest and clearest benchmark;
- best-defined latency/bandwidth semantics;
- easiest traversal comparisons;
- avoids socket/OS variability and misleading claims.

Disadvantages:

- loses production socket, compression, and kernel-buffer integration evidence unless production sync streams can be
  reused independently;
- discards the branch's socket-buffer experiments;
- cannot observe production `SocketFactory` settings or OS autotuning;
- simulator in-flight capacity remains a chosen model parameter.

Use this option if the benchmark's only accepted purpose is controlled traversal comparison and socket integration is
better covered elsewhere.

## Current Conclusion

The session did not produce evidence that one transport can be made a perfect replacement for the other under the
portable single-JVM constraint.

The conclusions that are currently well supported are:

1. `SimulatedNetworkChannel` is the stronger primary instrument for comparing traversal approaches under a configured
   network because its latency, bandwidth, and in-flight semantics are explicit.
2. The current `PacingInputStream` reasonably approximates long-run bandwidth and deliberately models a
   kernel-buffer-derived `W / RTT` throughput ceiling. It does not model configured first-byte or per-interaction
   latency.
3. A simple injected sleep cannot correct that latency model. A sender-aware coordinator is required.
4. A coordinated socket hybrid can provide reliable application-visible latency in the same JVM, but it still cannot
   make TCP ACK/congestion behavior equivalent to a real path.
5. A relay gives a clearer forwarding schedule but changes one connection into two and must be bounded; making it
   “unbounded” hides backpressure and increases dilution.
6. Keeping simulator and socket modes separately is scientifically meaningful when results are compared within each
   mode and the modes answer different questions.
7. Raw loopback remains valuable for production stack integration, compression, counters, lifecycle, and a
   near-zero-network floor. It is not a realistic network measurement.
8. Removing `PacingInputStream` would not destroy all socket value, but it would largely remove the current branch's
   original local socket-buffer sensitivity experiment.
9. The 5M/10M results correctly show that buffer configuration affects wall-clock only in a binding regime. The lack
   of an effect at `270 us` is expected and does not invalidate the mechanism.
10. Improving burst-level bandwidth behavior alone is not currently justified by evidence. If a hybrid scheduler is
    chosen, latency and bandwidth should be designed together.

Subject to the unresolved requirement below, the most honest near-term architecture is:

```text
Primary comparison:
    SIMULATED_NETWORK

Production control:
    LOOPBACK_SOCKET (raw, no network claim)

Optional retained diagnostic if buffer sensitivity is required:
    WINDOW_PACED_SOCKET (W/RTT and bandwidth model, explicitly not propagation latency)
```

The current socket `REALISTIC` name should not survive unchanged if its semantics remain the current
`PacingInputStream` behavior. Naming, parameter descriptions, logs, README guidance, and result interpretation must
state exactly which timing model is active.

## Unresolved Decision

The decisive product question is:

> Is demonstrating the effect of production `SocketFactory` buffer settings still a required benchmark outcome, or
> was it an investigative path toward the broader traversal comparison?

If **required**, retain a clearly named window-paced socket diagnostic or design the more ambitious coordinated
hybrid. Do not silently reduce the socket path to raw loopback.

If **not required**, the pacer can be removed and raw sockets kept only as a production integration baseline—or the
socket transport can be removed entirely if that secondary value does not justify its maintenance cost.

No implementation plan should be approved before this question is answered.

## Cross-Check Checklist For Another Reviewer Or Agent

A cross-check should independently verify the following:

### Code facts

- `LoopbackSocketTransport` creates one loopback TCP connection.
- It installs one pacer on each raw input only under the current `REALISTIC` profile.
- It passes `2 * configured one-way latency` as the pacer's RTT.
- Each pacer computes `W` from the remote sender buffer plus local receiver buffer.
- The first window opens without configured one-way delay.
- The bandwidth cursor releases bytes before charging their transmission duration.
- Production sync streams place compression and connection counting relative to the raw socket as described.
- `SimulatedNetworkChannel` schedules byte ranges from sender time and makes bytes progressively readable.
- Simulator in-flight capacity is released when the receiving Java stream reads bytes.

### Evidence facts

- The 10M `270 us` buffer configurations are within `3.8%` by mean.
- The 10M 32 KiB / `50 ms` cell is about `2.53x` its control and about `2.61x` the larger-window `50 ms` cells.
- The non-small 10M cells achieved about `11.6 MB/s`, below the configured `25 MB/s` cap.
- Live window and parked-time diagnostics support the `W / RTT` interpretation.
- The 5M result is trend evidence with a larger noise/confound floor.
- Cluster evidence shows low RTT plus application/window-driven queue buildup; it does not directly validate the
  pacer's propagation semantics.

### Reasoning challenges

- Could the coordinated hybrid timestamp blocking socket writes without materially biasing visibility time?
- Would sharing production sync streams across simulator and socket modes remove important confounds?
- Does the traversal comparison depend more on response visibility, sender run-ahead, or bulk throughput?
- Are current cluster artifacts sufficient to justify keeping socket-buffer sensitivity as a first-class outcome?
- What minimum measurements would falsify the claim that fast loopback ACKs are unimportant at the calibrated BDP?
- Is an optional third mode worth its maintenance and user-confusion cost?
- Can names and Gradle defaults make incompatible transport results difficult to misuse?

## Primary Sources

Current branch code:

- [`ReconnectBench.java`](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java)
- [`MerkleBenchmarkUtils.java`](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java)
- [`LoopbackSocketTransport.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java)
- [`PacingInputStream.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/PacingInputStream.java)
- [`SocketNetworkConfig.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SocketNetworkConfig.java)
- [`PacingInputStreamTest.java`](../../platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/PacingInputStreamTest.java)
- [`LoopbackSocketTransportTest.java`](../../platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java)

Related designs:

- [`Traversal-Comparison MVP Design`](ReconnectBench-traversal-comparison-mvp-design.md)
- [`Loopback Socket Transport Design`](ReconnectBench-loopback-socket-transport-design.md)
- [`Socket-Buffer Read-Pacing Design`](ReconnectBench-socket-buffer-read-pacing-design.md)
- [`Read-Pacing 10M Matrix Experiment Design`](2026-07-16-read-pacing-10m-matrix-experiment-design.md)

Evidence:

- [`2026-07-08 Read-Pacing Smoke Matrix`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-08-read-pacing-smoke-matrix.md)
- [`2026-07-16 Read-Pacing 10M Matrix`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md)
- `2026-07-08-socket-buffer-probe.md` is cited by the existing read-pacing design and 5M matrix but is absent from the
  current branch/worktree. Those documents retain summarized probe results; recover the missing raw probe artifact
  before treating its individual measurements as independently verified evidence.
- [`Extracted Cluster Socket Evidence`](../evidence-and-calibration/extracted-cluster-evidence/2026-07-04-cluster-calibration/socket-evidence.md)
- [`2026-07-16 Compression 10M Comparison`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-compression-10m-comparison.md)

Simulator-branch code should be cross-checked directly from Git:

```bash
git show 25083-improve-reconnect-bench-sim-net:platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java
git show 25083-improve-reconnect-bench-sim-net:platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/network/PairedStreams.java
```
