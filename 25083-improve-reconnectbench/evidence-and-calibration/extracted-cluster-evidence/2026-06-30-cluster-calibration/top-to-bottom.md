# Top-To-Bottom Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/30-06-2026/dallas10_pullTopToBottom/report`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | derived | No fatal network disease found. Missing-parent evidence is present on peer node logs, but no post-startup `ACTIVE -> CHECKING` evidence was found in the available run-local node logs. | `derived:network_disease_preflight;inputs=log:podlog_solo-mdlt-n10/network-node2_logs/swirlds.log:711,log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:748,log:podlog_solo-mdlt-n10/network-node4_logs/swirlds.log:774,log:podlog_solo-mdlt-n10/network-node5_logs/swirlds.log:711,log:podlog_solo-mdlt-n10/network-node6_logs/swirlds.log:723,log:podlog_solo-mdlt-n10/network-node7_logs/swirlds.log:752;reason=fatal_requires_ACTIVE_to_CHECKING_and_missing_parent` |
| Files searched | present | Node log directories `network-node1_logs` through `network-node7_logs` were present. Plain `swirlds.log` files were present and searched for nodes 1 through 7. | `derived:file_inventory;scope=podlog_solo-mdlt-n10/network-node*_logs/swirlds.log` |
| Startup-only transitions | present | Startup `OBSERVING -> CHECKING -> ACTIVE` appears on nodes 2 through 7 and was excluded from fatal preflight. | `log:podlog_solo-mdlt-n10/network-node2_logs/swirlds.log:123-125` |
| Extraction disposition | present | Normal extraction is allowed. Learner plain `swirlds.log` is present and used for exact receiver lifecycle, path-range, synchronization, data-usage, and work-shape evidence. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:161-186`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:231-264`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:323-356` |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTopToBottom`; `version_run.txt` and all seven copied `settingsUsed.txt` files agree. | `config:version_run.txt:key=inputs.AddSettings;line=8`; `derived:scan_all_nodes;inputs=config:podlog_solo-mdlt-n10/network-node*_logs/config/settingsUsed.txt:line=725;expected=pullTopToBottom` |
| Namespace | present | `Dallas10` | `config:version_run.txt:key=namespace;line=1` |
| Commit hash | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=inputs.hederaversion;line=2`; `config:version_run.txt:key=hederahash;line=11` |
| Version context | present | Solo/chart version `latest_tested_solo-charts0.59_balanced`; client observed Services/HAPI `0.77.0`; learner node started as `v0.77.0-SNAPSHOT+0`. | `config:version_run.txt:key=inputs.soloversion;line=3`; `log:client.log:24`; `log:podlog_solo-mdlt-n10/network-node1_logs/hgcaa.log:46` |
| Run number / job URL | present | run `304`; workflow URL `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/28399413260`. | `config:version_run.txt:key=JOB_URL;line=10`; `config:version_run.txt:key=run_number;line=12` |
| NLG controls | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `97,500,000` accounts, `6h`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`; `config:version_run.txt:key=inputs.NLGDparams;line=4`; `config:version_run.txt:key=inputs.NLG_Accounts;line=5`; `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Learner node and pod | present | Learner is node ID `0`, log directory `network-node1_logs`, pod `network-node1-0`. | `log:podlog_solo-mdlt-n10/network-node1_logs/hgcaa.log:46`; `log:podlog_solo-mdlt-n10/network-node1_logs/hgcaa.log:1821`; `log:podlog_solo-mdlt-n10/network-node1_logs/support-zip.log:60` |
| Learner pod IP | ambiguous | No labeled `POD_IP` or pod env record was found for the learner. Passive sampler local socket evidence shows `10.36.69.147`, but it is not labeled `POD_IP` in config. | `sampler:network_sampler_network-node1-0.log:471-490;window=2026-06-30T00:09:52Z..2026-06-30T00:33:45Z` |
| Workflow-control logs | missing | No workflow-control source was found for warmtime, downtime, loop count, or `profileReconnectLoopK8s`. | files checked: `version_run.txt`, `client.log`, `podlog_solo-mdlt-n10/*`, `podlog_solo-mdlt-n10/network-node*_logs/*`; patterns checked: `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s`; reason: no matches |
| Baseline/restored state upload | missing | No baseline, restore, upload, or state-upload evidence was found. | files checked: run root inventory, `version_run.txt`, `client.log`, pod logs; patterns checked: `baseline`, `restore`, `restored-state`, `restored state`, `upload`, `state upload`; reason: no matches |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved to `BEHIND` at `2026-06-30 00:21:08.215`. | `log:podlog_solo-mdlt-n10/network-node1_logs/hgcaa.log:1820-1821` |
| Iteration 1 receiver window | present | Learner received from peer node `6`, `2026-06-30 00:21:08.306..00:27:30.674`, wall duration `382.368 s`, receiver round `107824 -> 137835`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:161`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:186`; `derived:duration=2026-06-30T00:27:30.674Z-2026-06-30T00:21:08.306Z` |
| Iteration 1 teacher window | present | Teacher node `6` (`network-node7_logs`) sent to learner node `0`, `2026-06-30 00:21:16.036..00:27:30.675`, round `137835`. | `log:podlog_solo-mdlt-n10/network-node7_logs/swirlds.log:682-717` |
| Iteration 2 receiver window | present | Learner received from peer node `3`, `2026-06-30 00:27:37.090..00:29:33.338`, wall duration `116.248 s`, receiver round `137835 -> 141569`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:231`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:264`; `derived:duration=2026-06-30T00:29:33.338Z-2026-06-30T00:27:37.090Z` |
| Iteration 2 teacher window | present | Teacher node `3` (`network-node4_logs`) sent to learner node `0`, `2026-06-30 00:27:44.745..00:29:33.340`, round `141569`. | `log:podlog_solo-mdlt-n10/network-node4_logs/swirlds.log:683-718` |
| Iteration 3 receiver window | present | Learner received from peer node `2`, `2026-06-30 00:29:43.231..00:30:43.868`, wall duration `60.637 s`, receiver round `141569 -> 142786`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:323`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:356`; `derived:duration=2026-06-30T00:30:43.868Z-2026-06-30T00:29:43.231Z` |
| Iteration 3 teacher window | present | Teacher node `2` (`network-node3_logs`) sent to learner node `0`, `2026-06-30 00:29:51.073..00:30:43.870`, round `142786`. | `log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:682-747` |
| Learner status after final receiver finish | present | `RECONNECT_COMPLETE -> CHECKING -> ACTIVE`; `ACTIVE` at `2026-06-30 00:33:36.552`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:356`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:458` |

## Learner Evidence

| Iteration | Status | Extracted value or observation | Source references |
|---:|---:|---|---|
| 1 | present | Learner path range `[294597232,589194464]` size `294,597,233` to target `[304720426,609440852]` size `304,720,427`; deleted leading leaves `10,123,194`; synchronization `374.629 s`; data `7958.613612174988 MB`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:164`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:168`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:171`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:184-186` |
| 2 | present | Learner path range `[304720426,609440852]` size `304,720,427` to target `[305935864,611871728]` size `305,935,865`; deleted leading leaves `1,215,438`; synchronization `107.915 s`; data `1641.4289321899414 MB`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:234`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:246`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:250`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:262-264` |
| 3 | present | Learner path range `[305935864,611871728]` size `305,935,865` to target `[306331789,612663578]` size `306,331,790`; deleted leading leaves `395,925`; synchronization `52.792 s`; data `666.0146207809448 MB`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:326`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:338`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:342`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:354-356` |
| Stage timing | present | Learner status sequence includes repeated `BEHIND -> RECONNECT_COMPLETE -> BEHIND` before final `CHECKING -> ACTIVE`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:203`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:227`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:282`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:319`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:374`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:425`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:458` |

## Teacher Evidence

| Iteration | Teacher | Status | Extracted value or observation | Source references |
|---:|---:|---:|---|---|
| 1 | node `6` | present | Sent-state metadata and root response report `[304720426,609440852]`; derived sent/root size `304,720,427` leaves. | `log:podlog_solo-mdlt-n10/network-node7_logs/swirlds.log:682-698` |
| 2 | node `3` | present | Sent-state metadata and root response report `[305935864,611871728]`; derived sent/root size `305,935,865` leaves. | `log:podlog_solo-mdlt-n10/network-node4_logs/swirlds.log:683-699` |
| 3 | node `2` | present | Sent-state metadata and root response report `[306331789,612663578]`; derived sent/root size `306,331,790` leaves. | `log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:682-698` |
| Teacher sampled growth | present | Teacher `vmap_size_state` grew during each sampled reconnect window: node6 `+1,176,286`, node3 `+337,027`, node2 `+159,579`. | `csv:podlog_solo-mdlt-n10/network-node7_logs/stats/MainNetStats6.csv:rows=5272,5397;column=vmap_size_state`; `csv:podlog_solo-mdlt-n10/network-node4_logs/stats/MainNetStats3.csv:rows=5401,5437;column=vmap_size_state`; `csv:podlog_solo-mdlt-n10/network-node3_logs/stats/MainNetStats2.csv:rows=5444,5461;column=vmap_size_state` |

## Reconnect Work-Shape Counters

| Iteration | transfersFromTeacher | transfersFromLearner | internalHashes | internalCleanHashes | internalData | internalCleanData | leafHashes | leafCleanHashes | leafData | leafCleanData | Source references |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 215,249,005 | 212,330,138 | 73,863,471 | 37,175,399 | 73,791,319 | 37,150,447 | 138,053,874 | 89,093,214 | 141,926,231 | 90,357,040 | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:183` |
| 2 | 63,297,300 | 62,975,746 | 38,934,930 | 29,002,516 | 38,873,619 | 28,990,771 | 24,075,245 | 17,395,121 | 24,795,285 | 17,689,297 | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:261` |
| 3 | 27,669,209 | 27,726,782 | 19,330,160 | 15,320,420 | 19,271,711 | 15,301,922 | 8,451,724 | 6,137,442 | 8,611,590 | 6,201,246 | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:353` |

| Iteration | internalDirtyHashes | internalDirtyData | leafDirtyHashes | leafDirtyData | Source references |
|---:|---:|---:|---:|---:|---|
| 1 | 36,688,072 | 36,640,872 | 48,960,660 | 51,569,191 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:183` |
| 2 | 9,932,414 | 9,882,848 | 6,680,124 | 7,105,988 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:261` |
| 3 | 4,009,740 | 3,969,789 | 2,314,282 | 2,410,344 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:353` |

## Network Evidence

Observed `send`, `delivery_rate`, and passive byte-counter rates are socket behavior during samples, not link capacity.

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner data lower-bound throughput | derived | Reconnect log data/time gives lower-bound receive rates: iter1 `7958.61 MB / 374.629 s = 21.24 MB/s`; iter2 `1641.43 MB / 107.915 s = 15.21 MB/s`; iter3 `666.01 MB / 52.792 s = 12.62 MB/s`. | `derived:formula=dataMegabytes/timeInSeconds;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:184-186/log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:262-264/log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:354-356` |
| CSV RTT and send-rate, iteration 1 | present | Over sampled iter1 window, node0->node6 `ping_us_06=933.80`, mean/max `bytes_per_sec_sent_06=36,058,917.74/46,106,023.57 B/s`; node6->node0 `ping_us_00=745.92`, mean/max `bytes_per_sec_sent_00=20,894,525.08/56,293,355.74 B/s`. `ping_us_*MIN` rows are sentinel `9999999`. | `csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_06,ping_us_06MIN,bytes_per_sec_sent_06;rows=1348-1473`; `csv:podlog_solo-mdlt-n10/network-node7_logs/stats/MainNetStats6.csv:columns=ping_us_00,ping_us_00MIN,bytes_per_sec_sent_00;rows=5272-5397` |
| CSV RTT and send-rate, iteration 2 | present | node0->node3 `ping_us_03=143.19`, mean/max `bytes_per_sec_sent_03=33,683,827.96/41,294,846.26 B/s`; node3->node0 `ping_us_00=138.75`, mean/max `bytes_per_sec_sent_00=12,674,581.27/18,408,051.55 B/s`. | `csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_03,ping_us_03MIN,bytes_per_sec_sent_03;rows=1478-1513`; `csv:podlog_solo-mdlt-n10/network-node4_logs/stats/MainNetStats3.csv:columns=ping_us_00,ping_us_00MIN,bytes_per_sec_sent_00;rows=5402-5436` |
| CSV RTT and send-rate, iteration 3 | present | node0->node2 `ping_us_02=149.62`, mean/max `bytes_per_sec_sent_02=25,259,416.17/38,282,134.22 B/s`; node2->node0 `ping_us_00=148.56`, mean/max `bytes_per_sec_sent_00=8,857,217.72/17,409,735.82 B/s`. | `csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_02,ping_us_02MIN,bytes_per_sec_sent_02;rows=1520-1537`; `csv:podlog_solo-mdlt-n10/network-node3_logs/stats/MainNetStats2.csv:columns=ping_us_00,ping_us_00MIN,bytes_per_sec_sent_00;rows=5444-5461` |
| Passive sampler inventory | present | Per-node samplers exist for all seven nodes and cover `2026-06-30T00:09:52Z..00:33:45Z`. The top-level `reconnect_network_samples_1.log` contains only a sampler stop marker. | `sampler:network_sampler_network-node1-0.log:1;window=2026-06-30T00:09:52Z..2026-06-30T00:33:45Z`; `sampler:network_sampler_network-node1-0.log:4985;window=2026-06-30T00:09:52Z..2026-06-30T00:33:45Z`; `sampler:reconnect_network_samples_1.log:1;window=2026-06-30T00:09:52Z..2026-06-30T00:33:46Z` |
| Passive TCP/window evidence, iteration 1 | present | Candidate learner/teacher socket samples overlap exact receiver window `2026-06-30T00:21:08.306Z..00:27:30.674Z` for peer pod `network-node7-0`; representative rows show non-trivial queues and `rwnd_limited`. | `sampler:network_sampler_network-node1-0.log:477-2730;peer=network-node7-0;window=2026-06-30T00:21:08.306Z..2026-06-30T00:27:30.674Z`; `sampler:network_sampler_network-node7-0.log:2857-5110;window=2026-06-30T00:21:08.306Z..2026-06-30T00:27:30.674Z`; `sampler:network_sampler_network-node1-0.log:989-990;window=2026-06-30T00:21:08.306Z..2026-06-30T00:27:30.674Z`; `sampler:network_sampler_network-node7-0.log:2939-2940;window=2026-06-30T00:21:08.306Z..2026-06-30T00:27:30.674Z` |
| Passive TCP/window evidence, iteration 2 | present | Candidate learner/teacher socket samples overlap exact receiver window `2026-06-30T00:27:37.090Z..00:29:33.338Z` for peer pod `network-node4-0`; representative rows show non-trivial queues and `rwnd_limited`. | `sampler:network_sampler_network-node1-0.log:2773-3500;peer=network-node4-0;window=2026-06-30T00:27:37.090Z..2026-06-30T00:29:33.338Z`; `sampler:network_sampler_network-node4-0.log:5139-5866;window=2026-06-30T00:27:37.090Z..2026-06-30T00:29:33.338Z`; `sampler:network_sampler_network-node1-0.log:2847-2848;window=2026-06-30T00:27:37.090Z..2026-06-30T00:29:33.338Z`; `sampler:network_sampler_network-node4-0.log:5217-5218;window=2026-06-30T00:27:37.090Z..2026-06-30T00:29:33.338Z` |
| Passive TCP/window evidence, iteration 3 | present | Candidate learner/teacher socket samples overlap exact receiver window `2026-06-30T00:29:43.231Z..00:30:43.868Z` for peer pod `network-node3-0`; representative rows show non-trivial queues and `rwnd_limited`. | `sampler:network_sampler_network-node1-0.log:3571-3948;peer=network-node3-0;window=2026-06-30T00:29:43.231Z..2026-06-30T00:30:43.868Z`; `sampler:network_sampler_network-node3-0.log:5951-6328;window=2026-06-30T00:29:43.231Z..2026-06-30T00:30:43.868Z`; `sampler:network_sampler_network-node1-0.log:3623-3624;window=2026-06-30T00:29:43.231Z..2026-06-30T00:30:43.868Z`; `sampler:network_sampler_network-node3-0.log:6005-6006;window=2026-06-30T00:29:43.231Z..2026-06-30T00:30:43.868Z` |
| Passive attribution caveat | ambiguous | Socket endpoint attribution is strong enough for protocol acceptance by peer pod and exact reconnect window, but samples are connection-level on long-lived sockets, not frame-level reconnect-only telemetry. | [Passive TCP/window evidence, iteration 1](#network-evidence); [Passive TCP/window evidence, iteration 2](#network-evidence); [Passive TCP/window evidence, iteration 3](#network-evidence) |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 97.5M accounts, 1000 tokens, 97.5M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer workload for `PT6H`. | `log:client.log:1-9` |
| Client network targets | present | Seven NLG targets on port `50211`. | `log:client.log:10-16` |
| Transaction mix during reconnect | present | Crypto transfers around `5k TPS`, NFT transfers around `3k TPS`, messages around `2k TPS`, swaps around `312-313 TPS`, and contract crypto transfers around `75 TPS` are visible around the reconnect interval. | `log:client.log:4997-5003`; `log:client.log:5023`; `log:client.log:5062`; `log:client.log:5123` |
| Actual transaction-rate samples | present | From `00:21:19` through `00:30:45`, aggregate current TPS stays about `10.1k-10.6k`; samples continue after `ACTIVE` at `00:33:41..00:33:45` and later. | `log:client.log:4998-5003`; `log:client.log:5162-5175`; `log:client.log:5228-5233`; `log:client.log:5438` |
| Load continuity | present | Client load samples exist before episode start, during the exact episode, straddling the final receiver finish at `00:30:43.868`, and after `ACTIVE`. | `log:client.log:4993-5003`; `log:client.log:5174-5177`; `log:client.log:5228-5233`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:356,458` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Iteration 1 state-size gap | derived | Learner start range size `294,597,233`; teacher target range size `304,720,427`; derived gap `10,123,194` leaves. | `derived:formula=304720427-294597233;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:164,168/log:podlog_solo-mdlt-n10/network-node7_logs/swirlds.log:695-698` |
| Iteration 2 state-size gap | derived | Prior target `304,720,427`; next target `305,935,865`; derived gap `1,215,438` leaves. | `derived:formula=305935865-304720427;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:234,246/log:podlog_solo-mdlt-n10/network-node4_logs/swirlds.log:696-699` |
| Iteration 3 state-size gap | derived | Prior target `305,935,865`; next target `306,331,790`; derived gap `395,925` leaves. | `derived:formula=306331790-305935865;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:326,338/log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:695-698` |
| Learner stats state/store snapshots | present | Learner `vmap_size_state`: `294,597,233`, `304,720,427`, `305,935,865`, `306,331,790`, and nearest `ACTIVE` sample `307,030,941`; `ds_files_totalSizeMb_state` rises from `57,385` to `63,216`. | `csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:rows=1348,1473,1478,1513,1520,1537,1594;columns=time,vmap_size_state,ds_files_totalSizeMb_state,ds_offheap_dataSourceMb_state` |
| Flush/store divergence context | present | Learner `swirlds-vmap.log` flush totals show large updated/deleted leaf counts during the first reconnect and smaller follow-up deltas. Raw clean/dirty reconnect counters are present in learner `swirlds.log`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds-vmap.log:14986`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds-vmap.log:31924`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds-vmap.log:32084`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds-vmap.log:36502`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds-vmap.log:36651`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds-vmap.log:37039`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:183,261,353` |
| Divergence shape | derived | Growth-heavy multi-iteration reconnect. State-size gaps, data MB, and dirty counters drop across iterations. | `derived:classify_from_state_gap_and_clean_dirty_counters;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:164-186,234-264,326-356` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:356`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:458` |
| Iteration count | derived | `3` learner receiver reconnect iterations were observed before `ACTIVE`. | `derived:count_receiver_starts_before_ACTIVE;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:161,231,323,458` |
| Complete catch-up start | present | `2026-06-30 00:21:08.306` UTC, first exact receiver start. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:161` |
| Complete catch-up end | present | `2026-06-30 00:30:43.868` UTC, final receiver finish before `ACTIVE`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:356`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:458` |
| Complete catch-up duration | derived | `575.562 s`. | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:161,356` |
| Active confirmation | present | `2026-06-30 00:33:36.552` UTC; post-finish to `ACTIVE` is `172.684 s` and is not included in complete catch-up duration. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:458`; `derived:post_finish_to_ACTIVE=2026-06-30T00:33:36.552Z-2026-06-30T00:30:43.868Z` |
| Additional iterations observed | present | Yes. The complete episode has three receiver iterations before `ACTIVE`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:161`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:231`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:323`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:458` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTopToBottom` | [Run Context](#run-context) |
| Manifest batch | present | `2026-06-30-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration` |
| Manifest run | present | `top-to-bottom` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration;run=top-to-bottom` |
| Commit | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | [Run Context](#run-context) |
| Network disease preflight | derived | pass; missing-parent evidence exists, but no fatal `ACTIVE -> CHECKING` corroboration was found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | [Run Context](#run-context) |
| Teacher node | present | First sampled teacher node `6`; later teacher nodes `3` and `2`. | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect start UTC | present | `2026-06-30 00:21:08.306` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect end UTC | present | `2026-06-30 00:27:30.674` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| Learner duration | derived | First iteration wall `382.368 s`; first iteration synchronization `374.629 s`; complete catch-up duration `575.562 s`. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Teacher reconnect context present | present | yes | [Teacher Evidence](#teacher-evidence) |
| Reconnect stats present | present | yes | [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Teacher/learner state size present | present | yes | [State And Divergence Evidence](#state-and-divergence-evidence) |
| Workload profile present | present | yes | [Workload Evidence](#workload-evidence) |
| RTT evidence present | present | yes, via CSV stats and passive socket RTT fields. | [Network Evidence](#network-evidence) |
| Bandwidth evidence present | present | yes, via learner data/time, CSV send-rate, and passive socket context; not link capacity. | [Network Evidence](#network-evidence) |
| TCP/window evidence present | present | yes, passive sampler fields overlap all three exact reconnect iterations. | [Network Evidence](#network-evidence) |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `3` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-06-30 00:21:08.306` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-06-30 00:30:43.868` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `575.562 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-06-30 00:33:36.552` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | yes | `derived:protocol_acceptance;inputs=[Run Context](#run-context),[Network Disease Preflight](#network-disease-preflight),[Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations),[Reconnect Work-Shape Counters](#reconnect-work-shape-counters),[State And Divergence Evidence](#state-and-divergence-evidence),[Workload Evidence](#workload-evidence),[Network Evidence](#network-evidence)` |
| Reason if not accepted | not_applicable | Accepted; no rejection reason. | [Acceptance Notes](#acceptance-notes) |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Calibration acceptance | derived | Accepted for calibration: no fatal preflight disease, confirmed mode/learner, complete catch-up through `ACTIVE`, exact learner receiver payload timing, raw `ReconnectMapMetrics` work-shape counters, workload continuity, and RTT/bandwidth/TCP-window context are present. | [Analysis Output Per Mode](#analysis-output-per-mode) |
| Multiple iterations | present | The complete catch-up episode includes three receiver iterations. Trend/ranking should use complete catch-up duration, not only first-iteration duration. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Independent-run interpretation | present | This cluster run is an independent live-state calibration anchor; do not use it alone for causal traversal-mode ranking. | [State And Divergence Evidence](#state-and-divergence-evidence); `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Passive socket attribution | ambiguous | per-node network sampler logs for node1/node3/node4/node7 | learner-teacher socket tuples during exact receiver windows | Sockets are linkable by endpoint and exact receiver window, but samples are connection-level on long-lived sockets rather than frame-level reconnect-only telemetry. |
| Workflow controls | missing | Run root inventory, `version_run.txt`, `client.log`, pod logs | `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s` | Workflow-control files are absent. |
| Baseline/restored-state upload | missing | Run root inventory, `version_run.txt`, `client.log`, pod logs | `baseline`, `restore`, `restored-state`, `upload`, `state upload` | No baseline/restored-state upload evidence found; treat run as independent live-state workflow evidence. |
