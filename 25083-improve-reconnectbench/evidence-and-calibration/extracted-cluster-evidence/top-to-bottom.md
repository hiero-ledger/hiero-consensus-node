# Top-To-Bottom Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect1`

All artifact paths below are relative to the artifact run root.

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
| Baseline/restored state upload | absent | Solo node start skipped `Upload state files network nodes`, so this run did not start from an uploaded common baseline state. | `workflow:performance-tests-start.log:4778-4779` |
| Learner node | present | Learner was node ID `0`, represented by `network-node1_logs`. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| Stopped pod source | ambiguous | Workflow output has a generic `Stopping java` marker, but no exact stopped-pod script line identifies the learner pod. | `workflow:performance-tests-watch.log:1721-1722`; search scope: `version_run.txt`, `client.log`, top-level `reconnect_network_samples_1.log`, `reconnect_network_samples_1_summary.log`, `performance-tests-watch.log`; patterns: `stopped`, `delete`, `network-node1-0`, `Stopping java`; reason: generic stop marker is present but exact pod identity is not. |
| Controlled warmtime, downtime, and loop count | present | `downtime=1800`, `warmtime=600`, and `NofLoops=0`; workflow then executed `profileReconnectLoopK8s.sh "solo-mdlt-n3" ${nlgpod} ${downtime} ${warmtime} ${NofLoops}`. | `workflow:performance-tests-watch.log:1496-1501`, `workflow:performance-tests-watch.log:1718-1722` |
| Workload profile | present | `LongevityLoadTest` with max TPS input `8000`, `24000000` accounts, and `6h` duration. | `config:version_run.txt:key=inputs.NLG_Test;line=9`, `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `workflow:performance-tests-start.log:41-44` |
| Transaction mix | present | Client was configured for 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and `transfer` for `PT6H`. | `log:client.log:2-9` |
| learnerCandidate | missing | Expected learner node ID was not found as a controlled/script value. Learner node `0` / `network-node1_logs` is observable from reconnect logs and artifact naming, but that is observed evidence rather than a controlled learner-candidate key. | Files checked: `version_run.txt`, `client.log`, `reconnect_network_samples_1.log`, `reconnect_network_samples_1_summary.log`, learner `settingsUsed.txt`; patterns checked: `learnerCandidate`, `learner candidate`, `stoppedPod`, `stopped pod`, `network-node1-0`, `node1-0`, `node 0`, `warmtime`, `downtime`, `NofLoops`, `learnerBehindDuration`; observed learner source: `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| teacherCandidate | missing | No run-control expected teacher candidate was found. Observed first reconnect teacher peer is node `2`, and matching sender log is `network-node3_logs`, but this is observed reconnect role evidence and remains separate from expected `teacherCandidate`. | Files checked: `version_run.txt`, `client.log`, `reconnect_network_samples_1.log`, `reconnect_network_samples_1_summary.log`, learner `settingsUsed.txt`; patterns checked: `teacherCandidate`, `teacher candidate`, `network-node3-0`, `node3-0`, `node 2`; observed teacher peer/source: `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164`, `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:397-397` |
| NLGArguments | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `24000000` accounts, `6h`; client config shows 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50%, 100000 topics, ECDSA, `transfer PT6H`. | `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `config:version_run.txt:key=inputs.NLG_Test;line=9`, `log:client.log:2-9` |
| learnerBehindDuration | missing | No controlled `learnerBehindDuration` value was found. The workflow has `downtime=1800`, but that is the scripted Java-down interval, not the observed platform `BEHIND` duration. Observed log-only platform `BEHIND` duration was `3.1 m`; first reconnect started at `06:41:57.343`. | `workflow:performance-tests-watch.log:1496-1501`, `workflow:performance-tests-watch.log:1721-1722`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:160-208`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
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
| Exact teacher reserved snapshot end size | missing | The teacher log gives the state sent to the learner and stats give sampled growth, but no exact reserved-state end snapshot was found. | Search scope: `podlog_solo-mdlt-n3/network-node3_logs/swirlds.log`, `MainNetStats2.csv`; patterns/columns: `Root has been flushed`, `VirtualMapMetadata`, `vmap_size_state`; reason: exact reserved-state end snapshot was not emitted for this reconnect window. |

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
| Passive TCP sampler coverage during first reconnect | present | Teacher sampler has timestamped blocks spanning the first reconnect window. | `sampler:network_sampler_network-node3-0.log:27887-27893;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z`, `sampler:network_sampler_network-node3-0.log:35389-35395;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |
| Passive TCP/window fields | ambiguous | TCP samples include `Recv-Q`, `Send-Q`, `rtt`, `cwnd`, `ssthresh`, `bytes_sent`, `bytes_retrans`, `delivery_rate`, `rwnd_limited`, and `snd_wnd`, but the socket endpoint IPs were not linked to node IDs by an authoritative artifact. | `sampler:network_sampler_network-node3-0.log:27887-27893;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z`; search scope: run-local client log and sampler files; patterns: pod IPs and `10.36.*`; reason: no IP-to-node mapping source found. |
| Direct learner/teacher socket attribution | ambiguous | Stats columns link teacher node `2` to learner node `0`, but passive socket rows cannot be attributed to that pair without IP mapping. | `derived:formula=teacher_stats_column_mapping_from_node_ids;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164,csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2650-2711`, `sampler:network_sampler_network-node3-0.log:27887-27893;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |
| Full node metrics cross-check | present | Full stats CSVs are present for all seven node logs. Reconnect-window cross-check uses teacher node 2 stats for peer traffic, ping, and state growth, plus learner node 0 stats for service/store size. | `csv:podlog_solo-mdlt-n3/network-node1_logs/stats/MainNetStats0.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node2_logs/stats/MainNetStats1.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node4_logs/stats/MainNetStats3.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node5_logs/stats/MainNetStats4.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node6_logs/stats/MainNetStats5.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node7_logs/stats/MainNetStats6.csv:column=time;row=1`, `csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00,bytes_per_sec_sent,ping_us_00,vmap_size_state;rows=2650-2711;timestamp=2026-05-29 06:41:58 UTC..2026-05-29 06:44:58 UTC`, `csv:podlog_solo-mdlt-n3/network-node1_logs/stats/MainNetStats0.csv:column=accountsUsed,contractsUsed,nftsUsed,tokenAssociationsUsed,tokensUsed,topicsUsed;rows=1012-1072;timestamp=2026-05-29 06:41:58 UTC..2026-05-29 06:44:58 UTC` |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and transfer workload for `PT6H`. | `log:client.log:2-9` |
| Workload during reconnect | present | Client logs continue to show transactions and receipts during the reconnect interval. | `log:client.log:2018-2074` |
| Actual transaction rate during first reconnect window | present | During the bounded client-log window inside learner reconnect `2026-05-29 06:41:57.343..06:45:00.693` UTC, aggregate `WorkingQueue` samples report `TPS(current)` range `10358..10415` and `TPS(EMA)` range `10379..10380`; workload component samples include crypto transfers `4971..5028 TPS`, NFT transfers `2996..3003 TPS`, messages `1999 TPS`, and contract swaps `312 TPS`. | `log:client.log:2018-2073;window=2026-05-29 06:41:57.343 UTC..2026-05-29 06:45:00.693 UTC`; examples: aggregate min `TPS(current): 10358` at `log:client.log:2043-2043`, aggregate max `TPS(current): 10415` at `log:client.log:2046-2046`, crypto samples at `log:client.log:2021-2072`, NFT samples at `log:client.log:2027-2069`, message samples at `log:client.log:2028-2059`, contract swap sample at `log:client.log:2026-2026` |
| Full-run average transaction rate | missing | A full-run average was not derived for this extraction; only configured max TPS and reconnect-window client samples are recorded. | Search scope: `client.log`, stats CSV headers; patterns/columns: `TPS`, `transactions`, `receipts`; reason: extraction protocol requires source-referenced evidence and no unsourced aggregate was available without a separate documented derivation. |

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

## Later Reconnects

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Additional learner-side reconnect starts | present | No additional learner-side `ReconnectStartPayload` was found after the first receiver reconnect in this learner log. | Search scope: `podlog_solo-mdlt-n3/network-node1_logs/swirlds.log`; pattern: `ReconnectStartPayload`; source: `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164`; reason: only one learner receiver start matched. |
| Exclusion note | not_applicable | No later learner reconnect needs to be excluded from first traversal timing. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-191` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTopToBottom` | `config:podlog_solo-mdlt-n3/network-node1_logs/config/settingsUsed.txt:727` |
| Artifact directory | derived | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect1` | `derived:formula=artifact_root+runRoot;inputs=atlas:25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-atlas.md:14-18,atlas:25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-atlas.md:39-39` |
| Commit | present | `796905b12784f90d8b12b9ee0d9a6a91de0e9b85` | `config:version_run.txt:key=hederahash;line=11` |
| Learner node | present | `0` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| Teacher node | present | `2` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| First reconnect start UTC | present | `2026-05-29 06:41:57.343` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164` |
| First reconnect end UTC | present | `2026-05-29 06:45:00.693` | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191-191` |
| Learner duration | derived | `183.350 s` | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164,log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:191-191` |
| Teacher reconnect context present | present | yes | `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:397-433` |
| Reconnect stats present | present | yes | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-190` |
| Teacher/learner state size present | present | yes | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:167-172`, `log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:412-412` |
| Workload profile present | present | yes | `log:client.log:2-9`, `log:client.log:2018-2074` |
| RTT evidence present | present | yes, via stats ping; direct passive socket attribution remains ambiguous. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2650-2711`, `sampler:network_sampler_network-node3-0.log:27887-27893;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |
| Bandwidth evidence present | present | yes, via learner data/time and teacher stats throughput. | `derived:formula=dataMiB/timeSeconds;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:189-190`, `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2650-2711` |
| TCP/window evidence present | ambiguous | Sampler fields are present during the window, but endpoint-to-node attribution is unresolved. | `sampler:network_sampler_network-node3-0.log:27887-27893;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |
| Later reconnects observed | present | no later learner receiver reconnect observed. | Search scope: `podlog_solo-mdlt-n3/network-node1_logs/swirlds.log`; pattern: `ReconnectStartPayload`; source: `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-164`; reason: only one learner receiver start matched. |
| Run accepted for calibration | derived | no | `derived:formula=protocol_acceptance_requires_sufficient_RTT_bandwidth_TCP_window_evidence;inputs=sampler:network_sampler_network-node3-0.log:27887-27893;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z,derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2650-2711,derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2650-2711` |
| Reason if not accepted | derived | Passive TCP/window fields are present during the reconnect window, but direct learner/teacher socket attribution is unresolved, so network-inflight calibration is incomplete. | `derived:formula=insufficient_TCP_window_attribution_for_network_inflight_calibration;inputs=sampler:network_sampler_network-node3-0.log:27887-27893;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Timing and work-shape calibration | present | First reconnect timing, roles, counters, state sizes, and workload evidence are source-referenced. | `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-191`, `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:188-190`, `log:client.log:2-9` |
| Cross-mode state comparability | low | Workflow logs confirm the nominal reconnect controls, but also show no uploaded restored baseline state. This run's wall-clock duration should be compared only with runs that reproduce the same state size, gap, and work shape. | `workflow:performance-tests-start.log:4778-4779`, `workflow:performance-tests-watch.log:1496-1501`, `derived:formula=teacher_target_size-learner_start_size;inputs=log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:167-167,log:podlog_solo-mdlt-n3/network-node3_logs/swirlds.log:412-412` |
| Network-inflight calibration | ambiguous | Stats throughput and ping are present; passive TCP/window evidence is present but not linkable to learner/teacher sockets by node ID. | `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n3/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2650-2711`, `sampler:network_sampler_network-node3-0.log:27887-27893;window=2026-05-29T06:41:57Z..2026-05-29T06:45:00Z` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Exact stopped pod script output | ambiguous | `version_run.txt`, `client.log`, top-level sampler files, `performance-tests-watch.log` | `stopped`, `delete`, `network-node1-0`, `Stopping java` | Learner node is known from logs and a generic stop marker exists, but no script line records the exact stopped pod. |
| learnerCandidate controlled/script value | missing | `version_run.txt`, `client.log`, `reconnect_network_samples_1.log`, `reconnect_network_samples_1_summary.log`, learner `settingsUsed.txt` | `learnerCandidate`, `learner candidate`, `stoppedPod`, `network-node1-0`, `node 0` | Observed learner node `0` is logged, but no controlled expected learner-candidate key was found. |
| teacherCandidate controlled/script value | missing | `version_run.txt`, `client.log`, `reconnect_network_samples_1.log`, `reconnect_network_samples_1_summary.log`, learner `settingsUsed.txt` | `teacherCandidate`, `teacher candidate`, `network-node3-0`, `node 2` | Observed teacher peer `2` is logged, but no controlled expected teacher-candidate key was found. |
| learnerBehindDuration controlled/script value | missing | `version_run.txt`, `client.log`, reconnect sample logs, learner `settingsUsed.txt`, `performance-tests-watch.log` | `learnerBehindDuration`, `behind duration`, `warmtime`, `downtime`, `NofLoops` | `downtime`, `warmtime`, and `NofLoops` are now known, but no controlled learner-behind duration key was found. |
| Full-run average transaction rate | missing | `client.log`, learner stats CSV headers | `TPS`, `transactions`, `receipts`, `trans_per_sec` | Configured max TPS and reconnect-window samples are recorded, but no source-referenced full-run average was derived in this phase. |
| Direct passive socket attribution | ambiguous | `network_sampler_network-node3-0.log`, run-local client log, sampler files | `10.36.*`, endpoint IPs | TCP rows use IP endpoints and no IP-to-node mapping source was found. |
| Exclusion note for later learner reconnect timing | not_applicable | `podlog_solo-mdlt-n3/network-node1_logs/swirlds.log`; source `log:podlog_solo-mdlt-n3/network-node1_logs/swirlds.log:164-191` | `ReconnectStartPayload` after first receiver window | No later learner receiver reconnect was observed, so no later reconnect needs exclusion from first traversal timing. |
| Exact teacher reserved snapshot end size | missing | Teacher `swirlds.log`, `MainNetStats2.csv` | `Root has been flushed`, `VirtualMapMetadata`, `vmap_size_state` | Root response and sampled stats are available; exact reserved-state end snapshot was not emitted. |
