# Top-To-Bottom Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/29-06-2026/dallas11_pullTopToBottom/report`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | derived | No fatal network disease found. All seven node logs had `ACTIVE -> CHECKING` count `0`, missing-parent count `0`, and at least one `CHECKING -> ACTIVE` confirmation. | `derived:scan;inputs=log:podlog_solo-mdlt-n11/network-node*_logs/swirlds.log;patterns=oldStatus=ACTIVE,newStatus=CHECKING;Shadowgraph: Missing non-expired other parent` |
| Active confirmations | present | Learner node0 reached post-reconnect `CHECKING -> ACTIVE` at `2026-06-26 17:19:52.830`; peers reached startup `CHECKING -> ACTIVE` earlier. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:354`; `log:podlog_solo-mdlt-n11/network-node2_logs/swirlds.log:125`; `log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:125` |
| Extraction disposition | present | Normal extraction is valid. | [Analysis Output Per Mode](#analysis-output-per-mode) |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTopToBottom`; `version_run.txt` and all seven copied `settingsUsed.txt` files agree. | `config:version_run.txt:key=inputs.AddSettings;line=8`; `derived:scan_all_nodes;inputs=config:podlog_solo-mdlt-n11/network-node*_logs/config/settingsUsed.txt:line=725;expected=pullTopToBottom` |
| Namespace | present | `Dallas11` | `config:version_run.txt:key=namespace;line=1` |
| Commit hash | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=hederahash;line=11` |
| Run number / job URL | present | run `303`; workflow URL `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/28245940870` | `config:version_run.txt:key=run_number;line=12`; `config:version_run.txt:key=JOB_URL;line=10` |
| NLG controls | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `30000000` accounts, `6h`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`; `config:version_run.txt:key=inputs.NLGDparams;line=4`; `config:version_run.txt:key=inputs.NLG_Accounts;line=5`; `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Learner node and pod | present | Learner is node ID `0`, log directory `network-node1_logs`, pod `network-node1-0`, `POD_IP=10.36.38.121`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164`; `config:podlog_solo-mdlt-n11/network-node1_logs/config/settingsUsed.txt:948`; `config:podlog_solo-mdlt-n11/network-node1_logs/config/settingsUsed.txt:1143` |
| Client workload profile | present | 32 clients, 30M accounts, 1000 tokens, 30M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer duration `PT6H`. | `log:client.log:2-9` |
| Client network targets | present | Seven NLG targets on port `50211`. | `log:client.log:10-16` |
| Workflow-control logs | missing | No `performance-tests-start.log`, `performance-tests-watch.log`, or equivalent workflow-control filename was found under the run root. | `derived:search_no_matches;scope=run root;patterns=*performance-tests-start.log,*performance-tests-watch.log,*performance*,*workflow*,*watch*,*start*,*profileReconnectLoopK8s*,*reconnect*loop*` |
| Warmtime / downtime / loop count | missing | No `warmtime`, `downtime`, `NofLoops`, or `profileReconnectLoopK8s` controls were found in the available run-root files. | `derived:search_no_matches;inputs=config:version_run.txt,log:client.log,log:podlog_solo-mdlt-n11/error_summary*.txt,log:podlog_solo-mdlt-n11/network-node*_logs/support-zip.log,log:podlog_solo-mdlt-n11/network-node*_logs/journalctl.log;patterns=warmtime,downtime,NofLoops,profileReconnectLoopK8s` |
| Baseline/restored state upload | missing | No baseline, restore, upload, or state-upload evidence was found. | `derived:search_no_matches;inputs=config:version_run.txt,log:client.log,log:podlog_solo-mdlt-n11/error_summary*.txt,log:podlog_solo-mdlt-n11/network-node*_logs/support-zip.log,log:podlog_solo-mdlt-n11/network-node*_logs/journalctl.log;patterns=baseline,restored state,restored-state,state upload,upload,uploaded,restore` |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved `OBSERVING -> BEHIND` at `2026-06-26 17:12:54.387`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:160` |
| Iteration 1 receiver window | present | Learner node `0` received from teacher peer `4`, `2026-06-26 17:12:54.502..17:16:06.085`; wall-clock duration `191.583 s`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:189`; `derived:formula=end-start` |
| Iteration 1 teacher window | present | Teacher node `4` (`network-node5_logs`) sent to learner node `0`, `2026-06-26 17:12:56.954..17:16:06.086`. | `log:podlog_solo-mdlt-n11/network-node5_logs/swirlds.log:455`; `log:podlog_solo-mdlt-n11/network-node5_logs/swirlds.log:520` |
| Iteration 2 receiver window | present | Learner node `0` received from teacher peer `6`, `2026-06-26 17:16:13.434..17:17:05.885`; wall-clock duration `52.451 s`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:234`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:267`; `derived:formula=end-start` |
| Iteration 2 teacher window | present | Teacher node `6` (`network-node7_logs`) sent to learner node `0`, `2026-06-26 17:16:16.326..17:17:05.888`. | `log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:488`; `log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:523` |
| Learner status after final receiver finish | present | `BEHIND -> RECONNECT_COMPLETE -> CHECKING -> ACTIVE`; final `ACTIVE` at `2026-06-26 17:19:52.830`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:284`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:350`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:354` |

## Learner Evidence

| Iteration | Status | Extracted value or observation | Source references |
|---:|---:|---|---|
| 1 | present | Receiver synchronization time `189.124 s`, data `5269.814120292664 MB`; view range `[92086113,184172226]` (`92,086,114` leaves); new range `[100898113,201796226]` (`100,898,114` leaves); deleted leading nodes `8,812,000`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:167`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:171-177`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:186-202` |
| 2 | present | Receiver synchronization time `49.557 s`, data `876.587815284729 MB`; view range `[100898113,201796226]` (`100,898,114` leaves); new range `[101458070,202916140]` (`101,458,071` leaves); deleted leading nodes `559,957`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:245`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:249-255`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:264-280` |
| finalization | present | Iteration 1 and 2 each have learner completion and received-state metadata before the receiver finish payload. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:185-202`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:263-280` |

## Teacher Evidence

| Iteration | Teacher | Status | Extracted value or observation | Source references |
|---:|---:|---:|---|---|
| 1 | node `4` | present | Sent-state metadata and root response both report `[100898113,201796226]`; derived sent/root size `100,898,114` leaves. | `log:podlog_solo-mdlt-n11/network-node5_logs/swirlds.log:455-471` |
| 2 | node `6` | present | Sent-state metadata and root response both report `[101458070,202916140]`; derived sent/root size `101,458,071` leaves. | `log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:488-504` |
| matching | present | Teacher windows match learner peer IDs and receiver finish times for both iterations. | `log:podlog_solo-mdlt-n11/network-node5_logs/swirlds.log:455-520`; `log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:488-523` |

## Reconnect Work-Shape Counters

| Iteration | transfersFromTeacher | transfersFromLearner | internalHashes | internalCleanHashes | internalData | internalCleanData | leafHashes | leafCleanHashes | leafData | leafCleanData | Source references |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 107,633,139 | 106,347,032 | 16,672,080 | 2,275,864 | 16,658,682 | 2,276,019 | 88,335,809 | 49,536,859 | 91,081,034 | 50,132,805 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:186` |
| 2 | 32,369,092 | 32,202,963 | 15,143,920 | 10,433,733 | 15,144,412 | 10,431,158 | 16,826,231 | 13,387,482 | 17,338,595 | 13,571,712 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:264` |

| Iteration | internalDirtyHashes | internalDirtyData | leafDirtyHashes | leafDirtyData | Source references |
|---:|---:|---:|---:|---:|---|
| 1 | 14,396,216 | 14,382,663 | 38,798,950 | 40,948,229 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:186` |
| 2 | 4,710,187 | 4,713,254 | 3,438,749 | 3,766,883 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:264` |

## Network Evidence

Observed `send` and `delivery_rate` values from `ss -tin` sampler logs are socket behavior during samples, not link capacity.

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner data lower-bound throughput, iteration 1 | derived | `5269.814120292664 MB / 189.124 s = 27.864 MB/s` from learner log payloads. | `derived:formula=dataMegabytes/timeInSeconds;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:186-187` |
| Learner data lower-bound throughput, iteration 2 | derived | `876.587815284729 MB / 49.557 s = 17.688 MB/s` from learner log payloads. | `derived:formula=dataMegabytes/timeInSeconds;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:264-265` |
| Stats RTT and send-rate, iteration 1 | present | CSV rows over iter1 show node0->node4 `ping_us_04=388`, mean `bytes_per_sec_sent_04=34,236,038 B/s`; node4->node0 `ping_us_00=610`, mean `bytes_per_sec_sent_00=26,105,194 B/s`. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:column=ping_us_04,bytes_per_sec_sent_04;rows=1271-1334`; `csv:podlog_solo-mdlt-n11/network-node5_logs/stats/MainNetStats4.csv:column=ping_us_00,bytes_per_sec_sent_00;rows=3150-3213` |
| Stats RTT and send-rate, iteration 2 | present | CSV rows over iter2 show node0->node6 `ping_us_06=154`, mean `bytes_per_sec_sent_06=28,954,254 B/s`; node6->node0 `ping_us_00=146`, mean `bytes_per_sec_sent_00=11,694,974 B/s`. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:column=ping_us_06,bytes_per_sec_sent_06;rows=1337-1353`; `csv:podlog_solo-mdlt-n11/network-node7_logs/stats/MainNetStats6.csv:column=ping_us_00,bytes_per_sec_sent_00;rows=3216-3233` |
| Passive sampler inventory | present | Per-node sampler logs cover `2026-06-26T17:05:29Z..17:19:58Z`; top-level `reconnect_network_samples_1.log` has only one `awk: close failed` line and no timestamped samples. | `sampler:network_sampler_network-node1-0.log:1`; `sampler:network_sampler_network-node1-0.log:3345`; `sampler:network_sampler_network-node7-0.log:5505`; `sampler:reconnect_network_samples_1.log:1` |
| Passive TCP/window evidence, iteration 1 | present | Attributed socket pair `10.36.38.121:53002 <-> 10.36.39.143:50111`; 95 samples per side. Learner side max `Recv-Q=1,652,827`, max `Send-Q/notsent=1,297,192`, min `snd_wnd=1024`, max `rwnd_limited=89.6%`, max `rtt=1.76 ms`, max `cwnd=149`, max `bytes_retrans=298,286`. Teacher side max `rwnd_limited=50.6%`, max `rtt=5.537 ms`, max `cwnd=113`, max `bytes_retrans=84,720`. | `sampler:network_sampler_network-node1-0.log:489-496,531-538,769-776,1357-1364,1651-1658,1721-1728;window=2026-06-26T17:12:54Z..2026-06-26T17:16:06Z`; `sampler:network_sampler_network-node5-0.log:2635-2644,3797-3806,3839-3848,3881-3890,3895-3904;window=2026-06-26T17:12:54Z..2026-06-26T17:16:06Z` |
| Passive TCP/window evidence, iteration 2 | present | Attributed bounded traffic on pre-existing socket pair `10.36.38.121:36872 <-> 10.36.36.81:50111`; 26 samples per side. Learner side max `Recv-Q=14,651,906`, max `Send-Q/notsent=866,560`, min `snd_wnd=1024`, max `rtt=2.297 ms`, max `bytes_retrans=165,828`. Teacher side max `Recv-Q=2,968,131`, max `Send-Q/notsent=1,725,936`, max `rtt=1.957 ms`, max `bytes_retrans=130,368`. | `sampler:network_sampler_network-node1-0.log:1805-1816,2085-2096,2127-2138,2155-2166;window=2026-06-26T17:16:13Z..2026-06-26T17:17:06Z`; `sampler:network_sampler_network-node7-0.log:3965-3968,4091-4094,4315-4318;window=2026-06-26T17:16:13Z..2026-06-26T17:17:06Z` |
| Passive attribution caveat | ambiguous | Iteration 1 maps cleanly. Iteration 2 uses a pre-existing node0/node6 socket during the receiver window; a new node0/node6 socket appears only after the receiver finish and is not used as reconnect-window evidence. | `sampler:network_sampler_network-node1-0.log:2197-2208;window=2026-06-26T17:16:13Z..2026-06-26T17:17:06Z`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:267` |
| `ping_us_*MIN` columns | ambiguous | `ping_us_*MIN` columns exist, but reconnect-window rows are dominated by sentinel-like `9999999`; plain `ping_us_*` columns are used as CSV RTT evidence. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:column=ping_us_04MIN;rows=1271-1334`; `csv:podlog_solo-mdlt-n11/network-node7_logs/stats/MainNetStats6.csv:column=ping_us_00MIN;rows=3216-3233` |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 30M accounts, 1000 tokens, 30M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer workload for `PT6H`. | `log:client.log:2-9` |
| Transaction mix during reconnect | present | Aggregate transactions/receipts plus crypto transfers, NFT transfers, messages, and contract swaps are visible around the reconnect interval. | `log:client.log:2335-2357`; `log:client.log:2362-2366`; `log:client.log:2383-2384`; `log:client.log:2398`; `log:client.log:2428`; `log:client.log:2431-2434` |
| Actual transaction-rate samples | present | Start window: transactions `69.8M`, `TPS(current)=10336`, receipts `69.7M`, `TPS(current)=10385`; mid-window: transactions `71.3M`, `TPS(current)=10387`, receipts `71.2M`, `TPS(current)=10406`; final-finish window: transactions `72.3M`, `TPS(current)=10389`, receipts `72.2M`, `TPS(current)=10387`. | `log:client.log:2358-2359`; `log:client.log:2403-2404`; `log:client.log:2432-2433` |
| Load continuity | present | Client load continues from the first receiver start through the second receiver finish and past learner `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:267`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:354`; `log:client.log:2357-2434`; `log:client.log:2449-2487` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Iteration 1 learner start size | derived | `92,086,114` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:167` |
| Iteration 1 teacher/target size | derived | `100,898,114` leaves; target equals learner received-state metadata. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n11/network-node5_logs/swirlds.log:455-471,log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:190-202` |
| Iteration 1 state-size gap | derived | `8,812,000` leaves between teacher target and learner start. | `derived:formula=100898114-92086114;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:167,log:podlog_solo-mdlt-n11/network-node5_logs/swirlds.log:455-471` |
| Iteration 2 target size | derived | `101,458,071` leaves; second-iteration gap from previous target is `559,957` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:245-255,log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:488-504` |
| Learner stats state/store snapshots | present | Learner `vmap_size_state`: `92,086,114` before episode, `100,898,114` at final-finish sample, `101,458,071` just after final finish, `102,059,840` after `ACTIVE`. Stable service counts: `accountsUsed=30000712`, `contractsUsed=6`, `nftsUsed=30000000`, `tokensUsed=1000`, `topicsUsed=100000`; `tokenAssociationsUsed` rises to `10,723,618` after `ACTIVE`. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:columns=vmap_size_state,accountsUsed,contractsUsed,nftsUsed,tokenAssociationsUsed,tokensUsed,topicsUsed;rows=1270,1353,1354,1410` |
| Divergence shape | derived | Growth-heavy, multi-iteration reconnect: first gap is large, second gap is much smaller; raw counters show substantial dirty leaf data in both iterations. | `derived:classify_from_state_gap_and_clean_dirty_counters;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:186,log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:264` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:267`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:354` |
| Iteration count | derived | `2` learner receiver reconnect iterations before `ACTIVE`. | `derived:count_receiver_starts_before_ACTIVE;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164,234,354` |
| Complete catch-up start | present | `2026-06-26 17:12:54.502` UTC. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164` |
| Complete catch-up end | present | `2026-06-26 17:17:05.885` UTC, final learner receiver finish before `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:267`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:354` |
| Complete catch-up duration | derived | `251.383 s`. | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164,267` |
| Active confirmation | present | `2026-06-26 17:19:52.830` UTC. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:354` |
| Additional iterations observed | present | Yes. The complete episode has two receiver iterations before `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:234`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:354` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTopToBottom` | `config:version_run.txt:key=inputs.AddSettings;line=8`; `config:podlog_solo-mdlt-n11/network-node1_logs/config/settingsUsed.txt:725` |
| Manifest batch | present | `2026-06-29-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration` |
| Manifest run | present | `top-to-bottom` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration;run=top-to-bottom` |
| Commit | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=hederahash;line=11` |
| Network disease preflight | derived | pass; no fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164` |
| Teacher node | present | First iteration teacher node `4`; second iteration teacher node `6`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:234` |
| First reconnect start UTC | present | `2026-06-26 17:12:54.502` | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164` |
| First reconnect end UTC | present | `2026-06-26 17:16:06.085` | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:189` |
| Learner duration | derived | First iteration `191.583 s`; complete catch-up duration `251.383 s`. | `derived:formula=end-start;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164,189,267` |
| Teacher reconnect context present | present | yes | [Teacher Evidence](#teacher-evidence) |
| Reconnect stats present | present | yes | [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Teacher/learner state size present | present | yes | [State And Divergence Evidence](#state-and-divergence-evidence) |
| Workload profile present | present | yes | [Workload Evidence](#workload-evidence) |
| RTT evidence present | present | yes, via CSV stats and passive socket RTT fields. | [Network Evidence](#network-evidence) |
| Bandwidth evidence present | present | yes, via learner data/time, CSV send-rate, and passive socket observed send/delivery fields. | [Network Evidence](#network-evidence) |
| TCP/window evidence present | present | yes, passive sampler fields overlap both reconnect iterations. | [Network Evidence](#network-evidence) |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `2` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-06-26 17:12:54.502` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-06-26 17:17:05.885` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `251.383 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-06-26 17:19:52.830` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | yes | `derived:protocol_acceptance;inputs=[Run Context](#run-context),[Network Disease Preflight](#network-disease-preflight),[Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations),[Reconnect Work-Shape Counters](#reconnect-work-shape-counters),[State And Divergence Evidence](#state-and-divergence-evidence),[Workload Evidence](#workload-evidence),[Network Evidence](#network-evidence)` |
| Reason if not accepted | not_applicable | Accepted; no rejection reason. | [Acceptance Notes](#acceptance-notes) |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Calibration acceptance | derived | The run has no fatal preflight disease, confirmed mode/learner, complete catch-up through `ACTIVE`, per-iteration counters, coarse state/workload context, and RTT/bandwidth/TCP-window evidence. | [Analysis Output Per Mode](#analysis-output-per-mode) |
| Multiple iterations | present | The complete catch-up episode includes two receiver iterations. Trend/ranking should use complete catch-up duration, not only first-iteration duration. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Independent-run interpretation | present | This cluster run is an independent live-state calibration anchor; do not use it alone for causal traversal-mode ranking. | [State And Divergence Evidence](#state-and-divergence-evidence); `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Workflow controls | missing | Run root file inventory; `version_run.txt`; `client.log`; support/journal logs | `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s` | Workflow-control files are absent from this artifact, so stopped-pod timing and loop controls are inferred from reconnect logs and manifest context only. |
| Baseline/restored-state upload | missing | Run root file inventory; `version_run.txt`; `client.log`; support/journal logs | `baseline`, `restore`, `restored`, `upload`, `state upload` | No baseline/restored-state upload evidence found; treat run as independent live-state workflow evidence. |
| Exact stopped-pod script output | missing | `version_run.txt`, `client.log`, support/journal logs, learner config, learner log | `Stopping java`, `delete pod`, `kubectl.*delete`, `network-node1-0`, `HOSTNAME`, `ReconnectStartPayload` | No direct workflow stop marker exists; stopped pod is inferred as `network-node1-0` from learner node/pod mapping and receiver reconnect evidence. |
| `ReconnectMapMetrics` in stats CSV | missing | `MainNetStats0.csv`, `MainNetStats4.csv`, `MainNetStats6.csv` | `transfersFromTeacher`, `internalHashes`, `leafData`, clean/dirty fields | Work-shape counters are present in learner logs, not mirrored in stats CSVs. |
| Iteration 2 passive socket attribution | ambiguous | `network_sampler_network-node1-0.log`, `network_sampler_network-node7-0.log` | node0/node6 socket pairs during `17:16:13.434..17:17:05.885` | The bounded window uses a pre-existing socket; a new node0/node6 socket appears after receiver finish and is excluded from reconnect-window evidence. |
