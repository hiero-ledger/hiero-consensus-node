# 2026-07-21 Refined-A1 75M Traversal-Order Comparison

Status: **complete; all nine `ABC / BCA / CAB` measurements are documented (`n=3` per traversal)**.

This note is the completed record of three counterbalanced triples and three independent observations per traversal.

## Executive Summary

All three counterbalanced triples succeeded on the supplied large saved state:

| Symbol | Traversal | `ABC` | `BCA` | `CAB` | `n=3` mean | Rank in every round |
|---|---|---:|---:|---:|---:|---:|
| `A` | `pullTopToBottom` | `588.010` | `495.196` | `519.783` | **`534.330 s/op`** | 1 |
| `C` | `pullTwoPhasePessimistic` | `603.069` | `569.628` | `583.779` | **`585.492 s/op`** | 2 |
| `B` | `pullParallelSync` | `766.182` | `986.455` | `614.978` | **`789.205 s/op`** | 3 |

Every individual round, the means, and the medians have the same ordering:

```text
pullTopToBottom < pullTwoPhasePessimistic < pullParallelSync
```

This also matches both recorded same-state `SimulatedNetworkChannel` passes and the July 3 same-state socket runs.
The completed experiment therefore reproduces the requested traversal-order trend under all three execution positions.
It remains a small local descriptive experiment, not a general performance proof.

All 18 directional accounting/lifecycle records passed. All 18 records failed both frozen refined-A1 timing-quality
gates, consistent with the preceding 10M experiment. The runs are valid observations of the implemented hybrid
benchmark, but they do not establish that `263 us` is realized with the declared timing precision or that the loopback
TCP connection behaves like remote TCP at a `526 us` RTT.

## Questions And Campaign Protocol

The campaign asks whether refined A1 preserves the broad traversal-order trend on the same large saved state that was
used by the earlier socket experiment.

Symbols are fixed for the whole campaign:

| Symbol | Traversal |
|---|---|
| `A` | `pullTopToBottom` |
| `B` | `pullParallelSync` |
| `C` | `pullTwoPhasePessimistic` |

The approved counterbalanced schedule is:

| Round | Order | State after round |
|---:|---|---|
| 1 | `ABC` | **complete and documented; `n=1` per traversal** |
| 2 | `BCA` | **complete and documented; `n=2` per traversal** |
| 3 | `CAB` | **complete and documented; final `n=3` per traversal** |

Each letter is one independent Gradle/JMH invocation with one fresh benchmark JVM, one fork, no warmup, and one
single-shot measurement. Thus the complete campaign contains nine scored reconnects, three per traversal. The
round boundary is an intentional approval checkpoint, not a warmup boundary.

## Source And Runtime Context

| Item | Value |
|---|---|
| Branch | `25083-improve-reconnect-bench-socket-net` |
| Commit | `06dc95c783d3beba8692413761e3d0037134e4a4` |
| Build log version | `0.77.0-SNAPSHOT (06dc95c)` |
| JVM | Temurin OpenJDK `25.0.2+10-LTS` |
| Heap | `-Xms24g -Xmx24g -XX:+AlwaysPreTouch` |
| Benchmark mode | JMH `SingleShotTime` |
| Forks / warmup / measurements per invocation | `1 / 0 / 1` |

The working tree also contains the uncommitted benchmark/refined-A1 changes recorded by the preflight Git diff, so the
commit alone is not a complete source fingerprint. Every run archives its exact `SocketFactory.java`, `settings.txt`,
and resolved `settingsUsed.txt` beside the console/JMH/GC evidence.

The standard Java 25 JMH `Unsafe` warning and the existing unknown-module warning appeared in every invocation. No
run logged an OOM, timeout, full GC, benchmark exception, or `BUILD FAILED`.

## Fixed Saved State And Preservation

The benchmark used the user-supplied parent directly:

```text
/Users/thenswan/Work/LimeChain/playground/hiero-consensus-node/benchmark-data/100M
```

`BaseBench` appended `ReconnectBench` and restored both maps from:

```text
benchmark-data/100M/ReconnectBench/learner/saved0
benchmark-data/100M/ReconnectBench/teacher/saved0
```

The directory name says `100M`, while the actual restored map sizes are:

| State | Entries | Snapshot files | Logical bytes |
|---|---:|---:|---:|
| Learner | `74,089,999` | `144` | `22,145,980,676` |
| Teacher | `81,767,068` | `412` | `28,080,961,438` |
| Combined snapshots | — | `556` | `50,226,942,114` |

These sizes and leaf paths match the July 3 large-state lineage:

```text
learner: firstLeafPath=74,089,998, lastLeafPath=148,179,996
teacher: firstLeafPath=81,767,067, lastLeafPath=163,534,134
```

The pre-existing `ReconnectBench/tmp` tree was accepted as disposable. At each setup, `FileSystemManager` replaced the
previous working tree and the run populated it with MerkleDB data. After round 3 it contains `B`'s working files, so it
is explicitly excluded from state-preservation claims.

The expensive inputs remained intact:

- all nine runs logged one successful teacher restore and one successful learner restore from the exact paths above;
- no run logged `Building a state` or `Saved map to`;
- learner and teacher sorted path/size manifests matched preflight after every run in `ABC`, `BCA`, and `CAB`;
- `table_metadata.pbj` retained SHA-256
  `2d94b0ed2708d009f6cd069a9d6d80766715b4c731248a4389638b62a041c525` for the learner and
  `4484ad88e43b8c251a666c9647f66c49b64268caa9740d93d34011b867f99f60` for the teacher.

The preservation check proves names/sizes and critical metadata remained unchanged; it is not a full cryptographic
hash of all 50.2 GB of snapshot content.

## Fixed Benchmark And Network Configuration

All nine runs used:

```text
networkProfile=REALISTIC
networkLatencyMicroseconds=263
networkBandwidthMegabitsPerSecond=200
randomSeed=9823452658
teacherAddProbability=0.10
teacherRemoveProbability=0.00
teacherModifyProbability=0.30
numFiles=1000
numRecords=10000
maxKey=10000000
keySize=32
recordSize=128
numThreads=32
benchmark.saveDataDirectory=true
benchmark.verifyResult=false
benchmark.enableSnapshots=false
socket.gzipCompression=false
```

The state-shape parameters are retained for historical invocation provenance. Because restoration succeeded, they did
not regenerate or fingerprint the already-saved state.

The resolved refined-A1 profile was identical in every run:

| Setting | Resolved value |
|---|---:|
| Configured/modeled one-way latency | `263,000 ns` |
| Configured/modeled bandwidth per direction | `25,000,000 B/s` |
| Release quantum | `26,300 ns` |
| Maximum observed range | `657 bytes` |
| Visibility/latency/bandwidth shaping flags | all `true` |

The configured latency is a sender-observation-to-application-visibility target in each direction. It is not the RTT
of the real loopback TCP connection. Bandwidth applies to payload below the production sync-stream buffering layer,
not to TCP/IP headers or physical line rate.

## Socket-Buffer Condition

The kernel socket-buffer condition was **unset / OS-default-autotuned**:

- production `SocketFactory.java` had no tracked difference from `HEAD` before the campaign;
- it matched every archived run copy and retained SHA-256
  `e2d37b1e16f82b4e7b3d1323974d60dc131437228526adefa6685500029b217b`;
- no active `setSendBufferSize()` or `setReceiveBufferSize()` call exists on this path;
- the remaining setter examples in `SocketFactory.java` are comments only;
- `socket.bufferSize=8192` is the Java production sync-stream buffer, not a kernel TCP buffer setting.

All runs began with the same effective readbacks:

```text
server receive=131,072
teacher/client send=146,988
teacher/client receive=408,300
learner/accepted send=146,988
learner/accepted receive=408,300 bytes
TCP_NODELAY=true at both endpoints
```

The buffers then autotuned differently during each workload:

| Run | End teacher send / receive | End learner send / receive |
|---|---:|---:|
| `R1 A` | `818,732 / 849,648` | `442,368 / 2,084,056` |
| `R1 B` | `506,292 / 1,013,456` | `417,064 / 821,750` |
| `R1 C` | `449,624 / 981,712` | `425,984 / 977,660` |
| `R2 B` | `1,064,492 / 3,910,428` | `753,664 / 2,523,492` |
| `R2 C` | `1,203,756 / 2,498,656` | `794,624 / 2,923,264` |
| `R2 A` | `769,580 / 2,757,600` | `368,640 / 1,832,776` |
| `R3 C` | `884,268 / 1,071,104` | `466,216 / 2,173,244` |
| `R3 A` | `917,036 / 1,013,456` | `458,752 / 2,205,964` |
| `R3 B` | `1,531,436 / 721,168` | `557,056 / 4,194,240` |

These are OS readbacks and context for natural loopback backpressure, not fixed benchmark inputs.

## Round 1 Results

### Wall-clock score

| Run | Score | Scored duration | Full Gradle duration |
|---|---:|---:|---:|
| `A`, top-to-bottom | `588.010 s/op` | `9m48.010s` | `10m02s` |
| `B`, parallel | `766.182 s/op` | `12m46.182s` | `12m55s` |
| `C`, two-phase | `603.069 s/op` | `10m03.069s` | `10m12s` |
| Total | `1,957.261 s` | `32m37.261s` | about `33m09s` |

Round-1 differences are:

- `C - A = 15.059 s`; `C/A = 1.025610` (`+2.56%`);
- `B - A = 178.172 s`; `B/A = 1.303008` (`+30.30%`);
- `B - C = 163.113 s`; `B/C = 1.270472` (`+27.05%`).

These are single-observation descriptive ratios, not confidence intervals.

### Network payload

| Run | Teacher -> learner | Learner -> teacher | Total directional payload |
|---|---:|---:|---:|
| `A` | `6,175,425,338` | `5,528,588,494` | `11,704,013,832` |
| `B` | `6,283,281,672` | `6,116,928,736` | `12,400,210,408` |
| `C` | `6,240,526,805` | `5,896,072,597` | `12,136,599,402` |

Each directional value is counted once. Written and read copies are equal and must not be added together. Compression
was disabled, and these values still exclude TCP/IP headers and retransmission accounting.

### Reconnect work counters

| Run | Internal clean hashes | Internal hashes | Leaf clean data | Leaf data | Transfers learner / teacher |
|---|---:|---:|---:|---:|---:|
| `A` | `2,914,265` | `17,971,177` | `33,297,565` | `69,784,196` | `87,755,373 / 87,755,373` |
| `B` | `15,030,339` | `46,199,589` | `14,407,887` | `50,894,518` | `97,094,107 / 97,094,107` |
| `C` | `15,194,258` | `41,448,544` | `15,653,279` | `52,139,910` | `93,588,454 / 93,588,454` |

The counters are algorithmic work units, not bytes. Their different shapes are expected across traversal algorithms.

## Round 2 Results

Round 2 deliberately changed the execution order to `BCA` and used a fresh JMH JVM for every letter.

### Wall-clock score

| Execution order | Run | Score | Full Gradle duration | Round-2 rank |
|---:|---|---:|---:|---:|
| 1 | `B`, parallel | `986.455 s/op` | `16m41s` | 3 |
| 2 | `C`, two-phase | `569.628 s/op` | `9m37s` | 2 |
| 3 | `A`, top-to-bottom | `495.196 s/op` | `8m25s` | 1 |
| — | Total | `2,051.279 s` (`34m11.279s`) | about `34m43s` | — |

Round-2 differences are:

- `C - A = 74.432 s`; `C/A = 1.150308` (`+15.03%`);
- `B - A = 491.259 s`; `B/A = 1.992050` (`+99.20%`);
- `B - C = 416.827 s`; `B/C = 1.731753` (`+73.18%`).

The changed order did not change the traversal ranking. It also did not produce simple position drift: `B` ran first
and became slower than in round 1, while `A` ran last and became faster; `C` changed least.

### Network payload

| Run | Teacher -> learner | Learner -> teacher | Total directional payload |
|---|---:|---:|---:|
| `B`, parallel | `6,283,281,672` | `6,116,928,736` | `12,400,210,408` |
| `C`, two-phase | `6,240,548,601` | `5,896,273,693` | `12,136,822,294` |
| `A`, top-to-bottom | `6,175,425,338` | `5,528,588,494` | `11,704,013,832` |

`A` and `B` repeat their round-1 payloads exactly. `C` differs by only `21,796` teacher-to-learner bytes
(`0.000349%`) and `201,096` learner-to-teacher bytes (`0.003411%`), consistent with its small work-count variation.

### Reconnect work counters

| Run | Internal clean hashes | Internal hashes | Leaf clean data | Leaf data | Transfers learner / teacher |
|---|---:|---:|---:|---:|---:|
| `B`, parallel | `15,030,339` | `46,199,589` | `14,407,887` | `50,894,518` | `97,094,107 / 97,094,107` |
| `C`, two-phase | `15,204,810` | `41,472,138` | `15,632,877` | `52,119,508` | `93,591,646 / 93,591,646` |
| `A`, top-to-bottom | `2,914,265` | `17,971,177` | `33,297,565` | `69,784,196` | `87,755,373 / 87,755,373` |

## Round 3 Results

Round 3 used the remaining `CAB` execution order and a fresh JMH JVM for every letter.

### Wall-clock score

| Execution order | Run | Score | Full Gradle duration | Round-3 rank |
|---:|---|---:|---:|---:|
| 1 | `C`, two-phase | `583.779 s/op` | `9m54s` | 2 |
| 2 | `A`, top-to-bottom | `519.783 s/op` | `8m48s` | 1 |
| 3 | `B`, parallel | `614.978 s/op` | `10m21s` | 3 |
| — | Total | `1,718.540 s` (`28m38.540s`) | about `29m03s` | — |

Round-3 differences are:

- `C - A = 63.996 s`; `C/A = 1.123121` (`+12.31%`);
- `B - A = 95.195 s`; `B/A = 1.183144` (`+18.31%`);
- `B - C = 31.199 s`; `B/C = 1.053443` (`+5.34%`).

The third execution order again preserves `A < C < B`. The `B/C` margin is much narrower than in rounds 1 and 2,
which is important evidence that the order is repeatable here but its magnitude is not stable.

### Network payload and reconnect work counters

| Run | Teacher -> learner | Learner -> teacher | Total payload | Internal clean / total | Leaf clean / total | Transfers each side |
|---|---:|---:|---:|---:|---:|---:|
| `C`, two-phase | `6,240,587,731` | `5,896,679,035` | `12,137,266,766` | `15,228,202 / 41,524,220` | `15,587,229 / 52,073,860` | `93,598,080` |
| `A`, top-to-bottom | `6,175,425,338` | `5,528,588,494` | `11,704,013,832` | `2,914,265 / 17,971,177` | `33,297,565 / 69,784,196` | `87,755,373` |
| `B`, parallel | `6,283,281,672` | `6,116,928,736` | `12,400,210,408` | `15,030,339 / 46,199,589` | `14,407,887 / 50,894,518` | `97,094,107` |

`A` and `B` again repeat exactly. `C` retains the same work shape with small nondeterministic count differences; this
does not approach the size of the timing gaps or change the traversal interpretation.

## Final Cumulative Result

| Traversal | Observations | Mean | Median | Min-max | Sample SD | CV |
|---|---|---:|---:|---:|---:|---:|
| `A`, top-to-bottom | `588.010`, `495.196`, `519.783` | **`534.330 s/op`** | `519.783` | `495.196-588.010` | `48.087 s` | `9.00%` |
| `C`, two-phase | `603.069`, `569.628`, `583.779` | **`585.492 s/op`** | `583.779` | `569.628-603.069` | `16.786 s` | `2.87%` |
| `B`, parallel | `766.182`, `986.455`, `614.978` | **`789.205 s/op`** | `766.182` | `614.978-986.455` | `186.806 s` | `23.67%` |

At `n=3`, standard deviations and CVs remain descriptive. Mean ratios are `C/A=1.095751`, `B/A=1.477000`, and
`B/C=1.347935`. The stronger result is paired and ordinal: `C-A`, `B-A`, and `B-C` are positive in every one of the
three counterbalanced rounds. The observed margins vary widely, so the campaign supports the ranking under this fixed
state/profile—not precise percentage speedups.

### `SimulatedNetworkChannel` ordering cross-check

The same saved-state simulator campaign at `263 us`, `200 Mbps`, and an explicit `128 MiB` in-flight cap also
recorded two complete passes:

| Model | Pass | `A`, top | `C`, two-phase | `B`, parallel | Ordering |
|---|---:|---:|---:|---:|---|
| `SimulatedNetworkChannel` | 1 | `480.446` | `580.738` | `765.266` | `A < C < B` |
| `SimulatedNetworkChannel` | 2 | `527.114` | `567.559` | `953.710` | `A < C < B` |
| Refined A1 socket | 1 | `588.010` | `603.069` | `766.182` | `A < C < B` |
| Refined A1 socket | 2 | `495.196` | `569.628` | `986.455` | `A < C < B` |
| Refined A1 socket | 3 | `519.783` | `583.779` | `614.978` | `A < C < B` |

Thus all five complete passes preserve the primary traversal-order result. This is cross-model directional agreement,
not equivalence of absolute times or transport semantics: the simulator used an explicit software in-flight cap,
whereas refined A1 uses loopback TCP and OS-managed socket buffers and fails its timing-fidelity gates.

## Accounting And Lifecycle Validity

All 18 directional records passed these hard invariants:

```text
bytesWritten == bytesRead == observedBytes == scheduledBytes == returnedBytes
rangeCount == rawWriteCount
maxRangeSizeBytes <= 657
pendingRanges == 0
pendingBytes == 0
failedRawReads == 0
failedRawWrites == 0
state == CLOSED
```

Every run also produced exactly one JMH result row, one `BUILD SUCCESSFUL`, one reconnect-stat snapshot, two network
byte summaries, and one refined-A1 visibility summary. These checks establish complete byte accounting and drained,
closed controllers. `CLOSED` is controller state at summary time, not direct proof of physical socket close; successful
JMH/Gradle completion supplies the broader completion evidence. None of these checks establishes timing fidelity.

## Refined-A1 Timing-Quality Gates

The frozen release-lateness limit is one quarter of configured one-way latency:

```text
L / 4 = 65,750 ns = 65.75 us
```

The raw-write union gate requires no more than `1%` of observed bytes to belong to ranges whose write duration exceeds
either the quarter-latency target or the range's target serialization duration.

| Run | Direction | Release-lateness p99 | `<=65.75 us` | Raw-write union bytes / observed | `<=1%` |
|---|---|---:|---:|---:|---:|
| `R1 A` | teacher -> learner | `33.554431 ms` | fail | `194,732,724 / 6,175,425,338 = 3.153%` | fail |
| `R1 A` | learner -> teacher | `134.217727 ms` | fail | `119,142,945 / 5,528,588,494 = 2.155%` | fail |
| `R1 B` | teacher -> learner | `16.777215 ms` | fail | `234,422,968 / 6,283,281,672 = 3.731%` | fail |
| `R1 B` | learner -> teacher | `16.777215 ms` | fail | `188,989,488 / 6,116,928,736 = 3.090%` | fail |
| `R1 C` | teacher -> learner | `33.554431 ms` | fail | `184,790,514 / 6,240,526,805 = 2.961%` | fail |
| `R1 C` | learner -> teacher | `67.108863 ms` | fail | `124,418,376 / 5,896,072,597 = 2.110%` | fail |
| `R2 B` | teacher -> learner | `67.108863 ms` | fail | `148,015,874 / 6,283,281,672 = 2.356%` | fail |
| `R2 B` | learner -> teacher | `33.554431 ms` | fail | `118,036,809 / 6,116,928,736 = 1.930%` | fail |
| `R2 C` | teacher -> learner | `67.108863 ms` | fail | `142,138,799 / 6,240,548,601 = 2.278%` | fail |
| `R2 C` | learner -> teacher | `268.435455 ms` | fail | `94,858,065 / 5,896,273,693 = 1.609%` | fail |
| `R2 A` | teacher -> learner | `33.554431 ms` | fail | `124,954,090 / 6,175,425,338 = 2.023%` | fail |
| `R2 A` | learner -> teacher | `231.249904 ms` | fail | `91,991,223 / 5,528,588,494 = 1.664%` | fail |
| `R3 C` | teacher -> learner | `33.554431 ms` | fail | `140,402,591 / 6,240,587,731 = 2.250%` | fail |
| `R3 C` | learner -> teacher | `67.108863 ms` | fail | `95,591,871 / 5,896,679,035 = 1.621%` | fail |
| `R3 A` | teacher -> learner | `67.108863 ms` | fail | `139,570,237 / 6,175,425,338 = 2.260%` | fail |
| `R3 A` | learner -> teacher | `67.108863 ms` | fail | `101,875,815 / 5,528,588,494 = 1.843%` | fail |
| `R3 B` | teacher -> learner | `16.777215 ms` | fail | `185,109,356 / 6,283,281,672 = 2.946%` | fail |
| `R3 B` | learner -> teacher | `16.777215 ms` | fail | `149,360,589 / 6,116,928,736 = 2.442%` | fail |

Result: release gate `0/18` directions pass; raw-write union gate `0/18` directions pass. Most p99 values are
conservative base-two histogram bucket upper bounds. A timing-gate failure is recorded as implementation-fidelity
evidence and is not a reason to selectively rerun a slow or fast traversal.

## GC Context

| Run | Recorded pause count | Recorded pause total | Maximum pause | Full GC |
|---|---:|---:|---:|---:|
| `R1 A` | `119` | `14.893232 s` | `597.972 ms` | `0` |
| `R1 B` | `115` | `19.829830 s` | `1,022.497 ms` | `0` |
| `R1 C` | `93` | `13.721270 s` | `531.288 ms` | `0` |
| `R2 B` | `138` | `23.813369 s` | `1,063.291 ms` | `0` |
| `R2 C` | `166` | `28.834916 s` | `1,235.557 ms` | `0` |
| `R2 A` | `103` | `12.823667 s` | `645.998 ms` | `0` |
| `R3 C` | `96` | `15.028479 s` | `740.172 ms` | `0` |
| `R3 A` | `60` | `5.666270 s` | `250.124 ms` | `0` |
| `R3 B` | `92` | `13.020184 s` | `891.758 ms` | `0` |

These totals cover each complete JMH fork, not only its scored interval. Recorded stop-the-world pause time is too small
to explain the hundreds-of-seconds separation of `B` from `A/C` in round 2, but this does not exclude allocation or
concurrent-GC CPU as contributors.

## Historical Same-State Comparison

The July 3 loopback-socket note recorded three grouped measurements per traversal on this state lineage:

| Traversal | July 3 mean | Current round-1 score | Current / historical mean |
|---|---:|---:|---:|
| top-to-bottom | `1,204.611 s/op` | `588.010 s/op` | `0.488x` |
| parallel | `1,647.915 s/op` | `766.182 s/op` | `0.465x` |
| two-phase | `1,437.108 s/op` | `603.069 s/op` | `0.420x` |

Both datasets currently order top-to-bottom first, two-phase second, and parallel third. Their relative separations are
not identical:

| Ratio | July 3 | Current round 1 |
|---|---:|---:|
| parallel / top-to-bottom | `1.368x` | `1.303x` |
| two-phase / top-to-bottom | `1.193x` | `1.026x` |
| parallel / two-phase | `1.147x` | `1.270x` |

The payload shapes strongly support workload continuity: current `A` and `B` directional byte totals exactly match
the July 3 values, while current `C` differs from the July 3 three-run mean by only about `-0.006%` teacher-to-learner
and `-0.064%` learner-to-teacher. This does not make the timing protocols equivalent.

Do not interpret the approximately twofold absolute-time difference as a pure refined-A1 improvement. Important
differences include:

- July 3 used the earlier socket/read-pacing implementation and an earlier source revision;
- July 3 used `270 us`, while this campaign uses `263 us`;
- July 3 grouped three measurements of one traversal in one JVM; this campaign uses independent JVM invocations in a
  counterbalanced sequence;
- refined A1 has separately measured plumbing overhead and failed timing-accuracy gates;
- verification is disabled in both datasets, and the historical note has no full cryptographic state fingerprint.

The appropriate current statement is that all three refined-A1 replicates preserve the broad historical ordering on
the same recorded state lineage; the protocols still do not support a pure absolute-time comparison.

## Artifacts And Restoration

Raw evidence is retained locally under:

```text
platform-sdk/swirlds-benchmarks/build/reconnectbench-refined-a1-75m-traversal-2026-07-21/
```

For each run, the round directory contains a nonempty console log, JMH result, GC log, exact `settings.txt`, resolved
`settingsUsed.txt`, exact `SocketFactory.java`, and metrics/name snapshots. The metrics snapshots are cumulative because
`csvAppend=true`; console, JMH, and GC evidence is per invocation.

After the complete campaign:

- benchmark `settings.txt` was restored byte-for-byte to its run-start contents;
- production `SocketFactory.java` remained byte-for-byte unchanged;
- all nine learner/teacher path-size manifest pairs (`18` files) and all seven recorded post-run metadata-hash
  snapshots match preflight;
- all 72 per-run evidence files expected across the three round directories are nonempty;
- no branch, worktree, commit, `gradlew clean`, or destructive Git operation was used;
- no benchmark process remains active.

## Final Conclusion

The campaign meets its narrow descriptive traversal-order objective:

1. All nine planned invocations completed on the supplied large state, with every traversal occupying every execution
   position exactly once.
2. Every refined-A1 round ranks top-to-bottom first, two-phase second, and parallel third.
3. Means (`534.330 < 585.492 < 789.205 s/op`) and medians (`519.783 < 583.779 < 766.182 s/op`) retain that order.
4. Both recorded same-state simulator passes have the same order, so all five complete cross-model passes agree.
5. The round-3 `B/C` gap is only `5.34%`, and parallel has high variation (`23.67%` CV). The ranking repeats, but its
   margins are not stable enough to present as precise speedups.
6. Counterbalancing rules out a simple fixed first/second/third-position explanation, but one observation per cell
   cannot eliminate traversal-by-position, host-load, cache, thermal, or time effects.
7. Hard accounting/lifecycle checks pass `18/18`, and the supplied state remains intact under the recorded preservation
   checks. Result verification was disabled, and preservation did not hash all `50.2 GB` of content.
8. Both timing gates pass `0/18`. This does not erase the observed within-implementation ordering, but it prevents a
   claim of accurate `263 us` realization, simulator equivalence, remote-TCP fidelity, or general real-network ranking.

The defensible conclusion is: **refined A1 corroborates the simulator's `top-to-bottom < two-phase < parallel` trend
on this saved state and host.** It is corroborating cross-model trend evidence, not proof of transport equivalence or a
stable universal performance margin.

## Related Notes

- [2026-07-03 Loopback Socket REALISTIC Local Runs](2026-07-03-loopback-socket-realistic-local-runs.md)
- [2026-07-21 Refined-A1 10M Overhead And Buffer Matrix](2026-07-21-refined-a1-10m-overhead-and-buffer-matrix.md)
- [Refined-A1 Socket-Network Design And Real-Network Gap Analysis](../../design-and-implementation/2026-07-21-refined-a1-socket-network-design-and-real-network-gap-analysis.md)
