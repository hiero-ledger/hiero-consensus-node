# 2026-07-22 Raw-Loopback 75M Traversal-Order Comparison

Status: **rounds 1 (`ABC`) and 2 (`BCA`) complete and audited; paused before round 3 (`CAB`) with `n=2` per
traversal**.

## Two-Round Checkpoint Summary

Both completed raw-loopback triples preserved the requested traversal ordering:

```text
pullTopToBottom < pullTwoPhasePessimistic < pullParallelSync
```

| Symbol | Traversal | `ABC` | `BCA` | `n=2` mean | Rank in both rounds |
|---|---|---:|---:|---:|---:|
| `A` | `pullTopToBottom` | `472.026` | `451.841` | **`461.934 s/op`** | 1 |
| `C` | `pullTwoPhasePessimistic` | `476.244` | `474.134` | **`475.189 s/op`** | 2 |
| `B` | `pullParallelSync` | `591.085` | `989.305` | **`790.195 s/op`** | 3 |

This is now two observations per traversal. The order repeats, but its magnitude is not stable: B changed by
`398.220 s` (`+67.371%`) between rounds. `CAB` remains required to place every traversal in its third execution
position and reach the planned `n=3` before making the campaign-level repeatability conclusion.

## Question

Does the raw loopback-socket transport preserve the traversal ordering observed in refined A1 and
`SimulatedNetworkChannel`?

```text
pullTopToBottom < pullTwoPhasePessimistic < pullParallelSync
```

This is a separate `ABC / BCA / CAB` campaign with one fresh JVM invocation per letter and `n=3` per traversal after
all three rounds.

## What Raw `LOOPBACK` Means

The invocation supplies only:

```text
networkProfile=LOOPBACK
```

It does not supply latency or bandwidth overrides. The Gradle/JMH task still prints its generic inert defaults
(`270 us` and `200 Mbps`) because those parameters exist for every profile. Acceptance depends on the effective
configuration, which must be:

```text
visibilitySchedulingActive=false
latencyShapingActive=false
bandwidthShapingActive=false
modeledLatencyNanos=0
modeledBandwidthBytesPerSecond=9223372036854775807
releaseQuantumNanos=0
maxObservedRangeBytes=0
```

No refined-A1 observer, receiver gate, range splitting, visibility scheduling, or timing wait is installed. The
benchmark still uses one real TCP connection on `127.0.0.1`, the production sync-stream factories, and OS-managed
socket buffers.

## Fixed Protocol

Symbols:

| Symbol | Traversal |
|---|---|
| `A` | `pullTopToBottom` |
| `B` | `pullParallelSync` |
| `C` | `pullTwoPhasePessimistic` |

Schedule:

| Round | Order | Status |
|---:|---|---|
| 1 | `ABC` | **complete and documented; `n=1` per traversal** |
| 2 | `BCA` | **complete and documented; `n=2` per traversal** |
| 3 | `CAB` | not started |

Each letter is one Gradle/JMH invocation with one fork, no warmup, and one single-shot measurement. The campaign
pauses and updates this note after each triple.

## Saved State And Preservation

The benchmark uses the user-supplied parent directly:

```text
/Users/thenswan/Work/LimeChain/playground/hiero-consensus-node/benchmark-data/100M
```

`BaseBench` restores:

```text
benchmark-data/100M/ReconnectBench/learner/saved0
benchmark-data/100M/ReconnectBench/teacher/saved0
```

| State | Entries | Snapshot files | Logical bytes |
|---|---:|---:|---:|
| Learner | `74,089,999` | `144` | `22,145,980,676` |
| Teacher | `81,767,068` | `412` | `28,080,961,438` |
| Combined | — | `556` | `50,226,942,114` |

Preflight path/size-manifest SHA-256 values are:

```text
learner fa2b709ba0f0c3c8d90bdc1792aca8a01f265e2eef3698baa9d2ae5b66d1bf1f
teacher 96fd7459ef5929552a8fc5461f49e13b098c16ecbbbd8abaa5e5f99ff697b849
```

Critical `table_metadata.pbj` SHA-256 values are:

```text
learner 2d94b0ed2708d009f6cd069a9d6d80766715b4c731248a4389638b62a041c525
teacher 4484ad88e43b8c251a666c9647f66c49b64268caa9740d93d34011b867f99f60
```

The existing `ReconnectBench/tmp` directory is disposable and may be replaced by benchmark setup. The learner and
teacher snapshots must not be deleted or modified. Sorted path/size manifests and the two metadata hashes are checked
after every run; this is not a full cryptographic hash of all `50.2 GB` of content.

## Fixed Workload And Socket Condition

```text
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

Kernel socket buffers remain unset and OS managed. Production `SocketFactory.java` matches SHA-256
`e2d37b1e16f82b4e7b3d1323974d60dc131437228526adefa6685500029b217b`; its send/receive buffer setter examples are
comments only. `socket.bufferSize=8192` remains the Java sync-stream buffer, not a kernel buffer setting.

## Acceptance Rules

Every accepted invocation must have:

- exactly one teacher and learner restore from the approved paths;
- expected map sizes and traversal mode;
- no state generation or save message;
- raw `LOOPBACK` diagnostics with all shaping flags false, zero modeled latency, unlimited modeled bandwidth, and no
  refined-A1 visibility summary;
- equal positive written/read payload bytes in both directions;
- one reconnect-stat snapshot, one finite positive JMH score, and `BUILD SUCCESSFUL`;
- no benchmark exception, OOM, timeout, or full GC;
- unchanged state manifests and metadata hashes after the run.

Refined-A1 timing gates do not apply because the controller and scheduler do not exist in this profile.

## Artifacts

Raw evidence is retained under:

```text
platform-sdk/swirlds-benchmarks/build/reconnectbench-loopback-75m-traversal-2026-07-22/
```

The preflight directory contains source/configuration fingerprints and saved-state manifests. Every accepted run in
rounds 1 and 2 contains eight nonempty prefixed artifacts: console log, JMH result, GC log, exact settings, resolved
settings, production `SocketFactory.java`, and the two metrics snapshots. The shared metrics files use
`csvAppend=true`; consequently, later per-run metrics snapshots include earlier accepted rows and must not be
interpreted as independent per-run CSVs.

## Results

### Round 1: `ABC`

Each letter ran in a fresh JMH JVM. The scored duration was `1,539.355 s` (`25m39.355s`) in total; the three complete
Gradle invocations took about `26m12s`.

| Execution order | Run | Score | Full Gradle duration | Rank |
|---:|---|---:|---:|---:|
| 1 | `A`, top-to-bottom | `472.026 s/op` | `8m07s` | 1 |
| 2 | `B`, parallel | `591.085 s/op` | `10m00s` | 3 |
| 3 | `C`, two-phase | `476.244 s/op` | `8m05s` | 2 |

Round-1 differences are:

- `C - A = 4.218 s`; `C/A = 1.008936` (`+0.894%`);
- `B - A = 119.059 s`; `B/A = 1.252230` (`+25.223%`);
- `B - C = 114.841 s`; `B/C = 1.241139` (`+24.114%`).

These are single-observation descriptive ratios, not confidence intervals. The first triple agrees directionally with
the refined-A1 campaign and the recorded `SimulatedNetworkChannel` ordering, but the narrow raw-loopback `A/C` margin
needs the two remaining counterbalanced triples.

### Network Payload

| Run | Teacher -> learner | Learner -> teacher | Total directional payload |
|---|---:|---:|---:|
| `A` | `6,175,425,338` | `5,528,588,494` | `11,704,013,832` |
| `B` | `6,283,281,672` | `6,116,928,736` | `12,400,210,408` |
| `C` | `6,240,568,505` | `5,896,477,939` | `12,137,046,444` |

Written and read values matched exactly and were positive in both directions for every run. Each value in the table is
counted once; it excludes TCP/IP headers and retransmission accounting.

### Reconnect Work Counters

| Run | Internal clean hashes | Internal hashes | Leaf clean data | Leaf data | Transfers learner / teacher |
|---|---:|---:|---:|---:|---:|
| `A` | `2,914,265` | `17,971,177` | `33,297,565` | `69,784,196` | `87,755,373 / 87,755,373` |
| `B` | `15,030,339` | `46,199,589` | `14,407,887` | `50,894,518` | `97,094,107 / 97,094,107` |
| `C` | `15,216,517` | `41,498,208` | `15,610,049` | `52,096,680` | `93,594,888 / 93,594,888` |

The `A` and `B` work counters and payloads match refined-A1 round 1 exactly. `C` has the same work shape with small
count and payload variation. This supports that the raw/refined comparison uses the same algorithmic workload rather
than proving that timing differences are caused only by network modeling.

### GC And Runtime Checks

| Run | Completed GC pauses | Total pause time | Maximum pause | Full GC |
|---|---:|---:|---:|---:|
| `A` | `185` | `31.263 s` | `1,003.482 ms` | `0` |
| `B` | `126` | `23.456 s` | `1,096.694 ms` | `0` |
| `C` | `127` | `23.689 s` | `1,240.416 ms` | `0` |

No run logged an OOM, timeout, benchmark exception, `BUILD FAILED`, state build, or state save. Every run logged one
teacher restore, one learner restore, one reconnect-stat snapshot, two network summaries, and `BUILD SUCCESSFUL`.
The standard Java 25 JMH `Unsafe` warning and existing unknown-module warning appeared and are not run failures.

### Raw-Loopback And Preservation Audit

All three logs resolve the same effective profile:

```text
profile=LOOPBACK
visibilitySchedulingActive=false
latencyShapingActive=false
bandwidthShapingActive=false
modeledLatencyNanos=0
modeledBandwidthBytesPerSecond=9223372036854775807
releaseQuantumNanos=0
maxObservedRangeBytes=0
```

There are zero refined-A1 visibility summaries. The printed `270 us / 200 Mbps` values are the inert generic JMH
defaults and were not supplied as campaign overrides.

After A, B, and C, both saved-state path/size manifests and both metadata hashes matched preflight byte-for-byte. All
24 prefixed run artifacts and all nine post-run preservation files are nonempty. Production and archived
`SocketFactory.java` copies retain SHA-256 `e2d37b1e16f82b4e7b3d1323974d60dc131437228526adefa6685500029b217b`.
After the checkpoint, `settings.txt` was restored byte-for-byte to its baseline SHA-256
`a0fd38de007d850d3a8efed5b25200af8e5842dedd6984d3158df3b563c196d1`; no JMH process remains active.

## Round 2 Results: `BCA`

Only completed, accepted measurements are included here. B was retained across a user-requested campaign pause before
fresh C and A invocations. Round 2 therefore preserves the accepted `BCA` execution order but is not one contiguous
time block.

All three accepted round-2 invocations used this host process-launch compatibility setting:

```text
JAVA_TOOL_OPTIONS=-Djdk.lang.Process.launchMechanism=FORK
```

It changes Java child-process creation, not reconnect code or network behavior. It is part of round-2 provenance; the
measured reconnect operation does not launch child processes.

### Wall-Clock Score

| Execution order | Run | Score | JMH total | Full Gradle duration | Rank |
|---:|---|---:|---:|---:|---:|
| 1 | `B`, parallel | `989.305 s/op` | `16m36s` | `16m43s` | 3 |
| 2 | `C`, two-phase | `474.134 s/op` | `8m01s` | `8m11s` | 2 |
| 3 | `A`, top-to-bottom | `451.841 s/op` | `7m36s` | `7m42s` | 1 |
| — | Total | `1,915.280 s` (`31m55.280s`) | — | `32m36s` | — |

Round-2 differences are:

- `C - A = 22.293 s`; `C/A = 1.049338` (`+4.934%`);
- `B - A = 537.464 s`; `B/A = 2.189498` (`+118.950%`);
- `B - C = 515.171 s`; `B/C = 2.086551` (`+108.655%`).

### Network Payload And Reconnect Work

| Run | Teacher -> learner | Learner -> teacher | Internal clean / total | Leaf clean / total | Transfers each side |
|---|---:|---:|---:|---:|---:|
| `B` | `6,283,281,672` | `6,116,928,736` | `15,030,339 / 46,199,589` | `14,407,887 / 50,894,518` | `97,094,107` |
| `C` | `6,240,568,505` | `5,896,477,939` | `15,216,517 / 41,498,208` | `15,610,049 / 52,096,680` | `93,594,888` |
| `A` | `6,175,425,338` | `5,528,588,494` | `2,914,265 / 17,971,177` | `33,297,565 / 69,784,196` | `87,755,373` |

Written/read values match exactly in both directions. Each traversal repeats its accepted round-1 payload and work
counters exactly, so the large B timing change is not caused by a changed reconnect work shape.

### GC And Accepted-Run Audit

| Run | Completed GC pauses | Total pause time | Maximum pause | Full GC |
|---|---:|---:|---:|---:|
| `B` | `162` | `30.872 s` | `1,201.034 ms` | `0` |
| `C` | `128` | `22.696 s` | `765.625 ms` | `0` |
| `A` | `157` | `27.657 s` | `976.675 ms` | `0` |

Every accepted run has all eight expected nonempty artifacts, one teacher and learner restore, one reconnect-stat
snapshot, two equal-positive network summaries, one finite score, and `BUILD SUCCESSFUL`. All resolve raw `LOOPBACK`
with zero modeled latency, unlimited modeled bandwidth, all shaping flags false, and no visibility summary.

Learner/teacher path-size manifests and both metadata hashes match preflight after B, C, and A. All archived and live
`SocketFactory.java` copies match SHA-256 `e2d37b1e16f82b4e7b3d1323974d60dc131437228526adefa6685500029b217b`.
`settings.txt` is restored to baseline SHA-256
`a0fd38de007d850d3a8efed5b25200af8e5842dedd6984d3158df3b563c196d1`.

## Cumulative Result After Two Rounds

| Traversal | `ABC` | `BCA` | `n=2` mean | Round-to-round change |
|---|---:|---:|---:|---:|
| `A`, top-to-bottom | `472.026` | `451.841` | **`461.934 s/op`** | `-20.185 s` (`-4.276%`) |
| `C`, two-phase | `476.244` | `474.134` | **`475.189 s/op`** | `-2.110 s` (`-0.443%`) |
| `B`, parallel | `591.085` | `989.305` | **`790.195 s/op`** | `+398.220 s` (`+67.371%`) |

Both rounds preserve `A < C < B`. Across the two-round means, C is `13.256 s` (`2.870%`) slower than A, while B is
`315.006 s` (`66.291%`) slower than C. Descriptive sample variability is low for C (`SD 1.492 s`, `CV 0.314%`),
modest for A (`SD 14.273 s`, `CV 3.090%`), and high for B (`SD 281.584 s`, `CV 35.635%`).

The directional trend now repeats across two accepted execution orders, but the effect magnitude—especially B—is not
stable. `CAB` is still required to put C first, A second, and B third and raise every traversal to `n=3`.

## Checkpoint Decision

The campaign is paused before `CAB` as planned. Do not start round 3 without explicit approval.
