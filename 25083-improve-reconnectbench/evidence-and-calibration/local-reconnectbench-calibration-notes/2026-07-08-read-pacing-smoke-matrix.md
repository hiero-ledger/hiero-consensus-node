# 2026-07-08 Read-Pacing Smoke Matrix (5M state, local macOS)

Status: `smoke validation of the read-pacing implementation`
Run date: `2026-07-08`
Implements: [`ReconnectBench-socket-buffer-read-pacing-design.md`](../../design-and-implementation/ReconnectBench-socket-buffer-read-pacing-design.md)
Related evidence: [`2026-07-08-socket-buffer-probe.md`](2026-07-08-socket-buffer-probe.md),
[`new-5m-local.md`](new-5m-local.md) (the pre-pacing null result these runs supersede)

## Purpose

First end-to-end validation that, with the read-side pacer (`PacingInputStream`) in place, changing the socket
send/receive buffer size in production `SocketFactory` moves reconnect wall-clock — and does so **only** in the
parameter regime where the window physically binds, with no fabricated effect at the cluster-calibrated control point.

Smoke scope only: small (5M) state, 3 single-shot iterations per cell, one fork, weak laptop. Trend evidence, not
absolute-time calibration. Larger-state runs are planned separately.

## Environment

- Darwin 23.5.0 (macOS), loopback; Temurin OpenJDK 25.0.3; JMH via Gradle task (`-Xms2g -Xmx8g`)
- State: existing saved 5M-leaf state (`data/ReconnectBench/{teacher,learner}/saved0`), restored per trial;
  `keySize=32, maxKey=10000000, numFiles=10, numRecords=100, recordSize=128, numThreads=32, randomSeed=9823452658,
  add/modify/remove=0.1/0.3/0.0`; traversal `pullTopToBottom`
- Transport `LOOPBACK_SOCKET`, profile `REALISTIC`, bandwidth `200 Mbit/s` (25 MB/s), `networkInflightBytesLimit`
  ignored by socket transport
- Code: working tree with the read-pacing implementation (write path raw; read-side pacer, REALISTIC only;
  `RTT = 2 x one-way latency`); all 30 unit tests passing at run time

Commands (one run per `SocketFactory` buffer edit):

```bash
./gradlew :swirlds-benchmarks:jmhReconnectLoopbackRealistic                                     # control, 270 us
./gradlew :swirlds-benchmarks:jmhReconnectLoopbackRealistic -PnetworkLatencyMicroseconds=50000  # binding, RTT 100 ms
```

## Buffer configs and connect-time readbacks (diagnostics)

| Config | server recv | client send | client recv | accepted send | accepted recv |
|---|---:|---:|---:|---:|---:|
| unset (autotuning on) | 131072 | 146988 | 408300 | 146988 | 408300 |
| 32 KiB set | 32768 | 65328 | 326640 | 146988 | 326640 |
| 1 MiB set | 1048576 | 1061580 | 1061580 | 146988 | 1061580 |

Accepted send is never set by `SocketFactory` (production behavior, recorded decision 5) — it starts at the OS default
and autotunes.

## Result matrix

`s/op` per iteration, then mean. Binding-leg per-direction expectation: throughput ≈ `min(25 MB/s, W / 0.1 s)` with
`W = sender send-buffer + receiver recv-buffer` (live).

| Leg | unset (autotuned) | 32 KiB set | 1 MiB set |
|---|---|---|---|
| **control** 270 µs (RTT 0.54 ms) | 116.6*, 53.6, 47.9 → mean 72.7 (warm ~50.8) | 60.1, 52.5, 48.2 → **mean 53.6** | 40.1, 38.0, 42.3 → **mean 40.1** |
| **binding** 50,000 µs (RTT 100 ms) | 108.3, 63.0, 69.1 → **mean 80.1** (median 69.1) | 94.9, 92.4, 111.9 → **mean 99.7** (median 94.9) | 47.8, 63.9, 54.6 → **mean 55.4** (median 54.6) |

\* first-iteration cold-start outlier (same pattern as the historical runs); warm mean shown.

## Live pacing readouts (per-direction `Socket read pacing` log)

- **Binding, 32 KiB:** t→l `lastWindowBytes` ~522–536 K (initial sum 391,968 — receive term autotuned up),
  `windowsOpened` ~883–958 per ~93 s ≈ one per ~103 ms (RTT cadence + park overshoot), `totalParkedMillis` ~62–68 s of
  ~93 s wall — the pacer, not CPU, dominated the leg. Realized t→l throughput ≈ 392 MB / 93 s ≈ 4.2 MB/s ≈ predicted
  W/RTT.
- **Binding, unset:** t→l `lastWindowBytes` grew 1.42 M → 2.24 M → 1.94 M across iterations (kernel autotuning under
  sustained window-bound load); iteration times improved 108.3 → 63.0 accordingly. The autotuning *is* the measured
  effect — captured live, per the design's bonus goal.
- **Binding, 1 MiB:** t→l `lastWindowBytes` 2,110,156 stable (explicitly set send is pinned; recv term near its set
  value), ~416–441 windows per iteration; l→t 1.47–1.75 M (accepted send autotuning above its 146,988 default).
- **Control (all configs):** ~28–36 K windows per run (RTT 0.54 ms), unset `lastWindowBytes` grew to ~5.5 M by
  iteration 3 — far above the leg's 13.5 KB bandwidth-delay product, so the window never binds and the bandwidth
  cursor (~25 MB/s) governs, as designed.

## Interpretation

1. **Control leg: no buffer effect — correctly.** All three configs converge (~40–54 s/op, within the established
   run-to-run noise); at sub-ms RTT every window is orders of magnitude above the bandwidth-delay product. The pacer
   fabricates no effect where physics says there is none.
2. **Binding leg: clean, physically ordered separation.** 32 KiB (94.9) > unset (69.1) > 1 MiB (54.6) — ~1.7× spread
   from the `SocketFactory` setting alone, far above the ~26% noise floor documented in `new-5m-local.md`. Pinned-small
   throttles; unset partially escapes via autotuning; pinned-large approaches the bandwidth cap.
3. **Versus the pre-pacing transport:** `new-5m-local.md` showed 32 KiB ≈ 1 MiB ≈ 54 s/op at these cluster-calibrated
   parameters (write-side shaper starved the kernel buffer). The buffer signal now exists at the binding leg, and the
   control leg additionally runs somewhat faster (~40–54 vs ~54) because the pacer no longer double-charges latency
   per write.

## Why unset is slower than 1 MiB pinned (binding leg: 69.1 vs 54.6 s/op median)

One line: **the pinned socket has its full window at byte zero; the unset socket must earn its window through
autotuning, and the throughput lost during the ramp is never repaid** — even though both end at a comparable window.

Mechanism, each step evidenced:

1. **Starting windows are ~4x apart.** At connect, unset W = 146,988 + 408,300 ≈ 0.55 MB → ~5.5 MB/s at RTT 100 ms;
   pinned 1 MiB W ≈ 2.11 MB → ~21 MB/s from the first byte (readbacks in the config table above).
2. **The ramp is visible in the live-W trajectories.** The unset run *ended* iteration 1 at only 1.42 MB — after 108 s
   it still had less window than the 1 MiB config had at t=0. Iteration times track the ramp exactly: 108.3 → 63.0 →
   69.1 s/op as end-of-run W reached 1.42 → 2.24 → 1.94 MB. The 1 MiB run's W was 2,110,156 in every iteration, flat,
   with flat times.
3. **The ramp is a feedback loop, so it is inherently late** (probe 3 in
   [`2026-07-08-socket-buffer-probe.md`](2026-07-08-socket-buffer-probe.md)): autotuning grows the buffer in
   proportion to delivered bytes, but delivery is capped by the current window — slow-start-like convergence from
   below. With an aggressive drain the ramp takes ~1 s; under the window-capped drain of a real (or paced) transfer it
   takes tens of seconds. It is also **restarted by every fresh connection** (no carryover between sockets) and
   **non-monotone** (the kernel adjusts it down during slow-drain phases; a pin never moves).
4. **Steady state converges; the integral does not.** Autotuned W plateaus at ~2.3 MB ≈ the pinned 2.11 MB, which is
   why unset's later iterations (63-69 s) approach the 1 MiB cell (~55 s) without reaching it: average window over the
   whole transfer stays below final window.

### Cluster mapping (why this matches the pinned-1 MiB cluster observation)

- The cluster exhibited the same ramp, live, during a real reconnect: the teacher's fresh reconnect socket appeared
  with `rb=65536` and autotuned to `4,604,312` **within the reconnect window**
  ([`socket-evidence.md`](../extracted-cluster-evidence/2026-07-04-cluster-calibration/socket-evidence.md), node4).
  Reconnect sockets are created per attempt, so every attempt pays the ramp again.
- On Linux the pin is stronger than on macOS: an explicit set doubles the requested value and applies immediately
  (1 MiB → ~2 MB at byte zero), while unset starts at the ~128 KB `tcp_rmem` default.
- Three compounding mechanisms favor the pin at large state (the colleague's 300-500M threshold): (a) aborted/retried
  reconnects restart the ramp on each fresh socket; (b) autotuning backs off when the learner's consumer (hashing)
  stalls — the cluster showed `rwnd_limited` stalls — while a pin holds through every stall; (c) window-starved phases
  stretch individual waits toward reconnect timeouts, converting "slow" into "aborts", which feeds back into (a).
- Caveat: locally the window binds via injected RTT; on the sub-ms cluster it binds via queueing/drain
  (`rwnd_limited`). The pin-vs-ramp asymmetry is mechanism-independent — whatever makes the window matter,
  pinned-large has it instantly and unconditionally.
- **To verify on the cluster re-test:** capture `ss -tinm` on the reconnect socket with the 1 MiB pin active and check
  whether `rb` stays pinned (~2 MB) or still autotunes — the 2026-07-04 batch showed 32 KB-configured sockets
  autotuning to multi-MB, which standard Linux set-disables-moderation semantics should not allow, so it is still open
  whether the configured value reaches the socket that carries the reconnect.

## Discarded runs (recorded for hygiene)

- 32 KiB control, mean 76.4 (70.4/73.5/85.4): ran concurrently with a heavy multi-agent review workload on the same
  laptop; superseded by the clean rerun (mean 53.6).
- 1 MiB control, first attempt: laptop slept mid-iteration (iteration 1 reported 141.1 s/op); superseded by the clean
  rerun (mean 40.1).

## Caveats and follow-ups

- Smoke only: N=3 single-shot, one fork, weak machine — trend evidence. Repeat on the stronger machine with larger
  state; the cluster trend remains the arbiter.
- macOS numbers; Linux clamps/autotunes differently — re-check the matrix shape on a cluster-matched Linux host before
  cluster conclusions.
- Latency is modeled as a throughput window only (recorded decision 6): socket and simulated transports are not
  comparable at the same `networkLatencyMicroseconds`.
- `SocketFactory` remains a local experiment vehicle (left at the 1 MiB state after these runs); revert it to `main`
  before any PR — the read-pacing change itself is benchmark-only.
