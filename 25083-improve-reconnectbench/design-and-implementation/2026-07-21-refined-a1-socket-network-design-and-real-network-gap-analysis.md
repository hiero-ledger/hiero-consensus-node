# ReconnectBench Refined A1 Socket-Network Design And Real-Network Gap Analysis

Date: `2026-07-21`

Status: `implemented as an uncommitted, falsifiable prototype on the current socket-network branch; correctness and tiny functional smokes pass; the 270 us timing acceptance gate currently fails`

Related documents:

- [`2026-07-20-runtime-network-architecture-analysis.md`](2026-07-20-runtime-network-architecture-analysis.md) records the
  full simulator-versus-socket discussion that led to choosing Option A for further investigation.
- [`ReconnectBench-socket-buffer-read-pacing-design.md`](ReconnectBench-socket-buffer-read-pacing-design.md) specifies
  the former periodic `W / RTT` `PacingInputStream`. Refined A1 has now superseded that timing model and removed that
  class; the older document remains historical evidence for the 5M/10M experiments.
- [`ReconnectBench-loopback-socket-transport-design.md`](ReconnectBench-loopback-socket-transport-design.md) records
  why the benchmark uses one production-configured loopback TCP connection.

## Purpose

This document turns the refined “Option A1” discussion into a concrete design and implementation audit that another
engineer or AI agent can cross-check. It has two equally important jobs:

1. describe the socket-based latency and bandwidth model precisely enough to implement and test; and
2. state what still differs from a real network, even when the implementation works exactly as designed.

The second job is essential. The implemented design can control when reconnect code observes bytes while retaining real
loopback sockets, real socket buffers, production stream buffering, and production compression. It cannot move the
model between the two TCP endpoints. Consequently, it is a useful hybrid experiment, but it is not a transparent
emulation of a remote TCP path.

This document is intentionally self-contained. The intended reader should be able to challenge the design without
reconstructing the preceding conversation.

## Decision Summary

The implemented first experiment is **refined, pure A1**:

- keep one real, full-duplex loopback TCP connection;
- keep sockets configured through production `SocketFactory`;
- keep production `SyncInputStream` and `SyncOutputStream`, including their buffering, compression, and socket-payload
  counters;
- represent the two TCP directions with two independent one-way timing controllers;
- observe compressed socket-payload ranges immediately before they are passed to each raw socket output;
- make the opposite raw socket input eligible to consume only the prefix whose sender-relative one-way latency and
  bandwidth serialization have elapsed;
- leave ineligible payload bytes in the receiving kernel buffer by not reading them;
- let the real OS socket buffers decide how much the sender can write before it blocks;
- do **not** impose a software `W`, a periodic `W / RTT` window, a shadow-credit pool, or initial “tickets.”

In one sentence:

> A1 keeps real loopback TCP as the storage and backpressure path, while a coordinated sender observer and receiver
> gate control when compressed socket payload bytes become visible to reconnect.

The honest benchmark claim would be:

> Production reconnect sync/compression streams over plain loopback TCP configured with production socket-option
> helpers, with a controlled minimum sender-observation-to-visibility delay and payload-bandwidth ceiling.

It must **not** claim:

> TCP behavior equivalent to a real path having the configured latency and bandwidth.

## Fixed Constraints

The design assumes all of the following remain mandatory:

- portable across supported developer and CI hosts;
- unprivileged;
- one JVM;
- runnable as a normal Gradle/JMH task;
- no `tc netem`, dummynet, network namespaces, containers, root setup, or external proxy process;
- no production consensus-node behavior changes;
- benchmark-only implementation under `platform-sdk/swirlds-benchmarks/**`;
- real teacher and learner reconnect implementations remain under measurement.

Those constraints deliberately trade TCP-path fidelity for ease of use and reproducibility.

## Implementation Status And Evidence

The current working tree implements this design entirely in the benchmark module:

- [`SocketVisibilityController.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SocketVisibilityController.java)
  owns each direction's metadata schedule, timing waits, lifecycle, and diagnostics;
- [`ObservedSocketOutputStream.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/ObservedSocketOutputStream.java)
  publishes bounded pre-write ranges and delegates the same payload directly to the raw socket;
- [`ScheduledSocketInputStream.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/ScheduledSocketInputStream.java)
  prevents raw reads before eligibility and preserves one logical read timeout;
- [`LoopbackSocketTransport.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java)
  wires two independent controller pairs below the production sync/compression streams and owns connection-wide
  abort;
- [`SocketVisibilityStats.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SocketVisibilityStats.java)
  separates configured targets, modeled values, serialization backlog, timed-wake lateness,
  observer-to-first-return latency, eligible raw-read time, and target-relative raw-write duration.

The former `PacingInputStream` and its periodic-window tests have been removed. `NetworkProfile` now exposes:

```text
REALISTIC
INSTRUMENTED_LOOPBACK
LOOPBACK
```

As of this update, 50 focused network tests pass. They cover deterministic schedule arithmetic, fractional carry,
clock wrap, latency/bandwidth integration, ping-pong and full-duplex directionality, production compression/counters,
raw and instrumented controls, timeouts, interruption, abort/close wakeups, metadata limits, pass-through target
thresholds, and diagnostics. The benchmark source also compiles and formatting checks pass.

That is implementation-correctness evidence, not experimental acceptance. No raw-loopback versus instrumented
pass-through **calibrated** comparison or traversal matrix has yet established that `675`-byte range splitting is
cheap enough or ranking-neutral. Until those gates pass, refined A1 remains a prototype whose output must be inspected
through its diagnostics.

### Tiny functional smoke result

On the current Darwin `25.5.0` arm64 / Temurin `25.0.2` host, an isolated `1,000`-record, one-iteration JMH smoke ran
all three profiles at the `270 us / 200 Mbit/s` target using temporary data with verification enabled. All three
completed the real reconnect stack and verified the same `1,116` teacher keys. Directional production counters also
matched exactly in every mode:

```text
teacher -> learner: 85,012 written / 85,012 read
learner -> teacher: 75,910 written / 75,910 read
```

The single-shot wall times (`0.237 s` shaped, `0.332 s` instrumented, `0.343 s` raw) are deliberately **not** an
overhead result: this is one tiny cold run, and their ordering itself demonstrates that noise dominates.

The shaped diagnostics did expose an important open acceptance issue. Timed-wake p99 fell in the `<= 524,287 ns`
histogram bucket, maximum lateness was about `303-325 us`, and many sampled releases exceeded `0.25L = 67.5 us`.
Target-relative raw-write serialization-duration violations also exceeded the provisional one-percent byte budget in
this small cold run. Therefore the prototype correctly reports that the documented `270 us` timer/write-duration
acceptance gates do **not** pass on this smoke. A calibrated repeated workload is still needed to separate cold-start
write tails and instrumentation cost, but the timer result must not be hidden or described as accepted in the
meantime.

## Terminology

This document uses the following terms consistently:

- `L`: configured **one-way** latency.
- `RTT`: nominal configured round trip, `2L`, only when describing a complete request/response or comparing with a
  real network. A1 does not use `2L` as a periodic release interval.
- `B`: configured payload bandwidth in bytes per second, independently applied to each direction.
- **observer**: a no-payload-copy output wrapper that records the sequence number and observation time of compressed
  byte ranges passed toward a socket.
- **gate**: the receiver-side raw input wrapper that waits until a range is eligible before reading it from the socket.
- **controller**: the small per-direction metadata scheduler shared by one observer and its opposite gate.
- **visibility**: the instant a raw compressed byte may be returned by the gate to the production input stack.
- **socket payload byte**: a compressed byte passed between the production sync stream and the plain benchmark socket.
  It excludes TCP/IP/link headers and, because the benchmark does not use production TLS, it is not an encrypted
  physical-wire byte.
- **socket admission**: the instant the local OS accepts a byte from Java's output operation. Standard blocking Java
  `OutputStream` does not expose this instant per byte.
- **natural socket capacity**: whatever send/receive capacity and autotuning the current OS actually grants. It is not
  replaced by a configured Java credit number in A1.

“A2” refers to a different family of designs that enforces an explicit software in-flight/window capacity. A1 does
not do that.

## Why The Former `PacingInputStream` Was Replaced

The removed `PacingInputStream` was valuable for the earlier buffer experiments, but it implemented a different
model:

- it reads `W = remote SO_SNDBUF + local SO_RCVBUF`;
- its latency-window mechanism makes up to `W` bytes immediately eligible; with finite bandwidth, the first read is
  free but subsequent reads still obey the release-then-wait bandwidth cursor;
- it opens another release window every `2L`;
- it applies bandwidth as “release a read, then wait before the next read”;
- after an idle period, the next window can again release immediately;
- therefore it models a `W / RTT` throughput ceiling, but not sender-relative first-byte propagation latency.

Refined A1 changes all three central semantics:

| Concern | Current pacer | Refined A1 |
|---|---|---|
| Latency | `2L` is a periodic throughput-window interval | every byte range is eligible relative to when its sender is observed |
| Bandwidth | a whole read is released, then the next read waits | bytes become progressively eligible along one continuous directional serialization timeline |
| Capacity | explicit `W = SO_SNDBUF + SO_RCVBUF` budget per interval | no software capacity; Java writes block only when the actual socket blocks |

This was not a small constructor change. The old class had only receiver-side information, so it could not know when
a particular byte was emitted by its sender. Refined A1 therefore replaced it with coordinated, accurately named
components on both ends of each logical direction.

## Runtime Architecture

### One TCP connection, two one-way controllers

The existing socket transport creates one full-duplex connection:

```text
teacherSocket (client) <============== TCP ==============> learnerSocket (accepted)
```

That connection carries two independent ordered byte streams:

```text
teacher -> learner: controller TL + observer TL + gate TL
learner -> teacher: controller LT + observer LT + gate LT
```

Each controller has its own latency/bandwidth cursor. A large teacher-to-learner transfer therefore does not consume
learner-to-teacher payload bandwidth. That models an ordinary full-duplex link with equal capacity in each direction.

### Proposed stream placement

For teacher-to-learner traffic, the effective stack should be:

```text
TEACHER WRITE SIDE

reconnect / AsyncOutputStream
        |
DataOutputStream
        |
SyncOutputStream
        |
BufferedOutputStream or DeflaterOutputStream
        |
production CountingOutputStream
        |
wire-range observer TL
        |
teacherSocket raw OutputStream
        |
loopback TCP
        |
learnerSocket raw InputStream
        |
receiver gate TL
        |
production CountingInputStream
        |
BufferedInputStream or InflaterInputStream
        |
SyncInputStream / DataInputStream
        |
learner reconnect code
```

The learner-to-teacher direction is the mirror image with controller LT.

This placement is deliberate:

- the observer and gate see **compressed socket payload bytes**, not decompressed objects;
- the production output and input counters retain their compressed socket-payload meaning;
- production buffering and compression still decide when bytes reach the transport boundary;
- the observer does not pace or copy payload and therefore does not intentionally starve the socket;
- the gate is the only component that reads the raw socket, so an ineligible byte remains in the kernel receive buffer;
- no relay or second TCP connection is introduced.

### Why an output observer is needed even though shaping happens on input

An input-only wrapper knows only when its local application calls `read()`. It cannot distinguish these cases:

```text
case 1: byte was sent 100 ms ago and is already late
case 2: byte was sent just now and must still wait L
```

Sleeping `L` on every receiver read would make latency depend on read-call boundaries, buffering, decompression, and
application behavior. It could delay one logical message many times or allow a large buffered read to receive only one
delay.

The output observer supplies the missing sender-relative time and stream position. The input gate still performs the
actual withholding because that keeps ineligible bytes in the real receive buffer.

## Exact Timing Model

### Directional byte sequence

Each controller numbers compressed bytes monotonically from zero. An observed output range records:

```text
[startOffset, endOffset)
observedAt
serializationStart
serializationEnd
```

The corresponding input gate consumes those offsets in exactly the same order. TCP already preserves byte order, so
the controller needs metadata only; it does not copy or compare the payload in the normal path.

### Serialization schedule

For range `i` containing `n_i` bytes:

```text
s_i = time the output observer sees the bounded range
p_i = max(s_i, serializationEnd_(i-1))
e_i = p_i + serializationDuration(n_i, B)
```

Conceptually, for finite `B`:

```text
serializationDuration(n, B) = n / B seconds
```

For unlimited bandwidth, the duration is zero.

The `j`th byte of the range, where `1 <= j <= n_i`, becomes eligible at approximately:

```text
eligible_i(j) = p_i + L + ceil(j * 1_000_000_000 / B)
```

For unlimited bandwidth, the whole range becomes eligible at `p_i + L`.

This has the intended properties:

- after an idle period, a newly observed byte still waits one-way `L`;
- adjacent writes share one continuous bandwidth cursor instead of each getting a fresh burst;
- a large write becomes progressively readable rather than appearing all at once;
- the final range does not require an unnecessary full-range post-delay after its last byte is already eligible;
- write-call boundaries do not reset bandwidth.

`B` controls the growth of the **eligible/arrived prefix**, not the speed at which an application drains bytes that
became eligible while it was busy elsewhere. A receiver that falls behind may later read an accumulated eligible
backlog faster than `B`, just as an application can drain an already-filled real receive buffer faster than the link
rate. With an eager reader and continuous supply, long-run visibility converges toward `B`.

The implementation must carry fractional serialization remainder across range boundaries, or derive deadlines from a
cumulative byte position. Independently rounding every range upward would add a tiny range-count-dependent drift and
would make the strict write-boundary-independence claim false.

Java cannot wake and read once per byte: at `25 MB/s`, one byte serializes every `40 ns`. The implementation therefore
needs an explicit release quantum. The **algorithmic** coalescing delay should provisionally be no more than `50 us`
and, when `L > 0`, no more than `0.10 * L`; at `L = 270 us` and `B = 25 MB/s`, that is `27 us`, or about `675 bytes`.
This provisional budget must be confirmed against measured CPU overhead before implementation is accepted. The gate
may return a larger prefix when scheduler lateness has already made more bytes eligible, but it must record the planned
quantum, actual raw-read sizes, wake count, and release lateness. There is no free first chunk.

### Receiver-gate algorithm

For `read(byte[], off, len)`, the gate should conceptually:

1. validate the request and return `0` only for `len == 0`;
2. wait until range metadata exists, cleanup begins, or the connection aborts;
3. compute how many bytes at the head of the stream are currently eligible;
4. if none are eligible, wait until the next eligibility time or until signaled by close/abort/interruption;
5. clamp the raw socket read to the eligible prefix and caller length;
6. perform the raw read without holding the controller lock;
7. advance the range offset by the number actually read;
8. return at least one byte or an `IOException` on abort/unexpected EOF; successful reconnect termination is the
   scheduled in-band `-1` frame, not a modeled FIN.

The crucial rule is:

> The gate must not call the underlying socket read for an ineligible payload byte.

That rule is what preserves real receive-buffer occupancy until modeled application visibility.

The no-argument `read()` must delegate through the same gate. `skip()` must not bypass pacing; it should either consume
through the gated path or be explicitly unsupported. `available()` may report only the intersection of scheduled
eligibility and raw availability, and it must remain advisory. `mark/reset` should not manufacture a second view of
already-consumed socket data.

### Output-observer algorithm

The minimum portable implementation should:

1. divide a raw delegate write into ranges bounded by an explicit observation-error budget;
2. reserve/publish each range's sequence metadata before invoking an operation that may block indefinitely;
3. immediately delegate the same bytes to the raw socket without applying latency or bandwidth sleeps;
4. record the delegate write duration and completion/failure;
5. invoke the transport's connection-wide abort on an ambiguous partial-write failure.

As a provisional starting point for finite `B`, use the same time budget `Q` as the release quantum and cap a raw
delegate range at `max(1, floor(B * Q))` bytes. At `270 us / 25 MB/s`, this is about `675 bytes`. For unlimited `B`,
start with an `8 KiB` cap and judge it against the latency/write-duration diagnostics. These caps are deliberately
falsifiable: splitting production's buffered writes changes syscall granularity, so the instrumented pass-through
control must show whether the accuracy gain costs too much CPU or changes traversal behavior.

Publishing bounded metadata before a potentially blocking write prevents this deadlock:

```text
writer waits for receiver to drain socket capacity
receiver waits for metadata before it is allowed to drain
```

It creates a known timestamp approximation: the observation time is the wrapper's handoff time, not the exact
per-byte kernel-admission time. The gate also waits for the raw byte, so it cannot return data that the socket does not
contain. However, if a bounded raw write blocks for a long time, a late-admitted byte may have little or none of its
intended `L` **or scheduled serialization time** left when it finally becomes readable. Acceptance must compare the
delegate duration with both `L` and the range's `n / B` duration. This is a measured fidelity condition, not something
to hide.

The first implementation should therefore record raw-write blocked time. If it is material, a more exact A1 variant
can use channel-backed sockets and publish ranges after each successful partial `SocketChannel.write()`. A successful
channel write still means only **local send-buffer admission**, not TCP transmission or remote arrival; bytes can wait
behind `rwnd` or `cwnd`, so this removes the pre-call timing error without closing the TCP-control gap.

Both endpoints would have to be created as channel-backed sockets. Blocking channel writes can still hide time inside
a call. Nonblocking writes expose clearer accepted prefixes, but nonblocking mode applies to the whole channel and
cannot be combined with the current blocking socket streams; selector-aware input and output adapters would be
required. This remains A1 as long as it adds no delayed software credit, but it is substantially more than replacing
one output wrapper and changes syscall/selector behavior. Diagnostics should justify it.

### Timing arithmetic

The controller should use one monotonic `System.nanoTime()` timebase shared by both endpoints. All deadline comparisons
must use differences because `nanoTime()` has an arbitrary origin. Duration addition and byte-to-nanosecond conversion
must handle overflow explicitly.

Testing should separate pure schedule arithmetic from real sleeping. A package-private clock/wait abstraction is
appropriate for deterministic schedule tests; end-to-end timing tests should use broad tolerances and report actual
lateness.

`L` is a **minimum added delay from observer timestamp to eligibility**, not a promise that the measured end-to-end
latency equals exactly `L`. Production buffering before the observer, local TCP work, raw-byte availability, gate
execution, and scheduler overshoot can all make actual visibility later. If `270 us` was derived from half of a
measured cluster RTT, those endpoint costs may already be included in that measurement; A1 adds its local costs on top.
The run must report both configured `L` and measured observer-to-visibility latency.

## Why Two Gates Produce `2L`, Not `4L`

There are two gates because there are two directions, not because one direction pays two fixed delays.

Ignoring processing and serialization for a small request/response:

```text
t = 0     teacher observer records request
t = L     learner gate exposes request
t = L     learner writes response
t = 2L    teacher gate exposes response
```

Thus:

```text
teacher -> learner  = L
learner -> teacher  = L
application round trip = 2L
```

The teacher's input gate never touches teacher-to-learner bytes, and the learner's input gate never touches
learner-to-teacher bytes. Full-duplex transfers can progress simultaneously.

A separate design discussed delaying **sender credit** for another `L`. Such a delay is not another propagation sleep
on every message. It affects a direction only when that sender has exhausted its permitted outstanding capacity. The
primary A1 design in this document does not add that credit mechanism at all.

## Natural Socket Buffers And Why A1 Has No Initial Tickets

### What remains real

The output observer does not wait for the modeled link and the controller does not grant permission to write. Java
passes bytes to the socket until the actual OS refuses further progress or blocks the writer. The input gate withholds
raw reads until bytes are eligible, so the real receive buffer can fill. Consequently, the following remain genuine
properties of the local host:

- `SocketFactory` option application;
- OS clamping and accounting of requested `SO_SNDBUF` and `SO_RCVBUF` values;
- send- and receive-buffer occupancy;
- natural blocking of Java socket writes;
- local advertised receive-window behavior;
- local autotuning behavior;
- real loopback TCP ordering, copying, close, and error paths.

These properties are genuine loopback behavior, not necessarily remote-network behavior.

### Why no capacity number is configured

An explicit shadow-credit controller must answer:

> How many bytes may the virtual sender emit before its first virtual acknowledgment/window update returns?

That answer becomes an initial ticket count such as `C`. Choosing `C` from
`SO_SNDBUF + SO_RCVBUF` would replace part of the socket's natural decision with a software approximation and conflate
several different TCP mechanisms.

Pure A1 avoids the question. It begins with no software ledger:

```text
initial writable capacity = whatever this socket and OS actually accept
```

The benchmark observes resulting write stalls and effective option values. It does not convert those values into a
virtual flight window.

Controller metadata must not accidentally become a hidden capacity model. It should contain compact range metadata,
not payload copies. Adjacent compatible ranges may be coalesced. A safety limit may fail the benchmark loudly if
metadata grows unexpectedly, but waiting for that safety limit would introduce new backpressure and should not happen
in a valid run.

### What is deliberately given up

By leaving capacity entirely to loopback TCP, A1 also accepts that TCP acknowledgments and advertised-window updates
return at loopback speed. A configured `RTT = 2L` does **not** govern real TCP feedback. This is the most important
remaining difference from a real high-latency connection.

## Saturated-Flow Timeline: Target Semantics And The Main Remaining Gap

The distinction is easiest to see with an idealized, window-limited direction that ignores serialization, delayed ACK,
receiver silly-window avoidance, and already queued sender-buffer data. Let `C` mean “enough real socket capacity to
make the writer stall,” without claiming that `C` is literally `SO_SNDBUF + SO_RCVBUF`.

### Refined A1 when observation closely tracks admission

```text
t = 0       loopback TCP quickly places initial bytes in the receiver kernel
t = L       A1 gate may read the eligible initial bytes
t = L       reading frees receive-buffer space
t ~= L      any window update crosses loopback; observer sees a newly accepted replacement range
t = 2L      that replacement range becomes application-visible after its own sender-relative L
```

### Simplified real path

```text
t = 0       sender begins sending initial bytes
t = L       initial bytes arrive and can become application-visible
t = L       receiver consumption frees receive-window space
t = 2L      window update reaches sender across the reverse path
t = 2L      sender can transmit replacement bytes
t = 3L      replacement bytes reach the receiving application
```

When observation closely tracks admission, the sender-aware forward schedule fixes an important problem: replacement
bytes do not become visible immediately at `t ~= L`; a newly observed range receives its own forward `L` and appears
around `2L`.

But A1 does **not** delay the return of receive-window credit. In a capacity-bound regime, replacement visibility can
therefore still be about one `L` earlier than in the simplified real path. In this toy model, the respective ceilings
are approximately `min(B, C / L)` and `min(B, C / (2L))`. Actual TCP is more complex, so this is an illustrative
contrast, not a bound or prediction.

### Counterexample with pre-write observation

The minimum blocking-`OutputStream` observer does not guarantee the `t = 2L` line above:

```text
t = 0       observer publishes metadata for a bounded range, then its raw write blocks
t = L       earlier eligible data is read and socket capacity opens
t ~= L      the blocked range is finally admitted, but its deadline from t=0 has already elapsed
t ~= L      the gate may expose it as soon as the raw byte appears
```

Range bounding limits how much data is affected, but not how long one bounded call can block. Progressive bandwidth
may also have elapsed during the stall. Therefore the guaranteed first-version statement is only:

> A byte is not visible before its **observer-relative** schedule and before the raw socket actually supplies it.

If “a newly socket-admitted replacement range must still wait approximately `L`” is a required acceptance property,
accepted-prefix `SocketChannel` publication becomes mandatory. Even that timestamps local admission rather than real
TCP emission, so it narrows this error without making A1 a wire emulator.

This and the loopback-fast reverse feedback are why the old high-RTT buffer matrix cannot be an exact correctness
condition for refined A1. The old pacer explicitly enforced approximately `W / (2L)`; refined A1 intentionally removes
that software rule.

## What Refined A1 Improves

Compared with the former `PacingInputStream`, refined A1 fixes or materially improves:

1. **First-byte latency.** A byte observed after an idle interval must still wait sender-relative `L`.
2. **Request/response latency.** An uncongested request and response cross two independently delayed directions and
   therefore take approximately `2L`, plus production flush/processing and scheduler error.
3. **Progressive bandwidth.** Eligibility grows with serialized byte position rather than releasing a whole caller
   read and charging it afterward.
4. **Write-boundary independence.** One continuous serialization cursor spans writes.
5. **Natural capacity ownership.** No sum of socket option values is treated as the TCP flight window.
6. **Production stream fidelity.** Timing applies to compressed socket payload below the production stream wrappers.
7. **Kernel receive occupancy.** Ineligible data remains unread in the real kernel buffer.
8. **Scientific labeling.** Latency/bandwidth claims are limited to reconnect-visible bytes, not TCP internals.

It is therefore substantially closer than the current pacer to `SimulatedNetworkChannel` at the **application byte
visibility boundary**, while retaining a real socket path.

## Known Real-Network Gaps

### Root architectural difference

On a real network, path effects occur between the TCP implementations:

```text
sender application -> sender TCP -> [real L, B, queues, loss] -> receiver TCP -> receiver application
```

In A1, loopback TCP completes before the timing gate:

```text
sender application -> observer -> sender TCP -> loopback -> receiver TCP -> [A1 L, B gate] -> receiver application
```

Under the fixed constraints, direct Java socket streams provide no insertion point after the sending TCP stack and
before the receiving TCP stack. A relay would create two TCP connections, while kernel shaping would require external
OS-specific setup. The sections below analyze the consequences rather than repeating that boundary as a proposed fix.

### Gap 1: TCP acknowledgments are loopback-fast

TCP acknowledges sequence data when the receiving TCP implementation accepts it, not when Java later reads it. A1's
data reaches the receiving kernel over loopback before its modeled application-visibility time, so data ACKs return
far earlier than they would across configured `L`.

Consequences can include:

- the sender's acknowledged sequence advances early;
- send-buffer storage can be reclaimed early;
- the congestion window can grow much faster than on a real path;
- the sender can queue farther ahead;
- delayed-ACK cadence is the local loopback cadence, not the configured path cadence;
- slow start and sender pacing advance through loopback-scale feedback rounds;
- RTT/RTO estimation reflects loopback rather than the configured path;
- zero-window probes/persist behavior, if reached, use local timers and feedback;
- application-output queue occupancy and writer blocking differ from remote TCP.

This gap is usually secondary for a long, healthy, low-RTT transfer whose socket/window capacity is much larger than
the bandwidth-delay product. It is first-order for startup, short transfers, high RTT, small windows, or loss-driven
congestion behavior. If `TCP_NODELAY` is ever disabled, local Nagle/delayed-ACK interaction is another difference;
the benchmark's normal production-derived configuration currently avoids that conditional case.

### Gap 2: receive-window updates return over loopback

When the gate actually reads eligible bytes, it consumes data from the real receive queue and frees buffer space. The
OS may batch or delay an advertised-window update; it is not correct to say every Java read immediately sends one.
However, whenever TCP does advertise the larger window, that feedback crosses loopback rather than the configured
reverse `L`.

This produces the saturated-flow discrepancy shown above. It is the main reason a high-RTT small-buffer result can be
optimistic relative to a real path.

Adding delayed sender credits could reproduce this application-level backpressure, but it would be an explicit
software flow-control model with an initial-capacity problem. It is intentionally outside pure A1.

### Gap 3: bytes occupy the receiver kernel too early

On a real path, bytes spend `L` in flight before entering the receiving TCP queue. In A1, they enter that queue at
loopback speed and then wait there for gate eligibility.

This can make the receive buffer fill and close its window earlier than on a real path. It also gives receive-buffer
autotuning a different occupancy and drain history. This effect points in the opposite direction from early ACK and
window feedback, so exact writer-block behavior cannot be derived from a single `C / RTT` formula.

The useful statement is narrower: A1 exercises the real host's buffers under a repeatable withheld-read workload. It
does not reproduce the exact buffer timeline of a remote path.

### Gap 4: configured bandwidth is not TCP-path bandwidth

The gate controls how quickly reconnect can consume compressed payload. The actual loopback TCP transfer into the
receiver kernel can occur in bursts much faster than `B`.

Therefore `B` does not govern:

- actual TCP segment spacing;
- ACK spacing;
- congestion-window evolution;
- sender-kernel queue drain;
- NIC or link queue occupancy;
- reverse-direction ACK bandwidth.

At the reconnect byte-stream boundary, a validated progressive gate can prevent the continuously scheduled
**eligible prefix** from growing faster than `B`. An application may drain an already eligible backlog faster than
`B`; an eager reader approaches `B` only while raw bytes are continuously available and scheduler lateness is
acceptable. That is a real and useful property, but it should be called an application-visible payload-eligibility
bandwidth ceiling.

### Gap 5: observer time is not exact kernel transmission time

With ordinary blocking `OutputStream`, the wrapper can observe a range before calling the socket, or after a complete
write returns. It cannot portably learn when each byte was admitted into the local send buffer or emitted by TCP.

Pre-write bounded publication avoids deadlock but can start a range's virtual clock too early when the socket write
blocks. Post-write publication can withhold metadata needed by the receiver to drain the socket and unblock that same
write.

Mitigations are:

- keep ranges bounded;
- never hold controller locks across socket I/O;
- record per-range raw-write duration;
- record raw-read waits that occur after a range was already eligible;
- use `actual visibility = max(scheduled eligibility, raw-byte availability)`;
- if diagnostics show material error, investigate a channel-backed partial-admission observer.

This gap must be treated as an acceptance criterion, not only an implementation detail.

### Gap 6: socket buffer options are not TCP state variables

Java documents `SO_SNDBUF` and `SO_RCVBUF` as platform buffer settings/hints. `SO_RCVBUF` influences the advertised
receive window, but getters do not reveal current occupancy, current advertised `rwnd`, current `cwnd`, bytes in
flight, or usable send window.

OSes also differ in doubling, clamping, bookkeeping, autotuning, and inherited accepted-socket behavior. Thus:

- a requested `32 KiB` is not necessarily an effective `32 KiB` queue;
- `SO_SNDBUF + SO_RCVBUF` is not literally a TCP window;
- a result comparing buffer settings is host/kernel specific;
- readback values and write stalls should be reported, not silently normalized.

Pure A1 improves honesty by no longer treating the sum as a modeled capacity, but OS dependence remains part of the
experiment.

### Gap 7: timer and execution granularity

`System.nanoTime()` provides one useful monotonic clock for both same-JVM endpoints. The scheduler still has:

- `parkNanos()` overshoot;
- OS scheduling latency;
- JIT warmup;
- GC pauses;
- lock handoff latency;
- minimum practical read/chunk sizes.

At `L = 270 us`, those costs can be a meaningful fraction of the requested delay. A scheduler that is always late is
conservative for latency, but excessive or input-dependent lateness can change traversal behavior and benchmark
rankings.

The benchmark should report scheduled-versus-actual release lateness. If lateness is comparable to `L`, it cannot
claim precise `270 us` timing even though it still prevents early visibility.

The release quantum is a separate algorithmic error. An `8 KiB` quantum takes about `327.68 us` to serialize at
`25 MB/s`, already exceeding the calibrated `270 us` latency. The provisional quantum budget in the timing model must
therefore be implemented and measured rather than leaving the gate's chunk size to caller behavior.

### Gap 8: both endpoints share one JVM and host

Teacher, learner, both timing controllers, both TCP endpoints in one kernel network stack, storage activity, and the
benchmark harness share CPU, caches, memory bandwidth, scheduler, global socket-memory pressure, networking timers,
loopback routing, and GC. A stop-the-world pause delays both endpoints and the timing mechanism together. Real nodes
normally have independent processes, kernels, clocks, pauses, and resource contention.

This affects both socket and in-memory simulator modes. It is particularly relevant when compression or database work
dominates the nominal network delay.

### Gap 9: no physical packet/link details

A1 schedules compressed TCP payload bytes. It does not reproduce:

- path MTU and MSS negotiation effects beyond local loopback defaults;
- TCP/IP/Ethernet header overhead;
- segmentation, coalescing, checksum offload, or NIC interrupt behavior;
- router queues;
- physical line-rate versus payload-goodput conversion.

Consequently, `200 Mbit/s` configured payload bandwidth means `25 MB/s` of compressed application payload in the
model. It is not a claim that a physical 200 Mbit/s Ethernet path would deliver exactly 25 MB/s of TCP payload.

### Gap 10: no loss, jitter, reordering, retransmission, ECN, or congestion

The first A1 design is a deterministic, ordered, lossless baseline. That is reasonable if the target question is a
healthy cluster path. It does not predict degraded or congested paths, where TCP converts packet loss/reordering into
head-of-line stalls, retransmission delays, and congestion-window reduction.

Seeded visibility jitter could be added later without exposing out-of-order bytes, because TCP applications still see
an ordered stream. Doing so would remain an application-level model; it would not cause real TCP retransmissions or
congestion control.

A1 also has no finite virtual bottleneck queue, drop policy, or ECN threshold. When production exceeds `B`, virtual
queue residence is represented only by the serialization cursor while payload storage and blocking come from host
stream/socket buffers. This is not equivalent to a router or NIC queue. Maximum scheduled-backlog bytes and time must
be reported so a long virtual queue cannot remain invisible.

### Gap 11: directional payload capacities ignore ACK competition

Two independent `B` schedulers model a clean full-duplex link with the same payload capacity in each direction. This
is reasonable for ordinary switched full-duplex networking. It does not model:

- TCP ACK packets consuming reverse capacity;
- asymmetric upload/download limits;
- a shared bottleneck queue;
- cross-traffic;
- half-duplex contention.

Separate directional bandwidth parameters or a shared application-payload token bucket are possible future models,
but neither would move real ACKs into the virtual schedule.

### Gap 12: connection setup and termination are not path-shaped by default

The TCP three-way handshake currently completes at loopback speed before reconnect begins. A raw socket close or FIN
also travels through loopback TCP, not configured `L`.

If connection setup is outside the measured benchmark interval, handshake timing does not distort reconnect wall
clock, but the connection still begins with loopback-initialized RTT and congestion state. A fresh real connection can
need several high-RTT slow-start rounds that A1 compresses into loopback-scale feedback rounds.

Successful reconnect does not terminate by closing one socket direction. `AsyncOutputStream` writes an in-band `-1`
frame and flushes; that frame is scheduled like every other payload byte. In the first A1 version, socket close is
cleanup or connection-wide abort, and FIN propagation is not modeled. Graceful half-close would require explicit
`Socket.shutdownOutput()` coordination and is outside the first implementation.

### Gap 13: socket timeout semantics require explicit handling

The gate's wait happens before the underlying `SocketInputStream.read()`. The socket's `SO_TIMEOUT` therefore measures
only the subsequent kernel read, not the time already spent in the A1 gate. A real blocking socket read would include
network transit in its elapsed wait.

The first implementation should preserve one combined logical deadline. At entry to every positive-length gate read,
compute a deadline from `timeoutSyncClientSocket`. Metadata waits, eligibility waits, and the subsequent raw read all
consume it. Before the raw read, apply the remaining socket timeout, conservatively rounded to milliseconds; throw
`SocketTimeoutException` if the logical deadline has already elapsed. A configured infinite timeout remains infinite.
There is one input reader per socket, so this does not compete with another reader.

Large-`L`, constrained-buffer, disconnect-race, and async input/output queue timeout tests must verify the combined
behavior. The implementation must not silently extend the timeout merely because the gate waits outside the kernel.

### Gap 14: production buffering and flush delay remain visible

The production output stack may retain bytes before the observer sees them. Virtual-map `AsyncOutputStream` defaults
to an `8 ms` flush interval, and `SyncOutputStream` adds buffering or compression. Therefore a sparse request can have:

```text
observed application response time
    = async/application queueing
    + production buffer/flush delay
    + modeled A1 latency and serialization
    + scheduler lateness
    + peer processing
```

This is not necessarily a defect: retaining production behavior is one purpose of socket mode. It does mean a
full-stack ping-pong cannot by itself prove the gate applied exactly `2L`. Controller-level tests and wire-observer
timestamps must be analyzed separately.

### Gap 15: production TLS is absent

The benchmark uses plain `Socket`/`ServerSocket` objects configured through production socket-option helpers.
Production gossip creates `SSLSocket` connections through `TlsFactory`. Therefore A1 preserves the production
sync/compression stack but not the full production network stack.

The observer schedules compressed plaintext socket payload. It does not include TLS record framing, encryption
overhead, handshake behavior, encrypted byte counts, or TLS buffering/copy/CPU costs. This omission does not invalidate
same-mode A1 traversal comparisons, but it prevents physical-wire byte calibration and any claim that the complete
production connection stack is measured.

## Gap Severity By Intended Use

| Intended question | Confidence after validation | Main qualification |
|---|---|---|
| Does reconnect use the production sync/compression stack over production-option-configured plain TCP correctly? | high | excludes production TLS and remote-host integration |
| Does the compressed reconnect-byte eligible prefix follow the observer-relative `L`/`B` schedule? | high at the modeled byte boundary, conditional | requires the release-quantum, pass-through-overhead, scheduler, and timestamp criteria to pass |
| Which traversal is faster under the same A1 settings on the same host? | medium-high, conditional | strongest when rankings are stable, timing is material, and instrumentation controls pass |
| Does a local `SocketFactory` buffer setting alter this loopback-withheld-read workload? | medium | valid host-specific observation; cause is not identical to remote TCP |
| Will a healthy low-RTT cluster preserve the same traversal ranking? | medium | requires calibration/cross-check against cluster evidence |
| What exact reconnect time will a real 50 ms one-way TCP path have? | low | ACK/window feedback and congestion state are not delayed |
| Is a socket buffer or TCP tuning value optimal for production WAN conditions? | low | A1 does not reproduce configured-RTT TCP control behavior |
| How does reconnect behave under loss, congestion, or jitter? | unsupported by the first design | those effects are absent |

## Applying Existing 5M And 10M Evidence

The former `PacingInputStream` produced useful evidence. The earlier 5M smoke matrix was noisy but showed the first
ordered high-latency signal:

| Configuration | `270 us` one-way, 5M summary | `50 ms` one-way, 5M median |
|---|---:|---:|
| unset/autotuned | warm about `50.8 s` | `69.1 s` |
| requested `32 KiB` | mean about `53.6 s` | `94.9 s` |
| requested `1 MiB` | mean about `40.1 s` | `54.6 s` |

Its control leg had a large cold/noise confound. The `50 ms` ordering was `32 KiB > unset > 1 MiB`, but the
unset-versus-1-MiB separation did not reproduce in the stronger 10M matrix:

| Configuration | `270 us` one-way, 10M mean | `50 ms` one-way, 10M mean |
|---|---:|---:|
| unset/autotuned | `75.824 s` | `78.610 s` |
| requested `32 KiB` | `78.264 s` | `197.362 s` |
| requested `1 MiB` | `78.702 s` | `75.686 s` |

At `270 us`, the largest mean spread was `3.8%`. At `50 ms`, the requested-32-KiB **mean** took `2.51x` as long as
unset and `2.61x` as long as requested 1 MiB. The corresponding median ratios were about `2.61x` against both.

Those results establish that:

- withholding reads can engage real socket-buffer backpressure;
- a deliberately imposed `W / RTT` ceiling can make a small effective window visible in reconnect wall clock;
- socket configuration and OS autotuning are meaningful experimental factors.

Separately, the
[`2026-07-16 compression comparison`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-compression-10m-comparison.md)
showed that production sync-stream compression materially changes both counted socket payload and wall clock. The
read-pacing matrices themselves did not compare compression modes.

They do **not** establish that refined A1 will reproduce the same matrix. The old pacer enforced one `W` every `2L`.
The refined design removes that rule and lets window feedback return over loopback. The old results are motivation and
a regression comparison, not a pass/fail oracle.

At the calibrated `200 Mbit/s` (`25 MB/s`) bandwidth:

```text
L = 270 us, RTT = 540 us: RTT bandwidth-delay product ~= 13.5 KB
L = 50 ms,  RTT = 100 ms: RTT bandwidth-delay product = 2.5 MB
```

The old experiment's socket-option readbacks and the local stall-probe results reported in the earlier read-pacing
design were much larger than `13.5 KB`; the standalone probe artifact referenced by that design is currently absent
from this branch. The available evidence is encouraging for the long `270 us` run, but it is not a measurement of
current `cwnd`, `rwnd`, or flight size and does not prove that fast TCP feedback is irrelevant. Refined A1 must confirm
the condition with its raw-write-stall diagnostics. At `50 ms`, the small-buffer readbacks were well below `2.5 MB`,
so the missing reverse-path feedback delay is first-order. This yields a practical expectation:

- refined A1 is most defensible for the low-latency, lossless target profile;
- the `50 ms` matrix remains a useful stress/diagnostic run, but not proof of real high-RTT TCP accuracy;
- refined A1 may preserve the qualitative ordering “small requested buffers can be slower,” but the size of the gap
  may shrink, disappear, or change with the OS. None of those outcomes alone proves the implementation wrong.

## Implementation Responsibilities

The implementation uses the class mapping below.

### Per-direction controller

`SocketVisibilityController` owns:

- sequence offsets;
- pending range metadata;
- the directional serialization cursor;
- latency and bandwidth configuration;
- close/abort state;
- wait/notify coordination;
- timing and queue diagnostics.

It must not own payload bytes or a configurable in-flight credit count.

### Output observer

`ObservedSocketOutputStream`:

- sit directly above the raw socket output and below production counting/compression;
- preserve `OutputStream` ordering and blocking/error semantics;
- publish bounded metadata without latency/bandwidth sleeping;
- never retain an unbounded payload copy;
- record delegate-write duration;
- preserve one raw-writer-per-direction behavior;
- serialize metadata reservation and raw delegation in socket-byte order with a private write-lifecycle lock;
- route failure to the transport-owned connection abort without waiting for that write lock;
- avoid a controller lock across raw I/O.

The current reconnect `AsyncOutputStream` has one background raw writer per direction; multiple producers enqueue
messages rather than writing the socket concurrently. The observer must enforce or assert that invariant so metadata
order cannot diverge from TCP byte order.

### Input gate

`ScheduledSocketInputStream`:

- sit directly on the raw socket input and below production counting/decompression;
- gate all consuming read paths;
- clamp reads to eligible bytes;
- preserve blocking `InputStream` behavior and never return zero for a positive request;
- treat the normal in-band `-1` reconnect frame like ordinary scheduled payload;
- apply one timeout deadline across metadata, eligibility, and raw-read waits;
- wake condition/timer waits on interruption and rely on transport-wide socket closure for blocked raw I/O;
- expose timing and raw-read-wait diagnostics;
- avoid a controller lock across raw I/O.

### Loopback transport wiring

[`LoopbackSocketTransport.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java)
creates two controllers and installs each observer/gate pair around the appropriate raw socket streams before calling
the production sync-stream factories.

It must also own one idempotent connection-abort operation. Observer failure, gate failure, metadata overflow,
timeout-driven cancellation, and `disconnect()` all use that operation. It atomically marks and signals both
directional controllers, then closes both connected sockets. Controller signals wake metadata/timer waits; physical
socket closure wakes threads already blocked in classic `SocketInputStream` or `SocketOutputStream` operations. The
abort path must bypass observer write locks.

No caller may invoke transport abort while holding a directional controller lock. It first records the local failure,
releases that lock, and then requests connection abort. A connection-state compare-and-set elects at most one aborting
thread; that thread signals the two controllers in a fixed order and closes the sockets. Concurrent callers observe
the aborted state and return without acquiring the other direction's lock. This prevents simultaneous TL/LT failures
from creating a controller-lock inversion.

The raw `LOOPBACK` profile remains completely unshaped and instantiates no timing components. It is the true
socket-floor control. `INSTRUMENTED_LOOPBACK` instantiates the same observer, gate, target-derived range splitting,
locks, and metadata with modeled `L = 0` and unlimited modeled `B`; it retains the configured target for granularity
and write-duration diagnostics. Comparing the raw floor with this pass-through isolates plumbing overhead; comparing
the pass-through with shaped A1 isolates the additional timing effect.

## Concurrency, Lifecycle, And Edge Cases

The implementation must preserve these invariants:

1. exactly one controller per direction;
2. byte offsets are assigned and consumed monotonically;
3. no ineligible raw payload is consumed;
4. no controller lock is held during socket read, socket write, or a long timed wait;
5. observer metadata is visible before a possibly blocking write needs the receiver to drain;
6. metadata reservation and raw output delegation have identical byte order;
7. controller timing state remains directional, but every I/O failure is a connection-wide event;
8. an idempotent transport abort atomically signals both controllers and closes both connected sockets;
9. controller signals wake metadata/timer waits, while socket closure wakes classic raw reads and writes;
10. abort never waits for the observer's private write lock;
11. successful reconnect termination remains the scheduled in-band `-1` frame rather than modeled socket EOF;
12. one direction can make progress while the other is healthy, idle, or timing-blocked;
13. timeouts use one deadline across gate and raw-socket waits;
14. interruption directly wakes controller waits, but raw-I/O cancellation does not rely on interruption alone;
15. metadata growth is bounded by a fail-fast safety assertion and monitored.

If metadata reservation would exceed its high-water limit, the transport must atomically abort and throw **before**
delegating that range. It must never wait for metadata capacity, because that would silently become an A2-style
backpressure mechanism. Report maximum pending ranges and bytes. Coalescing is allowed only when it preserves the
eligibility schedule.

The benchmark cancellation path must call `disconnect()` before waiting for worker termination. In the current
`MerkleBenchmarkUtils`, an interruption catch outside the `StandardWorkGroup` try-with-resources runs only after
`StandardWorkGroup.close()` has already waited; implementation must move interruption handling around `join()` so it
disconnects the streams before resource close waits for workers. Closing one logical direction is insufficient because
both directions share one full-duplex TCP connection. Normal transport/wrapper `close()` must signal controller
cleanup before it closes any raw stream.

Important cases to test explicitly:

- zero-length writes and reads;
- single-byte reads;
- large `readFully` operations;
- compression enabled and disabled;
- successful in-band `-1` termination followed by cleanup;
- socket close before scheduled eligibility;
- interruption while waiting for time;
- interruption or close during raw read/write;
- simultaneous failures in both directions, verifying fixed abort lock ordering;
- write failure after metadata reservation;
- finite bandwidth with zero latency;
- finite latency with unlimited bandwidth;
- both values effectively disabled;
- long idle interval followed by one byte;
- both directions saturated simultaneously;
- `nanoTime` arithmetic near overflow boundaries using a fake clock.

## Diagnostics Required To Trust A Run

At minimum, record per direction:

- configured `L` and `B`;
- compressed bytes observed, scheduled, and returned;
- range count, range-size distribution, and maximum pending metadata ranges and bytes;
- maximum scheduled serialization backlog in bytes and time;
- configured algorithmic release quantum, actual raw-read-size distribution, and timing-wake count;
- time spent waiting for latency eligibility;
- time spent waiting for bandwidth eligibility;
- timed-wake scheduled-versus-actual release lateness, including sample count, maximum, and distribution; application
  reads that begin after eligibility are not mislabeled as scheduler lateness;
- observer-to-first-raw-return latency per consumed range, including sample count, maximum, and distribution;
- raw input wait after a byte was already scheduled as eligible;
- raw output delegate duration, including bytes exceeding the configured target's quarter-latency and range
  serialization-duration thresholds, even in instrumented pass-through mode;
- initial and end-of-run effective socket buffer readbacks;
- disconnect/EOF/failure outcome;
- whether shaping, instrumented pass-through, or the raw loopback profile was used.

These diagnostics answer different questions:

```text
eligibility wait          -> modeled network delay
release lateness          -> JVM/OS scheduler error
observer-to-first-return  -> modeled delay plus scheduler/raw-read/application-demand delay
eligible raw-read wait    -> sender observation/admission mismatch or sender production gap
raw-write block           -> actual loopback socket backpressure
socket option readback    -> host configuration context, not modeled capacity
```

Without this separation, a slow result could be incorrectly attributed to configured latency when it came from GC,
compression, raw socket blocking, or stream flush behavior.

## Validation And Falsification Plan

### Layer 1: deterministic schedule tests

Use a fake monotonic clock and no sockets to prove:

- first-byte eligibility is not earlier than sender observation plus `L`;
- range serialization is continuous across write boundaries;
- eligible prefixes grow progressively at `B`;
- idle time resets serialization backlog but does not remove `L`;
- finite arithmetic does not overflow silently;
- two controllers are independent;
- close/abort ordering is correct.

### Layer 2: controlled stream-pair tests

With small loopback transfers and explicit flushes:

1. **Latency only:** an uncongested one-way byte is not visible before its observer-relative `L` deadline.
2. **Ping-pong:** an uncongested request plus immediate response is approximately `2L`, not `4L`, after subtracting
   known test processing and allowing scheduler tolerance.
3. **Bandwidth only:** a continuously supplied long transfer does not exceed `B` and approaches it within tolerance;
   the first caller chunk is not free.
4. **Latency plus bandwidth:** the `j`th byte follows the combined formula, within declared tolerance.
5. **Full duplex:** simultaneous transfers each receive independent `B` and one `L`.
6. **Idle reset:** a later small message still pays `L` but does not inherit an obsolete serialization backlog.
7. **Natural backpressure:** small real socket buffers produce observable raw-write stalls without any Java credit
   window.
8. **Lifecycle:** connection-wide close/abort wakes raw and controller waits; interruption alone is tested only for
   controller waits.
9. **Timeout:** metadata, eligibility, and raw-read time consume one logical socket-read deadline.

### Layer 3: production-stack integration tests

Verify with `SyncInputStream`/`SyncOutputStream`:

- compression on and off;
- output and input socket-payload counters agree after drain;
- observer/gate placement is below compression;
- raw `LOOPBACK` has no A1 components;
- instrumented pass-through instantiates all A1 plumbing at `L = 0`, unlimited `B`;
- the same reconnect payload arrives byte-for-byte;
- no production modules are modified.

### Layer 4: benchmark experiments

Run, in a counterbalanced order:

- raw loopback versus instrumented pass-through to isolate wrapper/syscall/metadata overhead;
- pass-through versus shaped A1 to measure the additional timing effect;
- `270 us / 200 Mbit/s` using the current calibrated workload;
- the prior `270 us` and `50 ms` buffer matrix for comparison, not as an equality requirement;
- at least one latency sweep and bandwidth sweep;
- compression enabled/disabled;
- traversal alternatives under identical A1 settings.

Where possible, compare traversal ranking and compressed byte counts with simulator and cluster evidence. Agreement
increases confidence. Disagreement should be investigated rather than averaged together because the modes have
different backpressure semantics.

### Explicit falsification conditions

Before implementation, agree an error budget. A provisional starting budget for the calibrated profile is:

```text
algorithmic release quantum <= 50 us and, when L > 0, <= 0.10 * L
p99 scheduled-release lateness <= 0.25 * L
at least 99% of bytes belong to ranges whose delegate-write duration
    is <= 0.25 * L and <= that range's serialization duration
```

Report tails and maximums as diagnostics even when the percentile passes. These values are design-review thresholds,
not claims about what the current host will achieve; a failed prototype should cause an explicit redesign or weaker
benchmark claim, not a relaxed threshold after seeing traversal results.

The A1 result should be downgraded or the design revised if any of these occur:

- the provisional timing budgets fail;
- bounded pre-write observation leads to material raw-read waits after eligibility;
- raw writes block long enough that their pre-write timestamps materially understate forward latency;
- metadata waits for capacity or grows beyond the fail-fast high-water assertion;
- the instrumented pass-through overhead is material relative to the raw loopback floor or changes traversal ranking;
- the gate consumes ineligible data into a hidden Java payload buffer;
- close or disconnect can strand a reconnect thread;
- measured bandwidth depends mainly on caller read size rather than `B`;
- two simultaneous directions serialize through shared controller state;
- documentation or logs describe the result as real configured-RTT TCP.

If the timestamp conditions fail, the next investigation should be accepted-range publication through channel-backed
sockets. If the high-RTT TCP-feedback condition is the required outcome, that is not a timestamp fix; it requires a
separately named synthetic credit model or a different external test environment.

## Considered Alternatives

### Keep the current periodic `W / RTT` pacer

Useful for an explicit socket-buffer sensitivity experiment and supported by the existing matrix. Rejected as the
primary realistic profile because it does not provide sender-relative propagation latency and treats buffer-option
readbacks as a software throughput window.

### Add a fixed latency sleep to the current input pacer

Rejected because receiver read boundaries are not sender write/message boundaries. It can apply latency too many
times, only once to a large buffered batch, or after data was already eligible.

### Add delayed sender shadow credits

This can delay reuse of `n` bytes until receiver visibility plus another `L`, approximating reverse-path feedback. It
also needs an initial capacity and becomes an explicit software flow-control model—A2 by this document's definition.
It was called “A1-R” during brainstorming, but that historical label does not make it pure A1. It would no longer leave
capacity solely to loopback TCP.

### Discover capacity by nonblocking saturation, then delay reuse

Writing until `SocketChannel.write()` returns zero can empirically discover how much the local socket accepts instead
of deriving `C` from getters. Delaying subsequent writable progress by `L` still creates a synthetic feedback model,
can freeze or misread autotuning, and requires custom selector/output semantics. Keep it separate from the first A1
implementation.

This is distinct from using partial channel writes only to timestamp successfully accepted ranges. The latter can
improve A1 observation accuracy without adding delayed credit.

### Native `MSG_PEEK` followed by delayed drain

Native peeking can copy data to the application while leaving it in the receive queue, then consume it another `L`
later. That can delay receive-window reopening without initial shadow tickets. It still cannot delay data ACKs, and
Java 25 has no public `MSG_PEEK` socket API. Efficient advancing peeks are platform/version dependent; macOS and
Windows would require repeated growing-prefix copies. Native handle access and FFM/JNI would violate the desired
portable default and could materially distort the benchmark.

### Copy payload to a shadow input and drain the real socket later

Portable in principle, but reconnect would consume a memory copy rather than the real socket input. The socket would
become a backpressure prop, payload would be duplicated, and failure/alignment semantics would become complex. This is
closer to a simulator plus dummy TCP than to the desired socket path.

### Relay between teacher and learner

A relay can place explicit scheduling between Java endpoints, but it terminates one TCP connection and originates a
second. Its buffers, copies, threads, and independent flow-control domains become part of the measurement. Making relay
sockets “unbounded” removes meaningful backpressure and does not restore one end-to-end TCP control loop.

### Kernel/network emulator

This is the cleanest way to preserve one real TCP connection while delaying actual data and ACKs between endpoints.
It violates the portable, unprivileged, single-JVM Gradle-task constraint and belongs in separate integration or
cluster validation, not the default benchmark.

## Recommended Interpretation And Next Decision Points

Refined A1 is a reasonable next experiment because it combines three properties no current single mode provides:

1. observer-relative reconnect-visible latency and progressive payload-bandwidth eligibility;
2. production sync-stream/compression behavior;
3. genuine local socket-buffer occupancy and writer blocking.

It does **not** close the TCP-control gap. The design is strongest when used to compare reconnect behavior on a
healthy, low-latency, lossless path—especially the calibrated `270 us / 200 Mbit/s` profile, whose RTT bandwidth-delay
product is small compared with the old option readbacks/probe reports. That comparison is encouraging, not proof; the
release-quantum, pass-through, raw-write, and scheduler criteria must all pass before the low-latency claim is trusted.

The remaining questions to revisit after the prototype are:

1. Are scheduler lateness and raw-write timestamp error small enough at `270 us`?
2. Does accepted-range `SocketChannel` publication become necessary, or are bounded blocking writes adequate?
3. Does refined A1 retain useful, stable traversal ranking and buffer diagnostics without the old periodic window?
4. Are high-RTT buffer experiments only stress diagnostics, or must the benchmark predict their absolute TCP
   behavior? If the latter, pure A1 is insufficient.
5. Does the required combined gate/socket timeout preserve current reconnect lifecycle behavior under large `L`?
6. Is the first supported claim limited to a healthy lossless path, or are jitter/loss scenarios required later?
7. Is plain-TCP sync/compression fidelity sufficient, or is production TLS a separate future integration benchmark?

This trust boundary must remain visible in parameter help, logs, README guidance, and result reports.

## Cross-Check Checklist

Another reviewer should independently verify all of the following:

- [x] The former `PacingInputStream` used `2L` as a window period and gave the first window immediately.
- [x] Both former paced socket directions had separate input pacers.
- [x] Production sync output counts bytes below buffering/compression, and sync input counts before decompression.
- [x] The implemented observer/gate placement therefore schedules compressed socket payload.
- [x] A small uncongested request/response pays one `L` in each direction, not two `L` per gate.
- [x] The implementation contains no software `W`, initial tickets, or delayed sender credit.
- [x] The controller adds no shadow payload store; the socket is the only transport-level payload store between
      observer and gate and remains the actual blocking-capacity authority.
- [x] The input gate never consumes ineligible socket bytes.
- [x] Pre-write metadata and socket-I/O lock ordering cannot deadlock by current code/audit evidence.
- [x] Pre-write timing error is measured and has a falsification threshold.
- [x] The saturated `t = 2L` replacement timeline is treated as conditional, not guaranteed by pre-write metadata.
- [ ] The release quantum and instrumented pass-through control meet their acceptance budgets.
- [x] Failure signals both controllers and physically closes the shared socket connection.
- [x] One logical read deadline covers metadata, eligibility, and raw socket waits.
- [x] ACK timing and receive-window-update timing are described separately.
- [x] The old 5M/10M matrix is not presented as validation of refined A1.
- [x] `270 us` and `50 ms` conclusions are not generalized into one fidelity claim.
- [x] Payload bandwidth is not mislabeled as physical line rate.
- [x] Plain benchmark TCP is not mislabeled as production TLS.
- [x] The document makes no real-network-equivalence claim.

## Primary Sources

Local code and task evidence:

- [`LoopbackSocketTransport.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java)
- [`SocketVisibilityController.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SocketVisibilityController.java)
- [`ObservedSocketOutputStream.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/ObservedSocketOutputStream.java)
- [`ScheduledSocketInputStream.java`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/ScheduledSocketInputStream.java)
- [`SyncInputStream.java`](../../platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/sync/SyncInputStream.java)
- [`SyncOutputStream.java`](../../platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/sync/SyncOutputStream.java)
- [`TlsFactory.java`](../../platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/TlsFactory.java)
- [`AsyncInputStream.java`](../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/sync/streams/AsyncInputStream.java)
- [`AsyncOutputStream.java`](../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/sync/streams/AsyncOutputStream.java)
- [`2026-07-16-read-pacing-10m-matrix.md`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md)
- [`2026-07-08-read-pacing-smoke-matrix.md`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-08-read-pacing-smoke-matrix.md)
- [`2026-07-16-compression-10m-comparison.md`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-compression-10m-comparison.md)

Protocol and Java API references:

- [RFC 9293: Transmission Control Protocol](https://www.rfc-editor.org/rfc/rfc9293.html), especially window
  management in section 3.8.6.
- [RFC 5681: TCP Congestion Control](https://www.rfc-editor.org/rfc/rfc5681.html), especially `cwnd`, `rwnd`, ACK
  clocking, and delayed ACK behavior.
- [Java 25 `Socket`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/Socket.html), especially the
  buffer-size option semantics.
- [Java 25 `SocketChannel`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/SocketChannel.html),
  especially blocking and nonblocking read/write behavior.
- [Java 25 `ExtendedSocketOptions`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.net/jdk/net/ExtendedSocketOptions.html),
  showing that `TCP_QUICKACK` can reduce delayed ACKs but does not provide arbitrary configured ACK delay.
