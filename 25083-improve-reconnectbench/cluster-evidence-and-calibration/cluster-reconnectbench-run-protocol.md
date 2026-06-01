# Cluster ReconnectBench Calibration Protocol

Date: `2026-05-21`

## Purpose

This document defines the lean cluster run protocol needed to calibrate local `ReconnectBench` against a real cluster.

The goal is not to collect every possible diagnostic artifact. The goal is to answer one practical question:

```text
Can local ReconnectBench, with calibrated state and network inputs, reproduce the same traversal-mode ordering and broad
reconnect shape observed in the cluster?
```

The protocol should minimize DevOps time. We should prepare the configuration/script changes ourselves, then ask DevOps
only where the shell changes should live.

## Core Principle

Start from what local `ReconnectBench` can report, then collect the cluster equivalent.

Local `ReconnectBench` currently reports:

- traversal mode;
- wall-clock benchmark time;
- reconnect transfer counters;
- clean internal/leaf hash/data counters;
- simulated network profile;
- simulated in-flight/backpressure diagnostics.

The cluster run must therefore provide the real-world equivalents:

- traversal mode used for the run;
- first learner reconnect window and matching teacher peer;
- learner reconnect duration;
- reconnect transfer counters and clean/dirty work shape;
- coarse state size and divergence shape;
- enough network evidence to choose local latency, bandwidth, and in-flight assumptions.

Everything else is fallback diagnostics.

## Working Assumptions

- The cluster test branch should be based on latest `main`, not this prototype branch.
- No production reconnect log or metric changes are needed for the first cluster calibration pass.
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

## Artifact Extraction Contract

Use the existing low-volume metrics, reconnect lifecycle logs, and VirtualMap path-range/state-info logs that were
validated locally. Do not add logs on hot paths, and do not add duplicate parsing-only logs. We will receive the full
cluster artifact bundle and extract the needed fields ourselves from node logs, node metrics, script output, and
configuration files.

Forbidden for this calibration work:

- per-node traversal logs;
- per-leaf logs;
- per-request or per-response logs;
- per-byte or per-message logs;
- any logging that can create gigabyte-scale files during reconnect.

Expected artifact sources:

- existing counters updated through metric paths;
- summary counters already accumulated by reconnect code;
- existing reconnect lifecycle logs;
- existing VirtualMap path-range and state-info logs;
- script output and configuration values that identify the traversal mode and run context.

The fields below define what analysis must extract for each accepted reconnect window. They are not a node logging
format.

### Learner Summary

For the first learner reconnect in each traversal-mode run, extract:

- traversal mode from script output or configuration;
- learner node ID;
- teacher peer node ID;
- learner reconnect start UTC;
- learner reconnect end UTC;
- learner reconnect duration in milliseconds;
- transfers from teacher;
- transfers from learner;
- internal hashes;
- internal clean hashes;
- internal data;
- internal clean data;
- leaf hashes;
- leaf clean hashes;
- leaf data;
- leaf clean data;
- learner state size at reconnect start, inferred from existing `firstLeafPath` / `lastLeafPath` ranges;
- learner state size at reconnect end, inferred from existing `firstLeafPath` / `lastLeafPath` ranges;
- reconnect status.

Derived values do not need to be extracted as separate fields if raw counters exist:

```text
internalDirtyHashes = internalHashes - internalCleanHashes
internalDirtyData = internalData - internalCleanData
leafDirtyHashes = leafHashes - leafCleanHashes
leafDirtyData = leafData - leafCleanData
```

### Teacher Context

For the matching teacher reconnect, extract supporting context:

- traversal mode from script output or configuration;
- teacher node ID;
- learner peer node ID;
- teacher reconnect start UTC;
- teacher reconnect end UTC when available;
- teacher state size at reconnect start, inferred from existing `firstLeafPath` / `lastLeafPath` ranges;
- teacher state size at reconnect end when available, or an explicit unavailable note;
- reconnect status.

Teacher duration is not a primary calibration target. It is useful for matching the teacher-side log window to the
learner-side reconnect, but learner reconnect completion is the timing we optimize because the learner can continue
processing transactions after it reconnects.

Exact reconnect byte totals are not required for this calibration pass. The cluster evidence we need is the network
environment shape: RTT, throughput/bandwidth evidence, and TCP/window/backpressure evidence. Reconnect transfer counts
and clean/dirty counters remain the work-shape evidence.

### Run Context From Script Output

Do not require the run script to produce a separate run-summary file. The expected artifact set is ordinary script
output, node logs, node metrics, and any explicitly started network sampler output.

When analyzing a run, extract or infer this context from script output and configuration:

```text
commit=<git SHA>
image=<image tag or digest>
mode=<virtualMap.reconnectMode>
baseline=<baseline restore identifier>
networkSize=<node count>
learnerCandidate=<expected learner node id if known>
teacherCandidate=<expected teacher node id if known>
workloadProfile=<short name or description>
learnerBehindDuration=<duration if controlled by script>
transactionRate=<rate if controlled by script>
transactionMix=<short description if controlled by script>
configSummary=<path or short config identifier>
```

If the script already prints these values, keep that output. If it does not, rely on the normal script output and
configuration files unless adding a short context print is the simplest way to make the output readable.

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

## Minimal Run Procedure

For each traversal mode:

1. Restore the same cluster baseline state.
2. Set only:

   ```text
   virtualMap.reconnectMode=<mode>
   ```

3. Start the cluster with the selected latest-`main` based test branch/configuration.
4. Run the same fallen-behind/reconnect scenario.
5. Capture script output.
6. Capture full node logs for the run, with learner and teacher logs required.
7. Capture full metrics exports for all nodes in the run.
8. Capture required network evidence described below.
9. Identify the first learner reconnect window and matching teacher peer.
10. Exclude later reconnects from the traversal-mode timing result.
11. Record whether the run is accepted for local calibration.

If the learner immediately enters a second reconnect, keep it as a note about state growth during the run. Do not mix it
into the first traversal-mode result.

## Analysis Output Per Mode

After the artifacts are available, fill this table during analysis:

```text
Traversal mode:
Commit:
Learner node:
Teacher node:
First reconnect start UTC:
First reconnect end UTC:
Learner duration:
Teacher reconnect context present: yes/no
Reconnect stats present: yes/no
Teacher/learner state size present: yes/no
Workload profile present: yes/no
RTT evidence present: yes/no
Bandwidth evidence present: yes/no
TCP/window evidence present: yes/no
Later reconnects observed: yes/no
Run accepted for calibration: yes/no
Reason if not accepted:
```

## Acceptance Criteria

A traversal run is accepted for calibration when it has:

- confirmed traversal mode;
- confirmed learner node and matching teacher peer;
- first learner reconnect start and end times;
- learner reconnect duration;
- reconnect transfer and clean/dirty counters from existing learner-side reconnect metrics/logs;
- enough state/workload context to classify divergence at a coarse level;
- RTT, bandwidth, and TCP/window evidence sufficient to map the cluster network to local benchmark settings;
- clear note if later reconnects occurred after the first window.

A run is still useful but incomplete if it has duration only. Duration without reconnect counters and coarse divergence
context cannot validate whether local `ReconnectBench` is modeling the same work shape. Duration without network
evidence also cannot calibrate the local network simulator.

## Network Evidence

The local benchmark has explicit network settings:

```text
networkLatencyMicroseconds
networkBandwidthMegabitsPerSecond
networkInflightBytesLimit
```

The cluster run therefore needs corresponding network evidence from the same test environment. This is required
calibration evidence, not fallback diagnostics.

Required network evidence:

1. RTT between learner and teacher.
   - Prefer direct pod-to-pod or node-to-node RTT around the reconnect scenario.
   - Existing cluster ping metrics are acceptable if direct RTT is not available.
2. Directional bandwidth evidence.
   - Collect directional throughput outside the reconnect window if the scripts/environment make it practical.
   - Prefer existing cluster, pod, or node network metrics for the test environment.
   - Do not add reconnect byte counters only to compute effective reconnect rates.
3. TCP/window/backpressure evidence during reconnect.
   - Capture `ss -ti` or equivalent samples for the actual reconnect connection.
   - This is the best evidence for whether local `networkInflightBytesLimit` should be neutral or constrained.
   - If the environment cannot provide TCP/window samples, record that as a calibration gap.
4. Full node metrics for the run.
   - Metrics are easy to collect and help cross-check peer traffic, reconnect counters, and network shape.

Do not run active bandwidth generators during reconnect. They can change the thing we are trying to measure.

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
- let samplers continue until the learner finish lifecycle log for the first reconnect is observed;
- stop samplers immediately after the first learner reconnect window completes.

Use active throughput tests outside the reconnect window:

- do not run `iperf3` or other bandwidth-generating tests during reconnect unless the explicit goal is to study
  interference;
- run directional throughput checks before the fallen-behind scenario or after the reconnect run, using the same pod
  placement when possible;
- use passive metrics during reconnect; do not require exact reconnect byte totals for this calibration pass.

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
| Sustained throughput or bandwidth evidence | `networkBandwidthMegabitsPerSecond`, using lower/average/nominal values as a small sweep |
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

Use this section only if the required evidence is missing or local/cluster results cannot be explained.

Possible fallback artifacts:

- JVM version, heap flags, and command line;
- Kubernetes pod placement;
- pod and node descriptions;
- `/proc/net/dev` or `ip -s link` before/after samples;
- config file copies for reconnect, virtual map, MerkleDB, socket, and JVM settings.

These diagnostics should not be part of the default DevOps ask.

## Immediate Next Work

1. Keep the cluster test branch based on latest `main`, without production reconnect log or metric changes.
2. Ask DevOps where the shell/script modifications should live.
3. Prepare script changes that parameterize `virtualMap.reconnectMode`, restore the same baseline, and collect node
   logs, metrics, script output, and network evidence.
4. Run one cluster reconnect per traversal mode.
5. Import the resulting summaries into `25083-improve-reconnectbench/cluster-evidence-and-calibration/cluster-metrics`.
6. Update `cluster-metrics-analysis.md` with the new evidence and the local calibration implications.

## Open Questions For Reconnect Experiment Owner

These questions are for the software development colleague who has run reconnect experiments before. They should be
answered before finalizing the run scripts.

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
