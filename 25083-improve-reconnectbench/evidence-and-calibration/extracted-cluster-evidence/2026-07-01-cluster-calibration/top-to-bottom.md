# Top-To-Bottom Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/01-07-2026/dallas10_pullTopToBottom/report`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | derived | No fatal network disease found. No post-startup `ACTIVE -> CHECKING` evidence and no `Shadowgraph: Missing non-expired other parent` evidence were found in the seven run-local node logs. | `derived:network_disease_preflight;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:447-450;scope=podlog_solo-mdlt-n10/network-node1_logs..network-node7_logs;patterns=ACTIVE->CHECKING,Shadowgraph: Missing non-expired other parent` |
| Files searched | present | Plain `swirlds.log` files for `network-node1_logs` through `network-node7_logs` were present and searched. | `derived:file_inventory;scope=podlog_solo-mdlt-n10/network-node*_logs/swirlds.log;expected_nodes=1..7` |
| Startup/status context | present | Learner later reaches `CHECKING -> ACTIVE` at `2026-07-01 00:41:18.024`; this is completion context and not fatal preflight evidence. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:447-450` |
| Extraction disposition | present | Normal extraction is allowed. Learner plain `swirlds.log`, stats CSVs, client log, settings, and passive sampler files are present. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162-187`; `csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:rows=903-1226`; `sampler:network_sampler_network-node1-0.log:373-5598` |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTopToBottom`; `version_run.txt` and all seven copied `settingsUsed.txt` files agree. | `config:version_run.txt:key=inputs.AddSettings;line=8`; `derived:scan_all_nodes;inputs=config:podlog_solo-mdlt-n10/network-node*_logs/config/settingsUsed.txt:line=725;expected=pullTopToBottom` |
| Namespace | present | `Dallas10` | `config:version_run.txt:key=namespace;line=1` |
| Commit hash | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=inputs.hederaversion;line=2`; `config:version_run.txt:key=hederahash;line=11` |
| Solo/chart version | present | `latest_tested_solo-charts0.59_balanced` | `config:version_run.txt:key=inputs.soloversion;line=3` |
| Run number / job URL | present | run `307`; workflow URL `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/28468562152`. | `config:version_run.txt:key=JOB_URL;line=10`; `config:version_run.txt:key=run_number;line=12` |
| NLG controls | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `97,500,000` accounts, `6h`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`; `config:version_run.txt:key=inputs.NLGDparams;line=4`; `config:version_run.txt:key=inputs.NLG_Accounts;line=5`; `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Network size | present | Seven configured network nodes. | `config:podlog_solo-mdlt-n10/network-node1_logs/config/.archive/genesis-network.json:1`; `derived:count_configured_nodes=7` |
| Learner node and pod | present | Learner is node ID `0`, log directory `network-node1_logs`; stopped/learner pod is inferred as `network-node1-0`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:158`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162`; `derived:atlas_node_log_mapping;inputs=podlog_solo-mdlt-n10/network-node1_logs` |
| Workflow controls | missing | No workflow-control source was found for warmtime, downtime, loop count, or `profileReconnectLoopK8s`. | files checked: `version_run.txt`, `client.log`, pod logs, run root inventory; patterns checked: `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s`; reason: no matches |
| Baseline/restored state upload | missing | No baseline, restore, upload, or state-upload evidence was found. | files checked: run root inventory, `version_run.txt`, `client.log`, pod logs; patterns checked: `baseline`, `restore`, `restored-state`, `restored state`, `upload`, `state upload`; reason: no matches |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` became the receiver learner before the first reconnect start. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:158`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162` |
| Iteration 1 receiver window | present | Learner received from peer node `2`, `2026-07-01 00:25:06.448..00:33:53.480`; wall duration `527.032 s`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:187`; `derived:duration=2026-07-01T00:33:53.480Z-2026-07-01T00:25:06.448Z` |
| Iteration 1 teacher window | present | Teacher node `2` (`network-node3_logs`) sent to learner node `0`, `2026-07-01 00:25:14.661..00:33:53.479`. | `log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:835-900` |
| Iteration 2 receiver window | present | Learner received from peer node `5`, `2026-07-01 00:34:01.416..00:36:29.869`; wall duration `148.453 s`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:234`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:267`; `derived:duration=2026-07-01T00:36:29.869Z-2026-07-01T00:34:01.416Z` |
| Iteration 2 teacher window | present | Teacher node `5` (`network-node6_logs`) sent to learner node `0`, `2026-07-01 00:34:09.292..00:36:29.873`. | `log:podlog_solo-mdlt-n10/network-node6_logs/swirlds.log:859-896` |
| Iteration 3 receiver window | present | Learner received from peer node `2`, `2026-07-01 00:36:40.373..00:37:52.970`; wall duration `72.597 s`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:326`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:359`; `derived:duration=2026-07-01T00:37:52.970Z-2026-07-01T00:36:40.373Z` |
| Iteration 3 teacher window | present | Teacher node `2` (`network-node3_logs`) sent to learner node `0`, `2026-07-01 00:36:47.851..00:37:52.968`. | `log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:903-938` |
| Learner status after final receiver finish | present | Learner reached `ACTIVE` at `2026-07-01 00:41:18.024` after final receiver reconnect finish. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:359`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:445-450` |
| Later fall-behind scan | present | No later learner fall-behind was found through learner log EOF line `480`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:450-480` |

## Learner Evidence

| Iteration | Status | Extracted value or observation | Source references |
|---:|---:|---|---|
| 1 | present | Learner path range `[294610461,589220922]` size `294,610,462` to target `[320220708,640441416]` size `320,220,709`; state-size gap `25,610,247`; synchronization `518.809 s`; data `15149.827708244324 MB`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162-187`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:165-170` |
| 2 | present | Learner path range `[320220708,640441416]` size `320,220,709` to target `[321777438,643554876]` size `321,777,439`; state-size gap `1,556,730`; synchronization `140.576 s`; data `2209.731840133667 MB`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:234-267`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:237-250` |
| 3 | present | Learner path range `[321777438,643554876]` size `321,777,439` to target `[322243785,644487570]` size `322,243,786`; state-size gap `466,347`; synchronization `65.114 s`; data `846.6726694107056 MB`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:326-359`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:329-342` |
| Stage timing | present | Learner status sequence includes repeated receiver reconnects before final `CHECKING -> ACTIVE`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:206`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:284`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:376`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:445-450` |

## Teacher Evidence

| Iteration | Teacher | Status | Extracted value or observation | Source references |
|---:|---:|---:|---|---|
| 1 | node `2` | present | Sent-state metadata/root response report `[320220708,640441416]`; derived sent/root size `320,220,709` leaves. | `log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:835-900`; `derived:formula=640441416-320220708+1;inputs=log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:836-851` |
| 2 | node `5` | present | Sent-state metadata/root response report `[321777438,643554876]`; derived sent/root size `321,777,439` leaves. | `log:podlog_solo-mdlt-n10/network-node6_logs/swirlds.log:859-896`; `derived:formula=643554876-321777438+1;inputs=log:podlog_solo-mdlt-n10/network-node6_logs/swirlds.log:860-875` |
| 3 | node `2` | present | Sent-state metadata/root response report `[322243785,644487570]`; derived sent/root size `322,243,786` leaves. | `log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:903-938`; `derived:formula=644487570-322243785+1;inputs=log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:904-919` |
| Teacher sampled growth | present | Teacher `vmap_size_state` grew during sampled reconnect windows: iter1 node2 `+1,533,382`, iter2 node5 `+436,348`, iter3 node2 `+208,917`. | `csv:podlog_solo-mdlt-n10/network-node3_logs/stats/MainNetStats2.csv:columns=vmap_size_state;rows=6878-7053`; `csv:podlog_solo-mdlt-n10/network-node6_logs/stats/MainNetStats5.csv:columns=vmap_size_state;rows=7056-7106`; `csv:podlog_solo-mdlt-n10/network-node3_logs/stats/MainNetStats2.csv:columns=vmap_size_state;rows=7109-7133` |

## Reconnect Work-Shape Counters

| Iteration | transfersFromTeacher | transfersFromLearner | internalHashes | internalCleanHashes | internalData | internalCleanData | leafHashes | leafCleanHashes | leafData | leafCleanData | Source references |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 307,027,362 | 300,732,500 | 70,187,347 | 19,679,634 | 70,277,594 | 19,670,459 | 230,026,704 | 118,682,109 | 237,693,337 | 119,720,276 | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:184` |
| 2 | 83,475,144 | 83,129,550 | 47,668,216 | 34,567,338 | 47,660,036 | 34,502,283 | 35,521,840 | 26,353,988 | 36,566,815 | 26,686,460 | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:264` |
| 3 | 34,755,590 | 35,121,404 | 23,621,563 | 18,495,598 | 23,610,136 | 18,431,306 | 11,560,627 | 8,636,837 | 11,749,990 | 8,690,679 | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:356` |

| Iteration | internalDirtyHashes | internalDirtyData | leafDirtyHashes | leafDirtyData | Source references |
|---:|---:|---:|---:|---:|---|
| 1 | 50,507,713 | 50,607,135 | 111,344,595 | 117,973,061 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:184` |
| 2 | 13,100,878 | 13,157,753 | 9,167,852 | 9,880,355 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:264` |
| 3 | 5,125,965 | 5,178,830 | 2,923,790 | 3,059,311 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:356` |

| Aggregate counter | Status | Value | Source references |
|---|---:|---:|---|
| transfersFromTeacher | derived | 425,258,096 | `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:184,264,356` |
| leafCleanData | derived | 155,097,415 | `derived:sum leafCleanData over log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:184,264,356` |
| leafDirtyData | derived | 130,912,727 | `derived:formula=sum(leafData-leafCleanData);inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:184,264,356` |

## Network Evidence

Observed CSV send-rate and passive `ss -tin` rate fields are socket behavior/context, not link-capacity evidence.

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner data lower-bound throughput | derived | Reconnect log data/time gives lower-bound receive rates: iter1 `29.20 MB/s`; iter2 `15.72 MB/s`; iter3 `13.00 MB/s`. | `derived:formula=dataMegabytes/synchronizationSeconds;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:185-186,265-266,357-358` |
| CSV RTT and send-rate, iteration 1 | present | Learner node0->node2 `ping_us_02` mean/max `521.60/521.60 us`, mean/max `bytes_per_sec_sent_02` `37,000,820.37/48,276,402.18 B/s`; teacher node2->node0 `ping_us_00` `585.96 us`, send-rate `29,367,279.21/61,446,913.49 B/s`. | `csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_02,bytes_per_sec_sent_02;rows=903-1078`; `csv:podlog_solo-mdlt-n10/network-node3_logs/stats/MainNetStats2.csv:columns=ping_us_00,bytes_per_sec_sent_00;rows=6878-7053` |
| CSV RTT and send-rate, iteration 2 | present | Learner node0->node5 `ping_us_05` `137.14 us`, send-rate `32,392,389.08/44,934,255.71 B/s`; teacher node5->node0 `ping_us_00` `160.92 us`, send-rate `12,901,323.96/26,786,132.71 B/s`. | `csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_05,bytes_per_sec_sent_05;rows=1081-1130`; `csv:podlog_solo-mdlt-n10/network-node6_logs/stats/MainNetStats5.csv:columns=ping_us_00,bytes_per_sec_sent_00;rows=7057-7105` |
| CSV RTT and send-rate, iteration 3 | present | Learner node0->node2 `ping_us_02` `117.62 us`, send-rate `23,255,609.22/40,199,189.04 B/s`; teacher node2->node0 `ping_us_00` `145.02 us`, send-rate `8,682,626.24/19,091,436.76 B/s`. | `csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_02,bytes_per_sec_sent_02;rows=1134-1157`; `csv:podlog_solo-mdlt-n10/network-node3_logs/stats/MainNetStats2.csv:columns=ping_us_00,bytes_per_sec_sent_00;rows=7110-7133` |
| Passive sampler inventory | present | Per-node samplers exist for nodes 1 through 7 and cover `2026-07-01T00:18:56/58Z..00:41:27Z`, bounding all three receiver windows. The top-level `reconnect_network_samples_1.log` contains only a sampler stop marker. | `sampler:network_sampler_network-node1-0.log:1-6470`; `sampler:network_sampler_network-node2-0.log:1-8300`; `sampler:network_sampler_network-node3-0.log:1-8300`; `sampler:network_sampler_network-node4-0.log:1-8300`; `sampler:network_sampler_network-node5-0.log:1-8300`; `sampler:network_sampler_network-node6-0.log:1-8300`; `sampler:network_sampler_network-node7-0.log:1-8300`; `sampler:reconnect_network_samples_1.log:1` |
| Passive TCP/window evidence, iteration 1 | present | Learner/teacher socket samples overlap the exact receiver window for peer pod `network-node3-0`; representative rows show queueing and `rwnd_limited` behavior, including learner early `Recv-Q=1,266,542`, `Send-Q=592,544`, `rwnd_limited=86.3%`, and later finish with queues drained. | `sampler:network_sampler_network-node1-0.log:373-3874;peer=network-node3-0;window=2026-07-01T00:25:06.448Z..2026-07-01T00:33:53.480Z`; `sampler:network_sampler_network-node3-0.log:2299-5702;window=2026-07-01T00:25:06.448Z..2026-07-01T00:33:53.480Z`; `sampler:network_sampler_network-node1-0.log:471-472`; `sampler:network_sampler_network-node1-0.log:3565-3566`; `sampler:network_sampler_network-node1-0.log:3873-3874` |
| Passive TCP/window evidence, iteration 2 | present | Learner/teacher socket samples overlap the exact receiver window for peer pod `network-node6-0`; representative rows show `Recv-Q=2,050,217`, `Send-Q=1,102,936`, `notsent=1,102,936`, `snd_wnd=1024`, and `rwnd_limited=3.4%` near the max queued sample. | `sampler:network_sampler_network-node1-0.log:3935-4999;peer=network-node6-0;window=2026-07-01T00:34:01.416Z..2026-07-01T00:36:29.869Z`; `sampler:network_sampler_network-node6-0.log:5813-6822;window=2026-07-01T00:34:01.416Z..2026-07-01T00:36:29.869Z`; `sampler:network_sampler_network-node1-0.log:3935-3936`; `sampler:network_sampler_network-node1-0.log:4985-4986`; `sampler:network_sampler_network-node1-0.log:4999-5000` |
| Passive TCP/window evidence, iteration 3 | ambiguous | Node1/node3 socket samples overlap the iteration 3 receiver/teacher windows, but the fresh socket candidate `10.36.31.166:46264 -> 10.36.17.76:50111` first appears after receiver finish. Treat iteration 3 passive attribution as connection-level context, not fresh reconnect-only proof. | `sampler:network_sampler_network-node1-0.log:5121-5598;peer=network-node3-0;window=2026-07-01T00:36:40.373Z..2026-07-01T00:37:52.970Z`; `sampler:network_sampler_network-node3-0.log:6947-7424;window=2026-07-01T00:36:40.373Z..2026-07-01T00:37:52.970Z`; `sampler:network_sampler_network-node1-0.log:5649-5664`; `sampler:network_sampler_network-node3-0.log:7483-7498` |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 97.5M accounts, 1000 tokens, 97.5M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer workload for `PT6H`. | `log:client.log:1-9` |
| Client network targets | present | Seven NLG targets on port `50211`. | `log:client.log:10-16` |
| Transaction mix | present | Crypto transfers, NFT transfers, topic/message work, swaps, and smart-contract crypto-transfer work are visible in reconnect-window samples. | `log:client.log:6600-6601`; `log:client.log:6787-6789`; `log:client.log:6843-6845`; `log:client.log:6910-6911` |
| Actual transaction-rate samples | present | Aggregate current TPS around reconnect and `ACTIVE` stays near `10.3k` in sampled windows. | `log:client.log:6596-6638`; `log:client.log:6776-6862`; `log:client.log:6894-6929`; `log:client.log:7042-7128` |
| Load continuity | present | Client load samples exist before the first receiver start, throughout the catch-up interval, around final receiver finish, and after learner `ACTIVE`. | `log:client.log:6596-6638`; `log:client.log:6776-6862`; `log:client.log:6894-6929`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162,359,450` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Iteration 1 state-size gap | derived | Learner start size `294,610,462`; teacher target size `320,220,709`; derived gap `25,610,247` leaves. | `derived:formula=320220709-294610462;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:165-170/log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:836-851` |
| Iteration 2 state-size gap | derived | Learner start size `320,220,709`; teacher target size `321,777,439`; derived gap `1,556,730` leaves. | `derived:formula=321777439-320220709;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:237-250/log:podlog_solo-mdlt-n10/network-node6_logs/swirlds.log:860-875` |
| Iteration 3 state-size gap | derived | Learner start size `321,777,439`; teacher target size `322,243,786`; derived gap `466,347` leaves. | `derived:formula=322243786-321777439;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:329-342/log:podlog_solo-mdlt-n10/network-node3_logs/swirlds.log:904-919` |
| Learner stats state/store snapshots | present | Learner `vmap_size_state`: `294,610,462` near iter1 start, `320,220,709` at iter2 start, `321,777,439` at iter3 start, `322,243,786` after iter3 finish, and `323,009,325` near `ACTIVE`. | `csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:rows=903,1081,1134,1158,1226;columns=time,vmap_size_state,accountsUsed,nftsUsed,tokenAssociationsUsed,ds_files_totalSizeMb_state,ds_offheap_dataSourceMb_state` |
| Divergence shape | derived | Mixed modify-heavy plus append/growth-heavy, remove-light. The state-size gaps are positive, workload is active, and sampled store counters show `updated` much larger than `added` while `removed` is tiny; reconnect clean/dirty counters and path shifts indicate mixed work rather than pure append-only growth. | `derived:classify_from_state_gap_workload_and_store_counters;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:184,264,356/csv:podlog_solo-mdlt-n10/network-node1_logs/stats/MainNetStats0.csv:rows=903,1226/log:client.log:6596-6929` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:359`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:450` |
| Iteration count | derived | `3` learner receiver reconnect iterations before `ACTIVE`. | `derived:count_receiver_starts_before_ACTIVE;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162,234,326,450` |
| Complete catch-up start | present | `2026-07-01 00:25:06.448` UTC. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162` |
| Complete catch-up end | present | `2026-07-01 00:37:52.970` UTC, final learner receiver finish before `ACTIVE`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:359`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:450` |
| Complete catch-up duration | derived | `766.522 s`. | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162,359` |
| Active confirmation | present | `2026-07-01 00:41:18.024` UTC; post-finish to `ACTIVE` is `205.054 s` and is not included in complete catch-up duration. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:450`; `derived:post_finish_to_ACTIVE=2026-07-01T00:41:18.024Z-2026-07-01T00:37:52.970Z` |
| Additional iterations observed | present | Yes. The complete episode has three receiver iterations before `ACTIVE`. | `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:162`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:234`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:326`; `log:podlog_solo-mdlt-n10/network-node1_logs/swirlds.log:450` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTopToBottom` | [Run Context](#run-context) |
| Manifest batch | present | `2026-07-01-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration` |
| Manifest run | present | `top-to-bottom` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration;run=top-to-bottom` |
| Commit | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | [Run Context](#run-context) |
| Network disease preflight | derived | pass; no fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | [Run Context](#run-context) |
| Episode incomplete reason | not_applicable | Episode is complete. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Teacher node | present | First sampled teacher node `2`; later teacher nodes `5` and `2`. | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect start UTC | present | `2026-07-01 00:25:06.448` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect end UTC | present | `2026-07-01 00:33:53.480` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| Learner duration | derived | First iteration wall `527.032 s`; first iteration synchronization `518.809 s`; complete catch-up duration `766.522 s`. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Teacher reconnect context present | present | yes | [Teacher Evidence](#teacher-evidence) |
| Reconnect stats present | present | yes | [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Teacher/learner state size present | present | yes | [State And Divergence Evidence](#state-and-divergence-evidence) |
| Workload profile present | present | yes | [Workload Evidence](#workload-evidence) |
| RTT evidence present | present | yes, via CSV stats and passive socket RTT fields. | [Network Evidence](#network-evidence) |
| Bandwidth evidence present | present | yes, via learner data/time, CSV send-rate, and passive socket context; not link capacity. | [Network Evidence](#network-evidence) |
| TCP/window evidence present | ambiguous | yes for iterations 1 and 2, connection-level/ambiguous for iteration 3. | [Network Evidence](#network-evidence) |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `3` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-07-01 00:25:06.448` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-07-01 00:37:52.970` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `766.522 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-07-01 00:41:18.024` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | yes | `derived:protocol_acceptance;inputs=[Run Context](#run-context),[Network Disease Preflight](#network-disease-preflight),[Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations),[Reconnect Work-Shape Counters](#reconnect-work-shape-counters),[State And Divergence Evidence](#state-and-divergence-evidence),[Workload Evidence](#workload-evidence),[Network Evidence](#network-evidence)` |
| Reason if not accepted | not_applicable | Accepted; passive iteration 3 remains a calibration caveat, not a rejection reason. | [Acceptance Notes](#acceptance-notes) |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Calibration acceptance | derived | Accepted for calibration: no fatal preflight disease, confirmed mode/learner, complete catch-up through `ACTIVE`, exact learner receiver payload timing, raw `ReconnectMapMetrics` counters, workload continuity, and RTT/bandwidth/TCP-window context are present. | [Analysis Output Per Mode](#analysis-output-per-mode) |
| Passive attribution caveat | ambiguous | Iteration 3 passive socket attribution is connection-level and ambiguous for a fresh reconnect socket. Use it as a caveat when selecting `networkInflightBytesLimit`. | [Network Evidence](#network-evidence) |
| Multiple iterations | present | The complete catch-up episode includes three receiver iterations. Trend/ranking should use complete catch-up duration, not only first-iteration duration. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Independent-run interpretation | present | This cluster run is an independent live-state calibration anchor; do not use it alone for causal traversal-mode ranking. | [State And Divergence Evidence](#state-and-divergence-evidence); `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Passive socket attribution for iteration 3 | ambiguous | per-node sampler logs for node1 and node3 | learner-teacher socket tuples during exact iteration 3 receiver/teacher windows | Long-lived node1/node3 socket samples overlap the window, but the fresh socket candidate appears after receiver finish. |
| Workflow controls | missing | Run root inventory, `version_run.txt`, `client.log`, pod logs | `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s` | Workflow-control files are absent. |
| Baseline/restored-state upload | missing | Run root inventory, `version_run.txt`, `client.log`, pod logs | `baseline`, `restore`, `restored-state`, `upload`, `state upload` | No baseline/restored-state upload evidence found; treat run as independent live-state workflow evidence. |
