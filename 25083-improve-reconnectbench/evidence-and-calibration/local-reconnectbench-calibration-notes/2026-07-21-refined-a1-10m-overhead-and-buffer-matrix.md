# 2026-07-21 Refined-A1 10M Overhead And Socket-Buffer Matrix

Status: `completed exploratory validation; accounting/lifecycle pass, overhead and timing acceptance gates fail`
Run date: `2026-07-21`
Historical comparison: [`2026-07-16-read-pacing-10m-matrix.md`](2026-07-16-read-pacing-10m-matrix.md)
Design under test:
[`2026-07-21-refined-a1-socket-network-design-and-real-network-gap-analysis.md`](../../design-and-implementation/2026-07-21-refined-a1-socket-network-design-and-real-network-gap-analysis.md)

## Executive Conclusion

The experiment produced a useful but deliberately mixed result.

1. **Refined A1 is mechanically sound in this run.** All 18 matrix iterations and all four instrumented controls
   transferred exactly the expected bytes, drained all metadata, closed cleanly, and reproduced the same reconnect
   work counters.
2. **Its plumbing is not cheap.** `INSTRUMENTED_LOOPBACK`, which retains the A1 observer/gate/range machinery but
   disables shaping, was slower than raw `LOOPBACK` in all four adjacent pairs. The geometric-mean slowdown was
   `19.51%`, with pair ratios from `1.162x` to `1.242x`.
3. **The important old stress trend did reappear.** At `50 ms` one-way latency, pinned `32 KiB` sockets were about
   `2.00x` slower than unset sockets and `2.14x` slower than pinned `1 MiB` sockets. Within the `32 KiB`
   configuration, `50 ms / 270 us` was `1.725x` by mean.
4. **The full old correctness shape did not reappear.** At the `270 us` control, `32 KiB` was already `13.7%` slower
   than unset by mean and `15.5%` by median. The earlier matrix's clean low-latency control had only about `3.8%`
   maximum mean spread.
5. **The predeclared timing gates failed.** Recorded release-lateness p99 failed in all `36/36` direction-iterations.
   The raw-write union limit passed in only `5/36` directions and in no complete iteration. Therefore no matrix
   iteration is timing-accepted.

The narrow supported conclusion is:

> Even without the old explicit periodic `W / RTT` rule, refined A1 exposes a stable high-latency/small-socket-buffer
> penalty through withheld reads and natural loopback socket pressure.

The experiment does **not** establish that refined A1 is a sufficiently low-overhead or timing-accurate final
`REALISTIC` transport. It also does not validate traversal ranking, because this matrix intentionally ran only
`pullTopToBottom`.

## Questions Tested

The run answered two bounded questions on one fresh 10-million-record state:

1. Does A1's observer, metadata, bounded-range splitting, input gate, and diagnostics materially change reconnect
   time when latency and bandwidth waits are disabled?
2. Does refined A1, which has no software window or periodic `W / RTT` release rule, still expose the historical
   interaction between a small real socket buffer and high configured latency?

This was not a traversal-order comparison, an absolute-time calibration, or a claim of real configured-RTT TCP.

## Implementation Under Test

Refined A1 keeps one real full-duplex loopback TCP connection and the production sync-stream stack. For each direction:

- an output observer splits each caller write into bounded compressed-payload ranges, records each range, and delegates
  each range as a separate raw socket write;
- the opposite input gate refuses to consume a range from the receiving kernel buffer before its sender-relative
  latency and progressive-bandwidth eligibility time;
- the OS socket buffers remain the only transport-level payload storage and the only capacity that can block Java
  writes;
- there is no explicit Java `W`, initial ticket count, delayed credit pool, or periodic `W / RTT` window.

The three profiles had different experimental roles:

| Profile | A1 machinery | Modeled latency/bandwidth | Role |
|---|---|---|---|
| `LOOPBACK` | absent | `0 / unlimited` | raw production-stream/socket floor |
| `INSTRUMENTED_LOOPBACK` | present | `0 / unlimited` | isolate observer/gate/range/metadata overhead |
| `REALISTIC` | present | configured `L / B` | refined-A1 shaped experiment |

An exact union diagnostic, `rawWriteBytesOverEitherTarget`, was added before the run. It counts a range once when its
raw write exceeds either the quarter-latency limit or that range's target serialization duration, avoiding double
counting when both are exceeded.

## Environment

- Machine: MacBook Pro `Mac15,9`, Apple M3 Max, 16 cores, 48 GiB memory.
- Power: AC, charging at preflight.
- OS: macOS `26.5` build `25F71`; Darwin `25.5.0`; arm64.
- JVM: Temurin OpenJDK `25.0.2+10` LTS; Java 25.
- JMH: `1.37`, one fork, no warmup, `SingleShotTime`.
- Heap: `-Xms24g -Xmx24g -XX:+AlwaysPreTouch`.
- Branch: `25083-improve-reconnect-bench-socket-net`.
- Run-start HEAD: `06dc95c783d3beba8692413761e3d0037134e4a4`.
- Free disk at preflight: about `295 GiB`.
- Sleep prevention: all 10M measured commands ran under `caffeinate -i`.

The raw artifact root is:

```text
platform-sdk/swirlds-benchmarks/build/reconnectbench-refined-a1-10m-2026-07-21/
```

It contains the console log, JMH result, GC log, exact `settings.txt`, resolved `settingsUsed.txt`, and exact
`SocketFactory.java` variant for every overhead and matrix invocation.

## Fixed Workload And Saved State

```text
numFiles=1000
numRecords=10000
requested base records=10,000,000
randomSeed=9823452658
teacherAddProbability=0.10
teacherModifyProbability=0.40
teacherRemoveProbability=0.00
maxKey=10000000
keySize=32
recordSize=128
numThreads=32
socket.gzipCompression=false
virtualMap.reconnectMode=pullTopToBottom
benchmark.verifyResult=false
```

One canonical state was generated under raw `LOOPBACK`; its preparation reconnect was discarded from comparisons.

| Property | Value |
|---|---:|
| Learner size | `9,999,999` |
| Teacher size | `11,150,666` |
| Learner snapshot | about `3.0 GiB` |
| Teacher snapshot | about `3.3 GiB` |
| Combined exact bytes | `6,755,529,162` |
| Files | `76` |
| Aggregate sorted-file SHA-256 | `e661d729f3be613cdc57c30ffc579ee88cc8fd9d9d0402f454a8bf832dce39cb` |
| Discarded preparation score | `58.170 s/op` |
| Preparation Gradle time | `2m52s` |

Every measured Gradle invocation restored this state once per JMH fork. The three matrix measurements inside a fork
then reused that restored trial state; they did not restore it three times.

## Preflight

Before state generation:

- the focused `SocketVisibilityControllerTest` selection passed `13` tests;
- the complete `swirlds-benchmarks` test task passed `54` tests;
- `compileJmhJava` passed;
- `spotlessCheck` passed;
- a verified `1,000`-record `REALISTIC` smoke completed through the production reconnect stack at
  `270 us / 200 Mbit/s`, verified the result, and scored `0.289 s/op`.

The verified smoke already reproduced the timing warning:

| Direction | Observed bytes | Release p99 | Union bytes | Union ratio |
|---|---:|---:|---:|---:|
| teacher -> learner | `91,014` | `398,709 ns` | `6,624` | `7.278%` |
| learner -> teacher | `76,918` | `114,041 ns` | `4,900` | `6.370%` |

Both directions drained and closed correctly, but both exceeded the `67,500 ns` and `1%` gates. The smoke also logged
a `NoSuchFileException` while attempting to write optional metrics CSV output because its metrics directory had not
yet been created. The JMH build and reconnect verification succeeded, so this did not invalidate the smoke; the
console log is retained rather than hidden.

## Run Protocol

### Overhead screen

Eight separate one-iteration invocations ran in this fixed counterbalanced order:

```text
LOOPBACK, INSTRUMENTED_LOOPBACK, INSTRUMENTED_LOOPBACK, LOOPBACK,
LOOPBACK, INSTRUMENTED_LOOPBACK, INSTRUMENTED_LOOPBACK, LOOPBACK
```

Adjacent opposite-profile runs formed four pairs. This is a practical material-overhead screen, not a formal
equivalence confidence interval.

### Historical matrix repeat

The matrix used `REALISTIC`, `200 Mbit/s` per direction, and this interleaved order:

| Cell | Requested kernel socket buffer | One-way latency | Measurements |
|---:|---:|---:|---:|
| 1 | OS default | `270 us` | 3 |
| 2 | `32 KiB` | `50,000 us` | 3 |
| 3 | `1 MiB` | `270 us` | 3 |
| 4 | OS default | `50,000 us` | 3 |
| 5 | `32 KiB` | `270 us` | 3 |
| 6 | `1 MiB` | `50,000 us` | 3 |

The user explicitly approved temporary production `SocketFactory.java` edits between cells to reproduce the earlier
experiment. The listener receive buffer and client send/receive buffers were requested before bind/connect; accepted
send remained untouched. Each exact source variant was captured, and the production file was restored byte-for-byte
after cell 6. These variants are controls, not proposed production changes.

The command shape was:

```bash
./gradlew :swirlds-benchmarks:jmhReconnect \
  --init-script <three-measurement-init-script> \
  -PnumFiles=1000 -PnumRecords=10000 -PrandomSeed=9823452658 \
  -PteacherAddProbability=0.10 -PteacherModifyProbability=0.40 \
  -PteacherRemoveProbability=0.00 -PmaxKey=10000000 \
  -PkeySize=32 -PrecordSize=128 -PnumThreads=32 \
  -PnetworkProfile=REALISTIC -PnetworkBandwidthMegabitsPerSecond=200 \
  -PnetworkLatencyMicroseconds=<270-or-50000> --console=plain
```

## Effective Socket Readbacks

The effective values exactly matched the July 16 host readbacks:

| Config | Server receive | Client send | Client receive | Accepted send | Accepted receive |
|---|---:|---:|---:|---:|---:|
| unset | `131072` | `146988` | `408300` | `146988` | `408300` |
| requested `32 KiB` | `32768` | `65328` | `326640` | `146988` | `326640` |
| requested `1 MiB` | `1048576` | `1061580` | `1061580` | `146988` | `1061580` |

## Instrumented-Loopback Overhead Result

| Pair | Raw `LOOPBACK` | `INSTRUMENTED_LOOPBACK` | Added seconds | Instrumented/raw |
|---:|---:|---:|---:|---:|
| 1: runs 1/2 | `49.714` | `61.726` | `12.012` | `1.241622x` |
| 2: runs 4/3 | `53.752` | `62.903` | `9.151` | `1.170245x` |
| 3: runs 5/6 | `53.084` | `61.684` | `8.600` | `1.162007x` |
| 4: runs 8/7 | `52.736` | `63.711` | `10.975` | `1.208112x` |

For pair ratio `r_i = instrumented_i / raw_i`:

- geometric mean: `exp(mean(log(r_i))) = 1.195076x`, or **19.51% slower**;
- median ratio: `1.189178x`, or **18.92% slower**;
- range: `1.162007x` to `1.241622x`;
- raw mean/range: `52.3215 s`, `49.714-53.752 s`;
- instrumented mean/range: `62.5060 s`, `61.684-63.711 s`.

All four instrumented runs behaved as a true pass-through control:

- modeled latency was `0`, modeled bandwidth was unlimited, and shaping flags were false;
- `observed == scheduled == returned` in both directions;
- timing wakes, latency waits, bandwidth waits, release-lateness samples, serialization backlog, pending bytes, and
  failed raw I/O were zero at completion;
- the target-derived range cap remained `675` bytes;
- all controllers ended `CLOSED`.

Recorded GC pause time does not explain the slowdown. Raw-loopback GC pause totals were `1.454-1.896 s`;
instrumented totals were `1.097-1.426 s`. Instrumented runs converted the reconnect payload into about `2.705
million` bounded raw socket writes per reconnect, retained nonzero metadata/raw-write work, and were slower in every
pair. With `TCP_NODELAY=true`,
this is a material change to syscall and TCP write shape, not passive observation.

This refutes the premise that the A1 machinery is negligible at this workload. It also means a shaped `REALISTIC`
score contains substantial controller cost. That cost cannot be safely subtracted as a constant because the matrix
shows that socket buffers and shaping change range timing, raw-write stalls, and metadata behavior.

## Refined-A1 Matrix Result

“Warm mean” below is only the mean of iterations 2-3; JMH itself used no warmup.

| Buffer / latency | Iterations, s/op | Mean | Median | Warm mean | CV |
|---|---|---:|---:|---:|---:|
| unset / `270 us` | `65.493, 64.520, 63.134` | **64.382** | `64.520` | `63.827` | `1.84%` |
| `32 KiB` / `270 us` | `74.945, 74.501, 70.239` | **73.228** | `74.501` | `72.370` | `3.55%` |
| `1 MiB` / `270 us` | `69.282, 65.318, 63.146` | **65.915** | `65.318` | `64.232` | `4.72%` |
| unset / `50 ms` | `63.283, 62.485, 63.766` | **63.178** | `63.283` | `63.126` | `1.02%` |
| `32 KiB` / `50 ms` | `125.180, 127.668, 126.018` | **126.289** | `126.018` | `126.843` | `1.00%` |
| `1 MiB` / `50 ms` | `58.930, 57.058, 61.032` | **59.007** | `58.930` | `59.045` | `3.37%` |

Within each buffer configuration:

| Buffer | `270 us` mean | `50 ms` mean | Mean ratio | Median ratio | Warm ratio |
|---|---:|---:|---:|---:|---:|
| unset | `64.382` | `63.178` | `0.981x` | `0.981x` | `0.989x` |
| `32 KiB` | `73.228` | `126.289` | **1.725x** | **1.691x** | **1.753x** |
| `1 MiB` | `65.915` | `59.007` | `0.895x` | `0.902x` | `0.919x` |

At `50 ms`:

- `32 KiB / unset` was `1.999x` by mean and `1.991x` by median;
- `32 KiB / 1 MiB` was `2.140x` by mean and `2.138x` by median;
- unset / `1 MiB` was `1.071x` by mean and `1.074x` by median.

At `270 us`, the maximum mean spread was `1.137x`; the `32 KiB` mean was `13.7%` above unset. Therefore the
low-latency leg is not a clean buffer-neutral control in this implementation.

The two latency legs also use different algorithmic range caps: `675` bytes at `270 us` and `1,250` bytes at `50 ms`.
Consequently, a reconnect creates about `2.705 million` bounded raw writes at `270 us` but about `1.482 million` at
`50 ms`, roughly `45%` fewer. Within-buffer latency ratios therefore mix configured timing with different raw-write
granularity. The stable `32 KiB / 50 ms` separation remains useful interaction evidence, but the ratios are not a pure
latency treatment.

### GC context

| Cell | Aggregate GC pauses across three measurements | Maximum pause |
|---|---:|---:|
| unset / `270 us` | `3.994 s` | `564 ms` |
| `32 KiB` / `50 ms` | `2.994 s` | `291 ms` |
| `1 MiB` / `270 us` | `3.646 s` | `316 ms` |
| unset / `50 ms` | `5.097 s` | `397 ms` |
| `32 KiB` / `270 us` | `3.657 s` | `344 ms` |
| `1 MiB` / `50 ms` | `5.113 s` | `365 ms` |

The uniquely slow `32 KiB / 50 ms` cell had the lowest aggregate pause time. Recorded GC pause time does not explain
its roughly `63-67 s/op` separation from the other `50 ms` cells.

## Accounting And Lifecycle Invariants

All 18 matrix iterations logged the same semantic work:

```text
learnerSize=9,999,999
teacherSize=11,150,666
internalCleanHashesTotal=422,367
internalHashesTotal=3,594,241
leafCleanDataTotal=4,666,556
leafDataTotal=9,772,810
transfersFromLearnerTotal=13,367,051
transfersFromTeacherTotal=13,367,051
teacher -> learner=878,400,066 bytes
learner -> teacher=842,124,208 bytes
```

For every one of the `36` directional matrix observations:

- network bytes written equaled network bytes read;
- controller observed, scheduled, and returned bytes equaled that direction's network total;
- pending ranges and bytes were zero;
- failed raw reads and writes were zero;
- maximum range size equaled, and never exceeded, the configured cap;
- the controller ended `CLOSED`.

These facts make the runs valid implementation and exploratory trend evidence. They do not make the timing model
accepted.

## Frozen Timing-Gate Results

The gates were fixed before the 10M measurements:

```text
releaseLatenessP99Nanos <= 0.25 * configured one-way latency
rawWriteBytesOverEitherTarget / observedBytes <= 1%
```

| Leg | Release-p99 limit | Observed p99 range | P99 passes | Union-ratio range | Union passes |
|---|---:|---:|---:|---:|---:|
| `270 us` | `67.5 us` | `16.777-177.157 ms` | `0/18` directions | `0.935-6.263%` | `2/18` directions |
| `50 ms` | `12.5 ms` | `16.777-134.218 ms` | `0/18` directions | `0.868-2.738%` | `3/18` directions |
| all | profile-specific | `16.777-177.157 ms` | **`0/36`** | `0.868-6.263%` | **`5/36`** |

No complete iteration passed the union gate because teacher -> learner failed it in all `18/18` iterations. No
direction passed both timing gates, so complete timing acceptance was `0/18` iterations.

The reported p99 uses a conservative base-two histogram bucket upper bound. In the three `32 KiB / 50 ms`
teacher-to-learner observations, only `0.171-0.442%` of release samples were actually over `L/4`; their exact empirical
p99 can therefore be inside `12.5 ms` even though the recorded p99 bucket is `16.777 ms`. This nuance does not rescue
any complete iteration: the opposite direction and the raw-write union still fail. The `270 us` failures are
unambiguous, with roughly `38.8-65.9%` of releases over the limit.

In every smoke and matrix direction, the union byte count equaled the serialization-duration violation count. The
union failure was therefore dominated by writes exceeding a very small per-range target serialization time (up to
`27 us` at the `270 us` target and up to `50 us` at `50 ms`; shorter ranges have proportionally smaller targets), not
by double counting. At `50 ms`, only about
`0.034-0.390%` of bytes exceeded the much larger `12.5 ms` quarter-latency write limit, yet most directions still
failed the stricter union gate. This records local raw-socket/write-scheduling interference with pre-write timestamps;
it is not byte loss.

## Live Wait And Backpressure Evidence

Bandwidth shaping was active in every `REALISTIC` cell, but cumulative waits in the dedicated bandwidth branch were
small: `0-58.5 ms` overall and at most `0.146 ms` in the `50 ms` cells. This does not mean bandwidth was disabled.
Every direction accumulated a nonzero serialization backlog, but latency/metadata waiting normally covered the
serialization deadline before the reader reached the bandwidth-wait branch. The achieved application transfer rate
was also below the configured `25,000,000 B/s` payload ceiling in the non-small-buffer cells.

The clearest mechanism evidence is teacher-to-learner cumulative latency waiting:

| Cell | Teacher -> learner latency wait per iteration | Maximum pending bytes | Maximum serialization backlog |
|---|---:|---:|---:|
| unset / `270 us` | `5.68-6.10 s` | `2.13 MB` | `2.02 MB` |
| `32 KiB` / `270 us` | `7.80-8.52 s` | `0.62 MB` | `0.57 MB` |
| `1 MiB` / `270 us` | `5.30-6.75 s` | `2.64 MB` | `2.54 MB` |
| unset / `50 ms` | `24.63-25.06 s` | `3.65 MB` | `2.29 MB` |
| `32 KiB` / `50 ms` | **`87.19-93.03 s`** | `0.63 MB` | `0.42 MB` |
| `1 MiB` / `50 ms` | `20.18-22.26 s` | `2.64 MB` | `2.16 MB` |

The small buffer sharply limits the maximum controller-pending observed prefix and forces much more gate waiting at
high latency. This metric is a controller-backlog proxy, not a direct measurement of socket occupancy; the inference
is that the smaller socket capacity contributes to the observed pressure. Once the gate reads and frees receive
capacity, however, any resulting advertised-window update is not subjected to configured reverse-path latency. When
the OS sends the update, it crosses loopback; TCP may still batch or delay it. This explains both why the
high-latency/small-buffer trend exists and why it is weaker than the old explicit `W / RTT` matrix.

Wait totals across directions and reconnect threads must not be added directly to wall-clock time. Their value here is
the stable relative pattern.

## Comparison With The July 16 Read-Pacing Matrix

Only normalized shapes are compared. Although the host and state signature match, the timing implementation and
source revision differ, so absolute seconds are not treated as a baseline.

### High-latency effect within each buffer

| Buffer | Current `50 ms / 270 us` mean | July 16 mean | Current median | July 16 median |
|---|---:|---:|---:|---:|
| unset | `0.981x` | `1.037x` | `0.981x` | `0.996x` |
| `32 KiB` | **`1.725x`** | **`2.522x`** | **`1.691x`** | **`2.530x`** |
| `1 MiB` | `0.895x` | `0.962x` | `0.902x` | `0.976x` |

### Buffers normalized to unset

| Leg | Current unset / `32 KiB` / `1 MiB` mean | July 16 mean |
|---|---:|---:|
| `270 us` | `1.000 / 1.137 / 1.024` | `1.000 / 1.032 / 1.038` |
| `50 ms` | `1.000 / 1.999 / 0.934` | `1.000 / 2.511 / 0.963` |

What reproduced:

- pinned `32 KiB / 50 ms` is uniquely and repeatably slow;
- unset and `1 MiB` do not acquire a positive high-latency penalty;
- socket readbacks, state, work counters, byte totals, and interleaved cell order match the earlier protocol;
- the separation is too large and stable to be explained by GC.

What did not reproduce:

- the high-latency separation is materially weaker than July 16;
- the old clean `270 us` buffer-neutral control failed because `32 KiB` is already slower;
- `1 MiB / 50 ms` is now about `6.6%` faster than unset by mean, rather than approximately equal;
- the earlier unset/`50 ms` first-iteration autotuning ramp did not recur;
- changing latency also changed the range cap and raw-write count, so the latency ratio is confounded by write
  granularity;
- strict release/write timing acceptance did not pass.

Thus the old high-RTT matrix is **partially reproduced as exploratory mechanism evidence**, not reproduced as a full
correctness condition for refined A1.

## What This Says About The Network Model

### Supported

- Refusing to consume ineligible bytes leaves them in the real receive buffer long enough for a deliberately small
  socket configuration to affect writer/gate progress.
- Natural loopback socket occupancy can therefore contribute useful backpressure signal without an explicit software
  `W / RTT` window.
- The high-latency/small-buffer interaction is not an artifact unique to the removed periodic pacer.

### Not supported

- Refined A1 does not reproduce configured-RTT TCP acknowledgements, receive-window-update travel, congestion control,
  packetization, loss, or jitter.
- Bytes still enter and are acknowledged by the receiving loopback kernel before their application-visibility time.
- The experiment does not show that configured latency or bandwidth is realized with the predeclared precision.
- The `19.5%` plumbing cost means `REALISTIC` is not simply raw sockets plus network delay.
- One `pullTopToBottom` matrix says nothing about whether traversal-order ranking is preserved.

The result is therefore useful for socket-buffer sensitivity research, but not sufficient to promote this prototype
as a trusted real-network simulator.

## Consequences For The Three Profiles

`LOOPBACK` remains valuable as the actual production-stream/socket integration floor. It instantiates no A1 machinery
and is the only profile that answers “what does this reconnect do over local TCP without the model?”

The overhead result also answers the earlier question about removing `INSTRUMENTED_LOOPBACK`:

- removing only its public enum value would **not** remove the same machinery from `REALISTIC`;
- it would remove the control that exposed the roughly `19.5%` cost;
- therefore it should not be removed merely on the assumption that A1 machinery is free.

This does not require three permanent user-facing modes. If refined A1 is retained and redesigned, the pass-through
control can later become an internal/diagnostic benchmark path rather than a normal advertised network profile. If A1
is abandoned, both its shaped mode and this control can be removed together. That architecture decision should follow
the evidence; it should not hide the failed overhead control.

## Recommended Decision Point

The bounded experiment was worth running because it answered the open question: refined A1 can recreate the target
small-buffer/high-latency trend without the old synthetic periodic window. It also falsified the current prototype's
acceptance assumptions.

For a branch intended to be almost merge-ready, the evidence does **not** support immediately treating this
implementation as the final `REALISTIC` benchmark mode. The predeclared next step should be a design decision, not a
larger traversal matrix:

1. keep refined A1 only as an explicitly experimental socket-buffer tool and investigate a lower-overhead,
   better-timestamped implementation; or
2. use `SimulatedNetworkChannel` for the portable controlled traversal comparison and reserve real configured-TCP
   fidelity for a separate externally shaped multi-JVM/container or cluster test.

Running all traversal orders on the current A1 implementation would produce more numbers without first resolving the
known `16-24%` control overhead and failed timing gates.

## Invalid And Superseded Attempts

All deviations are retained:

1. The verified tiny smoke's optional metrics CSV directory was absent. Metrics-file output failed, but reconnect
   verification and JMH succeeded. The run is retained as functional/timing-gate evidence.
2. The first matrix invocation failed before JMH because the retained Gradle init-script closure implicitly resolved
   `path` against an included build. It was fixed to use an explicit `project` argument. No state or measurement was
   touched; `matrix/invalid-attempt-01-init-closure.log` is retained.
3. The successful cell-1 measurement was initially rejected by the wrapper after artifact capture because the wrapper
   expected three state restores and observed one. JMH correctly restores once per fork and runs three measurements on
   that trial state. The wrapper was corrected; the valid cell was not selectively rerun. The cell has one restore per
   map, three state/config/diagnostic/counter/byte sets, three scores, and `BUILD SUCCESSFUL`.

All fork logs also contain the same nonfatal Java 25/JMH `sun.misc.Unsafe::objectFieldOffset` deprecation warning and
an unknown-module warning for the merged JMH jar's `--add-exports` argument. They did not differ by experimental cell
or cause a build/reconnect failure, and are retained in the raw logs.

## Artifact Integrity And Cleanup

- Overhead artifacts: `48/48` expected files are present and nonempty (`8` runs x `6` captures).
- Matrix artifacts: `36/36` expected per-cell files are present and nonempty, plus the retained invalid-attempt log.
- All `14` measured console logs contain exactly one `BUILD SUCCESSFUL`, no `BUILD FAILED`, no OOM, no timeout, and no
  measured state generation.
- Historical raw evidence remained unchanged:
  - July 16 read-pacing aggregate SHA-256:
    `0a7f06cdb293eea92b7fcdab3b57d736a7c3f9eee721607be7f8b1082e84580a`;
  - July 16 compression aggregate SHA-256:
    `902a151d0fdc6b66c646461b6a6454cdc41a0423658b766172ed603637d9fedc`.
- Production `SocketFactory.java` was restored byte-for-byte to run-start SHA-256
  `e2d37b1e16f82b4e7b3d1323974d60dc131437228526adefa6685500029b217b`.
- Benchmark `settings.txt` was restored byte-for-byte to run-start SHA-256
  `a0fd38de007d850d3a8efed5b25200af8e5842dedd6984d3158df3b563c196d1`.
- No `clean`, branch, worktree, commit, or destructive Git command was used.

Final verification after restoration and documentation updates invoked:

```bash
./gradlew :swirlds-benchmarks:test \
  :swirlds-benchmarks:compileJmhJava \
  :swirlds-benchmarks:spotlessCheck --console=plain
```

It completed `BUILD SUCCESSFUL`. `git diff --check` also passed.
