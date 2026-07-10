# 2026-07-03 Loopback Socket Realistic Local Runs

## Purpose

This note records local `ReconnectBench` runs using `LOOPBACK_SOCKET` transport with the `REALISTIC` network profile.
The intent is to compare loopback TCP behavior against the earlier simulated-network local calibration runs while
keeping the large restored state shape stable.

Append future `LOOPBACK_SOCKET` local runs here when they use the same restored large state lineage.

## Run Context

Run sources:

| Run | Source log | Build | Traversal | Total JMH time |
|---|---|---|---|---:|
| LSR1 | Codex attachment `4b2fb97d-dcdc-462d-9456-5d3ad6042428/pasted-text.txt` | `0.77.0-SNAPSHOT (3932aab)` | `pullTopToBottom` | `01:00:19` |
| LSR2 | Codex attachment `5efdc3ca-e5cd-4df5-a13c-04f74be53b69/pasted-text.txt` | `0.77.0-SNAPSHOT (3932aab)` | `pullParallelSync` | `01:22:30` |
| LSR3 | Codex attachment `2cedfffd-5eae-47e0-972b-1f9ced509c58/pasted-text.txt` | `0.77.0-SNAPSHOT (3932aab)` | `pullTwoPhasePessimistic` | `01:11:59` |
| LSR4 | Codex attachment `7b25dc89-58e2-4c74-bb1f-486109deb856/pasted-text.txt` | `0.77.0-SNAPSHOT (3af8481)` | `pullTopToBottom` with local `SocketFactory` buffer experiment | `01:00:42` |

Common run configuration:

| Item | Value |
|---|---|
| Benchmark data root | `data` |
| Restored teacher state | `data/ReconnectBench/teacher/saved0` |
| Restored learner state | `data/ReconnectBench/learner/saved0` |
| Transport | `LOOPBACK_SOCKET` |
| Network profile | `REALISTIC` |
| Verification | disabled, `benchmark.verifyResult=false` |
| Snapshots | disabled, `benchmark.enableSnapshots=false` |
| CSV output | `data`, `csvAppend=true`, `csvWriteFrequency=1000` |
| JMH iterations | `3` single-shot iterations per recorded run |

State restored at the beginning of the run:

| State | First leaf path | Last leaf path | Size |
|---|---:|---:|---:|
| Learner/start | `74089998` | `148179996` | `74089999` |
| Teacher/desired | `81767067` | `163534134` | `81767068` |

Parameter provenance from the JMH result row:

| Parameter | Value |
|---|---:|
| `randomSeed` | `9823452658` |
| `teacherAddProbability` | `0.1` |
| `teacherRemoveProbability` | `0.0` |
| `teacherModifyProbability` | `0.3` |
| `numFiles` | `1000` |
| `numRecords` | `10000` |
| `maxKey` | `10000000` |
| `keySize` | `32` |
| `recordSize` | `128` |
| `numThreads` | `32` |

Because both maps were restored from `data/ReconnectBench/.../saved0`, the JMH state-generation parameters above are
parameter provenance for the benchmark invocation, not proof that this run regenerated a fresh state.

## Network And Socket Profile

Resolved network profile:

| Setting | Value |
|---|---:|
| `networkLatencyMicroseconds` | `270` |
| `latencyNanos` | `270000` |
| `networkBandwidthMegabitsPerSecond` | `200` |
| `bandwidthBytesPerSecond` | `25000000` |
| `networkInflightBytesLimit` | `16777216` |

Baseline socket transport diagnostics reported for each LSR1-LSR3 iteration:

| Diagnostic | Value |
|---|---:|
| `latencyShapingActive` | `true` |
| `bandwidthShapingActive` | `true` |
| `configuredLatencyNanos` | `270000` |
| `configuredBandwidthBytesPerSecond` | `25000000` |
| `inflightBytesLimitIgnored` | `true` |
| `streamBufferBytes` | `8192` |
| `serverReceiveBufferBytes` | `131072` |
| `clientSendBufferBytes` | `146988` |
| `clientReceiveBufferBytes` | `408300` |
| `acceptedSendBufferBytes` | `146988` |
| `acceptedReceiveBufferBytes` | `408300` |
| `clientTcpNoDelay` | `true` |
| `acceptedTcpNoDelay` | `true` |

LSR4 socket transport diagnostics with the local `SocketFactory` buffer experiment:

| Diagnostic | Value |
|---|---:|
| `latencyShapingActive` | `true` |
| `bandwidthShapingActive` | `true` |
| `configuredLatencyNanos` | `270000` |
| `configuredBandwidthBytesPerSecond` | `25000000` |
| `inflightBytesLimitIgnored` | `true` |
| `streamBufferBytes` | `8192` |
| `serverReceiveBufferBytes` | `1048576` |
| `clientSendBufferBytes` | `1061580` |
| `clientReceiveBufferBytes` | `1061580` |
| `acceptedSendBufferBytes` | `146988` |
| `acceptedReceiveBufferBytes` | `1061580` |
| `clientTcpNoDelay` | `true` |
| `acceptedTcpNoDelay` | `true` |

`networkInflightBytesLimit` is recorded because it is present in the benchmark parameters, but this transport explicitly
ignores it. The socket run should not be compared against simulated-network capacity-wait counters as if both transports
were enforcing the same in-flight cap.

## Run Matrix

| Run | Start time | Traversal | Network | Score | Verification | Traffic T->L / L->T | Outcome |
|---|---|---|---|---:|---|---:|---|
| LSR1.1 | `2026-07-03 16:07:18` | `pullTopToBottom` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1166.340 s/op` | disabled | `5.751 GiB / 5.149 GiB` | Completed |
| LSR1.2 | `2026-07-03 16:26:45` | `pullTopToBottom` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1188.223 s/op` | disabled | `5.751 GiB / 5.149 GiB` | Completed |
| LSR1.3 | `2026-07-03 16:46:34` | `pullTopToBottom` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1259.270 s/op` | disabled | `5.751 GiB / 5.149 GiB` | Completed |
| LSR1 aggregate | `2026-07-03 16:07:18` | `pullTopToBottom` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1204.611 +/- 886.351 s/op` | disabled | `5.751 GiB / 5.149 GiB` | `N = 3` |
| LSR2.1 | `2026-07-03 17:29:05` | `pullParallelSync` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1502.013 s/op` | disabled | `5.852 GiB / 5.697 GiB` | Completed |
| LSR2.2 | `2026-07-03 17:54:09` | `pullParallelSync` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1794.200 s/op` | disabled | `5.852 GiB / 5.697 GiB` | Completed |
| LSR2.3 | `2026-07-03 18:24:03` | `pullParallelSync` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1647.532 s/op` | disabled | `5.852 GiB / 5.697 GiB` | Completed |
| LSR2 aggregate | `2026-07-03 17:29:05` | `pullParallelSync` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1647.915 +/- 2665.301 s/op` | disabled | `5.852 GiB / 5.697 GiB` | `N = 3` |
| LSR3.1 | `2026-07-03 18:56:18` | `pullTwoPhasePessimistic` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1441.226 s/op` | disabled | `5.812 GiB / 5.496 GiB` | Completed |
| LSR3.2 | `2026-07-03 19:20:22` | `pullTwoPhasePessimistic` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1386.982 s/op` | disabled | `5.812 GiB / 5.496 GiB` | Completed |
| LSR3.3 | `2026-07-03 19:43:29` | `pullTwoPhasePessimistic` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1483.117 s/op` | disabled | `5.812 GiB / 5.492 GiB` | Completed |
| LSR3 aggregate | `2026-07-03 18:56:18` | `pullTwoPhasePessimistic` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps` | `1437.108 +/- 879.342 s/op` | disabled | `5.812 GiB / 5.492-5.496 GiB` | `N = 3` |
| LSR4.1 | `2026-07-03 20:13:11` | `pullTopToBottom` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps`, local `SocketFactory` buffer experiment | `1200.069 s/op` | disabled | `5.751 GiB / 5.149 GiB` | Completed |
| LSR4.2 | `2026-07-03 20:33:12` | `pullTopToBottom` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps`, local `SocketFactory` buffer experiment | `1166.913 s/op` | disabled | `5.751 GiB / 5.149 GiB` | Completed |
| LSR4.3 | `2026-07-03 20:52:39` | `pullTopToBottom` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps`, local `SocketFactory` buffer experiment | `1270.624 s/op` | disabled | `5.751 GiB / 5.149 GiB` | Completed |
| LSR4 aggregate | `2026-07-03 20:13:11` | `pullTopToBottom` | `LOOPBACK_SOCKET`, `REALISTIC`, `270 us`, `200 Mbps`, local `SocketFactory` buffer experiment | `1212.536 +/- 966.322 s/op` | disabled | `5.751 GiB / 5.149 GiB` | `N = 3` |

The aggregate rows are the JMH results. The `+/-` value is JMH's 99.9% confidence-interval half-width, not the observed
min/max spread:

- LSR1: `N = 3`, mean `1204.611 s/op`, 99.9% CI half-width `886.351 s/op`.
- LSR2: `N = 3`, mean `1647.915 s/op`, 99.9% CI half-width `2665.301 s/op`.
- LSR3: `N = 3`, mean `1437.108 s/op`, 99.9% CI half-width `879.342 s/op`.
- LSR4: `N = 3`, mean `1212.536 s/op`, 99.9% CI half-width `966.322 s/op`.

## Work Counters

| Run | Transfers teacher / learner | Internal data / hashes | Internal clean data / hashes | Leaf data / hashes | Leaf clean data / hashes |
|---|---:|---:|---:|---:|---:|
| LSR1.1 | `85753434 / 87554155` | `15676155 / 15653087` | `2806950 / 2769351` | `69784196 / 62864912` | `33297565 / 31316320` |
| LSR1.2 | `85663490 / 87565883` | `15663913 / 15619522` | `2805056 / 2774068` | `69784196 / 62276442` | `33297565 / 31224746` |
| LSR1.3 | `85860892 / 87558273` | `15879790 / 15841396` | `2817094 / 2789028` | `69784196 / 62007970` | `33297565 / 31097534` |
| LSR2.1 | `92971272 / 96854933` | `41323016 / 40965013` | `14495531 / 14377350` | `50894518 / 45178429` | `14407887 / 13896137` |
| LSR2.2 | `92415845 / 96864199` | `40533353 / 40205263` | `14404560 / 14253462` | `50894518 / 44829056` | `14407887 / 13855356` |
| LSR2.3 | `92538229 / 96870746` | `40695469 / 40428335` | `14422276 / 14278813` | `50894518 / 45212369` | `14407887 / 13913765` |
| LSR3.1 | `88791465 / 93402902` | `37473735 / 37416996` | `14716619 / 14351021` | `51595976 / 45370925` | `15109345 / 14298895` |
| LSR3.2 | `88417708 / 93397475` | `37077947 / 37063139` | `14674066 / 14272445` | `51555460 / 45438255` | `15068829 / 14294955` |
| LSR3.3 | `88398479 / 93356601` | `36663846 / 36448844` | `14442042 / 14012468` | `51981930 / 45656642` | `15495299 / 14638874` |
| LSR4.1 | `85869447 / 87535755` | `15833017 / 15701433` | `2844356 / 2787968` | `69784196 / 63234169` | `33297565 / 31558926` |
| LSR4.2 | `85757217 / 87568138` | `15739845 / 15708551` | `2842912 / 2784410` | `69784196 / 62601442` | `33297565 / 31425125` |
| LSR4.3 | `85697686 / 87547674` | `15656285 / 15644836` | `2840583 / 2778435` | `69784196 / 62344366` | `33297565 / 31358335` |

## Network Counters

| Run | Direction | Bytes written | Bytes read | Max in-flight | Capacity wait count / time | Empty-read wait count / time | Arrival wait count / time |
|---|---|---:|---:|---:|---:|---:|---:|
| LSR1.1 | Teacher to learner | `6175425338` | `6175425338` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR1.1 | Learner to teacher | `5528588494` | `5528588494` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR1.2 | Teacher to learner | `6175425338` | `6175425338` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR1.2 | Learner to teacher | `5528588494` | `5528588494` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR1.3 | Teacher to learner | `6175425338` | `6175425338` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR1.3 | Learner to teacher | `5528588494` | `5528588494` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR2.1 | Teacher to learner | `6283281672` | `6283281672` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR2.1 | Learner to teacher | `6116928736` | `6116928736` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR2.2 | Teacher to learner | `6283281672` | `6283281672` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR2.2 | Learner to teacher | `6116928736` | `6116928736` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR2.3 | Teacher to learner | `6283281672` | `6283281672` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR2.3 | Learner to teacher | `6116928736` | `6116928736` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR3.1 | Teacher to learner | `6240977821` | `6240977821` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR3.1 | Learner to teacher | `5900830861` | `5900830861` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR3.2 | Teacher to learner | `6241012109` | `6241012109` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR3.2 | Learner to teacher | `5901187315` | `5901187315` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR3.3 | Teacher to learner | `6240656442` | `6240656442` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR3.3 | Learner to teacher | `5897449840` | `5897449840` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR4.1 | Teacher to learner | `6175425338` | `6175425338` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR4.1 | Learner to teacher | `5528588494` | `5528588494` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR4.2 | Teacher to learner | `6175425338` | `6175425338` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR4.2 | Learner to teacher | `5528588494` | `5528588494` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR4.3 | Teacher to learner | `6175425338` | `6175425338` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |
| LSR4.3 | Learner to teacher | `5528588494` | `5528588494` | `0` | `0 / 0 ns` | `0 / 0 ns` | `0 / 0 ns` |

The zero wait and in-flight counters are expected for this transport. `LOOPBACK_SOCKET` reuses the stats shape for
bytes, but it does not use the simulated-network queue and capacity-wait machinery.

## Initial Observations

- All recorded runs used the expected large restored state: learner size `74089999`, teacher size `81767068`.
- LSR1 traffic matched the earlier top-to-bottom simulated local runs: `5.751 GiB` teacher-to-learner and `5.149 GiB`
  learner-to-teacher.
- LSR2 traffic matched the earlier parallel simulated local runs: `5.852 GiB` teacher-to-learner and `5.697 GiB`
  learner-to-teacher.
- LSR3 traffic matched the earlier two-phase simulated local runs: about `5.812 GiB` teacher-to-learner and
  `5.492-5.496 GiB` learner-to-teacher.
- LSR4 traffic matched LSR1 exactly: `5.751 GiB` teacher-to-learner and `5.149 GiB` learner-to-teacher.
- LSR4 confirmed that the local `SocketFactory` buffer experiment reached the benchmark socket path: server receive
  buffer rose from `131072` to `1048576`, client send/receive rose from `146988 / 408300` to `1061580 / 1061580`, and
  accepted receive rose from `408300` to `1061580`. Accepted send stayed at `146988`.
- The loopback socket aggregate score, `1204.611 s/op`, was `2.313x` R10 (`520.805 s/op`), `2.163x` R7
  (`556.910 s/op`), and `2.507x` R1 (`480.446 s/op`) from
  [`2026-06-26-cluster-evidence-profile-run.md`](2026-06-26-cluster-evidence-profile-run.md).
- The SocketFactory-modified top-to-bottom run, `1212.536 s/op`, was `1.0066x` LSR1, only `7.925 s` slower on the mean
  (`+0.658%`). With `N = 3` and wide JMH confidence intervals, this is best read as no clear wall-clock improvement
  from the local buffer-size change in this run.
- The parallel loopback socket aggregate score, `1647.915 s/op`, was `1.921x` R11 (`857.815 s/op`), `1.642x` R8
  (`1003.538 s/op`), `2.153x` R2 (`765.266 s/op`), and `1.728x` R5 (`953.710 s/op`) from the same earlier note.
- The two-phase loopback socket aggregate score, `1437.108 s/op`, was `2.600x` R9 (`552.700 s/op`), `2.534x` R12
  (`567.044 s/op`), `2.475x` R3 (`580.738 s/op`), and `2.532x` R6 (`567.559 s/op`) from the same earlier note.
- LSR2 was `1.368x` LSR1, but this compares different traversal modes, so it is a traversal-plus-transport comparison
  rather than a socket-only effect.
- LSR3 was `1.193x` LSR1 and `0.872x` LSR2, but these are also traversal-plus-transport comparisons rather than
  socket-only effects.
- This should not yet be read as a pure `SocketFactory` buffer-size effect. The comparison also includes the current
  loopback socket transport implementation, active artificial latency shaping, active bandwidth shaping, actual loopback
  TCP streams, and the `8192` byte stream-buffer chunking used by the benchmark socket wrapper.
- Verification was disabled for these runs, so the scores are useful for transport timing comparison but not standalone
  correctness validation.
