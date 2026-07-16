# 2026-07-16 Read-Pacing Matrix (10M state, fresh local pairing)

Status: `completed local validation`
Run date: `2026-07-16`
Repeats: [`2026-07-08-read-pacing-smoke-matrix.md`](2026-07-08-read-pacing-smoke-matrix.md)
Implements: [`ReconnectBench-socket-buffer-read-pacing-design.md`](../../design-and-implementation/ReconnectBench-socket-buffer-read-pacing-design.md)

## Purpose

Repeat the July 8 socket-buffer/read-pacing matrix with a newly generated 10M base state. The machine, OS, JVM patch
version, heap shape, and state all differ from the July 8 run, so this is a **fresh internally paired comparison**. The
July 8 absolute times are not a baseline for these results; only the qualitative matrix shape is compared afterward.

The tested factors are production `SocketFactory` buffer configuration (`unset`, `32 KiB`, and `1 MiB`) and one-way
latency (`270 us` control and `50,000 us` candidate binding leg). Every measured cell restores the same saved state.

## Environment and protocol

- Machine: MacBook Pro `Mac15,9`, Apple M3 Max (16 cores), 48 GB memory; AC power at capture time.
- OS: macOS 26.5, Darwin 25.5.0.
- JVM: Temurin OpenJDK 25.0.2+10 LTS; JMH 1.37.
- Code: `6f680a1b21f721242e6d336a0c875e486e148d8f` on branch
  `25083-improve-reconnect-bench-socket-net`.
- JVM options: current `swirlds-benchmarks` defaults, `-Xms24g -Xmx24g -XX:+AlwaysPreTouch`; no `caffeinate` or other
  sleep-prevention wrapper.
- JMH: one fork, no warmup, three single-shot measurement iterations per cell. The annotation was temporarily changed
  from one to three iterations and restored after the matrix.
- Transport: loopback socket, `REALISTIC`, 200 Mbit/s (25,000,000 B/s); the read-side pacer models
  `RTT = 2 * one-way latency`.
- Traversal: `pullTopToBottom`; verification disabled.
- Raw local evidence: `platform-sdk/swirlds-benchmarks/build/reconnectbench-10m-2026-07-16/` contains the full console
  log, JMH result, GC log, `settingsUsed.txt`, and `SocketFactory.java` snapshot for prep and each measured cell.

The state directory had been cleared before the experiment. Prep generated and saved one canonical state with:

```text
numFiles=1000
numRecords=10000
base records=10,000,000
randomSeed=9823452658
teacherAddProbability=0.10
teacherModifyProbability=0.40
teacherRemoveProbability=0.00
maxKey=10000000
keySize=32
recordSize=128
numThreads=32
```

Observed state sizes were learner `9,999,999` and teacher `11,150,666`; the saved learner/teacher directories occupy
about `3.0G`/`3.3G` (`6.3G` total). The teacher is larger than the simple base-plus-10%-adds estimate because the
current builder's modify phase can put a value under an absent key when additions have made its sampled dense key
range larger than the actually populated sparse range. This observed shape was held fixed for the whole matrix.

Relevant settings remained:

```text
benchmark.benchmarkData=data
benchmark.saveDataDirectory=true
benchmark.verifyResult=false
virtualMap.reconnectMode=pullTopToBottom
```

Prep produced one discarded `78.483 s/op` measurement after generating the state. Every measured iteration then logged
both teacher and learner restoration from `data/ReconnectBench/{teacher,learner}/saved0`.

The measured command shape was:

```bash
./gradlew :swirlds-benchmarks:jmhReconnect \
  -PnumFiles=1000 -PnumRecords=10000 -PrandomSeed=9823452658 \
  -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 -PteacherRemoveProbability=0.00 \
  -PmaxKey=10000000 -PkeySize=32 -PrecordSize=128 -PnumThreads=32 \
  -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200 \
  -PnetworkLatencyMicroseconds=<270-or-50000> --console=plain
```

Run order was deliberately interleaved: unset/control, 32 KiB/binding, 1 MiB/control, unset/binding, 32 KiB/control,
1 MiB/binding. `SocketFactory.java` was edited between cells and compiled each time.

## Buffer configurations and connect-time readbacks

The effective values matched the July 8 macOS readbacks exactly:

| Config | server recv | client send | client recv | accepted send | accepted recv |
|---|---:|---:|---:|---:|---:|
| unset (OS defaults/autotuning) | 131072 | 146988 | 408300 | 146988 | 408300 |
| 32 KiB set | 32768 | 65328 | 326640 | 146988 | 326640 |
| 1 MiB set | 1048576 | 1061580 | 1061580 | 146988 | 1061580 |

Accepted send is not set by `SocketFactory`; its connect-time value remains the OS default.

## Result matrix

Each entry lists the three single-shot iterations, JMH mean, median, and the mean of iterations 2-3 (`warm`).

| Leg | unset (autotuned) | 32 KiB set | 1 MiB set |
|---|---|---|---|
| **control**, 270 us | 73.814, 77.798, 75.860 → mean **75.824**, median 75.860, warm 76.829 | 76.011, 78.101, 80.678 → mean **78.264**, median 78.101, warm 79.390 | 77.588, 76.942, 81.577 → mean **78.702**, median 77.588, warm 79.260 |
| **candidate binding**, 50,000 us | 87.056, 75.544, 73.230 → mean **78.610**, median 75.544, warm 74.387 | 196.831, 197.674, 197.582 → mean **197.362**, median 197.582, warm 197.628 | 74.852, 76.501, 75.704 → mean **75.686**, median 75.704, warm 76.103 |

Within-config latency effects:

| Config | control mean | 50 ms mean | 50 ms / control | median ratio | Interpretation |
|---|---:|---:|---:|---:|---|
| unset | 75.824 | 78.610 | 1.037x | 0.996x | no repeatable wall-clock penalty after autotuning |
| 32 KiB | 78.264 | 197.362 | **2.522x** | **2.530x** | strongly window-bound |
| 1 MiB | 78.702 | 75.686 | 0.962x | 0.976x | no wall-clock penalty at this workload rate |

At 50 ms, the 32 KiB median is `2.615x` unset and `2.610x` 1 MiB. Unset and 1 MiB medians differ by only `0.2%`.
At 270 us, the largest mean-to-mean spread across all three buffer configurations is `3.8%`.

## Evidence invariants

All 18 measured iterations logged the same state shape and semantic work:

- learner/teacher sizes: `9,999,999` / `11,150,666`;
- reconnect counters: `internalCleanHashesTotal=422367`, `internalHashesTotal=3594241`,
  `leafCleanDataTotal=4666556`, `leafDataTotal=9772810`, and both transfer totals `13367051`;
- teacher-to-learner bytes: `878,400,066` written and read;
- learner-to-teacher bytes: `842,124,208` written and read;
- transport profile, bandwidth, latency, and effective socket diagnostics matched the intended cell;
- all six Gradle/JMH invocations completed successfully.

GC was active but does not explain the matrix separation. Across each three-iteration fork, aggregate reported pause
time ranged from about `3.8 s` to `6.6 s`; the maximum individual pause was below `0.9 s`. The 32 KiB/50 ms fork had
about `4.8 s` total GC pauses, far below its roughly `119 s/op` paired slowdown.

## Live pacing evidence

- **32 KiB, 50 ms:** teacher-to-learner `lastWindowBytes` stayed near `523 KiB`, with exactly `1885` windows per
  iteration and `162.6-163.9 s` parked. Roughly 1885 windows over 197 s is one window per 105 ms, matching the modeled
  100 ms RTT plus scheduling overhead. Teacher-to-learner realized about `4.45 MB/s`, close to the approximately
  `5.23 MB/s` end-window/RTT ceiling. This is the clean binding cell.
- **Unset, 50 ms:** teacher-to-learner end-window grew `1.19 MB → 1.46 MB → 2.01 MB`, while wall time improved
  `87.056 s → 75.544 s → 73.230 s`; windows fell `842 → 718 → 698` and parked time fell `65.2 s → 52.7 s → 51.0 s`.
  The first-connection autotuning ramp is visible, but later iterations converge with the pinned 1 MiB cell.
- **1 MiB, 50 ms:** teacher-to-learner end-window was fixed at `2,110,156` bytes, with `718-719` windows and
  `50.3-51.3 s` parked. Results were flat at `74.9-76.5 s/op`.
- **270 us controls:** every configuration opened roughly `60K-65K` windows per iteration. Despite different socket
  readbacks, all wall times stayed within the same narrow band, so the socket window did not control end-to-end time.

Parked totals overlap across directions and threads and must not be summed into wall-clock time; their value here is
the relative pattern and RTT cadence.

## Interpretation

1. **The control leg is clean.** Unset, 32 KiB, and 1 MiB means are within 3.8%, and their medians are similarly close.
   On this fresh pairing, changing only `SocketFactory` does not fabricate a low-RTT performance effect.
2. **The 32 KiB candidate-binding cell is unambiguously bound.** Its 50 ms run is about 2.53x its own control and 2.61x
   either larger-window 50 ms cell. Counters and bytes are identical, and GC is much too small to explain the gap.
3. **The 50 ms leg does not bind unset or 1 MiB at this workload's achieved rate.** The non-small cells move roughly
   `878 MB` teacher-to-learner in about 76 s, around `11.6 MB/s`. A 2.11 MB pinned window at 100 ms RTT permits about
   `21 MB/s`, comfortably above that rate. The unset window grows into roughly the `1.2-2.0 MB` range, enough to stop
   governing wall-clock after its startup ramp.
4. **The longer state amortizes unset's fresh-socket ramp.** Unset still pays a first-iteration cost (87.1 s versus
   74.9 s for pinned 1 MiB), but the two warm unset iterations average 74.4 s and the two warm 1 MiB iterations average
   76.1 s. The ramp remains observable without producing a stable whole-run penalty.

## What changed from July 8

Only internally normalized shapes are compared because the host environment and state changed.

- The core read-pacing result **reproduced and strengthened**: pinned-small is slow only at high RTT, while all three
  controls agree. The new 32 KiB/1 MiB binding median ratio is `2.61x`; July 8 reported about `1.74x`.
- The July 8 three-way binding order (`32 KiB > unset > 1 MiB`) did **not** reproduce. The 10M result is instead
  `32 KiB >> unset ≈ 1 MiB`; the new unset/1 MiB binding median ratio is `1.00x`, versus about `1.27x` on July 8.
- The live evidence explains the changed shape: unset ramps toward the 1 MiB window during iteration 1 and catches it
  in wall-clock by iterations 2-3. With twice the base records, the fixed ramp cost is a smaller fraction of the full
  transfer, while the achieved non-small-cell rate remains below both cells' post-ramp window ceilings.

The correct conclusion is therefore narrower than "50 ms always separates every buffer setting": **read pacing makes
an actually undersized socket window visible in reconnect wall-clock; whether unset separates from 1 MiB depends on
the state, host, achieved application rate, and how much of the transfer is spent ramping.**

## Caveats and cleanup

- N=3, one fork, no warmup, and a fixed interleaved run order: strong local mechanism evidence, not absolute-time
  calibration or a statistical performance claim.
- This is macOS loopback. Linux socket clamping and autotuning must still be checked on the target cluster.
- No attempt is made to compare absolute seconds with July 8 because the machine environment and benchmark state are
  not controlled across dates.
- The prep measurement is excluded because it includes generation/save setup before reconnect.
- After the matrix, `ReconnectBench.java` was restored to one measurement iteration and `SocketFactory.java` to its
  committed 1 MiB configuration. Those files and `settings.txt` have no tracked diff.
