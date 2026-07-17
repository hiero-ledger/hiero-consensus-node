# 2026-07-16 1B Observational Reconnect Extraction

All artifact paths in source references are relative to the artifact run root recorded under [Run Context](#run-context).

## Scope And Artifact Coverage

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Manifest identity | present | Collection `2026-07-16-1b-observational`, run `reconnect-run`. | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-16-1b-observational-reconnect` |
| Extraction purpose | present | Single-run, large-state observational extraction of reconnect outcome, work shape, workload, SocketFactory lifecycle telemetry, and focused `ss -tinm` evidence. | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-16-1b-observational-reconnect` |
| Primary artifact inventory | present | `version_run.txt`, `client.log`, `pod_state.txt`, seven node `swirlds.log` files, seven node stats CSVs, seven copied `settingsUsed.txt` files, and seven top-level passive sampler logs are present. | `derived:file_inventory;scope=runRoot,podlog_solo-mdlt-n12/network-node{1..7}_logs` |
| Node-log coverage | present | Seven of seven expected plain `swirlds.log` files are present. Their end times differ; learner node `0` ends at `2026-07-16 01:57:22.847` UTC. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34847`; `derived:file_inventory;scope=podlog_solo-mdlt-n12/network-node{1..7}_logs/swirlds.log` |
| Raw artifact handling | present | The collection was read only; durable extraction output is Markdown only. | extraction method |
| Traversal-order comparison | not_applicable | The collection has one observed traversal mode and is not a traversal-comparison batch. | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-07-16-1b-observational-reconnect` |
| Calibration acceptance or parameter mapping | not_applicable | This extraction is observational and makes no calibration-acceptance or local-parameter recommendation. | [observational extraction profile](../../cluster-reconnectbench-observational-extraction-profile.md) |

## Network Disease Preflight

The protocol's fatal preflight condition requires post-startup `ACTIVE -> CHECKING` churn together with
`Shadowgraph: Missing non-expired other parent`. The second symptom is absent from all seven supplied node logs, so the
combined fatal condition is not present. This observational profile would continue extraction even if it were present.

| Node log | Status | `ACTIVE -> CHECKING` | `CHECKING -> ACTIVE` | Missing-parent events | Verification handle |
|---|---:|---:|---:|---:|---|
| `network-node1_logs` | derived | 0 | 0 | 0 | `derived:exact_count;input=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log;patterns=ACTIVE->CHECKING,CHECKING->ACTIVE,Shadowgraph: Missing non-expired other parent` |
| `network-node2_logs` | derived | 6 | 7 | 0 | `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:235,1062`; `derived:exact_count` |
| `network-node3_logs` | derived | 2 | 3 | 0 | `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:189,954`; `derived:exact_count` |
| `network-node4_logs` | derived | 8 | 9 | 0 | `log:podlog_solo-mdlt-n12/network-node4_logs/swirlds.log:153,864`; `derived:exact_count` |
| `network-node5_logs` | derived | 11 | 12 | 0 | `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:155,674`; `derived:exact_count` |
| `network-node6_logs` | derived | 0 | 1 | 0 | `log:podlog_solo-mdlt-n12/network-node6_logs/swirlds.log:163`; `derived:exact_count` |
| `network-node7_logs` | derived | 8 | 9 | 0 | `log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:127,712`; `derived:exact_count` |
| **Total** | derived | **35** | **41** | **0** | `derived:sum_rows_above` |

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Fatal network disease | derived | No. The required missing-parent symptom has count zero across the seven-log search scope. | `derived:preflight_rule;inputs=table_above` |
| Learner recovery context | present | Learner node `0` has no `ACTIVE` or `CHECKING` transition anywhere in its supplied log. | `derived:exact_count;input=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log;patterns=Now in ACTIVE,Now in CHECKING;counts=0,0` |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Artifact run root | present | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/16-07-2026-1B` | manifest run entry |
| Namespace | present | `Dallas12` | `config:version_run.txt:key=namespace;line=1` |
| Producing commit | present | `09f7ef40e031fc3e1a06db6f7db5e7dcfe9abc73`; version label `NikitaReconnect`. | `config:version_run.txt:lines=2,11` |
| Solo/chart version | present | `latest_tested_solo-charts0.59_balanced` | `config:version_run.txt:line=3` |
| Workflow run | present | Run `1429`; workflow URL is recorded in the artifact. | `config:version_run.txt:lines=10,12` |
| Services/HAPI version | present | Services `0.77.0`, HAPI `0.77.0`. | `log:client.log:34` |
| Observed traversal mode | present | `pullTopToBottom`; all seven copied settings files agree. | `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:line=723`; `derived:scan_all_nodes;scope=podlog_solo-mdlt-n12/network-node{1..7}_logs/config/settingsUsed.txt:line=723` |
| Network size | derived | Seven configured client targets and seven supplied node-log directories. | `log:client.log:11-17`; `derived:count=7` |
| Learner identity | present | Internal node ID `0`, pod/log role `network-node1-0` / `network-node1_logs`. | `config:podlog_solo-mdlt-n12/network-node1_logs/config/settingsUsed.txt:line=946`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:181` |
| Intended workload scale | present | `inputs.NLG_Accounts=300000000`; this is the configured account count, not a VirtualMap-size measurement. | `config:version_run.txt:line=5` |
| Workflow controls | missing | No workflow-control artifact establishes warm time, down time, loop count, or an explicit collection stop/completion condition. | files checked: run-root inventory, `version_run.txt`, `client.log`, pod logs; patterns checked: `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, stop/completion markers; reason: no controlling artifact found |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Initial fall-behind context | present | Node `0` entered `BEHIND` immediately before its first receiver reconnect. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:176-181` |
| First receiver start | present | `2026-07-14 15:54:56.075` UTC, teacher node `1`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:181` |
| Last observed receiver finish | present | Iteration `341`, `2026-07-16 01:55:58.476` UTC, teacher node `2`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34727-34756` |
| Final receiver start | present | Iteration `342`, `2026-07-16 01:56:08.096` UTC, teacher node `4`; no matching receiver finish appears before learner-log EOF. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34830-34847` |
| Learner log coverage end | present | `2026-07-16 01:57:22.847` UTC, `74.751 s` after iteration 342 began. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34830-34847`; `derived:duration=2026-07-16T01:57:22.847Z-2026-07-16T01:56:08.096Z` |
| Receiver lifecycle count | derived | `342` starts, `341` finishes, one start unmatched in the supplied learner log. | `derived:exact_count;input=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:181-34847;patterns=Starting reconnect in the role of the receiver,Finished reconnect in the role of the receiver;counts=342,341` |
| Diagnostic first-start to last-finish span | derived | `34:01:02.401`. This is not a complete catch-up duration because another receiver attempt follows and `ACTIVE` is absent. | `derived:duration=2026-07-16T01:55:58.476Z-2026-07-14T15:54:56.075Z;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:181,34756` |
| Observed repeated-reconnect span | derived | At least `34:02:27.015`, from explicit `SELF_FALLEN_BEHIND` through learner-log EOF without `ACTIVE`. | `derived:duration=2026-07-16T01:57:22.847Z-2026-07-14T15:54:55.832Z;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:171,34847` |
| Teacher sequence | present | All 342 receiver-anchored target ranges match teacher root-response ranges; teacher distribution is node `1`:59, `2`:62, `3`:53, `4`:52, `5`:66, `6`:50. | `derived:exact_range_tuple_join;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:188-34837,log:podlog_solo-mdlt-n12/network-node{2..7}_logs/swirlds.log;matched=342` |
| Later teacher-only attempt | ambiguous | Teacher node `6` starts another sender attempt at `02:03:00.777` and emits target `[1213812835,2427625670]`; learner attribution is unavailable because the learner log ended earlier. It is excluded from the 342 receiver-anchored iterations. | `log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:9562-9579`; `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34847` |
| Later `ACTIVE` | missing | No learner `ACTIVE` transition is present before learner-log EOF. | files checked: `podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:1-34847`; patterns checked: `Now in ACTIVE`, `CHECKING -> ACTIVE`; reason: no match |

## Learner Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Complete-stage coverage | present | Iterations `1..341` each contain old path range, root response, target path initialization, learner completion, `ReconnectMapMetrics`, synchronization duration, data usage, and receiver finish. | first sequence `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:181-206`; last sequence `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34727-34756`; `derived:ordinal_stage_join;iterations=1..341` |
| Final-stage coverage | missing | Iteration `342` reaches target initialization and front-leaf deletion completion, but completion, metrics, synchronization, data-usage, and receiver-finish payloads are absent before EOF. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34830-34847` |
| Synchronization duration aggregate | derived | `341` values; sum `113,098.844 s`, mean `331.668164 s`, minimum `200.229 s` at iteration `4`, maximum `1,034.318 s` at iteration `1`. | `derived:aggregate_timeInSeconds;input=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:204-34754;count=341;min=log:.../swirlds.log:482;max=log:.../swirlds.log:204` |
| Data-usage aggregate | derived | `341` values; sum `751,385.387627602 MB`, mean `2,203.476210052 MB`, minimum `1,289.7865762710571 MB` at iteration `5`, maximum `21,875.007661819458 MB` at iteration `1`. | `derived:aggregate_dataMegabytes;input=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:205-34755;count=341;min=log:.../swirlds.log:587;max=log:.../swirlds.log:205` |
| Iteration 1 | present | Old state `[903041445,1806082890]` size `903,041,446`; target `[928566184,1857132368]` size `928,566,185`; gap `25,524,739`; synchronization `1,034.318 s`; data `21,875.007661819458 MB`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:181-206`; `derived:formula=last-first+1,target-old` |
| Iteration 170 | present | Old state `[1084304228,2168608456]` size `1,084,304,229`; target `[1085200917,2170401834]` size `1,085,200,918`; gap `896,689`; synchronization `344.173 s`; data `2,039.1909408569336 MB`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:17335-17364`; `derived:formula=last-first+1,target-old` |
| Iteration 341 | present | Old state `[1212164274,2424328548]` size `1,212,164,275`; target `[1212707745,2425415490]` size `1,212,707,746`; gap `543,471`; synchronization `361.909 s`; data `2,656.740990638733 MB`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34727-34756`; `derived:formula=last-first+1,target-old` |
| Iteration 342 | ambiguous | Old state `[1212707745,2425415490]` size `1,212,707,746`; target `[1213258384,2426516768]` size `1,213,258,385`; gap `550,639`. The attempt's final lifecycle result is unknown because learner evidence ends while it is running. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34830-34847`; `derived:formula=last-first+1,target-old` |
| Repeated status cycle | present | Every one of the 341 receiver finishes is followed by `BEHIND -> RECONNECT_COMPLETE`, then `RECONNECT_COMPLETE -> BEHIND`; no cycle reaches `ACTIVE` in the learner log. | first transitions `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:226,249`; last transitions `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34775,34826`; `derived:ordinal_transition_join;count=341` |
| Last-finished local error context | present | After iteration 341, an old-signed-state error and a `SocketException: Broken pipe` on the node `0` to node `2` connection precede the next fall-behind and iteration 342 start. No cause is inferred. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34772-34830` |

## Teacher Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Receiver-to-teacher attribution | present | Exact `(firstLeafPath,lastLeafPath)` joins match all 342 learner targets to teacher root responses; sent-state metadata and root ranges agree. | `derived:exact_range_tuple_join;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log,log:podlog_solo-mdlt-n12/network-node{2..7}_logs/swirlds.log;matched=342` |
| Teacher tree completion | present | `TeachingSynchronizer: Finished sending tree` is present for every receiver-anchored iteration, including iteration 342. | first `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:1984-2050`; last `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:9664-9727`; `derived:matched_count=342` |
| Formal sender finish payload | present | Present only for iterations 1 and 2. | `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:2050`; `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:2026` |
| Formal sender finish payload, iterations 3..342 | missing | The sender `ReconnectFinishPayload` is absent, even though matched teacher starts/root responses/tree-finish lines are present. | files checked: `podlog_solo-mdlt-n12/network-node{2..7}_logs/swirlds.log`; pattern checked: `Finished reconnect in the role of the sender`; reason: exact count is 2 |
| Iteration 1 teacher | present | Node `1`; sender starts `15:55:15.462`, target size `928,566,185`, and formal sender finish is present. | `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:1984-2050` |
| Iteration 170 teacher | present | Node `2`; sender start `07:20:54.286`, matching target `[1085200917,2170401834]`, tree finish `07:23:59.626`. | `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:6076-6110` |
| Iteration 341 teacher | present | Node `2`; sender start/root/tree-finish context matches the learner target. | `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:10364-10400` |
| Iteration 342 teacher | present | Node `4`; sender starts `01:56:26.747`, matches target `[1213258384,2426516768]`, and finishes sending the tree at `02:00:48.099`. | `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:9664-9727` |

## Reconnect Work-Shape Counters

All 341 emitted metric rows use the six-field schema shown below. `internalData`, `internalCleanData`, `leafHashes`, and
`leafCleanHashes` are not emitted by this producing commit and are not reconstructed.

| Raw field | Status | Sum | Mean | Minimum | Maximum | Source references |
|---|---:|---:|---:|---|---|---|
| `transfersFromTeacher` | derived | 33,261,254,990 | 97,540,337.214 | 58,036,507, iter 5 | 650,608,642, iter 1 | `derived:aggregate;input=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:203-34753;count=341;extrema=log:.../swirlds.log:585,203` |
| `transfersFromLearner` | derived | 33,261,254,990 | 97,540,337.214 | 58,036,507, iter 5 | 650,608,642, iter 1 | same 341 metric rows |
| `internalHashes` | derived | 21,799,946,069 | 63,929,460.613 | 36,117,689, iter 5 | 146,092,449, iter 1 | same 341 metric rows |
| `internalCleanHashes` | derived | 17,032,927,026 | 49,949,932.628 | 28,448,750, iter 5 | 67,627,585, iter 316 | same 341 metric rows; maximum `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:32212` |
| `leafData` | derived | 11,461,308,921 | 33,610,876.601 | 21,918,818, iter 5 | 504,516,193, iter 1 | same 341 metric rows |
| `leafCleanData` | derived | 8,841,691,626 | 25,928,714.446 | 15,273,006, iter 162 | 373,677,696, iter 1 | same 341 metric rows; minimum `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:16549` |
| `internalData`, `internalCleanData` | missing | — | — | — | — | files checked: 341 metric rows; keys checked: `internalData`, `internalCleanData`; reason: absent from emitted schema |
| `leafHashes`, `leafCleanHashes` | missing | — | — | — | — | files checked: 341 metric rows; keys checked: `leafHashes`, `leafCleanHashes`; reason: absent from emitted schema |

All 341 rows have `transfersFromTeacher == transfersFromLearner`.

| Protocol-derived field | Status | Formula | Sum | Mean | Minimum | Maximum | Source references |
|---|---:|---|---:|---:|---|---|---|
| `internalDirtyHashes` | derived | `internalHashes - internalCleanHashes` | 4,767,019,043 | 13,979,527.985 | 7,668,939, iter 5 | 82,236,210, iter 1 | `derived:subtract_per_row_then_sum;inputs=341 metric rows above` |
| `leafDirtyData` | derived | `leafData - leafCleanData` | 2,619,617,295 | 7,682,162.155 | 4,270,500, iter 5 | 130,838,497, iter 1 | `derived:subtract_per_row_then_sum;inputs=341 metric rows above` |
| `internalDirtyData` | missing | Required raw inputs absent | — | — | — | — | missing raw fields above |
| `leafDirtyHashes` | missing | Required raw inputs absent | — | — | — | — | missing raw fields above |

Across aggregate emitted work, `78.132886%` of internal hashes and `77.143821%` of leaf data are clean. These are
work-shape ratios, not traversal-comparison or calibration claims.

| Selected iteration | Status | Raw six-field metric tuple | Source references |
|---:|---:|---|---|
| 1 | present | `650608642,650608642,146092449,63856239,504516193,373677696` | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:203` |
| 170 | present | `90416103,90416103,67685425,53664948,22730678,15747451` | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:17361` |
| 341 | present | `119624475,119624475,83899297,65638484,35725178,26568957` | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34753` |
| 342 | missing | No metric row before learner-log EOF. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34830-34847` |

## Network Evidence

CSV send-rate and passive-sampler `send`, `pacing_rate`, and `delivery_rate` fields below describe observed socket
behavior. They are not link-capacity measurements.

### Metrics-Derived RTT And Throughput Context

| Window | Status | RTT context | Directed teacher-to-learner send-rate context | Source references |
|---|---:|---|---|---|
| Iteration 1 | present | Learner `ping_us_01=746.60 us`; one valid minimum `558 us`. | Teacher node 1 `bytes_per_sec_sent_00` range `0.00..63,167,212.60 B/s` across 352 three-second samples; end point `51,307,775.69 B/s`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_01,ping_us_01MIN;rows=1325-1676`; `csv:podlog_solo-mdlt-n12/network-node2_logs/stats/MainNetStats1.csv:column=bytes_per_sec_sent_00;rows=17293-17644;max-row=17639` |
| Iteration 170 | present | Learner `ping_us_02=104.20 us`; all `MIN` values are sentinel `9999999`. | Teacher node 2 range `268.74..22,036,301.16 B/s` across 116 samples; end point `17,898,916.78 B/s`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_02,ping_us_02MIN;rows=19844-19959`; `csv:podlog_solo-mdlt-n12/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=35813-35928;max-row=35874` |
| Iteration 341 | present | Learner `ping_us_02=96.89 us`; all `MIN` values are sentinel `9999999`. | Teacher node 2 range `56.97..17,990,503.12 B/s` across 121 samples; sender-end point `14,612,981.47 B/s`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:columns=ping_us_02,ping_us_02MIN;rows=42024-42145`; `csv:podlog_solo-mdlt-n12/network-node3_logs/stats/MainNetStats2.csv:column=bytes_per_sec_sent_00;rows=57993-58113;max-row=58079` |
| Iteration 342 near learner EOF | ambiguous | No fresh RTT result is attributed. | Learner total is `24,579,518.97 B/s`, of which peer `04` is `24,579,086.60 B/s` at `01:57:21`; this is point context, not proof of reconnect-only traffic. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:columns=bytes_per_sec_sent,bytes_per_sec_sent_04;rows=42172-42173` |

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Log data/time ratios | derived | Iteration 1 `21.149 MB/s`, iteration 170 `5.925 MB/s`, iteration 341 `7.341 MB/s`; aggregate emitted data / synchronization time `6.644 MB/s`. | `derived:formula=dataMegabytes/timeInSeconds;inputs=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:204-205,17362-17363,34754-34755;aggregate_inputs=341 rows` |
| RTT minimum sentinel handling | present | `9999999` is retained as a sentinel observation and is not reported as RTT. | CSV rows above |

### SocketFactory Lifecycle Telemetry

Values are observed bytes multiplied by occurrence count. SocketFactory log values and later kernel `skmem` buffer-cap
values are distinct evidence layers.

| Node | Server RX PRE/POST BIND | Client TX PRE/POST CONNECT | Client RX PRE/POST CONNECT | Status | Source references |
|---:|---:|---:|---:|---:|---|
| 0 (`network-node1`) | `32768×1 / 32768×1` | `32768×346 / 43520×346` | `32768×346 / 32768×346` | present | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:144-166,17221-17224,34818-34821` |
| 1 (`network-node2`) | `32768×1 / 32768×1` | `32768×49 / 43520×5` | `32768×49 / 32768×5` | present | `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:122-155,173-199,228-231` |
| 2 (`network-node3`) | `32768×1 / 32768×1` | `32768×27 / 43520×4` | `32768×27 / 32768×4` | present | `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:122-133,150-155,182-185` |
| 3 (`network-node4`) | `32768×1 / 32768×1` | `32768×10 / 43520×3` | `32768×10 / 32768×3` | present | `log:podlog_solo-mdlt-n12/network-node4_logs/swirlds.log:122-133,136-137,146-149` |
| 4 (`network-node5`) | `32768×1 / 32768×1` | `32768×12 / 43520×2` | `32768×12 / 32768×2` | present | `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:122-127,136-137,148-151` |
| 5 (`network-node6`) | `32768×1 / 32768×1` | `32768×17 / 43520×1` | `32768×17 / 32768×1` | present | `log:podlog_solo-mdlt-n12/network-node6_logs/swirlds.log:122-125,140-141,156-159` |
| 6 (`network-node7`) | `32768×1 / 32768×1` | — | — | present | `log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:122-123`; outbound client lifecycle is recorded separately below as `not_applicable` |

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Server receive-buffer delta | derived | Seven complete bind pairs; `32768 - 32768 = 0` bytes on every node. | lifecycle table above |
| Client send-buffer delta | derived | Every completed connect pair changes `32768 -> 43520`, `+10752` bytes. | lifecycle table above |
| Client receive-buffer delta | derived | Every completed connect pair remains `32768 -> 32768`, delta `0`. | lifecycle table above |
| Phase totals | derived | `461` complete PRE send/receive pairs, `361` complete POST pairs, `100` PRE-only pairs, zero POST-without-PRE cases, zero send/receive component imbalances; `1,658` lifecycle telemetry lines. | `derived:ordered_phase_pairing;inputs=all SocketFactory lifecycle lines in seven logs` |
| PRE-only attempts | ambiguous | Nodes 1..5 internally (log directories `network-node2..6`) contain the 100 PRE-only pairs in startup lifecycle blocks. Their cause is not inferred. | lifecycle table above; `derived:PRE-POST=100` |
| Node 6 client lifecycle | not_applicable | Internal node `6` has no outbound higher-node context in the observed `0→1..6`, `1→2..6`, …, `5→6` topology. | `log:podlog_solo-mdlt-n12/network-node7_logs/swirlds.log:122-123`; full-log SocketFactory search |
| Unexpected buffer values | missing | No mixed or unexpected values were found in the exact lifecycle grammar. | files checked: all seven `swirlds.log` files; method: group by phase, metric, value |
| Socket marker warnings | present | All telemetry lines use `WARN SOCKET_EXCEPTIONS`; separately, the marker contains 686 `NetworkUtils: Connection broken` warnings and two node-2/internal-node-2 `SSLPeerUnverifiedException` warnings. There are no marker-level `ERROR` lines. | first/last node0 connection warnings `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:321,34794`; SSL examples `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:6467,8040`; `derived:exact_marker_count;scope=seven logs` |

### Passive Sampler Coverage And Endpoint Mapping

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Sampler schema | derived | All seven files contain TCP socket state, TCP-info fields, and `skmem:(r,rb,t,tb,f,w,o,bl,d)`, consistent with `ss -tinm`. | `sampler:network-node1-0_network_sampler.log:2,638`; `sampler:network-node2-0_network_sampler.log:2,4`; equivalent `:2,4` in node3..node7 samplers |
| Literal sampler command | missing | The exact `ss -tinm` invocation is not preserved in sampler or run metadata. The `skmem` schema, rather than a command line, establishes memory-field collection. | files checked: all seven samplers and non-sampler run metadata; pattern checked: `ss -tinm`; reason: no literal invocation found |
| Sampling cadence | derived | Mean timestamp cadence is `1.923..1.924 s`; 2 s is dominant. Maximum gaps are `26..39 s`, so coverage is dense but not gap-free. | `derived:timestamp_stream;max_gap_end_refs=sampler:network-node1-0_network_sampler.log:503449,network-node2-0_network_sampler.log:506967,network-node3-0_network_sampler.log:506953,network-node4-0_network_sampler.log:507305,network-node5-0_network_sampler.log:125867,network-node6-0_network_sampler.log:507055,network-node7-0_network_sampler.log:507231` |
| Endpoint mapping | present | Internal nodes `0..6` map respectively to pod/IP `network-node1-0` `10.36.41.165`, node2 `10.36.58.95`, node3 `10.36.59.218`, node4 `10.36.61.57`, node5 `10.36.62.105`, node6 `10.36.47.167`, node7 `10.36.71.156`. | `config:podlog_solo-mdlt-n12/network-node{1..7}_logs/config/settingsUsed.txt:lines=946,1141` |
| `ACTIVE` sampler window | not_applicable | No learner `ACTIVE` anchor exists, so no post-`ACTIVE` socket window can be attributed. | [Reconnect Window And Roles](#reconnect-window-and-roles) |

| Sampler / internal node | Status | Capture span UTC | Timestamp count | Mean cadence | Receiver-window coverage | Source references |
|---|---:|---|---:|---:|---|---|
| `network-node1-0` / 0 learner | present | `2026-07-14 15:44:54..2026-07-15 23:36:22` | 59,640 | 1.923 s | Full through iteration 319; 320 partial; 321..342 missing | `sampler:network-node1-0_network_sampler.log:1,829185` |
| `network-node2-0` / 1 | present | `2026-07-14 15:44:54..2026-07-16 00:28:40` | 61,281 | 1.923 s | Full temporal span through iteration 327; 328 partial | `sampler:network-node2-0_network_sampler.log:1,855809` |
| `network-node3-0` / 2 | present | `2026-07-14 15:44:54..2026-07-15 23:13:42` | 58,911 | 1.924 s | Full temporal span through iteration 316; 317 partial | `sampler:network-node3-0_network_sampler.log:1,822581` |
| `network-node4-0` / 3 | present | `2026-07-14 15:44:54..2026-07-15 21:39:15` | 55,960 | 1.924 s | Full temporal span through iteration 301; 302 partial | `sampler:network-node4-0_network_sampler.log:1,781763` |
| `network-node5-0` / 4 | present | `2026-07-14 15:44:54..2026-07-15 23:07:55` | 58,730 | 1.924 s | Full temporal span through iteration 315; 316 partial | `sampler:network-node5-0_network_sampler.log:1,820363` |
| `network-node6-0` / 5 | present | `2026-07-14 15:44:54..2026-07-16 02:11:28` | 64,460 | 1.924 s | Spans all receiver anchors, but is not the teacher for iteration 342 | `sampler:network-node6-0_network_sampler.log:1,900343` |
| `network-node7-0` / 6 | present | `2026-07-14 15:44:54..2026-07-16 00:01:59` | 60,438 | 1.923 s | Full temporal span through iteration 323; 324 partial | `sampler:network-node7-0_network_sampler.log:1,844435` |

| Coverage result | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Full both-endpoint coverage | derived | 315 of 342 receiver windows have the complete learner and active-teacher sampler span. Iteration 319 is the last fully covered window; coverage is not contiguous because some teacher samplers end earlier. | `derived:join_iteration_roles_and_sampler_bounds;inputs=ordered iteration table,sampler table above` |
| Missing full-window set | missing | Iterations `304`, `314`, `317`, `318`, and `320..342`. Iterations 304/314/317 lack node-3/internal-node-3 teacher coverage; 318 lacks node-2/internal-node-2 teacher coverage; later gaps include learner coverage. | same coverage join |
| Iteration 341 | missing | Both learner and node-2 teacher samplers end before `01:49:37..01:55:58`. | `sampler:network-node1-0_network_sampler.log:829185`; `sampler:network-node3-0_network_sampler.log:822581` |
| Iteration 342 | missing | Learner and node-4 teacher samplers end before `01:56:08..01:57:22`. Node-5/internal-node-5 is the only sampler spanning the learner coverage end, but it is not this iteration's teacher. | `sampler:network-node1-0_network_sampler.log:829185`; `sampler:network-node5-0_network_sampler.log:820363`; `sampler:network-node6-0_network_sampler.log:900343` |

### Focused Learner/Teacher `ss -tinm` Evidence

Samples were assigned only inside receiver windows and only when reciprocal IP/four-tuples matched the iteration's
learner and teacher. Timestamp resolution is one second. Cumulative deltas below use an unchanged four-tuple whose
counters never decrease. A retransmission difference is called a lower bound when `bytes_retrans` is absent in early
samples. `skmem` vectors are component-wise maxima within the stated window, not a claim that every maximum occurred
in one sample.

#### Iteration 1 — first fully covered window

| Evidence item | Status | Learner endpoint | Teacher endpoint | Source references |
|---|---:|---|---|---|
| Window and tuple | present | `10.36.41.165:35816 -> 10.36.58.95:50111`; 548 samples | reciprocal tuple; 548 samples | learner `sampler:network-node1-0_network_sampler.log:637-638,8295-8296`; teacher `sampler:network-node2-0_network_sampler.log:3815-3816,11473-11474`; window `2026-07-14T15:54:56Z..16:12:29Z` |
| Peak `Recv-Q` / `Send-Q` | derived | `4,247,997 / 3,037,448` | `2,508,654 / 1,385,937` | learner extrema `sampler:network-node1-0_network_sampler.log:7680,7833-7834`; teacher `sampler:network-node2-0_network_sampler.log:10311,11166` |
| Peak `skmem r/rb/t/tb/f/w/o/bl/d` | derived | `6,180,096 / 6,287,288 / 145,152 / 3,037,696 / 2,084,864 / 3,097,608 / 0 / 2,304 / 1,737` | `3,641,600 / 3,706,128 / 9,503 / 1,645,056 / 2,082,560 / 1,414,097 / 0 / 0 / 1,647` | learner `sampler:network-node1-0_network_sampler.log:7833-7834,8113-8114`; teacher `sampler:network-node2-0_network_sampler.log:11291-11292` |
| RTT / `minrtt` ranges | derived | `0.069..9.043 ms / 0.039..0.076 ms` | `0.070..5.460 ms / 0.039..0.061 ms` | learner `sampler:network-node1-0_network_sampler.log:3872,6700`; teacher `sampler:network-node2-0_network_sampler.log:3816,7904` |
| Window fields | derived | `rcv_space 14,480..585,478`; `snd_wnd 1,024..1,853,440`; `cwnd 10..349`; `ssthresh 34..261` | `rcv_space 14,480..553,033`; `snd_wnd 1,024..3,143,680`; `cwnd 10..192`; `ssthresh 64..144` | learner extrema `sampler:network-node1-0_network_sampler.log:638,946,1072,1520,7624,7652,8156`; teacher `sampler:network-node2-0_network_sampler.log:3816,4698,5188,8856,11348,11376` |
| Peak `unacked` / `notsent` | derived | `324 / 3,037,448` | `60 / 1,385,937` | learner backpressure `sampler:network-node1-0_network_sampler.log:946,1058,8170`; teacher `sampler:network-node2-0_network_sampler.log:4124,4278,11348` |
| `rwnd_limited` | derived | maximum `335,716 ms`; observed percentage `16.1..72.3%` | maximum `103,492 ms`; `18.5..73.8%` | backpressure refs above |
| Cumulative counter deltas | derived | sent `41,136,457,010`; acked `41,135,163,785`; received `23,020,936,236`; exact sampled retrans `1,293,188` | sent `23,021,186,131`; acked `23,020,936,266`; received `41,135,163,822`; retrans lower-bound `246,969` | first/last tuple refs above |
| Socket-rate ranges | derived | `send 34.76 Mb/s..51.35 Gb/s`; pacing `69.50 Mb/s..61.10 Gb/s`; delivery `1.78 Mb/s..7.34 Gb/s` | `send 21.22 Mb/s..22.57 Gb/s`; pacing `42.43 Mb/s..26.88 Gb/s`; delivery `77.74 Mb/s..7.64 Gb/s` | bounded tuple scan; rate context only, not capacity |

#### Iteration 170 — selected middle window

The reciprocal socket ends at `07:24:58` before the receiver lifecycle finish. Deltas therefore cover this continuous
socket lifetime, not the full receiver wall window.

| Evidence item | Status | Learner endpoint | Teacher endpoint | Source references |
|---|---:|---|---|---|
| Window and tuple | present | `10.36.41.165:37032 -> 10.36.59.218:50111`; 140 samples | reciprocal tuple; 140 samples | learner `sampler:network-node1-0_network_sampler.log:403447-403448,405393-405394`; teacher `sampler:network-node3-0_network_sampler.log:407011-407012,408957-408958`; receiver window `2026-07-15T07:20:37Z..07:26:38Z` |
| Peak `Recv-Q` / `Send-Q` | derived | `1,017,760 / 900,520` | `2,735,508 / 8,221` | learner extrema `sampler:network-node1-0_network_sampler.log:404007-404008`; teacher `sampler:network-node3-0_network_sampler.log:407950` |
| Peak `skmem r/rb/t/tb/f/w/o/bl/d` | derived | `1,540,864 / 1,674,024 / 28,497 / 1,027,072 / 644,554 / 918,440 / 0 / 0 / 91` | `4,016,128 / 4,120,904 / 9,500 / 974,848 / 2,043,904 / 9,501 / 0 / 4,352 / 139` | learner `sampler:network-node1-0_network_sampler.log:404931-404946`; teacher `sampler:network-node3-0_network_sampler.log:408495-408524` |
| RTT range | derived | `0.080..4.018 ms` | `0.077..5.503 ms` | bounded tuple scan; first/extrema refs above |
| Window fields | derived | `rcv_space 14,480..212,671`; `snd_wnd 1,024..2,061,312`; `cwnd 10..131`; `ssthresh 39..91` | `rcv_space 14,480..468,420`; `snd_wnd 31,744..837,632`; `cwnd 10..63`; `ssthresh 25..46` | learner `sampler:network-node1-0_network_sampler.log:403448,403770,403784,404890,404904,404932,404946`; teacher `sampler:network-node3-0_network_sampler.log:407012,407152,407320,407390,407502,408510,408524` |
| Cumulative counter deltas | derived | sent `5,716,807,651`; acked `5,716,673,804`; received `2,146,091,997`; retrans lower-bound `108,751` | sent `2,146,114,928`; acked `2,146,094,463`; received `5,716,674,390`; retrans lower-bound `20,505` | first/last tuple refs above |
| `rwnd_limited` | derived | maximum `3,604 ms / 1.8%` | maximum `368 ms / 1.1%` | bounded tuple scan; extrema refs above |
| Socket-rate ranges | derived | `send 28.83 Mb/s..17.24 Gb/s`; pacing `57.65 Mb/s..20.69 Gb/s`; delivery `210.62 Mb/s..4.04 Gb/s` | `send 21.05 Mb/s..6.32 Gb/s`; pacing `42.10 Mb/s..7.52 Gb/s`; delivery `307.54 Mb/s..1.59 Gb/s` | bounded tuple scan; rate context only, not capacity |

#### Iteration 319 — last fully covered both-endpoint window

The reciprocal socket ends at `23:31:50` before the receiver lifecycle finish. Deltas cover this continuous socket
lifetime.

| Evidence item | Status | Learner endpoint | Teacher endpoint | Source references |
|---|---:|---|---|---|
| Window and tuple | present | `10.36.41.165:49894 -> 10.36.58.95:50111`; 183 samples | reciprocal tuple; 183 samples | learner `sampler:network-node1-0_network_sampler.log:824607-824608,827155-827156`; teacher `sampler:network-node2-0_network_sampler.log:828145-828146,830693-830694`; receiver window `2026-07-15T23:26:10Z..23:32:45Z` |
| Peak `Recv-Q` / `Send-Q` | derived | `1,419,887 / 1,604,424` | `1,831,703 / 202,251` | learner extrema `sampler:network-node1-0_network_sampler.log:825070,826147-826148`; teacher `sampler:network-node2-0_network_sampler.log:828552-828594` |
| Peak `skmem r/rb/t/tb/f/w/o/bl/d` | derived | `2,084,608 / 2,196,224 / 9,499 / 1,714,688 / 1,277,952 / 1,636,424 / 0 / 0 / 123` | `2,682,880 / 2,736,328 / 9,491 / 1,018,368 / 2,052,096 / 207,371 / 0 / 0 / 325` | learner `sampler:network-node1-0_network_sampler.log:826679-826694`; teacher `sampler:network-node2-0_network_sampler.log:829685-829700` |
| RTT range | derived | `0.079..2.866 ms` | `0.078..5.614 ms` | bounded tuple scan; extrema refs above |
| Window fields | derived | `rcv_space 14,480..270,472`; `snd_wnd 1,024..1,369,088`; `cwnd 10..197`; `ssthresh 134..147` | `rcv_space 14,480..537,041`; `snd_wnd 1,024..1,098,752`; `cwnd 10..74`; `ssthresh 28..55` | learner `sampler:network-node1-0_network_sampler.log:824608,825000,825028,826134,826148,826554,826568`; teacher `sampler:network-node2-0_network_sampler.log:828146,828538,829238,829686,829812,830218` |
| Cumulative counter deltas | derived | sent `7,490,613,423`; acked `7,490,265,467`; received `2,780,244,680`; exact sampled retrans `347,956` | sent `2,780,281,298`; acked `2,780,244,680`; received `7,490,265,467`; retrans lower-bound `33,722` | first/last tuple refs above |
| `rwnd_limited` | derived | maximum `16,080 ms / 0.6%` | maximum `1,652 ms / 1.9%` | learner `sampler:network-node1-0_network_sampler.log:826554`; teacher `sampler:network-node2-0_network_sampler.log:830218-830246` |
| Socket-rate maxima | derived | `send/pacing/delivery 28.17/33.70/4.42 Gb/s` | `10.25/12.16/2.70 Gb/s` | bounded tuple scan; rate context only, not capacity |

| Interpretation limit | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Selected-window socket continuity | derived | Reciprocal tuples match and cumulative sent/acked/received counters never decrease in the three selected windows. | selected tuple refs above |
| Cross-endpoint consistency | derived | Learner acked/received deltas closely match teacher received/acked deltas; small edge differences reflect independently timed samples. | selected first/last refs above |
| Kernel buffer-cap interpretation | present | `skmem rb/tb` vary through the windows and are kernel socket-memory observations, not the SocketFactory pre/post values. | selected `skmem` rows above; [SocketFactory Lifecycle Telemetry](#socketfactory-lifecycle-telemetry) |
| Whole-episode TCP attribution | ambiguous | Dense selected windows show transient queues, socket-memory growth, retransmissions, and `rwnd_limited` time, but the learner sampler lacks the final 23 iterations. TCP evidence cannot establish why the final reconnect did not complete in the supplied learner log. | sampler coverage table above |

Field omission is not interpreted as a zero value:

| Iteration / endpoint | Status | Tuple samples | `rcv_space` | `snd_wnd` | `unacked` | `notsent` |
|---|---:|---:|---:|---:|---:|---:|
| 1 learner | present | 548 | 548, throughout | 489, partial | 223, partial | 179, partial |
| 1 teacher | present | 548 | 548, throughout | 511, partial | 208, partial | 58, partial |
| 170 learner | present | 140 | 140, throughout | 140, throughout | 43, partial | 2, partial |
| 170 teacher | present | 140 | 140, throughout | 140, throughout | 17, partial | 0, absent |
| 319 learner | present | 183 | 183, throughout | 178, partial | 51, partial | 7, partial |
| 319 teacher | present | 183 | 183, throughout | 183, throughout | 22, partial | 1, partial |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Workload configuration | present | `LongevityLoadTest`, 32 clients, 300,000,000 accounts, 1,000 tokens, 300,000,000 NFTs, 100,000 topics, `HOT 50%`, ECDSA keys, configured duration `PT167H`. | `log:client.log:3-10` |
| Version-run controls | present | `inputs.NLG_Time=1m` and empty `inputs.NLGDparams`; these differ from the client-emitted `PT167H` and no workflow-control artifact resolves the relationship. | `config:version_run.txt:lines=4,6`; `log:client.log:10` |
| Loaded account target | present | Client reports `Loaded 300000000 accounts`. | `log:client.log:45` |
| Transaction mix | present | Crypto transfers, NFT transfers, messages, swaps, and smart-contract crypto transfers. | `log:client.log:46-50`; window samples `log:client.log:2756-2760,25291-25292,40885-40913` |
| Component rates | present | Near the final window, component rates are approximately 5,000 crypto-transfer, 3,000 NFT-transfer, 2,000 message, 312-313 swap, and 75 smart-contract TPS. | `log:client.log:40885-40913` |
| Aggregate rate before first reconnect | present | Transaction and receipt samples near `10,375..10,390` current/EMA TPS. | `log:client.log:2756-2757` |
| Aggregate rate during reconnect | present | Mid-episode samples remain near `10,370..10,391` TPS. | `log:client.log:25291-25292` |
| Aggregate rate near final observed attempts | present | Samples around iteration 341 finish, iteration 342 start, and learner-log EOF remain near `10,374..10,389` TPS. | `log:client.log:40885-40913` |
| Load continuity | derived | `12,688` transaction and `12,666` receipt samples cover the episode; maximum adjacent gaps are `19.486 s` and `19.448 s`. Load therefore continues throughout the supplied receiver episode. | `derived:timestamp_gap_scan;input=log:client.log;boundary_refs=log:client.log:2759-2760,40912-40913;extrema_refs=log:client.log:27786-27790,35407-35411` |
| Client coverage end | present | Client log ends at `07-16 02:07:32.934` still emitting transactions near `10,384` current TPS; `pod_state.txt` also shows the NLG pod `Running`, but that snapshot has no timestamp. | `log:client.log:41101-41104`; `config:pod_state.txt:line=24` |
| Client/workflow completion | missing | No explicit successful completion, stop, or terminal workflow marker is supplied. | files checked: run-root inventory, `client.log`, `version_run.txt`, `pod_state.txt`; patterns checked: completion/stop/workflow-control markers; reason: client log ends while load is active |
| Reconnect-related client failure | ambiguous | Generic `ConnectException`, `TimeoutException`, and error samples occur, but the supplied sources do not attribute them to reconnect or establish a terminal client failure. | examples `log:client.log:2763,25302,40669,40725`; reason: generic and non-terminal |

## State And Divergence Evidence

Path-derived state size is `lastLeafPath - firstLeafPath + 1`; per-attempt divergence is
`targetSize - learnerSize`. The configured 300,000,000 accounts are an application workload/entity count, not a
VirtualMap-size measurement.

| Attempt | Status | Learner size | Sent target size | Target gap | Source references |
|---:|---:|---:|---:|---:|---|
| 1 | derived | 903,041,446 | 928,566,185 | 25,524,739, `2.826530%` of learner size | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:188`; matching teacher `log:podlog_solo-mdlt-n12/network-node2_logs/swirlds.log:2000` |
| 170 | derived | 1,084,304,229 | 1,085,200,918 | 896,689, `0.082697%` | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:17342`; teacher `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:6091` |
| 341 | derived | 1,212,164,275 | 1,212,707,746 | 543,471, `0.044835%` | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34734`; teacher `log:podlog_solo-mdlt-n12/network-node3_logs/swirlds.log:10380` |
| 342 | derived | 1,212,707,746 | 1,213,258,385 | 550,639, `0.045406%` | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34837`; teacher `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:9680` |

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Path-range coverage | present | 342 learner target transitions; every target is larger than that attempt's learner state. | `derived:bounded_scan;input=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:188-34837;count=342` |
| Attempt continuity | derived | Zero mismatches across 341 boundaries: target size from attempt `i` equals learner size at attempt `i+1`. | same bounded 342-row scan |
| Gap distribution, all attempts | derived | Minimum `540,145` at attempt 340, maximum `25,524,739` at attempt 1, median `880,263`, mean `907,067.073`. | `derived:aggregate_target_minus_old;input=342 path transitions;extrema=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34633,188` |
| Gap distribution after first attempt | derived | Maximum `3,369,551` at attempt 2, median `880,218`, mean `834,874.487`. | `derived:aggregate_attempts=2..342;max=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:267` |
| Learner state growth | derived | `903,041,446 -> 1,212,707,746`, `+309,666,300` or `+34.291483%`. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:188,34837`; `derived:end-start` |
| Target state growth | derived | `928,566,185 -> 1,213,258,385`, `+284,692,200` or `+30.659333%`. | same source rows; `derived:end-start` |
| First observed target at least one billion | derived | Attempt 76 target size `1,000,524,549`; the learner first carries that size at attempt 77. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:7776,7879` |
| Divergence shape | derived | Growth-positive: every sent target is larger than the learner state, and completed attempts contain clean and non-clean work. Exact append/modify/remove composition is ambiguous because dirty counters do not identify mutation cause. | path scan above; [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |

The signed target range from the reconnect log remains authoritative for the state actually sent. Nearby teacher CSV
metrics describe a live state that continues to grow and must not replace that signed snapshot.

| Attempt | Status | Learner CSV near target receipt | Teacher CSV near target receipt | Reconciliation | Source references |
|---:|---:|---:|---:|---|---|
| 1 | present | 903,041,446 | 928,701,864 | Learner equals old path size; teacher live metric is 135,679 above the sent target. Same-second order remains ambiguous because CSV time has no fractional seconds. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:row=1339`; `csv:podlog_solo-mdlt-n12/network-node2_logs/stats/MainNetStats1.csv:row=17307` |
| 170 | present | 1,084,304,229 | 1,085,302,077 | Learner equals old path size; teacher live metric is 101,159 above the sent target. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:row=19852`; `csv:podlog_solo-mdlt-n12/network-node3_logs/stats/MainNetStats2.csv:row=35820` |
| 341 | present | 1,212,164,275 | 1,212,786,790 | Learner equals old path size; teacher live metric is 79,044 above the sent target. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:row=42036`; `csv:podlog_solo-mdlt-n12/network-node3_logs/stats/MainNetStats2.csv:row=58004` |
| 342 | missing | 1,212,707,746 | — | Node 4/internal-node-4 teacher stats end about 7h58m40 before its target response; teacher log range remains present. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:row=42166`; `csv:podlog_solo-mdlt-n12/network-node5_logs/stats/MainNetStats4.csv:row=48561`; `log:podlog_solo-mdlt-n12/network-node5_logs/swirlds.log:9680` |

| Attempt | Status | Storage/lifecycle stage observation | Source references |
|---:|---:|---|---|
| 1 | present | Learner state changes `903,041,446 -> 928,566,185`; cumulative flush count `55 -> 2,159`; pipeline size `2`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:rows=1339,1676` |
| 170 | present | State `1,084,304,229 -> 1,085,200,918`; flush count `91,464 -> 92,096`; pipeline size `2`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:rows=19852,19959` |
| 341 | present | State `1,212,164,275 -> 1,212,707,746`; flush count `230,153 -> 231,076`; pipeline size `2`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:rows=42036,42145` |
| 342 | present | Through `01:57:21`, state remains `1,212,707,746` rather than target `1,213,258,385`; flush count advances `231,076 -> 231,125`; pipeline size `2`. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:rows=42166,42172` |

Flush counts are storage context only, not traversal counts or causes. Selected teacher snapshots report
`accountsUsed=300,000,713`, `tokensUsed=1,000`, `nftsUsed=300,000,000`, and `topicsUsed=100,000`; learner
`tokenAssociationsUsed` remains `2,667,161` while installed `vmap_size_state` changes, so treating that service counter
as installed-state divergence is ambiguous (`csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:rows=1339,19852,42036`).

| Final-attempt evidence | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Partial VirtualMap stage | present | Target range initializes and hashing/deletion workers start. Logged deletion-stage count `550,639` equals `newFirstLeafPath - oldFirstLeafPath`; it is a path-shift stage count, not evidence of that many application-record removals. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34837-34843` |
| Storage snapshot | present | A state-to-disk write for pre-attempt round `1,696,841`, reason `RECONNECT`, is storage-stage evidence and does not establish target installation or receiver completion. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34844-34846` |
| Target installed in learner stats | missing | `vmap_size_state` remains at the prior target through the final learner stats window. | `csv:podlog_solo-mdlt-n12/network-node1_logs/stats/MainNetStats0.csv:rows=42166,42172` |
| Completion evidence | missing | No metrics, synchronization finish, data report, or receiver finish appears before learner coverage ends. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34830-34847` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Receiver-anchored episode | present | One continuous fall-behind/reconnect episode contains 342 receiver starts and 341 finishes in the supplied learner log. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:176-34847`; `derived:ordinal_receiver_pairing` |
| Episode completion | ambiguous | No `ACTIVE` is observed, and iteration 342 is still in progress when learner logging ends. Eventual receiver and platform outcome cannot be recovered from the supplied learner evidence. | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34830-34847` |
| Complete catch-up duration | missing | No terminal receiver finish followed by `ACTIVE` exists. | files checked: learner log; patterns checked: receiver finish followed by `ACTIVE`; reason: final start unmatched and no `ACTIVE` |
| Ordered iteration table | derived | The compact table below is the ordinal join of receiver starts, synchronization payloads, and receiver finishes. | `derived:ordinal_join;input=log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:181-34847` |

| Iteration | Teacher node | Receiver start UTC | Receiver result | Receiver finish UTC | Synchronization payload |\n|---:|---:|---|---|---|---:|
| 1 | 1 | `2026-07-14 15:54:56.075` (`:181`) | finished | `2026-07-14 16:12:29.789` (`:206`) | 1034.318 s |
| 2 | 2 | `2026-07-14 16:12:36.244` (`:253`) | finished | `2026-07-14 16:17:55.302` (`:285`) | 300.122 s |
| 3 | 3 | `2026-07-14 16:18:06.210` (`:348`) | finished | `2026-07-14 16:22:21.940` (`:380`) | 236.473 s |
| 4 | 4 | `2026-07-14 16:22:30.804` (`:452`) | finished | `2026-07-14 16:26:10.543` (`:484`) | 200.229 s |
| 5 | 1 | `2026-07-14 16:26:19.347` (`:556`) | finished | `2026-07-14 16:30:45.905` (`:588`) | 246.666 s |
| 6 | 3 | `2026-07-14 16:30:56.113` (`:660`) | finished | `2026-07-14 16:34:48.508` (`:692`) | 214.249 s |
| 7 | 5 | `2026-07-14 16:34:58.128` (`:764`) | finished | `2026-07-14 16:39:20.788` (`:793`) | 244.139 s |
| 8 | 6 | `2026-07-14 16:39:29.104` (`:865`) | finished | `2026-07-14 16:43:23.991` (`:894`) | 215.419 s |
| 9 | 3 | `2026-07-14 16:43:34.442` (`:966`) | finished | `2026-07-14 16:47:39.081` (`:995`) | 230.746 s |
| 10 | 5 | `2026-07-14 16:47:48.985` (`:1067`) | finished | `2026-07-14 16:52:14.883` (`:1096`) | 247.331 s |
| 11 | 6 | `2026-07-14 16:52:24.655` (`:1168`) | finished | `2026-07-14 16:56:54.578` (`:1197`) | 251.185 s |
| 12 | 4 | `2026-07-14 16:57:05.324` (`:1269`) | finished | `2026-07-14 17:01:23.271` (`:1298`) | 239.279 s |
| 13 | 1 | `2026-07-14 17:01:32.342` (`:1370`) | finished | `2026-07-14 17:05:55.148` (`:1399`) | 246.036 s |
| 14 | 6 | `2026-07-14 17:06:05.801` (`:1471`) | finished | `2026-07-14 17:10:32.555` (`:1500`) | 248.545 s |
| 15 | 5 | `2026-07-14 17:10:41.966` (`:1572`) | finished | `2026-07-14 17:15:13.088` (`:1601`) | 253.185 s |
| 16 | 1 | `2026-07-14 17:15:22.756` (`:1673`) | finished | `2026-07-14 17:19:44.906` (`:1702`) | 246.710 s |
| 17 | 6 | `2026-07-14 17:19:55.223` (`:1774`) | finished | `2026-07-14 17:24:25.044` (`:1803`) | 250.392 s |
| 18 | 3 | `2026-07-14 17:24:35.112` (`:1875`) | finished | `2026-07-14 17:29:06.349` (`:1904`) | 257.071 s |
| 19 | 1 | `2026-07-14 17:29:15.423` (`:1976`) | finished | `2026-07-14 17:33:48.319` (`:2005`) | 257.126 s |
| 20 | 4 | `2026-07-14 17:33:57.839` (`:2077`) | finished | `2026-07-14 17:38:27.830` (`:2106`) | 251.620 s |
| 21 | 6 | `2026-07-14 17:38:37.464` (`:2178`) | finished | `2026-07-14 17:43:08.744` (`:2207`) | 252.962 s |
| 22 | 5 | `2026-07-14 17:43:18.467` (`:2279`) | finished | `2026-07-14 17:47:57.945` (`:2308`) | 260.151 s |
| 23 | 3 | `2026-07-14 17:48:08.270` (`:2380`) | finished | `2026-07-14 17:52:47.040` (`:2409`) | 263.813 s |
| 24 | 4 | `2026-07-14 17:52:57.379` (`:2481`) | finished | `2026-07-14 17:57:34.579` (`:2510`) | 258.546 s |
| 25 | 1 | `2026-07-14 17:57:44.165` (`:2582`) | finished | `2026-07-14 18:02:19.882` (`:2611`) | 259.334 s |
| 26 | 2 | `2026-07-14 18:02:30.336` (`:2683`) | finished | `2026-07-14 18:07:10.341` (`:2712`) | 261.021 s |
| 27 | 5 | `2026-07-14 18:07:20.798` (`:2784`) | finished | `2026-07-14 18:12:00.331` (`:2813`) | 260.475 s |
| 28 | 3 | `2026-07-14 18:12:10.710` (`:2885`) | finished | `2026-07-14 18:16:54.226` (`:2914`) | 269.323 s |
| 29 | 1 | `2026-07-14 18:17:04.591` (`:2986`) | finished | `2026-07-14 18:21:47.520` (`:3015`) | 267.512 s |
| 30 | 5 | `2026-07-14 18:21:58.062` (`:3087`) | finished | `2026-07-14 18:26:45.874` (`:3116`) | 267.921 s |
| 31 | 2 | `2026-07-14 18:26:56.623` (`:3188`) | finished | `2026-07-14 18:31:46.416` (`:3217`) | 270.344 s |
| 32 | 6 | `2026-07-14 18:31:56.154` (`:3289`) | finished | `2026-07-14 18:36:52.081` (`:3318`) | 277.255 s |
| 33 | 5 | `2026-07-14 18:37:00.022` (`:3392`) | finished | `2026-07-14 18:41:58.539` (`:3421`) | 279.772 s |
| 34 | 3 | `2026-07-14 18:42:09.060` (`:3493`) | finished | `2026-07-14 18:46:57.563` (`:3522`) | 275.100 s |
| 35 | 2 | `2026-07-14 18:47:07.417` (`:3594`) | finished | `2026-07-14 18:52:01.931` (`:3623`) | 279.578 s |
| 36 | 5 | `2026-07-14 18:52:10.468` (`:3697`) | finished | `2026-07-14 18:57:01.233` (`:3726`) | 271.821 s |
| 37 | 3 | `2026-07-14 18:57:11.272` (`:3798`) | finished | `2026-07-14 19:02:07.958` (`:3827`) | 282.459 s |
| 38 | 5 | `2026-07-14 19:02:18.506` (`:3901`) | finished | `2026-07-14 19:07:14.163` (`:3930`) | 276.416 s |
| 39 | 4 | `2026-07-14 19:07:24.549` (`:4002`) | finished | `2026-07-14 19:12:21.789` (`:4031`) | 277.573 s |
| 40 | 1 | `2026-07-14 19:12:31.371` (`:4105`) | finished | `2026-07-14 19:17:23.581` (`:4134`) | 276.760 s |
| 41 | 6 | `2026-07-14 19:17:33.172` (`:4206`) | finished | `2026-07-14 19:22:38.306` (`:4235`) | 286.305 s |
| 42 | 4 | `2026-07-14 19:22:47.806` (`:4309`) | finished | `2026-07-14 19:27:44.626` (`:4338`) | 277.712 s |
| 43 | 2 | `2026-07-14 19:27:54.399` (`:4410`) | finished | `2026-07-14 19:32:54.126` (`:4439`) | 283.883 s |
| 44 | 3 | `2026-07-14 19:33:03.596` (`:4511`) | finished | `2026-07-14 19:38:05.787` (`:4540`) | 286.650 s |
| 45 | 2 | `2026-07-14 19:38:15.393` (`:4612`) | finished | `2026-07-14 19:43:16.505` (`:4641`) | 285.873 s |
| 46 | 3 | `2026-07-14 19:43:25.934` (`:4713`) | finished | `2026-07-14 19:48:25.555` (`:4742`) | 285.546 s |
| 47 | 1 | `2026-07-14 19:48:35.255` (`:4814`) | finished | `2026-07-14 19:53:43.141` (`:4843`) | 290.686 s |
| 48 | 6 | `2026-07-14 19:53:52.571` (`:4917`) | finished | `2026-07-14 19:59:00.401` (`:4946`) | 288.016 s |
| 49 | 5 | `2026-07-14 19:59:10.797` (`:5018`) | finished | `2026-07-14 20:04:23.455` (`:5047`) | 293.272 s |
| 50 | 2 | `2026-07-14 20:04:34.088` (`:5121`) | finished | `2026-07-14 20:09:38.769` (`:5150`) | 288.806 s |
| 51 | 4 | `2026-07-14 20:09:49.121` (`:5222`) | finished | `2026-07-14 20:15:00.584` (`:5251`) | 293.263 s |
| 52 | 1 | `2026-07-14 20:15:09.330` (`:5325`) | finished | `2026-07-14 20:20:21.711` (`:5354`) | 288.862 s |
| 53 | 4 | `2026-07-14 20:20:31.452` (`:5426`) | finished | `2026-07-14 20:25:42.675` (`:5455`) | 291.852 s |
| 54 | 5 | `2026-07-14 20:25:53.268` (`:5529`) | finished | `2026-07-14 20:31:02.392` (`:5558`) | 290.306 s |
| 55 | 6 | `2026-07-14 20:31:13.298` (`:5630`) | finished | `2026-07-14 20:36:31.806` (`:5659`) | 298.989 s |
| 56 | 2 | `2026-07-14 20:36:40.535` (`:5733`) | finished | `2026-07-14 20:41:55.289` (`:5762`) | 299.452 s |
| 57 | 3 | `2026-07-14 20:42:05.233` (`:5834`) | finished | `2026-07-14 20:47:33.030` (`:5863`) | 313.858 s |
| 58 | 2 | `2026-07-14 20:47:42.817` (`:5937`) | finished | `2026-07-14 20:53:11.632` (`:5966`) | 314.298 s |
| 59 | 5 | `2026-07-14 20:53:20.225` (`:6038`) | finished | `2026-07-14 20:58:46.720` (`:6067`) | 306.312 s |
| 60 | 1 | `2026-07-14 20:58:56.299` (`:6139`) | finished | `2026-07-14 21:04:31.914` (`:6168`) | 320.046 s |
| 61 | 2 | `2026-07-14 21:04:40.719` (`:6242`) | finished | `2026-07-14 21:10:09.730` (`:6271`) | 314.883 s |
| 62 | 6 | `2026-07-14 21:10:19.193` (`:6343`) | finished | `2026-07-14 21:15:59.405` (`:6372`) | 320.821 s |
| 63 | 2 | `2026-07-14 21:16:08.101` (`:6446`) | finished | `2026-07-14 21:21:43.972` (`:6475`) | 320.098 s |
| 64 | 5 | `2026-07-14 21:21:52.486` (`:6547`) | finished | `2026-07-14 21:27:34.487` (`:6576`) | 321.756 s |
| 65 | 6 | `2026-07-14 21:27:44.937` (`:6648`) | finished | `2026-07-14 21:33:21.449` (`:6677`) | 322.885 s |
| 66 | 5 | `2026-07-14 21:33:31.393` (`:6751`) | finished | `2026-07-14 21:39:15.339` (`:6780`) | 323.756 s |
| 67 | 2 | `2026-07-14 21:39:24.994` (`:6852`) | finished | `2026-07-14 21:44:55.693` (`:6881`) | 315.314 s |
| 68 | 4 | `2026-07-14 21:45:05.387` (`:6953`) | finished | `2026-07-14 21:50:53.410` (`:6982`) | 317.179 s |
| 69 | 3 | `2026-07-14 21:51:03.123` (`:7056`) | finished | `2026-07-14 21:56:37.225` (`:7085`) | 318.917 s |
| 70 | 1 | `2026-07-14 21:56:46.082` (`:7157`) | finished | `2026-07-14 22:02:21.954` (`:7186`) | 318.640 s |
| 71 | 3 | `2026-07-14 22:02:31.793` (`:7260`) | finished | `2026-07-14 22:08:18.117` (`:7289`) | 331.012 s |
| 72 | 2 | `2026-07-14 22:08:28.918` (`:7361`) | finished | `2026-07-14 22:14:10.867` (`:7390`) | 325.736 s |
| 73 | 3 | `2026-07-14 22:14:19.848` (`:7464`) | finished | `2026-07-14 22:20:05.218` (`:7493`) | 330.347 s |
| 74 | 5 | `2026-07-14 22:20:14.285` (`:7565`) | finished | `2026-07-14 22:26:03.366` (`:7594`) | 328.130 s |
| 75 | 2 | `2026-07-14 22:26:13.949` (`:7668`) | finished | `2026-07-14 22:31:53.642` (`:7697`) | 324.309 s |
| 76 | 1 | `2026-07-14 22:32:03.731` (`:7769`) | finished | `2026-07-14 22:37:43.766` (`:7798`) | 322.908 s |
| 77 | 6 | `2026-07-14 22:37:53.099` (`:7872`) | finished | `2026-07-14 22:43:35.336` (`:7901`) | 327.212 s |
| 78 | 1 | `2026-07-14 22:43:45.385` (`:7973`) | finished | `2026-07-14 22:49:29.059` (`:8002`) | 326.279 s |
| 79 | 3 | `2026-07-14 22:49:40.270` (`:8076`) | finished | `2026-07-14 22:55:27.782` (`:8105`) | 332.938 s |
| 80 | 1 | `2026-07-14 22:55:37.173` (`:8177`) | finished | `2026-07-14 23:01:21.125` (`:8206`) | 327.249 s |
| 81 | 4 | `2026-07-14 23:01:30.053` (`:8280`) | finished | `2026-07-14 23:07:19.588` (`:8309`) | 329.409 s |
| 82 | 1 | `2026-07-14 23:07:29.594` (`:8381`) | finished | `2026-07-14 23:13:14.544` (`:8410`) | 327.972 s |
| 83 | 5 | `2026-07-14 23:13:24.233` (`:8482`) | finished | `2026-07-14 23:19:20.489` (`:8511`) | 335.863 s |
| 84 | 1 | `2026-07-14 23:19:30.290` (`:8585`) | finished | `2026-07-14 23:25:15.497` (`:8614`) | 326.882 s |
| 85 | 5 | `2026-07-14 23:25:25.758` (`:8686`) | finished | `2026-07-14 23:30:41.183` (`:8715`) | 295.093 s |
| 86 | 6 | `2026-07-14 23:30:51.071` (`:8789`) | finished | `2026-07-14 23:35:30.809` (`:8818`) | 264.321 s |
| 87 | 4 | `2026-07-14 23:35:40.136` (`:8890`) | finished | `2026-07-14 23:40:31.248` (`:8919`) | 270.728 s |
| 88 | 1 | `2026-07-14 23:40:38.884` (`:8991`) | finished | `2026-07-14 23:45:31.853` (`:9020`) | 276.120 s |
| 89 | 3 | `2026-07-14 23:45:40.468` (`:9094`) | finished | `2026-07-14 23:50:41.800` (`:9123`) | 285.832 s |
| 90 | 5 | `2026-07-14 23:50:51.729` (`:9195`) | finished | `2026-07-14 23:55:54.521` (`:9224`) | 282.118 s |
| 91 | 6 | `2026-07-14 23:56:04.220` (`:9296`) | finished | `2026-07-15 00:00:57.727` (`:9325`) | 278.464 s |
| 92 | 5 | `2026-07-15 00:01:06.993` (`:9397`) | finished | `2026-07-15 00:06:11.569` (`:9426`) | 284.349 s |
| 93 | 2 | `2026-07-15 00:06:21.382` (`:9500`) | finished | `2026-07-15 00:11:16.056` (`:9529`) | 279.165 s |
| 94 | 3 | `2026-07-15 00:11:25.730` (`:9601`) | finished | `2026-07-15 00:16:30.181` (`:9630`) | 288.846 s |
| 95 | 1 | `2026-07-15 00:16:40.290` (`:9704`) | finished | `2026-07-15 00:21:45.158` (`:9733`) | 287.188 s |
| 96 | 5 | `2026-07-15 00:21:54.076` (`:9805`) | finished | `2026-07-15 00:26:59.414` (`:9834`) | 285.342 s |
| 97 | 3 | `2026-07-15 00:27:10.162` (`:9908`) | finished | `2026-07-15 00:32:19.134` (`:9937`) | 294.155 s |
| 98 | 5 | `2026-07-15 00:32:29.065` (`:10009`) | finished | `2026-07-15 00:37:38.774` (`:10038`) | 288.476 s |
| 99 | 2 | `2026-07-15 00:37:48.448` (`:10112`) | finished | `2026-07-15 00:42:56.386` (`:10141`) | 292.245 s |
| 100 | 6 | `2026-07-15 00:43:05.398` (`:10213`) | finished | `2026-07-15 00:48:05.003` (`:10242`) | 284.101 s |
| 101 | 5 | `2026-07-15 00:48:13.805` (`:10314`) | finished | `2026-07-15 00:53:22.067` (`:10343`) | 287.375 s |
| 102 | 2 | `2026-07-15 00:53:31.999` (`:10417`) | finished | `2026-07-15 00:58:34.614` (`:10446`) | 287.019 s |
| 103 | 5 | `2026-07-15 00:58:44.293` (`:10518`) | finished | `2026-07-15 01:03:50.921` (`:10547`) | 286.030 s |
| 104 | 2 | `2026-07-15 01:04:00.208` (`:10621`) | finished | `2026-07-15 01:09:05.958` (`:10650`) | 289.096 s |
| 105 | 3 | `2026-07-15 01:09:15.668` (`:10722`) | finished | `2026-07-15 01:14:22.339` (`:10751`) | 290.964 s |
| 106 | 1 | `2026-07-15 01:14:32.240` (`:10825`) | finished | `2026-07-15 01:19:40.882` (`:10854`) | 290.872 s |
| 107 | 3 | `2026-07-15 01:19:50.840` (`:10926`) | finished | `2026-07-15 01:24:58.048` (`:10955`) | 292.026 s |
| 108 | 1 | `2026-07-15 01:25:05.868` (`:11027`) | finished | `2026-07-15 01:30:15.503` (`:11056`) | 292.741 s |
| 109 | 2 | `2026-07-15 01:30:24.623` (`:11128`) | finished | `2026-07-15 01:35:32.542` (`:11157`) | 291.880 s |
| 110 | 3 | `2026-07-15 01:35:42.365` (`:11231`) | finished | `2026-07-15 01:40:52.531` (`:11260`) | 295.602 s |
| 111 | 2 | `2026-07-15 01:41:02.091` (`:11332`) | finished | `2026-07-15 01:46:11.476` (`:11361`) | 293.578 s |
| 112 | 6 | `2026-07-15 01:46:20.612` (`:11435`) | finished | `2026-07-15 01:51:32.721` (`:11464`) | 297.322 s |
| 113 | 2 | `2026-07-15 01:51:41.705` (`:11536`) | finished | `2026-07-15 01:56:56.105` (`:11565`) | 297.442 s |
| 114 | 6 | `2026-07-15 01:57:06.213` (`:11637`) | finished | `2026-07-15 02:02:24.345` (`:11666`) | 303.008 s |
| 115 | 1 | `2026-07-15 02:02:33.232` (`:11738`) | finished | `2026-07-15 02:07:50.059` (`:11767`) | 298.449 s |
| 116 | 5 | `2026-07-15 02:07:58.669` (`:11841`) | finished | `2026-07-15 02:13:23.538` (`:11870`) | 303.982 s |
| 117 | 4 | `2026-07-15 02:13:33.151` (`:11942`) | finished | `2026-07-15 02:18:51.754` (`:11971`) | 297.546 s |
| 118 | 5 | `2026-07-15 02:19:00.670` (`:12045`) | finished | `2026-07-15 02:24:21.178` (`:12074`) | 299.535 s |
| 119 | 4 | `2026-07-15 02:24:29.911` (`:12146`) | finished | `2026-07-15 02:29:52.675` (`:12175`) | 301.898 s |
| 120 | 2 | `2026-07-15 02:30:02.556` (`:12249`) | finished | `2026-07-15 02:35:14.992` (`:12278`) | 296.475 s |
| 121 | 6 | `2026-07-15 02:35:23.963` (`:12350`) | finished | `2026-07-15 02:40:46.970` (`:12379`) | 306.697 s |
| 122 | 2 | `2026-07-15 02:40:55.857` (`:12451`) | finished | `2026-07-15 02:46:18.903` (`:12480`) | 306.820 s |
| 123 | 5 | `2026-07-15 02:46:27.944` (`:12554`) | finished | `2026-07-15 02:51:51.030` (`:12583`) | 301.945 s |
| 124 | 2 | `2026-07-15 02:52:00.769` (`:12655`) | finished | `2026-07-15 02:57:16.089` (`:12684`) | 298.909 s |
| 125 | 1 | `2026-07-15 02:57:26.087` (`:12756`) | finished | `2026-07-15 03:02:51.741` (`:12785`) | 307.998 s |
| 126 | 5 | `2026-07-15 03:03:01.424` (`:12859`) | finished | `2026-07-15 03:08:33.391` (`:12888`) | 311.315 s |
| 127 | 6 | `2026-07-15 03:08:43.561` (`:12960`) | finished | `2026-07-15 03:14:07.166` (`:12989`) | 308.561 s |
| 128 | 5 | `2026-07-15 03:14:16.906` (`:13061`) | finished | `2026-07-15 03:19:42.486` (`:13090`) | 305.162 s |
| 129 | 3 | `2026-07-15 03:19:52.410` (`:13164`) | finished | `2026-07-15 03:25:22.196` (`:13193`) | 314.214 s |
| 130 | 2 | `2026-07-15 03:25:31.379` (`:13265`) | finished | `2026-07-15 03:30:57.780` (`:13294`) | 310.125 s |
| 131 | 4 | `2026-07-15 03:31:07.673` (`:13366`) | finished | `2026-07-15 03:36:39.790` (`:13395`) | 311.995 s |
| 132 | 3 | `2026-07-15 03:36:49.812` (`:13469`) | finished | `2026-07-15 03:42:17.311` (`:13498`) | 311.463 s |
| 133 | 1 | `2026-07-15 03:42:27.262` (`:13570`) | finished | `2026-07-15 03:47:59.286` (`:13599`) | 314.244 s |
| 134 | 2 | `2026-07-15 03:48:09.533` (`:13673`) | finished | `2026-07-15 03:53:43.204` (`:13702`) | 316.750 s |
| 135 | 1 | `2026-07-15 03:53:52.073` (`:13774`) | finished | `2026-07-15 03:59:23.319` (`:13803`) | 313.766 s |
| 136 | 2 | `2026-07-15 03:59:32.942` (`:13877`) | finished | `2026-07-15 04:05:01.531` (`:13906`) | 312.470 s |
| 137 | 4 | `2026-07-15 04:05:11.310` (`:13978`) | finished | `2026-07-15 04:10:49.145` (`:14007`) | 316.865 s |
| 138 | 1 | `2026-07-15 04:10:58.359` (`:14081`) | finished | `2026-07-15 04:16:31.054` (`:14110`) | 314.675 s |
| 139 | 6 | `2026-07-15 04:16:39.727` (`:14182`) | finished | `2026-07-15 04:22:09.154` (`:14211`) | 313.912 s |
| 140 | 3 | `2026-07-15 04:22:18.829` (`:14285`) | finished | `2026-07-15 04:27:56.697` (`:14314`) | 321.774 s |
| 141 | 5 | `2026-07-15 04:28:05.570` (`:14386`) | finished | `2026-07-15 04:33:43.970` (`:14415`) | 316.535 s |
| 142 | 2 | `2026-07-15 04:33:54.048` (`:14487`) | finished | `2026-07-15 04:39:29.765` (`:14516`) | 319.217 s |
| 143 | 5 | `2026-07-15 04:39:39.592` (`:14590`) | finished | `2026-07-15 04:45:18.077` (`:14619`) | 316.753 s |
| 144 | 3 | `2026-07-15 04:45:27.628` (`:14691`) | finished | `2026-07-15 04:51:01.680` (`:14720`) | 317.698 s |
| 145 | 5 | `2026-07-15 04:51:11.581` (`:14794`) | finished | `2026-07-15 04:56:53.413` (`:14823`) | 320.658 s |
| 146 | 6 | `2026-07-15 04:57:02.408` (`:14895`) | finished | `2026-07-15 05:02:44.358` (`:14924`) | 325.803 s |
| 147 | 4 | `2026-07-15 05:02:54.184` (`:14996`) | finished | `2026-07-15 05:08:24.890` (`:15025`) | 315.149 s |
| 148 | 5 | `2026-07-15 05:08:34.718` (`:15097`) | finished | `2026-07-15 05:14:11.716` (`:15126`) | 316.398 s |
| 149 | 1 | `2026-07-15 05:14:19.814` (`:15200`) | finished | `2026-07-15 05:20:03.512` (`:15229`) | 326.632 s |
| 150 | 6 | `2026-07-15 05:20:14.380` (`:15301`) | finished | `2026-07-15 05:25:53.045` (`:15330`) | 322.714 s |
| 151 | 2 | `2026-07-15 05:26:02.227` (`:15404`) | finished | `2026-07-15 05:31:45.751` (`:15433`) | 326.413 s |
| 152 | 5 | `2026-07-15 05:31:55.481` (`:15505`) | finished | `2026-07-15 05:37:40.450` (`:15534`) | 322.576 s |
| 153 | 1 | `2026-07-15 05:37:49.605` (`:15608`) | finished | `2026-07-15 05:43:39.895` (`:15637`) | 332.142 s |
| 154 | 6 | `2026-07-15 05:43:50.054` (`:15709`) | finished | `2026-07-15 05:49:50.024` (`:15738`) | 344.341 s |
| 155 | 5 | `2026-07-15 05:49:59.761` (`:15812`) | finished | `2026-07-15 05:55:57.831` (`:15841`) | 336.652 s |
| 156 | 4 | `2026-07-15 05:56:07.701` (`:15913`) | finished | `2026-07-15 06:01:59.781` (`:15942`) | 336.931 s |
| 157 | 1 | `2026-07-15 06:02:09.157` (`:16016`) | finished | `2026-07-15 06:08:06.405` (`:16045`) | 338.243 s |
| 158 | 6 | `2026-07-15 06:08:16.430` (`:16117`) | finished | `2026-07-15 06:14:07.746` (`:16146`) | 335.058 s |
| 159 | 5 | `2026-07-15 06:14:17.610` (`:16218`) | finished | `2026-07-15 06:20:15.819` (`:16247`) | 336.840 s |
| 160 | 1 | `2026-07-15 06:20:25.753` (`:16319`) | finished | `2026-07-15 06:26:18.880` (`:16348`) | 334.813 s |
| 161 | 3 | `2026-07-15 06:26:29.048` (`:16422`) | finished | `2026-07-15 06:32:14.715` (`:16451`) | 331.250 s |
| 162 | 5 | `2026-07-15 06:32:24.804` (`:16523`) | finished | `2026-07-15 06:38:20.228` (`:16552`) | 332.872 s |
| 163 | 4 | `2026-07-15 06:38:29.922` (`:16626`) | finished | `2026-07-15 06:44:21.212` (`:16655`) | 334.956 s |
| 164 | 2 | `2026-07-15 06:44:31.271` (`:16727`) | finished | `2026-07-15 06:50:24.303` (`:16756`) | 336.943 s |
| 165 | 3 | `2026-07-15 06:50:34.158` (`:16828`) | finished | `2026-07-15 06:56:26.579` (`:16857`) | 336.313 s |
| 166 | 2 | `2026-07-15 06:56:35.561` (`:16929`) | finished | `2026-07-15 07:02:24.023` (`:16958`) | 333.575 s |
| 167 | 5 | `2026-07-15 07:02:34.108` (`:17030`) | finished | `2026-07-15 07:08:29.080` (`:17059`) | 332.671 s |
| 168 | 2 | `2026-07-15 07:08:38.938` (`:17133`) | finished | `2026-07-15 07:14:28.462` (`:17162`) | 334.106 s |
| 169 | 4 | `2026-07-15 07:14:38.252` (`:17234`) | finished | `2026-07-15 07:20:27.816` (`:17263`) | 333.191 s |
| 170 | 2 | `2026-07-15 07:20:37.572` (`:17335`) | finished | `2026-07-15 07:26:38.464` (`:17364`) | 344.173 s |
| 171 | 1 | `2026-07-15 07:26:48.462` (`:17438`) | finished | `2026-07-15 07:32:52.184` (`:17467`) | 344.628 s |
| 172 | 4 | `2026-07-15 07:33:02.045` (`:17539`) | finished | `2026-07-15 07:38:56.110` (`:17568`) | 337.411 s |
| 173 | 5 | `2026-07-15 07:39:04.818` (`:17640`) | finished | `2026-07-15 07:44:57.827` (`:17669`) | 336.013 s |
| 174 | 1 | `2026-07-15 07:45:07.720` (`:17741`) | finished | `2026-07-15 07:51:23.526` (`:17770`) | 347.608 s |
| 175 | 2 | `2026-07-15 07:51:33.973` (`:17844`) | finished | `2026-07-15 07:57:27.945` (`:17873`) | 338.335 s |
| 176 | 4 | `2026-07-15 07:57:37.679` (`:17945`) | finished | `2026-07-15 08:03:33.838` (`:17974`) | 340.949 s |
| 177 | 5 | `2026-07-15 08:03:43.730` (`:18048`) | finished | `2026-07-15 08:09:36.148` (`:18077`) | 336.493 s |
| 178 | 1 | `2026-07-15 08:09:44.891` (`:18149`) | finished | `2026-07-15 08:15:46.183` (`:18178`) | 341.594 s |
| 179 | 6 | `2026-07-15 08:15:56.090` (`:18250`) | finished | `2026-07-15 08:21:56.923` (`:18279`) | 344.638 s |
| 180 | 4 | `2026-07-15 08:22:06.925` (`:18351`) | finished | `2026-07-15 08:27:58.514` (`:18380`) | 336.018 s |
| 181 | 3 | `2026-07-15 08:28:08.795` (`:18454`) | finished | `2026-07-15 08:34:08.854` (`:18483`) | 343.340 s |
| 182 | 6 | `2026-07-15 08:34:18.031` (`:18555`) | finished | `2026-07-15 08:40:01.242` (`:18584`) | 326.932 s |
| 183 | 2 | `2026-07-15 08:40:11.129` (`:18658`) | finished | `2026-07-15 08:46:10.336` (`:18687`) | 340.923 s |
| 184 | 6 | `2026-07-15 08:46:19.390` (`:18759`) | finished | `2026-07-15 08:52:13.007` (`:18788`) | 337.256 s |
| 185 | 2 | `2026-07-15 08:52:23.036` (`:18862`) | finished | `2026-07-15 08:58:14.301` (`:18891`) | 333.251 s |
| 186 | 6 | `2026-07-15 08:58:24.402` (`:18963`) | finished | `2026-07-15 09:04:15.365` (`:18992`) | 334.936 s |
| 187 | 4 | `2026-07-15 09:04:23.325` (`:19064`) | finished | `2026-07-15 09:10:16.468` (`:19093`) | 336.402 s |
| 188 | 3 | `2026-07-15 09:10:24.608` (`:19165`) | finished | `2026-07-15 09:16:20.699` (`:19194`) | 339.829 s |
| 189 | 5 | `2026-07-15 09:16:29.616` (`:19268`) | finished | `2026-07-15 09:22:28.936` (`:19297`) | 343.301 s |
| 190 | 6 | `2026-07-15 09:22:39.105` (`:19369`) | finished | `2026-07-15 09:28:40.245` (`:19398`) | 344.691 s |
| 191 | 2 | `2026-07-15 09:28:49.302` (`:19472`) | finished | `2026-07-15 09:34:46.697` (`:19501`) | 339.521 s |
| 192 | 6 | `2026-07-15 09:34:56.662` (`:19573`) | finished | `2026-07-15 09:40:53.006` (`:19602`) | 339.869 s |
| 193 | 1 | `2026-07-15 09:41:02.969` (`:19676`) | finished | `2026-07-15 09:46:57.400` (`:19705`) | 335.447 s |
| 194 | 3 | `2026-07-15 09:47:07.169` (`:19777`) | finished | `2026-07-15 09:52:59.782` (`:19806`) | 336.590 s |
| 195 | 1 | `2026-07-15 09:53:09.622` (`:19880`) | finished | `2026-07-15 09:59:07.499` (`:19909`) | 338.675 s |
| 196 | 5 | `2026-07-15 09:59:16.287` (`:19981`) | finished | `2026-07-15 10:05:15.066` (`:20010`) | 342.440 s |
| 197 | 2 | `2026-07-15 10:05:25.169` (`:20082`) | finished | `2026-07-15 10:11:23.002` (`:20111`) | 341.192 s |
| 198 | 1 | `2026-07-15 10:11:32.979` (`:20185`) | finished | `2026-07-15 10:17:41.025` (`:20214`) | 348.146 s |
| 199 | 3 | `2026-07-15 10:17:51.238` (`:20286`) | finished | `2026-07-15 10:23:55.944` (`:20315`) | 348.495 s |
| 200 | 6 | `2026-07-15 10:24:06.226` (`:20387`) | finished | `2026-07-15 10:30:09.695` (`:20416`) | 346.665 s |
| 201 | 2 | `2026-07-15 10:30:17.581` (`:20490`) | finished | `2026-07-15 10:36:17.748` (`:20519`) | 341.536 s |
| 202 | 4 | `2026-07-15 10:36:26.867` (`:20591`) | finished | `2026-07-15 10:42:26.464` (`:20620`) | 342.835 s |
| 203 | 5 | `2026-07-15 10:42:36.126` (`:20692`) | finished | `2026-07-15 10:48:36.501` (`:20721`) | 343.105 s |
| 204 | 1 | `2026-07-15 10:48:46.100` (`:20793`) | finished | `2026-07-15 10:54:55.598` (`:20822`) | 349.922 s |
| 205 | 4 | `2026-07-15 10:55:05.881` (`:20894`) | finished | `2026-07-15 11:01:11.440` (`:20923`) | 349.474 s |
| 206 | 5 | `2026-07-15 11:01:21.222` (`:20997`) | finished | `2026-07-15 11:07:27.523` (`:21026`) | 348.973 s |
| 207 | 1 | `2026-07-15 11:07:36.173` (`:21098`) | finished | `2026-07-15 11:13:45.219` (`:21127`) | 349.484 s |
| 208 | 5 | `2026-07-15 11:13:55.828` (`:21199`) | finished | `2026-07-15 11:20:05.255` (`:21228`) | 352.715 s |
| 209 | 1 | `2026-07-15 11:20:15.606` (`:21300`) | finished | `2026-07-15 11:26:35.654` (`:21329`) | 359.782 s |
| 210 | 3 | `2026-07-15 11:26:46.067` (`:21401`) | finished | `2026-07-15 11:32:54.797` (`:21430`) | 352.194 s |
| 211 | 2 | `2026-07-15 11:33:03.474` (`:21502`) | finished | `2026-07-15 11:39:12.745` (`:21531`) | 352.959 s |
| 212 | 4 | `2026-07-15 11:39:21.403` (`:21605`) | finished | `2026-07-15 11:45:34.561` (`:21634`) | 356.342 s |
| 213 | 2 | `2026-07-15 11:45:44.428` (`:21706`) | finished | `2026-07-15 11:51:55.976` (`:21735`) | 354.145 s |
| 214 | 5 | `2026-07-15 11:52:05.847` (`:21807`) | finished | `2026-07-15 11:58:15.820` (`:21836`) | 352.920 s |
| 215 | 3 | `2026-07-15 11:58:24.339` (`:21908`) | finished | `2026-07-15 12:04:35.060` (`:21937`) | 354.615 s |
| 216 | 6 | `2026-07-15 12:04:43.768` (`:22009`) | finished | `2026-07-15 12:10:55.801` (`:22038`) | 354.794 s |
| 217 | 4 | `2026-07-15 12:11:04.709` (`:22110`) | finished | `2026-07-15 12:17:22.237` (`:22139`) | 361.657 s |
| 218 | 6 | `2026-07-15 12:17:32.258` (`:22213`) | finished | `2026-07-15 12:23:45.824` (`:22242`) | 356.599 s |
| 219 | 1 | `2026-07-15 12:23:55.591` (`:22314`) | finished | `2026-07-15 12:30:13.713` (`:22343`) | 358.173 s |
| 220 | 6 | `2026-07-15 12:30:22.784` (`:22417`) | finished | `2026-07-15 12:36:40.969` (`:22446`) | 360.809 s |
| 221 | 2 | `2026-07-15 12:36:50.447` (`:22518`) | finished | `2026-07-15 12:43:13.869` (`:22547`) | 366.602 s |
| 222 | 4 | `2026-07-15 12:43:23.568` (`:22621`) | finished | `2026-07-15 12:49:43.523` (`:22650`) | 363.294 s |
| 223 | 3 | `2026-07-15 12:49:53.380` (`:22722`) | finished | `2026-07-15 12:56:16.560` (`:22751`) | 365.767 s |
| 224 | 4 | `2026-07-15 12:56:26.257` (`:22823`) | finished | `2026-07-15 13:02:48.535` (`:22852`) | 364.679 s |
| 225 | 1 | `2026-07-15 13:02:58.715` (`:22926`) | finished | `2026-07-15 13:09:23.281` (`:22955`) | 364.904 s |
| 226 | 2 | `2026-07-15 13:09:32.205` (`:23027`) | finished | `2026-07-15 13:15:55.961` (`:23056`) | 366.346 s |
| 227 | 5 | `2026-07-15 13:16:04.839` (`:23128`) | finished | `2026-07-15 13:22:29.306` (`:23157`) | 366.203 s |
| 228 | 1 | `2026-07-15 13:22:39.182` (`:23231`) | finished | `2026-07-15 13:29:15.673` (`:23260`) | 375.520 s |
| 229 | 4 | `2026-07-15 13:29:24.932` (`:23332`) | finished | `2026-07-15 13:35:49.032` (`:23361`) | 366.983 s |
| 230 | 5 | `2026-07-15 13:35:58.416` (`:23435`) | finished | `2026-07-15 13:42:19.944` (`:23464`) | 363.576 s |
| 231 | 1 | `2026-07-15 13:42:28.036` (`:23536`) | finished | `2026-07-15 13:48:50.437` (`:23565`) | 362.278 s |
| 232 | 2 | `2026-07-15 13:49:01.572` (`:23639`) | finished | `2026-07-15 13:55:23.413` (`:23668`) | 364.482 s |
| 233 | 1 | `2026-07-15 13:55:33.668` (`:23740`) | finished | `2026-07-15 14:01:59.309` (`:23769`) | 368.888 s |
| 234 | 4 | `2026-07-15 14:02:09.105` (`:23843`) | finished | `2026-07-15 14:08:30.197` (`:23872`) | 364.074 s |
| 235 | 5 | `2026-07-15 14:08:39.803` (`:23944`) | finished | `2026-07-15 14:14:59.826` (`:23973`) | 361.718 s |
| 236 | 3 | `2026-07-15 14:15:08.652` (`:24047`) | finished | `2026-07-15 14:21:51.730` (`:24076`) | 376.146 s |
| 237 | 2 | `2026-07-15 14:22:01.163` (`:24148`) | finished | `2026-07-15 14:28:22.748` (`:24177`) | 363.773 s |
| 238 | 5 | `2026-07-15 14:28:31.472` (`:24251`) | finished | `2026-07-15 14:34:56.569` (`:24280`) | 366.171 s |
| 239 | 4 | `2026-07-15 14:35:06.328` (`:24352`) | finished | `2026-07-15 14:41:27.018` (`:24381`) | 363.848 s |
| 240 | 2 | `2026-07-15 14:41:36.540` (`:24453`) | finished | `2026-07-15 14:47:55.325` (`:24482`) | 361.355 s |
| 241 | 1 | `2026-07-15 14:48:05.014` (`:24554`) | finished | `2026-07-15 14:54:28.008` (`:24583`) | 365.419 s |
| 242 | 3 | `2026-07-15 14:54:36.700` (`:24655`) | finished | `2026-07-15 15:01:05.160` (`:24684`) | 370.889 s |
| 243 | 1 | `2026-07-15 15:01:14.735` (`:24756`) | finished | `2026-07-15 15:07:38.131` (`:24785`) | 365.900 s |
| 244 | 4 | `2026-07-15 15:07:47.707` (`:24857`) | finished | `2026-07-15 15:14:12.224` (`:24886`) | 366.862 s |
| 245 | 5 | `2026-07-15 15:14:21.964` (`:24960`) | finished | `2026-07-15 15:20:52.046` (`:24989`) | 370.917 s |
| 246 | 1 | `2026-07-15 15:21:00.645` (`:25061`) | finished | `2026-07-15 15:27:33.800` (`:25090`) | 375.712 s |
| 247 | 6 | `2026-07-15 15:27:43.181` (`:25164`) | finished | `2026-07-15 15:34:08.934` (`:25193`) | 367.492 s |
| 248 | 2 | `2026-07-15 15:34:18.737` (`:25265`) | finished | `2026-07-15 15:40:53.807` (`:25294`) | 377.075 s |
| 249 | 5 | `2026-07-15 15:41:02.718` (`:25368`) | finished | `2026-07-15 15:47:30.092` (`:25398`) | 368.836 s |
| 250 | 2 | `2026-07-15 15:47:39.883` (`:25470`) | finished | `2026-07-15 15:54:12.264` (`:25499`) | 374.336 s |
| 251 | 3 | `2026-07-15 15:54:22.072` (`:25573`) | finished | `2026-07-15 16:00:51.572` (`:25602`) | 371.046 s |
| 252 | 1 | `2026-07-15 16:01:01.336` (`:25674`) | finished | `2026-07-15 16:07:23.933` (`:25703`) | 365.497 s |
| 253 | 4 | `2026-07-15 16:07:31.585` (`:25775`) | finished | `2026-07-15 16:13:58.942` (`:25804`) | 370.330 s |
| 254 | 6 | `2026-07-15 16:14:06.840` (`:25878`) | finished | `2026-07-15 16:20:31.069` (`:25907`) | 365.926 s |
| 255 | 2 | `2026-07-15 16:20:40.858` (`:25979`) | finished | `2026-07-15 16:27:10.000` (`:26008`) | 372.116 s |
| 256 | 5 | `2026-07-15 16:27:20.083` (`:26082`) | finished | `2026-07-15 16:33:47.308` (`:26111`) | 369.770 s |
| 257 | 1 | `2026-07-15 16:33:56.988` (`:26183`) | finished | `2026-07-15 16:40:30.321` (`:26212`) | 375.461 s |
| 258 | 6 | `2026-07-15 16:40:39.713` (`:26286`) | finished | `2026-07-15 16:47:09.919` (`:26315`) | 372.156 s |
| 259 | 2 | `2026-07-15 16:47:19.804` (`:26387`) | finished | `2026-07-15 16:53:55.030` (`:26416`) | 377.177 s |
| 260 | 4 | `2026-07-15 16:54:04.033` (`:26488`) | finished | `2026-07-15 17:00:46.559` (`:26517`) | 384.852 s |
| 261 | 2 | `2026-07-15 17:00:56.316` (`:26589`) | finished | `2026-07-15 17:07:31.119` (`:26618`) | 377.298 s |
| 262 | 3 | `2026-07-15 17:07:39.824` (`:26692`) | finished | `2026-07-15 17:14:15.802` (`:26721`) | 378.004 s |
| 263 | 2 | `2026-07-15 17:14:24.225` (`:26793`) | finished | `2026-07-15 17:21:01.560` (`:26822`) | 380.426 s |
| 264 | 3 | `2026-07-15 17:21:10.363` (`:26896`) | finished | `2026-07-15 17:27:43.353` (`:26925`) | 374.600 s |
| 265 | 5 | `2026-07-15 17:27:53.219` (`:26997`) | finished | `2026-07-15 17:34:16.026` (`:27026`) | 365.042 s |
| 266 | 4 | `2026-07-15 17:34:24.294` (`:27098`) | finished | `2026-07-15 17:40:48.025` (`:27127`) | 365.078 s |
| 267 | 5 | `2026-07-15 17:40:57.958` (`:27201`) | finished | `2026-07-15 17:47:28.039` (`:27230`) | 370.485 s |
| 268 | 3 | `2026-07-15 17:47:37.641` (`:27302`) | finished | `2026-07-15 17:54:08.289` (`:27331`) | 372.210 s |
| 269 | 6 | `2026-07-15 17:54:17.192` (`:27405`) | finished | `2026-07-15 18:00:50.015` (`:27434`) | 373.249 s |
| 270 | 1 | `2026-07-15 18:00:59.760` (`:27506`) | finished | `2026-07-15 18:07:29.061` (`:27535`) | 370.801 s |
| 271 | 3 | `2026-07-15 18:07:38.669` (`:27609`) | finished | `2026-07-15 18:14:07.698` (`:27638`) | 370.417 s |
| 272 | 5 | `2026-07-15 18:14:17.311` (`:27710`) | finished | `2026-07-15 18:20:48.839` (`:27739`) | 373.579 s |
| 273 | 3 | `2026-07-15 18:20:58.349` (`:27813`) | finished | `2026-07-15 18:27:33.416` (`:27842`) | 376.474 s |
| 274 | 5 | `2026-07-15 18:27:42.239` (`:27914`) | finished | `2026-07-15 18:34:16.905` (`:27943`) | 377.278 s |
| 275 | 4 | `2026-07-15 18:34:25.802` (`:28015`) | finished | `2026-07-15 18:41:00.141` (`:28044`) | 376.635 s |
| 276 | 1 | `2026-07-15 18:41:09.672` (`:28118`) | finished | `2026-07-15 18:47:38.201` (`:28147`) | 369.443 s |
| 277 | 6 | `2026-07-15 18:47:47.118` (`:28219`) | finished | `2026-07-15 18:54:15.965` (`:28248`) | 369.134 s |
| 278 | 2 | `2026-07-15 18:54:23.570` (`:28320`) | finished | `2026-07-15 19:00:55.962` (`:28349`) | 374.975 s |
| 279 | 4 | `2026-07-15 19:01:05.395` (`:28421`) | finished | `2026-07-15 19:07:39.206` (`:28450`) | 376.090 s |
| 280 | 3 | `2026-07-15 19:07:48.027` (`:28524`) | finished | `2026-07-15 19:14:22.972` (`:28553`) | 375.677 s |
| 281 | 2 | `2026-07-15 19:14:32.632` (`:28625`) | finished | `2026-07-15 19:21:00.769` (`:28654`) | 369.211 s |
| 282 | 1 | `2026-07-15 19:21:09.361` (`:28728`) | finished | `2026-07-15 19:27:34.162` (`:28757`) | 366.559 s |
| 283 | 5 | `2026-07-15 19:27:43.868` (`:28829`) | finished | `2026-07-15 19:34:16.186` (`:28858`) | 373.407 s |
| 284 | 6 | `2026-07-15 19:34:25.902` (`:28930`) | finished | `2026-07-15 19:40:59.895` (`:28959`) | 374.383 s |
| 285 | 4 | `2026-07-15 19:41:08.222` (`:29033`) | finished | `2026-07-15 19:47:41.506` (`:29062`) | 375.196 s |
| 286 | 5 | `2026-07-15 19:47:50.185` (`:29134`) | finished | `2026-07-15 19:54:19.382` (`:29163`) | 371.440 s |
| 287 | 6 | `2026-07-15 19:54:28.143` (`:29237`) | finished | `2026-07-15 20:01:02.224` (`:29266`) | 375.013 s |
| 288 | 3 | `2026-07-15 20:01:11.096` (`:29338`) | finished | `2026-07-15 20:07:34.068` (`:29367`) | 364.223 s |
| 289 | 4 | `2026-07-15 20:07:42.585` (`:29439`) | finished | `2026-07-15 20:14:17.817` (`:29468`) | 377.628 s |
| 290 | 3 | `2026-07-15 20:14:26.871` (`:29540`) | finished | `2026-07-15 20:21:07.624` (`:29569`) | 381.125 s |
| 291 | 2 | `2026-07-15 20:21:17.682` (`:29641`) | finished | `2026-07-15 20:27:42.965` (`:29670`) | 366.782 s |
| 292 | 3 | `2026-07-15 20:27:52.355` (`:29744`) | finished | `2026-07-15 20:34:26.346` (`:29773`) | 375.395 s |
| 293 | 4 | `2026-07-15 20:34:35.098` (`:29845`) | finished | `2026-07-15 20:41:08.035` (`:29874`) | 374.808 s |
| 294 | 3 | `2026-07-15 20:41:17.982` (`:29948`) | finished | `2026-07-15 20:47:53.425` (`:29977`) | 376.897 s |
| 295 | 1 | `2026-07-15 20:48:02.471` (`:30049`) | finished | `2026-07-15 20:54:35.507` (`:30078`) | 373.543 s |
| 296 | 2 | `2026-07-15 20:54:45.258` (`:30150`) | finished | `2026-07-15 21:01:18.201` (`:30179`) | 374.825 s |
| 297 | 1 | `2026-07-15 21:01:26.823` (`:30251`) | finished | `2026-07-15 21:07:56.094` (`:30280`) | 369.873 s |
| 298 | 6 | `2026-07-15 21:08:04.602` (`:30352`) | finished | `2026-07-15 21:14:34.021` (`:30381`) | 370.231 s |
| 299 | 5 | `2026-07-15 21:14:42.892` (`:30453`) | finished | `2026-07-15 21:21:11.054` (`:30482`) | 369.205 s |
| 300 | 4 | `2026-07-15 21:21:20.709` (`:30556`) | finished | `2026-07-15 21:27:51.251` (`:30585`) | 372.616 s |
| 301 | 2 | `2026-07-15 21:28:01.083` (`:30657`) | finished | `2026-07-15 21:34:30.189` (`:30686`) | 370.443 s |
| 302 | 6 | `2026-07-15 21:34:39.818` (`:30760`) | finished | `2026-07-15 21:41:11.537` (`:30789`) | 372.362 s |
| 303 | 4 | `2026-07-15 21:41:21.107` (`:30861`) | finished | `2026-07-15 21:47:45.601` (`:30890`) | 366.727 s |
| 304 | 3 | `2026-07-15 21:47:54.552` (`:30962`) | finished | `2026-07-15 21:54:19.743` (`:30991`) | 366.954 s |
| 305 | 5 | `2026-07-15 21:54:29.513` (`:31065`) | finished | `2026-07-15 22:00:42.673` (`:31094`) | 354.159 s |
| 306 | 4 | `2026-07-15 22:00:50.597` (`:31166`) | finished | `2026-07-15 22:07:16.752` (`:31195`) | 367.636 s |
| 307 | 6 | `2026-07-15 22:07:26.427` (`:31267`) | finished | `2026-07-15 22:13:51.045` (`:31296`) | 366.173 s |
| 308 | 5 | `2026-07-15 22:13:59.661` (`:31370`) | finished | `2026-07-15 22:20:25.915` (`:31399`) | 367.680 s |
| 309 | 4 | `2026-07-15 22:20:35.618` (`:31471`) | finished | `2026-07-15 22:27:01.561` (`:31500`) | 367.794 s |
| 310 | 5 | `2026-07-15 22:27:11.431` (`:31574`) | finished | `2026-07-15 22:33:37.824` (`:31603`) | 367.054 s |
| 311 | 4 | `2026-07-15 22:33:47.467` (`:31675`) | finished | `2026-07-15 22:40:13.739` (`:31704`) | 367.480 s |
| 312 | 2 | `2026-07-15 22:40:22.594` (`:31778`) | finished | `2026-07-15 22:46:43.595` (`:31807`) | 362.337 s |
| 313 | 4 | `2026-07-15 22:46:53.209` (`:31879`) | finished | `2026-07-15 22:53:20.630` (`:31908`) | 369.458 s |
| 314 | 3 | `2026-07-15 22:53:30.255` (`:31982`) | finished | `2026-07-15 22:59:54.594` (`:32011`) | 367.081 s |
| 315 | 4 | `2026-07-15 23:00:04.380` (`:32083`) | finished | `2026-07-15 23:06:43.008` (`:32112`) | 364.126 s |
| 316 | 6 | `2026-07-15 23:06:51.563` (`:32186`) | finished | `2026-07-15 23:13:01.913` (`:32215`) | 351.478 s |
| 317 | 3 | `2026-07-15 23:13:11.459` (`:32287`) | finished | `2026-07-15 23:19:36.808` (`:32316`) | 365.794 s |
| 318 | 2 | `2026-07-15 23:19:44.760` (`:32388`) | finished | `2026-07-15 23:26:01.905` (`:32417`) | 358.744 s |
| 319 | 1 | `2026-07-15 23:26:10.640` (`:32491`) | finished | `2026-07-15 23:32:45.509` (`:32520`) | 375.757 s |
| 320 | 2 | `2026-07-15 23:32:54.239` (`:32592`) | finished | `2026-07-15 23:39:12.092` (`:32621`) | 359.001 s |
| 321 | 6 | `2026-07-15 23:39:21.923` (`:32695`) | finished | `2026-07-15 23:45:37.958` (`:32724`) | 357.296 s |
| 322 | 5 | `2026-07-15 23:45:46.635` (`:32796`) | finished | `2026-07-15 23:52:10.520` (`:32825`) | 366.077 s |
| 323 | 3 | `2026-07-15 23:52:20.427` (`:32899`) | finished | `2026-07-15 23:58:43.587` (`:32928`) | 364.565 s |
| 324 | 5 | `2026-07-15 23:58:52.324` (`:33000`) | finished | `2026-07-16 00:05:16.523` (`:33029`) | 366.083 s |
| 325 | 4 | `2026-07-16 00:05:25.924` (`:33101`) | finished | `2026-07-16 00:11:37.557` (`:33130`) | 352.539 s |
| 326 | 1 | `2026-07-16 00:11:47.210` (`:33202`) | finished | `2026-07-16 00:18:11.045` (`:33231`) | 364.332 s |
| 327 | 6 | `2026-07-16 00:18:20.717` (`:33305`) | finished | `2026-07-16 00:24:43.292` (`:33334`) | 363.970 s |
| 328 | 1 | `2026-07-16 00:24:53.081` (`:33406`) | finished | `2026-07-16 00:31:10.781` (`:33435`) | 359.351 s |
| 329 | 6 | `2026-07-16 00:31:20.600` (`:33509`) | finished | `2026-07-16 00:37:41.690` (`:33538`) | 361.951 s |
| 330 | 3 | `2026-07-16 00:37:50.689` (`:33610`) | finished | `2026-07-16 00:44:15.779` (`:33639`) | 366.215 s |
| 331 | 1 | `2026-07-16 00:44:24.477` (`:33711`) | finished | `2026-07-16 00:50:45.122` (`:33740`) | 359.835 s |
| 332 | 5 | `2026-07-16 00:50:54.866` (`:33812`) | finished | `2026-07-16 00:57:15.690` (`:33841`) | 362.834 s |
| 333 | 1 | `2026-07-16 00:57:25.954` (`:33913`) | finished | `2026-07-16 01:03:53.571` (`:33942`) | 367.470 s |
| 334 | 5 | `2026-07-16 01:04:03.031` (`:34016`) | finished | `2026-07-16 01:10:24.935` (`:34045`) | 363.532 s |
| 335 | 6 | `2026-07-16 01:10:34.729` (`:34117`) | finished | `2026-07-16 01:16:52.088` (`:34146`) | 358.319 s |
| 336 | 4 | `2026-07-16 01:17:00.824` (`:34220`) | finished | `2026-07-16 01:23:17.332` (`:34249`) | 357.666 s |
| 337 | 3 | `2026-07-16 01:23:27.108` (`:34321`) | finished | `2026-07-16 01:29:56.994` (`:34350`) | 370.785 s |
| 338 | 4 | `2026-07-16 01:30:06.567` (`:34422`) | finished | `2026-07-16 01:36:37.477` (`:34451`) | 359.305 s |
| 339 | 2 | `2026-07-16 01:36:47.114` (`:34523`) | finished | `2026-07-16 01:43:01.266` (`:34552`) | 355.365 s |
| 340 | 6 | `2026-07-16 01:43:09.802` (`:34626`) | finished | `2026-07-16 01:49:27.610` (`:34655`) | 358.837 s |
| 341 | 2 | `2026-07-16 01:49:37.311` (`:34727`) | finished | `2026-07-16 01:55:58.476` (`:34756`) | 361.909 s |
| 342 | 4 | `2026-07-16 01:56:08.096` (`:34830`) | incomplete in supplied learner log | — | — |

## Observational Outcome

| Layer | Status | Outcome | Source references |
|---|---:|---|---|
| Receiver lifecycle | ambiguous | Iterations `1..341` finish; iteration `342` starts but learner logging ends only `74.751 s` later. Since the shortest completed synchronization is `200.229 s`, the final attempt is incomplete in the artifact but cannot be classified as a protocol failure. | [Learner Evidence](#learner-evidence); [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Platform recovery | missing | Learner node `0` never reaches `ACTIVE` in the supplied status log. Thus the artifact does not demonstrate a passed platform recovery. | [Reconnect Window And Roles](#reconnect-window-and-roles) |
| Post-recovery stability | not_applicable | No confirmed `ACTIVE` transition exists, so there is no post-recovery interval to evaluate. | platform-recovery row above |
| Workload and client outcome | ambiguous | Load continues throughout reconnect and beyond learner-log EOF, but no terminal client/workflow outcome is supplied and generic client errors are not reconnect-attributable. | [Workload Evidence](#workload-evidence) |
| **Overall observational conclusion** | **derived** | **`indeterminate_due_to_evidence_gap`**. The supplied window shows 341 completed receiver synchronizations without `ACTIVE`, followed by a 342nd attempt, but learner evidence ends too early to determine that attempt's finish or any later recovery. This label does not imply calibration fitness or a causal diagnosis. | derived from the four layer rows above |

## Unresolved Evidence Register

| Evidence item | Status | Search scope or source | Reason |
|---|---:|---|---|
| Iteration 342 receiver finish and metrics | missing | `log:podlog_solo-mdlt-n12/network-node1_logs/swirlds.log:34830-34847` | Learner log ends while synchronization is in progress. |
| Eventual learner `ACTIVE` | missing | full learner `swirlds.log` | No `ACTIVE` transition is present. |
| Final lifecycle outcome | ambiguous | learner EOF, teacher iteration-342 tree finish, later teacher-only attempt | Different node logs extend to different times; no later learner status evidence resolves the run. |
| Post-recovery stability | not_applicable | observational outcome | Recovery is not confirmed. |
| Formal teacher finish payloads, iterations 3..342 | missing | all six possible teacher logs; exact sender-finish pattern | Only two formal sender finish payloads are present, although tree-finish evidence exists. |
| Workflow controls and terminal outcome | missing | run-root inventory, version/client/pod sources | No control or terminal workflow artifact is supplied. |
| Reconnect-related client failure attribution | ambiguous | `client.log` generic errors | Errors are generic and non-terminal. |
| SocketFactory PRE-only cause | ambiguous | startup SocketFactory lifecycle blocks on nodes 1..5 internally | Both PRE components exist; no POST or sourced cause is present. |
| Literal `ss -tinm` command invocation | missing | seven sampler files and non-sampler metadata | `skmem` proves memory fields were captured, but the command line itself is absent. |
| Full passive both-endpoint coverage | missing | all seven samplers joined to the ordered iteration roles | Iterations 304, 314, 317, 318, and 320..342 lack a full learner/active-teacher window. |
| Whole-episode TCP explanation | ambiguous | selected reciprocal socket windows and sampler coverage limits | Selected behavior cannot be extrapolated to the uncovered final attempts or used as a causal diagnosis. |
| `internalData`/`internalCleanData` and `leafHashes`/`leafCleanHashes` | missing | all 341 emitted `ReconnectMapMetrics` rows | Keys are absent from the six-field schema. |
| Valid RTT minimum for selected iterations 170 and 341 | missing | selected learner CSV windows | Only sentinel `9999999` values are emitted. |
| Traversal comparison and calibration disposition | not_applicable | observational profile | This is a one-run observational extraction. |
