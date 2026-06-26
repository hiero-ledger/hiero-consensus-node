# Two-Phase-Pessimistic Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/30-06-2026/dallas11_pullTwoPhasePessimistic/report`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | derived | No fatal network disease found. No post-startup `ACTIVE -> CHECKING` transitions and no missing-parent evidence were found in searched run-local logs. | `derived:network_disease_preflight;inputs=log:podlog_solo-mdlt-n11/network-node*_logs/swirlds*.log;patterns=ACTIVE->CHECKING,Shadowgraph: Missing non-expired other parent` |
| Files searched | present | Available node `*.log` files under `podlog_solo-mdlt-n11/network-node*_logs/`, including available `swirlds*.log` files. Plain `swirlds.log` is missing for `network-node4_logs` and `network-node6_logs`. | `derived:file_inventory;scope=podlog_solo-mdlt-n11/network-node*_logs` |
| Active confirmation context | present | Learner post-reconnect `CHECKING -> ACTIVE` occurred at `2026-06-30 05:26:29.186`; startup `CHECKING -> ACTIVE` appears in other nodes and is non-fatal. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3761`; `log:podlog_solo-mdlt-n11/network-node2_logs/swirlds.log:152`; `log:podlog_solo-mdlt-n11/network-node3_logs/swirlds.log:125`; `log:podlog_solo-mdlt-n11/network-node5_logs/swirlds.log:125`; `log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:125` |
| Extraction disposition | present | Normal extraction is valid, with teacher-window gaps caused by missing peer node logs. | [Unresolved Evidence Register](#unresolved-evidence-register) |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTwoPhasePessimistic`; all seven copied `settingsUsed.txt` files agree. | `config:version_run.txt:key=inputs.AddSettings;line=8`; `derived:scan_all_nodes;inputs=config:podlog_solo-mdlt-n11/network-node*_logs/config/settingsUsed.txt:line=725;expected=pullTwoPhasePessimistic` |
| Namespace | present | `Dallas11` | `config:version_run.txt:key=namespace;line=1` |
| Commit hash | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=inputs.hederaversion;line=2`; `config:version_run.txt:key=hederahash;line=11` |
| Run number / job URL | present | run `305`; workflow URL `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/28399465175`. | `config:version_run.txt:key=JOB_URL;line=10`; `config:version_run.txt:key=run_number;line=12` |
| NLG controls | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `97,500,000` accounts, `6h`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`; `config:version_run.txt:key=inputs.NLGDparams;line=4`; `config:version_run.txt:key=inputs.NLG_Accounts;line=5`; `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Network size | present | Seven network-node pods, `network-node1-0` through `network-node7-0`, captured running in `pod_state.txt`. | `log:pod_state.txt:17-23` |
| Learner node and pod | present | Learner is node ID `0`, pod `network-node1-0`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3692`; `config:podlog_solo-mdlt-n11/network-node1_logs/config/settingsUsed.txt:948`; `log:pod_state.txt:17` |
| Node log inventory | missing | Plain `swirlds.log` is absent for `network-node4_logs` and `network-node6_logs`, causing missing teacher windows for peer `3` and peer `5`. | files checked: `podlog_solo-mdlt-n11/network-node*_logs/swirlds.log`; reason: expected files absent |
| Workflow controls | missing | No workflow-control source was found for warmtime, downtime, loop count, or `profileReconnectLoopK8s`. | files checked: `version_run.txt`, `client.log`, `pod_state.txt`, pod logs; patterns checked: `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s`; reason: no matches |
| Baseline/restored state upload | missing | No baseline, restore, upload, or state-upload evidence was found. | files checked: run root inventory, `version_run.txt`, `client.log`, `pod_state.txt`, pod logs; patterns checked: `baseline`, `restore`, `restored-state`, `restored state`, `upload`, `state upload`; reason: no matches |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved through startup to `OBSERVING -> BEHIND` before the first receiver reconnect. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:130`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:151`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:157` |
| Episode receiver coverage | present | The complete episode has 40 learner receiver starts and 40 learner receiver finishes from `2026-06-30 00:08:40.750` through `05:26:06.913`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161-3692`; `derived:count_receiver_starts_finishes=40` |
| First receiver window | present | Iteration 1, peer `5`, `2026-06-30 00:08:40.750..00:16:51.301`; duration `490.551 s`. Matching teacher window is missing because `network-node6_logs/swirlds.log` is absent. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:186`; files checked: `podlog_solo-mdlt-n11/network-node6_logs/swirlds.log`; reason: expected teacher log absent |
| Representative teacher windows | present | 27 of 40 teacher windows matched from available teacher logs. Available teacher-log counts are node1/network-node2 `6`, node2/network-node3 `8`, node4/network-node5 `8`, and node6/network-node7 `5`; examples include iteration 2 node2 `00:17:05.830..00:24:16.262`, iteration 3 node6 `00:24:32.021..00:31:43.449`, and iteration 40 node1 `05:25:56.860..05:26:06.912`. | `derived:teacher_window_coverage=27_of_40;available_teacher_log_counts=network-node2:6,network-node3:8,network-node5:8,network-node7:5;missing_logs=network-node4,network-node6`; `log:podlog_solo-mdlt-n11/network-node3_logs/swirlds.log:678-715`; `log:podlog_solo-mdlt-n11/network-node7_logs/swirlds.log:680-745`; `log:podlog_solo-mdlt-n11/network-node2_logs/swirlds.log:1615-1650` |
| Missing teacher windows | missing | 13 of 40 teacher windows cannot be matched because plain teacher logs are absent for peer `3` / `network-node4_logs` and peer `5` / `network-node6_logs`. | files checked: `podlog_solo-mdlt-n11/network-node4_logs/swirlds.log`, `podlog_solo-mdlt-n11/network-node6_logs/swirlds.log`; affected iterations: peer3 `8,15,17,21,27`; peer5 `1,6,14,23,25,29,32,34` |
| Final receiver window | present | Iteration 40, peer `1`, `2026-06-30 05:25:51.172..05:26:06.913`; duration `15.741 s`; matching teacher node1 window `05:25:56.860..05:26:06.912`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3662`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3692`; `log:podlog_solo-mdlt-n11/network-node2_logs/swirlds.log:1615-1650` |
| Learner status after final receiver finish | present | `BEHIND -> RECONNECT_COMPLETE -> CHECKING -> ACTIVE`; final `ACTIVE` at `2026-06-30 05:26:29.186`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3709`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3760`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3761` |

## Learner Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| First iteration learner evidence | present | Iteration 1 peer `5`, receiver window `00:08:40.750..00:16:51.301`, duration `490.551 s`; synchronization stage `482.884 s`; data `8925.015906 MB`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161-186` |
| Last iteration learner evidence | present | Iteration 40 peer `1`, receiver window `05:25:51.172..05:26:06.913`, duration `15.741 s`; synchronization stage `9.337 s`; data `13.145569 MB`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3662-3692` |
| Learner status churn | present | Episode contains repeated `BEHIND -> RECONNECT_COMPLETE -> BEHIND` between iterations before final completion through `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:203`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:227`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3620`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3658`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3709-3761` |

## Teacher Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Teacher-window coverage | ambiguous | 27 of 40 teacher windows are present; 13 are missing because two peer logs are absent. Available teacher-log counts are node1/network-node2 `6`, node2/network-node3 `8`, node4/network-node5 `8`, and node6/network-node7 `5`. | `derived:teacher_window_coverage=27_of_40;available_teacher_log_counts=network-node2:6,network-node3:8,network-node5:8,network-node7:5;missing_logs=network-node4,network-node6;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161-3692` |
| First available teacher sent state | present | Iteration 2, peer `2` / `network-node3_logs`, window `00:17:05.830..00:24:16.262`, sent range `[305230553,610461106]`, derived size `305,230,554`. | `log:podlog_solo-mdlt-n11/network-node3_logs/swirlds.log:678-715` |
| Last available teacher sent state | present | Iteration 40, peer `1` / `network-node2_logs`, window `05:25:56.860..05:26:06.912`, sent range `[354501031,709002062]`, derived size `354,501,032`. | `log:podlog_solo-mdlt-n11/network-node2_logs/swirlds.log:1615-1650` |

## Reconnect Work-Shape Counters

| Counter | First iteration | Last iteration | Aggregate 40 iterations | Source references |
|---|---:|---:|---:|---|
| transfersFromTeacher | 312,388,459 | 913,000 | 12,899,624,176 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| transfersFromLearner | 305,215,467 | 891,244 | 12,575,297,203 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| internalHashes | 9,351,159 | 148,559 | 80,351,023 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| internalCleanHashes | 274,374 | 148,891 | 19,857,151 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| internalData | 9,345,401 | 149,771 | 80,247,578 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| internalCleanData | 274,483 | 149,879 | 19,850,654 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| leafHashes | 291,999,728 | 741,705 | 12,329,578,552 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| leafCleanHashes | 252,073,705 | 752,672 | 12,186,386,283 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| leafData | 303,119,305 | 763,760 | 12,820,069,459 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| leafCleanData | 256,556,227 | 763,099 | 12,432,836,830 | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3684`; `derived:sum ReconnectMapMetrics over log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |

| Derived aggregate dirty counter | Status | Value | Source references |
|---|---:|---:|---|
| internalDirtyHashes | derived | 60,493,872 | `derived:formula=internalHashes-internalCleanHashes;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| internalDirtyData | derived | 60,396,924 | `derived:formula=internalData-internalCleanData;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| leafDirtyHashes | derived | 143,192,269 | `derived:formula=leafHashes-leafCleanHashes;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |
| leafDirtyData | derived | 387,232,629 | `derived:formula=leafData-leafCleanData;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684` |

## Network Evidence

Observed CSV send-rate and passive socket-rate evidence are observed behavior, not link capacity.

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| CSV network evidence, first iteration | present | Learner node0 to teacher peer5 (`network-node6`) has `ping_us_05` and `bytes_per_sec_sent_05` over 164 rows; teacher has `ping_us_00` and `bytes_per_sec_sent_00` over 164 rows. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_05,bytes_per_sec_sent_05,time;rows=894-1057`; `csv:podlog_solo-mdlt-n11/network-node6_logs/stats/MainNetStats5.csv:columns=ping_us_00,bytes_per_sec_sent_00,time;rows=5091-5254` |
| CSV network evidence, representative middle iteration | present | Iteration 21 node0 to teacher peer3 (`network-node4`) has learner CSV `ping_us_03` and `bytes_per_sec_sent_03` over 158 rows; teacher CSV `ping_us_00` and `bytes_per_sec_sent_00` over 157 rows. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_03,bytes_per_sec_sent_03,time;rows=4006-4163`; `csv:podlog_solo-mdlt-n11/network-node4_logs/stats/MainNetStats3.csv:columns=ping_us_00,bytes_per_sec_sent_00,time;rows=8203-8359` |
| CSV network evidence, last iteration | present | Iteration 40 node0 to teacher peer1 (`network-node2`) has learner and teacher CSV ping/send-rate evidence over 5 rows. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_01,bytes_per_sec_sent_01,time;rows=7238-7242`; `csv:podlog_solo-mdlt-n11/network-node2_logs/stats/MainNetStats1.csv:columns=ping_us_00,bytes_per_sec_sent_00,time;rows=11434-11438` |
| Passive sampler files | missing | No top-level reconnect sampler files, per-node passive sampler files, or embedded passive socket samples were found under the run root. | files checked: full run root; patterns checked: `*sampler*`, `*samples*`, `network_sampler_network-node*-0.log`, `reconnect_network_samples_1.log`, `reconnect_network_samples_1_summary.log`; reason: no matches |
| TCP/window/backpressure evidence | missing | No passive `ss -tin`/TCP-window source exists, so no sampler-derived queue, congestion-window, retransmission, or backpressure evidence can be extracted. | files checked: full run root; patterns checked: `ss -tin`, `cwnd`, `snd_wnd`, `rtt:`, `minrtt`, `delivery_rate`, `pacing_rate`, `rwnd_limited`, `notsent`, `Send-Q`, `Recv-Q`, `ESTAB`; reason: no matches |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 97.5M accounts, 1000 tokens, 97.5M NFTs, `HOT 50%`, 100000 topics, transfer workload for `PT6H`. | `log:client.log:2-9` |
| Transaction mix | present | Crypto transfer, NFT transfer, HCS message send, HeliSwap swaps, and smart-contract calls are present in final summaries. | `log:client.log:10564-10568` |
| Workload near reconnect start | present | Around `00:08:34..00:08:43`, transactions were about `142.8M..142.9M`, current TPS around `10.3k`, with crypto/NFT/HCS lines nearby. | `log:client.log:4916-4922`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161` |
| Mid-window workload | present | Around `02:44:16..02:44:45`, transactions were about `239.8M..240.1M`, current TPS around `10.4k`, with crypto/NFT/HCS/swap lines nearby. | `log:client.log:7817-7828`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1955`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:1985` |
| Late workload | present | Around `05:04:01..05:06:55`, transactions were about `326.9M..328.7M`; current TPS generally around `10.1k..10.5k`; crypto/NFT/HCS lines are present. | `log:client.log:10422-10477` |
| Load continuity limitation | ambiguous | Client jobs finish at `05:11:41..05:11:42`, before final receiver start `05:25:51`, final receiver finish `05:26:06`, and `ACTIVE` at `05:26:29`. | `log:client.log:10564-10570`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3662`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3692`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3761`; `derived:timestamp_comparison` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| First iteration learner start size | derived | `[294590651,589181302]` gives `294,590,652` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164` |
| First iteration target size and gap | derived | Target `[303660500,607321000]` gives `303,660,501` leaves; first gap is `9,069,849` leaves. | `derived:formula=303660501-294590652;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:164,168` |
| Final iteration target size and gap | derived | Final start size `[354501360,709002720]` gives `354,501,361`; target `[354501031,709002062]` gives `354,501,032`; final gap is `-329` leaves. | `derived:formula=354501032-354501361;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3665,3669` |
| Learner stats state/store snapshots | present | Learner `vmap_size_state`: `294,578,998` before first start, `294,590,652` just after start, `354,501,361` at/before final finish, `354,501,032` after `ACTIVE`. Service-store counts include accounts `97,500,712`, NFTs `97,500,000`, token associations `1,798,562`, tokens `1,000`, topics `100,000`. | `csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:rows=893,894,7242,7251;columns=time,vmap_size_state,accountsUsed,contractsUsed,nftsUsed,tokenAssociationsUsed,tokensUsed,topicsUsed` |
| Divergence shape | derived | Growth-heavy catch-up overall, with a large initial target gap and very high aggregate clean leaf work; final iteration becomes a tiny shrink/correction (`-329` leaves), matching the post-`ACTIVE` `vmap_size_state` drop. | `derived:classify_from_state_gap_and_clean_dirty_counters;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:183-3684/csv:podlog_solo-mdlt-n11/network-node1_logs/stats/MainNetStats0.csv:rows=893,894,7242,7251` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3692`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3761` |
| Iteration count | derived | `40` learner receiver reconnect iterations before `ACTIVE`. | `derived:count_receiver_starts_before_ACTIVE;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161-3692` |
| Complete catch-up start | present | `2026-06-30 00:08:40.750` UTC. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161` |
| Complete catch-up end | present | `2026-06-30 05:26:06.913` UTC, final learner receiver finish before `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3692`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3761` |
| Complete catch-up duration | derived | `19,046.163 s` (`5:17:26.163`). | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161,3692` |
| Active confirmation | present | `2026-06-30 05:26:29.186` UTC. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3761` |
| Iteration detail coverage | present | First and last iteration anchors, aggregate 40-row work-shape counters, and teacher-window coverage are recorded. Full raw 40-row metric output is represented by coverage plus aggregate sums rather than listing all 40 rows inline. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161-3692`; `derived:coverage=receiver_pairs=40,metric_rows=40,teacher_windows=27,missing_teacher_windows=13` |
| Additional iterations observed | present | Yes. The complete episode has 40 receiver iterations before `ACTIVE`. | `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:161-3692`; `log:podlog_solo-mdlt-n11/network-node1_logs/swirlds.log:3761` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTwoPhasePessimistic` | [Run Context](#run-context) |
| Manifest batch | present | `2026-06-30-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration` |
| Manifest run | present | `two-phase-pessimistic` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration;run=two-phase-pessimistic` |
| Commit | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | [Run Context](#run-context) |
| Network disease preflight | derived | pass; no fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | [Run Context](#run-context) |
| Teacher node | ambiguous | First peer is `5` but matching teacher log is absent; 27 of 40 teacher windows are matched, including final teacher node `1`. | [Teacher Evidence](#teacher-evidence) |
| First reconnect start UTC | present | `2026-06-30 00:08:40.750` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect end UTC | present | `2026-06-30 00:16:51.301` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| Learner duration | derived | First iteration `490.551 s`; complete catch-up duration `19,046.163 s`. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Teacher reconnect context present | ambiguous | partial; 27 of 40 teacher windows matched. | [Teacher Evidence](#teacher-evidence) |
| Reconnect stats present | present | yes | [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Teacher/learner state size present | present | yes, with partial teacher-window coverage. | [State And Divergence Evidence](#state-and-divergence-evidence) |
| Workload profile present | present | yes, but load does not continue through final receiver finish/`ACTIVE`. | [Workload Evidence](#workload-evidence) |
| RTT evidence present | present | yes, via CSV stats. | [Network Evidence](#network-evidence) |
| Bandwidth evidence present | present | yes, via CSV send-rate and learner data/time; no passive socket throughput context. | [Network Evidence](#network-evidence) |
| TCP/window evidence present | missing | no passive sampler evidence. | [Network Evidence](#network-evidence) |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `40` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-06-30 00:08:40.750` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-06-30 05:26:06.913` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `19,046.163 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-06-30 05:26:29.186` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | no | `derived:protocol_acceptance_requires_TCP_window_evidence;additional_limitations=workload_ends_before_final_completion,partial_teacher_context;inputs=[Network Evidence](#network-evidence),[Workload Evidence](#workload-evidence),[Teacher Evidence](#teacher-evidence)` |
| Reason if not accepted | present | Passive TCP/window evidence is absent, workload ends before final receiver finish/`ACTIVE`, and 13 teacher windows are missing due absent peer logs. | [Unresolved Evidence Register](#unresolved-evidence-register) |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Timing/work-shape extraction | present | Complete 40-iteration learner episode and aggregate work-shape counters are source-referenced. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations); [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Full calibration acceptance | derived | Not accepted for full calibration because passive TCP/window evidence is absent, workload ends before final completion, and teacher-window coverage is partial. | [Network Evidence](#network-evidence); [Workload Evidence](#workload-evidence); [Teacher Evidence](#teacher-evidence) |
| Independent-run interpretation | present | This cluster run is an independent live-state calibration anchor; do not use it alone for causal traversal-mode ranking. | [State And Divergence Evidence](#state-and-divergence-evidence); `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Missing peer teacher logs | missing | `podlog_solo-mdlt-n11/network-node4_logs/swirlds.log`, `podlog_solo-mdlt-n11/network-node6_logs/swirlds.log` | expected plain `swirlds.log` | Absent peer logs prevent matching 13 of 40 teacher windows. |
| Passive sampler files | missing | Full run root | `*sampler*`, `*samples*`, `network_sampler_network-node*-0.log`, `reconnect_network_samples_1.log`, `reconnect_network_samples_1_summary.log` | No passive sampler source exists; TCP/window/backpressure evidence cannot be extracted. |
| Workload continuity through final completion | ambiguous | `client.log`, learner `swirlds.log` | timestamp comparison | Client jobs finish before the final receiver start, final receiver finish, and final `ACTIVE`. |
| Workflow controls | missing | `version_run.txt`, `client.log`, `pod_state.txt`, pod logs | `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s` | Workflow-control files are absent. |
| Baseline/restored-state upload | missing | Run root inventory, `version_run.txt`, `client.log`, `pod_state.txt`, pod logs | `baseline`, `restore`, `restored-state`, `upload`, `state upload` | No baseline/restored-state upload evidence found; treat run as independent live-state workflow evidence. |
| Full 40-row metric table | present | Learner `swirlds.log` | per-iteration `ReconnectMapMetrics` rows | This Markdown records first, last, and aggregate counter rows plus coverage rather than expanding all 40 raw counter rows inline. |
