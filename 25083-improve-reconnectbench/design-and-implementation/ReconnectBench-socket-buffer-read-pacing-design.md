# ReconnectBench Socket-Buffer Read-Pacing Design (Option C)

Date: `2026-07-07`

## Status

Approved brainstorming design, pending implementation. Follow-up to
[`ReconnectBench-loopback-socket-transport-design.md`](ReconnectBench-loopback-socket-transport-design.md),
which added the `LOOPBACK_SOCKET` transport specifically to test "whether real socket configuration changes affect
reconnect wall clock time" (that doc, lines 20-27).

This design is **benchmark-only**. It changes only `platform-sdk/swirlds-benchmarks/**`. It reads the socket buffer
sizes that production `SocketFactory` configures, but it does **not** change `SocketFactory` or any production code.

## Problem

The motivating goal of the loopback transport — see whether a `SocketFactory` send/receive buffer change moves
reconnect wall-clock — is currently **unmeasurable**. Local `LOOPBACK_SOCKET + REALISTIC` runs show no wall-clock
difference between a 32 KiB and a 1 MiB buffer (see
[`local-reconnectbench-calibration-notes/new-5m-local.md`](../evidence-and-calibration/local-reconnectbench-calibration-notes/new-5m-local.md),
run 2 vs run 3: 54.495 vs 54.143 s/op).

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
- Write-side latency the way `ShapingOutputStream` does it is **not** used for the buffer-sensitive path — trickling
  bytes into the socket is exactly what starves the buffer today.

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

A standalone local probe (macOS, 2026-07-07; mirrors `SocketFactory`'s set-before-connect configuration) confirmed the
mechanism and refuted the two objections that would have killed it:

- **The real buffer binds, and its size dominates the effective window.** With a non-reading receiver, the sender's
  `write()` blocks after: **32 KiB config → ~385 KB**, **1 MiB config → ~2.4 MB** (~6× spread), unset default →
  ~536 KB.
- **The effective window is `send buffer + receive buffer`, not `min(...)`.** (385 KB ≈ 65 KB send + 327 KB receive.)
  The earlier `min()` assumption under-sized the window ~2×; the design uses the **sum**.
- **Autotuning is visible via `getReceiveBufferSize()`.** Under load the receive buffer grew in every config (e.g.
  32 KiB config: 326 KB → 480 KB). An explicitly **set send** buffer is pinned (no growth); **receive** buffers
  autotune and the JVM surfaces the growth. So a live per-window readback follows autotuning.

Caveat: these are macOS numbers. Linux clamps/accounts differently. Per the model philosophy, the real validation is
whether the built transport predicts the cluster trend; macOS runs are smoke-tests until confirmed on a
cluster-matched Linux host.

## Placement in the stream stack

One `PacingInputStream` per direction, inserted as the **bottom-most read wrapper — directly on the raw socket input**.
Everything above it is kept unchanged. Per-direction stacks (top = reconnect-facing, bottom = kernel):

```
teacher->learner:  DataInputStream -> BufferedInputStream(8192) -> CountingInputStream -> PacingInputStream -> learnerSocket input
learner->teacher:  DataInputStream -> BufferedInputStream(8192) -> CountingInputStream -> PacingInputStream -> teacherSocket input
```

Construction sites: the two `CountingInputStream`s in
[`LoopbackSocketTransport`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java)
(lines 65 and 72) wrap a `PacingInputStream(rawSocketInput)` instead of the raw socket input directly.

**The `BufferedInputStream(8192)` is kept** (decision below). Because the pacer is *underneath* it, the buffer can never
read the socket faster than the pacer allows — it only holds up to ~8 KB in Java that the pacer already released
(≤ ~2% of the measured windows), which keeps the connection faithful to the real/cluster stream setup. As a side
effect, the buffer coalesces the reconnect's byte-at-a-time `readInt()` reads into ~8 KB chunk reads, so the pacer
never sees single-byte reads (see item 2 below).

## The window `W`

`W` is read **live from the live sockets each time a window opens**, so kernel autotuning is captured:

- teacher->learner (paced on the learner receive side):
  `W = teacherSocket.getSendBufferSize() + learnerSocket.getReceiveBufferSize()`
- learner->teacher (paced on the teacher receive side):
  `W = learnerSocket.getSendBufferSize() + teacherSocket.getReceiveBufferSize()`

These are the same values [`SocketTransportDiagnostics`](../../platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SocketTransportDiagnostics.java)
already reads once at construction (`LoopbackSocketTransport` lines 84-89); the pacer re-reads them per window.

Both directions respond to a `SocketFactory` buffer change:

- teacher->learner: `teacherSocket` send **and** `learnerSocket` receive both trace back to `SocketFactory` settings
  (client send set directly; accepted receive inherited from the listening socket) — strong, direct response, and the
  receive term also autotunes.
- learner->teacher: `teacherSocket` receive is `SocketFactory`-set (responds to the tweak); `learnerSocket` send is
  **never set by `SocketFactory`** (OS default + autotuning), matching production. So this direction still responds via
  the receive term, while faithfully carrying the un-tuned accepted-send component.

## Release cadence, RTT, bandwidth

Per direction, all state guarded by a per-stream lock (each direction is an independent object; no cross-direction
locking). `read(b, off, len)`:

1. If a new RTT window is due (`now >= windowClosesAt`), open it: `W = windowSupplier.get()`, `releasedThisWindow = 0`,
   `windowClosesAt = now + RTT`.
2. If this window's `W` budget is exhausted, **park** until the window reopens (this is the withhold that fills the
   kernel buffer), then continue.
3. If the bandwidth cursor is ahead of `now`, park to it.
4. Issue **one** underlying `in.read(...)` (the only place the socket is read). Advance `releasedThisWindow` by the
   bytes returned and the bandwidth cursor by `transmitDurationNanos(n)` (formula reused verbatim from
   `ShapingOutputStream` lines 54-59).
5. Never return `0` for a `len > 0` request (park-then-continue instead) — returning `0` would spin
   `DataInputStream.readFully`. Return `>= 1` or `-1` (EOF, latched).

- **RTT** = `2 × NetworkSimulationConfig.latencyNanos()` (one-way latency stays the clean parameter; a released window
  is "un-acked" for one round trip). To be confirmed against target cluster RTT during calibration.
- **Bandwidth** cap layered on top via the cursor, so steady-state throughput = `min(bandwidth, W / RTT)`. Applied as a
  post-read delay of the *next* read (release-then-wait), never as a pre-write trickle, so the kernel buffer actually
  fills between releases. `bandwidthBytesPerSecond == Long.MAX_VALUE` (LOOPBACK profile) makes the cursor inert.
- Collapse the window and bandwidth waits into a **single** `park` to the max-eligible time, to limit `parkNanos`
  overshoot.

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
   fidelity wins. Safe because the pacer sits underneath it (only ~8 KB held in Java, ≤ ~2% of the window). Revisit
   only if a future need arises.
2. **Drop the single-byte-read override; document the dependency instead.** With `BufferedInputStream` kept, it
   coalesces the reconnect's `readInt()` byte-reads and only ever calls the pacer with ~8 KB chunk reads, so the pacer
   never sees single-byte reads and the override would be dead code (the pacer's inherited no-arg `read()` is never
   invoked either — `BufferedInputStream` only calls the array `read`). A javadoc note on the pacer must record: *"we
   rely on the `BufferedInputStream` above to coalesce reads; if it is ever removed, the pacer must also gate the
   single-byte `read()`."*
3. **Shutdown-wake is optional.** A reader parked in the pacer is not in a syscall, so closing the socket does not wake
   it — but the park is at most one RTT, after which it reads the closed socket and exits cleanly. So teardown on abort
   is delayed by ≤ one RTT (tens of ms), never hangs. An explicit flag + `LockSupport.unpark` on `disconnect()`/`close()`
   is a nicety to make teardown instant; implement only if that delay ever matters. Javadoc must note: *before Option C,
   closing the socket alone woke a blocked reader; the pacer can now be parked in a Java sleep that a socket close does
   not interrupt.*
4. **Leave `SO_TIMEOUT` (5000 ms) alone.** Under backpressure the sender's buffer is full, so when the pacer reads,
   data is already waiting and `read()` returns immediately; the 5 s limit only counts time inside a `read()` call and
   is never approached with continuously flowing reconnect data. If a run ever aborts with `SocketTimeoutException` at a
   quiet protocol boundary, the fix is a one-line benchmark-only bump of `timeoutSyncClientSocket` — not applied
   preemptively.
5. **Leave the learner (accepted) send buffer unset.** Production `SocketFactory` never sets it; ReconnectBench should
   exercise the real production setting. Both directions still respond to the tweak (via their receive terms); this
   just faithfully carries the un-tuned accepted-send component.

## Output and diagnostics

- **Primary output: reconnect wall-clock time.** This is the signal devs optimize against and the basis for
  cluster-trend validation.
- **Secondary diagnostics (optional readouts, not gates):** per-direction live `W`, effective window from
  `bytesWritten - bytesRead` (the existing Counting streams), and whether the kernel send/receive queue is actually
  near capacity. These are an additional feedback loop to catch a run where the buffer never bound (distinguishing "the
  buffer genuinely did not matter here" from "the harness never let it bind"). They do not invalidate runs.

## Accepted modeling caveats

- **Latency changes speculation/workload.** Under realistic latency the traversal (`ParallelSyncTraversalOrder`)
  speculates more and may transfer more redundant nodes. This is the model working, not an artifact — it is why
  traversal order matters. Trust is established by cluster-trend prediction. (The optional byte-count diagnostic makes
  any drift observable.)
- **Sleep jitter.** `parkNanos` is imprecise; the same is already true of `SimulatedNetworkChannel`, which predicts the
  cluster successfully. Use a coarse RTT (well above jitter) and the existing JMH forking to control variance.
- **macOS ≠ Linux.** All local numbers are macOS; the design is validated by cluster-trend prediction after
  implementation, and calibrated on Linux before drawing cluster conclusions.

## Scope

- **Committed:** reconnect wall-clock responds to the **configured** `SocketFactory` buffer (probe-proven viable).
- **Bonus (validated, not depended on):** wall-clock reflects kernel **autotuning** via the live per-window readback.

## Open questions / verify during implementation

- Does the real reconnect (with its two 10000-message queues and 8 ms flush batching) actually keep the kernel buffer
  full, or do the queues mask it? Observe via the secondary diagnostics; shrink `asyncStreamBufferSize` if needed.
- The `RTT = 2 × one-way` convention and the raw `send + receive` window are first-order; calibrate the effective-window
  multiplier against a measured raw-loopback throughput run.
- Confirm the cluster-trend prediction holds on Linux.

## Implementation checklist

- Add `PacingInputStream` (package `com.swirlds.benchmark.reconnect.network`), inserted as the bottom read wrapper on
  both directions in `LoopbackSocketTransport`.
- Effective window `W = sender.getSendBufferSize() + receiver.getReceiveBufferSize()`, read live per window.
- Single `park` to max-eligible time; `read` never returns `0`; EOF latched.
- Keep `BufferedInputStream`; do **not** add the single-byte override (document the dependency).
- Do **not** raise `SO_TIMEOUT`; do **not** set the accepted send buffer.
- Shutdown-wake (flag + unpark): optional.
- Keep reconnect wall-clock primary; add the secondary diagnostics as optional readouts.
- Independent from `SimulatedNetworkChannel` — no shared code.
