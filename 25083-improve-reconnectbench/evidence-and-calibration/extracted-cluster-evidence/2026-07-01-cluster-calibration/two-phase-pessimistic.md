# Two-Phase-Pessimistic Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/01-07-2026/dallas11_pullTwoPhasePessimistic/report`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | derived | No fatal network disease found. No post-startup `ACTIVE -> CHECKING` transitions and no `Shadowgraph: Missing non-expired other parent` evidence were found in searched run-local logs. | `derived:network_disease_preflight;inputs=log:podlog_solo-mdlt-n11/network-node*_logs/swirlds*.log;patterns=ACTIVE->CHECKING,Shadowgraph: Missing non-expired other parent` |
| Files searched | present | Available `swirlds*.log` files under `podlog_solo-mdlt-n11/network-node*_logs/` were searched. Plain `swirlds.log` is missing for `network-node6_logs`. | `derived:file_inventory;scope=podlog_solo-mdlt-n11/network-node*_logs` |
| Active confirmation context | present | Learner post-reconnect `CHECKING -> ACTIVE` occurred at `2026-07-01 04:18:28.825`; startup/non-fatal `CHECKING -> ACTIVE` evidence is not fatal disease. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2668-2669` |
| Extraction disposition | present | Normal extraction is valid, with teacher-window gaps caused by the missing peer node plain log. | [Unresolved Evidence Register](#unresolved-evidence-register) |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTwoPhasePessimistic`; `version_run.txt` and all seven copied `settingsUsed.txt` files agree. | `config:version_run.txt:key=inputs.AddSettings;line=8`; `derived:scan_all_nodes;inputs=config:podlog_solo-mdlt-n11/network-node*_logs/config/settingsUsed.txt:line=725;expected=pullTwoPhasePessimistic` |
| Namespace | present | `Dallas11` | `config:version_run.txt:key=namespace;line=1` |
| Commit hash | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=inputs.hederaversion;line=2`; `config:version_run.txt:key=hederahash;line=11` |
| Solo/chart version | present | `latest_tested_solo-charts0.59_balanced` | `config:version_run.txt:key=inputs.soloversion;line=3` |
| Run number / job URL | present | run `308`; workflow URL `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/28468673432`. | `config:version_run.txt:key=JOB_URL;line=10`; `config:version_run.txt:key=run_number;line=12` |
| NLG controls | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `97,500,000` accounts, `6h`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`; `config:version_run.txt:key=inputs.NLGDparams;line=4`; `config:version_run.txt:key=inputs.NLG_Accounts;line=5`; `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Network size | present | Seven network-node pods, `network-node1-0` through `network-node7-0`, captured in `pod_state.txt`. | `log:pod_state.txt:17-23` |
| Learner node and pod | present | Learner is node ID `0`, pod/log directory `network-node1-0` / `network-node1_logs`. | `log:pod_state.txt:17`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2669` |
| Workflow controls | missing | No workflow-control source was found for warmtime, downtime, loop count, or `profileReconnectLoopK8s`. | files checked: `version_run.txt`, `client.log`, `pod_state.txt`, pod logs, run root inventory; patterns checked: `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s`; reason: no matches |
| Baseline/restored state upload | missing | No baseline, restore, upload, or state-upload evidence was found. | files checked: run root inventory, `version_run.txt`, `client.log`, pod logs; patterns checked: `baseline`, `restore`, `restored-state`, `restored state`, `upload`, `state upload`; reason: no matches |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` entered receiver reconnect as the learner before the first receiver start. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162` |
| Episode receiver coverage | present | The complete episode has 28 learner receiver starts and 28 learner receiver finishes from `2026-07-01 00:31:26.780` through `04:18:07.786`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162-2609`; `derived:count_receiver_starts_finishes=28` |
| First receiver window | present | Iteration 1, peer `3`, `2026-07-01 00:31:26.780..00:40:47.633`; duration `560.853 s`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162-187`; `derived:duration=2026-07-01T00:40:47.633Z-2026-07-01T00:31:26.780Z` |
| Final receiver window | present | Iteration 28, peer `4`, `2026-07-01 04:17:55.273..04:18:07.786`; duration `12.513 s`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2585-2609`; `derived:duration=2026-07-01T04:18:07.786Z-2026-07-01T04:17:55.273Z` |
| Learner status after final receiver finish | present | `BEHIND -> RECONNECT_COMPLETE -> CHECKING -> ACTIVE`; final `ACTIVE` at `2026-07-01 04:18:28.825`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2609`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2668-2669` |
| Later fall-behind scan | present | No later learner fall-behind was found after final `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2669` |

| Iteration | Peer | Receiver window UTC | Duration s | Learner source |
|---:|---:|---|---:|---|
| 1 | 3 | `00:31:26.780..00:40:47.633` | 560.853 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162-187` |
| 2 | 4 | `00:40:55.385..00:48:31.951` | 456.566 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:234-267` |
| 3 | 1 | `00:48:41.312..00:56:16.474` | 455.162 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:326-359` |
| 4 | 3 | `00:56:24.196..01:04:16.337` | 472.141 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:420-453` |
| 5 | 2 | `01:04:25.960..01:12:12.269` | 466.309 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:512-545` |
| 6 | 3 | `01:12:22.178..01:20:05.443` | 463.265 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:604-637` |
| 7 | 4 | `01:20:15.121..01:27:58.643` | 463.522 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:698-728` |
| 8 | 1 | `01:28:07.370..01:35:53.239` | 465.869 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:787-817` |
| 9 | 2 | `01:36:03.096..01:43:51.739` | 468.643 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:876-906` |
| 10 | 5 | `01:44:00.804..01:51:55.126` | 474.322 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:965-995` |
| 11 | 6 | `01:52:04.840..01:59:56.046` | 471.206 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1056-1086` |
| 12 | 4 | `02:00:05.759..02:07:59.379` | 473.620 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1145-1175` |
| 13 | 2 | `02:08:09.171..02:16:09.103` | 479.932 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1236-1266` |
| 14 | 6 | `02:16:17.946..02:24:00.608` | 462.662 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1325-1355` |
| 15 | 2 | `02:24:09.780..02:32:16.296` | 486.516 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1414-1444` |
| 16 | 5 | `02:32:26.077..02:40:17.423` | 471.346 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1505-1535` |
| 17 | 2 | `02:40:27.218..02:48:21.259` | 474.041 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1594-1624` |
| 18 | 6 | `02:48:31.056..02:56:40.117` | 489.061 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1683-1715` |
| 19 | 5 | `02:56:50.038..03:05:02.276` | 492.238 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1774-1804` |
| 20 | 6 | `03:05:11.150..03:13:19.695` | 488.545 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1863-1895` |
| 21 | 4 | `03:13:28.059..03:21:40.372` | 492.313 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1954-1984` |
| 22 | 2 | `03:21:50.029..03:30:06.579` | 496.550 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2043-2073` |
| 23 | 5 | `03:30:16.334..03:38:31.833` | 495.499 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2134-2164` |
| 24 | 2 | `03:38:41.520..03:46:48.211` | 486.691 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2223-2253` |
| 25 | 3 | `03:46:56.944..03:55:21.168` | 504.224 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2314-2344` |
| 26 | 4 | `03:55:30.321..04:05:15.749` | 585.428 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2403-2433` |
| 27 | 6 | `04:05:25.585..04:17:46.546` | 740.961 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2494-2524` |
| 28 | 4 | `04:17:55.273..04:18:07.786` | 12.513 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2585-2609` |

## Learner Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| First iteration learner evidence | present | Iteration 1 peer `3`, receiver window `00:31:26.780..00:40:47.633`, duration `560.853 s`; synchronization stage `553.083 s`; data `15901.918 MB`; path size `294,615,073 -> 320,694,320`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162-187`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:165,169` |
| Representative middle learner evidence | present | Iteration 14 peer `6`, receiver window `02:16:17.946..02:24:00.608`, duration `462.662 s`; synchronization `456.241 s`; data `5817.561 MB`; path size `337,010,480 -> 338,321,255`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1325-1355`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1328,1332` |
| Long late learner evidence | present | Iteration 27 peer `6`, receiver window `04:05:25.585..04:17:46.546`, duration `740.961 s`; synchronization `733.181 s`; data `5727.385 MB`; path size `353,672,196 -> 354,499,751`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2494-2524`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2497,2506` |
| Final learner evidence | present | Iteration 28 peer `4`, receiver window `04:17:55.273..04:18:07.786`, duration `12.513 s`; synchronization `7.109 s`; data `3.235 MB`; path size `354,499,751 -> 354,499,751`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2585-2609`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2588,2592` |
| Learner status churn | present | Episode contains repeated `BEHIND -> RECONNECT_COMPLETE -> BEHIND` between iterations before final completion through `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162-2609`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2668-2669` |

## Teacher Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Teacher-window coverage | ambiguous | `24` of `28` teacher windows are matched from available teacher logs. Counts by matched teacher node: node1 `2`, node2 `7`, node3 `4`, node4 `6`, node6 `5`. | `derived:teacher_window_coverage=24_of_28;available_teacher_log_counts=node1:2,node2:7,node3:4,node4:6,node6:5;missing_peer=node5;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162-2609` |
| First matched teacher sent state | present | Iteration 1, teacher node `3` / `network-node4_logs`, `00:31:34.539..00:40:47.636`; sent range `[320694319,641388638]`, derived size `320,694,320`. | `log:podlog_solo-mdlt-n11/network-node4_logs/swirlds.log:858-893`; `derived:formula=641388638-320694319+1;inputs=log:podlog_solo-mdlt-n11/network-node4_logs/swirlds.log:871-874` |
| Representative teacher sent state | present | Iteration 14, teacher node `6` / `network-node7_logs`, `02:16:24.366..02:24:00.609`; sent range `[338321254,676642508]`, derived size `338,321,255`. | `log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:1140-1177`; `derived:formula=676642508-338321254+1;inputs=log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:1153-1156` |
| Late teacher sent state | present | Iteration 27, teacher node `6` / `network-node7_logs`, `04:05:33.221..04:17:46.546`; sent/root range `[354499750,708999500]`, derived size `354,499,751`. | `log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:1543-1608`; `derived:formula=708999500-354499750+1;inputs=log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:1559` |
| Final matched teacher sent state | present | Iteration 28, teacher node `4` / `network-node5_logs`, `04:18:00.065..04:18:07.786`; sent range `[354499750,708999500]`, derived size `354,499,751`. | `log:podlog_solo-mdlt-n11/network-node5_logs/swirlds.log:1633-1668`; `derived:formula=708999500-354499750+1;inputs=log:podlog_solo-mdlt-n11/network-node5_logs/swirlds.log:1646-1649` |
| Missing teacher windows | missing | Iterations `10`, `16`, `19`, and `23` use peer/teacher node `5`, but `podlog_solo-mdlt-n11/network-node6_logs/swirlds.log` is absent. | files checked: `podlog_solo-mdlt-n11/network-node6_logs/swirlds.log`; affected learner refs: `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:965-995,1505-1535,1774-1804,2134-2164` |

## Reconnect Work-Shape Counters

| Counter | Iteration 1 | Iteration 27 | Iteration 28 | Aggregate 28 iterations | Source references |
|---|---:|---:|---:|---:|---|
| transfersFromTeacher | 346,084,098 | 354,725,135 | 222,634 | 9,176,397,828 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| transfersFromLearner | 341,556,544 | 348,406,806 | 219,743 | 8,959,052,872 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| internalHashes | 27,268,887 | 1,371,555 | 208,762 | 75,102,156 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| internalCleanHashes | 803,906 | 543,470 | 207,721 | 14,423,002 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| internalData | 27,162,799 | 1,370,595 | 208,639 | 74,978,727 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| internalCleanData | 803,698 | 544,846 | 208,593 | 14,451,248 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| leafHashes | 309,729,375 | 341,168,022 | 15,309 | 8,776,944,866 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| leafCleanHashes | 196,681,551 | 340,243,061 | 15,384 | 8,566,993,597 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| leafData | 319,115,120 | 353,366,637 | 15,436 | 9,101,929,942 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| leafCleanData | 199,294,114 | 346,886,934 | 15,433 | 8,744,541,887 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2521`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2606`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |

| Derived aggregate dirty counter | Status | Value | Source references |
|---|---:|---:|---|
| internalDirtyHashes | derived | 60,679,154 | `derived:formula=internalHashes-internalCleanHashes;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| internalDirtyData | derived | 60,527,479 | `derived:formula=internalData-internalCleanData;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| leafDirtyHashes | derived | 209,951,269 | `derived:formula=leafHashes-leafCleanHashes;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |
| leafDirtyData | derived | 357,388,055 | `derived:formula=leafData-leafCleanData;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:184-2606` |

## Network Evidence

Observed CSV send-rate and passive `ss -tin` rate fields are socket behavior/context, not link-capacity evidence.

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner data lower-bound throughput | derived | Selected reconnect log data/time gives lower-bound receive rates: iter1 `28.75 MB/s`, iter27 `7.81 MB/s`, iter28 `0.46 MB/s`. | `derived:formula=dataMegabytes/synchronizationSeconds;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:185-186,2521-2524,2606-2609` |
| CSV network evidence, first iteration | present | Learner node0->teacher node3 `ping_us_03` mean/max `853.20/853.20 us`, send-rate `39,863,624.43/48,276,618.27 B/s`; teacher node3->node0 `ping_us_00` `4000.19 us`, send-rate `29,164,423.03/63,886,984.36 B/s`. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_03,bytes_per_sec_sent_03;rows=1300-1486`; `csv:podlog_solo-mdlt-n11/network-node4_logs/stats/MainNetStats3.csv:columns=ping_us_00,bytes_per_sec_sent_00;rows=6988-7174` |
| CSV network evidence, representative middle iteration | present | Iteration 14 node0->teacher node6 `ping_us_06` `131.52 us`, send-rate `45,780,000.18/50,564,308.89 B/s`; teacher node6->node0 `183.82 us`, send-rate `12,136,839.09/17,765,771.40 B/s`. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_06,bytes_per_sec_sent_06;rows=3397-3550`; `csv:podlog_solo-mdlt-n11/network-node7_logs/stats/MainNetStats6.csv:columns=ping_us_00,bytes_per_sec_sent_00;rows=9085-9238` |
| CSV network evidence, late and final iterations | present | Iteration 27 node6 has learner/teacher RTT/send-rate evidence over rows `5580-5826` and `11268-11514`; iteration 28 node4 has four learner/teacher samples over rows `5830-5833` and `11518-11521`. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_06,bytes_per_sec_sent_06;rows=5580-5826`; `csv:podlog_solo-mdlt-n11/network-node7_logs/stats/MainNetStats6.csv:columns=ping_us_00,bytes_per_sec_sent_00;rows=11268-11514`; `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_04,bytes_per_sec_sent_04;rows=5830-5833`; `csv:podlog_solo-mdlt-n11/network-node5_logs/stats/MainNetStats4.csv:columns=ping_us_00,bytes_per_sec_sent_00;rows=11518-11521` |
| Passive sampler inventory | ambiguous | Alternate-named sampler files `network-node<N>-0_network_sampler.log` exist for nodes 1 through 7, but coverage ends around `2026-07-01T02:05:17Z`, so sampler data covers early catch-up only and not later/final reconnect iterations. | `sampler:network-node1-0_network_sampler.log:1-8,34633-34646`; `sampler:network-node4-0_network_sampler.log:3013-3020,37133-37146`; `derived:sampler_coverage=2026-07-01T00:22:28Z..2026-07-01T02:05:17Z` |
| Passive TCP/window evidence, iteration 1 | present | Iteration 1 learner/teacher socket is linkable: `10.36.38.105:51486 -> 10.36.29.100:50111`, with 243 learner and 243 teacher rows. Representative rows show queueing and `rwnd_limited` rising to `21.8%..24.3%` late in the window. | `sampler:network-node1-0_network_sampler.log:513-514`; `sampler:network-node1-0_network_sampler.log:2207-2208`; `sampler:network-node1-0_network_sampler.log:3817-3818`; `sampler:network-node1-0_network_sampler.log:3845-3846`; `sampler:network-node4-0_network_sampler.log:3013-3014`; `sampler:network-node4-0_network_sampler.log:6233-6234` |
| Passive TCP/window evidence, later iterations | missing | No passive sampler coverage exists for representative middle iteration 14, late iteration 27, final iteration 28, or the final completion/`ACTIVE` region. | files checked: `network-node*-0_network_sampler.log`, `reconnect_network_samples_1.log`, `reconnect_network_samples_1_summary.log`; reason: sampler coverage ends before later/final receiver windows |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 97.5M accounts, 1000 tokens, 97.5M NFTs, `HOT 50%`, 100000 topics, transfer workload for `PT6H`. | `log:client.log:1-9` |
| Client network targets | present | Seven NLG targets on port `50211`. | `log:client.log:10-16` |
| Transaction mix | present | Final summaries include crypto transfer, NFT transfer, HCS message send, swaps, and smart-contract crypto-transfer work. | `log:client.log:10560-10566` |
| Workload before and during catch-up | present | Client samples exist near early, middle, and late portions of the catch-up before client completion. | `log:client.log:6649-6668`; `log:client.log:8682-8707`; `log:client.log:10262-10287` |
| Load continuity limitation | ambiguous | Client load finishes at `2026-07-01 04:01:06.240`, before final receiver finish `04:18:07.786` and `ACTIVE` `04:18:28.825`; the final reconnect iterations are not under continued client load. | `log:client.log:10560-10566`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2609`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2669`; `derived:timestamp_comparison` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| First iteration state-size gap | derived | Learner start size `294,615,073`; teacher target size `320,694,320`; derived gap `26,079,247` leaves. | `derived:formula=320694320-294615073;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:165,169` |
| Representative middle state-size gap | derived | Iteration 14 learner start size `337,010,480`; teacher target size `338,321,255`; derived gap `1,310,775` leaves. | `derived:formula=338321255-337010480;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1328,1332` |
| Late state-size gap | derived | Iteration 27 learner start size `353,672,196`; teacher target size `354,499,751`; derived gap `827,555` leaves. | `derived:formula=354499751-353672196;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2497,2506` |
| Final state-size gap | derived | Iteration 28 learner start size `354,499,751`; target size `354,499,751`; derived gap `0` leaves. | `derived:formula=354499751-354499751;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2588,2592` |
| Learner stats state/store snapshots | present | Learner CSV moves from `vmap_size_state=294,610,018` one second before first start to `354,499,751` at final receiver finish and remains `354,499,751` at `ACTIVE`. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:rows=1299,5833,5840;columns=time,vmap_size_state,startsReconnectAsReceiver,endsReconnectAsReceiver,receiverReconnectDurationSeconds,ds_files_totalSizeMb_state,ds_offheap_dataSourceMb_state` |
| Divergence shape | derived | Growth-heavy catch-up with final convergence. Gaps remain positive through iteration 27 and close to zero in iteration 28; the first-to-final target growth is `59,884,678` leaves. | `derived:classify_from_path_gaps_and_final_convergence;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:165,169,2497,2506,2588,2592/csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:rows=5833,5840` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2609`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2669` |
| Iteration count | derived | `28` learner receiver reconnect iterations before `ACTIVE`. | `derived:count_receiver_starts_before_ACTIVE;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162-2609` |
| Complete catch-up start | present | `2026-07-01 00:31:26.780` UTC. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162` |
| Complete catch-up end | present | `2026-07-01 04:18:07.786` UTC, final learner receiver finish before `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2609`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2669` |
| Complete catch-up duration | derived | `13,601.006 s` (`3:46:41.006`). | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162,2609` |
| Active confirmation | present | `2026-07-01 04:18:28.825` UTC; post-finish to `ACTIVE` is `21.039 s` and is not included in complete catch-up duration. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2669`; `derived:post_finish_to_ACTIVE=2026-07-01T04:18:28.825Z-2026-07-01T04:18:07.786Z` |
| Iteration detail coverage | present | Full 28-iteration timing table is recorded; counter rows are represented by selected outlier rows plus aggregate 28-row sums. | [Reconnect Window And Roles](#reconnect-window-and-roles); [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Additional iterations observed | present | Yes. The complete episode has 28 receiver iterations before `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:162-2609`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:2669` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTwoPhasePessimistic` | [Run Context](#run-context) |
| Manifest batch | present | `2026-07-01-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration` |
| Manifest run | present | `two-phase-pessimistic` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration;run=two-phase-pessimistic` |
| Commit | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | [Run Context](#run-context) |
| Network disease preflight | derived | pass; no fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | [Run Context](#run-context) |
| Episode incomplete reason | not_applicable | Episode is complete. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Teacher node | ambiguous | First teacher node `3`; final teacher node `4`; `24/28` teacher windows are matched and four peer-5 windows are missing. | [Teacher Evidence](#teacher-evidence) |
| First reconnect start UTC | present | `2026-07-01 00:31:26.780` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect end UTC | present | `2026-07-01 00:40:47.633` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| Learner duration | derived | First iteration `560.853 s`; complete catch-up duration `13,601.006 s`. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Teacher reconnect context present | ambiguous | partial; `24/28` teacher windows matched. | [Teacher Evidence](#teacher-evidence) |
| Reconnect stats present | present | yes | [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Teacher/learner state size present | present | yes, with partial teacher-window coverage. | [State And Divergence Evidence](#state-and-divergence-evidence) |
| Workload profile present | present | yes, but load does not continue through final receiver finish/`ACTIVE`. | [Workload Evidence](#workload-evidence) |
| RTT evidence present | present | yes, via CSV stats. | [Network Evidence](#network-evidence) |
| Bandwidth evidence present | present | yes, via CSV send-rate and learner data/time; no later/final passive socket throughput context. | [Network Evidence](#network-evidence) |
| TCP/window evidence present | ambiguous | partial only; passive sampler evidence covers early catch-up but not middle, late, or final receiver windows. | [Network Evidence](#network-evidence) |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `28` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-07-01 00:31:26.780` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-07-01 04:18:07.786` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `13,601.006 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-07-01 04:18:28.825` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | no | `derived:protocol_acceptance_requires_full_TCP_window_evidence;additional_limitations=workload_ends_before_final_completion,partial_teacher_context;inputs=[Network Evidence](#network-evidence),[Workload Evidence](#workload-evidence),[Teacher Evidence](#teacher-evidence)` |
| Reason if not accepted | present | Passive TCP/window evidence is absent for later/final reconnect windows, workload ends before final receiver finish/`ACTIVE`, and four teacher windows are missing due absent peer log. | [Unresolved Evidence Register](#unresolved-evidence-register) |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Timing/work-shape extraction | present | Complete 28-iteration learner episode and aggregate work-shape counters are source-referenced. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations); [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Full calibration acceptance | derived | Not accepted for full calibration because passive TCP/window evidence does not cover later/final reconnect windows, workload ends before final completion, and teacher-window coverage is partial. | [Network Evidence](#network-evidence); [Workload Evidence](#workload-evidence); [Teacher Evidence](#teacher-evidence) |
| Independent-run interpretation | present | This cluster run is an independent live-state calibration anchor; do not use it alone for causal traversal-mode ranking. | [State And Divergence Evidence](#state-and-divergence-evidence); `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Missing peer teacher log | missing | `podlog_solo-mdlt-n11/network-node6_logs/swirlds.log` | expected plain `swirlds.log` | Absent peer log prevents matching iterations `10`, `16`, `19`, and `23`. |
| Passive TCP/window coverage after early catch-up | missing | `network-node*-0_network_sampler.log`, `reconnect_network_samples_1.log`, `reconnect_network_samples_1_summary.log` | exact receiver windows for iterations 14, 27, and 28 | Sampler coverage ends before later/final receiver windows. |
| Workload continuity through final completion | ambiguous | `client.log`, learner `swirlds.log` | timestamp comparison | Client jobs finish before final receiver finish and final `ACTIVE`. |
| Iteration 28 negative dirty hash derivation | ambiguous | learner `swirlds.log` | `ReconnectMapMetrics` row | Raw row has `leafCleanHashes=15,384` and `leafHashes=15,309`, so `leafDirtyHashes=-75` by the standard formula; value is recorded as raw-derived, not corrected. |
| Workflow controls | missing | `version_run.txt`, `client.log`, `pod_state.txt`, pod logs | `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s` | Workflow-control files are absent. |
| Baseline/restored-state upload | missing | Run root inventory, `version_run.txt`, `client.log`, pod logs | `baseline`, `restore`, `restored-state`, `upload`, `state upload` | No baseline/restored-state upload evidence found; treat run as independent live-state workflow evidence. |
