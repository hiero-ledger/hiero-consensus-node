# Cluster ReconnectBench Artifact Atlas

Updated: `2026-06-01`

## Purpose

This atlas maps calibration evidence to the collected cluster artifacts that contain it. It is a source-location guide,
not an extracted-results document.

Artifact root:

```text
/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs
```

## Run Roots

Use `runRoot` below as the base path for top-level run artifacts, network sampler files, and `version_run.txt`.

| Run bucket | `runRoot` | Pod log root |
| --- | --- | --- |
| Top-to-bottom run | `NikitaReconnect1` | `NikitaReconnect1/podlog_solo-mdlt-n3` |
| Two-phase pessimistic run | `NikitaReconnect2_2phase/report` | `NikitaReconnect2_2phase/report/podlog_solo-mdlt-n4` |
| Parallel-sync run | `NikitaReconnect3_PullParallelSync/report` | `NikitaReconnect3_PullParallelSync/report/podlog_solo-mdlt-n4` |

Node log directories follow this pattern:

```text
<podLogRoot>/network-node<N>_logs
```

`network-node1_logs` is node ID `0`, `network-node2_logs` is node ID `1`, and so on.

## Run Identity And Mode

- `runRoot/version_run.txt`
  - `namespace`
  - `inputs.hederaversion`
  - `inputs.soloversion`
  - `inputs.NLGDparams`
  - `inputs.NLG_Accounts`
  - `inputs.NLG_Time`
  - `inputs.AddSettings`
  - `inputs.NLG_Test`
  - `JOB_URL`
  - `hederahash`
  - `run_number`
- `<podLogRoot>/network-node*_logs/config/settingsUsed.txt`
  - authoritative per-node `virtualMap.reconnectMode`
  - use this to confirm the mode, especially when `version_run.txt` has an empty `inputs.AddSettings`

## Workload Evidence

- `runRoot/client.log`
  - NLG configuration lines: `CONFIG #clients`, `CONFIG #accounts`, `CONFIG #tokens`, `CONFIG #NFTs`,
    `CONFIG schema`, `CONFIG transfer`
  - network target lines: `NETWORK 0.0.<node> <host>:50211`
  - transaction and receipt rate lines: `Transactions:`, `Receipts:`, `TPS(EMA)`, `TPS(current)`
  - account/topic creation progress lines: `Created <count> accounts`, `Created <count> topics`

## Learner Reconnect Evidence

Primary learner log:

```text
<podLogRoot>/network-node1_logs/swirlds.log
```

Primary learner patterns:

- `ReconnectController: Preparing for reconnect, stopping gossip`
- `RpcPeerHandler: SELF_FALLEN_BEHIND`
- `ReconnectStatePeerProtocol: Starting reconnect in the role of the receiver`
- `ReconnectStateLearner: Receiving signed state signatures`
- `VirtualMapLearner: Building learner view for map with path range`
- `VirtualMapLearner: Init reconnect state: firstLeafPath:`
- `ReconnectHashLeafFlusher: Reconnect flusher initialized with firstLeafPath=`
- `VirtualMapLearner: Starting deleting`
- `VirtualMapLearner: Learner reconnect complete`
- `LearningSynchronizer: learner is done synchronizing`
- `ReconnectStateLearner: ReconnectMapMetrics:`
- `ReconnectStateLearner: Finished synchronization`
- `ReconnectStateLearner: Reconnect data usage report`
- `ReconnectStatePeerProtocol: Finished reconnect in the role of the receiver`
- `StatusStateMachine: Platform spent ... in BEHIND. Now in RECONNECT_COMPLETE`
- `StatusStateMachine: Platform spent ... in RECONNECT_COMPLETE. Now in CHECKING`
- `StatusStateMachine: ... Now in ACTIVE`

`swirlds_reconnect_1.log` under the same node directory contains startup and early state metadata. Use `swirlds.log` for
the accepted reconnect window.

## Teacher Reconnect Evidence

The learner `ReconnectStartPayload` contains `otherNodeId`. Convert that peer ID to the teacher directory:

```text
teacherLogDir=<podLogRoot>/network-node<otherNodeId + 1>_logs
teacherLog=<teacherLogDir>/swirlds.log
```

Primary teacher patterns:

- `ReconnectStateTeacher: Starting reconnect in the role of the sender`
- `ReconnectStateTeacher: The following state will be sent to the learner`
- `ReconnectStateTeacher: Sending signatures from nodes`
- `TeacherPullVirtualTreeView: Teacher sending root node response`
- `TeacherPullVirtualTreeReceiveTask: Teacher task: duration=`
- `TeacherPullVirtualTreeReceiveTask: Teaching is complete as requested by the learner`
- `TeachingSynchronizer: Finished sending tree`
- `ReconnectStateTeacher: Finished reconnect in the role of the sender`

## Metrics And Stats CSVs

Stats files live under each node log directory:

```text
<podLogRoot>/network-node<N>_logs/stats/MainNetStats<M>.csv
```

Use the header rows to locate columns. Confirmed reconnect and network metric names include:

- `startsReconnectAsReceiver`
- `endsReconnectAsReceiver`
- `receiverReconnectDurationSeconds`
- `startsReconnectAsSender`
- `endsReconnectAsSender`
- `senderReconnectDurationSeconds`
- `reconnectRejections_per_sec_*`
- `bytes_per_sec_sent`
- `bytes_per_sec_sent_*`
- `ping_us_*`
- `ping_us_*MIN`
- `vmap_lifecycle_nodeCacheSizeB_state`
- `vmap_queries_addedEntities_state`
- `vmap_queries_readEntities_state`
- `vmap_queries_removedEntities_state`
- `vmap_queries_updatedEntities_state`

## Passive Network Evidence

- `runRoot/reconnect_network_samples_1.log`
  - timestamped `ss -tin` samples around the reconnect window
  - fields include `Recv-Q`, `Send-Q`, peer address, `rtt`, `minrtt`, `cwnd`, `ssthresh`, `bytes_sent`,
    `bytes_retrans`, `bytes_acked`, `bytes_received`, `send`, `pacing_rate`, `delivery_rate`, `rwnd_limited`,
    `unacked`, `notsent`, `rcv_space`, `snd_wnd`
- `runRoot/network_sampler_network-node<N>-0.log`
  - per-node passive socket samples
- `runRoot/reconnect_network_samples_1_summary.log`
  - present in `NikitaReconnect1`

## Configuration Snapshots

- `<podLogRoot>/network-node<N>_logs/config/settingsUsed.txt`
- `<podLogRoot>/network-node<N>_logs/config/application.properties`
- `<podLogRoot>/network-node<N>_logs/config/bootstrap.properties`
- `<podLogRoot>/network-node<N>_logs/config/api-permission.properties`
- `<podLogRoot>/network-node<N>_logs/config/.archive/genesis-network.json`

These files are source evidence for runtime settings, node roster context, and per-node configuration confirmation.
