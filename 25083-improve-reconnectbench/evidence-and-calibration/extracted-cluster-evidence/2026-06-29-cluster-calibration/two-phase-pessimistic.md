# Two-Phase-Pessimistic Cluster Evidence

Artifact run root: `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/29-06-2026/dallas14_pullTwoPhasePessimistic/report`

All artifact paths below are relative to the artifact run root.

## Network Disease Preflight

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Preflight result | derived | No fatal network disease found. All seven node logs had `ACTIVE -> CHECKING` count `0` and missing-parent count `0`. | `derived:scan;inputs=log:podlog_solo-sdpt-n14/network-node*_logs/swirlds.log;patterns=oldStatus=ACTIVE,newStatus=CHECKING;Shadowgraph: Missing non-expired other parent` |
| Active confirmations | present | Learner node0 reached post-reconnect `CHECKING -> ACTIVE` at `2026-06-26 22:22:14.244`; peer startup active confirmations are also present. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7178`; `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:123-125` |
| Extraction disposition | present | Normal extraction is valid, but acceptance is limited by missing passive TCP/window evidence. | [Analysis Output Per Mode](#analysis-output-per-mode) |

## Run Context

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTwoPhasePessimistic`; `version_run.txt` and all seven copied `settingsUsed.txt` files agree. | `config:version_run.txt:key=inputs.AddSettings;line=8`; `derived:scan_all_nodes;inputs=config:podlog_solo-sdpt-n14/network-node*_logs/config/settingsUsed.txt:line=725;expected=pullTwoPhasePessimistic` |
| Namespace | present | `AdHoc14` | `config:version_run.txt:key=namespace;line=1` |
| Commit hash | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=hederahash;line=11` |
| Run number / job URL | present | run `301`; workflow URL `https://github.com/swirldslabs/performance-analysis-automation/actions/runs/28245047274` | `config:version_run.txt:key=run_number;line=12`; `config:version_run.txt:key=JOB_URL;line=10` |
| NLG controls | present | `LongevityLoadTest`, `-Dbenchmark.maxtps=8000`, `30000000` accounts, `6h`. | `config:version_run.txt:key=inputs.NLG_Test;line=9`; `config:version_run.txt:key=inputs.NLGDparams;line=4`; `config:version_run.txt:key=inputs.NLG_Accounts;line=5`; `config:version_run.txt:key=inputs.NLG_Time;line=6` |
| Learner node and pod | present | Learner is node ID `0`, log directory `network-node1_logs`, pod `network-node1-0`, `POD_IP=10.68.11.91`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162`; `config:podlog_solo-sdpt-n14/network-node1_logs/config/settingsUsed.txt:948`; `config:podlog_solo-sdpt-n14/network-node1_logs/config/settingsUsed.txt:1143` |
| Network size | present | Seven network-node pods plus one NLG pod are present in pod state. | `log:pod_state.txt:17-24` |
| Client workload profile | present | 32 clients, 30M accounts, 1000 tokens, 30M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer duration `PT6H`. | `log:client.log:2-9` |
| Client network targets | present | Seven NLG targets on port `50211`. | `log:client.log:10-16` |
| Workflow-control logs | missing | No `performance-tests-start.log`, `performance-tests-watch.log`, or equivalent workflow-control filename was found under the run root. | `derived:search_no_matches;scope=run root;patterns=performance-tests-start.log,performance-tests-watch.log,*workflow*,*watch*,*start*,*loop*` |
| Warmtime / downtime / loop count | missing | No `warmtime`, `downtime`, `NofLoops`, or `profileReconnectLoopK8s` controls were found in available run-root files. | `derived:search_no_matches;inputs=config:version_run.txt,log:pod_state.txt,log:client.log,log:podlog_solo-sdpt-n14/error_summary*.txt,log:podlog_solo-sdpt-n14/*errors.log;patterns=warmtime,downtime,NofLoops,profileReconnectLoopK8s` |
| Baseline/restored state upload | missing | No baseline, restore, upload, or state-upload evidence was found. | `derived:search_no_matches;inputs=config:version_run.txt,log:pod_state.txt,log:client.log,log:podlog_solo-sdpt-n14/error_summary*.txt,log:podlog_solo-sdpt-n14/*errors.log;patterns=baseline,restored-state,restored state,restore,upload,state upload,s3://,gs://` |

## Reconnect Window And Roles

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner fell behind | present | Node `0` moved `OBSERVING -> BEHIND` before the first receiver reconnect. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:157`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162` |
| Complete receiver episode | present | First learner receiver start `2026-06-26 17:09:42.975`; final learner receiver finish before `ACTIVE` `2026-06-26 22:22:03.807`; final `ACTIVE` `2026-06-26 22:22:14.244`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7123`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7178` |
| Iteration count | present | `89` learner receiver reconnect iterations before `ACTIVE`; no unpaired receiver starts or finishes. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-7123`; `derived:verification=receiver_start=89,receiver_finish=89,metrics=89` |
| First iteration receiver window | present | Learner node `0` received from teacher peer `3`, `2026-06-26 17:09:42.975..17:13:10.799`; wall-clock duration `207.824 s`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:187`; `derived:formula=end-start` |
| First iteration teacher window | present | Teacher node `3` (`network-node4_logs`) sent to learner node `0`, `2026-06-26 17:09:45.614..17:13:10.794`. | `log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:485-520` |
| Second iteration receiver window | present | Learner node `0` received from teacher peer `1`, `2026-06-26 17:13:16.531..17:16:05.845`; wall-clock duration `169.314 s`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:232`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:265`; `derived:formula=end-start` |
| Second iteration teacher window | present | Teacher node `1` (`network-node2_logs`) sent to learner node `0`, `2026-06-26 17:13:19.143..17:16:05.845`. | `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:484`; `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:549` |
| Last iteration receiver window | present | Learner node `0` received from teacher peer `4`, `2026-06-26 22:21:57.852..22:22:03.807`; wall-clock duration `5.955 s`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7094`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7123`; `derived:formula=end-start` |
| Last iteration teacher window | present | Teacher node `4` (`network-node5_logs`) sent to learner node `0`, `2026-06-26 22:22:00.198..22:22:03.808`. | `log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:1814-1849` |
| Learner status after final receiver finish | present | Final `RECONNECT_COMPLETE -> CHECKING -> ACTIVE` after iteration 89. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7140`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7177-7178` |

## Learner Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner receiver coverage | present | `89` receiver start lines, `89` receiver finish lines, `89` `ReconnectMapMetrics` lines, `89` synchronization timing lines, `89` data usage lines, and learner path-range/init/flusher lines for each iteration. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-7123`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:184-7120`; `derived:verification=all metric keys complete` |
| Synchronization time total | derived | Sum of `89` `timeInSeconds` payloads is `17742.019 s`. | `derived:sum;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:185-7121` |
| Reconnect data total | derived | Sum of `89` data payloads is `196172.06626033783 MB`. | `derived:sum;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:186-7122` |
| First iteration | present | Teacher peer `3`; sync `204.531 s`; data `5231.3557777404785 MB`; view range `[92069524,184139048]` (`92,069,525` leaves); new range `[100676193,201352386]` (`100,676,194` leaves). | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-187` |
| Second iteration | present | Teacher peer `1`; sync `166.267 s`; data `1896.269718170166 MB`; view range `[100676193,201352386]` (`100,676,194` leaves); new range `[101282013,202564026]` (`101,282,014` leaves). | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:232-265` |
| Last iteration | present | Teacher peer `4`; sync `3.303 s`; data `4.608307838439941 MB`; view range `[132364613,264729226]` and new range `[132364613,264729226]` (`132,364,614` leaves). | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7094-7123` |

## Teacher Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Teacher matching coverage | present | All `89` learner finish rounds matched teacher sender windows; missing matches `0`; learner/teacher state range mismatches `0`. | `derived:verification=learner_pairs=89,teacher_windows=89,missing_matches=0,range_mismatches=0;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-7123,log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:484-1941,log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:514-1707` |
| First iteration teacher | present | Teacher node `3` sent state metadata/root response `[100676193,201352386]`; derived size `100,676,194` leaves. | `log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:485-501`; `log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:519-520` |
| Second iteration teacher | present | Teacher node `1` sent state metadata/root response `[101282013,202564026]`; derived size `101,282,014` leaves. | `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:484-500`; `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:548-549` |
| Last iteration teacher | present | Teacher node `4` sent state metadata/root response `[132364613,264729226]`; derived size `132,364,614` leaves. | `log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:1814-1830`; `log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:1848-1849` |
| Teacher peer coverage | present | Sender-window counts by teacher peer: node1 `16`, node2 `12`, node3 `16`, node4 `13`, node5 `18`, node6 `14`; all expected sender start/state header/state metadata/root response/finished tree/sender finish records are present. | `log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:484-1941`; `log:podlog_solo-sdpt-n14/network-node3_logs/swirlds.log:518-1577`; `log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:485-1784`; `log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:519-1849`; `log:podlog_solo-sdpt-n14/network-node6_logs/swirlds.log:547-1902`; `log:podlog_solo-sdpt-n14/network-node7_logs/swirlds.log:514-1707` |

## Reconnect Work-Shape Counters

| Row | transfersFromTeacher | transfersFromLearner | internalHashes | internalCleanHashes | internalData | internalCleanData | leafHashes | leafCleanHashes | leafData | leafCleanData | Source references |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Iteration 1 | 109,099,914 | 107,460,816 | 12,914,970 | 2,154,733 | 12,906,494 | 2,154,215 | 92,861,314 | 55,395,237 | 96,369,574 | 56,226,435 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:184` |
| Iteration 89 | 318,792 | 315,242 | 260,557 | 260,425 | 260,338 | 260,370 | 58,552 | 59,264 | 59,836 | 59,833 | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7120` |
| Aggregate sum, 89 rows | 10,458,857,786 | 10,274,056,741 | 171,647,593 | 94,808,013 | 171,433,786 | 94,803,740 | 9,930,295,021 | 9,679,316,788 | 10,289,084,392 | 9,856,850,986 | `derived:sum of 89 metric lines;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:184-7120` |

| Row | internalDirtyHashes | internalDirtyData | leafDirtyHashes | leafDirtyData | Source references |
|---|---:|---:|---:|---:|---|
| Iteration 1 | 10,760,237 | 10,752,279 | 37,466,077 | 40,143,139 | `derived:formula=total-clean;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:184` |
| Iteration 89 | 132 | -32 | -712 | 3 | `derived:formula=total-clean;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7120` |
| Aggregate sum, 89 rows | 76,839,580 | 76,630,046 | 250,978,233 | 432,233,406 | `derived:formula=aggregate_total-aggregate_clean;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:184-7120` |

## Network Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Learner data lower-bound throughput, first iteration | derived | `5231.3557777404785 MB / 204.531 s = 25.578 MB/s` from learner log payloads. | `derived:formula=dataMegabytes/timeInSeconds;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:185-186` |
| Learner data lower-bound throughput, complete episode | derived | `196172.06626033783 MB / 17742.019 s = 11.057 MB/s` across summed learner synchronization time; this is not link capacity. | `derived:formula=sum(dataMegabytes)/sum(timeInSeconds);inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:185-7122` |
| CSV reconnect lifecycle stats | present | Learner stats show first receiver `1/1/204s`, last receiver `89/89/3s`, and after-`ACTIVE` `89/89/0`; teacher sender totals by nodes `1..6` sum to `89`. | `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:row=906;columns=time,startsReconnectAsReceiver,endsReconnectAsReceiver,receiverReconnectDurationSeconds`; `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:row=7151;columns=time,startsReconnectAsReceiver,endsReconnectAsReceiver,receiverReconnectDurationSeconds`; `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:row=7155;columns=time,startsReconnectAsReceiver,endsReconnectAsReceiver,receiverReconnectDurationSeconds`; `csv:podlog_solo-sdpt-n14/network-node{2..7}_logs/stats/MainNetStats{1..6}.csv:row=9743;columns=startsReconnectAsSender,endsReconnectAsSender` |
| CSV RTT/send-rate first pair | present | First pair node0->node3 start/end `ping_us_03=340.20`, `bytes_per_sec_sent_03=20.62 -> 32,986,033.66`; node3->node0 start/end `ping_us_00=593.09`, `bytes_per_sec_sent_00=154.88 -> 41,287,012.98`. | `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:row=906;columns=time,ping_us_03,bytes_per_sec_sent_03,bytes_per_sec_sent`; `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:row=974;columns=time,ping_us_03,bytes_per_sec_sent_03,bytes_per_sec_sent`; `csv:podlog_solo-sdpt-n14/network-node4_logs/stats/MainNetStats3.csv:row=3497;columns=time,ping_us_00,bytes_per_sec_sent_00,bytes_per_sec_sent`; `csv:podlog_solo-sdpt-n14/network-node4_logs/stats/MainNetStats3.csv:row=3566;columns=time,ping_us_00,bytes_per_sec_sent_00,bytes_per_sec_sent` |
| CSV RTT/send-rate last pair | present | Last pair node0->node4 start/finish `ping_us_04=158.77`, `bytes_per_sec_sent_04=111.40 -> 1,209,329.08`; node4->node0 start/end `ping_us_00=182.91`, `bytes_per_sec_sent_00=671.81 -> 300,090.32`. | `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:row=7150;columns=time,ping_us_04,bytes_per_sec_sent_04,bytes_per_sec_sent`; `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:row=7151;columns=time,ping_us_04,bytes_per_sec_sent_04,bytes_per_sec_sent`; `csv:podlog_solo-sdpt-n14/network-node5_logs/stats/MainNetStats4.csv:row=9742;columns=time,ping_us_00,bytes_per_sec_sent_00,bytes_per_sec_sent`; `csv:podlog_solo-sdpt-n14/network-node5_logs/stats/MainNetStats4.csv:row=9743;columns=time,ping_us_00,bytes_per_sec_sent_00,bytes_per_sec_sent` |
| Passive sampler files | missing | No passive sampler files, top-level reconnect sampler files, or embedded `ss -tin`/socket sample blocks were found under the run root. | `derived:search_no_matches;scope=full run root,156 files;patterns=*sampler*,*reconnect_network_samples*,*tcp*,*TCP*,*socket*,*Socket*,*ss*tin*,*ss*sample*,*network*sample*,ss -tin,Recv-Q,Send-Q,rtt:,minrtt,cwnd:,bytes_retrans,delivery_rate,rwnd_limited,snd_wnd` |
| TCP/window/backpressure evidence | missing | No reconnect-window passive sample source exists, so no sampler-derived RTT/minRTT, congestion-window, send/receive queue, retransmission, or backpressure evidence can be extracted. | `derived:search_no_matches;scope=complete episode window 2026-06-26 17:09:42.975..22:22:03.807;patterns=Recv-Q,Send-Q,cwnd,ssthresh,unacked,notsent,rwnd_limited,snd_wnd,bytes_retrans` |
| `ping_us_*MIN` columns | ambiguous | `ping_us_*MIN` exists but often reports sentinel `9999999`; plain `ping_us_*` columns are used as CSV RTT evidence. | `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:row=906;columns=ping_us_03MIN`; `csv:podlog_solo-sdpt-n14/network-node4_logs/stats/MainNetStats3.csv:row=3497;columns=ping_us_00MIN`; `csv:podlog_solo-sdpt-n14/network-node5_logs/stats/MainNetStats4.csv:row=9743;columns=ping_us_00MIN` |

## Workload Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Configured max TPS | present | `-Dbenchmark.maxtps=8000`. | `config:version_run.txt:key=inputs.NLGDparams;line=4` |
| Client workload profile | present | 32 clients, 30M accounts, 1000 tokens, 30M NFTs, `HOT 50%`, 100000 topics, ECDSA keys, transfer workload for `PT6H`. | `log:client.log:2-9` |
| Workload mix final summaries | present | HeliSwap `6,756,169` swaps at `312 TPS`; crypto transfers `107,934,006` at `4996 TPS`; smart contract calls `1,636,520` at `75 TPS`; HCS messages `43,173,524` at `1998 TPS`; NFT transfers `64,770,018` at `2998 TPS`. | `log:client.log:7947-7951` |
| Reconnect-window load near start | present | Around first reconnect start, transactions `68.9M` at current `10390 TPS`, receipts `68.9M` at current `10390 TPS`, and crypto transfers active at `5000 TPS`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162`; `log:client.log:2313-2316` |
| Reconnect-window load mid/late | present | Mid-window around `19:30`, transactions/receipts near `156.4M-156.8M` with current TPS around `10386-10483`; around `21:30`, transactions/receipts near `231.1M-231.7M` with current TPS around `10353-10392`. | `log:client.log:4930-4942`; `log:client.log:7160-7177` |
| Load continuity limitation | ambiguous | Load overlaps most of the long reconnect sequence, including a late reconnect start at `22:11:59.422`, but client jobs finish by `22:12:15-22:12:16`, before the later reconnect finish at `22:16:47.163`, final receiver finish at `22:22:03.807`, and final `ACTIVE` at `22:22:14.244`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:6944`; `log:client.log:7944-7953`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:6974`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7123`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7178` |

## State And Divergence Evidence

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| First iteration learner start size | derived | `92,069,525` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-187` |
| First iteration teacher/target size | derived | `100,676,194` leaves; target equals learner received-state metadata. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:485-501,log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-187` |
| First iteration state-size gap | derived | `8,606,669` leaves between teacher target and learner start. | `derived:formula=100676194-92069525;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-187,log:podlog_solo-sdpt-n14/network-node4_logs/swirlds.log:485-501` |
| Second iteration target size | derived | `101,282,014` leaves; second-iteration gap from prior target is `605,820` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;gap=101282014-100676194;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:247,log:podlog_solo-sdpt-n14/network-node2_logs/swirlds.log:484-500` |
| Final target size | derived | `132,364,614` leaves. The last iteration has equal view and target ranges, so the final iteration gap is `0` leaves. | `derived:formula=lastLeafPath-firstLeafPath+1;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7094-7123,log:podlog_solo-sdpt-n14/network-node5_logs/swirlds.log:1814-1830` |
| Learner stats state/store snapshots | present | Learner `vmap_size_state`: `92,067,586 / 92,069,525 / 132,364,614 / 132,364,614` for before start / first after start / final at-or-before finish / first after `ACTIVE`; `tokenAssociationsUsed` stays `1,775,805 -> 1,777,563`; stable counts include `accountsUsed=30000712`, `contractsUsed=6`, `nftsUsed=30000000`, `tokensUsed=1000`, `topicsUsed=100000`. | `csv:podlog_solo-sdpt-n14/network-node1_logs/stats/MainNetStats0.csv:rows=904,905,7151,7155;columns=time,vmap_size_state,tokenAssociationsUsed,accountsUsed,contractsUsed,nftsUsed,tokensUsed,topicsUsed,vmap_lifecycle_nodeCacheSizeB_state,vmap_lifecycle_flushCount_state` |
| Teacher stats state/store snapshots | present | First teacher node3 `vmap_size_state`: `100,675,138 / 100,683,521 / 132,364,614 / 132,364,614`; last teacher node4 `vmap_size_state`: `100,675,502 / 100,683,792 / 132,364,614 / 132,364,614`. | `csv:podlog_solo-sdpt-n14/network-node4_logs/stats/MainNetStats3.csv:rows=3495,3496,9742,9747;columns=time,vmap_size_state,tokenAssociationsUsed,accountsUsed,contractsUsed,nftsUsed,tokensUsed,topicsUsed,vmap_lifecycle_nodeCacheSizeB_state,vmap_lifecycle_flushCount_state`; `csv:podlog_solo-sdpt-n14/network-node5_logs/stats/MainNetStats4.csv:rows=3495,3496,9742,9747;columns=time,vmap_size_state,tokenAssociationsUsed,accountsUsed,contractsUsed,nftsUsed,tokensUsed,topicsUsed,vmap_lifecycle_nodeCacheSizeB_state,vmap_lifecycle_flushCount_state` |
| Divergence shape | derived | Very long multi-iteration growth-heavy reconnect: first state-size gap is large, final gap is zero, and aggregate counters show large clean leaf work plus non-trivial dirty leaf data. | `derived:classify_from_state_gap_and_clean_dirty_counters;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:184-7120` |

## Reconnect Episodes And Iterations

| Evidence item | Status | Extracted value or observation | Source references |
|---|---:|---|---|
| Episode complete | present | Learner reached `ACTIVE` after the final receiver reconnect finish. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7123`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7178` |
| Iteration count | derived | `89` learner receiver reconnect iterations before `ACTIVE`. | `derived:count_receiver_starts_before_ACTIVE;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-7123` |
| Complete catch-up start | present | `2026-06-26 17:09:42.975` UTC. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162` |
| Complete catch-up end | present | `2026-06-26 22:22:03.807` UTC, final learner receiver finish before `ACTIVE`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7123`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7178` |
| Complete catch-up duration | derived | `18,740.832 s`. | `derived:formula=final_receiver_finish-first_receiver_start;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162,7123` |
| Active confirmation | present | `2026-06-26 22:22:14.244` UTC. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7178` |
| Iteration detail coverage | present | First 15 iteration anchors were extracted during anchoring, last iteration was extracted, and full log-role verification confirmed all 89 learner/teacher matches. Full raw 89-row metric output is represented by coverage plus aggregate sums rather than listing all 89 rows in this Markdown file. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-1395`; `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:7049-7123`; `derived:verification=learner_pairs=89,teacher_windows=89,missing_matches=0,range_mismatches=0` |
| Additional iterations observed | present | Yes. The complete episode has 89 receiver iterations before `ACTIVE`. | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162-7123` |

## Analysis Output Per Mode

| Field | Status | Value | Source references |
|---|---:|---|---|
| Traversal mode | present | `pullTwoPhasePessimistic` | `config:version_run.txt:key=inputs.AddSettings;line=8`; `config:podlog_solo-sdpt-n14/network-node1_logs/config/settingsUsed.txt:725` |
| Manifest batch | present | `2026-06-29-cluster-calibration` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration` |
| Manifest run | present | `two-phase-pessimistic` | `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration;run=two-phase-pessimistic` |
| Commit | present | `0cc709860be30d5892ba5fa70ed9300ce4107628` | `config:version_run.txt:key=hederahash;line=11` |
| Network disease preflight | derived | pass; no fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Network disease reason if failed | not_applicable | No fatal disease found. | [Network Disease Preflight](#network-disease-preflight) |
| Learner node | present | `0` | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162` |
| Teacher node | present | First iteration teacher node `3`; final iteration teacher node `4`; all teacher matches verified across nodes `1..6`. | [Teacher Evidence](#teacher-evidence) |
| First reconnect start UTC | present | `2026-06-26 17:09:42.975` | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162` |
| First reconnect end UTC | present | `2026-06-26 17:13:10.799` | `log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:187` |
| Learner duration | derived | First iteration `207.824 s`; complete catch-up duration `18,740.832 s`. | `derived:formula=end-start;inputs=log:podlog_solo-sdpt-n14/network-node1_logs/swirlds.log:162,187,7123` |
| Teacher reconnect context present | present | yes | [Teacher Evidence](#teacher-evidence) |
| Reconnect stats present | present | yes | [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Teacher/learner state size present | present | yes | [State And Divergence Evidence](#state-and-divergence-evidence) |
| Workload profile present | present | yes, with limitation that load ends before the final receiver finish and final `ACTIVE`. | [Workload Evidence](#workload-evidence) |
| RTT evidence present | present | yes via CSV stats; no passive socket RTT. | [Network Evidence](#network-evidence) |
| Bandwidth evidence present | present | yes via learner data/time and CSV send-rate; no passive socket throughput context. | [Network Evidence](#network-evidence) |
| TCP/window evidence present | missing | no passive sampler evidence. | [Network Evidence](#network-evidence) |
| Episode complete | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Iteration count | derived | `89` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up start UTC | present | `2026-06-26 17:09:42.975` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up end UTC | present | `2026-06-26 22:22:03.807` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Complete catch-up duration | derived | `18,740.832 s` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Active confirmation UTC | present | `2026-06-26 22:22:14.244` | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Additional iterations observed | present | yes | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations) |
| Run accepted for calibration | derived | no | `derived:protocol_acceptance_requires_RTT_bandwidth_TCP_window_evidence;inputs=[Network Evidence](#network-evidence)` |
| Reason if not accepted | present | Passive TCP/window/backpressure evidence is absent, and workload does not continue through the final receiver finish/`ACTIVE`; the run remains useful for timing/work-shape diagnostics but incomplete for full local network calibration. | [Network Evidence](#network-evidence); [Workload Evidence](#workload-evidence) |

## Acceptance Notes

| Acceptance item | Status | Note | Source references |
|---|---:|---|---|
| Timing/work-shape extraction | present | Complete 89-iteration episode, teacher matching, state sizes, and aggregate work-shape counters are source-referenced. | [Reconnect Episodes And Iterations](#reconnect-episodes-and-iterations); [Reconnect Work-Shape Counters](#reconnect-work-shape-counters) |
| Full calibration acceptance | derived | Protocol acceptance requires RTT, bandwidth, and TCP/window evidence. This run lacks passive TCP/window evidence. | [Network Evidence](#network-evidence) |
| Workload limitation | ambiguous | Load overlaps most of the episode but ends before the final receiver finish and final `ACTIVE`. | [Workload Evidence](#workload-evidence) |
| Independent-run interpretation | present | This cluster run is an independent live-state calibration anchor; do not use it alone for causal traversal-mode ranking. | [State And Divergence Evidence](#state-and-divergence-evidence); `manifest:../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration` |

## Unresolved Evidence Register

| Evidence gap | Status | Files checked | Search pattern or column | Reason |
|---|---:|---|---|---|
| Workflow controls | missing | Run root file inventory; `version_run.txt`; `pod_state.txt`; `client.log`; error-summary logs | `performance-tests-start.log`, `performance-tests-watch.log`, `warmtime`, `downtime`, `NofLoops`, `profileReconnectLoopK8s` | Workflow-control files are absent from this artifact, so stopped-pod timing and loop controls are inferred from reconnect logs and manifest context only. |
| Baseline/restored-state upload | missing | Run root file inventory; `version_run.txt`; `pod_state.txt`; `client.log`; error-summary logs | `baseline`, `restored-state`, `restored state`, `restore`, `upload`, `state upload`, `s3://`, `gs://` | No baseline/restored-state upload evidence found; treat run as independent live-state workflow evidence. |
| Exact stopped-pod script output | missing | `version_run.txt`, `pod_state.txt`, `client.log`, error logs, learner config, learner log | `Stopping java`, `stop pod`, `delete pod`, `kubectl.*delete`, `network-node1-0`, `HOSTNAME`, `ReconnectStartPayload` | No direct workflow stop marker exists; stopped pod is inferred as `network-node1-0` from learner node/pod mapping and receiver reconnect evidence. |
| Passive sampler files | missing | Full run root, 156 files | `*sampler*`, `*reconnect_network_samples*`, `*tcp*`, `*socket*`, `ss -tin`, `Recv-Q`, `Send-Q`, `rtt:`, `minrtt`, `cwnd:`, `bytes_retrans`, `delivery_rate`, `rwnd_limited`, `snd_wnd` | No passive sampler source exists; TCP/window/backpressure evidence cannot be extracted. |
| `ReconnectMapMetrics` in stats CSV | missing | `MainNetStats0.csv` and teacher stats CSVs | transfer/hash/data/clean counter names | Work-shape counters are present in learner logs, not mirrored in stats CSVs. |
| Full 89-row metric table | present | Learner log and teacher logs | per-iteration `ReconnectMapMetrics` table | This Markdown records first, last, and aggregate counter rows plus verification of all 89 metric rows; it does not expand all 89 raw counter rows inline. |
