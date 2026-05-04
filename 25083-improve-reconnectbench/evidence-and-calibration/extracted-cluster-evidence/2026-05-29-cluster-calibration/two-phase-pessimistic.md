# Two-Phase Pessimistic Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect2_2phase/report`

All pod, config, client, sampler, and stats artifact paths below are relative to the artifact run root. Workflow log references use `../performance-tests-start.log` or `../performance-tests-watch.log` because those logs live beside `report/`.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | pass | No fatal network disease found. Retrospective scan found no post-startup `ACTIVE -> CHECKING` transitions. Missing-parent evidence is present, but missing-parent lines alone are not fatal under the protocol. | `derived:formula=rg_no_matches_oldStatus_ACTIVE_newStatus_CHECKING;inputs=log:podlog_solo-mdlt-n4/network-node*_logs/swirlds.log`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:148-170` |
| Missing non-expired other parent | present | Missing-parent evidence is present in node logs 1-6. Count by node log: `network-node1_logs=66`, `network-node2_logs=181`, `network-node3_logs=189`, `network-node4_logs=191`, `network-node5_logs=189`, `network-node6_logs=188`. This is diagnostic only because no `ACTIVE -> CHECKING` churn was found. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:148-170`, `derived:formula=rg_count_Missing_non_expired_other_parent_by_node_log` |
| Extraction disposition | pass | Normal extraction remains governed by the existing evidence acceptance criteria. This run remains rejected for calibration because passive TCP/window samples miss the first reconnect window, not because of network disease. | [Analysis Output Per Mode](#analysis-output-per-mode) |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTwoPhasePessimistic` | `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:727`, `config:version_run.txt:key=inputs.AddSettings;line=8` |
| Namespace | present | `MDLT4` | `config:version_run.txt:key=namespace;line=1` |
| Hedera version input | present | `main` | `config:version_run.txt:key=inputs.hederaversion;line=2` |
| Solo chart/version input | present | `latest_tested_solo-charts0.59_balanced` | `config:version_run.txt:key=inputs.soloversion;line=3` |
| Commit hash | present | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | `config:version_run.txt:key=hederahash;line=11` |
| Run number | present | `295` | `config:version_run.txt:key=run_number;line=12` |
| Job URL | present | `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/26648700320` | `config:version_run.txt:key=JOB_URL;line=10` |
| Network size | present | Seven configured node domains were present. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:60-108` |
| Workflow pod listing IP detail | absent | Workflow pod listings contain plain `get pods` output without IP columns; endpoint identity therefore uses per-pod sampler local-IP evidence. | `workflow:../performance-tests-start.log:4942-4948`, `workflow:../performance-tests-watch.log:1005-1011` |
| Sampler pod IP mapping | present | `network-node1-0 -> 10.36.16.208`, `network-node2-0 -> 10.36.28.152`, `network-node3-0 -> 10.36.68.154`, `network-node4-0 -> 10.36.27.27`, `network-node5-0 -> 10.36.9.166`, `network-node6-0 -> 10.36.11.147`, `network-node7-0 -> 10.36.63.147`. | `sampler:network_sampler_network-node1-0.log:3`, `sampler:network_sampler_network-node2-0.log:3`, `sampler:network_sampler_network-node3-0.log:3`, `sampler:network_sampler_network-node4-0.log:3`, `sampler:network_sampler_network-node5-0.log:3`, `sampler:network_sampler_network-node6-0.log:3`, `sampler:network_sampler_network-node7-0.log:3` |
| Baseline/restored state upload | absent | Solo node start skipped `Upload state files network nodes`, so this run did not start from an uploaded common baseline state. | `workflow:../performance-tests-start.log:4793-4794` |
| Learner node | present | Learner was node ID `0`, represented by `network-node1_logs`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| Stopped pod | inferred | `network-node1-0`. No exact `stoppedPod=` script line was emitted, but the manifest records intended learner `network-node1-0` / node `0`; workflow `Stopping java` / `Down for downtime=1800` is followed by reconnect-loop sampling on `network-node1-0`; `network-node1_logs` maps to node ID `0`; learner `settingsUsed.txt` records `HOSTNAME, network-node1-0`; and the first receiver reconnect is logged by node `0`. | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration;run=two-phase-pessimistic`, `workflow:../performance-tests-watch.log:1842-1845`, `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:950`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197` |
| Controlled warmtime, downtime, and loop count | present | `downtime=1800`, `warmtime=600`, and `NofLoops=0`; workflow then executed `profileReconnectLoopK8s.sh "solo-mdlt-n4" ${nlgpod} ${downtime} ${warmtime} ${NofLoops}`. | `workflow:../performance-tests-watch.log:1494-1499`, `workflow:../performance-tests-watch.log:1839-1843` |
| Workload profile | present | `LongevityLoadTest` with max TPS input `8000`, `24000000` accounts, and `6h` duration. | `config:version_run.txt:key=inputs.NLG_Test;line=9`, `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `workflow:../performance-tests-start.log:41-44` |
| Transaction mix | present | Client was configured for 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and `transfer` for `PT6H`. | `log:client.log:2-9` |
| NLGArguments | present | `LongevityLoadTest`; `-Dbenchmark.maxtps=8000`; `NLG_Accounts=24000000`; `NLG_Time=6h`. Client config expands this to 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50%, 100000 topics, ECDSA keys, transfer `PT6H`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`, `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `log:client.log:2-9` |
| configSummary | present | Short config identifier: `MDLT4/run 295/eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2/pullTwoPhasePessimistic`; primary config path: `podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt`. | `config:version_run.txt:key=namespace;line=1`, `config:version_run.txt:key=inputs.AddSettings;line=8`, `config:version_run.txt:key=hederahash;line=11`, `config:version_run.txt:key=run_number;line=12`, `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:727` |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved to `BEHIND` after self-fallen-behind reports for peers. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:188-193` |
| First reconnect start | present | `2026-05-29 18:26:08.709` UTC, receiver node `0`, teacher peer `2`, round `22388`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| First reconnect end | present | `2026-05-29 18:29:04.992` UTC, receiver node `0`, teacher peer `2`, round `30408`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224-224` |
| Learner wall-clock reconnect duration | derived | `176.283 s` from learner start to learner finish. | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224-224` |
| Learner reconnect synchronization stage duration | present | `170.618 s`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:222-222` |
| Learner status transition after reconnect | present | `BEHIND -> RECONNECT_COMPLETE -> CHECKING -> ACTIVE`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:241-241`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:280-280`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:390-390` |
| Matching teacher node | present | Teacher peer `2` maps to `network-node3_logs`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197`, `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:858-858` |
| Teacher reconnect start | present | `2026-05-29 18:26:10.935` UTC, sender node `2`, receiver node `0`. | `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:858-858` |
| Teacher reconnect end | present | `2026-05-29 18:29:04.995` UTC, sender node `2`, receiver node `0`. | `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:894-894` |
| Teacher reconnect status | present | Teacher sender reconnect finished for receiver `0`. | `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:894-894` |
| Teacher wall-clock reconnect duration | derived | `174.060 s` from teacher start to teacher finish. | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:858-858,log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:894-894` |

## Learner Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner view path range before reconnect | present | `firstLeafPath=73728939`, `lastLeafPath=147457878`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:200-200` |
| Learner reinitialized path range | present | `firstLeafPath: 73728939 -> 80174848`, `lastLeafPath: 147457878 -> 160349696`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:204-204` |
| Learner flusher state range | present | `firstLeafPath=80174848`, `lastLeafPath=160349696`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:205-205` |
| Learner start state size | derived | `73728940` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:200-200` |
| Learner target/end state size | derived | `80174849` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:204-205` |
| Learner data received | present | `3947.6936264038086 MiB`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:223-223` |
| Learner-side reconnect completion | present | Reconnect learner was complete and learner task finished before the receiver finish payload. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:218-224` |

## Teacher Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Teacher state send start | present | Teacher began sending state to receiver node `0`. | `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:859-859` |
| Teacher root response state range | present | `firstLeafPath=80174848`, `lastLeafPath=160349696`. | `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| Teacher sent state size | derived | `80174849` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| Teacher task execution | present | Teacher task output spans reconnect teaching and finished tree send. | `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:875-893` |
| Teacher sampled state size during window | derived | `vmap_size_state` increased from `80181704` to `80684635` over bounded teacher stats rows. | `derived:formula=first_and_last_vmap_size_state_in_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=vmap_size_state;rows=2955-3014;timestamp=2026-05-29 18:26:09 UTC..2026-05-29 18:29:06 UTC` |

## Reconnect Work-Shape Counters

| Counter | Status | Value | Source references |
|---|---:|---:|---|
| transfersFromTeacher | present | `84383463` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| transfersFromLearner | present | `83633336` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| internalHashes | present | `42075117` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| internalCleanHashes | present | `19028734` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| internalData | present | `42125346` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| internalCleanData | present | `19084520` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| leafHashes | present | `41795314` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| leafCleanHashes | present | `12716870` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| leafData | present | `42848733` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| leafCleanData | present | `12846292` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| internalDirtyHashes | derived | `23046383` | `derived:formula=internalHashes-internalCleanHashes;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| internalDirtyData | derived | `23040826` | `derived:formula=internalData-internalCleanData;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| leafDirtyHashes | derived | `29078444` | `derived:formula=leafHashes-leafCleanHashes;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| leafDirtyData | derived | `30002441` | `derived:formula=leafData-leafCleanData;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |

## Network Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Reconnect data lower-bound throughput | derived | `23.138 MiB/s`, approximately `194.092 Mbit/s`, from learner data volume divided by synchronization stage time. | `derived:formula=dataMiB/timeSeconds;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:222-223` |
| Teacher-to-learner stats throughput | derived | `bytes_per_sec_sent_00` over bounded teacher rows: average `22865350.21 B/s`, max `59004332.32 B/s` at row `3006`. | `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2955-3014;timestamp=2026-05-29 18:26:09 UTC..2026-05-29 18:29:06 UTC` |
| Teacher total stats throughput | derived | `bytes_per_sec_sent` average `26364105.86 B/s` over the same bounded rows. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent;rows=2955-3014;timestamp=2026-05-29 18:26:09 UTC..2026-05-29 18:29:06 UTC` |
| Stats ping from teacher to learner | derived | `ping_us_00` average `556.99 us` over bounded teacher rows. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2955-3014;timestamp=2026-05-29 18:26:09 UTC..2026-05-29 18:29:06 UTC` |
| Passive TCP sampler coverage during first reconnect | missing | Both learner and teacher samplers skip the first reconnect window. Node1 jumps from `18:25:31Z` to `18:35:37Z`; node3 jumps from `18:25:32Z` to `18:35:32Z`. | `sampler:network_sampler_network-node1-0.log:2581,2653;window=gap_covering_first_reconnect`, `sampler:network_sampler_network-node3-0.log:8213,8425;window=gap_covering_first_reconnect` |
| Passive TCP/window fields | missing | The sampler format has TCP fields outside the reconnect window, and post-window learner/teacher socket rows exist, but no passive TCP/window metrics overlap the first reconnect window and the post-window rows are not used for first-window calibration. | `sampler:network_sampler_network-node1-0.log:3043;window=post_first_reconnect`, `sampler:network_sampler_network-node3-0.log:10113;window=post_first_reconnect`, `sampler:network_sampler_network-node1-0.log:2581,2653;window=gap_covering_first_reconnect`, `sampler:network_sampler_network-node3-0.log:8213,8425;window=gap_covering_first_reconnect` |
| Post-window TCP sample values | present | Endpoint identity is resolved for pair `[::ffff:10.36.16.208]:56440 <-> [::ffff:10.36.68.154]:50111`, but these samples are after the first reconnect window and are not first-window calibration evidence. Learner-side post-window sample at `18:35:54Z`: `Recv-Q=0`, `Send-Q=0`, `rtt=10.126/12.639`, `cwnd=10`, `ssthresh=108`, `bytes_sent=5570169979`, `bytes_retrans=162052`, `bytes_received=6191818981`, `delivery_rate=289600000bps`, `rwnd_limited=121792ms(51.7%)`, `snd_wnd=1302528`. Teacher-side post-window sample at `18:36:12Z`: `Recv-Q=0`, `Send-Q=0`, `rtt=3.26/6.117`, `cwnd=26`, `ssthresh=18`, `bytes_sent=6240463923`, `bytes_retrans=1557813`, `bytes_received=5570468101`, `delivery_rate=1210268656bps`, `rwnd_limited=143012ms(61.0%)`, `snd_wnd=31744`. | `sampler:network_sampler_network-node1-0.log:2951,3043-3044;window=post_first_reconnect`, `sampler:network_sampler_network-node3-0.log:10013,10113-10114;window=post_first_reconnect` |
| Learner/teacher endpoint identity | present | First reconnect learner node `0` / `network-node1-0` maps to `10.36.16.208`; teacher node `2` / `network-node3-0` maps to `10.36.68.154`. Endpoint identity is resolved, but reconnect-window passive socket metrics are unavailable. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197,224`, `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:858,894`, `sampler:network_sampler_network-node1-0.log:3`, `sampler:network_sampler_network-node3-0.log:3` |
| Top-level reconnect sampler attribution source | absent | `reconnect_network_samples_1.log` is empty and provides no reconnect-window TCP attribution evidence. | `derived:formula=wc_l_is_zero;inputs=sampler:reconnect_network_samples_1.log` |
| Full node metrics cross-check | ambiguous | Stats CSVs for nodes 1-6 overlap the first reconnect window and expose reconnect counters, `time`, `bytes_per_sec_sent`, peer throughput/ping columns, and `vmap_size_state`; node 7 does not overlap the reconnect window, with available stats ending at `2026-05-29 17:15:30 UTC`. | `csv:podlog_solo-mdlt-n4/network-node1_logs/stats/MainNetStats0.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,time,vmap_size_state;rows=1203-1282;timestamp=2026-05-29 18:26:01 UTC..18:29:58 UTC`, `csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2953-3032;timestamp=2026-05-29 18:26:00 UTC..18:29:57 UTC`, `csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2952-3031;timestamp=2026-05-29 18:26:00 UTC..18:29:57 UTC`, `csv:podlog_solo-mdlt-n4/network-node4_logs/stats/MainNetStats3.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2953-3032;timestamp=2026-05-29 18:26:00 UTC..18:29:57 UTC`, `csv:podlog_solo-mdlt-n4/network-node5_logs/stats/MainNetStats4.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2952-3031;timestamp=2026-05-29 18:26:00 UTC..18:29:57 UTC`, `csv:podlog_solo-mdlt-n4/network-node6_logs/stats/MainNetStats5.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2953-3032;timestamp=2026-05-29 18:26:01 UTC..18:29:58 UTC`, `csv:podlog_solo-mdlt-n4/network-node7_logs/stats/MainNetStats6.csv:column=time;rows=786-1542;timestamp=2026-05-29 16:37:42 UTC..17:15:30 UTC`; node7 overlap search pattern: `2026-05-29 18:26` or `2026-05-29 18:29`; reason: no node7 stats rows overlap first reconnect window |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and transfer workload for `PT6H`. | `log:client.log:2-9` |
| Workload during reconnect | present | Client logs continue to show transactions and receipts during the reconnect interval, with `PLATFORM_NOT_ACTIVE` indicators also present. | `log:client.log:1959-2026` |
| Actual transaction rate during first reconnect | present | During `18:26:08.709..18:29:04.992 UTC`, client queue samples continue after the window start: transaction `TPS(current)` samples range `9297..9486` with `TPS(EMA)` `9441..9502`; receipt `TPS(current)` samples range `8882..9901` with `TPS(EMA)` `9466..9540`. Workload-specific samples include messages `1707..1716 TPS`, NFT transfers `2967..3010 TPS`, crypto transfers `4221..4343 TPS`, and one contract-swap sample at `313 TPS`. | `log:client.log:1962-2012;window=2026-05-29 18:26:08.709..18:29:04.992 UTC` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner start size | derived | `73728940` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:200-200` |
| Teacher target state size | derived | `80174849` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| Learner target equals teacher target | derived | yes, learner target/end size `80174849` equals teacher target state size `80174849`. | `derived:formula=learner_target_size==teacher_target_size;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:204-205,log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| State-size gap at reconnect | derived | `6445909` leaves between teacher target and learner start. | `derived:formula=teacher_target_size-learner_start_size;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:200-200,log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| Teacher growth during reconnect window | derived | Bounded stats sampled `vmap_size_state` from `80181704` to `80684635`. | `derived:formula=first_and_last_vmap_size_state_in_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=vmap_size_state;rows=2955-3014;timestamp=2026-05-29 18:26:09 UTC..2026-05-29 18:29:06 UTC` |
| Service/store size metrics during reconnect window | present | Learner stats rows stayed at `accountsUsed=24000712`, `contractsUsed=6`, `nftsUsed=24000000`, `tokenAssociationsUsed=1469889`, `tokensUsed=1000`, `topicsUsed=100000`. | `csv:podlog_solo-mdlt-n4/network-node1_logs/stats/MainNetStats0.csv:column=accountsUsed,contractsUsed,nftsUsed,tokenAssociationsUsed,tokensUsed,topicsUsed;rows=1206-1264;timestamp=2026-05-29 18:26:10 UTC..2026-05-29 18:29:04 UTC` |
| Divergence shape | derived | Growth-heavy reconnect with a substantial dirty component: `leafCleanData=12846292`, `leafDirtyData=30002441`, and a `6445909` leaf state-size gap. | `derived:formula=classify_from_state_gap_and_clean_dirty_counters;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221,derived:formula=teacher_target_size-learner_start_size` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish, confirming the catch-up episode completed. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:390` |
| Iteration count | derived | `1` learner receiver reconnect iteration before `ACTIVE`. | `derived:formula=count_receiver_ReconnectStartPayload_before_ACTIVE;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:390` |
| Complete catch-up start | present | `2026-05-29 18:26:08.709` UTC. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197` |
| Complete catch-up end | present | `2026-05-29 18:29:04.992` UTC, the final learner receiver reconnect finish before `ACTIVE`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:390` |
| Complete catch-up duration | derived | `176.283 s`. | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224` |
| Active confirmation | present | `2026-05-29 18:36:13.782` UTC, `CHECKING -> ACTIVE`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:390` |
| Iteration 1 | present | Receiver node `0`, teacher peer `2`, start `2026-05-29 18:26:08.709` UTC, finish `2026-05-29 18:29:04.992` UTC, duration `176.283 s`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224`, `derived:formula=end_timestamp-start_timestamp` |
| Additional iterations observed | present | No additional learner receiver reconnect starts were found before `ACTIVE`. | Search scope: `podlog_solo-mdlt-n4/network-node1_logs/swirlds.log`; pattern: `ReconnectStartPayload`; source: `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197`; reason: only one learner receiver start matched before `ACTIVE`. |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTwoPhasePessimistic` | `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:727` |
| Manifest batch | present | `2026-05-29-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration` |
| Manifest run | present | `two-phase-pessimistic` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration;run=two-phase-pessimistic` |
| Commit | present | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | `config:version_run.txt:key=hederahash;line=11` |
| Network disease preflight | pass | No fatal network disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | Missing-parent lines are present, but no `ACTIVE -> CHECKING` churn was found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| Teacher node | present | `2` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| First reconnect start UTC | present | `2026-05-29 18:26:08.709` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| First reconnect end UTC | present | `2026-05-29 18:29:04.992` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224-224` |
| Learner duration | derived | `176.283 s` | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224-224` |
| Teacher reconnect context present | present | yes | `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:858-894` |
| Reconnect stats present | present | yes | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-223` |
| Teacher/learner state size present | present | yes | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:200-205`, `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| Workload profile present | present | yes | `log:client.log:2-9`, `log:client.log:1959-2026` |
| RTT evidence present | present | yes, via stats ping; learner/teacher endpoint identity is resolved, but passive socket sample coverage is missing for the first reconnect window. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2955-3014`, `sampler:network_sampler_network-node1-0.log:3`, `sampler:network_sampler_network-node3-0.log:3`, `sampler:network_sampler_network-node3-0.log:8213,8425;window=gap_covering_first_reconnect` |
| Bandwidth evidence present | present | yes, via learner data/time and teacher stats throughput. | `derived:formula=dataMiB/timeSeconds;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:222-223`, `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2955-3014` |
| TCP/window evidence present | missing | No passive sampler block overlaps the first reconnect window. | `sampler:network_sampler_network-node3-0.log:8213-8213;window=before_first_reconnect`, `sampler:network_sampler_network-node3-0.log:8425-8425;window=after_first_reconnect` |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `1` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-05-29 18:26:08.709` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-05-29 18:29:04.992` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `176.283 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-05-29 18:36:13.782` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | no additional learner receiver reconnect observed before `ACTIVE`. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | no | `derived:formula=protocol_acceptance_requires_sufficient_RTT_bandwidth_TCP_window_evidence;inputs=sampler:network_sampler_network-node3-0.log:8213-8213;window=before_first_reconnect,sampler:network_sampler_network-node3-0.log:8425-8425;window=after_first_reconnect,derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2955-3014,derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2955-3014` |
| Reason if not accepted | derived | Passive TCP/window sampler coverage skips the first reconnect window, so network-inflight calibration is incomplete. | `derived:formula=missing_TCP_window_samples_for_first_reconnect;inputs=sampler:network_sampler_network-node3-0.log:8213-8213;window=before_first_reconnect,sampler:network_sampler_network-node3-0.log:8425-8425;window=after_first_reconnect` |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Timing and work-shape calibration | present | First reconnect timing, roles, counters, state sizes, and workload evidence are source-referenced. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-224`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-223`, `log:client.log:2-9` |
| Independent-run interpretation | present | Workflow logs confirm nominal reconnect controls and skipped restored-state upload. This run is a separate live-state calibration anchor; use its wall-clock duration with its own state size, gap, and work shape, and compare traversal modes locally only after reproducing comparable inputs. | `workflow:../performance-tests-start.log:4793-4794`, `workflow:../performance-tests-watch.log:1494-1499`, `derived:formula=teacher_target_size-learner_start_size;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:200-200,log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| Network-inflight calibration | missing | Stats throughput and ping are present; passive TCP/window samples do not overlap the first reconnect window. | `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2955-3014`, `sampler:network_sampler_network-node3-0.log:8213-8425;window=gap_covering_first_reconnect` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Exact stopped pod script output | inferred_not_direct | `version_run.txt`, `client.log`, top-level sampler file, `../performance-tests-watch.log`, learner `settingsUsed.txt`, learner `swirlds.log` | `stoppedPod`, `stopped pod`, `network-node1-0`, `Stopping java`, `HOSTNAME`, `ReconnectStartPayload` | No literal script field records the stopped pod, but `stoppedPod` is inferable as `network-node1-0` from intended learner context, workflow stop/down timing, host config, artifact node mapping, and observed node `0` receiver reconnect. Keep as inferred rather than directly captured. |
| Passive TCP/window fields during first reconnect | missing | `network_sampler_network-node1-0.log`, `network_sampler_network-node3-0.log` | TCP fields and timestamp blocks around `2026-05-29T18:26:08Z..18:29:05Z` | Endpoint identity is resolved, but samplers skip the first reconnect window; post-window socket rows are not first-window calibration evidence. |
| Full node metrics cross-check | ambiguous | `MainNetStats0.csv` through `MainNetStats6.csv` | reconnect counters, `bytes_per_sec_sent`, peer throughput/ping, `vmap_size_state`, `time` | Nodes 1-6 overlap the reconnect window, but node 7 stats do not overlap it. |
| Passive TCP/window samples during first reconnect | missing | `network_sampler_network-node3-0.log` | timestamp blocks around `2026-05-29T18:26:08Z..18:29:05Z` | Sampler jumps from `18:25:32Z` to `18:35:32Z`, skipping the first reconnect window. |
| Additional learner receiver reconnect iterations | not_applicable | `podlog_solo-mdlt-n4/network-node1_logs/swirlds.log`; source `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-390` | `ReconnectStartPayload` before `ACTIVE` | Only one learner receiver reconnect was observed before `ACTIVE`, so the complete catch-up episode has one iteration. |
