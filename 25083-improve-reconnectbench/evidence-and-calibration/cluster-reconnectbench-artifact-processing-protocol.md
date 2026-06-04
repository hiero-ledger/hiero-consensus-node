# Cluster ReconnectBench Artifact Processing Protocol

Updated: `2026-06-04`

## Purpose

This document defines the workflow-ordered artifact extraction protocol for manifest-listed cluster ReconnectBench
calibration runs.

Select raw artifact batches and traversal run roots from
`cluster-reconnectbench-artifact-manifest.md`. The manifest is the source of truth for concrete artifact roots. Do not
duplicate the manifest's raw-root index in this protocol.

The goal is to process each manifest batch into comparable per-mode evidence, then use completed learner catch-up
episodes to decide whether local `ReconnectBench` can reproduce the same traversal-mode ordering and broad reconnect
shape observed in the cluster.

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
- learner catch-up episode and every learner receiver reconnect iteration in that episode;
- complete catch-up duration, from the first learner receiver reconnect start to the final learner receiver reconnect
  finish before confirmed learner `ACTIVE`;
- per-iteration reconnect duration;
- per-iteration reconnect transfer counters and clean/dirty work shape;
- coarse state size and divergence shape;
- enough network evidence to choose local latency, bandwidth, and in-flight assumptions.

Everything else is fallback diagnostics.

## Manifest And Batch Context

Keep concrete batch facts in `cluster-reconnectbench-artifact-manifest.md`. Treat manifest batch facts as expected run
context to verify from artifacts, not as substitutes for parsing logs, settings, metrics, and script output.

Batch entries may record intended traversal modes, intended learner identity, workflow shape, NLG/load controls, and
known collection notes. Extraction must still validate the observed mode, learner, teacher, workload, reconnect windows,
and status transitions from raw artifacts.

## Source And Atlas Contract

This protocol defines extraction order, evidence requirements, interpretation rules, and acceptance criteria. Exact
per-run paths come from `cluster-reconnectbench-artifact-manifest.md`. Detailed file patterns, log patterns, and full
metric inventories belong in `cluster-reconnectbench-artifact-atlas.md`.

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

Process one manifest batch at a time, then one traversal run entry at a time:

0. Resolve the batch and traversal run root from `cluster-reconnectbench-artifact-manifest.md`.
1. Run the network disease preflight.
2. Identify run context.
3. Build the learner status and reconnect timeline.
4. Identify learner catch-up episode(s).
5. Extract each learner receiver reconnect iteration in the main catch-up episode.
6. Match teacher context for each iteration.
7. Extract per-iteration reconnect work-shape counters.
8. Extract network evidence, workload evidence, state evidence, and divergence context for each iteration where
   available.
9. Produce per-iteration rows, one episode summary, and one run acceptance row.

If the network disease preflight finds fatal symptoms, stop normal calibration extraction after recording the preflight
findings and a minimal analysis row. Do not process reconnect timing, counters, network evidence, workload evidence,
state evidence, reconnect iterations, complete catch-up duration, or calibration inputs as calibration evidence from that
artifact. A fatal preflight means the traversal run should be re-run.

Diagnostic-only episode rows may be retained for already-extracted artifacts, or added only when needed to explain an
exclusion decision. Such rows must be clearly marked diagnostic-only and must not feed calibration inputs, traversal
ordering, or complete catch-up trend/ranking.

## Network Disease Preflight

Before normal extraction, scan every node `swirlds.log` in the traversal artifact for cluster-wide network disease.

Fatal disease requires both symptom families:

- post-startup platform instability:
  - `StatusStateMachine: Platform spent ... in ACTIVE. Now in CHECKING`
  - or JSON payload `{"oldStatus":"ACTIVE","newStatus":"CHECKING"}`
- shadowgraph missing-parent evidence:
  - `Shadowgraph: Missing non-expired other parent`

Do not count normal startup transitions such as `OBSERVING -> CHECKING -> ACTIVE`. Do not reject on
`CHECKING -> ACTIVE` by itself. Do not reject on missing-parent evidence by itself, because missing-parent lines can also
appear in artifacts that do not show `ACTIVE -> CHECKING` churn.

For each traversal artifact, record:

- whether the preflight was run;
- whether fatal disease was found;
- which node logs contain `ACTIVE -> CHECKING`;
- which node logs contain `Missing non-expired other parent`;
- compact counts and first example source references for each symptom family.

If fatal disease is found, set `Run accepted for calibration: no` and `Reason if not accepted:
NETWORK_DISEASE_FATAL`. Keep any already extracted values diagnostic-only and exclude them from calibration summaries,
ordering claims, and local `ReconnectBench` parameter selection.

Do not use fatal-disease artifacts for complete catch-up trends, traversal ordering, or local `ReconnectBench` parameter
selection. Previously extracted iteration values from such artifacts are diagnostic-only.

## Run Context

Extract or infer the following fields for each traversal run:

```text
commit=<git SHA>
mode=<virtualMap.reconnectMode>
clusterNamespace=<namespace if captured>
networkSize=<node count>
stoppedPod=<stopped learner pod if captured>
workloadProfile=<short name or description>
NLGArguments=<NLG arguments if captured>
warmtime=<warmtime if controlled by script>
downtime=<downtime if controlled by script>
loopCount=<NofLoops value if controlled by script>
transactionRate=<actual reconnect-window rate if controlled by script or inferred from logs>
transactionMix=<short description if controlled by script or inferred from NLG logs>
configSummary=<path or short config identifier>
```

## Reconnect Episodes And Iterations

Identify the learner reconnect timeline before extracting counters or comparing timings.

A learner catch-up episode is a same-learner sequence that:

- starts at the first learner receiver reconnect start payload;
- contains every learner receiver reconnect start/finish pair before the learner reaches `ACTIVE`;
- ends at the last learner receiver reconnect finish payload before that `ACTIVE`;
- is complete only when a later learner `ACTIVE` status is observed.

If the learner reaches `ACTIVE` and later falls behind again in the same artifact, treat the later fall-behind as a new
episode rather than another iteration in the first episode.

For each reconnect iteration inside the episode, extract the minimum fields needed to anchor the reconnect:

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

Complete catch-up duration is the episode-level trend target:

```text
completeCatchUpDuration = finalLearnerReceiverReconnectFinish - firstLearnerReceiverReconnectStart
```

Use the subsequent learner `ACTIVE` transition only as completion confirmation. Do not include the
finish-to-`ACTIVE` interval in `completeCatchUpDuration`; record it separately as post-reconnect status/stage context.

## Learner Evidence

After each accepted learner reconnect iteration is anchored, add learner-specific evidence:

- learner state size at reconnect start, inferred from existing `firstLeafPath` / `lastLeafPath` ranges when available;
- learner state size at reconnect end, inferred from existing `firstLeafPath` / `lastLeafPath` ranges when available;
- learner-side stage timing when present, such as `Finished synchronization`.

The learner side is also the primary source for `ReconnectMapMetrics` work-shape counters.

## Teacher Evidence

After each matching teacher reconnect is anchored, add teacher-specific evidence:

- teacher sent state size, inferred from existing `firstLeafPath` / `lastLeafPath` ranges when available;
- sampled teacher state-size growth during the reconnect window when stats coverage is available.

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

Extract workload evidence around the time the learner was behind and during each reconnect iteration:

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

- learner state size near each reconnect iteration start;
- teacher state size near each reconnect iteration start;
- learner/teacher state gap;
- transaction/load profile during each reconnect iteration;
- teacher growth around each reconnect iteration, if visible;
- reconnect clean/dirty counters from `ReconnectMapMetrics`;
- service/store size metrics if already available.

For local `ReconnectBench`, classify the cluster shape as approximately:

- append/growth-heavy;
- modify-heavy;
- remove-heavy;
- mixed.

The first local approximation should use the starting learner/teacher gap for iteration 1. If the teacher continues
growing while reconnect runs, record that as cluster behavior and use later iterations to understand whether the learner
needed additional catch-up work.

## Episode Completion And Later Episodes

After the first accepted learner reconnect window is identified, scan for additional learner reconnect starts before the
learner reaches `ACTIVE`.

Record:

- every reconnect iteration in the same catch-up episode;
- whether a subsequent learner `ACTIVE` status confirms episode completion;
- whether a later fall-behind after `ACTIVE` starts a new episode;
- whether any episode is incomplete due to missing learner `ACTIVE` confirmation.

Do not blend separate episodes together. Do include all reconnect iterations in the same episode when computing complete
catch-up duration.

## Analysis Output Per Mode

Fill one block per traversal run, plus iteration details for each learner receiver reconnect iteration:

```text
Traversal mode:
Manifest batch ID:
Manifest run ID:
Commit:
Network disease preflight:
Network disease reason if failed:
Learner node:
Episode complete: yes/no
Episode incomplete reason:
Iteration count:
Complete catch-up start UTC:
Complete catch-up end UTC:
Complete catch-up duration:
Active confirmation UTC:
First iteration teacher node:
First iteration start UTC:
First iteration end UTC:
First iteration duration:
Teacher reconnect context present: yes/no
Reconnect stats present: yes/no
Teacher/learner state size present: yes/no
Workload profile present: yes/no
RTT evidence present: yes/no
Bandwidth evidence present: yes/no
TCP/window evidence present: yes/no
Additional iterations observed: yes/no
Run accepted for calibration: yes/no
Reason if not accepted:
```

For backward compatibility with existing first-window summaries, keep first-iteration duration and first-iteration
work-shape fields. Future trend/ranking must use complete catch-up duration for completed, accepted episodes.

## Acceptance Criteria

A traversal run is accepted for calibration when it has:

- no fatal network disease preflight result;
- confirmed traversal mode;
- confirmed learner node;
- at least one learner receiver reconnect iteration with start and end times;
- learner `ACTIVE` confirmation after the final reconnect iteration;
- complete catch-up duration;
- reconnect transfer and clean/dirty counters from existing learner-side reconnect metrics/logs for each iteration used in
  the episode summary;
- enough state/workload context to classify divergence at a coarse level;
- RTT, bandwidth, and TCP/window evidence sufficient to map the cluster network to local benchmark settings;
- clear note if multiple reconnect iterations occurred in the episode.

A run is still useful but incomplete if it has duration only. Duration without reconnect counters and coarse divergence
context cannot validate whether local `ReconnectBench` is modeling the same work shape. Duration without network
evidence also cannot calibrate the local network simulator. A run with reconnect finish payloads but no later learner
`ACTIVE` confirmation has an incomplete episode and must be excluded from complete catch-up trend/ranking.

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
