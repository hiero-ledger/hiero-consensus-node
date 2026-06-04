# Top-To-Bottom Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect1`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | pass | No fatal network disease found. Retrospective scan found no post-startup `ACTIVE -> CHECKING` transitions and no `Shadowgraph: Missing non-expired other parent` lines in the node logs. | `derived:formula=rg_no_matches_oldStatus_ACTIVE_newStatus_CHECKING_and_rg_no_matches_Missing_non_expired_other_parent;inputs=log:podlog_solo-mdlt-n3/network-node*_logs/swirlds.log` |
| Extraction disposition | pass | Normal extraction remains valid for calibration subject to the existing acceptance criteria. | [Analysis Output Per Mode](#analysis-output-per-mode) |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTopToBottom` | `config:podlog_solo-mdlt-n3/network-node1_logs/config/settingsUsed.txt:727` |
| Namespace | present | `MDLT3` | `config:version_run.txt:key=namespace;line=1` |
| Hedera version input | present | `main` | `config:version_run.txt:key=inputs.hederaversion;line=2` |
| Solo chart/version input | present | `latest_tested_solo-charts0.59_balanced` | `config:version_run.txt:key=inputs.soloversion;line=3` |
| Commit hash | present | `796905b12784f90d8b12b9ee0d9a6a91de0e9b85` | `config:version_run.txt:key=hederahash;line=11` |
| Run number | present | `293` | `config:version_run.txt:key=run_number;line=12` |
| Job URL | present | `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/26618604004` | `config:version_run.txt:key=JOB_URL;line=10` |
| Network size | present | Seven configured node domains were present. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:59-107` |
| Workflow pod listing IP detail | absent | Workflow logs contain plain `get pods` output without IP columns; endpoint attribution therefore uses sampler/config/log evidence below. | `workflow:performance-tests-start.log:4911,4927-4933`, `workflow:performance-tests-watch.log:991,1007-1013` |
| Sampler pod IP mapping | present | `network-node1-0 -> 10.36.24.218`, `network-node2-0 -> 10.36.22.146`, `network-node3-0 -> 10.36.7.246`, `network-node4-0 -> 10.36.23.124`, `network-node5-0 -> 10.36.5.105`, `network-node6-0 -> 10.36.4.143`, `network-node7-0 -> 10.36.6.45`. | `sampler:network_sampler_network-node1-0.log:3`, `sampler:network_sampler_network-node2-0.log:3`, `sampler:network_sampler_network-node3-0.log:3`, `sampler:network_sampler_network-node4-0.log:3`, `sampler:network_sampler_network-node5-0.log:3`, `sampler:network_sampler_network-node6-0.log:3`, `sampler:network_sampler_network-node7-0.log:3` |
| Baseline/restored state upload | absent | Solo node start skipped `Upload state files network nodes`, so this run did not start from an uploaded common baseline state. | `workflow:performance-tests-start.log:4778-4779` |
| Learner node | present | Learner was node ID `0`, represented by `network-node1_logs`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| Stopped pod | inferred | `network-node1-0`. No exact `stoppedPod=` script line was emitted, but the manifest records intended learner `network-node1-0` / node `0`; workflow `Stopping java` / `Down for downtime=1800` is followed by reconnect-loop sampling on `network-node1-0`; `network-node1_logs` maps to node ID `0`; learner `settingsUsed.txt` records `HOSTNAME, network-node1-0`; and the first receiver reconnect is logged by node `0`. | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration;run=top-to-bottom`, `workflow:performance-tests-watch.log:1721-1724`, `config:podlog_solo-mdlt-n3/network-node1_logs/config/settingsUsed.txt:950`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164` |
| Controlled warmtime, downtime, and loop count | present | `downtime=1800`, `warmtime=600`, and `NofLoops=0`; workflow then executed `profileReconnectLoopK8s.sh "solo-mdlt-n3" ${nlgpod} ${downtime} ${warmtime} ${NofLoops}`. | `workflow:performance-tests-watch.log:1496-1501`, `workflow:performance-tests-watch.log:1718-1722` |
| Workload profile | present | `LongevityLoadTest` with max TPS input `8000`, `24000000` accounts, and `6h` duration. | `config:version_run.txt:key=inputs.NLG_Test;line=9`, `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `workflow:performance-tests-start.log:41-44` |
| Transaction mix | present | Client was configured for 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and `transfer` for `PT6H`. | `log:client.log:2-9` |
| NLGArguments | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `24000000` accounts, `6h`; client config shows 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50%, 100000 topics, ECDSA, `transfer PT6H`. | `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `config:version_run.txt:key=inputs.NLG_Test;line=9`, `log:client.log:2-9` |
| configSummary | present | Learner config bundle under `podlog_solo-mdlt-n3/network-node1_logs/config/`; mode `pullTopToBottom`, host `network-node1-0`, with copied `settingsUsed.txt`, `application.properties`, `bootstrap.properties`, and `api-permission.properties`. | `config:podlog_solo-mdlt-n3/network-node1_logs/config/settingsUsed.txt:727`, `config:podlog_solo-mdlt-n3/network-node1_logs/config/settingsUsed.txt:950`, `config:podlog_solo-mdlt-n3/network-node1_logs/config/application.properties:5`, `config:podlog_solo-mdlt-n3/network-node1_logs/config/bootstrap.properties:6-9`, `config:podlog_solo-mdlt-n3/network-node1_logs/config/api-permission.properties:64` |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved to `BEHIND` after self-fallen-behind reports for peers. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:154-160` |
| First reconnect start | present | `2026-05-29 06:41:57.343` UTC, receiver node `0`, teacher peer `2`, round `16534`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| First reconnect end | present | `2026-05-29 06:45:00.693` UTC, receiver node `0`, teacher peer `2`, round `28936`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191-191` |
| Learner wall-clock reconnect duration | derived | `183.350 s` from learner start to learner finish. | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164,log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191-191` |
| Learner reconnect synchronization stage duration | present | `178.623 s`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:189-189` |
| Learner status transition after reconnect | present | `BEHIND -> RECONNECT_COMPLETE -> CHECKING -> ACTIVE`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:208-208`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:259-259`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:295-295` |
| Matching teacher node | present | Teacher peer `2` maps to `network-node3_logs`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164`, `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:397-397` |
| Teacher reconnect start | present | `2026-05-29 06:41:59.715` UTC, sender node `2`, receiver node `0`. | `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:397-397` |
| Teacher reconnect end | present | `2026-05-29 06:45:00.692` UTC, sender node `2`, receiver node `0`. | `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:433-433` |
| Teacher reconnect status | present | Teacher sender reconnect finished for receiver `0`. | `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:433-433` |
| Teacher wall-clock reconnect duration | derived | `180.977 s` from teacher start to teacher finish. | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:397-397,log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:433-433` |

## Learner Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner view path range before reconnect | present | `firstLeafPath=74090174`, `lastLeafPath=148180348`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:167-167` |
| Learner reinitialized path range | present | `firstLeafPath: 74090174 -> 81734058`, `lastLeafPath: 148180348 -> 163468116`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:171-171` |
| Learner flusher state range | present | `firstLeafPath=81734058`, `lastLeafPath=163468116`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:172-172` |
| Learner start state size | derived | `74090175` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:167-167` |
| Learner target/end state size | derived | `81734059` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:171-172` |
| Learner data received | present | `4494.547908782959 MiB`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:190-190` |
| Learner-side reconnect completion | present | Reconnect learner was complete and learner task finished before the receiver finish payload. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:185-191` |

## Teacher Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Teacher state send start | present | Teacher began sending state to receiver node `0`. | `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:398-398` |
| Teacher root response state range | present | `firstLeafPath=81734058`, `lastLeafPath=163468116`. | `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:412-412` |
| Teacher sent state size | derived | `81734059` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:412-412` |
| Teacher task execution | present | Teacher task output spans reconnect teaching and finished tree send. | `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:414-432` |
| Teacher sampled state size during window | derived | `vmap_size_state` increased from `81735051` to `82240241` over bounded teacher stats rows. | `derived:formula=first_and_last_vmap_size_state_in_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=vmap_size_state;rows=2650-2711;timestamp=2026-05-29 06:41:57 UTC..2026-05-29 06:45:00 UTC` |

## Reconnect Work-Shape Counters

| Counter | Status | Value | Source references |
|---|---:|---:|---|
| transfersFromTeacher | present | `89172265` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| transfersFromLearner | present | `88446368` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| internalHashes | present | `17842026` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| internalCleanHashes | present | `2523584` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| internalData | present | `17809423` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| internalCleanData | present | `2523343` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| leafHashes | present | `69949044` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| leafCleanHashes | present | `35651888` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| leafData | present | `71433927` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| leafCleanData | present | `36037864` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| internalDirtyHashes | derived | `15318442` | `derived:formula=internalHashes-internalCleanHashes;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| internalDirtyData | derived | `15286080` | `derived:formula=internalData-internalCleanData;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| leafDirtyHashes | derived | `34297156` | `derived:formula=leafHashes-leafCleanHashes;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |
| leafDirtyData | derived | `35396063` | `derived:formula=leafData-leafCleanData;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188` |

## Network Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Reconnect data lower-bound throughput | derived | `25.162 MiB/s`, approximately `211.076 Mbit/s`, from learner data volume divided by synchronization stage time. | `derived:formula=dataMiB/timeSeconds;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:189-190` |
| Teacher-to-learner stats throughput | derived | `bytes_per_sec_sent_00` over bounded teacher rows: average `22352583.89 B/s`, max `51816363.39 B/s` at row `2711`. | `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2650-2711;timestamp=2026-05-29 06:41:57 UTC..2026-05-29 06:45:00 UTC` |
| Teacher total stats throughput | derived | `bytes_per_sec_sent` average `24103340.74 B/s` over the same bounded rows. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent;rows=2650-2711;timestamp=2026-05-29 06:41:57 UTC..2026-05-29 06:45:00 UTC` |
| Stats ping from teacher to learner | derived | `ping_us_00` average `525.75 us` over bounded teacher rows. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2650-2711;timestamp=2026-05-29 06:41:57 UTC..2026-05-29 06:45:00 UTC` |
| Passive TCP sampler coverage during first reconnect | present | Teacher and reciprocal learner samplers have timestamped socket blocks spanning the first reconnect window. | `sampler:network_sampler_network-node3-0.log:27887-27888,28015-28016;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z`, `sampler:network_sampler_network-node3-0.log:35389-35390,35515-35516;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z`, `sampler:network_sampler_network-node1-0.log:8305-8306,8383-8384;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z`, `sampler:network_sampler_network-node1-0.log:10399-10400,10443-10444;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |
| Passive TCP/window fields | present | TCP samples include `Recv-Q`, `Send-Q`, `rtt`, `cwnd`, `ssthresh`, `bytes_sent`, `bytes_retrans`, `delivery_rate`, `rwnd_limited`, and `snd_wnd` for the attributed learner/teacher socket pair. | `sampler:network_sampler_network-node3-0.log:27887-27888,28015-28016;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z`, `sampler:network_sampler_network-node1-0.log:8305-8306,8383-8384;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |
| First-window TCP sample values | present | Attributed learner/teacher socket pair `[::ffff:10.36.24.218]:41024 <-> [::ffff:10.36.7.246]:50111`. Learner-side start sample at `06:41:57Z`: `Recv-Q=0`, `Send-Q=0`, `rtt=3.611/6.298`, `cwnd=10`, `bytes_sent=1923`, `bytes_received=2662`, `delivery_rate=151424832bps`, `snd_wnd=31744`. Learner-side end sample at `06:44:59Z`: `Recv-Q=12938354`, `Send-Q=1205840`, `rtt=0.244/0.068`, `cwnd=161`, `ssthresh=120`, `bytes_sent=6058178797`, `bytes_retrans=310935`, `bytes_received=5010532892`, `delivery_rate=4565458816bps`, `rwnd_limited=151024ms(88.8%)`, `snd_wnd=1024`. Teacher-side end cross-check at `06:45:00Z`: `rtt=5.745/11.152`, `cwnd=10`, `ssthresh=60`, `bytes_sent=5039032007`, `bytes_retrans=33694`, `bytes_received=6065725082`, `delivery_rate=735492056bps`, `rwnd_limited=109156ms(62.9%)`, `snd_wnd=31744`. | `sampler:network_sampler_network-node1-0.log:8305,8383-8384`, `sampler:network_sampler_network-node1-0.log:10399,10443-10444`, `sampler:network_sampler_network-node3-0.log:35389,35515-35516` |
| Direct learner/teacher socket attribution | present | First reconnect learner node `0` / `network-node1-0` maps to `10.36.24.218`; teacher node `2` / `network-node3-0` maps to `10.36.7.246`. The first-window socket pair is `[::ffff:10.36.24.218]:41024 <-> [::ffff:10.36.7.246]:50111`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164`, `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:397`, `config:podlog_solo-mdlt-n3/network-node1_logs/config/settingsUsed.txt:950`, `config:podlog_solo-mdlt-n3/network-node3_logs/config/settingsUsed.txt:950`, `sampler:network_sampler_network-node1-0.log:3`, `sampler:network_sampler_network-node3-0.log:3`, `sampler:network_sampler_network-node3-0.log:27887-27888,28015-28016`, `sampler:network_sampler_network-node1-0.log:8305-8306,8383-8384` |
| Top-level reconnect sampler attribution source | absent | `reconnect_network_samples_1.log` is empty. `reconnect_network_samples_1_summary.log` has unlabeled IP evidence and is usable only as a cross-check, not as a learner/teacher attribution source. | `derived:formula=wc_l_is_zero;inputs=sampler:reconnect_network_samples_1.log`, `sampler:reconnect_network_samples_1_summary.log:28099-28225`, `sampler:reconnect_network_samples_1_summary.log:35621-35837` |
| Full node metrics cross-check | present | Full stats CSVs are present for all seven node logs. Reconnect-window cross-check uses teacher node 2 stats for peer traffic, ping, and state growth, plus learner node 0 stats for service/store size. | `csv:podlog_solo-mdlt-n3/network-node1_logs/stats/MainNetStats0.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node2_logs/stats/MainNetStats1.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node4_logs/stats/MainNetStats3.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node5_logs/stats/MainNetStats4.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node6_logs/stats/MainNetStats5.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node7_logs/stats/MainNetStats6.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00,bytes_per_sec_sent,ping_us_00,vmap_size_state;rows=2650-2711;timestamp=2026-05-29 06:41:58 UTC..2026-05-29 06:44:58 UTC`, `csv:podlog_solo-mdlt-n3/network-node1_logs/stats/MainNetStats0.csv:column=accountsUsed,contractsUsed,nftsUsed,tokenAssociationsUsed,tokensUsed,topicsUsed;rows=1012-1072;timestamp=2026-05-29 06:41:58 UTC..2026-05-29 06:44:58 UTC` |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and transfer workload for `PT6H`. | `log:client.log:2-9` |
| Workload during reconnect | present | Client logs continue to show transactions and receipts during the reconnect interval. | `log:client.log:2018-2074` |
| Actual transaction rate during first reconnect window | present | During the bounded client-log window inside learner reconnect `2026-05-29 06:41:57.343..06:45:00.693` UTC, aggregate `WorkingQueue` samples report `TPS(current)` range `10358..10415` and `TPS(EMA)` range `10379..10380`; workload component samples include crypto transfers `4971..5028 TPS`, NFT transfers `2996..3003 TPS`, messages `1999 TPS`, and contract swaps `312 TPS`. | `log:client.log:2018-2073;window=2026-05-29 06:41:57.343 UTC..2026-05-29 06:45:00.693 UTC`; examples: aggregate min `TPS(current): 10358` at `log:client.log:2043-2043`, aggregate max `TPS(current): 10415` at `log:client.log:2046-2046`, crypto samples at `log:client.log:2021-2072`, NFT samples at `log:client.log:2027-2069`, message samples at `log:client.log:2028-2059`, contract swap sample at `log:client.log:2026-2026` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner start size | derived | `74090175` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:167-167` |
| Teacher target state size | derived | `81734059` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:412-412` |
| Learner target equals teacher target | derived | yes, learner target/end size `81734059` equals teacher target state size `81734059`. | `derived:formula=learner_target_size==teacher_target_size;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:171-172,log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:412-412` |
| State-size gap at reconnect | derived | `7643884` leaves between teacher target and learner start. | `derived:formula=teacher_target_size-learner_start_size;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:167-167,log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:412-412` |
| Teacher growth during reconnect window | derived | Bounded stats sampled `vmap_size_state` from `81735051` to `82240241`. | `derived:formula=first_and_last_vmap_size_state_in_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=vmap_size_state;rows=2650-2711;timestamp=2026-05-29 06:41:57 UTC..2026-05-29 06:45:00 UTC` |
| Service/store size metrics during reconnect window | present | Learner stats rows stayed at `accountsUsed=24000712`, `contractsUsed=6`, `nftsUsed=24000000`, `tokenAssociationsUsed=1796550`, `tokensUsed=1000`, `topicsUsed=100000`. | `csv:podlog_solo-mdlt-n3/network-node1_logs/stats/MainNetStats0.csv:column=accountsUsed,contractsUsed,nftsUsed,tokenAssociationsUsed,tokensUsed,topicsUsed;rows=1012-1072;timestamp=2026-05-29 06:41:58 UTC..2026-05-29 06:44:58 UTC` |
| Divergence shape | derived | Growth-heavy reconnect with substantial clean and dirty leaf work: `leafCleanData=36037864`, `leafDirtyData=35396063`, and a `7643884` leaf state-size gap. | `derived:formula=classify_from_state_gap_and_clean_dirty_counters;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-188,derived:formula=teacher_target_size-learner_start_size` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish, confirming the catch-up episode completed. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:295` |
| Iteration count | derived | `1` learner receiver reconnect iteration before `ACTIVE`. | `derived:formula=count_receiver_ReconnectStartPayload_before_ACTIVE;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164,log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:295` |
| Complete catch-up start | present | `2026-05-29 06:41:57.343` UTC. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164` |
| Complete catch-up end | present | `2026-05-29 06:45:00.693` UTC, the final learner receiver reconnect finish before `ACTIVE`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:295` |
| Complete catch-up duration | derived | `183.350 s`. | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164,log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191` |
| Active confirmation | present | `2026-05-29 06:54:45.837` UTC, `CHECKING -> ACTIVE`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:295` |
| Iteration 1 | present | Receiver node `0`, teacher peer `2`, start `2026-05-29 06:41:57.343` UTC, finish `2026-05-29 06:45:00.693` UTC, duration `183.350 s`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191`, `derived:formula=end_timestamp-start_timestamp` |
| Additional iterations observed | present | No additional learner receiver reconnect starts were found before `ACTIVE`. | Search scope: `podlog_solo-mdlt-n3/network-node1_logs/swirlds.log`; pattern: `ReconnectStartPayload`; source: `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164`; reason: only one learner receiver start matched before `ACTIVE`. |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTopToBottom` | `config:podlog_solo-mdlt-n3/network-node1_logs/config/settingsUsed.txt:727` |
| Manifest batch | present | `2026-05-29-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration` |
| Manifest run | present | `top-to-bottom` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration;run=top-to-bottom` |
| Commit | present | `796905b12784f90d8b12b9ee0d9a6a91de0e9b85` | `config:version_run.txt:key=hederahash;line=11` |
| Network disease preflight | pass | No fatal network disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| Teacher node | present | `2` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| First reconnect start UTC | present | `2026-05-29 06:41:57.343` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| First reconnect end UTC | present | `2026-05-29 06:45:00.693` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191-191` |
| Learner duration | derived | `183.350 s` | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164,log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191-191` |
| Teacher reconnect context present | present | yes | `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:397-433` |
| Reconnect stats present | present | yes | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-190` |
| Teacher/learner state size present | present | yes | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:167-172`, `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:412-412` |
| Workload profile present | present | yes | `log:client.log:2-9`, `log:client.log:2018-2074` |
| RTT evidence present | present | yes, via stats ping; passive socket endpoint attribution is resolved for the first learner/teacher pair. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2650-2711`, `sampler:network_sampler_network-node3-0.log:27887-27888,28015-28016;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z`, `sampler:network_sampler_network-node1-0.log:8305-8306,8383-8384;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |
| Bandwidth evidence present | present | yes, via learner data/time and teacher stats throughput. | `derived:formula=dataMiB/timeSeconds;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:189-190`, `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2650-2711` |
| TCP/window evidence present | present | Sampler fields are present during the window and attributed to the first learner/teacher socket pair. | `sampler:network_sampler_network-node3-0.log:27887-27888,28015-28016;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z`, `sampler:network_sampler_network-node1-0.log:8305-8306,8383-8384;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `1` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-05-29 06:41:57.343` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-05-29 06:45:00.693` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `183.350 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-05-29 06:54:45.837` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | no additional learner receiver reconnect observed before `ACTIVE`. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | yes | `derived:formula=protocol_acceptance_requires_sufficient_RTT_bandwidth_TCP_window_evidence;inputs=sampler:network_sampler_network-node3-0.log:27887-27888,28015-28016;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z,sampler:network_sampler_network-node1-0.log:8305-8306,8383-8384;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z,derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2650-2711,derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2650-2711` |
| Reason if not accepted | not_applicable | Re-evaluated with TCP endpoint attribution resolved; no first-window network-evidence blocker remains for this run. | `derived:formula=network_evidence_acceptance_after_endpoint_attribution_resolved;inputs=sampler:network_sampler_network-node3-0.log:27887-27888,28015-28016,sampler:network_sampler_network-node1-0.log:8305-8306,8383-8384` |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Timing and work-shape calibration | present | First reconnect timing, roles, counters, state sizes, and workload evidence are source-referenced. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-191`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-190`, `log:client.log:2-9` |
| Independent-run interpretation | present | Workflow logs confirm nominal reconnect controls and skipped restored-state upload. This run is a separate live-state calibration anchor; use its wall-clock duration with its own state size, gap, and work shape, and compare traversal modes locally only after reproducing comparable inputs. | `workflow:performance-tests-start.log:4778-4779`, `workflow:performance-tests-watch.log:1496-1501`, `derived:formula=teacher_target_size-learner_start_size;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:167-167,log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:412-412` |
| Network-inflight calibration | present | Stats throughput and ping are present, and passive TCP/window evidence is attributed to the first learner/teacher socket pair. | `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2650-2711`, `sampler:network_sampler_network-node3-0.log:27887-27888,28015-28016;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z`, `sampler:network_sampler_network-node1-0.log:8305-8306,8383-8384;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Exact stopped pod script output | inferred_not_direct | `version_run.txt`, `client.log`, top-level sampler files, `performance-tests-watch.log`, learner `settingsUsed.txt`, learner `swirlds.log` | `stoppedPod`, `stopped pod`, `network-node1-0`, `Stopping java`, `HOSTNAME`, `ReconnectStartPayload` | No literal script field records the stopped pod, but `stoppedPod` is inferable as `network-node1-0` from intended learner context, workflow stop/down timing, host config, artifact node mapping, and observed node `0` receiver reconnect. Keep as inferred rather than directly captured. |
| Additional learner receiver reconnect iterations | not_applicable | `podlog_solo-mdlt-n3/network-node1_logs/swirlds.log`; source `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-295` | `ReconnectStartPayload` before `ACTIVE` | Only one learner receiver reconnect was observed before `ACTIVE`, so the complete catch-up episode has one iteration. |
