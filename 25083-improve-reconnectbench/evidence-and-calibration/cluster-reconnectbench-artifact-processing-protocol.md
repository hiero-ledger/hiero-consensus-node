# Cluster ReconnectBench Artifact Processing Protocol

Updated: `2026-06-01`

## Purpose

This document defines the workflow-ordered artifact extraction protocol for the collected cluster ReconnectBench
calibration runs.

The cluster run planning phase is complete. The traversal-order artifacts have been collected under:

```text
/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs
```

The goal is to process those artifacts into comparable per-mode evidence, then use that evidence to decide whether local
`ReconnectBench` can reproduce the same traversal-mode ordering and broad reconnect shape observed in the cluster.

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

Keep these facts with the extraction protocol so the collected artifacts are interpreted in the context in which they
were produced. Treat them as expected run context to verify from artifacts, not as substitutes for parsing logs, settings,
metrics, and script output.

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
  - `NofLoops=0`, chosen for one reconnect iteration with the script semantics used for this run.
- The intended NLG state/load shape used `24M` NLG accounts and the default `8K` TPS cap. This was chosen to target
  roughly `100M` Virtual Map records on the learner and about `10M` additional records of teacher/learner divergence.
- Load was not removed before the learner restarted; validate the actual workload rate and timing from NLG/client logs.
- Passive TCP/socket/network evidence was collected around the reconnect window, from learner restart through learner
  `ACTIVE`. Do not depend on old draft script details; DevOps debugged and changed the actual implementation.
- Production reconnect telemetry changes were not part of this pass.

## Source And Atlas Contract

This protocol defines extraction order, evidence requirements, interpretation rules, and acceptance criteria. Exact
per-run paths, detailed file patterns, log patterns, and full metric inventories belong in
`cluster-reconnectbench-artifact-atlas.md`.

Expected artifact families are:

- ordinary script output;
- `client.log` and NLG output;
- node `swirlds.log` files;
- node metrics and stats CSVs;
- `settingsUsed.txt`, `config.txt`, and copied configuration files;
- `version_run.txt` or equivalent run-context files;
- passive network sampler logs and summaries.

Use existing low-volume metrics, reconnect lifecycle logs, `ReconnectMapMetrics` output, and VirtualMap path-range/state
information. Do not require production telemetry changes for this extraction path.

If an expected field is not present in the artifacts, record it as missing instead of inferring beyond evidence.

## Processing Sequence

Process one traversal-order artifact directory at a time:

1. Identify run context.
2. Identify the first learner reconnect window.
3. Match the teacher context for the same reconnect.
4. Extract reconnect work-shape counters.
5. Extract network evidence.
6. Extract workload evidence.
7. Extract state and divergence context.
8. Record later reconnects.
9. Produce one analysis row and mark whether the run is accepted for calibration.

Do not mix later reconnects into the first traversal-mode timing result. If a learner immediately enters a second
reconnect, keep that later reconnect as state-growth context.

## Run Context

Extract or infer the following fields for each traversal run:

```text
commit=<git SHA>
image=<image tag or digest>
mode=<virtualMap.reconnectMode>
baseline=<baseline restore identifier>
clusterNamespace=<namespace if captured>
networkSize=<node count>
learnerCandidate=<expected learner node id if known>
stoppedPod=<stopped learner pod if captured>
teacherCandidate=<expected teacher node id if known>
workloadProfile=<short name or description>
NLGArguments=<NLG arguments if captured>
learnerBehindDuration=<duration if controlled by script>
warmtime=<warmtime if controlled by script>
downtime=<downtime if controlled by script>
loopCount=<NofLoops value if controlled by script>
transactionRate=<rate if controlled by script or inferred from logs>
transactionMix=<short description if controlled by script or inferred from NLG logs>
configSummary=<path or short config identifier>
```

## Reconnect Window And Roles

Identify the first learner reconnect window before extracting counters or comparing timings.

First extract the minimum fields needed to anchor the reconnect:

- learner node ID;
- teacher peer node ID;
- matching teacher node ID;
- learner peer node ID from the matching teacher log;
- learner reconnect start UTC;
- learner reconnect end UTC;
- learner reconnect duration in milliseconds;
- learner reconnect status;
- matching teacher reconnect start UTC;
- matching teacher reconnect end UTC when available;
- matching teacher reconnect status when available.

Use receiver lifecycle start-to-finish as the primary learner reconnect duration. If logs also contain a narrower
`Finished synchronization` timing, keep it as stage context instead of replacing the full learner duration.

Teacher duration is not a primary calibration target. It is useful for matching the teacher-side log window to the
learner-side reconnect, but learner reconnect completion is the timing to optimize because the learner can continue
processing transactions after it reconnects.

## Learner Evidence

After the first accepted learner reconnect is anchored, add learner-specific evidence:

- learner state size at reconnect start, inferred from existing `firstLeafPath` / `lastLeafPath` ranges when available;
- learner state size at reconnect end, inferred from existing `firstLeafPath` / `lastLeafPath` ranges when available;
- learner-side stage timing when present, such as `Finished synchronization`.

The learner side is also the primary source for `ReconnectMapMetrics` work-shape counters.

## Teacher Evidence

After the matching teacher reconnect is anchored, add teacher-specific evidence:

- teacher state size at reconnect start, inferred from existing `firstLeafPath` / `lastLeafPath` ranges when available;
- teacher state size at reconnect end when available, or an explicit unavailable note.

Exact reconnect byte totals are not required. Network environment shape is the important evidence: RTT,
throughput/bandwidth evidence, and TCP/window/backpressure evidence.

## Reconnect Work-Shape Counters

From existing learner-side reconnect metrics or logs, extract the raw `ReconnectMapMetrics` counters:

- transfers from teacher;
- transfers from learner;
- internal hashes;
- internal clean hashes;
- internal data;
- internal clean data;
- leaf hashes;
- leaf clean hashes;
- leaf data;
- leaf clean data.

Reconnect transfer counts and clean/dirty counters are the work-shape evidence. Derived dirty counts do not need
separate extraction if the raw counters exist:

```text
internalDirtyHashes = internalHashes - internalCleanHashes
internalDirtyData = internalData - internalCleanData
leafDirtyHashes = leafHashes - leafCleanHashes
leafDirtyData = leafData - leafCleanData
```

## Network Evidence

The local benchmark has explicit network settings:

```text
networkLatencyMicroseconds
networkBandwidthMegabitsPerSecond
networkInflightBytesLimit
```

Extract the corresponding cluster evidence from the same test environment:

1. RTT between learner and teacher.
   - Prefer direct pod-to-pod or node-to-node RTT around the reconnect scenario.
   - Existing cluster `ping_us_*` metrics are acceptable if direct RTT is not available.
2. Directional bandwidth or throughput evidence.
   - Prefer existing cluster, pod, or node network metrics, or interface counters for the test environment.
   - Reconnect MiB divided by synchronization seconds is an observed lower-bound reconnect receive rate, not link
     capacity.
3. TCP/window/backpressure evidence during reconnect.
   - Use passive sampler output such as `ss -tin` for the actual reconnect window when available.
   - This is the best evidence for whether local `networkInflightBytesLimit` should be neutral or constrained.
   - If the artifacts cannot provide TCP/window samples, record that as a calibration gap.
4. Full node metrics for the run.
   - Metrics help cross-check peer traffic, reconnect counters, and network shape.

Do not use active bandwidth generators as reconnect-window evidence. They can change the thing being measured.

## Workload Evidence

Extract workload evidence around the time the learner was behind and during the reconnect window:

- workload profile;
- NLG arguments;
- actual transaction rate;
- transaction mix;
- whether load continued while the learner was behind and reconnecting.

Use the workload evidence to help classify state divergence and to avoid treating intended run shape as observed run
shape.

## State And Divergence Evidence

Do not require exact per-key divergence in the first cluster processing pass.

Infer divergence from coarse run facts:

- learner state size near reconnect start;
- teacher state size near reconnect start;
- learner/teacher state gap;
- how long the learner was behind;
- transaction/load profile while the learner was behind;
- teacher growth while the learner is behind and while reconnect runs, if visible;
- reconnect clean/dirty counters from `ReconnectMapMetrics`;
- service/store size metrics if already available.

For local `ReconnectBench`, classify the cluster shape as approximately:

- append/growth-heavy;
- modify-heavy;
- remove-heavy;
- mixed.

The first local approximation should use the starting learner/teacher gap. If the teacher continues growing while
reconnect runs, record that as cluster behavior to validate later, not as a reason to block the first calibration pass.

## Later Reconnects

After the first accepted learner reconnect window is identified, scan for additional learner reconnect starts.

Record:

- whether another learner reconnect starts after the first accepted window;
- whether the later reconnect should be excluded from traversal-mode timing;
- whether the later reconnect is useful as state-growth context.

Do not blend a later reconnect into the first traversal-mode timing result.

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

Use fallback diagnostics only if required evidence is missing or local/cluster results cannot be explained.

Possible fallback artifacts:

- JVM version, heap flags, and command line;
- Kubernetes pod placement;
- pod and node descriptions;
- `/proc/net/dev` or `ip -s link` before/after samples;
- config file copies for reconnect, virtual map, MerkleDB, socket, and JVM settings.
