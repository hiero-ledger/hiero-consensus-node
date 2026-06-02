# Parallel Sync Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect3_PullParallelSync/report`

All artifact paths below are relative to the artifact run root.

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullParallelSync` | `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:727`, `config:version_run.txt:key=inputs.AddSettings;line=8` |
| Namespace | present | `MDLT4` | `config:version_run.txt:key=namespace;line=1` |
| Hedera version input | present | `main` | `config:version_run.txt:key=inputs.hederaversion;line=2` |
| Solo chart/version input | present | `latest_tested_solo-charts0.59_balanced` | `config:version_run.txt:key=inputs.soloversion;line=3` |
| Commit hash | present | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | `config:version_run.txt:key=hederahash;line=11` |
| Run number | present | `296` | `config:version_run.txt:key=run_number;line=12` |
| Job URL | present | `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/26660952751` | `config:version_run.txt:key=JOB_URL;line=10` |
| Exact image tag or digest | missing | Version inputs are present, but no exact node image tag or digest was found in the documented run context files. | Search scope: `version_run.txt`, `client.log`, `podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt`; patterns: `image`, `tag`, `digest`; reason: no authoritative image key found. |
| Baseline restore identifier | missing | No baseline restore identifier was found in the documented run context files. | Search scope: `version_run.txt`, `client.log`, learner `settingsUsed.txt`; patterns: `baseline`, `restore`, `saved state`; reason: no authoritative baseline key found. |
| Network size | present | Seven configured node domains were present. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:60-108` |
| Learner node | present | Learner was node ID `0`, represented by `network-node1_logs`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| Stopped pod source | ambiguous | Top-level sampler output records a sampler stop marker, but no exact stopped-pod script line was found. | `sampler:reconnect_network_samples_1.log:1-2;window=run_sampler`; search scope: `version_run.txt`, `client.log`, top-level sampler file; patterns: `stopped`, `delete`, `network-node1-0`; reason: sampler stop marker is not the same as stopped learner pod control evidence. |
| Controlled warmtime, downtime, and loop count | missing | No controlled `warmtime`, `downtime`, or `NofLoops` values were found. | Search scope: `version_run.txt`, `client.log`, learner `settingsUsed.txt`; patterns: `warmtime`, `downtime`, `NofLoops`, `loop`; reason: no authoritative control keys found. |
| Workload profile | present | `LongevityLoadTest` with max TPS input `8000`, `24000000` accounts, and `6h` duration. | `config:version_run.txt:key=inputs.NLG_Test;line=9`, `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Transaction mix | present | Client was configured for 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and `transfer` for `PT6H`. | `log:client.log:2-9` |
| learnerCandidate expected learner node id | ambiguous | No authoritative controlled/script `learnerCandidate` value was found. `network-node1-0` appears as sampler/host context, and the observed learner was node `0`, but neither is an explicit expected learner control key. | Search scope: `version_run.txt`, `client.log`, `reconnect_network_samples_1.log`, learner `settingsUsed.txt`; patterns: `learnerCandidate`, `learner candidate`, `expected learner`, `stoppedPod`, `stopped pod`, `network-node1-0`, `warmtime`, `downtime`, `NofLoops`; context refs: `sampler:reconnect_network_samples_1.log:1-2;window=run_sampler`, `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:key=HOSTNAME;line=950`, observed learner `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| teacherCandidate | missing | No expected/control teacher candidate was found in run context. Observed first reconnect teacher peer was `1`, but that is reconnect-window evidence, not a run-control candidate. | Search scope: run-root non-CSV artifacts, `version_run.txt`, `client.log`, `reconnect_network_samples_1.log`, learner `settingsUsed.txt`; patterns: `teacherCandidate`, `teacher candidate`; reason: no authoritative run-control teacher-candidate key found. Observed source: `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213`. |
| NLGArguments | present | `NLG_Test=LongevityLoadTest`, `NLGDparams=-Dbenchmark.maxtps=8000`, `NLG_Accounts=24000000`, `NLG_Time=6h`; client config confirms 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50%, 100000 topics, ECDSA keys, and `transfer PT6H`. | `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `config:version_run.txt:key=inputs.NLG_Test;line=9`, `log:client.log:2-9` |
| learnerBehindDuration | missing | No controlled learner-behind duration was found. Observed first learner BEHIND interval was logged as `6.2 m`, from `22:50:04.408` BEHIND to `22:56:15.927` RECONNECT_COMPLETE, approximately `371.519 s`. | Search scope: run-root non-CSV artifacts; patterns: `learnerBehindDuration`, `behind duration`, `warmtime`, `downtime`, `NofLoops`; reason: no authoritative control key found. Observed sources: `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:206-206`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:257-257`. |
| configSummary | present | Learner config identifier: `podlog_solo-mdlt-n4/network-node1_logs/config`, with `virtualMap.reconnectMode=pullParallelSync`, app `contracts.chainId=298` / `ledger.id=0x03`, and bootstrap `netty.mode=DEV` / `contracts.chainId=298`. | `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:727`, `config:podlog_solo-mdlt-n4/network-node1_logs/config/application.properties:1-2`, `config:podlog_solo-mdlt-n4/network-node1_logs/config/bootstrap.properties:2-5` |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved to `BEHIND` after self-fallen-behind reports for peers. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:204-209` |
| First reconnect start | present | `2026-05-29 22:50:04.496` UTC, receiver node `0`, teacher peer `1`, round `21501`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| First reconnect end | present | `2026-05-29 22:56:15.846` UTC, receiver node `0`, teacher peer `1`, round `30077`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:240-240` |
| Learner wall-clock reconnect duration | derived | `371.350 s` from learner start to learner finish. | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:240-240` |
| Learner reconnect synchronization stage duration | present | `366.69 s`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:238-238` |
| First reconnect status transition | present | `BEHIND -> RECONNECT_COMPLETE`, then the learner immediately fell behind again before returning to active later. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:257-281`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:388-448` |
| Matching first teacher node | present | Teacher peer `1` maps to `network-node2_logs`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213`, `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1030-1030` |
| First teacher reconnect start | present | `2026-05-29 22:50:06.933` UTC, sender node `1`, receiver node `0`. | `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1030-1030` |
| First teacher reconnect end | present | `2026-05-29 22:56:15.845` UTC, sender node `1`, receiver node `0`. | `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1075-1075` |
| First teacher reconnect status | present | Teacher sender reconnect finished for receiver `0`. | `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1075-1075` |
| First teacher wall-clock reconnect duration | derived | `368.912 s` from teacher start to teacher finish. | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1030-1030,log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1075-1075` |

## Learner Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner view path range before reconnect | present | `firstLeafPath=73886544`, `lastLeafPath=147773088`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:216-216` |
| Learner reinitialized path range | present | `firstLeafPath: 73886544 -> 78191109`, `lastLeafPath: 147773088 -> 156382218`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:220-220` |
| Learner flusher state range | present | `firstLeafPath=78191109`, `lastLeafPath=156382218`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:221-221` |
| Learner start state size | derived | `73886545` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:216-216` |
| Learner target/end state size | derived | `78191110` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:220-221` |
| Learner data received | present | `2992.621027946472 MiB`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:239-239` |
| Learner-side reconnect completion | present | First reconnect learner was complete and learner task finished before the receiver finish payload. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:234-240` |

## Teacher Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| First teacher state send start | present | Teacher began sending state to receiver node `0`. | `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1031-1031` |
| First teacher root response state range | present | `firstLeafPath=78191109`, `lastLeafPath=156382218`. | `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1045-1045` |
| First teacher sent state size | derived | `78191110` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1045-1045` |
| First teacher task execution | present | Teacher task output spans reconnect teaching and finished tree send. | `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1056-1074` |
| First teacher sampled state size during window | derived | `vmap_size_state` increased from `78191036` to `78832616` over bounded teacher stats rows. | `derived:formula=first_and_last_vmap_size_state_in_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=vmap_size_state;rows=2998-3122;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC` |
| Exact teacher reserved snapshot end size | missing | The teacher log gives the state sent to the learner and stats give sampled growth, but no exact reserved-state end snapshot was found. | Search scope: `podlog_solo-mdlt-n4/network-node2_logs/swirlds.log`, `MainNetStats1.csv`; patterns/columns: `Root has been flushed`, `VirtualMapMetadata`, `vmap_size_state`; reason: exact reserved-state end snapshot was not emitted for this reconnect window. |

## Reconnect Work-Shape Counters

| Counter | Status | Value | Source references |
|---|---:|---:|---|
| transfersFromTeacher | present | `72305133` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| transfersFromLearner | present | `71951130` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| internalHashes | present | `41203657` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| internalCleanHashes | present | `20245450` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| internalData | present | `41269230` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| internalCleanData | present | `20264712` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| leafHashes | present | `30469255` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| leafCleanHashes | present | `10099185` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| leafData | present | `31124718` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| leafCleanData | present | `10163254` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| internalDirtyHashes | derived | `20958207` | `derived:formula=internalHashes-internalCleanHashes;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| internalDirtyData | derived | `21004518` | `derived:formula=internalData-internalCleanData;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| leafDirtyHashes | derived | `20370070` | `derived:formula=leafHashes-leafCleanHashes;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |
| leafDirtyData | derived | `20961464` | `derived:formula=leafData-leafCleanData;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237` |

## Network Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Reconnect data lower-bound throughput | derived | `8.161 MiB/s`, approximately `68.461 Mbit/s`, from learner data volume divided by synchronization stage time. | `derived:formula=dataMiB/timeSeconds;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:238-239` |
| Teacher-to-learner stats throughput | derived | `bytes_per_sec_sent_00` over bounded first-teacher rows: average `7202969.59 B/s`, max `39370697.56 B/s` at row `3122`. | `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent_00;rows=2998-3122;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC` |
| Teacher total stats throughput | derived | `bytes_per_sec_sent` average `8835060.05 B/s` over the same bounded rows. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent;rows=2998-3122;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC` |
| Stats ping from teacher to learner | derived | `ping_us_00` average `647.14 us` over bounded first-teacher rows. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=ping_us_00;rows=2998-3122;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC` |
| Passive TCP sampler coverage during first reconnect | ambiguous | First-teacher sampler has timestamped blocks from reconnect start through `22:55:33Z`, but no later `22:56` block was found in that sampler. | `sampler:network_sampler_network-node2-0.log:17455-17461;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`, `sampler:network_sampler_network-node2-0.log:29325-30507;window=2026-05-29T22:55:05Z..2026-05-29T22:55:33Z` |
| Passive TCP/window fields | ambiguous | TCP samples include `Recv-Q`, `Send-Q`, `rtt`, `cwnd`, `ssthresh`, `bytes_sent`, `bytes_retrans`, `delivery_rate`, `rwnd_limited`, and `snd_wnd`, but endpoint IPs were not linked to node IDs by an authoritative artifact. | `sampler:network_sampler_network-node2-0.log:17455-17461;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`; search scope: run-local client log and sampler files; patterns: pod IPs and `10.36.*`; reason: no IP-to-node mapping source found. |
| Direct learner/teacher socket attribution | ambiguous | Stats columns link teacher node `1` to learner node `0`, but passive socket rows cannot be attributed to that pair without IP mapping. | `derived:formula=teacher_stats_column_mapping_from_node_ids;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213,csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent_00;rows=2998-3122`, `sampler:network_sampler_network-node2-0.log:17455-17461;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z` |
| Full node metrics cross-check | present | Full-node stats CSVs are present for all seven nodes and expose total `bytes_per_sec_sent`; teacher node `1` also exposes learner-directed `bytes_per_sec_sent_00`, `ping_us_00`, and `vmap_size_state` for first-window cross-checking of peer traffic, RTT, and state shape. | `csv:podlog_solo-mdlt-n4/network-node1_logs/stats/MainNetStats0.csv:column=bytes_per_sec_sent;rows=1281-1404;timestamp=2026-05-29 22:50:05 UTC..2026-05-29 22:56:14 UTC`, `csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent,bytes_per_sec_sent_00,ping_us_00,vmap_size_state;rows=2998-3122;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node4_logs/stats/MainNetStats3.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node5_logs/stats/MainNetStats4.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node6_logs/stats/MainNetStats5.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node7_logs/stats/MainNetStats6.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC` |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and transfer workload for `PT6H`. | `log:client.log:2-9` |
| Workload during reconnect | present | Client logs continue to show transactions and receipts during the first reconnect interval, with `PLATFORM_NOT_ACTIVE`, `RECEIPT_NOT_FOUND`, and `THROTTLED_AT_CONSENSUS` indicators also present. | `log:client.log:2173-2311` |
| Actual transaction rate during first reconnect | present | Client workload stayed active inside `2026-05-29 22:50:04.496..22:56:15.846` UTC. WorkingQueue samples in-window show transaction `TPS(current)` range `2928..10708` and receipt `TPS(current)` range `5191..18628`; workload-specific samples include crypto transfers `1444..4936 TPS`, NFT transfers `1105..3018 TPS`, submitted messages `806..1758 TPS`, and contract swaps sample `318 TPS`. | `log:client.log:2175-2311`; range anchors: transaction min/max `log:client.log:2271-2271`, `log:client.log:2219-2219`; receipt min/max `log:client.log:2267-2267`, `log:client.log:2251-2251`; workload-specific samples `log:client.log:2174-2304`; window=`2026-05-29 22:50:04.496 UTC..2026-05-29 22:56:15.846 UTC` |
| Full-run average transaction rate | missing | A full-run average was not derived for this extraction; only configured max TPS and reconnect-window client samples are recorded. | Search scope: `client.log`, stats CSV headers; patterns/columns: `TPS`, `transactions`, `receipts`; reason: extraction protocol requires source-referenced evidence and no unsourced aggregate was available without a separate documented derivation. |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner start size | derived | `73886545` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:216-216` |
| First teacher target state size | derived | `78191110` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1045-1045` |
| State-size gap at first reconnect | derived | `4304565` leaves between first teacher target and learner start. | `derived:formula=teacher_target_size-learner_start_size;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:216-216,log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1045-1045` |
| Teacher growth during first reconnect window | derived | Bounded stats sampled `vmap_size_state` from `78191036` to `78832616`. | `derived:formula=first_and_last_vmap_size_state_in_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=vmap_size_state;rows=2998-3122;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC` |
| Service/store size metrics during first reconnect window | present | Learner stats rows stayed at `accountsUsed=24000712`, `contractsUsed=6`, `nftsUsed=24000000`, `tokenAssociationsUsed=1611530`, `tokensUsed=1000`, `topicsUsed=100000`. | `csv:podlog_solo-mdlt-n4/network-node1_logs/stats/MainNetStats0.csv:column=accountsUsed,contractsUsed,nftsUsed,tokenAssociationsUsed,tokensUsed,topicsUsed;rows=1281-1404;timestamp=2026-05-29 22:50:05 UTC..2026-05-29 22:56:14 UTC` |
| Divergence shape | derived | Growth-heavy reconnect with immediate later growth pressure: first gap `4304565` leaves, first `leafCleanData=10163254`, first `leafDirtyData=20961464`, and a second reconnect occurred immediately after the first. | `derived:formula=classify_from_state_gap_clean_dirty_counters_and_later_reconnect;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-237,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:276-320` |

## Later Reconnects

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Additional learner-side reconnect starts | present | A second learner receiver reconnect began immediately after the first, with teacher peer `4`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:276-285` |
| Second reconnect learner window | present | Start `2026-05-29 22:56:19.941` UTC, end `2026-05-29 22:59:43.644` UTC, learner duration `203.703 s`, synchronization stage `199.133 s`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:285-320`, `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:285-285,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:320-320` |
| Second reconnect state range | present | Learner range moved from `78191109..156382218` to `78835071..157670142`; second target size `78835072` leaves. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:296-301`, `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:300-301` |
| Second reconnect counters | present | `transfersFromTeacher=32022597`, `transfersFromLearner=31809618`, `internalHashes=25705348`, `leafData=6356666`, `leafCleanData=2492169`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:317-317` |
| Second reconnect teacher | present | Teacher peer `4` maps to `network-node5_logs`; sender finished the second reconnect. | `log:podlog_solo-mdlt-n4/network-node5_logs/swirlds.log:1044-1095` |
| First traversal exclusion | present | The second reconnect is recorded as later evidence and excluded from first traversal timing. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-240`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:285-320` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullParallelSync` | `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:727` |
| Artifact directory | derived | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect3_PullParallelSync/report` | `derived:formula=artifact_root+runRoot;inputs=atlas:25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-atlas.md:14-18,atlas:25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-atlas.md:41-41` |
| Commit | present | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | `config:version_run.txt:key=hederahash;line=11` |
| Image | ambiguous | Version context present; exact image tag/digest missing. | `config:version_run.txt:key=inputs.hederaversion;line=2`, `config:version_run.txt:key=inputs.soloversion;line=3` |
| Learner node | present | `0` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| Teacher node | present | `1` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| First reconnect start UTC | present | `2026-05-29 22:50:04.496` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| First reconnect end UTC | present | `2026-05-29 22:56:15.846` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:240-240` |
| Learner duration | derived | `371.350 s` | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:240-240` |
| Teacher reconnect context present | present | yes | `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1030-1075` |
| Reconnect stats present | present | yes | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-239` |
| Teacher/learner state size present | present | yes | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:216-221`, `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1045-1045` |
| Workload profile present | present | yes | `log:client.log:2-9`, `log:client.log:2173-2311` |
| RTT evidence present | present | yes, via stats ping; direct passive socket attribution remains ambiguous. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=ping_us_00;rows=2998-3122`, `sampler:network_sampler_network-node2-0.log:17455-17461;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z` |
| Bandwidth evidence present | present | yes, via learner data/time and teacher stats throughput. | `derived:formula=dataMiB/timeSeconds;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:238-239`, `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent_00;rows=2998-3122` |
| TCP/window evidence present | ambiguous | Sampler fields are present through most of the first window, but endpoint-to-node attribution and end-of-window coverage are unresolved. | `sampler:network_sampler_network-node2-0.log:17455-17461;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`, `sampler:network_sampler_network-node2-0.log:29325-30507;window=2026-05-29T22:55:05Z..2026-05-29T22:55:33Z` |
| Later reconnects observed | present | second learner receiver reconnect observed and excluded from first traversal timing. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:276-320` |
| Run accepted for calibration | derived | no | `derived:formula=protocol_acceptance_requires_sufficient_RTT_bandwidth_TCP_window_evidence;inputs=sampler:network_sampler_network-node2-0.log:17455-17461;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z,sampler:network_sampler_network-node2-0.log:29325-30507;window=2026-05-29T22:55:05Z..2026-05-29T22:55:33Z,derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=ping_us_00;rows=2998-3122,derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent_00;rows=2998-3122` |
| Reason if not accepted | derived | Passive TCP/window evidence is partial and endpoint-to-node attribution is unresolved, so network-inflight calibration is incomplete. | `derived:formula=insufficient_TCP_window_attribution_and_partial_end_window_coverage;inputs=sampler:network_sampler_network-node2-0.log:17455-17461;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z,sampler:network_sampler_network-node2-0.log:29325-30507;window=2026-05-29T22:55:05Z..2026-05-29T22:55:33Z` |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Timing and work-shape calibration | present | First reconnect timing, roles, counters, state sizes, and workload evidence are source-referenced. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-240`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-239`, `log:client.log:2-9` |
| Later reconnect handling | present | Second reconnect is documented separately and excluded from first traversal timing. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:276-320` |
| Network-inflight calibration | ambiguous | Stats throughput and ping are present; passive TCP/window evidence is partial and not linkable to learner/teacher sockets by node ID. | `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent_00;rows=2998-3122`, `sampler:network_sampler_network-node2-0.log:17455-17461;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Exact image tag or digest | missing | `version_run.txt`, `client.log`, learner `settingsUsed.txt` | `image`, `tag`, `digest` | Version inputs exist, but no authoritative image tag/digest key was found. |
| Baseline restore identifier | missing | `version_run.txt`, `client.log`, learner `settingsUsed.txt` | `baseline`, `restore`, `saved state` | No authoritative baseline key was found. |
| Exact stopped pod script output | ambiguous | `version_run.txt`, `client.log`, top-level sampler file | `stopped`, `delete`, `network-node1-0` | Sampler stop marker exists, but no script line records the stopped learner pod control. |
| learnerCandidate expected learner node id | ambiguous | `version_run.txt`, `client.log`, `reconnect_network_samples_1.log`, learner `settingsUsed.txt` | `learnerCandidate`, `learner candidate`, `expected learner`, `stoppedPod`, `network-node1-0` | Sampler/host context names `network-node1-0`, and observed learner node `0` is logged, but no explicit expected learner-candidate control key was found. |
| teacherCandidate controlled/script value | missing | `version_run.txt`, `client.log`, `reconnect_network_samples_1.log`, learner `settingsUsed.txt` | `teacherCandidate`, `teacher candidate` | Observed first teacher peer `1` is logged, but no controlled expected teacher-candidate key was found. |
| learnerBehindDuration controlled/script value | missing | `version_run.txt`, `client.log`, `reconnect_network_samples_1.log`, learner `settingsUsed.txt` | `learnerBehindDuration`, `behind duration`, `warmtime`, `downtime`, `NofLoops` | Observed BEHIND interval exists in logs, but no controlled learner-behind duration key was found. |
| Controlled warmtime, downtime, loop count | missing | `version_run.txt`, `client.log`, learner `settingsUsed.txt` | `warmtime`, `downtime`, `NofLoops`, `loop` | No authoritative control values were found. |
| Full-run average transaction rate | missing | `client.log`, learner stats CSV headers | `TPS`, `transactions`, `receipts`, `trans_per_sec` | Configured max TPS and reconnect-window samples are recorded, but no source-referenced full-run average was derived in this phase. |
| Direct passive socket attribution | ambiguous | `network_sampler_network-node2-0.log`, run-local client log, sampler files | `10.36.*`, endpoint IPs | TCP rows use IP endpoints and no IP-to-node mapping source was found. |
| Passive sampler end-of-first-window coverage | ambiguous | `network_sampler_network-node2-0.log` | timestamp blocks around `2026-05-29T22:56:16Z` | Teacher sampler has blocks through `22:55:33Z`; no `22:56` timestamp was found. |
| Exact teacher reserved snapshot end size | missing | Teacher `swirlds.log`, `MainNetStats1.csv` | `Root has been flushed`, `VirtualMapMetadata`, `vmap_size_state` | Root response and sampled stats are available; exact reserved-state end snapshot was not emitted. |
