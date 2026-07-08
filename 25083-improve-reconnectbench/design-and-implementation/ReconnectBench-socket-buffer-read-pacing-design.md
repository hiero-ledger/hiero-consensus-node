# ReconnectBench Socket-Buffer Read-Pacing Design (Option C)

Date: `2026-07-07` (revised `2026-07-08` after four-lens design review)

## Status

Approved brainstorming design, pending implementation. Follow-up to
[`ReconnectBench-loopback-socket-transport-design.md`](ReconnectBench-loopback-socket-transport-design.md),
which added the `LOOPBACK_SOCKET` transport specifically to test "whether real socket configuration changes affect
reconnect wall clock time" (that doc, lines 20-27).

This design is **benchmark-only**. It changes only `platform-sdk/swirlds-benchmarks/**`. It reads the socket buffer
sizes that production `SocketFactory` configures, but it does **not** change `SocketFactory` or any production code.

### Supersedes

This design **overrides** the following directives of the loopback socket transport design (which is otherwise still in
force):

- **"Do not add read-side pacing"** (Socket Shaping, that doc lines 262-263). The stated fear — that read-side pacing
  would mask the `SocketFactory` buffer behavior — had the mechanism backwards: it is the **write-side trickle** that
  masks the buffer (bytes are metered before the socket, so the kernel buffer never fills), while read-side
  *withholding* is exactly what engages it. Empirically confirmed (see Empirical validation).
- **Non-Goals: "No benchmark-level in-flight cap for socket transport. Real TCP already has kernel send buffers,
  receive buffers, and advertised windows"** (lines 32-34). The premise is true but insufficient: at loopback RTT those
  kernel windows never bind, so without read-side pacing the buffer is invisible. Note the pacer's window is
  **kernel-derived** (live buffer readback); `networkInflightBytesLimit` remains ignored by the socket transport.
- **The `LOOPBACK_SOCKET + REALISTIC` row of the Behavior Matrix and the write-side Socket Shaping stack**
  (lines 176-190, 245-274). Replaced by the Behavior Matrix below: all REALISTIC shaping for the socket transport moves
  to the read side.
- **The "disconnect() wakes a blocked socket reader" expectation** (Tests, line 347) is weakened for a reader parked in
  the pacer: wake-up is bounded by `max(RTT, one chunk transmit time)` unless the optional unpark is implemented
  (decision 3 below).

## Problem

The motivating goal of the loopback transport — see whether a `SocketFactory` send/receive buffer change moves
reconnect wall-clock — is currently **unmeasurable**. Local `LOOPBACK_SOCKET + REALISTIC` runs show no wall-clock
difference between the buffer configs (see
[`new-5m-local.md`](../evidence-and-calibration/local-reconnectbench-calibration-notes/new-5m-local.md):
run 2 (1 MiB) 54.495 s/op vs run 3 (32 KiB) 54.143 s/op).

Root cause: today's shaping is **write-side** only
([`ShapingOutputStream`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/ShapingOutputStream.java):
`parkNanos(latency)` per write + bandwidth pacing per 8 KiB chunk). It meters bytes *before* they reach the socket, so
the kernel send buffer never fills. A socket buffer only affects throughput once bandwidth × RTT (the bandwidth-delay
product) exceeds the window; at loopback's ~microsecond RTT that product is a few KB, far below any configured buffer.
So the buffer is never the binding constraint and its size is invisible in wall-clock.

## Goal

Make the **real kernel socket buffer** create **real backpressure** during a loopback reconnect, so that changing the
buffer in `SocketFactory` moves reconnect wall-clock. This is done within ReconnectBench's established philosophy: it is
a **calibrated model** of the network whose correctness is judged by whether it **predicts the cluster trend**, not by
internal purity. Injecting latency and shaping bandwidth are the model; making the real buffer bind is the missing
third dimension.

## Approach: read-side pacing (Option C)

The only pure-Java way to make the real buffer bind is on the **read side**. The receiver deliberately withholds
reading, so bytes back up in its kernel receive buffer; the TCP window closes; the sender's `write()` blocks on its
full send buffer. That is real, kernel-driven backpressure whose severity is set by the real buffer size.

- The receiver releases at most a **window's worth** of bytes, then waits ~**RTT** before releasing the next window,
  giving steady-state throughput ≈ `window / RTT` — the same bandwidth-delay-product relation the model already relies
  on, now driven by the real buffer.
- **RTT and bandwidth are clean parameters**, applied via `LockSupport.parkNanos` — the same Java-sleep basis already
  accepted for [`SimulatedNetworkChannel`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java)
  latency (which predicts the cluster fine).
- Write-side shaping is **removed** for the socket transport (see "Write path" below) — trickling bytes into the socket
  is exactly what starves the buffer today.

### Why not the alternatives

- **Software-modeled window ("Option B"):** blocks the writer at a window `W` read from the socket, enforced in Java.
  Deterministic and simple, but the socket runs under no real load, so it cannot show the kernel's real behavior
  (autotuning). Rejected as the primary approach because the whole point of the socket transport is to exercise the
  real stack.
- **Kernel shaping (dummynet / containers / `tc netem`):** faithful, but requires OS/sudo setup — out of scope for a
  JMH benchmark that devs run with parameters only.
- Kept **independent** from `SimulatedNetworkChannel` (no shared code), so either transport can be deleted later once
  we know which one to keep.

## Empirical validation

A standalone local probe (macOS; filed with source and raw output at
[`2026-07-08-socket-buffer-probe.md`](../evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-08-socket-buffer-probe.md);
mirrors `SocketFactory`'s set-before-bind/connect configuration) confirmed the mechanism and refuted the two objections
that would have killed it:

- **The real buffer binds, and its size dominates the effective window.** With a non-reading receiver, the sender's
  `write()` blocks after: **32 KiB config → ~385 KB**, **1 MiB config → ~2.4 MB** (~6× spread), unset default →
  ~536 KB.
- **The effective window is `send buffer + receive buffer`, not `min(...)`.** The probe-measured stall point (~385 KB
  for the 32 KiB config) matches the sum of the effective buffer readbacks (65,328 send + 326,640 receive = 391,968);
  the earlier `min()` assumption under-sized the window ~2×. The design uses the **sum**.
- **Autotuning is visible via `getReceiveBufferSize()`.** Under load the receive buffer grew in every config (e.g.
  32 KiB config: 326 KB → 480 KB). An explicitly **set send** buffer is pinned (no growth); **receive** buffers
  autotune and the JVM surfaces the growth. So a live per-window readback follows autotuning.

Caveat: these are macOS numbers. Linux clamps/accounts differently. Per the model philosophy, the real validation is
whether the built transport predicts the cluster trend; macOS runs are smoke-tests until confirmed on a
cluster-matched Linux host.

## When the window binds — choosing parameters

The pacer yields steady-state throughput = `min(bandwidth, W / RTT)`, so **the buffer only moves wall-clock when
`W / RTT` drops below the bandwidth cap**, i.e. when `W < bandwidth × RTT`. This must be checked against the actual
parameters, and at the initiative's calibrated profile it does **not** hold — a correct implementation will (correctly)
still show no buffer effect there.

Worked table at 200 Mbit/s (25 MB/s cap), with per-direction windows from the run diagnostics
(teacher→learner `W = clientSend + acceptedReceive`; learner→teacher `W = acceptedSend + clientReceive`):

| Leg | One-way latency | RTT | Config | W (t→l / l→t) | W/RTT (t→l / l→t) | Binding constraint |
|---|---|---|---|---|---|---|
| control | 270 µs | 0.54 ms | 32 KiB | 0.39 MB / 0.47 MB | ~726 / ~877 MB/s | **bandwidth** (25 MB/s) |
| control | 270 µs | 0.54 ms | 1 MiB | 2.12 MB / 1.21 MB | ~3,930 / ~2,240 MB/s | **bandwidth** (25 MB/s) |
| binding | 50,000 µs | 100 ms | 32 KiB | 0.39 MB / 0.47 MB | **3.9 / 4.7 MB/s** | **window** |
| binding | 50,000 µs | 100 ms | 1 MiB | 2.12 MB / 1.21 MB | **21.2 / 12.1 MB/s** | **window** |

- **Control leg (270 µs — the cluster-calibrated value):** both configs run bandwidth-bound at identical throughput.
  Expected result: **no buffer effect** — this is the physically correct answer at sub-ms RTT, and the leg doubles as a
  check that the pacer does not fabricate an effect where none exists.
- **Binding leg (50,000 µs one-way → RTT 100 ms, within the real environment's expected up-to-200 ms range):** both
  configs are window-bound; expected separation ~2.6×-5.4× per direction — far above the observed run-to-run noise.
- Context: at 25 MB/s the 32 KiB config starts to bind at RTT > ~16 ms, the 1 MiB config at RTT > ~85 ms. The JMH class
  defaults (500 µs, 1000 Mbit/s) are also non-binding (3×-19× above the cap).
- Receive-side autotuning grows `W` during a run (probe: 326 KB → 480 KB), so realized separation may drift — that
  drift *is* the autotuning signal, visible via the live-`W` diagnostics.

**Noise floor.** In `new-5m-local.md`, run 1 (buffers unset, windows *between* the two set configs) scored 68.6 s/op vs
~54 s/op for both set configs — a ~26% spread not attributable to window size (first-run/cold-cache effects). Treat
~26% as the current noise/confound floor: the binding leg's ≥2.6× separation clears it comfortably, but any sub-30%
effect claim needs more forks/iterations.

**Runbook.** The buffer experiment runs the two legs via the existing task, one run per `SocketFactory` buffer edit:

```bash
./gradlew :swirlds-benchmarks:jmhReconnectLoopbackRealistic -PnetworkLatencyMicroseconds=270    # control
./gradlew :swirlds-benchmarks:jmhReconnectLoopbackRealistic -PnetworkLatencyMicroseconds=50000  # binding leg
```

`jmhReconnectLoopbackFast` (LOOPBACK profile) remains the pacing-free raw floor.

## Placement in the stream stack

One `PacingInputStream` per direction, inserted as the **bottom-most read wrapper — directly on the raw socket input**.
The pacer is installed **only under `NetworkProfile.REALISTIC`** (mirroring the old `maybeShape` pattern); under
`LOOPBACK` both stacks stay raw so the floor baseline is byte-identical to today. Per-direction stacks (top =
reconnect-facing, bottom = kernel):

```
teacher->learner:  DataInputStream -> BufferedInputStream(socketConfig.bufferSize(), default 8192) -> CountingInputStream -> PacingInputStream -> learnerSocket input
learner->teacher:  DataInputStream -> BufferedInputStream(socketConfig.bufferSize(), default 8192) -> CountingInputStream -> PacingInputStream -> teacherSocket input
```

Construction sites: the two `CountingInputStream`s in
[`LoopbackSocketTransport`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java)
(lines 65 and 72) wrap a `PacingInputStream(rawSocketInput)` instead of the raw socket input directly.

**The `BufferedInputStream` is kept** (decision below). Because the pacer is *underneath* it, the buffer can never read
the socket faster than the pacer allows — it only holds up to one `socketConfig.bufferSize()` chunk in Java that the
pacer already released (≤ ~2% of the measured windows at the 8192 default), which keeps the connection faithful to the
real/cluster stream setup. As a side effect, the buffer coalesces the reconnect's byte-at-a-time `readInt()` reads into
chunk reads, so the pacer never sees single-byte reads (see decision 2; note the coalescing claim is
workload-dependent — `readFully` requests ≥ the buffer size bypass straight through to the pacer, which is why the read
clamp below exists).

## Write path: shaper removed

Under `LOOPBACK_SOCKET`, the write-side shaper is **removed entirely**: `maybeShape(...)` is deleted and
`CountingOutputStream` wraps `socket.getOutputStream()` directly in **all** profiles. The read-side pacer becomes the
**sole carrier of both latency (RTT windows) and bandwidth (cursor)** for the socket transport.

This is not optional cleanup — it is load-bearing: if the write-side shaper were kept alongside the pacer, latency
would be applied twice and, decisively, the per-write trickle would keep metering bytes *before* the socket so the
kernel send buffer never fills — silently re-creating the exact failure this design fixes. (At binding-leg latencies it
would also collapse the write path to ~8 KB per one-way park, ~0.16 MB/s at 50 ms.)

`ShapingOutputStream` has no other user
(sole usage: `LoopbackSocketTransport.maybeShape`, line 156) and is **deleted**; its `transmitDurationNanos` formula
moves into `PacingInputStream`.

Resulting Behavior Matrix for the socket transport (supersedes the old doc's rows):

```text
LOOPBACK_SOCKET + LOOPBACK
  Raw sockets via SocketFactory.configure*. No pacer, no shaping, no wrappers beyond Counting/Buffered/Data.
  Unchanged raw floor.

LOOPBACK_SOCKET + REALISTIC
  Raw write path (no write-side shaping).
  Read-side PacingInputStream per direction: RTT-windowed release (W = live sendbuf + recvbuf) + bandwidth cursor.
  networkInflightBytesLimit ignored (unchanged; see below).
```

## The window `W`

`W` is read **live from the live sockets each time a window opens**, so kernel autotuning is captured:

- teacher->learner (paced on the learner receive side):
  `W = teacherSocket.getSendBufferSize() + learnerSocket.getReceiveBufferSize()`
- learner->teacher (paced on the teacher receive side):
  `W = learnerSocket.getSendBufferSize() + teacherSocket.getReceiveBufferSize()`

These are the same values [`SocketTransportDiagnostics`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SocketTransportDiagnostics.java)
already reads once at construction (`LoopbackSocketTransport` lines 84-89); the pacer re-reads them per window. The
window supplier is a small **`IOException`-throwing interface** (not `java.util.function.Supplier`):
`getSendBufferSize()`/`getReceiveBufferSize()` throw `SocketException` once `disconnect()` closes the sockets mid-run;
the pacer propagates that from `read()` — the same clean-abort path as reading a closed socket.

Both directions respond to a `SocketFactory` buffer change:

- teacher->learner: `teacherSocket` send **and** `learnerSocket` receive both trace back to `SocketFactory` settings
  (client send set directly; accepted receive inherited from the listening socket) — strong, direct response, and the
  receive term also autotunes.
- learner->teacher: `teacherSocket` receive is `SocketFactory`-set (responds to the tweak); `learnerSocket` send is
  **never set by `SocketFactory`** (OS default + autotuning), matching production. So this direction still responds via
  the receive term, while faithfully carrying the un-tuned accepted-send component.

`networkInflightBytesLimit` **remains ignored** by the socket transport (diagnostics keep `inflightBytesLimitIgnored =
true`). The pacer's window is kernel-derived, not the configured cap; the live-`W` log line exists precisely so the two
cannot be conflated.

## Release cadence, RTT, bandwidth

Per direction, all state guarded by a per-stream lock (each direction is an independent object; no cross-direction
locking). `read(b, off, len)`:

1. If a new RTT window is due (`now >= windowClosesAt`), open it: `W = windowSupplier.get()`, `releasedThisWindow = 0`,
   `windowClosesAt = now + RTT`.
2. If this window's `W` budget is exhausted, **park** until the window reopens (this is the withhold that fills the
   kernel buffer), then continue.
3. If the bandwidth cursor is ahead of `now`, park to it. (Steps 2-3 collapse into a **single** park to the
   max-eligible time, to limit `parkNanos` overshoot.)
4. Issue **one** underlying `in.read(b, off, toRead)` with the request **clamped to the remaining window budget**:
   `toRead = max(1, min(len, W - releasedThisWindow))`. The clamp keeps the per-window release invariant even for large
   `readFully` requests that bypass the `BufferedInputStream` (without it, one bypassed read against a full kernel
   buffer could overshoot the window by a whole buffer's worth). Advance `releasedThisWindow` by the bytes returned and
   the bandwidth cursor by `transmitDurationNanos(n)` (formula taken verbatim from the deleted `ShapingOutputStream`).
5. Never return `0` for a `len > 0` request (park-then-continue instead) — returning `0` would spin
   `DataInputStream.readFully`. Return `>= 1` or `-1` (EOF, latched).

- **RTT** = `2 × NetworkSimulationConfig.latencyNanos()` (one-way latency stays the clean parameter; a released window
  is "un-acked" for one round trip). Sweep legs fixed above: 270 µs control, 50,000 µs binding.
- **Bandwidth** cap layered on top via the cursor, so steady-state throughput = `min(bandwidth, W / RTT)`. Applied as a
  post-read delay of the *next* read (release-then-wait), never as a pre-write trickle, so the kernel buffer actually
  fills between releases. `bandwidthBytesPerSecond == Long.MAX_VALUE` makes the cursor inert.

## Both directions and deadlock

Both directions are paced independently. There is **no deadlock**: per
[`MerkleBenchmarkUtils`](../../platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java)
(lines 82-87) the teacher and learner run on separate threads, and each side's `TeachingSynchronizer` /
`LearningSynchronizer` use dedicated async reader and writer threads (`AsyncInputStream` / `AsyncOutputStream`); no
single thread must read to unblock its own write. Withholding one direction's reads back-pressures the peer's writer
thread (its `write()` blocks on a full send buffer) without stalling the local writer.

Note the two in-memory queues in series in front of the kernel on each side (`AsyncInputStream` `queueSizeThreshold` and
`AsyncOutputStream` buffer, each 10000 messages) plus ~8 ms time-based flush batching. Whether the real reconnect keeps
the kernel buffer full through these is an implementation-time observation (see Open questions); shrinking
`reconnect.asyncStreamBufferSize` for the benchmark (config-only) is a lever if they mask the buffer.

## Recorded decisions (with rationale)

1. **Keep `BufferedInputStream`.** Removing it would make the connection differ from the real/cluster stream setup;
   fidelity wins. Safe because the pacer sits underneath it (only one `socketConfig.bufferSize()` chunk held in Java —
   ≤ ~2% of the window at the 8192 default; the figure scales with `socket.bufferSize` if overridden). Revisit
   only if a future need arises.
2. **Drop the single-byte-read override; document the dependency instead.** With `BufferedInputStream` kept, small
   framed reads (`readInt()` byte-reads) are coalesced and the pacer's inherited no-arg `read()` is never invoked —
   verified against the JDK 25 sources: `BufferedInputStream` calls only the array `read` on the stream below it, in
   both its `fill()` and its large-read bypass, and `CountingInputStream` preserves read arity. So the override would
   be dead code. The pacer's javadoc must record the full chain and both halves of the claim: *"we rely on the
   `BufferedInputStream -> CountingInputStream` chain above to coalesce reads (JDK-guaranteed: no single-byte reads
   reach the pacer); chunk size is workload-dependent — `readFully` requests ≥ the buffer size bypass straight through,
   which the step-4 clamp bounds. If the `BufferedInputStream` is ever removed, the pacer must also gate the
   single-byte `read()`."*
3. **Shutdown-wake is optional.** A reader parked in the pacer is not in a syscall, so closing the socket does not wake
   it — but the park is bounded by `max(one RTT, one chunk's transmit time at the configured bandwidth)` (with the
   step-4 clamp, at most `max(RTT, transmitDuration(W))`), after which it reads the closed socket and exits cleanly. So
   teardown on abort is delayed by that bound, never hangs. An explicit flag + `LockSupport.unpark` on
   `disconnect()`/`close()` is a nicety to make teardown instant; implement only if that delay ever matters. Javadoc
   must note: *before Option C, closing the socket alone woke a blocked reader; the pacer can now be parked in a Java
   sleep that a socket close does not interrupt.*
4. **Leave `SO_TIMEOUT` (5000 ms) alone.** Under backpressure the sender's buffer is full, so when the pacer reads,
   data is already waiting and `read()` returns immediately; the 5 s limit only counts time inside a `read()` call and
   is never approached with continuously flowing reconnect data. If a run ever aborts with `SocketTimeoutException` at a
   quiet protocol boundary, the fix is a one-line benchmark-only bump of `timeoutSyncClientSocket` — not applied
   preemptively.
5. **Leave the learner (accepted) send buffer unset.** Production `SocketFactory` never sets it; ReconnectBench should
   exercise the real production setting. Both directions still respond to the tweak (via their receive terms); this
   just faithfully carries the un-tuned accepted-send component.
6. **Latency is modeled as a throughput window only (option a).** Reviewed and accepted: per-message/first-byte one-way
   delay is *not* modeled by the pacer — after any idle gap ≥ RTT the first window's worth of bytes flows with ~zero
   added delay, and latency emerges as throughput limiting under sustained flow. Consequence: `LOOPBACK_SOCKET +
   REALISTIC` and `SIMULATED + REALISTIC` are **not directly comparable at the same `networkLatencyMicroseconds`** (the
   simulated transport delays every byte-range's arrival by one-way latency). Accepted because buffer sensitivity is
   the goal and the arbiter is cluster-trend prediction. The alternative (delaying each window's opening by one-way
   latency) was considered and rejected as unnecessary complexity for now.

## Output and diagnostics

- **Primary output: reconnect wall-clock time.** This is the signal devs optimize against and the basis for
  cluster-trend validation.
- **Secondary diagnostics (optional readouts, not gates):** per-direction live `W`, effective window from
  `bytesWritten - bytesRead` (the existing Counting streams), and a kernel-buffer-fullness approximation (compare
  `bytesWritten - bytesRead` against live `W`; there is no pure-Java API for kernel queue depth). These are an
  additional feedback loop to catch a run where the buffer never bound (distinguishing "the buffer genuinely did not
  matter here" from "the harness never let it bind"). They do not invalidate runs.
- **Mechanism:** `SocketTransportDiagnostics` is a construction-time snapshot and cannot carry live values. The two
  existing flags are kept but **redefined** (javadoc + this doc): `latencyShapingActive` / `bandwidthShapingActive` now
  mean *read-side pacing active* for latency/bandwidth respectively (REALISTIC only). Live values are exposed via pacer
  accessors (e.g. `lastWindowBytes()`, `windowsOpened()`, `totalParkedNanos()`) and logged in an end-of-run line next
  to the existing diagnostics log in `MerkleBenchmarkUtils`.

## Accepted modeling caveats

- **Latency changes speculation/workload.** Under realistic latency the traversal (`ParallelSyncTraversalOrder`)
  speculates more and may transfer more redundant nodes. This is the model working, not an artifact — it is why
  traversal order matters. Trust is established by cluster-trend prediction. (The optional byte-count diagnostic makes
  any drift observable.)
- **Window-only latency semantics** (decision 6): no per-message delay; socket and simulated transports are not
  apples-to-apples at the same latency parameter.
- **Sleep jitter.** `parkNanos` is imprecise; the same is already true of `SimulatedNetworkChannel`, which predicts the
  cluster successfully. The binding leg's RTT (100 ms) dwarfs jitter; the control leg's separation claim is "none
  expected" so jitter is moot there.
- **macOS ≠ Linux.** All local numbers are macOS; the design is validated by cluster-trend prediction after
  implementation, and calibrated on Linux before drawing cluster conclusions.

## Scope

- **Committed:** reconnect wall-clock responds to the **configured** `SocketFactory` buffer **in the binding regime**
  (the 100 ms-RTT leg; probe-proven window separation ~2.6×-5.4×). At the calibrated control leg (270 µs) the correct
  and expected result is **no effect** — that is the physics of sub-ms RTT, not a failed implementation.
- **Bonus (validated, not depended on):** wall-clock reflects kernel **autotuning** via the live per-window readback.

## Tests

The predecessor design's test suite needs the following changes (file:
`platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java`):

- **`realisticProfileDelaysFirstBytes` is retired/replaced.** It asserts write-side first-byte delay (≥ 70 ms for a
  4-byte write) — under this design (write shaper removed) 4 bytes pass through a fresh window instantly, so the test
  **fails the correct implementation and passes the wrong one** (shaper accidentally kept). Replace with a windowed
  assertion: a transfer of `N × W` bytes at `bandwidth = MAX` must take ≥ `(N - 1) × RTT`.
- **`realisticProfilePacesLargeWrites`** shifts semantics from pre-write trickle to release-then-wait; expected to
  still pass — re-derive its threshold and keep it.
- **Diagnostics assertions** (`latencyShapingActive`/`bandwidthShapingActive` in several tests) are updated to the
  redefined read-side meanings.
- **`disconnectWakesBlockedReader`** still must pass for a reader blocked in a kernel read; add the teardown-bound
  tolerance for a reader parked in the pacer (or implement the optional unpark).
- **New `PacingInputStream` unit tests:** window budget + park cadence (large transfer at `bandwidth=MAX` paces at
  ~`W/RTT`), step-4 read clamp, never-returns-0, EOF latch, live-`W` readback reflects supplier changes mid-stream,
  LOOPBACK profile leaves the stack raw (pacer absent), disconnect during a park exits within the documented bound.

Verification commands as in the predecessor doc:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest --console=plain
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest --console=plain
./gradlew :swirlds-benchmarks:compileJmhJava --console=plain
```

## Open questions / verify during implementation

- Does the real reconnect (with its two 10000-message queues and 8 ms flush batching) actually keep the kernel buffer
  full, or do the queues mask it? Observe via the secondary diagnostics; shrink `asyncStreamBufferSize` if needed.
- The `RTT = 2 × one-way` convention and the raw `send + receive` window are first-order; calibrate the effective-window
  multiplier against a measured raw-loopback throughput run.
- Confirm the cluster-trend prediction holds on Linux.

## Documentation updates

- Old design doc amended with a supersession note (done alongside this revision).
- `Index.md` entry for this design and for the probe evidence note (done alongside this revision).
- When the implementation lands: update `future-work/future-follow-ups.md` item 3 (its note "write-side shaping only"
  becomes stale) and register `new-5m-local.md` in the calibration-notes hub.

## Implementation checklist

- Add `PacingInputStream` (package `com.swirlds.benchmark.reconnect.network`), inserted as the bottom read wrapper on
  both directions in `LoopbackSocketTransport`, **REALISTIC profile only** (LOOPBACK stays raw).
- **Remove the write-side shaper:** delete `maybeShape` and `ShapingOutputStream`; `CountingOutputStream` wraps the raw
  socket output in all profiles; move `transmitDurationNanos` into the pacer.
- Effective window `W = sender.getSendBufferSize() + receiver.getReceiveBufferSize()`, read live per window via an
  `IOException`-throwing supplier; supplier failure propagates from `read()` (clean-abort path).
- Single collapsed park; step-4 read clamp `max(1, min(len, W - releasedThisWindow))`; `read` never returns `0`; EOF
  latched.
- Keep `BufferedInputStream(socketConfig.bufferSize())`; do **not** add the single-byte override (javadoc dependency
  note per decision 2).
- Do **not** raise `SO_TIMEOUT`; do **not** set the accepted send buffer; `networkInflightBytesLimit` stays ignored
  (`inflightBytesLimitIgnored=true`).
- Shutdown-wake (flag + unpark): optional; teardown bound documented as `max(RTT, chunk transmit time)`.
- Diagnostics: redefine the two shaping flags (read-side); add pacer accessors + end-of-run live-`W` log line.
- Tests per the Tests section.
- Keep reconnect wall-clock primary; secondary diagnostics are non-gating readouts.
- Independent from `SimulatedNetworkChannel` — no shared code.
