# Parallel-Sync Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/01-07-2026/dallas14_pullParallelSync/report`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | derived | No fatal network disease found. Missing-parent evidence exists on peer node logs, but no post-startup `ACTIVE -> CHECKING` evidence was found in the seven run-local node logs. | `derived:network_disease_preflight;inputs=log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:680,log:podlog_solo-sdpt-n14/network-node3_logs/swirlds.log:1062,log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:672;reason=fatal_requires_ACTIVE_to_CHECKING_and_missing_parent` |
| Files searched | present | Plain `swirlds.log` files for `network-node1_logs` through `network-node7_logs` were present and searched. | `derived:file_inventory;scope=podlog_solo-sdpt-n14/network-node*_logs/swirlds.log;expected_nodes=1..7` |
| Missing-parent evidence | present | `Shadowgraph: Missing non-expired other parent` appears in peer logs, with 11 matches across nodes 2 through 7. Missing-parent without post-startup `ACTIVE -> CHECKING` is non-fatal by protocol. | `log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:672`; `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:680`; `log:podlog_solo-sdpt-n14/network-node3_logs/swirlds.log:1062`; `derived:missing_parent_count=11` |
| Active confirmation context | present | Learner reaches `ACTIVE` at `2026-07-01 00:54:27.859`; startup `CHECKING -> ACTIVE` transitions are non-fatal. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:862` |
| Extraction disposition | present | Normal extraction is allowed. Learner plain `swirlds.log`, stats CSVs, client log, settings, and passive sampler files are present. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:163-188`; `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:rows=76-766`; `sampler:network_sampler_network-node1-0.log:327-12048` |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullParallelSync`; `version_run.txt` and all seven copied `settingsUsed.txt` files agree. | `config:version_run.txt:key=inputs.AddSettings;line=8`; `derived:scan_all_nodes;inputs=config:podlog_solo-sdpt-n14/network-node*_logs/config/settingsUsed.txt:line=725;expected=pullParallelSync` |
| Namespace | present | `AdHoc14` | `config:version_run.txt:key=namespace;line=1` |
| Commit hash | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=inputs.hederaversion;line=2`; `config:version_run.txt:key=hederahash;line=11` |
| Solo/chart version | present | `latest_tested_solo-charts0.59_balanced` | `config:version_run.txt:key=inputs.soloversion;line=3` |
| Run number / job URL | present | run `309`; workflow URL `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/28468726692`. | `config:version_run.txt:key=JOB_URL;line=10`; `config:version_run.txt:key=run_number;line=12` |
| NLG controls | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `97,500,000` accounts, `6h`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`; `config:version_run.txt:key=inputs.NLGDparams;line=4`; `config:version_run.txt:key=inputs.NLG_Accounts;line=5`; `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Network size | present | Seven configured roster entries, `network-node1` through `network-node7`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:49-107` |
| Learner node and pod | present | Learner is node ID `0`, log directory `network-node1_logs`; stopped/learner pod inferred as `network-node1-0`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:152-164`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:754-862`; `derived:atlas_node_log_mapping;inputs=podlog_solo-sdpt-n14/network-node1_logs` |
| Workflow controls | missing | No workflow-control source was found for warmtime, downtime, loop count, or `profileReconnectLoopK8s`. | files checked: `version_run.txt`, `client.log`, pod logs, run root inventory; patterns checked: `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s`; reason: no matches |
| Baseline/restored state upload | missing | No baseline, restore, upload, or state-upload evidence was found. | files checked: run root inventory, `version_run.txt`, `client.log`, pod logs; patterns checked: `baseline`, `restore`, `restored-state`, `restored state`, `upload`, `state upload`; reason: no matches |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved to `BEHIND` and started receiver reconnect. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:152-164` |
| Episode receiver coverage | present | The complete episode has 8 learner receiver starts and 8 learner receiver finishes from `2026-07-01 00:19:55.595` through `00:49:32.202`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:163-784`; `derived:count_receiver_starts_finishes=8` |
| Learner status after final receiver finish | present | Learner reached `ACTIVE` at `2026-07-01 00:54:27.859` after final receiver reconnect finish. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:784`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:862` |
| Later fall-behind scan | present | No later learner fall-behind was found through learner log EOF line `892`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:862-892` |

| Iteration | Peer | Receiver window UTC | Duration s | Teacher window source |
|---:|---:|---|---:|---|
| 1 | 5 | `00:19:55.595..00:31:51.682` | 716.087 | `log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:921-986` |
| 2 | 6 | `00:31:58.237..00:36:02.339` | 244.102 | `log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:894-929` |
| 3 | 2 | `00:36:11.223..00:38:43.237` | 152.014 | `log:podlog_solo-sdpt-n14/network-node3_logs/swirlds.log:965-1002` |
| 4 | 1 | `00:38:52.456..00:41:00.709` | 128.253 | `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:896-933` |
| 5 | 4 | `00:41:08.073..00:43:20.657` | 132.584 | `log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:894-929` |
| 6 | 6 | `00:43:28.890..00:45:49.837` | 140.947 | `log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:960-1025` |
| 7 | 5 | `00:45:59.965..00:47:49.914` | 109.949 | `log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:1020-1055` |
| 8 | 3 | `00:47:57.306..00:49:32.202` | 94.896 | `log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:920-955` |

## Learner Evidence

| Iteration | Status | Extracted value or observation | Source references |
|---:|---:|---|---|
| 1 | present | Learner path range `[294620524,589241048]` size `294,620,525` to target `[319844810,639689620]` size `319,844,811`; gap `25,224,286`; synchronization `709.905 s`; data `15510.305026054382 MB`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:163-188`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:166-171` |
| 2 | present | Path size `319,844,811 -> 321,957,473`; gap `2,112,662`; synchronization `238.168 s`; data `3125.962000846863 MB`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:233-266`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:236-249` |
| 3 | present | Path size `321,957,473 -> 322,692,039`; gap `734,566`; synchronization `145.697 s`; data `1510.6968593597412 MB`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:325-358`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:328-341` |
| 4 | present | Path size `322,692,039 -> 323,160,478`; gap `468,439`; synchronization `121.687 s`; data `1109.788480758667 MB`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:417-450`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:417-450` |
| 5 | present | Path size `323,160,478 -> 323,548,752`; gap `388,274`; synchronization `127.904 s`; data `969.3209228515625 MB`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:495-528`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:495-528` |
| 6 | present | Path size `323,548,752 -> 323,955,157`; gap `406,405`; synchronization `135.022 s`; data `1005.648473739624 MB`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:587-620`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:587-620` |
| 7 | present | Path size `323,955,157 -> 324,391,376`; gap `436,219`; synchronization `103.703 s`; data `1060.8607330322266 MB`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:679-709`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:679-709` |
| 8 | present | Path size `324,391,376 -> 324,731,094`; gap `339,718`; synchronization `87.466 s`; data `887.6066474914551 MB`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:754-784`; `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:754-784` |

## Teacher Evidence

| Iteration | Teacher | Status | Extracted value or observation | Source references |
|---:|---:|---:|---|---|
| 1 | node `5` | present | Sent range `[319844810,639689620]`, derived size `319,844,811`; teacher sender window `00:20:01.771..00:31:51.685`. | `log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:921-986`; `derived:formula=639689620-319844810+1;inputs=log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:922-934` |
| 2 | node `6` | present | Sent range `[321957472,643914944]`, derived size `321,957,473`; teacher sender window `00:32:04.168..00:36:02.342`. | `log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:894-929`; `derived:formula=643914944-321957472+1;inputs=log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:895-907` |
| 3 | node `2` | present | Sent range `[322692038,645384076]`, derived size `322,692,039`; teacher sender window `00:36:17.537..00:38:43.238`. | `log:podlog_solo-sdpt-n14/network-node3_logs/swirlds.log:965-1002`; `derived:formula=645384076-322692038+1;inputs=log:podlog_solo-sdpt-n14/network-node3_logs/swirlds.log:966-978` |
| 4 | node `1` | present | Sent range `[323160477,646320954]`, derived size `323,160,478`; teacher sender window `00:38:59.021..00:41:00.713`. | `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:896-933`; `derived:formula=646320954-323160477+1;inputs=log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:897-909` |
| 5 | node `4` | present | Sent range `[323548751,647097502]`, derived size `323,548,752`; teacher sender window `00:41:12.750..00:43:20.659`. | `log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:894-929`; `derived:formula=647097502-323548751+1;inputs=log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:895-907` |
| 6 | node `6` | present | Sent range `[323955156,647910312]`, derived size `323,955,157`; teacher sender window `00:43:34.813..00:45:49.838`. | `log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:960-1025`; `derived:formula=647910312-323955156+1;inputs=log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:961-973` |
| 7 | node `5` | present | Sent range `[324391375,648782750]`, derived size `324,391,376`; teacher sender window `00:46:06.210..00:47:49.915`. | `log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:1020-1055`; `derived:formula=648782750-324391375+1;inputs=log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:1021-1033` |
| 8 | node `3` | present | Sent range `[324731093,649462186]`, derived size `324,731,094`; teacher sender window `00:48:04.137..00:49:32.205`. | `log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:920-955`; `derived:formula=649462186-324731093+1;inputs=log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:921-933` |
| Teacher root-response lines | present | Explicit `TeachingSynchronizer: Teacher sending root node response` lines are present for all eight teacher windows. | `log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:937`; `log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:910`; `log:podlog_solo-sdpt-n14/network-node3_logs/swirlds.log:981`; `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:912`; `log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:910`; `log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:976`; `log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:1036`; `log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:936` |

## Reconnect Work-Shape Counters

| Iteration | transfersFromTeacher | transfersFromLearner | internalHashes | internalCleanHashes | internalData | internalCleanData | leafHashes | leafCleanHashes | leafData | leafCleanData | Source references |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 347,207,139 | 343,726,547 | 182,622,484 | 74,049,333 | 182,718,155 | 74,170,544 | 162,253,909 | 49,443,378 | 166,312,329 | 49,848,021 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:185` |
| 2 | 125,293,348 | 125,107,020 | 104,314,023 | 78,913,170 | 103,949,362 | 78,742,240 | 21,599,178 | 8,741,881 | 22,084,085 | 8,800,256 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:263` |
| 3 | 69,563,432 | 69,536,856 | 61,438,961 | 50,585,027 | 61,599,848 | 50,487,014 | 7,954,665 | 3,275,958 | 8,087,457 | 3,292,049 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:355` |
| 4 | 53,240,014 | 53,325,365 | 48,492,762 | 40,542,909 | 48,508,967 | 40,492,232 | 5,197,026 | 2,151,537 | 5,274,164 | 2,161,219 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:447` |
| 5 | 47,513,304 | 47,593,724 | 43,518,370 | 36,692,869 | 43,560,391 | 36,644,167 | 4,360,130 | 1,805,268 | 4,407,714 | 1,810,473 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:525` |
| 6 | 49,085,049 | 49,095,014 | 44,876,704 | 37,744,207 | 44,904,334 | 37,700,383 | 4,572,380 | 1,892,149 | 4,622,295 | 1,899,080 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:617` |
| 7 | 51,458,067 | 51,369,984 | 46,838,546 | 39,071,211 | 46,784,706 | 39,193,241 | 4,900,877 | 2,029,562 | 4,959,312 | 2,036,942 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:706` |
| 8 | 44,127,684 | 44,117,259 | 40,512,960 | 34,146,340 | 40,463,791 | 34,271,714 | 3,832,877 | 1,604,581 | 3,914,814 | 1,613,669 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:781` |

| Aggregate counter | Status | Value | Source references |
|---|---:|---:|---|
| transfersFromTeacher | derived | 787,488,037 | `derived:sum ReconnectMapMetrics over log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:185-781` |
| leafCleanData | derived | 71,461,709 | `derived:sum leafCleanData over log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:185-781` |
| leafDirtyData | derived | 148,200,461 | `derived:formula=sum(leafData-leafCleanData);inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:185-781` |

## Network Evidence

Observed CSV send-rate and passive `ss -tin` rate fields are socket behavior/context, not link-capacity evidence.

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner data lower-bound throughput | derived | Selected reconnect log data/time gives lower-bound receive rates: iter1 `21.85 MB/s`, iter2 `13.13 MB/s`, iter8 `10.15 MB/s`. | `derived:formula=dataMegabytes/synchronizationSeconds;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:185-188,263-266,781-784` |
| CSV RTT and send-rate coverage | present | CSV RTT/send-rate evidence exists for all eight iteration windows. Iter1 learner->teacher node5 `ping_us_05=489.70 us`, send-rate `31,810,432.22/42,085,179.38 B/s`; teacher->learner `ping_us_00=662.69 us`, send-rate `22,608,005.87/56,314,621.22 B/s`. Iter8 learner->teacher node3 `ping_us_03=155.61 us`, send-rate `27,047,693.51/37,501,408.20 B/s`; teacher->learner `166.68 us`, send-rate `7,041,390.95/19,317,626.43 B/s`. | `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_05,bytes_per_sec_sent_05;rows=77-315`; `csv:podlog_solo-sdpt-n14/network-node6_logs/stats/MainNetStats5.csv:columns=ping_us_00,bytes_per_sec_sent_00;rows=6188-6426`; `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_03,bytes_per_sec_sent_03;rows=638-668`; `csv:podlog_solo-sdpt-n14/network-node4_logs/stats/MainNetStats3.csv:columns=ping_us_00,bytes_per_sec_sent_00;rows=6749-6780` |
| Passive sampler inventory | present | Per-node samplers exist for nodes 1 through 7 and cover `2026-07-01T00:15:46Z..00:54:29Z`, bounding the complete learner catch-up `00:19:55.595..00:49:32.202`. The top-level `reconnect_network_samples_1.log` contains only a sampler stop marker. | `sampler:network_sampler_network-node1-0.log:1-13977`; `sampler:network_sampler_network-node2-0.log:1-15061`; `sampler:network_sampler_network-node7-0.log:1-15061`; `sampler:reconnect_network_samples_1.log:1` |
| Passive socket coverage | present | All eight receiver windows have linked learner-side and reciprocal teacher-side socket samples by teacher pod/IP. Examples include iter1 `10.68.51.26:35250 -> 10.68.50.143:50111`, iter4 `10.68.51.26:49682 -> 10.68.11.79:50111`, and iter8 `10.68.51.26:52754 -> 10.68.33.50:50111`. | `sampler:network_sampler_network-node1-0.log:327-338`; `sampler:network_sampler_network-node6-0.log:1285-1294`; `sampler:network_sampler_network-node1-0.log:8055-8060`; `sampler:network_sampler_network-node2-0.log:9069-9078`; `sampler:network_sampler_network-node1-0.log:11947-11950`; `sampler:network_sampler_network-node4-0.log:12793-12796` |
| Representative TCP/window evidence | present | Representative learner rows show sub-millisecond RTT/minRTT and window/backpressure fields: iter1 early `rwnd_limited=10.9%`; iter3 `Recv-Q=448`, `Send-Q=8219`, `unacked=6`; iter7 late-mid `rwnd_limited=13.2%`; iter8 final-window `rwnd_limited=0.5%`. `send`, `pacing_rate`, and `delivery_rate` are socket context only. | `sampler:network_sampler_network-node1-0.log:327-338`; `sampler:network_sampler_network-node1-0.log:7215-7222`; `sampler:network_sampler_network-node1-0.log:11107-11116`; `sampler:network_sampler_network-node1-0.log:12045-12048` |
| Passive attribution caveat | ambiguous | Samples are connection-level and whole-second timestamped, not frame-level reconnect telemetry. Iteration linkage uses known teacher pod/IP for each reconnect window. | [Passive socket coverage](#network-evidence); [Representative TCP/window evidence](#network-evidence) |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 97.5M accounts, 1000 tokens, 97.5M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer workload for `PT6H`. | `log:client.log:1-9` |
| Client network targets | present | Seven NLG targets on port `50211`. | `log:client.log:10-16` |
| Actual workload phase around reconnect/ACTIVE | present | During the reconnect/ACTIVE window, observed load is account-creation/setup traffic. Later steady transfer jobs start after this reconnect episode. | `log:client.log:38-40`; `log:client.log:870-925`; `log:client.log:3401-3405`; `log:client.log:3711-3715` |
| Transaction-rate samples | present | Samples around the catch-up and `ACTIVE` window show aggregate current TPS roughly `14k..18k`, with account creation continuing. | `log:client.log:870-925`; `log:client.log:1160-1205`; `log:client.log:1510-1555`; `log:client.log:2050-2090` |
| Load continuity | present | Client load samples exist before the first receiver start, throughout the catch-up interval, around final receiver finish, and around learner `ACTIVE`; no client-side load gap is evident in sampled ranges. | `log:client.log:870-925`; `log:client.log:1160-1205`; `log:client.log:1510-1555`; `log:client.log:2050-2090`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:163,784,862` |

## State And Divergence Evidence

| Iteration | Status | Learner start size | Teacher target size | Gap | Source references |
|---:|---:|---:|---:|---:|---|
| 1 | derived | 294,620,525 | 319,844,811 | 25,224,286 | `derived:formula=319844811-294620525;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:166-171` |
| 2 | derived | 319,844,811 | 321,957,473 | 2,112,662 | `derived:formula=321957473-319844811;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:236-249` |
| 3 | derived | 321,957,473 | 322,692,039 | 734,566 | `derived:formula=322692039-321957473;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:328-341` |
| 4 | derived | 322,692,039 | 323,160,478 | 468,439 | `derived:formula=323160478-322692039;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:417-450` |
| 5 | derived | 323,160,478 | 323,548,752 | 388,274 | `derived:formula=323548752-323160478;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:495-528` |
| 6 | derived | 323,548,752 | 323,955,157 | 406,405 | `derived:formula=323955157-323548752;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:587-620` |
| 7 | derived | 323,955,157 | 324,391,376 | 436,219 | `derived:formula=324391376-323955157;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:679-709` |
| 8 | derived | 324,391,376 | 324,731,094 | 339,718 | `derived:formula=324731094-324391376;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:754-784` |

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner stats state/store snapshots | present | Learner `vmap_size_state` moves from `294,620,525` near iter1 start to `324,731,094` just after final finish; nearest `ACTIVE` row is `325,821,629`. | `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:rows=77,315,317,399,402,452,455,498,501,545,548,595,598,635,637,669,767;columns=time,vmap_size_state,accountsUsed,nftsUsed,tokenAssociationsUsed,storageSlotsUsed` |
| Teacher sampled growth | present | Teacher `vmap_size_state` grows during all eight iteration windows; examples: iter1 node5 `319,854,089 -> 321,940,160`, iter8 node3 `324,739,418 -> 325,005,692`. | `csv:podlog_solo-sdpt-n14/network-node6_logs/stats/MainNetStats5.csv:columns=vmap_size_state;rows=6188-6426`; `csv:podlog_solo-sdpt-n14/network-node4_logs/stats/MainNetStats3.csv:columns=vmap_size_state;rows=6749-6780` |
| Divergence shape | derived | Growth-heavy, repeated incremental catch-up. Iteration 1 has the dominant gap; later iterations have smaller positive gaps and exact teacher target equality at the path-range level. | `derived:classify_from_path_gaps_and_teacher_target_equality;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:166-784/log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:897-909/log:podlog_solo-sdpt-n14/network-node3_logs/swirlds.log:966-978/log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:921-933/log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:895-907/log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:922-934,1021-1033/log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:895-907,961-973` |
| CSV alignment caveat | ambiguous | CSV rows are nearest samples, not exact millisecond event timestamps; service counters corroborate state growth but are not exact reconnect divergence totals. | `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:rows=76-766` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:784`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:862` |
| Iteration count | derived | `8` learner receiver reconnect iterations before `ACTIVE`. | `derived:count_receiver_starts_before_ACTIVE;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:163-784` |
| Complete catch-up start | present | `2026-07-01 00:19:55.595` UTC. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:163` |
| Complete catch-up end | present | `2026-07-01 00:49:32.202` UTC, final learner receiver finish before `ACTIVE`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:784`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:862` |
| Complete catch-up duration | derived | `1,776.607 s`. | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:163,784` |
| Active confirmation | present | `2026-07-01 00:54:27.859` UTC; post-finish to `ACTIVE` is `295.657 s` and is not included in complete catch-up duration. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:862`; `derived:post_finish_to_ACTIVE=2026-07-01T00:54:27.859Z-2026-07-01T00:49:32.202Z` |
| Additional iterations observed | present | Yes. The complete episode has 8 receiver iterations before `ACTIVE`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:163-784`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:862` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullParallelSync` | [Run Context](#run-context) |
| Manifest batch | present | `2026-07-01-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration` |
| Manifest run | present | `parallel-sync` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration;run=parallel-sync` |
| Commit | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | [Run Context](#run-context) |
| Network disease preflight | derived | pass; missing-parent evidence exists, but no fatal `ACTIVE -> CHECKING` corroboration was found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | [Run Context](#run-context) |
| Episode incomplete reason | not_applicable | Episode is complete. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Teacher node | present | First sampled teacher node `5`; later teacher nodes `6`, `2`, `1`, `4`, `6`, `5`, and `3`. | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect start UTC | present | `2026-07-01 00:19:55.595` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| First reconnect end UTC | present | `2026-07-01 00:31:51.682` | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| Learner duration | derived | First iteration wall `716.087 s`; first iteration synchronization `709.905 s`; complete catch-up duration `1,776.607 s`. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Teacher reconnect context present | present | yes | [Teacher Evidence](#teacher-evidence) |
| Reconnect stats present | present | yes | [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Teacher/learner state size present | present | yes | [State And Divergence Evidence](#state-and-divergence-evidence) |
| Workload profile present | present | yes | [Workload Evidence](#workload-evidence) |
| RTT evidence present | present | yes, via CSV stats and passive socket RTT fields. | [Network Evidence](#network-evidence) |
| Bandwidth evidence present | present | yes, via learner data/time, CSV send-rate, and passive socket context; not link capacity. | [Network Evidence](#network-evidence) |
| TCP/window evidence present | present | yes, passive sampler fields overlap all eight exact receiver iterations. | [Network Evidence](#network-evidence) |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `8` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-07-01 00:19:55.595` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-07-01 00:49:32.202` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `1,776.607 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-07-01 00:54:27.859` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | yes | `derived:protocol_acceptance;inputs=[Run Context](#run-context),[Network Disease Preflight](#network-disease-preflight),[Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations),[Reconnect Work-Shape Counters](#reconnect-work-shape-counters),[State And Divergence Evidence](#state-and-divergence-evidence),[Workload Evidence](#workload-evidence),[Network Evidence](#network-evidence)` |
| Reason if not accepted | not_applicable | Accepted; no rejection reason. | [Acceptance Notes](#acceptance-notes) |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Calibration acceptance | derived | Accepted for calibration: no fatal preflight disease, confirmed mode/learner, complete catch-up through `ACTIVE`, exact learner receiver payload timing, raw `ReconnectMapMetrics` counters, workload continuity, and RTT/bandwidth/TCP-window context are present. | [Analysis Output Per Mode](#analysis-output-per-mode) |
| Multiple iterations | present | The complete catch-up episode includes eight receiver iterations. Trend/ranking should use complete catch-up duration, not only first-iteration duration. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Independent-run interpretation | present | This cluster run is an independent live-state calibration anchor; do not use it alone for causal traversal-mode ranking. | [State And Divergence Evidence](#state-and-divergence-evidence); `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Passive socket attribution | ambiguous | per-node network sampler logs for nodes 1 through 7 | learner-teacher socket tuples during exact receiver windows | Sockets are linkable by endpoint and exact receiver window, but samples are connection-level on long-lived sockets rather than frame-level reconnect-only telemetry. |
| Workflow controls | missing | Run root inventory, `version_run.txt`, `client.log`, pod logs | `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s` | Workflow-control files are absent. |
| Baseline/restored-state upload | missing | Run root inventory, `version_run.txt`, `client.log`, pod logs | `baseline`, `restore`, `restored-state`, `upload`, `state upload` | No baseline/restored-state upload evidence found; treat run as independent live-state workflow evidence. |
