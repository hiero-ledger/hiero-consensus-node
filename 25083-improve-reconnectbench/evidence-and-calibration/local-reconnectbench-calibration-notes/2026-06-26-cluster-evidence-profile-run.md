# Cluster-Evidence Local ReconnectBench Calibration Runs

## Purpose

This is the local run log for `ReconnectBench` executions generated from the May 29 cluster evidence profile. The state
profile is intended to stay fixed; traversal order and network configuration may vary across runs.

Use this note to compare local JMH scores, verification outcome, reconnect work counters, and network/backpressure
signals. Keep each new run compact enough that the run matrix remains readable.

Primary cluster anchor:

- Accepted cluster run: `pullTopToBottom`, documented in
  [`../extracted-cluster-evidence/2026-05-29-cluster-calibration/top-to-bottom.md`](../extracted-cluster-evidence/2026-05-29-cluster-calibration/top-to-bottom.md).
- Batch summary:
  [`../extracted-cluster-evidence/2026-05-29-cluster-calibration/batch-summary.md`](../extracted-cluster-evidence/2026-05-29-cluster-calibration/batch-summary.md).

The `pullParallelSync` cluster artifact remains excluded because it failed network-disease preflight. The
`pullTwoPhasePessimistic` artifact remains useful state-shape context, but is not a full network calibration anchor
because first-window passive TCP/window evidence is missing.

## Fixed State Profile

State-generation and BaseBench parameters kept fixed across the local calibration runs:

| Parameter | Value |
|---|---:|
| `randomSeed` | `9823452658` |
| `teacherAddProbability` | `0.09` |
| `teacherRemoveProbability` | `0.0` |
| `teacherModifyProbability` | `0.40` |
| `numFiles` | `7409` |
| `numRecords` | `10000` |
| `maxKey` | `10000000` |
| `keySize` | `32` |
| `recordSize` | `128` |
| `numThreads` | `32` |

Common benchmark settings for these runs:

```text
benchmark.benchmarkData=data
benchmark.saveDataDirectory=true
benchmark.enableSnapshots=false
benchmark.printHistogram=false
benchmark.csvOutputFolder=data
benchmark.csvWriteFrequency=1000
benchmark.csvAppend=true
```

`benchmark.verifyResult` and `virtualMap.reconnectMode` may vary by run and are recorded below.

State shape compared to the accepted cluster `pullTopToBottom` artifact:

| Item | Cluster accepted top-to-bottom | Local generated state | Delta |
|---|---:|---:|---:|
| Learner/start size | `74090175` | `74089999` | `-176` |
| Teacher/target size | `81734059` | `81767068` | `+33009` |
| State-size gap | `7643884` | `7677069` | `+33185` |

The local generated state is close to the accepted cluster state-size anchor. The local gap is `0.434%` larger than the
accepted cluster gap.

## Run Matrix

| Run | Date/time | Traversal | Network | Score | Verification | Traffic T->L / L->T | L->T cap wait | Outcome |
|---|---|---|---|---:|---:|---:|---:|---|
| R1 | `2026-06-26 16:22` | `pullTopToBottom` | `REALISTIC`, `263 us`, `200 Mbps`, `128 MiB` cap | `480.446 s/op` | `81767068` keys in `514.404 s` | `5.751 GiB / 5.149 GiB` | `86.736 s` | PASS; learner-to-teacher hit cap |
| R2 | `2026-06-26 17:22` | `pullParallelSync` | `REALISTIC`, `263 us`, `200 Mbps`, `128 MiB` cap | `765.266 s/op` | disabled | `5.852 GiB / 5.697 GiB` | `78.724 s` | Completed; learner-to-teacher hit cap |
| R3 | `2026-06-26 17:41` | `pullTwoPhasePessimistic` | `REALISTIC`, `263 us`, `200 Mbps`, `128 MiB` cap | `580.738 s/op` | disabled | `5.812 GiB / 5.492 GiB` | `197.929 s` | Completed; strongest L->T cap wait |
| R4 | `2026-06-26 17:58` | `pullTopToBottom` | `REALISTIC`, `263 us`, `200 Mbps`, `128 MiB` cap | `527.114 s/op` | disabled | `5.751 GiB / 5.149 GiB` | `83.784 s` | Completed; top-to-bottom repeat |
| R5 | `2026-06-26 18:11` | `pullParallelSync` | `REALISTIC`, `263 us`, `200 Mbps`, `128 MiB` cap | `953.710 s/op` | disabled | `5.852 GiB / 5.697 GiB` | `73.124 s` | Completed; parallel repeat much slower |
| R6 | `2026-06-26 18:33` | `pullTwoPhasePessimistic` | `REALISTIC`, `263 us`, `200 Mbps`, `128 MiB` cap | `567.559 s/op` | disabled | `5.812 GiB / 5.491 GiB` | `207.961 s` | Completed; two-phase repeat close to R3 |
| R7 | `2026-06-29 13:32` | `pullTopToBottom` | `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap | `556.910 s/op` | disabled | `5.751 GiB / 5.149 GiB` | `98.691 s` | Completed; smaller cap hit both directions |
| R8 | `2026-06-29 13:59` | `pullParallelSync` | `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap | `1003.538 s/op` | disabled | `5.852 GiB / 5.697 GiB` | `122.923 s` | Completed; parallel remains slow; smaller cap adds L->T wait |
| R9 | `2026-06-29 14:53` | `pullTwoPhasePessimistic` | `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap | `552.700 s/op` | disabled | `5.812 GiB / 5.492 GiB` | `198.112 s` | Completed; two-phase not penalized by smaller cap |
| R10 | `2026-06-29 18:25` | `pullTopToBottom` | `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap | `520.805 s/op` | disabled | `5.751 GiB / 5.149 GiB` | `105.496 s` | Completed; top-to-bottom repeat faster than R7 |
| R11 | `2026-06-29 18:45` | `pullParallelSync` | `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap | `857.815 s/op` | disabled | `5.852 GiB / 5.697 GiB` | `133.140 s` | Completed; parallel repeat faster than R8 |
| R12 | `2026-06-29 19:04` | `pullTwoPhasePessimistic` | `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap | `567.044 s/op` | disabled | `5.812 GiB / 5.493 GiB` | `208.510 s` | Completed; two-phase repeat close to R6/R9 |

## Work And Network Counters

| Run | Transfers teacher / learner | Internal data / hashes | Leaf data / hashes | Leaf clean data | Leaf dirty data | T->L max in-flight | L->T max in-flight |
|---|---:|---:|---:|---:|---:|---:|---:|
| R1 | `86249140 / 87612941` | `16592134 / 16475579` | `69784196 / 66100976` | `33297565` | `36486631` | `15403645` | `134217720` |
| R2 | `93657786 / 96866162` | `42442675 / 41919152` | `50894518 / 48196378` | `14407887` | `36486631` | `13292592` | `134217720` |
| R3 | `89787431 / 93299520` | `37674930 / 37640969` | `52096680 / 49228068` | `15610049` | `36486631` | `29025587` | `134217720` |
| R4 | `86176733 / 87605868` | `16445340 / 16445939` | `69784196 / 65664135` | `33297565` | `36486631` | `26979431` | `134217720` |
| R5 | `93761135 / 96806425` | `42128737 / 41960326` | `50894518 / 48426390` | `14407887` | `36486631` | `55040000` | `134217720` |
| R6 | `90159834 / 93343554` | `38235339 / 37995278` | `52119508 / 49063885` | `15632877` | `36486631` | `89876282` | `134217720` |
| R7 | `86223133 / 87614540` | `16478662 / 16464871` | `69784196 / 65038995` | `33297565` | `36486631` | `33554432` | `33554430` |
| R8 | `94007345 / 96910577` | `42699009 / 42067648` | `50894518 / 48356267` | `14407887` | `36486631` | `23400148` | `33554430` |
| R9 | `90010252 / 93324437` | `38204759 / 38047009` | `52081736 / 49668991` | `15595105` | `36486631` | `29906725` | `33554430` |
| R10 | `86259691 / 87630908` | `16386507 / 16507050` | `69784196 / 66343683` | `33297565` | `36486631` | `33554432` | `33554431` |
| R11 | `93918036 / 96897107` | `42493559 / 42166357` | `50894518 / 48267027` | `14407887` | `36486631` | `13171029` | `33554430` |
| R12 | `90295426 / 93362394` | `38672706 / 38627425` | `51959612 / 48903960` | `15472981` | `36486631` | `13519813` | `33554430` |

Accepted cluster `pullTopToBottom` leaf-work anchors: `leafCleanData=36037864`, `leafDirtyData=35396063`,
`leafData=71433927`.

| Run | `leafCleanData` delta | `leafDirtyData` delta | `leafData` delta |
|---|---:|---:|---:|
| R1 | `-2740299` (`-7.60%`) | `+1090568` (`+3.08%`) | `-1649731` (`-2.31%`) |
| R2 | `-21629977` (`-60.02%`) | `+1090568` (`+3.08%`) | `-20539409` (`-28.75%`) |
| R3 | `-20427815` (`-56.68%`) | `+1090568` (`+3.08%`) | `-19337247` (`-27.07%`) |
| R4 | `-2740299` (`-7.60%`) | `+1090568` (`+3.08%`) | `-1649731` (`-2.31%`) |
| R5 | `-21629977` (`-60.02%`) | `+1090568` (`+3.08%`) | `-20539409` (`-28.75%`) |
| R6 | `-20404987` (`-56.62%`) | `+1090568` (`+3.08%`) | `-19314419` (`-27.04%`) |
| R7 | `-2740299` (`-7.60%`) | `+1090568` (`+3.08%`) | `-1649731` (`-2.31%`) |
| R8 | `-21629977` (`-60.02%`) | `+1090568` (`+3.08%`) | `-20539409` (`-28.75%`) |
| R9 | `-20442759` (`-56.73%`) | `+1090568` (`+3.08%`) | `-19352191` (`-27.09%`) |
| R10 | `-2740299` (`-7.60%`) | `+1090568` (`+3.08%`) | `-1649731` (`-2.31%`) |
| R11 | `-21629977` (`-60.02%`) | `+1090568` (`+3.08%`) | `-20539409` (`-28.75%`) |
| R12 | `-20564883` (`-57.07%`) | `+1090568` (`+3.08%`) | `-19474315` (`-27.26%`) |

## Run Notes

### R1: `pullTopToBottom`, 200 Mbps, 128 MiB cap

- JMH result: `480.446 s/op`; total JMH run time: `01:43:08`.
- Verification was enabled and passed: `81767068` keys in `514.404 s`.
- Teacher-to-learner traffic did not hit the in-flight cap: max in-flight `15403645`, capacity waits `0`.
- Learner-to-teacher traffic reached the `128 MiB` cap: max in-flight `134217720`, capacity waits `45425`, capacity
  wait time `86.736 s`.
- The local run reproduced the state-size gap very closely and produced a similar dirty leaf component. Clean leaf work
  was lower than the accepted cluster `pullTopToBottom` artifact, so state size alone is not a complete work-shape match.
- Metrics/GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient. Timed reconnect had `138` GC pauses totaling
  `20.655 s` (`~4.3%` of score), so keep JVM args fixed for comparable local calibration unless intentionally testing
  heap effects.
- Preserved data footprint after this run was about `63 GiB` by `du` under `platform-sdk/swirlds-benchmarks/data/ReconnectBench`.

### R2: `pullParallelSync`, 200 Mbps, 128 MiB cap

- JMH result: `765.266 s/op`; total JMH run time: `00:12:52`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- R2 was `284.820 s` (`59.28%`) slower than R1 with the same generated state and network profile.
- Teacher-to-learner traffic did not hit the in-flight cap: max in-flight `13292592`, capacity waits `0`.
- Learner-to-teacher traffic reached the `128 MiB` cap: max in-flight `134217720`, capacity waits `37351`, capacity
  wait time `78.724 s`.
- Work shape changed substantially versus R1: internal data rose from `16592134` to `42442675`, while clean leaf data
  dropped from `33297565` to `14407887`. Dirty leaf data stayed at `36486631`.
- Metrics/GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient but tighter than R1. The metrics CSV observed
  free heap as low as `1.312 GiB`; timed reconnect had `130` GC pauses totaling `22.435 s` (`~2.9%` of score).
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.
- R2 `results-reconnect.txt` and `BenchmarkMetrics.csv` confirmed the same R2 parameter set and final
  `vmap_size_state=81767068` when inspected after that run.

### R3: `pullTwoPhasePessimistic`, 200 Mbps, 128 MiB cap

- JMH result: `580.738 s/op`; total JMH run time: `00:09:47`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- R3 was `100.292 s` (`20.87%`) slower than R1 and `184.528 s` (`24.11%`) faster than R2.
- Teacher-to-learner traffic did not hit the in-flight cap: max in-flight `29025587`, capacity waits `0`.
- Learner-to-teacher traffic reached the `128 MiB` cap and had the strongest backpressure of the three runs:
  max in-flight `134217720`, capacity waits `165143`, capacity wait time `197.929 s`.
- Work shape was closer to R2 than R1: high internal data (`37674930`) and low clean leaf data (`15610049`). Dirty
  leaf data stayed at `36486631`, matching R1 and R2.
- Metrics/GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient. The metrics CSV observed free heap as low as
  `3.743 GiB`; timed reconnect had `161` GC pauses totaling `25.389 s` (`~4.4%` of score).
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.
- R3 `results-reconnect.txt` and `BenchmarkMetrics.csv` confirmed the same R3 parameter set and final
  `vmap_size_state=81767068`.

### R4: `pullTopToBottom` repeat, 200 Mbps, 128 MiB cap

- JMH result: `527.114 s/op`; total JMH run time: `00:08:52`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- R4 was `46.668 s` (`9.71%`) slower than R1, `238.152 s` (`31.12%`) faster than R2, and `53.624 s`
  (`9.23%`) faster than R3.
- Teacher-to-learner traffic did not hit the in-flight cap: max in-flight `26979431`, capacity waits `0`.
- Learner-to-teacher traffic reached the `128 MiB` cap: max in-flight `134217720`, capacity waits `42294`,
  capacity wait time `83.784 s`.
- Work shape reproduced R1/top-to-bottom: same `leafData` and `leafCleanData`, with only small changes in transfers
  and internal counters.
- Metrics/GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient but tighter than R3. The metrics CSV
  observed free heap as low as `1.661 GiB`; timed reconnect had `178` GC pauses totaling `29.053 s`
  (`~5.5%` of score).
- Preserved data footprint after this run was about `73 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, and a
  remaining `26 GiB` `tmp` directory.
- R4 `results-reconnect.txt` and `BenchmarkMetrics.csv` confirmed the same R4 parameter set and final
  `vmap_size_state=81767068`.

### R5: `pullParallelSync` repeat, 200 Mbps, 128 MiB cap

- JMH result: `953.710 s/op`; total JMH run time: `00:16:05`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- R5 was `188.444 s` (`24.62%`) slower than R2, even though payload bytes were identical and reconnect work was nearly
  identical: `leafData` and `leafCleanData` matched R2 exactly, transfers changed by only about `0.1%`, and
  `internalData` was slightly lower (`-0.74%`).
- Teacher-to-learner traffic did not hit the in-flight cap: max in-flight `55040000`, capacity waits `0`.
- Learner-to-teacher traffic reached the `128 MiB` cap: max in-flight `134217720`, capacity waits `41943`,
  capacity wait time `73.124 s`. This was `5.600 s` less cap-wait time than R2, so the slower score is not explained by
  stronger simulator backpressure.
- Wait counters moved in the opposite direction from cap wait: versus R2, teacher-to-learner empty-read wait rose by
  `140.477 s`, learner-to-teacher empty-read wait rose by `117.735 s`, and learner-to-teacher arrival wait rose by
  `41.517 s`.
- Metrics quick check: the metrics CSV covered `2026-06-26 15:11:45 UTC` to `15:27:37 UTC`; final
  `vmap_size_state=81767068`. Free heap fell as low as `1.254 GiB`, max process CPU load was `13.679`, max open file
  descriptors were `4160`, and MerkleDB logical size peaked at `37529 MB` before ending at `32203 MB`.
- GC log was not available for R5. The only file present after the run was `reconnectbench-gc.log.0`, timestamped from
  R4, so do not compare R5 GC pause totals against earlier runs.
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.
- Post-run host checks were suggestive but not conclusive: `df` showed `128 GiB` free with the data volume `86%` full,
  `memory_pressure` reported current free memory at `77%`, and `uptime` still showed high recent load averages
  (`4.47`, `20.61`, `27.72`). `pmset -g therm` could not read thermal/performance status in this environment.

Interpretation: treat R5 as likely host-condition affected unless a cooled/restarted repeat reproduces it. The state
shape and reconnect work matched R2 too closely for this to be a useful traversal-order signal by itself.

### R6: `pullTwoPhasePessimistic` repeat, 200 Mbps, 128 MiB cap

- JMH result: `567.559 s/op`; total JMH run time: `00:09:34`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- R6 was `13.179 s` (`2.27%`) faster than R3, the prior two-phase run. Work and bytes were very close to R3:
  `leafData` rose by only `22828` (`0.044%`), teacher-to-learner bytes changed by `-0.0003%`, and
  learner-to-teacher bytes changed by `-0.0035%`.
- Teacher-to-learner traffic did not hit the in-flight cap: max in-flight `89876282`, capacity waits `0`.
- Learner-to-teacher traffic reached the `128 MiB` cap: max in-flight `134217720`, capacity waits `176258`,
  capacity wait time `207.961 s`. This was `10.032 s` more cap-wait time than R3 despite the slightly faster score.
- Metrics quick check: the metrics CSV covered `2026-06-26 15:33:12 UTC` to `15:42:38 UTC`; final
  `vmap_size_state=81767068`. Free heap fell as low as `1.584 GiB`, max process CPU load was `13.243`, max open file
  descriptors were `3575`, and MerkleDB logical size peaked at `33010 MB` before ending at `30888 MB`.
- GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient but tight. The GC log had `160` pauses totaling
  `26.070 s` (`~4.6%` of score), with max pause `696.629 ms`.
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.
- Post-run host checks remained noisy but did not explain R6 as an outlier: `df` showed `131 GiB` free with the data
  volume `86%` full, `memory_pressure` reported current free memory at `69%`, and `uptime` showed load averages
  (`5.78`, `16.61`, `23.34`). `pmset -g therm` still could not read thermal/performance status in this environment.

Interpretation: R6 does not reproduce the R5 slowdown. It tracks R3 closely enough that R5 should remain flagged as a
parallel-repeat outlier or host-condition run, while two-phase remains materially slower than the best top-to-bottom
repeat (`R4`) under this local profile.

### R7: `pullTopToBottom`, 200 Mbps, 32 MiB cap

- JMH result: `556.910 s/op`; total JMH run time: `00:09:26`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- Network profile changed from the earlier top-to-bottom runs: latency increased slightly from `263 us` to `270 us`,
  bandwidth stayed at `200 Mbps`, and the in-flight cap dropped from `128 MiB` to `32 MiB`.
- R7 was `29.796 s` (`5.65%`) slower than R4 and `76.464 s` (`15.92%`) slower than R1.
- Work shape stayed essentially identical to prior top-to-bottom runs: `leafData` and `leafCleanData` matched R1/R4
  exactly, while transfers changed by only `0.054%` teacher-side and `0.010%` learner-side versus R4.
- The smaller cap changed the network behavior. Teacher-to-learner now hit the cap for the first time in top-to-bottom:
  max in-flight `33554432`, `15292` capacity waits, `1.770 s` capacity wait time. Learner-to-teacher also hit the cap:
  max in-flight `33554430`, `51078` capacity waits, `98.691 s` capacity wait time.
- Compared with R4, learner-to-teacher cap wait increased by `14.907 s`; total capacity wait across both directions was
  `100.462 s`. Since work counters were stable, the slower score is mostly a network-profile effect, not a state/work
  change.
- Metrics quick check: the metrics CSV covered `2026-06-29 10:32:55 UTC` to `10:42:11 UTC`; final
  `vmap_size_state=81767068`. Free heap fell as low as `2.756 GiB`, max process CPU load was `14.103`, max open file
  descriptors were `3623`, and MerkleDB logical size peaked at `36056 MB`.
- GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient. The GC log had `180` pauses totaling `29.667 s`
  (`~5.3%` of score), with max pause `881.630 ms`.
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.

Interpretation: R7 is a clean top-to-bottom comparison point for the 32 MiB cap. It preserves the prior top-to-bottom
work shape but makes the simulated channel capacity visible in both directions, producing a modest slowdown versus the
best 128 MiB repeat.

### R8: `pullParallelSync`, 200 Mbps, 32 MiB cap

- JMH result: `1003.538 s/op`; total JMH run time: `00:16:51`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- Network profile matched R7: `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap.
- R8 was `238.272 s` (`31.14%`) slower than R2 and `49.828 s` (`5.22%`) slower than R5, the prior slow
  `pullParallelSync` repeat.
- Work shape stayed in the prior parallel-sync envelope: `leafData=50894518`, `leafCleanData=14407887`, and
  `leafDirtyData=36486631` matched R2/R5 exactly. Versus R5, transfers changed by `0.263%` teacher-side and `0.108%`
  learner-side; `internalData` rose by `1.354%`.
- Teacher-to-learner did not hit the 32 MiB cap: max in-flight `23400148`, capacity waits `0`. Learner-to-teacher hit
  the cap: max in-flight `33554430`, `50906` capacity waits, `122.923 s` capacity wait time.
- Compared with R5, learner-to-teacher cap wait increased by `49.799 s`, almost the same as the `49.828 s` score
  increase. Other major waits did not move in the same direction: teacher-to-learner empty-read wait rose by
  `23.819 s`, learner-to-teacher empty-read wait fell by `10.432 s`, and learner-to-teacher arrival wait fell by
  `17.281 s`.
- Compared with R2, R8 is also much slower, but this includes both the smaller cap and the same host/noise pattern that
  made R5 slow: teacher-to-learner empty-read wait was `568.167 s` in R8 versus `403.871 s` in R2.
- Metrics quick check: the metrics CSV covered `2026-06-29 10:59:18 UTC` to `11:16:01 UTC`; final
  `vmap_size_state=81767068`. Free heap fell as low as `1.304 GiB`, max process CPU load was `14.322`, max open file
  descriptors were `3524`, and MerkleDB logical size peaked at `33813 MB`.
- GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient but tight. The GC log had `173` pauses totaling
  `28.154 s` (`~2.8%` of score), with max pause `991.859 ms`.
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.

Interpretation: R8 supports two separate signals. First, `pullParallelSync` remains much slower than top-to-bottom on
this saved state. Second, the 32 MiB cap has a measurable parallel-sync cost: relative to the already-slow R5 baseline,
the extra score time is almost exactly explained by the extra learner-to-teacher capacity wait.

### R9: `pullTwoPhasePessimistic`, 200 Mbps, 32 MiB cap

- JMH result: `552.700 s/op`; total JMH run time: `00:09:19`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- Network profile matched R7/R8: `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap.
- R9 was `28.038 s` (`4.83%`) faster than R3 and `14.859 s` (`2.62%`) faster than R6, the prior
  `pullTwoPhasePessimistic` repeat. It was also `4.210 s` (`0.76%`) faster than R7/top-to-bottom in the same 32 MiB
  profile.
- Work shape stayed in the prior two-phase envelope: versus R6, `leafData` changed by `-0.072%`, `leafCleanData` by
  `-0.242%`, `internalData` by `-0.080%`, teacher transfers by `-0.166%`, and learner transfers by `-0.020%`.
- Teacher-to-learner did not hit the 32 MiB cap: max in-flight `29906725`, capacity waits `0`. Learner-to-teacher hit
  the cap: max in-flight `33554430`, `151971` capacity waits, `198.112 s` capacity wait time.
- The smaller cap did not materially increase two-phase cap wait. Learner-to-teacher cap wait was only `0.184 s` above
  R3 and `9.848 s` below R6, while the score was faster than both.
- Compared with R6, teacher-to-learner empty-read wait rose by `25.426 s` and arrival wait rose by `1.906 s`, while
  learner-to-teacher empty-read and arrival waits rose by `73.061 s` and `69.044 s`. These wait-counter changes did not
  translate into a slower score, so they should be treated as scheduling/cadence diagnostics rather than direct elapsed
  time adders.
- Metrics quick check: the current-run metrics CSV segment covered `2026-06-29 11:53:53 UTC` to `12:03:04 UTC`; final
  `vmap_size_state=81767068`. Free heap fell as low as `1.543 GiB`, max process CPU load was `13.322`, max open file
  descriptors were `4179`, and MerkleDB logical size peaked at `27583 MB`.
- GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient but tight. The GC log had `140` pauses totaling
  `28.149 s` (`~5.1%` of score), with max pause `850.349 ms`.
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.

Interpretation: R9 does not show the 32 MiB cap penalty seen in R7 and R8. Under this saved state, two-phase remains in
the same score band as its 128 MiB runs and is effectively tied with top-to-bottom for the first pass of the 32 MiB
profile, while parallel-sync is still the clear slow case.

### R10: `pullTopToBottom` repeat, 200 Mbps, 32 MiB cap

- JMH result: `520.805 s/op`; total JMH run time: `00:08:49`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- Network profile matched R7/R8/R9: `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap.
- R10 was `36.105 s` (`6.48%`) faster than R7, `6.309 s` (`1.20%`) faster than R4, and `40.359 s`
  (`8.40%`) slower than R1. It was also `31.895 s` (`5.77%`) faster than R9/two-phase under the same 32 MiB profile.
- Work shape reproduced prior top-to-bottom runs: `leafData` and `leafCleanData` matched R1/R4/R7 exactly. Versus R7,
  `internalData` fell by `0.559%`, teacher transfers rose by `0.042%`, and learner transfers rose by `0.019%`.
- Both directions hit the 32 MiB cap: teacher-to-learner max in-flight `33554432`, `27409` capacity waits, `4.178 s`
  capacity wait time; learner-to-teacher max in-flight `33554431`, `55876` capacity waits, `105.496 s` capacity wait
  time.
- Compared with R7, total capacity wait increased by `9.212 s`, and the other wait counters also rose: teacher
  empty-read wait by `14.885 s`, teacher arrival wait by `5.757 s`, learner empty-read wait by `5.815 s`, and learner
  arrival wait by `5.292 s`. Since the score improved by `36.105 s`, the R7/R10 gap is better explained as host/cadence
  variance than as a direct network-cap effect.
- Metrics quick check: the metrics CSV covered `2026-06-29 15:25:57 UTC` to `15:34:36 UTC`; final
  `vmap_size_state=81767068`. Free heap fell as low as `1.248 GiB`, max process CPU load was `13.609`, max open file
  descriptors were `3504`, and MerkleDB logical size peaked at `33842 MB`.
- GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient but tight. The GC log had `165` pauses totaling
  `28.322 s` (`~5.4%` of score), with max pause `911.228 ms`.
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.

Interpretation: R10 supersedes R7 as the stronger top-to-bottom repeat for the 32 MiB profile. Top-to-bottom is now
faster than two-phase in this pass and remains far faster than parallel-sync; the smaller cap is visible in counters, but
top-to-bottom timing variance is larger than the cap-wait delta alone.

### R11: `pullParallelSync` repeat, 200 Mbps, 32 MiB cap

- JMH result: `857.815 s/op`; total JMH run time: `00:14:25`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- Network profile matched R7/R8/R9/R10: `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap.
- R11 was `145.723 s` (`14.52%`) faster than R8, the prior 32 MiB `pullParallelSync` run. It was also `95.895 s`
  (`10.06%`) faster than R5, but `92.549 s` (`12.09%`) slower than R2 and `337.010 s` (`64.71%`) slower than
  R10/top-to-bottom under the same 32 MiB profile.
- Work shape stayed in the prior parallel-sync envelope: `leafData=50894518`, `leafCleanData=14407887`, and
  `leafDirtyData=36486631` matched R2/R5/R8 exactly. Versus R8, teacher transfers changed by `-0.095%`, learner
  transfers by `-0.014%`, and `internalData` by `-0.481%`.
- Teacher-to-learner did not hit the 32 MiB cap: max in-flight `13171029`, capacity waits `0`. Learner-to-teacher hit
  the cap: max in-flight `33554430`, `54082` capacity waits, `133.140 s` capacity wait time.
- R11 was much faster than R8 despite `10.217 s` more learner-to-teacher cap wait. The faster score aligns better with
  lower wait/cadence counters: versus R8, teacher-to-learner empty-read wait fell by `120.433 s`, learner-to-teacher
  empty-read wait fell by `123.165 s`, and learner-to-teacher arrival wait fell by `44.334 s`.
- Metrics quick check: the metrics CSV covered `2026-06-29 15:45:10 UTC` to `15:59:26 UTC`; final
  `vmap_size_state=81767068`. Free heap fell as low as `2.627 GiB`, max process CPU load was `13.485`, max open file
  descriptors were `3707`, and MerkleDB logical size peaked at `31982 MB`.
- GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient. The GC log had `160` pauses totaling `26.596 s`
  (`~3.1%` of score), with max pause `849.092 ms`.
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.

Interpretation: R11 weakens the idea that later macOS runs simply get slower as the machine gets tired. It is a later
run than R8 but substantially faster, with the same state, same network profile, and essentially the same reconnect work.
The useful signal remains that `pullParallelSync` is still far slower than top-to-bottom on this saved state; the exact
parallel-sync score is noisy and sensitive to run cadence/host scheduling.

### R12: `pullTwoPhasePessimistic` repeat, 200 Mbps, 32 MiB cap

- JMH result: `567.044 s/op`; total JMH run time: `00:09:34`.
- Verification was disabled for this run (`benchmark.verifyResult=false`), so there is no `PASS verified` line.
- Network profile matched R7/R8/R9/R10/R11: `REALISTIC`, `270 us`, `200 Mbps`, `32 MiB` cap.
- R12 was `14.344 s` (`2.60%`) slower than R9, the prior 32 MiB `pullTwoPhasePessimistic` run. It was effectively tied
  with the older 128 MiB repeat R6, finishing `0.515 s` (`0.09%`) faster, and was `13.694 s` (`2.36%`) faster than R3.
  It remained `46.239 s` (`8.88%`) slower than R10/top-to-bottom under the same 32 MiB profile.
- Work shape stayed in the prior two-phase envelope. Versus R9, `leafData` and `leafCleanData` both fell by `122124`
  entries (`-0.234%` and `-0.783%` respectively), while `internalData` rose by `1.225%`, teacher transfers by
  `0.317%`, and learner transfers by `0.041%`.
- Teacher-to-learner did not hit the 32 MiB cap: max in-flight `13519813`, capacity waits `0`. Learner-to-teacher hit
  the cap: max in-flight `33554430`, `150936` capacity waits, `208.510 s` capacity wait time.
- Compared with R9, learner-to-teacher cap wait increased by `10.397 s`, roughly in line with most of the `14.344 s`
  score increase. Other wait counters moved in the opposite direction: teacher empty-read wait fell by `32.756 s`,
  learner empty-read wait fell by `42.878 s`, and learner arrival wait fell by `30.662 s`.
- Metrics quick check: the metrics CSV covered `2026-06-29 16:04:31 UTC` to `16:13:56 UTC`; final
  `vmap_size_state=81767068`. Free heap fell as low as `2.773 GiB`, max process CPU load was `13.264`, max open file
  descriptors were `2781`, and MerkleDB logical size peaked at `31671 MB`.
- GC quick check: no OOM or Full GC; `24 GiB` heap was sufficient. The GC log had `177` pauses totaling `27.900 s`
  (`~4.9%` of score), with max pause `904.629 ms`.
- Preserved data footprint after this run was about `47 GiB` by `du`: learner `21 GiB`, teacher `26 GiB`, no temporary
  MerkleDB directory present.

Interpretation: R12 reinforces that two-phase is stable around the same local band under both 128 MiB and 32 MiB caps.
The 32 MiB cap is always visible on learner-to-teacher for this traversal, but it still does not create the kind of
large penalty seen for `pullParallelSync`. In the second A/B/C pass, top-to-bottom remains the best local traversal.

## Artifact Paths

These paths were inspected for the latest run. They are overwritten or appended by subsequent local runs unless copied
aside:

- `platform-sdk/swirlds-benchmarks/build/results/jmh/results-reconnect.txt`
- `platform-sdk/swirlds-benchmarks/settings.txt`
- `platform-sdk/swirlds-benchmarks/data/BenchmarkMetrics.csv`
- `platform-sdk/swirlds-benchmarks/data/reconnectbench-gc.log` or rotated `.0` variant, when present
- `platform-sdk/swirlds-benchmarks/data/ReconnectBench`
