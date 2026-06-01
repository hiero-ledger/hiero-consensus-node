# Cluster ReconnectBench Artifact Atlas

Updated: `2026-06-01`

## Purpose

This atlas maps required calibration evidence to the collected cluster artifacts that should contain it. It is a
source-location guide and template, not an extracted-results document.

The atlas follows the processing protocol order starting with `Run Context`, so it can be compared side by side with
`cluster-reconnectbench-artifact-processing-protocol.md`.

## Source And Atlas Contract

Artifact root:

```text
/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs
```

Coverage is source-mapping coverage only:

- `complete`: every required protocol field in the section has an exact mapped source, pattern, or field.
- `partial`: some required fields are mapped, and at least one required field still lacks an exact source mapping.
- `missing`: no exact source mapping exists yet for the section's required evidence.

Do not record extracted values in this atlas. Record only paths, file patterns, log patterns, metric names, and unmapped
required evidence.

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

## Run Context

Coverage: partial

Required evidence:

- commit SHA;
- image tag or digest, if captured;
- traversal mode;
- baseline or namespace identifier, if captured;
- network size;
- learner candidate and stopped pod;
- teacher candidate, if known before reconnect matching;
- workload profile and NLG arguments;
- learner-behind duration, warmtime, downtime, and loop count;
- transaction rate and transaction mix;
- config summary.

Mapped sources:

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
- `runRoot/client.log`
  - NLG configuration lines: `CONFIG #clients`, `CONFIG #accounts`, `CONFIG #tokens`, `CONFIG #NFTs`,
    `CONFIG schema`, `CONFIG transfer`
  - network target lines: `NETWORK 0.0.<node> <host>:50211`
  - transaction and receipt rate lines: `Transactions:`, `Receipts:`, `TPS(EMA)`, `TPS(current)`
  - account/topic creation progress lines: `Created <count> accounts`, `Created <count> topics`
- `<podLogRoot>/network-node<N>_logs/config/application.properties`
- `<podLogRoot>/network-node<N>_logs/config/bootstrap.properties`
- `<podLogRoot>/network-node<N>_logs/config/api-permission.properties`
- `<podLogRoot>/network-node<N>_logs/config/.archive/genesis-network.json`

Unmapped required evidence:

- exact ordinary script-output file or field for learner candidate, stopped pod, teacher candidate, warmtime, downtime,
  and loop count;
- exact image tag or digest source if `version_run.txt` version fields are not sufficient.

## Reconnect Window And Roles

Coverage: complete

Required evidence:

- learner node ID;
- teacher peer node ID from the learner side;
- matching teacher node ID;
- learner peer node ID from the matching teacher log;
- learner reconnect start and end UTC;
- learner reconnect duration in milliseconds;
- learner reconnect status;
- matching teacher reconnect start and end UTC when available;
- matching teacher reconnect status when available.

Mapped sources:

- Primary learner log:

```text
<podLogRoot>/network-node1_logs/swirlds.log
```

- Learner role/window patterns:
  - `ReconnectController: Preparing for reconnect, stopping gossip`
  - `RpcPeerHandler: SELF_FALLEN_BEHIND`
  - `ReconnectStartPayload`
  - `otherNodeId`
  - `ReconnectStatePeerProtocol: Starting reconnect in the role of the receiver`
  - `ReconnectStatePeerProtocol: Finished reconnect in the role of the receiver`
  - `StatusStateMachine: Platform spent ... in BEHIND. Now in RECONNECT_COMPLETE`
  - `StatusStateMachine: Platform spent ... in RECONNECT_COMPLETE. Now in CHECKING`
  - `StatusStateMachine: ... Now in ACTIVE`
- Teacher log derived from learner `otherNodeId`:

```text
teacherLogDir=<podLogRoot>/network-node<otherNodeId + 1>_logs
teacherLog=<teacherLogDir>/swirlds.log
```

- Teacher role/window patterns:
  - `ReconnectStateTeacher: Starting reconnect in the role of the sender`
  - `ReconnectStateTeacher: Finished reconnect in the role of the sender`
- Stats files, useful as reconnect lifecycle cross-checks:

```text
<podLogRoot>/network-node<N>_logs/stats/MainNetStats<M>.csv
```

- Relevant stats columns:
  - `startsReconnectAsReceiver`
  - `endsReconnectAsReceiver`
  - `receiverReconnectDurationSeconds`
  - `startsReconnectAsSender`
  - `endsReconnectAsSender`
  - `senderReconnectDurationSeconds`
  - `reconnectRejections_per_sec_*`

## Learner Evidence

Coverage: complete

Required evidence:

- learner state size at reconnect start;
- learner state size at reconnect end;
- learner-side stage timing when present, such as `Finished synchronization`;
- learner-side source for reconnect work-shape counters.

Mapped sources:

- Primary learner log:

```text
<podLogRoot>/network-node1_logs/swirlds.log
```

- Learner evidence patterns:
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
- Startup and early state metadata:

```text
<podLogRoot>/network-node1_logs/swirlds_reconnect_1.log
```

Use `swirlds.log` for the accepted reconnect window.

## Teacher Evidence

Coverage: partial

Required evidence:

- teacher state size at reconnect start;
- teacher state size at reconnect end when available;
- sender-side log context for the matching learner reconnect.

Mapped sources:

- Teacher log derived from learner `otherNodeId`:

```text
teacherLogDir=<podLogRoot>/network-node<otherNodeId + 1>_logs
teacherLog=<teacherLogDir>/swirlds.log
```

- Teacher evidence patterns:
  - `ReconnectStateTeacher: The following state will be sent to the learner`
  - `ReconnectStateTeacher: Sending signatures from nodes`
  - `TeacherPullVirtualTreeView: Teacher sending root node response`
  - `TeacherPullVirtualTreeReceiveTask: Teacher task: duration=`
  - `TeacherPullVirtualTreeReceiveTask: Teaching is complete as requested by the learner`
  - `TeachingSynchronizer: Finished sending tree`

Unmapped required evidence:

- exact source for teacher state size at reconnect end if the sender log does not contain an end-state path range.

## Reconnect Work-Shape Counters

Coverage: complete

Required evidence:

- transfers from teacher;
- transfers from learner;
- internal hashes and internal clean hashes;
- internal data and internal clean data;
- leaf hashes and leaf clean hashes;
- leaf data and leaf clean data.

Mapped sources:

- Learner log counter pattern:
  - `ReconnectStateLearner: ReconnectMapMetrics:`
- Primary learner log:

```text
<podLogRoot>/network-node1_logs/swirlds.log
```

- Stats files, if reconnect counters are mirrored there:

```text
<podLogRoot>/network-node<N>_logs/stats/MainNetStats<M>.csv
```

Use CSV header rows to locate columns.

## Network Evidence

Coverage: complete

Required evidence:

- RTT between learner and teacher;
- directional bandwidth or throughput evidence;
- TCP/window/backpressure evidence during reconnect;
- full node metrics for cross-checking peer traffic, reconnect counters, and network shape.

Mapped sources:

- Stats files:

```text
<podLogRoot>/network-node<N>_logs/stats/MainNetStats<M>.csv
```

- Relevant stats columns:
  - `bytes_per_sec_sent`
  - `bytes_per_sec_sent_*`
  - `ping_us_*`
  - `ping_us_*MIN`
- Passive socket samples:
  - `runRoot/reconnect_network_samples_1.log`
    - timestamped `ss -tin` samples around the reconnect window
  - `runRoot/network_sampler_network-node<N>-0.log`
    - per-node passive socket samples
  - `runRoot/reconnect_network_samples_1_summary.log`
    - summary file present in `NikitaReconnect1`
- Passive sample fields:
  - `Recv-Q`
  - `Send-Q`
  - peer address
  - `rtt`
  - `minrtt`
  - `cwnd`
  - `ssthresh`
  - `bytes_sent`
  - `bytes_retrans`
  - `bytes_acked`
  - `bytes_received`
  - `send`
  - `pacing_rate`
  - `delivery_rate`
  - `rwnd_limited`
  - `unacked`
  - `notsent`
  - `rcv_space`
  - `snd_wnd`

## Workload Evidence

Coverage: complete

Required evidence:

- workload profile;
- NLG arguments;
- actual transaction rate;
- transaction mix;
- whether load continued while the learner was behind and reconnecting.

Mapped sources:

- `runRoot/client.log`
  - NLG configuration lines: `CONFIG #clients`, `CONFIG #accounts`, `CONFIG #tokens`, `CONFIG #NFTs`,
    `CONFIG schema`, `CONFIG transfer`
  - network target lines: `NETWORK 0.0.<node> <host>:50211`
  - transaction and receipt rate lines: `Transactions:`, `Receipts:`, `TPS(EMA)`, `TPS(current)`
  - account/topic creation progress lines: `Created <count> accounts`, `Created <count> topics`
- `runRoot/version_run.txt`
  - `inputs.NLGDparams`
  - `inputs.NLG_Accounts`
  - `inputs.NLG_Time`
  - `inputs.NLG_Test`

## State And Divergence Evidence

Coverage: partial

Required evidence:

- learner state size near reconnect start;
- teacher state size near reconnect start;
- learner/teacher state gap;
- how long the learner was behind;
- transaction/load profile while the learner was behind;
- teacher growth while the learner is behind and while reconnect runs, if visible;
- reconnect clean/dirty counters;
- service/store size metrics if already available.

Mapped sources:

- Learner path-range patterns:
  - `VirtualMapLearner: Building learner view for map with path range`
  - `VirtualMapLearner: Init reconnect state: firstLeafPath:`
  - `ReconnectHashLeafFlusher: Reconnect flusher initialized with firstLeafPath=`
- Teacher state pattern:
  - `ReconnectStateTeacher: The following state will be sent to the learner`
- Workload sources:
  - `runRoot/client.log`
  - `runRoot/version_run.txt`
- Reconnect counter source:
  - `ReconnectStateLearner: ReconnectMapMetrics:`
- Stats files:

```text
<podLogRoot>/network-node<N>_logs/stats/MainNetStats<M>.csv
```

- Relevant stats columns:
  - `vmap_lifecycle_nodeCacheSizeB_state`
  - `vmap_queries_addedEntities_state`
  - `vmap_queries_readEntities_state`
  - `vmap_queries_removedEntities_state`
  - `vmap_queries_updatedEntities_state`

Unmapped required evidence:

- exact source for teacher growth while reconnect runs if path ranges and listed stats columns are insufficient;
- exact service/store size metric names beyond the listed VirtualMap columns, if needed.

## Later Reconnects

Coverage: complete

Required evidence:

- whether another learner reconnect starts after the first accepted window;
- whether that later reconnect should be excluded from traversal-mode timing;
- whether that later reconnect is useful as state-growth context.

Mapped sources:

- Primary learner log:

```text
<podLogRoot>/network-node1_logs/swirlds.log
```

- Repeat-window scan patterns:
  - `ReconnectController: Preparing for reconnect, stopping gossip`
  - `RpcPeerHandler: SELF_FALLEN_BEHIND`
  - `ReconnectStatePeerProtocol: Starting reconnect in the role of the receiver`
  - `ReconnectStatePeerProtocol: Finished reconnect in the role of the receiver`
  - `StatusStateMachine: Platform spent ... in BEHIND. Now in RECONNECT_COMPLETE`
  - `StatusStateMachine: ... Now in ACTIVE`

## Analysis Output Per Mode

Coverage: complete

Required evidence:

- source mapping for each output field in the per-mode analysis block.

Mapped sources:

| Output field | Atlas source section |
| --- | --- |
| `Traversal mode` | Run Context |
| `Artifact directory` | Run Roots |
| `Commit` | Run Context |
| `Image` | Run Context |
| `Learner node` | Reconnect Window And Roles |
| `Teacher node` | Reconnect Window And Roles |
| `First reconnect start UTC` | Reconnect Window And Roles |
| `First reconnect end UTC` | Reconnect Window And Roles |
| `Learner duration` | Reconnect Window And Roles |
| `Teacher reconnect context present` | Teacher Evidence |
| `Reconnect stats present` | Reconnect Work-Shape Counters |
| `Teacher/learner state size present` | Learner Evidence, Teacher Evidence, State And Divergence Evidence |
| `Workload profile present` | Workload Evidence |
| `RTT evidence present` | Network Evidence |
| `Bandwidth evidence present` | Network Evidence |
| `TCP/window evidence present` | Network Evidence |
| `Later reconnects observed` | Later Reconnects |
| `Run accepted for calibration` | Acceptance Criteria / Coverage Summary |
| `Reason if not accepted` | Acceptance Criteria / Coverage Summary |

## Acceptance Criteria / Coverage Summary

This section summarizes source-mapping coverage only. It does not determine whether any run is accepted for calibration;
that requires extraction and analysis later.

| Protocol section | Coverage | Remaining unmapped required evidence |
| --- | --- | --- |
| Run Context | partial | ordinary script-output source for learner/stopped-pod/timing controls; exact image tag or digest if version fields are insufficient |
| Reconnect Window And Roles | complete | - |
| Learner Evidence | complete | - |
| Teacher Evidence | partial | exact source for teacher state size at reconnect end if absent from sender log |
| Reconnect Work-Shape Counters | complete | - |
| Network Evidence | complete | - |
| Workload Evidence | complete | - |
| State And Divergence Evidence | partial | exact teacher-growth source during reconnect; additional service/store size metric names if needed |
| Later Reconnects | complete | - |
| Analysis Output Per Mode | complete | - |
