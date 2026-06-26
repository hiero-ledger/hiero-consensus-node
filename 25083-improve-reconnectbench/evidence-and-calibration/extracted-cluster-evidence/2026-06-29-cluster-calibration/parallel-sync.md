# Parallel-Sync Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/29-06-2026/dallas12_pullParallelSync/report`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | derived | No fatal network disease found. All seven node logs had `ACTIVE -> CHECKING` count `0`, missing-parent count `0`, and at least one `CHECKING -> ACTIVE` confirmation. | `derived:scan;inputs=log:podlog_solo-mdlt-n12/network-node*_logs/swirlds.log;patterns=oldStatus=ACTIVE,newStatus=CHECKING;Shadowgraph: Missing non-expired other parent` |
| Active confirmations | present | Learner node0 reached post-reconnect `CHECKING -> ACTIVE` at `2026-06-26 17:13:58.029`; peers reached startup `CHECKING -> ACTIVE` earlier. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:338`; `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:124`; `log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:124` |
| Extraction disposition | present | Normal extraction is valid. | [Analysis Output Per Mode](#analysis-output-per-mode) |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullParallelSync`; `version_run.txt` and all seven copied `settingsUsed.txt` files agree. | `config:version_run.txt:key=inputs.AddSettings;line=8`; `derived:scan_all_nodes;inputs=config:podlog_solo-mdlt-n12/network-node*_logs/config/settingsUsed.txt:line=725;expected=pullParallelSync` |
| Namespace | present | `Dallas12` | `config:version_run.txt:key=namespace;line=1` |
| Commit hash | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=hederahash;line=11` |
| Run number / job URL | present | run `302`; workflow URL `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/28245117957` | `config:version_run.txt:key=run_number;line=12`; `config:version_run.txt:key=JOB_URL;line=10` |
| NLG controls | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `30000000` accounts, `6h`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`; `config:version_run.txt:key=inputs.NLGDparams;line=4`; `config:version_run.txt:key=inputs.NLG_Accounts;line=5`; `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Learner node and pod | present | Learner is node ID `0`, log directory `network-node1_logs`, pod `network-node1-0`, `POD_IP=10.36.71.145`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:948`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:1143` |
| Client workload profile | present | 32 clients, 30M accounts, 1000 tokens, 30M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer duration `PT6H`. | `log:client.log:2-9` |
| Client network targets | present | Seven NLG targets on port `50211`. | `log:client.log:10-16` |
| Workflow-control logs | missing | No `performance-tests-start.log`, `performance-tests-watch.log`, or equivalent workflow-control filename was found under the run root. | `derived:search_no_matches;scope=run root;patterns=*performance*,*workflow*,*watch*,*start*,*profile*reconnect*` |
| Warmtime / downtime / loop count | missing | No `warmtime`, `downtime`, `NofLoops`, or `profileReconnectLoopK8s` controls were found in available non-CSV logs. | `derived:search_no_matches;scope=non-CSV non-settings logs under run root;patterns=warmtime,downtime,NofLoops,profileReconnectLoopK8s,performance-tests-start,performance-tests-watch` |
| Baseline/restored state upload | missing | No baseline, restore, upload, or state-upload evidence was found. | `derived:search_no_matches;scope=run root;patterns=baseline,restore,restored,upload,state upload,copy.*state` |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved `OBSERVING -> BEHIND` at `2026-06-26 17:05:36.856`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:159` |
| Iteration 1 receiver window | present | Learner node `0` received from teacher peer `2`, `2026-06-26 17:05:36.942..17:09:10.079`; wall-clock duration `213.137 s`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:188`; `derived:formula=end-start` |
| Iteration 1 teacher window | present | Teacher node `2` (`network-node3_logs`) sent to learner node `0`, `2026-06-26 17:05:39.556..17:09:10.079`. | `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:456`; `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:493` |
| Iteration 2 receiver window | present | Learner node `0` received from teacher peer `1`, `2026-06-26 17:09:16.465..17:10:23.456`; wall-clock duration `66.991 s`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:233`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:266`; `derived:formula=end-start` |
| Iteration 2 teacher window | present | Teacher node `1` (`network-node2_logs`) sent to learner node `0`, `2026-06-26 17:09:18.936..17:10:23.456`. | `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:457`; `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:492` |
| Learner status after final receiver finish | present | `BEHIND -> RECONNECT_COMPLETE -> CHECKING -> ACTIVE`; final `ACTIVE` at `2026-06-26 17:13:58.029`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:283`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:323`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:338` |

## Learner Evidence

| Iteration | Status | Extracted value or observation | Source references |
|---:|---:|---|---|
| 1 | present | Receiver synchronization time `210.516 s`, data `5125.976257324219 MB`; view range `[92085450,184170900]` (`92,085,451` leaves); new range `[100384348,200768696]` (`100,384,349` leaves); deleted leading nodes `8,298,898`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:166`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:170-175`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:185-201` |
| 2 | present | Receiver synchronization time `64.516 s`, data `1011.0164384841919 MB`; view range `[100384348,200768696]` (`100,384,349` leaves); new range `[101007153,202014306]` (`101,007,154` leaves); deleted leading nodes `622,805`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:244`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:248-253`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:263-279` |
| finalization | present | Both iterations have async input/finalization/hashing completion markers before the receiver finish payload. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:177-188`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:255-266` |

## Teacher Evidence

| Iteration | Teacher | Status | Extracted value or observation | Source references |
|---:|---:|---:|---|---|
| 1 | node `2` | present | Sent-state metadata and root response both report `[100384348,200768696]`; derived sent/root size `100,384,349` leaves. | `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:457-472` |
| 2 | node `1` | present | Sent-state metadata and root response both report `[101007153,202014306]`; derived sent/root size `101,007,154` leaves. | `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:458-473` |
| matching | present | Teacher windows match learner peer IDs and receiver finish times for both iterations. | `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:456-493`; `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:457-492` |

## Reconnect Work-Shape Counters

| Iteration | transfersFromTeacher | transfersFromLearner | internalHashes | internalCleanHashes | internalData | internalCleanData | leafHashes | leafCleanHashes | leafData | leafCleanData | Source references |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 111,369,129 | 110,508,953 | 56,673,493 | 21,510,338 | 56,687,739 | 21,505,642 | 54,223,487 | 16,266,464 | 55,317,621 | 16,385,947 | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:185` |
| 2 | 39,385,878 | 39,281,463 | 32,716,798 | 24,714,458 | 32,784,654 | 24,832,146 | 6,798,724 | 2,785,214 | 6,932,658 | 2,802,067 | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:263` |

| Iteration | internalDirtyHashes | internalDirtyData | leafDirtyHashes | leafDirtyData | Source references |
|---:|---:|---:|---:|---:|---|
| 1 | 35,163,155 | 35,182,097 | 37,957,023 | 38,931,674 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:185` |
| 2 | 8,002,340 | 7,952,508 | 4,013,510 | 4,130,591 | `derived:formula=total-clean;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:263` |

## Network Evidence

Observed `send` and `delivery_rate` values from `ss -tin` sampler logs are socket behavior during samples, not link capacity.

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner data lower-bound throughput, iteration 1 | derived | `5125.976257324219 MB / 210.516 s = 24.350 MB/s` from learner log payloads. | `derived:formula=dataMegabytes/timeInSeconds;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:186-187` |
| Learner data lower-bound throughput, iteration 2 | derived | `1011.0164384841919 MB / 64.516 s = 15.671 MB/s` from learner log payloads. | `derived:formula=dataMegabytes/timeInSeconds;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:264-265` |
| Stats RTT and send-rate, iteration 1 | present | CSV rows over iter1 show node0->node2 `ping_us_02=544.600`, mean `bytes_per_sec_sent_02=34,073,858.857 B/s`; node2->node0 `ping_us_00=635.360`, mean `bytes_per_sec_sent_00=25,021,766.964 B/s`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:column=ping_us_02,ping_us_02MIN,bytes_per_sec_sent_02;rows=869-939`; `csv:podlog_solo-mdlt-n12/network-node3_logs/stats/MainNetStats2.csv:column=ping_us_00,ping_us_00MIN,bytes_per_sec_sent_00;rows=3299-3370` |
| Stats RTT and send-rate, iteration 2 | present | CSV rows over iter2 show node0->node1 `ping_us_01=124.690`, mean `bytes_per_sec_sent_01=31,197,435.359 B/s`; node1->node0 `ping_us_00=146.780`, mean `bytes_per_sec_sent_00=9,938,318.768 B/s`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:column=ping_us_01,ping_us_01MIN,bytes_per_sec_sent_01;rows=942-964`; `csv:podlog_solo-mdlt-n12/network-node2_logs/stats/MainNetStats1.csv:column=ping_us_00,ping_us_00MIN,bytes_per_sec_sent_00;rows=3372-3394` |
| Passive sampler inventory | present | Per-node sampler logs cover `2026-06-26T17:01:12Z..17:14:08Z`; top-level `reconnect_network_samples_1.log` is present but empty. | `sampler:network_sampler_network-node1-0.log:1`; `sampler:network_sampler_network-node1-0.log:3731`; `sampler:network_sampler_network-node7-0.log:5007`; `sampler:reconnect_network_samples_1.log` |
| Passive TCP/window evidence, iteration 1 | present | Attributed socket pair `10.36.71.145:51160 <-> 10.36.58.58:50111`; 105 samples per side. Learner side `send` range `134.4..39490.9 Mbps`, `delivery_rate` range `92.7..5103.8 Mbps`, RTT `0.085..0.862 ms`, `cwnd=10..303`, `bytes_retrans +25,563`, max `Recv-Q=2,573,776`, max `Send-Q=2,635,792`, `rwnd_limited` in 99 samples. Teacher side RTT `0.081..6.38 ms`, `cwnd=10..207`, `bytes_retrans +66,796`, max `Recv-Q=2,621,642`, max `Send-Q=1,521,672`, `rwnd_limited` in 85 samples. | `sampler:network_sampler_network-node1-0.log:273-282,365-366,1219-1220,1359-1360,1415-1416,1513-1514,1737-1738;window=2026-06-26T17:05:37Z..2026-06-26T17:09:10Z`; `sampler:network_sampler_network-node3-0.log:1565-1576,1855-1856,2597-2598,2695-2696,2723-2724,3031-3032;window=2026-06-26T17:05:37Z..2026-06-26T17:09:10Z` |
| Passive TCP/window evidence, iteration 2 | present | Attributed socket pair `10.36.71.145:37506 <-> 10.36.41.74:50111`; 33 samples per side. Learner side `send` range `24.5..14442.4 Mbps`, `delivery_rate` range `192.0..3188.3 Mbps`, RTT `0.077..4.73 ms`, `cwnd=10..123`, `bytes_retrans +34,230`, max `Recv-Q=405,121`, max `Send-Q=573,592`, `rwnd_limited` in 30 samples. Teacher side RTT `0.090..5.34 ms`, `cwnd=10..152`, `bytes_retrans +63,192`, max `Recv-Q=1,694,671`, max `Send-Q=8,219`. | `sampler:network_sampler_network-node1-0.log:1790-1796,1837-1838,1879-1880,1907-1908,1949-1950,2243-2244;window=2026-06-26T17:09:16Z..2026-06-26T17:10:23Z`; `sampler:network_sampler_network-node2-0.log:3078-3086,3197-3198,3519-3520,3533-3534;window=2026-06-26T17:09:16Z..2026-06-26T17:10:23Z` |
| Passive attribution caveat | ambiguous | Sampler pair attribution is endpoint/port based. The sampler does not label protocol traffic, so the rows are observed learner/teacher TCP-pair behavior, not a reconnect-only byte stream. | `sampler:network_sampler_network-node1-0.log:273-282,1737-1738;window=2026-06-26T17:05:37Z..2026-06-26T17:09:10Z`; `sampler:network_sampler_network-node3-0.log:1565-1576,3031-3032;window=2026-06-26T17:05:37Z..2026-06-26T17:09:10Z` |
| `ping_us_*MIN` columns | ambiguous | Window rows contain plausible values and sentinel `9999999`; plain `ping_us_*` columns are used as primary CSV RTT evidence. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:column=ping_us_02MIN;rows=869-939`; `csv:podlog_solo-mdlt-n12/network-node2_logs/stats/MainNetStats1.csv:column=ping_us_00MIN;rows=3372-3394` |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 30M accounts, 1000 tokens, 30M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer workload for `PT6H`. | `log:client.log:2-9` |
| Transaction mix during reconnect | present | Token creation, NFT minting, then concurrent crypto transfer, NFT transfer, HeliSwap, message send, and smart contract jobs. | `log:client.log:1130-1134`; `log:client.log:1239-1245` |
| Actual transaction-rate samples | present | Around first start: transactions `67.8M`, `TPS(current)=10388`, receipts `67.7M`, `TPS(current)=10387`; near final finish: transactions `70.8M`, `TPS(current)=10385`, receipts `70.7M`, `TPS(current)=10387`; near `ACTIVE`: transactions `73.0M`, `TPS(current)=10386`, receipts `72.9M`, `TPS(current)=10412`. | `log:client.log:2281-2285`; `log:client.log:2362-2370`; `log:client.log:2433-2441` |
| Load continuity | present | Client load continues while learner is behind/reconnecting and also continues through later `ACTIVE`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:266`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:338`; `log:client.log:2281-2441` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Iteration 1 learner start size | derived | `92,085,451` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:166` |
| Iteration 1 teacher/target size | derived | `100,384,349` leaves; target equals learner received-state metadata. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:457-472,log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:189-201` |
| Iteration 1 state-size gap | derived | `8,298,898` leaves between teacher target and learner start. | `derived:formula=100384349-92085451;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:166,log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:457-472` |
| Iteration 2 target size | derived | `101,007,154` leaves; second-iteration gap from prior target is `622,805` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:244-253,log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:458-473` |
| Learner stats state/store snapshots | present | Learner node0 `vmap_size_state`: `92,078,389` before start, `92,085,451` after first start, `100,384,349` at final-finish sample, `101,775,359` after `ACTIVE`. Stable service counts: `accounts=30000712`, `contracts=6`, `files=23`, `nfts=30000000`, `schedules=0`, `tokens=1000`, `topics=100000`; token associations rise to `10,471,401` after `ACTIVE`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:column=vmap_size_state,storageSlotsUsed,tokenAssociationsUsed,accountsUsed,contractsUsed,filesUsed,nftsUsed,schedulesUsed,tokensUsed,topicsUsed;rows=868,869,964,1036` |
| Teacher stats state snapshots | present | Iter2 teacher node1 `vmap` from `100,392,480 -> 101,199,459 -> 101,804,950`; iter1 teacher node2 `vmap` from `100,392,136 -> 101,199,004 -> 101,812,929`. | `csv:podlog_solo-mdlt-n12/network-node2_logs/stats/MainNetStats1.csv:column=vmap_size_state,storageSlotsUsed,tokenAssociationsUsed;rows=3299-3466`; `csv:podlog_solo-mdlt-n12/network-node3_logs/stats/MainNetStats2.csv:column=vmap_size_state,storageSlotsUsed,tokenAssociationsUsed;rows=3299-3467` |
| Divergence shape | derived | Growth-heavy, multi-iteration reconnect with a large first gap, smaller second gap, and substantial clean plus dirty reconnect work. | `derived:classify_from_state_gap_and_clean_dirty_counters;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:185,log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:263` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:266`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:338` |
| Iteration count | derived | `2` learner receiver reconnect iterations before `ACTIVE`. | `derived:count_receiver_starts_before_ACTIVE;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163,233,338` |
| Complete catch-up start | present | `2026-06-26 17:05:36.942` UTC. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163` |
| Complete catch-up end | present | `2026-06-26 17:10:23.456` UTC, final learner receiver finish before `ACTIVE`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:266`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:338` |
| Complete catch-up duration | derived | `286.514 s`. | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163,266` |
| Active confirmation | present | `2026-06-26 17:13:58.029` UTC. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:338` |
| Additional iterations observed | present | Yes. The complete episode has two receiver iterations before `ACTIVE`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:233`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:338` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullParallelSync` | `config:version_run.txt:key=inputs.AddSettings;line=8`; `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:725` |
| Manifest batch | present | `2026-06-29-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration` |
| Manifest run | present | `parallel-sync` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration;run=parallel-sync` |
| Commit | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=hederahash;line=11` |
| Network disease preflight | derived | pass; no fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163` |
| Teacher node | present | First iteration teacher node `2`; second iteration teacher node `1`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:233` |
| First reconnect start UTC | present | `2026-06-26 17:05:36.942` | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163` |
| First reconnect end UTC | present | `2026-06-26 17:09:10.079` | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:188` |
| Learner duration | derived | First iteration `213.137 s`; complete catch-up duration `286.514 s`. | `derived:formula=end-start;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:163,188,266` |
| Teacher reconnect context present | present | yes | [Teacher Evidence](#teacher-evidence) |
| Reconnect stats present | present | yes | [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Teacher/learner state size present | present | yes | [State And Divergence Evidence](#state-and-divergence-evidence) |
| Workload profile present | present | yes | [Workload Evidence](#workload-evidence) |
| RTT evidence present | present | yes, via CSV stats and passive socket RTT fields. | [Network Evidence](#network-evidence) |
| Bandwidth evidence present | present | yes, via learner data/time, CSV send-rate, and passive socket observed send/delivery fields. | [Network Evidence](#network-evidence) |
| TCP/window evidence present | present | yes, passive sampler fields overlap both reconnect iterations. | [Network Evidence](#network-evidence) |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `2` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-06-26 17:05:36.942` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-06-26 17:10:23.456` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `286.514 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-06-26 17:13:58.029` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
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
| Workflow controls | missing | Run root file inventory; non-CSV non-settings logs | `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s` | Workflow-control files are absent from this artifact, so stopped-pod timing and loop controls are inferred from reconnect logs and manifest context only. |
| Baseline/restored-state upload | missing | Run root file inventory; `version_run.txt`; `client.log`; support/journal logs | `baseline`, `restore`, `restored`, `upload`, `state upload` | No baseline/restored-state upload evidence found; treat run as independent live-state workflow evidence. |
| Exact stopped-pod script output | missing | `version_run.txt`, `client.log`, support/journal logs, learner config, learner log | `Stopping java`, `delete pod`, `kubectl.*delete`, `network-node1-0`, `HOSTNAME`, `ReconnectStartPayload` | No direct workflow stop marker exists; stopped pod is inferred as `network-node1-0` from learner node/pod mapping and receiver reconnect evidence. |
| `ReconnectMapMetrics` in stats CSV | missing | All seven `MainNetStats*.csv` files | `transfersFromTeacher`, `internalHashes`, `leafData`, clean/dirty fields | Work-shape counters are present in learner logs, not mirrored in stats CSVs. |
| Passive socket attribution granularity | ambiguous | Per-node sampler logs | endpoint/port pairs during reconnect windows | Samplers do not label reconnect protocol traffic; attribution is endpoint/port based and should be treated as observed learner/teacher TCP-pair behavior. |
