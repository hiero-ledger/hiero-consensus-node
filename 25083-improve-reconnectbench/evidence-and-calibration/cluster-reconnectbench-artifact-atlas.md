# Cluster ReconnectBench Artifact Atlas

Updated: `2026-06-02`

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

Do not record extracted values in this atlas. Record only paths, file patterns, log patterns, metric names, and required
evidence without exact source mapping.

Use `historical-cluster-metrics-analysis.md` for metric-name and interpretation guidance only. Do not import historical
run values as evidence for the current run roots.

## Run Roots

Use `runRoot` below as the base path for top-level run artifacts, network sampler files, and `version_run.txt`.

| Run bucket | `runRoot` | Pod log root |
| --- | --- | --- |
| Top-to-bottom run | `NikitaReconnect1` | `NikitaReconnect1/podlog_solo-mdlt-n3` |
| Two-phase pessimistic run | `NikitaReconnect2_2phase/report` | `NikitaReconnect2_2phase/report/podlog_solo-mdlt-n4` |
| Parallel-sync run | `NikitaReconnect3_PullParallelSync/report` | `NikitaReconnect3_PullParallelSync/report/podlog_solo-mdlt-n4` |

Each artifact bucket now also contains workflow logs:

```text
performance-tests-start.log
performance-tests-watch.log
```

For `NikitaReconnect1`, these files live directly under `runRoot`. For report-based run roots, these files live beside
`report`, so resolve them as `runRoot/../performance-tests-start.log` and
`runRoot/../performance-tests-watch.log`.

Node log directories follow this pattern:

```text
<podLogRoot>/network-node<N>_logs
```

`network-node1_logs` is node ID `0`, `network-node2_logs` is node ID `1`, and so on.

## Run Context

Coverage: partial

Required evidence:

- commit SHA, from artifacts if present or manually supplied if absent;
- version context, if captured;
- traversal mode;
- namespace identifier, if captured;
- network size;
- stopped pod;
- workload profile and NLG arguments;
- warmtime, downtime, and loop count;
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
- `runRoot/performance-tests-start.log`, or `runRoot/../performance-tests-start.log` for report-based run roots
  - workflow-level workload/NLG setup output
  - baseline/restored-state upload step output, including skipped-upload evidence
- `runRoot/performance-tests-watch.log`, or `runRoot/../performance-tests-watch.log` for report-based run roots
  - reconnect-loop controls such as `downtime`, `warmtime`, and `NofLoops`
  - `profileReconnectLoopK8s.sh` invocation context
  - generic process-stop markers such as `Stopping java`; treat as ambiguous stopped-pod evidence unless the exact pod
    identity is printed or a separate multi-source inference documents the intended learner, host config, artifact node
    mapping, and observed learner reconnect
- In some artifacts, reconnect-loop control lines may be in `performance-tests-start.log` instead of
  `performance-tests-watch.log`; search both workflow logs.
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

Required evidence without exact source mapping:

- exact artifact source for commit SHA if `version_run.txt` does not contain it;
- direct stopped-pod script output, if emitted;

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

Coverage: complete

Required evidence:

- teacher sent state size;
- sampled teacher state-size growth during the reconnect window when stats coverage is available;
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
- Stats files for sampled teacher state size during the reconnect window:

```text
<podLogRoot>/network-node<N>_logs/stats/MainNetStats<M>.csv
```

- Relevant stats column:
  - `vmap_size_state`

`vmap_size_state` is sampled teacher live-state evidence during the reconnect window. The teacher root response
range is the source for the sent snapshot size.

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
    - the sampler filename identifies the Kubernetes pod, and the first local-address rows can be used as the pod's
      local IP evidence
  - `runRoot/reconnect_network_samples_1_summary.log`
    - summary file present in `NikitaReconnect1`
- Endpoint attribution sources:
  - `<podLogRoot>/network-node<N>_logs/config/settingsUsed.txt`
    - `HOSTNAME`
    - `POD_IP`, when present
  - `runRoot/network_sampler_network-node<N>-0.log`
    - local address in `ss -tin` output, cross-checked with the sampler filename
  - reconnect role logs, to map node IDs to node log directories before joining pod/IP evidence:
    - learner-side `ReconnectStartPayload` / `otherNodeId`
    - teacher-side `ReconnectStateTeacher: Starting reconnect in the role of the sender`
- Workflow logs may show plain `get pods` output without IP columns; do not require workflow pod listings for
  endpoint attribution when `settingsUsed.txt` or per-pod sampler evidence is available.
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
- `runRoot/performance-tests-start.log`, or `runRoot/../performance-tests-start.log` for report-based run roots
  - workflow-level workload/NLG setup output

## State And Divergence Evidence

Coverage: complete

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
  - `runRoot/performance-tests-start.log`, or `runRoot/../performance-tests-start.log` for report-based run roots
- Reconnect counter source:
  - `ReconnectStateLearner: ReconnectMapMetrics:`
- Stats files:

```text
<podLogRoot>/network-node<N>_logs/stats/MainNetStats<M>.csv
```

- Relevant stats columns:
  - `vmap_size_state`
  - `accountsUsed`
  - `contractsUsed`
  - `nftsUsed`
  - `tokenAssociationsUsed`
  - `tokensUsed`
  - `topicsUsed`
  - `vmap_lifecycle_nodeCacheSizeB_state`
  - `vmap_queries_addedEntities_state`
  - `vmap_queries_readEntities_state`
  - `vmap_queries_removedEntities_state`
  - `vmap_queries_updatedEntities_state`
  - `vmap_lifecycle_flushCount_state`
  - `vmap_lifecycle_flushDurationMs_state`
  - `vmap_lifecycle_hashDurationMs_state`
  - `vmap_lifecycle_mergeDurationMs_state`
  - `vmap_lifecycle_flushBackpressureMs_state`
  - `vmap_lifecycle_familySizeBackpressureMs_state`
  - `vmap_lifecycle_pipelineSize_state`

Use `vmap_size_state` and service-store `*Used` counters for state size and coarse divergence. Do not use first/last
`vmap_queries_*` deltas as divergence totals. Keep `vmap_lifecycle_*` metrics separate from traversal and network
interpretation; they are storage/lifecycle stage evidence.

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

| Protocol section | Coverage | Required evidence without exact source mapping |
| --- | --- | --- |
| Run Context | partial | commit SHA if absent from `version_run.txt`; ordinary script-output source for learner controls; direct stopped-pod script output is absent but extracted evidence can infer `stoppedPod=network-node1-0` |
| Reconnect Window And Roles | complete | - |
| Learner Evidence | complete | - |
| Teacher Evidence | complete | - |
| Reconnect Work-Shape Counters | complete | - |
| Network Evidence | complete | - |
| Workload Evidence | complete | - |
| State And Divergence Evidence | complete | - |
| Later Reconnects | complete | - |
| Analysis Output Per Mode | complete | - |
