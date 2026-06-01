# Cluster ReconnectBench Artifact Processing Protocol

Updated: `2026-06-01`

## Purpose

This document defines the artifact extraction protocol for the collected cluster ReconnectBench calibration runs.

The cluster run planning phase is complete. The traversal-order artifacts have been collected under:

```text
/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs
```

The current goal is to process those artifacts into comparable per-mode evidence, then use that evidence to decide
whether local `ReconnectBench` can reproduce the same traversal-mode ordering and broad reconnect shape observed in the
cluster.

## Core Principle

Start from what local `ReconnectBench` reports, then extract the cluster equivalent from existing artifacts.

Local `ReconnectBench` reports:

- traversal mode;
- wall-clock benchmark time;
- reconnect transfer counters;
- clean internal/leaf hash/data counters;
- simulated network profile;
- simulated in-flight/backpressure diagnostics.

For each cluster traversal run, extract the real-world equivalents:

- traversal mode used for the run;
- first learner reconnect window and matching teacher peer;
- learner reconnect duration;
- reconnect transfer counters and clean/dirty work shape;
- coarse state size and divergence shape;
- enough network evidence to choose local latency, bandwidth, and in-flight assumptions.

Everything else is fallback diagnostics.

## Collected Run Shape

Keep these run-shape facts with the extraction protocol so the collected artifacts are interpreted in the context in
which they were produced. Treat them as expected run context to verify from the artifacts, not as substitutes for
parsing the logs, settings, metrics, and script output.

- The collected data came from the performance-analysis reconnect workflow, not the single-day longevity workflow.
- The run strategy was one full workflow/job per traversal order, rather than an in-script traversal matrix.
- The intended traversal orders were:
  - `pullTopToBottom`
  - `pullParallelSync`
  - `pullTwoPhasePessimistic`
- The intended learner was `network-node1-0` / node `0`.
- The intended reconnect shape used:
  - `warmtime=600`
  - `downtime=1800`
  - `NofLoops=0`, which was chosen for one reconnect iteration with the script semantics used for this run.
- The intended NLG state/load shape used `24M` NLG accounts and the default `8K` TPS cap. This was chosen to target
  roughly `100M` Virtual Map records on the learner and about `10M` additional records of teacher/learner divergence.
- Load was not removed before the learner restarted; validate the actual workload rate and timing from NLG/client logs.
- Passive TCP/socket/network evidence was collected around the reconnect window, from learner restart through learner
  `ACTIVE`. Do not depend on the old draft script details; DevOps debugged and changed the actual implementation.
- Production reconnect telemetry changes were not part of this pass.

## Processing Sequence

For each traversal-order artifact directory:

1. Identify run context:
   - traversal mode;
   - commit SHA;
   - image tag or digest, if captured;
   - workload profile and NLG arguments;
   - learner candidate and stopped pod;
   - downtime, warmtime, and loop count;
   - cluster namespace or baseline identifier, if captured.
2. Identify the first learner reconnect window:
   - learner node ID;
   - teacher peer node ID;
   - learner reconnect start UTC;
   - learner reconnect end UTC;
   - learner duration.
3. Match the teacher context for the same reconnect:
   - teacher node ID;
   - learner peer node ID;
   - teacher reconnect start UTC;
   - teacher reconnect end UTC when available;
   - teacher state context when available.
4. Extract reconnect work-shape counters:
   - transfers from teacher;
   - transfers from learner;
   - internal hashes and internal clean hashes;
   - internal data and internal clean data;
   - leaf hashes and leaf clean hashes;
   - leaf data and leaf clean data.
5. Extract state and divergence context:
   - learner state size near reconnect start;
   - teacher state size near reconnect start;
   - learner/teacher state gap;
   - teacher growth while the learner is behind and while reconnect runs, if visible;
   - workload mix and actual transaction rate while the learner is behind.
6. Extract network evidence:
   - RTT evidence from direct probes or existing `ping_us_*` metrics;
   - bandwidth or observed throughput evidence from metrics, reconnect data usage, or interface counters;
   - TCP/window/backpressure evidence from passive sampler output such as `ss -tin`.
7. Record later reconnects:
   - whether another learner reconnect starts after the first accepted window;
   - whether the later reconnect should be excluded from traversal-mode timing.
8. Produce one analysis row for the traversal run and mark whether the run is accepted for calibration.

Do not mix later reconnects into the first traversal-mode timing result. If a learner immediately enters a second
reconnect, keep it as state-growth context.

## Artifact Sources

Expected artifact sources are:

- ordinary script output;
- `client.log` and NLG output;
- node `swirlds.log` files;
- node metrics and stats CSVs;
- `settingsUsed.txt`, `config.txt`, and copied configuration files;
- `version_run.txt` or equivalent run-context files;
- passive network sampler logs and summaries.

Use existing low-volume metrics, reconnect lifecycle logs, `ReconnectMapMetrics` output, and VirtualMap path-range/state
information. Do not require production telemetry changes for this extraction path.

## Learner Summary

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
- learner state size at reconnect start, inferred from existing `firstLeafPath` / `lastLeafPath` ranges when available;
- learner state size at reconnect end, inferred from existing `firstLeafPath` / `lastLeafPath` ranges when available;
- reconnect status.

Derived values do not need to be extracted as separate fields if raw counters exist:

```text
internalDirtyHashes = internalHashes - internalCleanHashes
internalDirtyData = internalData - internalCleanData
leafDirtyHashes = leafHashes - leafCleanHashes
leafDirtyData = leafData - leafCleanData
```

## Teacher Context

For the matching teacher reconnect, extract:

- traversal mode from script output or configuration;
- teacher node ID;
- learner peer node ID;
- teacher reconnect start UTC;
- teacher reconnect end UTC when available;
- teacher state size at reconnect start, inferred from existing `firstLeafPath` / `lastLeafPath` ranges when available;
- teacher state size at reconnect end when available, or an explicit unavailable note;
- reconnect status.

Teacher duration is not a primary calibration target. It is useful for matching the teacher-side log window to the
learner-side reconnect, but learner reconnect completion is the timing we optimize because the learner can continue
processing transactions after it reconnects.

Exact reconnect byte totals are not required. Network environment shape is the important evidence: RTT,
throughput/bandwidth evidence, and TCP/window/backpressure evidence. Reconnect transfer counts and clean/dirty counters
remain the work-shape evidence.

## Run Context

For each run, extract or infer:

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
transactionRate=<rate if controlled by script or inferred from logs>
transactionMix=<short description if controlled by script or inferred from NLG logs>
configSummary=<path or short config identifier>
```

If a field is not present, record it as missing instead of inferring beyond the artifact evidence.

## State And Divergence Strategy

Do not require exact per-key divergence in the first cluster processing pass.

Infer divergence from coarse run facts:

- teacher and learner state size near reconnect start;
- state size gap between learner and teacher;
- how long the learner was behind;
- transaction/load profile while the learner was behind;
- teacher growth while the reconnect was running;
- reconnect clean/dirty counters from `ReconnectMapMetrics`;
- service/store size metrics if already available.

For local `ReconnectBench`, classify the cluster shape as approximately:

- append/growth-heavy;
- modify-heavy;
- remove-heavy;
- mixed.

The first local approximation should use the starting learner/teacher gap. If the teacher continues growing while
reconnect runs, record that as cluster behavior to validate later, not as a reason to block the first calibration pass.

## Network Evidence

The local benchmark has explicit network settings:

```text
networkLatencyMicroseconds
networkBandwidthMegabitsPerSecond
networkInflightBytesLimit
```

The cluster artifacts therefore need corresponding network evidence from the same test environment.

Extract:

1. RTT between learner and teacher.
   - Prefer direct pod-to-pod or node-to-node RTT around the reconnect scenario.
   - Existing cluster `ping_us_*` metrics are acceptable if direct RTT is not available.
2. Directional bandwidth or throughput evidence.
   - Prefer existing cluster, pod, or node network metrics for the test environment.
   - Reconnect MiB divided by synchronization seconds is an observed lower-bound reconnect receive rate, not link
     capacity.
3. TCP/window/backpressure evidence during reconnect.
   - Use passive sampler output such as `ss -tin` for the actual reconnect window when available.
   - This is the best evidence for whether local `networkInflightBytesLimit` should be neutral or constrained.
   - If the artifacts cannot provide TCP/window samples, record that as a calibration gap.
4. Full node metrics for the run.
   - Metrics help cross-check peer traffic, reconnect counters, and network shape.

Do not use active bandwidth generators as reconnect-window evidence. They can change the thing we are trying to measure.

## Analysis Output Per Mode

Fill one block per accepted traversal run:

```text
Traversal mode:
Artifact directory:
Commit:
Image:
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

Use this section only if required evidence is missing or local/cluster results cannot be explained.

Possible fallback artifacts:

- JVM version, heap flags, and command line;
- Kubernetes pod placement;
- pod and node descriptions;
- `/proc/net/dev` or `ip -s link` before/after samples;
- config file copies for reconnect, virtual map, MerkleDB, socket, and JVM settings.
