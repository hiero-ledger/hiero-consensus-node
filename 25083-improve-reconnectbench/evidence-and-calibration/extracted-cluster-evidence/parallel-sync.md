# Parallel Sync Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect3_PullParallelSync/report`

All pod, config, client, sampler, and stats artifact paths below are relative to the artifact run root. Workflow log references use `../performance-tests-start.log` or `../performance-tests-watch.log` because those logs live beside `report/`.

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
| Network size | present | Seven configured node domains were present. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:60-108` |
| Workflow pod listing IP detail | absent | `../performance-tests-start.log` contains plain `get pods` output without IP columns; `../performance-tests-watch.log` is empty. Endpoint attribution therefore uses settings/sampler/log evidence below. | `workflow:../performance-tests-start.log:6293,6318-6340,7757,7841-7863`, `derived:formula=wc_l_is_zero;inputs=workflow:../performance-tests-watch.log` |
| Sampler and settings pod IP mapping | present | First reconnect learner `network-node1-0 -> 10.36.68.227` and teacher `network-node2-0 -> 10.36.16.71` are supported by settings and sampler evidence. Remaining sampler mappings: `network-node3-0 -> 10.36.9.138`, `network-node4-0 -> 10.36.27.241`, `network-node5-0 -> 10.36.28.146`, `network-node6-0 -> 10.36.11.79`, `network-node7-0 -> 10.36.63.182`. | `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:950`, `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:1145`, `sampler:network_sampler_network-node1-0.log:3`, `config:podlog_solo-mdlt-n4/network-node2_logs/config/settingsUsed.txt:950`, `config:podlog_solo-mdlt-n4/network-node2_logs/config/settingsUsed.txt:1145`, `sampler:network_sampler_network-node2-0.log:3`, `sampler:network_sampler_network-node3-0.log:3`, `sampler:network_sampler_network-node4-0.log:3`, `sampler:network_sampler_network-node5-0.log:3`, `sampler:network_sampler_network-node6-0.log:3`, `sampler:network_sampler_network-node7-0.log:3` |
| Baseline/restored state upload | absent | Solo node start skipped `Upload state files network nodes`, so this run did not start from an uploaded common baseline state. | `workflow:../performance-tests-start.log:4791-4792` |
| Learner node | present | Learner was node ID `0`, represented by `network-node1_logs`. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| Stopped pod | inferred | `network-node1-0`. No exact `stoppedPod=` script line was emitted, but the protocol records intended learner `network-node1-0` / node `0`; workflow `Stopping java` / `Down for downtime=1800` is followed by reconnect-loop sampling on `network-node1-0`; `network-node1_logs` maps to node ID `0`; learner `settingsUsed.txt` records `HOSTNAME, network-node1-0`; and the first receiver reconnect is logged by node `0`. | `protocol:../cluster-reconnectbench-artifact-processing-protocol.md:55`, `atlas:../cluster-reconnectbench-artifact-atlas.md:60`, `workflow:../performance-tests-start.log:7170-7173`, `sampler:reconnect_network_samples_1.log:1-2;window=run_sampler`, `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:950`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213` |
| Controlled warmtime, downtime, and loop count | present | `downtime=1800`, `warmtime=600`, and `NofLoops=0`; workflow then executed `profileReconnectLoopK8s.sh "solo-mdlt-n4" ${nlgpod} ${downtime} ${warmtime} ${NofLoops}`. | `workflow:../performance-tests-start.log:6823-6828`, `workflow:../performance-tests-start.log:7167-7171` |
| Workload profile | present | `LongevityLoadTest` with max TPS input `8000`, `24000000` accounts, and `6h` duration. | `config:version_run.txt:key=inputs.NLG_Test;line=9`, `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `workflow:../performance-tests-start.log:41-44` |
| Transaction mix | present | Client was configured for 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and `transfer` for `PT6H`. | `log:client.log:2-9` |
| NLGArguments | present | `NLG_Test=LongevityLoadTest`, `NLGDparams=-Dbenchmark.maxtps=8000`, `NLG_Accounts=24000000`, `NLG_Time=6h`; client config confirms 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50%, 100000 topics, ECDSA keys, and `transfer PT6H`. | `config:version_run.txt:key=inputs.NLGDparams;line=4`, `config:version_run.txt:key=inputs.NLG_Accounts;line=5`, `config:version_run.txt:key=inputs.NLG_Time;line=6`, `config:version_run.txt:key=inputs.NLG_Test;line=9`, `log:client.log:2-9` |
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
| Passive TCP sampler coverage during first reconnect | ambiguous | First-teacher and learner samplers have attributed socket blocks from reconnect start through `22:55:33Z`, but no `2026-05-29T22:56` timestamp was found, so end-of-window coverage remains partial. | `sampler:network_sampler_network-node2-0.log:17455,17615-17616;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`, `sampler:network_sampler_network-node1-0.log:5597,5685-5686;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`, `sampler:network_sampler_network-node2-0.log:30507,30663-30664;window=latest_teacher_before_completion` |
| Passive TCP/window fields | ambiguous | TCP samples include `Recv-Q`, `Send-Q`, `rtt`, `cwnd`, `ssthresh`, `bytes_sent`, `bytes_retrans`, `delivery_rate`, `rwnd_limited`, and `snd_wnd` for the attributed learner/teacher pair, but coverage remains partial near the first reconnect finish. | `sampler:network_sampler_network-node2-0.log:17455,17615-17616;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`, `sampler:network_sampler_network-node1-0.log:5597,5685-5686;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`, `sampler:network_sampler_network-node2-0.log:30507,30663-30664;window=latest_teacher_before_completion` |
| First-window TCP sample values | ambiguous | Attributed learner/teacher socket pair `[::ffff:10.36.68.227]:48896 <-> [::ffff:10.36.16.71]:50111`. Learner-side start sample at `22:50:08Z`: `Recv-Q=0`, `Send-Q=0`, `rtt=1.211/1.556`, `cwnd=10`, `bytes_sent=2104`, `bytes_received=5071`, `delivery_rate=131636360bps`, `snd_wnd=31744`. Teacher-side start cross-check at `22:50:08Z`: `Recv-Q=0`, `Send-Q=0`, `rtt=0.802/0.737`, `cwnd=10`, `bytes_sent=5071`, `bytes_received=2104`, `delivery_rate=229386136bps`, `snd_wnd=31744`. Latest teacher-side sample before finish at `22:55:33Z`: `Recv-Q=914150`, `Send-Q=266`, `rtt=0.08/0.011`, `cwnd=20`, `ssthresh=16`, `bytes_sent=2971488276`, `bytes_retrans=808246`, `bytes_received=4536926689`, `delivery_rate=498236552bps`, `rwnd_limited=20976ms(6.5%)`, `snd_wnd=31744`. Latest learner-side sample at `22:55:38Z`: `Recv-Q=0`, `Send-Q=0`, `rtt=6.426/8.663`, `cwnd=22`, `ssthresh=128`, `bytes_sent=5586559434`, `bytes_retrans=5614570`, `bytes_received=5031595703`, `delivery_rate=1300244896bps`, `rwnd_limited=47080ms(7.6%)`, `snd_wnd=1128448`. No `22:56` sampler timestamp was found, so the final part of the first reconnect remains uncovered. | `sampler:network_sampler_network-node1-0.log:5597,5685-5686`, `sampler:network_sampler_network-node2-0.log:17455,17615-17616`, `sampler:network_sampler_network-node2-0.log:30507,30663-30664`, `sampler:network_sampler_network-node1-0.log:9387,9539-9540` |
| Direct learner/teacher socket attribution | present | First reconnect learner node `0` / `network-node1-0` maps to `10.36.68.227`; teacher peer node `1` / `network-node2-0` maps to `10.36.16.71`. The first-window TCP pair is learner `10.36.68.227`, teacher `10.36.16.71`; sampled socket rows include `[::ffff:10.36.16.71]:50111 -> [::ffff:10.36.68.227]:48896` and the learner reciprocal. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213`, `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1030`, `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:950`, `config:podlog_solo-mdlt-n4/network-node1_logs/config/settingsUsed.txt:1145`, `sampler:network_sampler_network-node1-0.log:3`, `config:podlog_solo-mdlt-n4/network-node2_logs/config/settingsUsed.txt:950`, `config:podlog_solo-mdlt-n4/network-node2_logs/config/settingsUsed.txt:1145`, `sampler:network_sampler_network-node2-0.log:3`, `sampler:network_sampler_network-node2-0.log:17455,17615-17616`, `sampler:network_sampler_network-node1-0.log:5597,5685-5686` |
| Full node metrics cross-check | present | Full-node stats CSVs are present for all seven nodes and expose total `bytes_per_sec_sent`; teacher node `1` also exposes learner-directed `bytes_per_sec_sent_00`, `ping_us_00`, and `vmap_size_state` for first-window cross-checking of peer traffic, RTT, and state shape. | `csv:podlog_solo-mdlt-n4/network-node1_logs/stats/MainNetStats0.csv:column=bytes_per_sec_sent;rows=1281-1404;timestamp=2026-05-29 22:50:05 UTC..2026-05-29 22:56:14 UTC`, `csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent,bytes_per_sec_sent_00,ping_us_00,vmap_size_state;rows=2998-3122;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node4_logs/stats/MainNetStats3.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node5_logs/stats/MainNetStats4.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node6_logs/stats/MainNetStats5.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC`, `csv:podlog_solo-mdlt-n4/network-node7_logs/stats/MainNetStats6.csv:column=bytes_per_sec_sent;timestamp=2026-05-29 22:50:04 UTC..2026-05-29 22:56:16 UTC` |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 24M accounts, 1000 tokens, 24M NFTs, HOT 50% account selection, 100000 topics, ECDSA keys, and transfer workload for `PT6H`. | `log:client.log:2-9` |
| Workload during reconnect | present | Client logs continue to show transactions and receipts during the first reconnect interval, with `PLATFORM_NOT_ACTIVE`, `RECEIPT_NOT_FOUND`, and `THROTTLED_AT_CONSENSUS` indicators also present. | `log:client.log:2173-2311` |
| Actual transaction rate during first reconnect | present | Client workload stayed active inside `2026-05-29 22:50:04.496..22:56:15.846` UTC. WorkingQueue samples in-window show transaction `TPS(current)` range `2928..10708` and receipt `TPS(current)` range `5191..18628`; workload-specific samples include crypto transfers `1444..4936 TPS`, NFT transfers `1105..3018 TPS`, submitted messages `806..1758 TPS`, and contract swaps sample `318 TPS`. | `log:client.log:2175-2311`; range anchors: transaction min/max `log:client.log:2271-2271`, `log:client.log:2219-2219`; receipt min/max `log:client.log:2267-2267`, `log:client.log:2251-2251`; workload-specific samples `log:client.log:2174-2304`; window=`2026-05-29 22:50:04.496 UTC..2026-05-29 22:56:15.846 UTC` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner start size | derived | `73886545` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:216-216` |
| First teacher target state size | derived | `78191110` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1045-1045` |
| Learner target equals first teacher target | derived | yes, learner target/end size `78191110` equals first teacher target state size `78191110`. | `derived:formula=learner_target_size==teacher_target_size;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:220-221,log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1045-1045` |
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
| Learner node | present | `0` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| Teacher node | present | `1` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| First reconnect start UTC | present | `2026-05-29 22:50:04.496` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213` |
| First reconnect end UTC | present | `2026-05-29 22:56:15.846` | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:240-240` |
| Learner duration | derived | `371.350 s` | `derived:formula=end_timestamp-start_timestamp;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-213,log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:240-240` |
| Teacher reconnect context present | present | yes | `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1030-1075` |
| Reconnect stats present | present | yes | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-239` |
| Teacher/learner state size present | present | yes | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:216-221`, `log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1045-1045` |
| Workload profile present | present | yes | `log:client.log:2-9`, `log:client.log:2173-2311` |
| RTT evidence present | present | yes, via stats ping; passive socket endpoint attribution is resolved where sampler coverage exists. | `derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=ping_us_00;rows=2998-3122`, `sampler:network_sampler_network-node2-0.log:17455,17615-17616;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z` |
| Bandwidth evidence present | present | yes, via learner data/time and teacher stats throughput. | `derived:formula=dataMiB/timeSeconds;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:238-239`, `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent_00;rows=2998-3122` |
| TCP/window evidence present | ambiguous | Sampler fields are attributed to the first learner/teacher pair through most of the first window, but end-of-window coverage remains unresolved because no `22:56` sampler timestamp was found. | `sampler:network_sampler_network-node2-0.log:17455,17615-17616;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`, `sampler:network_sampler_network-node1-0.log:5597,5685-5686;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`, `sampler:network_sampler_network-node2-0.log:30507,30663-30664;window=latest_teacher_before_completion` |
| Later reconnects observed | present | second learner receiver reconnect observed and excluded from first traversal timing. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:276-320` |
| Run accepted for calibration | derived | no | `derived:formula=protocol_acceptance_requires_sufficient_RTT_bandwidth_TCP_window_evidence;inputs=sampler:network_sampler_network-node2-0.log:17455,17615-17616;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z,sampler:network_sampler_network-node1-0.log:5597,5685-5686;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z,sampler:network_sampler_network-node2-0.log:30507,30663-30664;window=latest_teacher_before_completion,derived:formula=avg_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=ping_us_00;rows=2998-3122,derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent_00;rows=2998-3122` |
| Reason if not accepted | derived | Passive TCP/window evidence is attributed but partial near the first reconnect finish, so network-inflight calibration remains incomplete. | `derived:formula=partial_end_window_TCP_coverage_for_network_inflight_calibration;inputs=sampler:network_sampler_network-node2-0.log:17455,17615-17616;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z,sampler:network_sampler_network-node2-0.log:30507,30663-30664;window=latest_teacher_before_completion` |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Timing and work-shape calibration | present | First reconnect timing, roles, counters, state sizes, and workload evidence are source-referenced. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:213-240`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:237-239`, `log:client.log:2-9` |
| Independent-run interpretation | present | Workflow logs confirm nominal reconnect controls and skipped restored-state upload. This run is a separate live-state calibration anchor; use its wall-clock duration with its own state size, gap, and work shape, and compare traversal modes locally only after reproducing comparable inputs. The immediate second reconnect reinforces that first-window timing must stay isolated. | `workflow:../performance-tests-start.log:4791-4792`, `workflow:../performance-tests-start.log:6823-6828`, `derived:formula=teacher_target_size-learner_start_size;inputs=log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:216-216,log:podlog_solo-mdlt-n4/network-node2_logs/swirlds.log:1045-1045`, `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:276-320` |
| Later reconnect handling | present | Second reconnect is documented separately and excluded from first traversal timing. | `log:podlog_solo-mdlt-n4/network-node1_logs/swirlds.log:276-320` |
| Network-inflight calibration | ambiguous | Stats throughput and ping are present, and passive TCP/window evidence is attributed where sampled; coverage is still partial near the first reconnect finish. | `derived:formula=avg_and_max_over_bounded_window;inputs=csv:podlog_solo-mdlt-n4/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent_00;rows=2998-3122`, `sampler:network_sampler_network-node2-0.log:17455,17615-17616;window=2026-05-29T22:50:04Z..2026-05-29T22:56:16Z`, `sampler:network_sampler_network-node2-0.log:30507,30663-30664;window=latest_teacher_before_completion` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Exact stopped pod script output | inferred_not_direct | `version_run.txt`, `client.log`, top-level sampler file, `../performance-tests-start.log`, learner `settingsUsed.txt`, learner `swirlds.log` | `stoppedPod`, `stopped pod`, `network-node1-0`, `Stopping java`, `HOSTNAME`, `ReconnectStartPayload` | No literal script field records the stopped pod, but `stoppedPod` is inferable as `network-node1-0` from intended learner context, workflow stop/down timing, sampler/host context, artifact node mapping, and observed node `0` receiver reconnect. Keep as inferred rather than directly captured. |
| Passive sampler end-of-first-window coverage | ambiguous | `network_sampler_network-node2-0.log` | timestamp blocks around `2026-05-29T22:56:16Z` | Teacher sampler has blocks through `22:55:33Z`; no `22:56` timestamp was found. |
