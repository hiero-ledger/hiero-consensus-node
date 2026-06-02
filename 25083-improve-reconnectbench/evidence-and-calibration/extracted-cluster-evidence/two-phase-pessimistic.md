# Two-Phase Pessimistic Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect2_2phase/report`

All artifact paths below are relative to the artifact run root.

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
| Exact image tag or digest | missing | Version inputs are present, but no exact node image tag or digest was found in the documented run context files. | Search scope: `version_run.txt`, `client.log`, `podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt`; patterns: `image`, `tag`, `digest`; reason: no authoritative image key found. |
| Baseline restore identifier | missing | No baseline restore identifier was found in the documented run context files. | Search scope: `version_run.txt`, `client.log`, learner `settingsUsed.txt`; patterns: `baseline`, `restore`, `saved state`; reason: no authoritative baseline key found. |
| Network size | present | Seven configured node domains were present. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:60-108` |
| Learner node | present | Learner was node ID `0`, represented by `network-node1_logs`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| Stopped pod source | missing | The expected learner pod is inferred from the learner log directory, but the exact stopped-pod script output was not found. | Search scope: `version_run.txt`, `client.log`, top-level `reconnect_network_samples_1.log`; patterns: `stopped`, `delete`, `network-node1-0`; reason: no ordinary script line identifying the stopped pod. |
| Controlled warmtime, downtime, and loop count | missing | No controlled `warmtime`, `downtime`, or `NofLoops` values were found. | Search scope: `version_run.txt`, `client.log`, learner `settingsUsed.txt`; patterns: `warmtime`, `downtime`, `NofLoops`, `loop`; reason: no authoritative control keys found. |
| Workload profile | present | `LongevityLoadTest` with max TPS input `8000`, `24000000` accounts, and `6h` duration. | `config:version_run.txt:key=inputs.NLG_Test;line=9`, `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Transaction mix | present | Client was configured for 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and `transfer` for `PT6H`. | `log:client.log:2-9` |
| learnerCandidate | missing | No controlled/script `learnerCandidate` value was found. `settingsUsed.txt` records `HOSTNAME, network-node1-0` but marks it `[NOT USED IN RECORD]`, so it supports pod identity/config context, not an expected learner control value. Observed learner node `0` remains reconnect-role evidence. | Search scope: `version_run.txt`, `client.log`, learner `settingsUsed.txt`, `reconnect_network_samples_1.log`; patterns: `learnerCandidate`, `learner_candidate`, `learnerNode`, `expectedLearner`, `stoppedPod`, `network-node1-0`, `candidate`, `learner`; context source: `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:950`; observed role source: `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| teacherCandidate | missing | No expected/script-selected `teacherCandidate` value was found. Actual reconnect teacher selected by the protocol was peer `2`, but that is observed reconnect role evidence, not controlled run context. | Search scope: `version_run.txt`, `client.log`, learner `settingsUsed.txt`, `reconnect_network_samples_1.log`; patterns: `teacherCandidate`, `teacher_candidate`, `teacherNode`, `expectedTeacher`, `learnerCandidate`, `stoppedPod`; reason: no controlled teacher-candidate key found. Observed teacher source: `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| NLGArguments | present | `LongevityLoadTest`; `-Dbenchmark.maxtps=8000`; `NLG_Accounts=24000000`; `NLG_Time=6h`. Client config expands this to 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50%, 100000 topics, ECDSA keys, transfer `PT6H`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`, `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `log:client.log:2-9` |
| learnerBehindDuration | missing | No controlled/script `learnerBehindDuration` value was found. Observed learner log duration is `2.9 m` in `BEHIND`, from `18:26:08.575` to `18:29:05.078`, but this is runtime evidence, not a controlled input. | Search scope: `version_run.txt`, `client.log`, learner `settingsUsed.txt`, `reconnect_network_samples_1.log`; patterns: `learnerBehindDuration`, `behindDuration`, `fallenBehindDuration`, `behind_duration`, `learner_behind`, `warmtime`, `downtime`, `NofLoops`; reason: no controlled behind-duration key found. Observed duration source: `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:193-241` |
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
| Exact teacher reserved snapshot end size | missing | The teacher log gives the state sent to the learner and stats give sampled growth, but no exact reserved-state end snapshot was found. | Search scope: `podlog_solo-mdlt-n4/network-node3_logs/swirlds.log`, `MainNetStats2.csv`; patterns/columns: `Root has been flushed`, `VirtualMapMetadata`, `vmap_size_state`; reason: exact reserved-state end snapshot was not emitted for this reconnect window. |

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
| Passive TCP sampler coverage during first reconnect | missing | Teacher sampler has a timestamp at `2026-05-29T18:25:32Z`, then the next timestamp is `2026-05-29T18:35:32Z`; the first reconnect window `18:26:08Z..18:29:05Z` falls inside that gap. | `sampler:network_sampler_network-node3-0.log:8213-8213;window=before_first_reconnect`, `sampler:network_sampler_network-node3-0.log:8425-8425;window=after_first_reconnect` |
| Passive TCP/window fields | ambiguous | The sampler format has TCP fields outside the reconnect window, but no window-overlapping teacher sample was present for this run. | `sampler:network_sampler_network-node3-0.log:1-3;window=pre_reconnect`, `sampler:network_sampler_network-node3-0.log:8213-8425;window=gap_covering_first_reconnect` |
| Direct learner/teacher socket attribution | missing | No passive socket sample overlaps the first reconnect window, so direct socket attribution is unavailable. | `sampler:network_sampler_network-node3-0.log:8213-8213;window=before_first_reconnect`, `sampler:network_sampler_network-node3-0.log:8425-8425;window=after_first_reconnect` |
| Full node metrics cross-check | ambiguous | Stats CSVs for nodes 1-6 overlap the first reconnect window and expose reconnect counters, `time`, `bytes_per_sec_sent`, peer throughput/ping columns, and `vmap_size_state`; node 7 does not overlap the reconnect window, with available stats ending at `2026-05-29 17:15:30 UTC`. | `csv:podlog_solo-mdlt-n4/network-node1_logs/stats/MainNetStats0.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,time,vmap_size_state;rows=1203-1282;timestamp=2026-05-29 18:26:01 UTC..18:29:58 UTC`, `csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2953-3032;timestamp=2026-05-29 18:26:00 UTC..18:29:57 UTC`, `csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2952-3031;timestamp=2026-05-29 18:26:00 UTC..18:29:57 UTC`, `csv:podlog_solo-mdlt-n4/network-node4_logs/stats/MainNetStats3.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2953-3032;timestamp=2026-05-29 18:26:00 UTC..18:29:57 UTC`, `csv:podlog_solo-mdlt-n4/network-node5_logs/stats/MainNetStats4.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2952-3031;timestamp=2026-05-29 18:26:00 UTC..18:29:57 UTC`, `csv:podlog_solo-mdlt-n4/network-node6_logs/stats/MainNetStats5.csv:column=startsReconnectAsReceiver,startsReconnectAsSender,bytes_per_sec_sent,bytes_per_sec_sent_01..06,ping_us_01..06,time,vmap_size_state;rows=2953-3032;timestamp=2026-05-29 18:26:01 UTC..18:29:58 UTC`, `csv:podlog_solo-mdlt-n4/network-node7_logs/stats/MainNetStats6.csv:column=time;rows=786-1542;timestamp=2026-05-29 16:37:42 UTC..17:15:30 UTC`; search pattern for node7 overlap: `2026-05-29 18:26\|2026-05-29 18:29`; reason: no node7 stats rows overlap first reconnect window |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and transfer workload for `PT6H`. | `log:client.log:2-9` |
| Workload during reconnect | present | Client logs continue to show transactions and receipts during the reconnect interval, with `PLATFORM_NOT_ACTIVE` indicators also present. | `log:client.log:1959-2026` |
| Actual transaction rate during first reconnect | present | During `18:26:08.709..18:29:04.992 UTC`, client queue samples continue after the window start: transaction `TPS(current)` samples range `9297..9486` with `TPS(EMA)` `9441..9502`; receipt `TPS(current)` samples range `8882..9901` with `TPS(EMA)` `9466..9540`. Workload-specific samples include messages `1707..1716 TPS`, NFT transfers `2967..3010 TPS`, crypto transfers `4221..4343 TPS`, and one contract-swap sample at `313 TPS`. | `log:client.log:1962-2012;window=2026-05-29 18:26:08.709..18:29:04.992 UTC` |
| Full-run average transaction rate | missing | A full-run average was not derived for this extraction; only configured max TPS and reconnect-window client samples are recorded. | Search scope: `client.log`, stats CSV headers; patterns/columns: `TPS`, `transactions`, `receipts`; reason: extraction protocol requires source-referenced evidence and no unsourced aggregate was available without a separate documented derivation. |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner start size | derived | `73728940` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:200-200` |
| Teacher target state size | derived | `80174849` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| State-size gap at reconnect | derived | `6445909` leaves between teacher target and learner start. | `derived:formula=teacher_target_size-learner_start_size;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:200-200,log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| Teacher growth during reconnect window | derived | Bounded stats sampled `vmap_size_state` from `80181704` to `80684635`. | `derived:formula=first_and_last_vmap_size_state_in_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=vmap_size_state;rows=2955-3014;timestamp=2026-05-29 18:26:09 UTC..2026-05-29 18:29:06 UTC` |
| Service/store size metrics during reconnect window | present | Learner stats rows stayed at `accountsUsed=24000712`, `contractsUsed=6`, `nftsUsed=24000000`, `tokenAssociationsUsed=1469889`, `tokensUsed=1000`, `topicsUsed=100000`. | `csv:podlog_solo-mdlt-n4/network-node1_logs/stats/MainNetStats0.csv:column=accountsUsed,contractsUsed,nftsUsed,tokenAssociationsUsed,tokensUsed,topicsUsed;rows=1206-1264;timestamp=2026-05-29 18:26:10 UTC..2026-05-29 18:29:04 UTC` |
| Divergence shape | derived | Growth-heavy reconnect with a substantial dirty component: `leafCleanData=12846292`, `leafDirtyData=30002441`, and a `6445909` leaf state-size gap. | `derived:formula=classify_from_state_gap_and_clean_dirty_counters;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221,derived:formula=teacher_target_size-learner_start_size` |

## Later Reconnects

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Additional learner-side reconnect starts | present | No additional learner-side `ReconnectStartPayload` was found after the first receiver reconnect in this learner log. | Search scope: `podlog_solo-mdlt-n4/network-node1_logs/swirlds.log`; pattern: `ReconnectStartPayload`; source: `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197`; reason: only one learner receiver start matched. |
| Exclusion note | not_applicable | No later learner reconnect needs to be excluded from first traversal timing. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-224` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTwoPhasePessimistic` | `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:727` |
| Artifact directory | derived | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect2_2phase/report` | `derived:formula=artifact_root+runRoot;inputs=atlas:25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-atlas.md:14-18,atlas:25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-atlas.md:40-40` |
| Commit | present | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | `config:version_run.txt:key=hederahash;line=11` |
| Image | ambiguous | Version context present; exact image tag/digest missing. | `config:version_run.txt:key=inputs.hederaversion;line=2`, `config:version_run.txt:key=inputs.soloversion;line=3` |
| Learner node | present | `0` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| Teacher node | present | `2` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| First reconnect start UTC | present | `2026-05-29 18:26:08.709` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197` |
| First reconnect end UTC | present | `2026-05-29 18:29:04.992` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224-224` |
| Learner duration | derived | `176.283 s` | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:224-224` |
| Teacher reconnect context present | present | yes | `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:858-894` |
| Reconnect stats present | present | yes | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-223` |
| Teacher/learner state size present | present | yes | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:200-205`, `log:podlog_solo-mdlt-n4/network-node3_logs/swirlds.log:873-873` |
| Workload profile present | present | yes | `log:client.log:2-9`, `log:client.log:1959-2026` |
| RTT evidence present | present | yes, via stats ping; passive socket sample coverage is missing for the first reconnect window. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2955-3014`, `sampler:network_sampler_network-node3-0.log:8213-8425;window=gap_covering_first_reconnect` |
| Bandwidth evidence present | present | yes, via learner data/time and teacher stats throughput. | `derived:formula=dataMiB/timeSeconds;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:222-223`, `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2955-3014` |
| TCP/window evidence present | missing | No passive sampler block overlaps the first reconnect window. | `sampler:network_sampler_network-node3-0.log:8213-8213;window=before_first_reconnect`, `sampler:network_sampler_network-node3-0.log:8425-8425;window=after_first_reconnect` |
| Later reconnects observed | present | no later learner receiver reconnect observed. | Search scope: `podlog_solo-mdlt-n4/network-node1_logs/swirlds.log`; pattern: `ReconnectStartPayload`; source: `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197`; reason: only one learner receiver start matched. |
| Run accepted for calibration | derived | no | `derived:formula=protocol_acceptance_requires_sufficient_RTT_bandwidth_TCP_window_evidence;inputs=sampler:network_sampler_network-node3-0.log:8213-8213;window=before_first_reconnect,sampler:network_sampler_network-node3-0.log:8425-8425;window=after_first_reconnect,derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00;rows=2955-3014,derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2955-3014` |
| Reason if not accepted | derived | Passive TCP/window sampler coverage skips the first reconnect window, so network-inflight calibration is incomplete. | `derived:formula=missing_TCP_window_samples_for_first_reconnect;inputs=sampler:network_sampler_network-node3-0.log:8213-8213;window=before_first_reconnect,sampler:network_sampler_network-node3-0.log:8425-8425;window=after_first_reconnect` |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Timing and work-shape calibration | present | First reconnect timing, roles, counters, state sizes, and workload evidence are source-referenced. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-224`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-223`, `log:client.log:2-9` |
| Network-inflight calibration | missing | Stats throughput and ping are present; passive TCP/window samples do not overlap the first reconnect window. | `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=2955-3014`, `sampler:network_sampler_network-node3-0.log:8213-8425;window=gap_covering_first_reconnect` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Exact image tag or digest | missing | `version_run.txt`, `client.log`, learner `settingsUsed.txt` | `image`, `tag`, `digest` | Version inputs exist, but no authoritative image tag/digest key was found. |
| Baseline restore identifier | missing | `version_run.txt`, `client.log`, learner `settingsUsed.txt` | `baseline`, `restore`, `saved state` | No authoritative baseline key was found. |
| Exact stopped pod script output | missing | `version_run.txt`, `client.log`, top-level sampler file | `stopped`, `delete`, `network-node1-0` | Learner node is known from logs, but no script line records the stopped pod. |
| learnerCandidate controlled/script value | missing | `version_run.txt`, `client.log`, learner `settingsUsed.txt`, `reconnect_network_samples_1.log` | `learnerCandidate`, `learner_candidate`, `learnerNode`, `expectedLearner`, `stoppedPod`, `network-node1-0`, `candidate`, `learner` | Observed learner node `0` and hostname context exist, but no controlled expected learner-candidate key was found. |
| teacherCandidate controlled/script value | missing | `version_run.txt`, `client.log`, learner `settingsUsed.txt`, `reconnect_network_samples_1.log` | `teacherCandidate`, `teacher_candidate`, `teacherNode`, `expectedTeacher`, `learnerCandidate`, `stoppedPod` | Observed teacher peer `2` is logged, but no controlled expected teacher-candidate key was found. |
| learnerBehindDuration controlled/script value | missing | `version_run.txt`, `client.log`, learner `settingsUsed.txt`, `reconnect_network_samples_1.log` | `learnerBehindDuration`, `behindDuration`, `fallenBehindDuration`, `behind_duration`, `learner_behind`, `warmtime`, `downtime`, `NofLoops` | Observed learner log duration exists, but no controlled learner-behind duration key was found. |
| Controlled warmtime, downtime, loop count | missing | `version_run.txt`, `client.log`, learner `settingsUsed.txt` | `warmtime`, `downtime`, `NofLoops`, `loop` | No authoritative control values were found. |
| Full-run average transaction rate | missing | `client.log`, learner stats CSV headers | `TPS`, `transactions`, `receipts`, `trans_per_sec` | Configured max TPS and reconnect-window samples are recorded, but no source-referenced full-run average was derived in this phase. |
| Passive TCP/window fields | ambiguous | `network_sampler_network-node3-0.log` | TCP fields outside the reconnect window | The sampler format has TCP fields outside the reconnect window, but no window-overlapping teacher sample was present. |
| Full node metrics cross-check | ambiguous | `MainNetStats0.csv` through `MainNetStats6.csv` | reconnect counters, `bytes_per_sec_sent`, peer throughput/ping, `vmap_size_state`, `time` | Nodes 1-6 overlap the reconnect window, but node 7 stats do not overlap it. |
| Passive TCP/window samples during first reconnect | missing | `network_sampler_network-node3-0.log` | timestamp blocks around `2026-05-29T18:26:08Z..18:29:05Z` | Sampler jumps from `18:25:32Z` to `18:35:32Z`, skipping the first reconnect window. |
| Direct passive socket attribution | missing | `network_sampler_network-node3-0.log` | timestamp blocks and endpoint rows | No passive socket rows overlap the first reconnect window. |
| Exclusion note | not_applicable | `podlog_solo-mdlt-n4/network-node1_logs/swirlds.log` | `ReconnectStartPayload` | No later learner reconnect needs exclusion from first traversal timing; only the first receiver reconnect start was found at `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:197-197`. |
| Exact teacher reserved snapshot end size | missing | Teacher `swirlds.log`, `MainNetStats2.csv` | `Root has been flushed`, `VirtualMapMetadata`, `vmap_size_state` | Root response and sampled stats are available; exact reserved-state end snapshot was not emitted. |
