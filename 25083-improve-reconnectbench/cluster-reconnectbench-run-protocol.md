# Cluster ReconnectBench Calibration Protocol

Date: `2026-05-21`

## Purpose

This document defines the lean cluster run protocol needed to calibrate local `ReconnectBench` against a real cluster.

The goal is not to collect every possible diagnostic artifact. The goal is to answer one practical question:

```text
Can local ReconnectBench, with calibrated state and network inputs, reproduce the same traversal-mode ordering and broad
reconnect shape observed in the cluster?
```

The protocol should minimize DevOps time. We should prepare the instrumentation branch and script changes ourselves,
then ask DevOps only where the shell changes should live.

## Core Principle

Start from what local `ReconnectBench` can report, then collect the cluster equivalent.

Local `ReconnectBench` currently reports:

- traversal mode;
- wall-clock benchmark time;
- reconnect transfer counters;
- clean internal/leaf hash/data counters;
- teacher-to-learner bytes;
- learner-to-teacher bytes;
- simulated network profile;
- simulated in-flight/backpressure diagnostics.

The cluster run must therefore provide the real-world equivalents:

- traversal mode used for the run;
- first matched learner/teacher reconnect window;
- learner and teacher reconnect durations;
- reconnect transfer counters and clean/dirty work shape;
- bytes by direction, or the closest reliable byte evidence available;
- coarse state size and divergence shape;
- enough network evidence to choose local latency, bandwidth, and in-flight assumptions.

Everything else is fallback diagnostics.

## Working Assumptions

- The cluster telemetry branch should be based on latest `main`, not this prototype branch.
- The telemetry branch may add temporary structured reconnect telemetry, but must not change reconnect behavior.
- The run should be able to start each traversal mode from the same baseline state.
- Script modifications will be prepared by us once DevOps confirms where those shell changes should live.
- The only remaining DevOps question should be:

```text
Where should the cluster run script changes be placed?
```

## Traversal Run Matrix

Target one cluster reconnect per traversal mode:

1. `pullTopToBottom`
2. `pullParallelSync`
3. `pullTwoPhasePessimistic`

If cluster time is reduced further, preserve `pullTopToBottom` and `pullParallelSync` first. They are the primary
comparison for the local benchmark calibration. `pullTwoPhasePessimistic` is still useful because local diagnostics show
it can behave differently under in-flight constraints.

Do not spend cluster time on repeated runs until a first calibration pass exists. A single run per mode is directional,
not statistically conclusive, but it is enough to check whether the local benchmark is pointed at the right shape.

## Required Telemetry Contract

Prefer low-volume structured metrics and reconnect-boundary logs. Do not add logs on hot paths.

Forbidden for this calibration work:

- per-node traversal logs;
- per-leaf logs;
- per-request or per-response logs;
- per-byte or per-message logs;
- any logging that can create gigabyte-scale files during reconnect.

Acceptable telemetry:

- existing counters updated through metric paths;
- summary counters already accumulated by reconnect code;
- one structured log line at reconnect start;
- one structured summary log line at reconnect completion;
- one script-level run manifest per traversal mode.

Use stable field names. Prefer `key=value` fields on a single line so logs can be extracted with simple tools.

### Learner Summary

Emit once per learner reconnect completion, after synchronization/finalization data is available.

Required fields:

```text
event=reconnect_calibration_learner_summary
runId=<script run id>
mode=<virtualMap.reconnectMode>
role=learner
nodeId=<learner node id>
peerNodeId=<teacher node id if available>
startTime=<UTC instant>
endTime=<UTC instant>
durationMillis=<learner reconnect duration>
receivedBytes=<learner received bytes if available>
transfersFromTeacher=<counter>
transfersFromLearner=<counter>
internalHashes=<counter>
internalCleanHashes=<counter>
internalData=<counter>
internalCleanData=<counter>
leafHashes=<counter>
leafCleanHashes=<counter>
leafData=<counter>
leafCleanData=<counter>
stateSizeAtStart=<coarse virtual-map or aggregate state size if cheap>
stateSizeAtEnd=<coarse virtual-map or aggregate state size if cheap>
status=success|failure
```

Derived values do not need to be logged if raw counters exist:

```text
internalDirtyHashes = internalHashes - internalCleanHashes
internalDirtyData = internalData - internalCleanData
leafDirtyHashes = leafHashes - leafCleanHashes
leafDirtyData = leafData - leafCleanData
```

### Teacher Summary

Emit once per teacher reconnect completion.

Required fields:

```text
event=reconnect_calibration_teacher_summary
runId=<script run id>
mode=<virtualMap.reconnectMode>
role=teacher
nodeId=<teacher node id>
peerNodeId=<learner node id if available>
startTime=<UTC instant>
endTime=<UTC instant>
durationMillis=<teacher reconnect duration>
sentBytes=<teacher-to-learner bytes if safely available>
stateSizeAtStart=<coarse virtual-map or aggregate state size if cheap>
stateSizeAtEnd=<coarse virtual-map or aggregate state size if cheap>
status=success|failure
```

If exact `sentBytes` is difficult to collect safely, record that explicitly and fall back to existing learner received
bytes plus application/network send-rate metrics.

### Run Manifest

The run script should produce or print one small manifest per traversal mode.

Required fields:

```text
event=reconnect_calibration_run_manifest
runId=<script run id>
commit=<git SHA>
image=<image tag or digest>
mode=<virtualMap.reconnectMode>
baseline=<baseline restore identifier>
freshRestoreUsed=true|false
networkSize=<node count>
learnerCandidate=<expected learner node id if known>
teacherCandidate=<expected teacher node id if known>
workloadProfile=<short name or description>
learnerBehindDuration=<duration if controlled by script>
transactionRate=<rate if controlled by script>
transactionMix=<short description if controlled by script>
configSummary=<path or short config identifier>
```

The manifest does not need to include full pod descriptions, JVM flags, or Kubernetes node inventory for the first pass.
Those are fallback diagnostics.

## Divergence Strategy

Do not try to log exact per-key divergence in the first cluster attempt. That would add too much code and telemetry.

Infer divergence from coarse run facts:

- teacher and learner state size near reconnect start;
- state size gap between learner and teacher;
- how long the learner was behind;
- transaction/load profile while the learner was behind;
- teacher growth while the reconnect was running;
- reconnect clean/dirty counters from `ReconnectMapMetrics`;
- service/store size metrics if already available.

For local `ReconnectBench`, this is enough to classify the cluster shape as approximately:

- append/growth-heavy;
- modify-heavy;
- remove-heavy;
- mixed.

The first local approximation should use the starting learner/teacher gap. If the teacher continues growing while
reconnect runs, record that as cluster behavior to validate later, not as a reason to block the first calibration pass.

## Open Questions For Reconnect Experiment Owner

These questions are for the teammate who has run reconnect experiments before. They should be answered before finalizing
the instrumentation branch and run scripts.

State size:

- What initial state size is large enough to exercise realistic reconnect traversal without making each cluster run too
  expensive?
- Should the target be closer to `50M`, `100M`, or `200M` records, and should that number represent one virtual map or
  aggregate service-state size?
- What teacher and learner state sizes should exist at the moment reconnect starts?
- Is there a known state-size threshold below which traversal ordering is misleading?

Learner-behind window:

- How long should the learner be stopped before restart?
- Should the stop duration be chosen by elapsed time, by target state-size gap, by number of rounds, or by transaction
  count?
- Should the learner be restarted as soon as the teacher reaches a target size, or should the script use a fixed
  stop-and-restart schedule?

Workload and divergence shape:

- What workload should run while the learner is stopped?
- Which transaction types dominate the desired reconnect scenario: entity creation, token associations, account updates,
  contract work, deletes, or a mixed profile?
- Is the intended divergence mostly append/growth-heavy, modify-heavy, remove-heavy, or mixed?
- Do we already know the transaction rate and transaction mix from previous reconnect experiments?
- Should the teacher keep processing the same workload while reconnect is running, or should workload pause before the
  learner restarts?

Local benchmark mapping:

- Which cluster state-size and divergence facts should be treated as the target for local `ReconnectBench`: starting
  gap, ending gap, or teacher growth during reconnect?
- If exact divergence is not available, which coarse proxy is preferred: state-size delta, transaction count, elapsed
  time behind, or workload profile?
- Are there existing reconnect experiment notes that explain which state shape previously produced useful cluster
  signal?

## Minimal Run Procedure

For each traversal mode:

1. Restore the same cluster baseline state.
2. Set only:

   ```text
   virtualMap.reconnectMode=<mode>
   ```

3. Start the cluster with the instrumentation branch.
4. Run the same fallen-behind/reconnect scenario.
5. Capture the run manifest.
6. Capture learner and teacher logs containing the calibration summary events.
7. Capture any existing metrics export that is already part of the normal run.
8. Identify the first matched learner/teacher reconnect window.
9. Exclude later reconnects from the traversal-mode timing result.
10. Record whether the run is accepted for local calibration.

If the learner immediately enters a second reconnect, keep it as a note about state growth during the run. Do not mix it
into the first traversal-mode result.

## Minimal Output Per Mode

Each traversal mode should end with this table filled in:

```text
Traversal mode:
Run ID:
Commit:
Fresh restore used:
Learner node:
Teacher node:
First reconnect start UTC:
First reconnect end UTC:
Learner duration:
Teacher duration:
Learner received bytes:
Teacher sent bytes:
Reconnect stats present: yes/no
Teacher/learner state size present: yes/no
Workload profile present: yes/no
Later reconnects observed: yes/no
Run accepted for calibration: yes/no
Reason if not accepted:
```

## Acceptance Criteria

A traversal run is accepted for calibration when it has:

- confirmed traversal mode;
- confirmed learner and teacher nodes;
- first matched learner/teacher reconnect start and end times;
- learner duration, and teacher duration when available;
- reconnect transfer and clean/dirty counters from learner-side summary telemetry;
- byte evidence for at least teacher-to-learner traffic, with learner-to-teacher byte evidence preferred;
- enough state/workload context to classify divergence at a coarse level;
- clear note if later reconnects occurred after the first window.

A run is still useful but incomplete if it has duration only. Duration without reconnect counters and coarse divergence
context cannot validate whether local `ReconnectBench` is modeling the same work shape.

## Network Evidence

The first calibration pass needs enough network evidence to choose local benchmark parameters, but it does not need a
full network investigation.

Use this priority order:

1. Direct learner-to-teacher RTT if available from an existing script or lightweight command.
2. Existing cluster ping metrics if direct RTT is not available.
3. Exact reconnect byte counts and duration to compute observed effective rates.
4. Existing per-peer send-rate metrics if exact byte counts are incomplete.
5. TCP/window samples only if local and cluster results disagree, or if byte/rate evidence suggests backpressure.

Do not require `ss -ti`, `iperf3`, pod-level interface counters, or Kubernetes node diagnostics for the first pass.
Those are useful only when the required telemetry cannot explain the result.

### Timing Network Collection With The Reconnect Flow

The expected test flow is:

```text
restore baseline -> start cluster -> stop learner -> run workload while learner is behind -> restart learner -> learner
detects fallen-behind state -> reconnect starts -> reconnect completes
```

Network collection should follow that flow without perturbing reconnect.

Use passive collection during reconnect:

- start log and metrics capture before restarting the learner;
- if TCP/window samples are needed, start the teacher-side sampler before restarting the learner and start the
  learner-side sampler as soon as the learner pod is running;
- let samplers continue until the learner and teacher summary events for the first reconnect are both observed;
- stop samplers immediately after the first matched reconnect window completes.

Use active throughput tests outside the reconnect window:

- do not run `iperf3` or other bandwidth-generating tests during reconnect unless the explicit goal is to study
  interference;
- run directional throughput checks before the fallen-behind scenario or after the reconnect run, using the same pod
  placement when possible;
- use reconnect summary bytes and reconnect duration as the primary effective-rate evidence for the reconnect itself.

RTT can be collected with minimal interference:

- if both learner and teacher pods exist before the learner is stopped, capture a short baseline RTT sample then;
- after the learner restarts, capture another short RTT sample as soon as the pod is reachable;
- if continuous ping is cheap and already allowed, it may run across the reconnect window, but it is not required if
  existing cluster ping metrics already constrain the latency profile.

## Mapping Cluster Results Back To Local ReconnectBench

Use the cluster output to choose local parameters in this order:

| Cluster evidence | Local `ReconnectBench` input |
| --- | --- |
| RTT between learner and teacher | `networkLatencyMicroseconds`, using roughly half RTT because the simulator models one-way latency |
| Effective or sustained byte rate | `networkBandwidthMegabitsPerSecond`, using lower/average/nominal values as a small sweep |
| TCP/window or backpressure evidence | `networkInflightBytesLimit`; use a large neutral cap unless evidence shows a real limit |
| Teacher/learner state size | local `numFiles * numRecords` target |
| State size gap and workload profile | local add/modify/remove probabilities or future divergence controls |
| Reconnect clean/dirty counters | validation that local state shape resembles cluster state shape |
| Cluster traversal ordering | target ordering for local traversal-mode runs |

If cluster traversal ordering differs from local ordering:

- If clean/dirty and transfer counters differ strongly, fix local state/divergence modeling first.
- If counters are similar but ordering differs, tune network latency, bandwidth, and in-flight assumptions first.
- If counters and network shape both match but cluster is much slower, investigate finalization, flush, state load, and
  state write timing.

## Fallback Diagnostics

Use this section only if the minimal telemetry is missing or local/cluster results cannot be explained.

Possible fallback artifacts:

- direct `ping` between learner and teacher pods;
- directional throughput test between learner and teacher;
- `ss -ti` samples for the reconnect connection;
- JVM version, heap flags, and command line;
- Kubernetes pod placement;
- pod and node descriptions;
- `/proc/net/dev` or `ip -s link` before/after samples;
- full metrics CSVs for all nodes;
- config file copies for reconnect, virtual map, MerkleDB, socket, and JVM settings.

These diagnostics should not be part of the default DevOps ask.

## Immediate Next Work

1. Create a separate instrumentation branch based on latest `main`.
2. Add low-volume reconnect summary telemetry, using metrics or reconnect-boundary structured logs only.
3. Ask DevOps where the shell/script modifications should live.
4. Prepare script changes that parameterize `virtualMap.reconnectMode`, restore the same baseline, and collect the
   calibration summary logs.
5. Run one cluster reconnect per traversal mode.
6. Import the resulting summaries into `25083-improve-reconnectbench/cluster-metrics`.
7. Update `cluster-metrics-analysis.md` with the new evidence and the local calibration implications.
