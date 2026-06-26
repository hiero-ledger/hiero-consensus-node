# Parallel-Sync Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/30-06-2026/dallas12_pullParallelSync/report`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | derived | No fatal network disease found. All seven plain `swirlds.log` files are present; no post-startup `ACTIVE -> CHECKING` and no missing-parent evidence were found. | `derived:network_disease_preflight;inputs=log:podlog_solo-mdlt-n12/network-node*_logs/swirlds*.log;patterns=ACTIVE->CHECKING,Shadowgraph: Missing non-expired other parent` |
| Files searched | present | `network-node1_logs` through `network-node7_logs`, including each available `swirlds.log`, `swirlds-vmap.log`, `swirlds-hashstream/swirlds-hashstream.log`, plus learner `swirlds_reconnect_1.log`. | `derived:file_inventory;scope=podlog_solo-mdlt-n12/network-node*_logs/swirlds*.log` |
| Active confirmation context | present | Learner post-reconnect `CHECKING -> ACTIVE` occurred at `2026-06-30 00:24:34.219`; preceding `RECONNECT_COMPLETE -> CHECKING` is non-fatal. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:494`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:498` |
| Extraction disposition | present | Normal extraction is valid. | [Analysis Output Per Mode](#analysis-output-per-mode) |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullParallelSync`; `version_run.txt` and all seven copied `settingsUsed.txt` files agree. | `config:version_run.txt:key=inputs.AddSettings;line=8`; `derived:scan_all_nodes;inputs=config:podlog_solo-mdlt-n12/network-node*_logs/config/settingsUsed.txt:line=725;expected=pullParallelSync` |
| Namespace | present | `Dallas12` | `config:version_run.txt:key=namespace;line=1` |
| Commit hash | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=inputs.hederaversion;line=2`; `config:version_run.txt:key=hederahash;line=11` |
| Version context | present | Solo/chart version `latest_tested_solo-charts0.59_balanced`; client observed Services/HAPI `0.77.0`. | `config:version_run.txt:key=inputs.soloversion;line=3`; `log:client.log:24` |
| Run number / job URL | present | run `306`; workflow URL `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/28399535148`. | `config:version_run.txt:key=JOB_URL;line=10`; `config:version_run.txt:key=run_number;line=12` |
| NLG controls | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `97,500,000` accounts, `6h`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`; `config:version_run.txt:key=inputs.NLGDparams;line=4`; `config:version_run.txt:key=inputs.NLG_Accounts;line=5`; `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Learner node and pod | present | Learner is node ID `0`, pod `network-node1-0`, `POD_IP=10.36.41.225`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:161`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:729`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:948`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:1143` |
| Client workload profile | present | 32 clients, 97.5M accounts, 1000 tokens, 97.5M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer duration `PT6H`. | `log:client.log:1-9` |
| Client network targets | present | Seven NLG targets on port `50211`; service-host config maps to the same target IP set. | `log:client.log:10-16`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:997`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:1020`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:1043`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:1066`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:1089`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:1112`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:1135` |
| Workflow-control logs | missing | No workflow-control source was found for warmtime, downtime, loop count, or `profileReconnectLoopK8s`. | files checked: run root inventory, `version_run.txt`, `client.log`, pod logs; patterns checked: `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s`; reason: no matches |
| Baseline/restored state upload | missing | No baseline, restore, upload, or state-upload evidence was found. | files checked: run root inventory, `version_run.txt`, `client.log`, pod logs; patterns checked: `baseline`, `restore`, `restored-state`, `restored state`, `upload`, `state upload`; reason: no matches |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved `OBSERVING -> BEHIND` at `2026-06-30 00:05:56.040`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:157` |
| Iteration 1 receiver window | present | Learner node `0` received from teacher peer `4`, `2026-06-30 00:05:56.122..00:13:06.372`; wall-clock duration `430.250 s`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:161`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:186`; `derived:formula=end-start` |
| Iteration 1 teacher window | present | Teacher node `4` (`network-node5_logs`) sent to learner node `0`, `2026-06-30 00:06:02.944..00:13:06.375`, round `126509`. | `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:648`; `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:685` |
| Iteration 2 receiver window | present | Learner received from teacher peer `6`, `2026-06-30 00:13:14.010..00:15:57.873`; wall-clock duration `163.863 s`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:233`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:266`; `derived:formula=end-start` |
| Iteration 2 teacher window | present | Teacher node `6` (`network-node7_logs`) sent to learner node `0`, `2026-06-30 00:13:22.250..00:15:57.875`, round `130759`. | `log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:651`; `log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:718` |
| Iteration 3 receiver window | present | Learner received from teacher peer `3`, `2026-06-30 00:16:06.001..00:18:00.140`; wall-clock duration `114.139 s`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:311`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:344`; `derived:formula=end-start` |
| Iteration 3 teacher window | present | Teacher node `3` (`network-node4_logs`) sent to learner node `0`, `2026-06-30 00:16:14.138..00:18:00.144`, round `132412`. | `log:podlog_solo-mdlt-n12/network-node4_logs/swirlds.log:681`; `log:podlog_solo-mdlt-n12/network-node4_logs/swirlds.log:716` |
| Iteration 4 receiver window | present | Learner received from teacher peer `4`, `2026-06-30 00:18:07.492..00:19:39.127`; wall-clock duration `91.635 s`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:389`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:422`; `derived:formula=end-start` |
| Iteration 4 teacher window | present | Teacher node `4` (`network-node5_logs`) sent to learner node `0`, `2026-06-30 00:18:14.418..00:19:39.129`, round `133588`. | `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:719`; `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:754` |
| Learner status after final receiver finish | present | `BEHIND -> RECONNECT_COMPLETE -> CHECKING -> ACTIVE`; final `ACTIVE` at `2026-06-30 00:24:34.219`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:440`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:494`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:498` |

## Learner Evidence

| Iteration | Status | Extracted value or observation | Source references |
|---:|---:|---|---|
| 1 | present | Receiver synchronization time `423.421 s`, data `8154.9465 MB`; learner view range `[294604931,589209862]` (`294,604,932` leaves) and target range `[304701794,609403588]` (`304,701,795` leaves); deleted leading leaves `10,096,863`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:164`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:168`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:171`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:184-186` |
| 2 | present | Receiver synchronization time `155.62 s`, data `2109.1939 MB`; view range `[304701794,609403588]`, target range `[306079121,612158242]`; deleted leading leaves `1,377,327`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:244`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:248`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:252`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:264-266` |
| 3 | present | Receiver synchronization time `106.0 s`, data `1105.7328 MB`; view range `[306079121,612158242]`, target range `[306617245,613234490]`; deleted leading leaves `538,124`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:314`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:326`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:330`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:342-344` |
| 4 | present | Receiver synchronization time `84.709 s`, data `856.4539 MB`; view range `[306617245,613234490]`, target range `[306993221,613986442]`; deleted leading leaves `375,976`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:392`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:404`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:408`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:420-422` |

## Teacher Evidence

| Iteration | Teacher | Status | Extracted value or observation | Source references |
|---:|---:|---:|---|---|
| 1 | node `4` | present | Sent round `126509`, range `[304701794,609403588]`, derived sent size `304,701,795`; total teacher requests `234,330,101`. | `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:648-665`; `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:668-685` |
| 2 | node `6` | present | Sent round `130759`, range `[306079121,612158242]`, derived sent size `306,079,122`; total teacher requests `91,577,640`. | `log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:651-667`; `log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:701-718` |
| 3 | node `3` | present | Sent round `132412`, range `[306617245,613234490]`, derived sent size `306,617,246`; total teacher requests `52,996,639`. | `log:podlog_solo-mdlt-n12/network-node4_logs/swirlds.log:681-697`; `log:podlog_solo-mdlt-n12/network-node4_logs/swirlds.log:699-716` |
| 4 | node `4` | present | Sent round `133588`, range `[306993221,613986442]`, derived sent size `306,993,222`; total teacher requests `42,676,828`. | `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:719-735`; `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:737-754` |

## Reconnect Work-Shape Counters

| Iteration | transfersFromTeacher | transfersFromLearner | internalHashes | internalCleanHashes | internalData | internalCleanData | leafHashes | leafCleanHashes | leafData | leafCleanData | Source references |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 229,276,292 | 229,944,908 | 152,975,405 | 90,590,584 | 152,773,844 | 90,466,077 | 76,343,490 | 27,545,543 | 79,302,525 | 27,847,605 | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:183` |
| 2 | 90,105,854 | 88,787,109 | 77,705,693 | 61,299,719 | 77,389,305 | 61,173,768 | 12,922,464 | 5,151,075 | 13,201,748 | 5,177,918 | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:263` |
| 3 | 52,191,471 | 51,863,730 | 47,373,045 | 39,415,515 | 47,301,857 | 39,385,388 | 5,258,062 | 2,132,953 | 5,382,144 | 2,145,374 | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:341` |
| 4 | 42,060,815 | 41,653,216 | 38,668,506 | 32,667,406 | 38,628,231 | 32,653,572 | 3,734,138 | 1,529,245 | 3,836,048 | 1,538,244 | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:419` |

| Iteration | internalDirtyHashes | internalDirtyData | leafDirtyHashes | leafDirtyData | Source references |
|---:|---:|---:|---:|---:|---|
| 1 | 62,384,821 | 62,307,767 | 48,797,947 | 51,454,920 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:183` |
| 2 | 16,405,974 | 16,215,537 | 7,771,389 | 8,023,830 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:263` |
| 3 | 7,957,530 | 7,916,469 | 3,125,109 | 3,236,770 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:341` |
| 4 | 6,001,100 | 5,974,659 | 2,204,893 | 2,297,804 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:419` |

## Network Evidence

Observed `send`, `delivery_rate`, and passive byte-counter rates are socket behavior during samples, not link capacity.

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner data lower-bound throughput | derived | Reconnect log data/time gives lower-bound receive rates: iter1 `8154.95 MB / 423.421 s = 19.26 MB/s`; iter2 `2109.19 MB / 155.62 s = 13.55 MB/s`; iter3 `1105.73 MB / 106.0 s = 10.43 MB/s`; iter4 `856.45 MB / 84.709 s = 10.11 MB/s`. | `derived:formula=dataMegabytes/timeInSeconds;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:184-186/log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:264-266/log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:342-344/log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:420-422` |
| CSV RTT and send-rate, iteration 1 | present | node0<->node4 CSV rows show learner avg/max `bytes_per_sec_sent_04=35.28/46.15 MB/s`, teacher avg/max `bytes_per_sec_sent_00=20.06/56.88 MB/s`, ping `634.7/663.4 us`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:columns=bytes_per_sec_sent_04,ping_us_04;rows=1343-1486`; `csv:podlog_solo-mdlt-n12/network-node5_logs/stats/MainNetStats4.csv:columns=bytes_per_sec_sent_00,ping_us_00;rows=4951-5093` |
| CSV RTT and send-rate, iteration 2 | present | node0<->node6 rows show learner avg/max `34.52/48.22 MB/s`, teacher `11.74/34.95 MB/s`, ping `126.91/154.64 us`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:columns=bytes_per_sec_sent_06,ping_us_06;rows=1489-1543`; `csv:podlog_solo-mdlt-n12/network-node7_logs/stats/MainNetStats6.csv:columns=bytes_per_sec_sent_00,ping_us_00;rows=5097-5151` |
| CSV RTT and send-rate, iteration 3 | present | node0<->node3 rows show learner avg/max `28.06/43.37 MB/s`, teacher `7.61/23.86 MB/s`, ping `125.03/161.12 us`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:columns=bytes_per_sec_sent_03,ping_us_03;rows=1547-1584`; `csv:podlog_solo-mdlt-n12/network-node4_logs/stats/MainNetStats3.csv:columns=bytes_per_sec_sent_00,ping_us_00;rows=5154-5191` |
| CSV RTT and send-rate, iteration 4 | present | node0<->node4 rows show learner avg/max `27.00/46.14 MB/s`, teacher `6.96/13.12 MB/s`, ping `123.47/143.96 us`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:columns=bytes_per_sec_sent_04,ping_us_04;rows=1587-1617`; `csv:podlog_solo-mdlt-n12/network-node5_logs/stats/MainNetStats4.csv:columns=bytes_per_sec_sent_00,ping_us_00;rows=5195-5224` |
| Passive sampler inventory | present | Seven per-node sampler logs are present, each with 709 timestamp blocks from `2026-06-29T23:54:48Z` through `2026-06-30T00:24:37Z`. Top-level `reconnect_network_samples_1.log` only contains a stop marker. | `sampler:network_sampler_network-node1-0.log:1;window=2026-06-29T23:54:48Z..2026-06-30T00:24:37Z`; `sampler:reconnect_network_samples_1.log:1;window=2026-06-29T23:54:48Z..2026-06-30T00:24:38Z` |
| Passive TCP/window evidence, iteration 1 | present | Socket `10.36.41.225:51434 <-> 10.36.62.72:50111`; learner max Recv-Q/Send-Q `1,305,528/1,827,416`, teacher `3,510,968/1,000,944`; RTT avg/max learner `0.251/3.281 ms`, teacher `0.849/6.100 ms`. | `sampler:network_sampler_network-node1-0.log:575-3138;window=2026-06-30T00:05:56.122Z..2026-06-30T00:13:06.372Z`; `sampler:network_sampler_network-node5-0.log:3419-5982;window=2026-06-30T00:05:56.122Z..2026-06-30T00:13:06.372Z` |
| Passive TCP/window evidence, iteration 2 | present | Socket `10.36.41.225:60066 <-> 10.36.58.49:50111`; learner max Recv-Q/Send-Q `605,005/1,962,880`, teacher `2,254,402/24,532`; RTT avg/max learner `0.325/2.144 ms`, teacher `0.572/6.262 ms`. | `sampler:network_sampler_network-node1-0.log:3197-4178;window=2026-06-30T00:13:14.010Z..2026-06-30T00:15:57.873Z`; `sampler:network_sampler_network-node7-0.log:6031-7012;window=2026-06-30T00:13:14.010Z..2026-06-30T00:15:57.873Z` |
| Passive TCP/window evidence, iteration 3 | present | Socket `10.36.41.225:55964 <-> 10.36.71.24:50111`; learner max Recv-Q/Send-Q `1,102,964/257,488`, teacher `2,464,102/8,219`; RTT avg/max learner `0.510/2.091 ms`, teacher `0.872/11.185 ms`. | `sampler:network_sampler_network-node1-0.log:4235-4656;window=2026-06-30T00:16:06.001Z..2026-06-30T00:18:00.140Z`; `sampler:network_sampler_network-node4-0.log:7065-7486;window=2026-06-30T00:16:06.001Z..2026-06-30T00:18:00.140Z` |
| Passive TCP/window evidence, iteration 4 | present | Socket `10.36.41.225:51434 <-> 10.36.62.72:50111`; learner max Recv-Q/Send-Q `1,735/16,438`, teacher `3,489,967/8,219`; RTT avg/max learner `1.019/9.015 ms`, teacher `1.445/8.166 ms`. | `sampler:network_sampler_network-node1-0.log:4677-4944;window=2026-06-30T00:18:07.492Z..2026-06-30T00:19:39.127Z`; `sampler:network_sampler_network-node5-0.log:7521-7788;window=2026-06-30T00:18:07.492Z..2026-06-30T00:19:39.127Z` |
| Passive attribution caveat | ambiguous | Socket endpoint attribution is strong by pod IP, peer port, and reconnect window, but traffic is not frame-level reconnect-only. | [Passive TCP/window evidence, iteration 1](#network-evidence); [Passive TCP/window evidence, iteration 2](#network-evidence); [Passive TCP/window evidence, iteration 3](#network-evidence); [Passive TCP/window evidence, iteration 4](#network-evidence) |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 97.5M accounts, 1000 tokens, 97.5M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer workload for `PT6H`. | `log:client.log:1-9` |
| Transaction jobs | present | Crypto transfer, NFT transfer, message send, swaps, and smart-contract crypto transfer jobs started after NFT mint phase. | `log:client.log:3851-3856` |
| Workload near episode start | present | Near first receiver start, client load has receipts/transactions around `146.2M-146.3M` at about `10.3k TPS`, with NFT, crypto transfer, and message workloads active. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:161`; `log:client.log:5023-5029` |
| Workload near final receiver finish | present | Near final receiver finish, transactions/receipts are around `154.7M-154.9M`; crypto transfer around `5001 TPS`, messages around `2000 TPS`, NFTs around `2999 TPS`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:420-422`; `log:client.log:5279-5285` |
| Load continuity | present | Client load continues through final receiver finish, through learner `ACTIVE`, and after `ACTIVE`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:498`; `log:client.log:5371-5377`; `log:client.log:5570-5589` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Iteration 1 state-size gap | derived | Learner start range size `294,604,932`; teacher target range size `304,701,795`; derived gap `10,096,863` leaves. | `derived:formula=304701795-294604932;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:164/log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:168/log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:648-665` |
| Iteration 2 state-size gap | derived | Prior target `304,701,795`; next target `306,079,122`; derived gap `1,377,327` leaves. | `derived:formula=306079122-304701795;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:244-252/log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:651-667` |
| Iteration 3 state-size gap | derived | Prior target `306,079,122`; next target `306,617,246`; derived gap `538,124` leaves. | `derived:formula=306617246-306079122;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:314-330/log:podlog_solo-mdlt-n12/network-node4_logs/swirlds.log:681-697` |
| Iteration 4 state-size gap | derived | Prior target `306,617,246`; next target `306,993,222`; derived gap `375,976` leaves. | `derived:formula=306993222-306617246;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:392-408/log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:719-735` |
| Divergence shape | derived | Growth-heavy multi-iteration reconnect. Work drops each iteration across state-size gap, round gap, data MB, teacher request totals, and dirty counters. | `derived:classify_from_state_gap_and_clean_dirty_counters;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:183,263,341,419/log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:164-422` |
| Post-final fall-behind context | present | After iteration 4, a peer5 fall-behind event with round gap `134569 - 133588 = 981` appears before `CHECKING` and `ACTIVE`; no additional receiver start follows before `ACTIVE`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:464`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:494`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:498` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:422`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:498` |
| Iteration count | derived | `4` learner receiver reconnect iterations before `ACTIVE`. | `derived:count_receiver_starts_before_ACTIVE;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:161,233,311,389,498` |
| Complete catch-up start | present | `2026-06-30 00:05:56.122` UTC. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:161` |
| Complete catch-up end | present | `2026-06-30 00:19:39.127` UTC, final learner receiver finish before `ACTIVE`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:422`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:498` |
| Complete catch-up duration | derived | `823.005 s`. | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:161,422` |
| Active confirmation | present | `2026-06-30 00:24:34.219` UTC. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:498` |
| Additional iterations observed | present | Yes. The complete episode has four receiver iterations before `ACTIVE`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:161`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:233`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:311`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:389`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:498` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullParallelSync` | [Run Context](#run-context) |
| Manifest batch | present | `2026-06-30-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration` |
| Manifest run | present | `parallel-sync` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration;run=parallel-sync` |
| Commit | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | [Run Context](#run-context) |
| Network disease preflight | derived | pass; no fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | [Run Context](#run-context) |
| Teacher node | present | First iteration teacher node `4`; later teacher nodes `6`, `3`, and `4`. | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect start UTC | present | `2026-06-30 00:05:56.122` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect end UTC | present | `2026-06-30 00:13:06.372` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| Learner duration | derived | First iteration `430.250 s`; complete catch-up duration `823.005 s`. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Teacher reconnect context present | present | yes | [Teacher Evidence](#teacher-evidence) |
| Reconnect stats present | present | yes | [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Teacher/learner state size present | present | yes | [State And Divergence Evidence](#state-and-divergence-evidence) |
| Workload profile present | present | yes | [Workload Evidence](#workload-evidence) |
| RTT evidence present | present | yes, via CSV stats and passive socket RTT fields. | [Network Evidence](#network-evidence) |
| Bandwidth evidence present | present | yes, via learner data/time, CSV send-rate, and passive socket context. | [Network Evidence](#network-evidence) |
| TCP/window evidence present | present | yes, passive sampler fields overlap all four reconnect iterations. | [Network Evidence](#network-evidence) |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `4` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-06-30 00:05:56.122` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-06-30 00:19:39.127` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `823.005 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-06-30 00:24:34.219` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | yes | `derived:protocol_acceptance;inputs=[Run Context](#run-context),[Network Disease Preflight](#network-disease-preflight),[Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations),[Reconnect Work-Shape Counters](#reconnect-work-shape-counters),[State And Divergence Evidence](#state-and-divergence-evidence),[Workload Evidence](#workload-evidence),[Network Evidence](#network-evidence)` |
| Reason if not accepted | not_applicable | Accepted; no rejection reason. | [Acceptance Notes](#acceptance-notes) |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Calibration acceptance | derived | The run has no fatal preflight disease, confirmed mode/learner, complete catch-up through `ACTIVE`, per-iteration counters, coarse state/workload context, and RTT/bandwidth/TCP-window evidence. | [Analysis Output Per Mode](#analysis-output-per-mode) |
| Multiple iterations | present | The complete catch-up episode includes four receiver iterations. Trend/ranking should use complete catch-up duration, not only first-iteration duration. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Independent-run interpretation | present | This cluster run is an independent live-state calibration anchor; do not use it alone for causal traversal-mode ranking. | [State And Divergence Evidence](#state-and-divergence-evidence); `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Workflow controls | missing | Run root file inventory, `version_run.txt`, `client.log`, pod logs | `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s` | Workflow-control files are absent from this artifact. |
| Baseline/restored-state upload | missing | Run root file inventory, `version_run.txt`, `client.log`, pod logs | `baseline`, `restore`, `restored-state`, `upload`, `state upload` | No baseline/restored-state upload evidence found; treat run as independent live-state workflow evidence. |
| Top-level reconnect sampler detail | missing | `reconnect_network_samples_1.log` | `ESTAB`, `Recv-Q`, `Send-Q`, timestamped samples | File contains only sampler stop marker; per-node sampler files contain the usable passive socket evidence. |
| Frame-level reconnect-only socket attribution | ambiguous | per-node sampler logs for learner/teacher pairs | learner-teacher sockets during receiver windows | Endpoint attribution is strong by pod IP/port/window, but passive socket samples are not frame-level reconnect-only telemetry. |
