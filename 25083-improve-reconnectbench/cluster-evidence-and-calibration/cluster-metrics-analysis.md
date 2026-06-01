# Cluster Metrics Analysis For ReconnectBench Calibration

This document captures the current calibration decisions, evidence, and remaining gaps for using cluster metrics to choose better local `ReconnectBench` parameters.

Source files were based on reconnect run that was done on May 6, 2026 (they were deleted after info was extracted).

The detailed metric sections are still maintained incrementally. Analyze additional CSV metrics only when they are
requested, and keep the decision matrix below synchronized with any new evidence.

## Executive Summary

Current confirmed cluster shape:

- Main reconnect pair: node 0 is the receiver/learner; node 2 is the sender/teacher.
- Main reconnect window: approximately `2026-05-05 20:11:48-20:22:23 UTC`.
- Metric-reported duration:
  - node 0 receiver/learner: `630s`
  - node 2 sender/teacher: `621s`
- Reconnect request rejections are not part of this sample: all `reconnectRejections_per_sec_XX` metrics are zero.
- Cluster RTT evidence is low-latency/private-network shaped, not WAN shaped:
  - fresh `ping_us_*` evidence is roughly `100-300 us` RTT;
  - stale first-window peer averages give a conservative upper hint around `600 us`;
  - `100 ms` and `200 ms` local runs are still useful WAN sensitivity tests, but they do not match this cluster sample.
- Observed application send rates during the confirmed reconnect are asymmetric:
  - learner-to-teacher, node 0 -> node 2: average `29.18 MB/s` (`~233 Mbps`), max `33.75 MB/s` (`~270 Mbps`)
  - teacher-to-learner, node 2 -> node 0: average `13.46 MB/s` (`~108 Mbps`), max `24.05 MB/s` (`~192 Mbps`)
- In-flight/window behavior is still not directly measured. Given sub-ms RTT, bandwidth-delay product is tiny compared
  to MiB-scale caps, so local cluster-profile runs should use a large/neutral cap unless TCP evidence says otherwise.
- State scale is much larger than a single `50M` local profile:
  - account-map/service-store scale: about `100M` total records;
  - total virtual-map scale from `vmap_size_state`: about `415M-418M` records;
  - active-node MerkleDB data-source file footprint: about `89k-90k MiB`, or roughly `87-88 GiB`.
- Divergence in the confirmed node0/node2 reconnect appears token-association-heavy and append/growth driven:
  - starting node0/node2 virtual-map gap: about `2.21M` records;
  - active teacher-side growth during the first reconnect: about `636k` records;
  - node0 receives a state close to the teacher's start-of-window state, then immediately needs a second reconnect for
    the state that accumulated while the first reconnect was running.
- Storage/finalization effects are visible and should be tracked separately from traversal/network:
  - node0 has heavy `vmap_lifecycle_flushCount_state` growth during reconnect;
  - node0 state-write samples appear after the first receiver reconnect completes;
  - `receiverReconnectDurationSeconds` includes learner-side virtual-map finalization, but not later controller/platform
    load or state-to-disk snapshot work.

Working conclusion:

- The current CSV evidence is enough to propose a better local cluster-profile sweep, but not enough to declare that the
  local benchmark fully reproduces cluster behavior.
- Before asking for another cluster experiment, it is reasonable to run a small local cluster-profile check using the
  existing saved state and sub-ms latency.
- Before treating local/cluster traversal ordering as validated, we still need cluster runs with explicit traversal mode,
  reconnect stats/logs, and TCP/window or socket evidence.

## Current Context

- Cluster shape: 7 consensus nodes running in Kubernetes via Solo.
- Hardware target: close to mainnet-class hardware.
- Current local benchmark status: no confirmed hard implementation bug in the ReconnectBench MVP has been identified.
  The main remaining gap is calibration against real cluster shape.
- Latest local diagnostic runs suggest traversal ordering is strongly affected by RTT. The in-flight/window cap can
  distort results when undersized, but it is not the only driver of traversal-mode crossover.
- `networkInflightBytesLimit` is not a cloud/provider setting. It is a local benchmark proxy for effective outstanding
  bytes before TCP/window/socket/receiver/application backpressure appears.

## Goal

Use cluster metrics to derive or constrain local `ReconnectBench` configuration:

- `networkLatencyMicroseconds`
- `networkBandwidthMegabitsPerSecond`
- `networkInflightBytesLimit`
- tree/state size
- divergence shape
- traversal modes to compare
- whether local runs should be network-bound, traversal-bound, storage-bound, or mixed

The end goal is not just to match cluster wall-clock time. The goal is to reproduce enough of the cluster's reconnect
shape that local traversal-mode ordering becomes meaningful.

## Data We Ideally Need

### Must Have

1. Same-state traversal matrix
   - Run the same teacher/learner saved state with:
     - `pullTopToBottom`
     - `pullParallelSync`
     - `pullTwoPhasePessimistic`, if feasible
   - Prefer alternating run order such as `top -> parallel -> top` or `parallel -> top -> parallel` to expose cache and
     ordering effects.

2. Reconnect summary per run
   - Wall-clock reconnect duration.
   - Teacher-to-learner bytes.
   - Learner-to-teacher bytes.
   - Transfers from teacher.
   - Transfers from learner.
   - Internal/leaf clean hash/data counts.
   - Dirty counts, or enough raw stats to derive clean/dirty ratio.

3. Network measurements between the actual learner and teacher
   - RTT: average, p50, p95, and p99 if available.
   - Sustained TCP throughput teacher-to-learner.
   - Sustained TCP throughput learner-to-teacher.
   - Whether nodes are on the same Kubernetes node, same rack, same region, same provider private network, or public
     internet.

4. State/divergence description
   - Teacher leaf count and learner leaf count.
   - Approximate added/removed/modified leaves, if known.
   - How the learner fell behind: number of rounds, elapsed time, and transaction mix.
   - Whether keys are mostly appended, uniformly modified, or follow another pattern.

### Very Useful

5. Runtime environment
   - Commit SHA.
   - JVM version and heap arguments.
   - CPU/RAM/disk type for both learner and teacher.
   - Reconnect-related config and relevant `virtualMap.*`, `merkleDb.*`, `socket.*`, and `reconnect.*` config.

6. TCP/window evidence during reconnect
   - `ss -ti` samples for the reconnect connection during the run, if available.
   - Key signal: effective send/receive window and whether the connection is window-limited.
   - This maps most directly to local `networkInflightBytesLimit`.

7. Stage timing logs
   - Existing reconnect logs around traversal, receive, hashing, flushing, finalize, and load.
   - Used to decide whether local differs because of traversal/network behavior or because learner flush/storage
     dominates on cluster.

## Existing Production Signals Expected To Help

Production reconnect already logs some useful data on the learner side:

- reconnect start/finish payloads identify learner and teacher IDs;
- synchronization duration is logged;
- reconnect data usage reports learner received bytes;
- `ReconnectMapMetrics.format()` logs:
  - `transfersFromTeacher`
  - `transfersFromLearner`
  - `internalHashes`
  - `internalCleanHashes`
  - `internalData`
  - `internalCleanData`
  - `leafHashes`
  - `leafCleanHashes`
  - `leafData`
  - `leafCleanData`

If these are present in cluster logs, production-code changes may not be needed for reconnect summary counters.

Potential gaps:

- learner-to-teacher bytes;
- precise teacher-to-learner bytes if only learner received mebibytes are logged;
- TCP/window evidence;
- exact divergence shape;
- detailed stage timing if logs are too coarse.

## Analysis Method

For each requested metric, add an entry using this template:

```text
Metric:
Category: network | reconnect | state
Source files:
Nodes present:
Time range:
Reconnect window:
Observed shape:
Per-node differences:
Candidate learner/teacher signal:
Implication for local ReconnectBench:
Recommended local parameter impact:
Confidence: high | medium | low
Open questions:
```

Use screenshots/PDFs only for visual confirmation. Use CSVs for numeric conclusions.

## Metric Categories

### Network Metrics

Purpose:

- Estimate RTT/throughput shape where possible.
- Identify network bursts, drops, asymmetric behavior, and candidate reconnect windows.
- Help choose `networkBandwidthMegabitsPerSecond` and constrain `networkInflightBytesLimit`.

Important caveat:

- Application byte-rate metrics do not equal available link bandwidth. They show observed application/network activity,
  not necessarily maximum throughput.

### Reconnect Metrics

Purpose:

- Identify exact reconnect windows.
- Determine teacher/learner roles.
- Compare traversal work shape against local benchmark stats.
- Extract duration, transfer counts, clean/dirty ratios, and reconnect byte counts.

### State Metrics

Purpose:

- Infer teacher/learner state size.
- Estimate divergence shape.
- Identify whether cluster reconnect represents append-heavy, modify-heavy, remove-heavy, or mixed divergence.

## Calibration Decision Matrix

These are calibration decisions, not final validation claims. `Use now` means the value is justified for a quick local
cluster-profile diagnostic. `Diagnostic only` means useful for sensitivity testing but not evidence-backed for this
cluster. `Blocked` means cluster evidence is required before treating the value as validated.

| Benchmark input | Status | Decision | Evidence anchor | Confidence / blocker |
| --- | --- | --- | --- | --- |
| `networkLatencyMicroseconds` | Use now | Current implementation applies one-way latency. Use `50`, `150`, and `300` to target roughly `100`, `300`, and `600 us` RTT. Do not use `50_000` or `100_000` for cluster calibration. | `ping_us_*` and aggregate `ping` | Medium; gossip RTT is not direct reconnect socket RTT. |
| `networkBandwidthMegabitsPerSecond` | Use now | Sweep `150`, `200`, `300`, and `1000`. Treat `150-300` as observed-throughput diagnostics and `1000` as nominal-link. Local simulation is symmetric, while observed cluster send rates are directional and asymmetric. | `bytes_per_sec_sent_*` | Medium; observed send rates are not link capacity. Sustained directional TCP throughput is still missing. |
| `networkInflightBytesLimit` | Use now | Use `134217728` bytes (`128 MiB`) or larger as a neutral cap for cluster-profile runs. Accept it as neutral only if local stats show negligible capacity waits and `maxInflightBytes` stays comfortably below the cap. | `ping_us_*`, `bytes_per_sec_sent_*` | Low for actual cluster window behavior; TCP/window samples are missing. |
| Small in-flight caps | Diagnostic only | Keep `13 MiB` and `25 MiB` only for sensitivity checks. | BDP calculation in `ping_us_*` | Blocked as a cluster model until `ss -ti` or equivalent window evidence exists. |
| State size | Use now for diagnostics | Reuse existing saved state for quick network-profile diagnostics. For new state-size profiles, target `totalRecords = numFiles * numRecords`, not `numRecords` alone. With current `numFiles=5000`, `100M` total records means `numRecords=20000`, and `400M` total records means `numRecords=80000`. | service-store size, `vmap_*`, `ds_files_*` | Medium; current benchmark is one synthetic virtual map, while cluster state has multiple service stores. |
| Divergence shape | Partial | Static first-pass approximation: token-association-heavy append gap. For the first confirmed reconnect, `teacherAddProbability ~= 2.21M / 415.3M ~= 0.0053`, `teacherRemoveProbability=0`, and `teacherModifyProbability` remains diagnostic until clean/dirty logs exist. | service-store size, `vmap_*` | Medium; current `ReconnectBench` cannot model teacher growth while reconnect is running. |
| Traversal matrix | Use locally, blocked for cluster validation | Local diagnostic: same saved teacher/learner state with `pullTopToBottom -> pullParallelSync -> pullTopToBottom`; optionally reverse the order and include `pullTwoPhasePessimistic` if cost is acceptable. | reconnect role/duration metrics | Cluster validation is blocked until explicit cluster traversal-mode runs exist. |

## Evidence Coverage

Status legend:

- `Satisfied`: enough evidence for current calibration use.
- `Partial`: useful signal exists, but at least one interpretation gap remains.
- `Missing`: no usable evidence in the current CSV/log set.
- `Blocked`: required before claiming local/cluster validation.

| Evidence | Status | Anchor | What it supports | Remaining gap |
| --- | --- | --- | --- | --- |
| Teacher/learner identity | Satisfied | `startsReconnectAsReceiver`, `startsReconnectAsSender`, `bytes_per_sec_sent_*` | node 0 learner, node 2 teacher | None for the first confirmed window. |
| Reconnect duration | Satisfied | `receiverReconnectDurationSeconds`, `senderReconnectDurationSeconds` | receiver `630s`, sender `621s` | Stage breakdown still missing. |
| Reconnect transfer counters | Missing | `ReconnectMapMetrics` class, CSV header search | No CSV-backed transfer/clean-dirty counter values | Need reconnect logs with `ReconnectMapMetrics.format()` or metrics export including `reconnect_vmap`. |
| Reconnect bytes | Partial | `bytes_per_sec_sent_*` | observed directional application send rates | Exact reconnect byte totals are missing. |
| RTT | Partial | `ping_us_*`, aggregate `ping` | sub-ms cluster-private latency profile | Direct reconnect socket RTT missing. |
| Sustained directional TCP throughput | Missing | none | link-capacity validation for bandwidth setting | Need `iperf3` or equivalent node0<->node2 tests in both directions. |
| TCP/window/in-flight behavior | Blocked | none | no validated cluster `networkInflightBytesLimit` | Need `ss -ti` or equivalent during reconnect. |
| Service-store size/divergence | Partial | service-store size section, `vmap_*` | token-association-heavy append/growth gap | Clean/dirty modify/remove shape missing. |
| Total virtual-map scale | Partial | `vmap_size_state` | `415M-418M` total virtual-map records | Synthetic single-map translation remains approximate. |
| Disk/storage footprint | Partial | `ds_files_*`, `Diskspace*` | disk budget and storage-churn sanity checks | Stage timing and exact storage bottleneck attribution are missing. |
| Same-state traversal matrix on cluster | Blocked | none | required for traversal-order validation | Need explicit cluster runs per mode. |
| Runtime environment | Partial | operator-provided context | Kubernetes/Solo and mainnet-like hardware | Need exact commit SHA, JVM/heap args, and config. |

### Quick Local Diagnostics

These can be run before requesting another cluster experiment. They are intended to test whether the current local
benchmark behaves plausibly under the best-supported cluster-profile inputs.

1. Run the existing saved teacher/learner state with one-way latency profiles `50`, `150`, and `300` microseconds.
2. Sweep `150`, `200`, `300`, and `1000` Mbps with a neutral `networkInflightBytesLimit` of at least `128 MiB`.
3. Repeat the same-state traversal order as `top -> parallel -> top`, and optionally `parallel -> top -> parallel`.
4. Keep `13 MiB`, `25 MiB`, `100 ms`, and `200 ms` runs labeled as sensitivity diagnostics, not cluster-calibration
   evidence.

### Cluster Validation Blockers

These are required before claiming the local benchmark reproduces cluster traversal ordering.

1. Cluster same-state traversal matrix with explicit reconnect mode in logs.
2. Learner and teacher reconnect logs containing `ReconnectMapMetrics.format()` output.
3. Metrics export that includes `reconnect_vmap`, or confirmation that logs are the only source for transfer counters.
4. TCP/window samples for the node0/node2 reconnect connection.
5. Sustained directional throughput tests between node0 and node2.
6. Exact commit SHA, JVM/heap args, and reconnect/virtualMap/MerkleDB/socket config.
7. Stage timing logs that separate synchronization, hashing, flushing, root switch, and state write/finalization.

## Detailed Findings

The detailed sections below remain metric-oriented. Use the executive summary and evidence coverage sections for current
decisions; use these findings to audit the raw reasoning behind each conclusion.

### Metrics: `startsReconnectAsReceiver`, `startsReconnectAsSender`, `endsReconnectAsReceiver`, And `endsReconnectAsSender`

Category: reconnect

Metrics covered:

- `startsReconnectAsReceiver`
- `startsReconnectAsSender`
- `endsReconnectAsReceiver`
- `endsReconnectAsSender`

Definition:

- Code source: `ReconnectMetrics`.
- `startsReconnectAsReceiver`: number of times a node starts reconnect as a receiver.
- `startsReconnectAsSender`: number of times a node starts reconnect as a sender.
- `endsReconnectAsReceiver`: number of times a node ends reconnect as a receiver.
- `endsReconnectAsSender`: number of times a node ends reconnect as a sender.
- These are counters. A transition identifies that a reconnect role started or ended on that node, but does not by
  itself identify the peer. Peer identity comes from aligned sender/receiver transitions, network peer metrics, and
  logs.

Role semantics:

- Receiver is the reconnect learner.
- Sender is the reconnect teacher.

Observed reconnect role events:

```text
Node 0:
  startsReconnectAsReceiver 0 -> 1 at 2026-05-05 20:11:50-20:11:53 UTC
  endsReconnectAsReceiver   0 -> 1 at 2026-05-05 20:22:20-20:22:23 UTC
  startsReconnectAsReceiver 1 -> 2 at 2026-05-05 20:22:35-20:22:38 UTC
  endsReconnectAsReceiver   1 -> 2 at 2026-05-05 20:28:29-20:28:32 UTC

Node 2:
  startsReconnectAsSender   0 -> 1 at 2026-05-05 17:23:57-17:24:00 UTC
  endsReconnectAsSender     0 -> 1 at 2026-05-05 17:34:21-17:34:24 UTC
  startsReconnectAsSender   1 -> 2 at 2026-05-05 17:34:45-17:34:48 UTC
  endsReconnectAsSender     1 -> 2 at 2026-05-05 17:40:15-17:40:18 UTC
  startsReconnectAsSender   2 -> 3 at 2026-05-05 20:11:48-20:11:51 UTC
  endsReconnectAsSender     2 -> 3 at 2026-05-05 20:22:12-20:22:15 UTC

Node 1:
  startsReconnectAsSender   0 -> 1 at 2026-05-05 18:47:03-18:47:06 UTC
  endsReconnectAsSender     0 -> 1 at 2026-05-05 18:58:30-18:58:33 UTC

Node 5:
  startsReconnectAsSender   0 -> 1 at 2026-05-05 18:58:54-18:58:57 UTC
  endsReconnectAsSender     0 -> 1 at 2026-05-05 19:04:57-19:05:00 UTC
```

Start-counter interpretation:

- The `20:11 UTC` reconnect has a close sender/receiver start alignment:
  - node 2 starts as sender at `2026-05-05 20:11:48-20:11:51 UTC`;
  - node 0 starts as receiver at `2026-05-05 20:11:50-20:11:53 UTC`.
- This is strong evidence that the first node0 receiver reconnect is paired with node2 as teacher.
- The few-second skew is expected from metric sampling. Do not interpret the exact counter sample order as protocol
  ordering.
- The second node0 receiver reconnect starts at `2026-05-05 20:22:35-20:22:38 UTC`, but no matching sender start appears
  in the currently analyzed node1/node2/node3/node5/node6 CSV transitions. Node4 metrics stop before this time. Treat
  that second reconnect as unpaired until logs or additional metrics identify the teacher.

Candidate learner/teacher signal:

- The main candidate window is confirmed as a reconnect between node 0 and node 2.
- Node 0 is the receiver/learner.
- Node 2 is the sender/teacher.
- The first node0/node2 reconnect window is approximately:

```text
start: 2026-05-05 20:11:48-20:11:53 UTC
end:   2026-05-05 20:22:12-20:22:23 UTC
```

This is about `10.5 minutes` from the CSV counter transitions. Use duration metrics for the exact reported duration.

Implication for local ReconnectBench:

- This confirms the teacher/learner direction needed to interpret peer network metrics:
  - teacher-to-learner traffic: node 2 -> node 0
  - learner-to-teacher traffic: node 0 -> node 2
- This direction matters because local ReconnectBench logs separate teacher-to-learner and learner-to-teacher byte
  counts.

Recommended local parameter impact:

```text
teacher node: node 2
learner node: node 0
primary reconnect window: 2026-05-05 20:11:48-20:22:23 UTC
```

Confidence: high.

### Metrics: `receiverReconnectDurationSeconds`, `senderReconnectDurationSeconds`, And `reconnectRejections_per_sec_XX`

Category: reconnect

Metrics covered:

- `receiverReconnectDurationSeconds`
- `senderReconnectDurationSeconds`
- `reconnectRejections_per_sec_XX`

Definition:

- Code sources: `ReconnectMetrics` and `ReconnectStatePeerProtocol`.
- `receiverReconnectDurationSeconds`: duration of reconnect as a receiver/learner, in seconds.
- `senderReconnectDurationSeconds`: duration of reconnect as a sender/teacher, in seconds.
- `reconnectRejections_per_sec_XX`: number of reconnect requests rejected per second by this node from peer `XX`.
- Duration metrics are updated when reconnect ends. In this CSV export they appear as one positive sample followed by
  zero, so treat the positive samples as the reported reconnect durations.
- Rejection metrics are per-second rate metrics, not cumulative counters.

Presence:

- `receiverReconnectDurationSeconds` and `senderReconnectDurationSeconds` are present in all seven node CSV files.
- `reconnectRejections_per_sec_XX` exists for every non-self peer column in all seven node CSV files.

Observed duration samples:

```text
receiverReconnectDurationSeconds:
  node 0: 630s at 2026-05-05 20:22:23 UTC
  node 0: 353s at 2026-05-05 20:28:32 UTC

senderReconnectDurationSeconds:
  node 1: 687s at 2026-05-05 18:58:33 UTC
  node 2: 623s at 2026-05-05 17:34:24 UTC
  node 2: 331s at 2026-05-05 17:40:18 UTC
  node 2: 621s at 2026-05-05 20:22:15 UTC
  node 5: 363s at 2026-05-05 19:05:00 UTC
```

Confirmed node0/node2 reconnect:

```text
node 0 receiver/learner duration: 630s at 2026-05-05 20:22:23 UTC
node 2 sender/teacher duration:   621s at 2026-05-05 20:22:15 UTC
```

Interpretation:

- The `630s` receiver duration and `621s` sender duration are the exact metric-reported durations for the confirmed
  node0/node2 reconnect.
- The `9s` difference is plausible because the teacher and learner measure different protocol boundaries and because the
  CSV samples are aligned to metric export intervals.
- Code boundary: `receiverReconnectDurationSeconds` measures the learner-side reconnect transfer/reconstruction interval
  inside `ReconnectStateLearner.reconnect()`. It starts after signature receipt and ends after virtual-map learner
  finalization and signed-state construction.
- It does not include final reconnect handshake completion, controller validation/load, platform wiring,
  reconnect-complete notification handling, or state-to-disk snapshot work. Treat state-write samples after the receiver
  duration sample as a separate post-reconnect persistence stage, while virtual-map learner finalization itself is
  already included in the receiver duration.
- The second node0 receiver reconnect reports `353s`, but no matching sender duration has been identified from the
  currently analyzed sender metrics. Keep that reconnect unpaired until logs or additional metrics identify the teacher.

Reconnect rejection findings:

- All `reconnectRejections_per_sec_XX` columns are zero in all seven CSV files.
- This means reconnect request rejection does not explain the observed node0 reconnect timing, repeated reconnect, or
  local/cluster traversal mismatch.

Implication for local ReconnectBench:

- Use `630s` as the cluster wall-clock duration for the first confirmed node0/node2 receiver reconnect.
- Use `621s` as the corresponding teacher-side duration.
- These metrics do not provide network or traversal parameters directly, but they give the cleanest cluster duration
  target for comparing local benchmark output.
- Rejection metrics are not useful for local parameter tuning in this data set because they carry no nonzero signal.

Recommended local parameter impact:

```text
cluster target duration:
  first confirmed receiver reconnect: 630s
  paired sender reconnect: 621s

networkLatencyMicroseconds: no direct value
networkBandwidthMegabitsPerSecond: no direct value
networkInflightBytesLimit: no direct value
traversal mode: no direct value
```

Confidence: high for metric presence, duration samples, and zero rejection signal.

### Metrics: `ReconnectMapMetrics` Transfer And Clean/Dirty Counters

Category: reconnect

Metric source:

- Code source: `ReconnectMapMetrics`.
- Metric category: `reconnect_vmap`.
- Expected metric names use the pattern `<counter>Total` or `<counter>_<label>_Total`, depending on whether a map label
  is present.

Counters defined by `ReconnectMapMetrics`:

```text
transfersFromTeacherTotal
transfersFromLearnerTotal
internalHashesTotal
internalCleanHashesTotal
internalDataTotal
internalCleanDataTotal
leafHashesTotal
leafCleanHashesTotal
leafDataTotal
leafCleanDataTotal
```

CSV availability:

- The current cluster CSV headers do not include `reconnect_vmap`.
- The current cluster CSV headers do not include the raw counter names, the `*Total` metric names, or label-style
  variants.
- The reconnect-related CSV columns that are present are role/duration/rejection metrics:
  - `startsReconnectAsReceiver`
  - `startsReconnectAsSender`
  - `endsReconnectAsReceiver`
  - `endsReconnectAsSender`
  - `receiverReconnectDurationSeconds`
  - `senderReconnectDurationSeconds`
  - `reconnectRejections_per_sec_XX`

Implication for local ReconnectBench:

- CSV data can support reconnect start/end/duration and rejection-rate evidence only.
- Transfer counts, internal/leaf hash/data counts, and clean/dirty ratios remain missing from the CSV data set.
- To fill this gap, use reconnect logs containing `ReconnectMapMetrics.format()` output or a metrics export that includes
  `reconnect_vmap`.

Recommended local parameter impact:

```text
teacherAddProbability: approximate from vmap/token-association size gap for now
teacherRemoveProbability: no CSV evidence beyond absence of remove-heavy signal
teacherModifyProbability: blocked on clean/dirty reconnect stats
cluster/local validation: blocked until transfer counters or logs are available
```

Confidence: high that these counters are missing from the current CSV headers.

### Metric: `hasFallenBehind`

Category: reconnect / state trigger

Related metric used for interpretation:

- `numReportFallenBehind`

Definition:

- Code source: `FallenBehindMonitor`.
- `hasFallenBehind`: boolean gauge reporting whether this node has fallen behind.
- `numReportFallenBehind`: number of peers that have reported this node as fallen behind.
- Default config source: `FallenBehindConfig`, `fallen.behind.fallenBehindThreshold = 0.50`.
- With 7 nodes, a node has 6 peers. At the default threshold, the node falls behind when more than 3 peers report it,
  so 4 reports are enough.

Reconnect semantics:

- `ReconnectStatePeerProtocol.shouldInitiate()` only initiates reconnect if `hasFallenBehind` is true and the selected
  peer is one of the peers that reported this node as behind.
- `ReconnectStatePeerProtocol.shouldAccept()` rejects teaching reconnect state if this node itself has fallen behind.

Observed transitions:

```text
Node 0:
  2026-05-05 20:11:44 UTC  hasFallenBehind false -> true, reports=6
  2026-05-05 20:11:53 UTC  startsReconnectAsReceiver 0 -> 1
  2026-05-05 20:22:23 UTC  hasFallenBehind true -> false, reports=2, endsReconnectAsReceiver 0 -> 1
  2026-05-05 20:22:26 UTC  hasFallenBehind false -> true, reports=4
  2026-05-05 20:22:38 UTC  startsReconnectAsReceiver 1 -> 2
  2026-05-05 20:28:32 UTC  hasFallenBehind true -> false, reports=0, endsReconnectAsReceiver 1 -> 2

Node 2:
  stayed hasFallenBehind=false, reports=0 while acting as sender/teacher for node 0

Nodes 1, 3, 5, and 6:
  no hasFallenBehind=true transition observed

Node 4:
  CSV data ends at 2026-05-05 13:18:14 UTC, before the 20:11 UTC reconnect window
```

Candidate learner/teacher signal:

- This independently confirms node 0 as the fallen-behind learner before the first node0/node2 reconnect.
- It also confirms node 2 was eligible to teach during that window because node 2 did not report itself as fallen
  behind.
- The first reconnect begins about `9 seconds` after node 0 first flips `hasFallenBehind=true`.

Implication for local ReconnectBench:

- This metric does not provide a direct local benchmark parameter.
- It is useful for cutting the cluster timeline into reconnect attempts. The node0/node2 reconnect is the first
  receiver reconnect after node 0 falls behind, not the entire node0 recovery sequence.
- Do not merge the first `20:11:53-20:22:23 UTC` receiver reconnect with the second `20:22:38-20:28:32 UTC` receiver
  reconnect when comparing cluster traffic or duration to one local ReconnectBench run.
- The second receiver reconnect has no matching sender transition in the currently analyzed node1/node2/node3/node5/node6
  CSV transitions, and node4 metrics are missing after `13:18:14 UTC`. Its teacher should be identified from logs or
  additional sender metrics before using it for calibration.

Recommended local parameter impact:

```text
networkLatencyMicroseconds: no direct value
networkBandwidthMegabitsPerSecond: no direct value
networkInflightBytesLimit: no direct value
cluster timeline cut: use node0/node2 first reconnect separately from the later node0 receiver reconnect
```

Confidence: high that node 0 is the fallen-behind learner for the confirmed node0/node2 reconnect; medium for the
second reconnect interpretation until its teacher is identified.

### Metrics: `sync_phase_XX_fraction_OTHER_FALLEN_BEHIND` And `sync_phase_XX_fraction_SELF_FALLEN_BEHIND`

Category: reconnect-adjacent gossip sync

Definition:

- Code source: `SyncPhase`, `RpcPeerHandler`, `SyncMetrics`, and `PhaseTimer`.
- `XX` is the peer node ID from the local node's perspective. For example, in node0's CSV,
  `sync_phase_02_fraction_SELF_FALLEN_BEHIND` means node0's sync phase with peer node2.
- `SELF_FALLEN_BEHIND`: this node has fallen behind; reconnect should take over soon.
- `OTHER_FALLEN_BEHIND`: the peer has fallen behind; this node should not continue normal gossip sync with that peer.
- These are reset-on-read fractional gauges, not counters. A value such as `0.003` means the sync phase was active for
  about 0.3% of that metric sampling interval.

Observed first node0 fall-behind trigger:

```text
Node 0 at 2026-05-05 20:11:44 UTC:
  sync_phase_01_fraction_SELF_FALLEN_BEHIND = 0.003
  sync_phase_02_fraction_SELF_FALLEN_BEHIND = 0.001
  sync_phase_03_fraction_SELF_FALLEN_BEHIND = 0.003
  sync_phase_04_fraction_SELF_FALLEN_BEHIND = 0.001
  sync_phase_05_fraction_SELF_FALLEN_BEHIND = 0.001
  sync_phase_06_fraction_SELF_FALLEN_BEHIND = 0.003

Peer-side reciprocal signal near the same time:
  node1 sync_phase_00_fraction_OTHER_FALLEN_BEHIND = 0.003 at 2026-05-05 20:11:42 UTC
  node2 sync_phase_00_fraction_OTHER_FALLEN_BEHIND = 0.001 at 2026-05-05 20:11:42 UTC
  node3 sync_phase_00_fraction_OTHER_FALLEN_BEHIND = 0.003 at 2026-05-05 20:11:42 UTC
  node5 sync_phase_00_fraction_OTHER_FALLEN_BEHIND = 0.001 at 2026-05-05 20:11:42 UTC
  node6 sync_phase_00_fraction_OTHER_FALLEN_BEHIND = 0.003 at 2026-05-05 20:11:42 UTC
```

Observed second node0 fall-behind trigger:

```text
Node 0 at 2026-05-05 20:22:26 UTC:
  sync_phase_01_fraction_SELF_FALLEN_BEHIND = 0.440
  sync_phase_02_fraction_SELF_FALLEN_BEHIND = 0.171
  sync_phase_04_fraction_SELF_FALLEN_BEHIND = 0.414
  sync_phase_06_fraction_SELF_FALLEN_BEHIND = 0.003

Peer-side reciprocal signal near the same time:
  node1 sync_phase_00_fraction_OTHER_FALLEN_BEHIND = 0.378 at 2026-05-05 20:22:24 UTC
  node2 sync_phase_00_fraction_OTHER_FALLEN_BEHIND = 0.173 at 2026-05-05 20:22:27 UTC
```

Candidate learner/teacher signal:

- This independently confirms that node 0 was the node falling behind before the first receiver reconnect.
- This metric does not identify the reconnect teacher by itself. For the first reconnect, teacher identity still comes
  from aligned reconnect start/end counters plus node0/node2 peer byte rates.
- Node4 peer-side confirmation is unavailable because `MainNetStats4.csv` stops at `2026-05-05 13:18:14 UTC`, but
  node0's local peer4 phase metric exists and participates in the second fall-behind trigger.

Implication for local ReconnectBench:

- This metric does not provide a direct local benchmark parameter.
- It is a good timeline marker for when normal gossip sync decided reconnect should take over.
- It reinforces that the first node0/node2 reconnect and the later node0 receiver reconnect are separate recovery
  attempts and should not be blended into a single local benchmark comparison.

Recommended local parameter impact:

```text
networkLatencyMicroseconds: no direct value
networkBandwidthMegabitsPerSecond: no direct value
networkInflightBytesLimit: no direct value
cluster timeline cut: use as trigger evidence before reconnect role counters
```

Confidence: high that node0 is the self-fallen-behind node; medium for interpreting the second trigger until its
teacher is identified.

### Metrics: Service Store Size (`accounts*`, `contracts*`, NFT, Token Association, Token, And Topic)

Category: state

This section groups service-store entity counts because their interpretation is the same:

- `*Used` metrics are current entity counts.
- `*MaxNumber` metrics are configured upper bounds.
- `*PercentUsed` metrics are ratios against those bounds.
- The CSV descriptions for several `*Used` metrics say "instantaneous % used", but the metric implementations and
  observed values confirm they are counts.

Summary during the confirmed first node0/node2 reconnect window:

| Store | Stable count / shape during first node0/node2 window | Calibration use |
| --- | --- | --- |
| Accounts | `100,000,713`, identical across active sampled nodes | Baseline `100M` service-store profile. |
| Contracts | `7`, identical and stable | Negative evidence; no contract-specific profile needed. |
| NFTs | `100,000,000`, identical and stable | Baseline `100M` service-store profile, not divergence driver. |
| Token associations | node0 `101,679,929`; node2 `103,891,967 -> 104,528,182` | Main divergence signal. |
| Tokens | `1,000`, stable | Too small to drive reconnect shape. |
| Topics | `100,000`, stable | Small relative to total state. |

#### Accounts Store

Observed values during the confirmed first node0/node2 reconnect window:

```text
Window: 2026-05-05 20:11:48-20:22:23 UTC

Active nodes with samples: node0, node1, node2, node3, node5, node6
Node4: no sample in this window; MainNetStats4.csv ends at 2026-05-05 13:18:14 UTC

accountsMaxNumber:   2,147,483,647
accountsUsed:          100,000,713
accountsPercentUsed:         4.66
```

Per-node shape:

- `accountsUsed` is identical across active nodes in the confirmed reconnect window.
- `accountsUsed` does not change between the first and last sample in that window.
- By itself, this metric does not explain the node0/node2 divergence; it gives account-map scale, not dirty/clean shape.

Context from adjacent state-size metric:

- At the start of the confirmed window, node0 reports `vmap_size_state=415,306,489`, while active non-reconnecting nodes
  are around `417,518,2xx-417,518,4xx`.
- This means account entities are only part of the total virtual-map state visible in these metrics. If local
  `ReconnectBench` models a single account-like virtual map, a `100M` total-record profile is the better scale from this metric. If it
  is meant to model total virtual-map load, later `vmap_size_state` and store-specific metrics matter more.

Implication for local ReconnectBench:

- The current local benchmark runs using about `50M` records are below the observed account-map scale.
- A useful next state-size sweep should include at least a `100M` total-record profile for account-map scale.
- Do not use `accountsMaxNumber` as `numFiles * numRecords`; it is a configured upper bound, not the current state size.
- Do not use `accountsPercentUsed` directly for benchmark size; it is only a ratio against the configured maximum.

Recommended local parameter impact:

```text
totalRecords/account-map scale: add 100,000,713-profile, probably rounded to 100M for repeatability
tree size / total virtual-map scale: unresolved until vmap_size_state and store-specific metrics are analyzed
divergence shape: no direct value
```

Confidence: high for account count and max-number interpretation; medium for mapping this to local `numFiles * numRecords` because
`ReconnectBench` currently models one synthetic virtual map, while the cluster has multiple state stores.

#### Contracts Store

Definition detail:

- Code source for `contractsMaxNumber`: `ConfigMetrics`, from `ContractsConfig.maxNumber()`.
- Code source for `contractsUsed` and `contractsPercentUsed`: `StoreMetricsImpl`.
- `contractsUsed`: current count of smart contract entities in the contracts store.
- `contractsPercentUsed`: `100.0 * contractsUsed / contractsMaxNumber`.
- CSV caveat: like `accountsUsed`, the CSV description for `contractsUsed` says "instantaneous % used", but the value
  is a count. The metric implementation and observed values confirm that `contractsUsed` is an entity count.

Observed values during the confirmed first node0/node2 reconnect window:

```text
Window: 2026-05-05 20:11:48-20:22:23 UTC

Active nodes with samples: node0, node1, node2, node3, node5, node6
Node4: no sample in this window; MainNetStats4.csv ends at 2026-05-05 13:18:14 UTC

contractsMaxNumber:   5,000,000
contractsUsed:                7
contractsPercentUsed:      0.00
```

Per-node shape:

- `contractsUsed` is identical across active nodes in the confirmed reconnect window.
- `contractsUsed` does not change between the first and last sample in that window.
- Contract entities are not a meaningful contributor to the cluster state size in this run.

Implication for local ReconnectBench:

- This metric does not justify a contract-specific benchmark scale.
- It is useful negative evidence: the observed large virtual-map state is not explained by smart contract entity count.
- Do not use `contractsMaxNumber` as `numFiles * numRecords`; it is a configured upper bound, not current state size.

Recommended local parameter impact:

```text
totalRecords/contract-map scale: no dedicated profile needed from this metric
tree size / total virtual-map scale: unresolved until vmap_size_state and store-specific metrics are analyzed
divergence shape: no direct value
```

Confidence: high. The values are stable and identical across all active sampled nodes in the confirmed reconnect window.

#### NFT, Token Association, Token, And Topic Stores

Metrics covered:

- `nftsMaxNumber`, `nftsPercentUsed`, `nftsUsed`
- `tokenAssociationsMaxNumber`, `tokenAssociationsPercentUsed`, `tokenAssociationsUsed`
- `tokensMaxNumber`, `tokensPercentUsed`, `tokensUsed`
- `topicsMaxNumber`, `topicsPercentUsed`, `topicsUsed`

Definition detail:

- Code source for max-number metrics: `ConfigMetrics`.
- `nftsMaxNumber`: `TokensConfig.nftsMaxAllowedMints()`.
- `tokenAssociationsMaxNumber`: `TokensConfig.maxAggregateRels()`.
- `tokensMaxNumber`: `TokensConfig.maxNumber()`.
- `topicsMaxNumber`: `TopicsConfig.maxNumber()`.
- Code source for `*Used` and `*PercentUsed` metrics: `StoreMetricsImpl`.
- `*Used`: current entity count for that store.
- `*PercentUsed`: `100.0 * used / maxNumber`.
- CSV caveat: as with accounts/contracts, the CSV description for `*Used` says "instantaneous % used", but these values
  are counts.

Observed stable store sizes during the confirmed first node0/node2 reconnect window:

```text
Window: 2026-05-05 20:11:48-20:22:23 UTC

NFTs:
  nftsMaxNumber:   2,147,483,647
  nftsUsed:          100,000,000
  nftsPercentUsed:         4.66

Tokens:
  tokensMaxNumber: 2,147,483,647
  tokensUsed:             1,000
  tokensPercentUsed:       0.00

Topics:
  topicsMaxNumber: 1,000,000
  topicsUsed:        100,000
  topicsPercentUsed:  10.00
```

These values are identical across active sampled nodes and do not change across the confirmed reconnect window.

Observed token-association shape:

```text
Node 0:
  2026-05-05 20:11:50 UTC tokenAssociationsUsed=101,679,929, percent=4.73, vmap_size_state=415,306,489
  2026-05-05 20:22:20 UTC tokenAssociationsUsed=101,679,929, percent=4.73, vmap_size_state=415,306,489
  2026-05-05 20:22:23 UTC tokenAssociationsUsed=101,679,929, percent=4.73, vmap_size_state=417,509,586

Node 2:
  2026-05-05 20:11:48 UTC tokenAssociationsUsed=103,891,967, percent=4.84, vmap_size_state=417,518,394
  2026-05-05 20:22:21 UTC tokenAssociationsUsed=104,528,182, percent=4.87, vmap_size_state=418,154,650
```

Node0/node2 gap:

```text
At reconnect start:
  tokenAssociationsUsed gap: 2,212,038
  vmap_size_state gap:      2,211,905

At the last node0 sample before the receiver-end sample:
  tokenAssociationsUsed gap: 2,848,253
  vmap_size_state gap:      2,848,161

Node2 growth during window:
  tokenAssociationsUsed growth: 636,215
  vmap_size_state growth:      636,256
```

Candidate divergence-shape signal:

- Token associations are the first strong state metric explaining the node0/node2 state-size gap.
- Node0's token-association app-store metric is flat during the reconnect window, while node2 and the other active
  non-reconnecting nodes continue growing.
- The token-association gap almost exactly matches the `vmap_size_state` gap, so this reconnect appears
  token-association-heavy and mostly append/growth driven before the receiver-end sample.
- `vmap_size_state` then jumps on node0 at `2026-05-05 20:22:23 UTC`, while `tokenAssociationsUsed` remains stale in
  that exact sample. Use the dedicated `vmap_*` section below for the reconnect-completion interpretation.
- NFTs are large (`100M`) but stable and identical across active sampled nodes, so they contribute to baseline state
  size but not to the observed node0/node2 divergence in this window.
- Tokens and topics are small compared with accounts/NFTs/token associations and are not useful divergence drivers here.

Current static `ReconnectBench` approximation:

- The current benchmark cannot model teacher growth while reconnect is running. The `636k` live growth during the first
  cluster window is cluster behavior to validate later, not a current local parameter.
- For a static first-pass local approximation, use an append-heavy gap:
  - `teacherAddProbability ~= 2.21M / 415.3M ~= 0.0053`
  - `teacherRemoveProbability=0`
  - keep `teacherModifyProbability` diagnostic until clean/dirty reconnect counters are available.

Context from visible store totals:

```text
Visible stable/growth stores included so far:
  accounts + contracts + NFTs + token associations + tokens + topics

Node0 visible store total:       301,781,649
Node2 visible store total start: 303,993,687
Node2 visible store total end:   304,629,902

Node0 vmap_size_state minus visible total:       113,524,840
Node2 vmap_size_state minus visible total start: 113,524,707
Node2 vmap_size_state minus visible total end:   113,524,748
```

The unaccounted `~113.5M` records are stable in this window based on currently analyzed stores. Later state metrics may
identify the remaining stores, but they are not the source of the node0/node2 gap seen here.

Implication for local ReconnectBench:

- The cluster state is not just a `100M` account map. A total virtual-map scale near `415M-418M` is visible in
  `vmap_size_state`.
- For local calibration, use at least two state-size profiles:
  - service-store scale: about `100M` total records;
  - total virtual-map scale: about `400M` total records, if feasible locally.
- The divergence shape should include an append-heavy token-association-style gap of roughly `2.2M-2.9M` records for
  this reconnect window, plus continued teacher-side growth during reconnect.

Recommended local parameter impact:

```text
totalRecords/store-scale profile: 100M
totalRecords/total-vmap profile: 400M if feasible
divergence shape: token-association-heavy append/growth gap, roughly 2.2M at start and 2.85M by end
static first-pass teacherAddProbability: about 0.0053 for a 415M-record profile
static first-pass teacherRemoveProbability: 0
teacherModifyProbability: unknown/diagnostic until clean-dirty reconnect counters are available
teacher growth during reconnect: roughly 636k token associations over the 10.5 minute window
```

Confidence: high that token associations explain the node0/node2 state-size gap in the confirmed first reconnect
window; medium for direct local benchmark translation because `vmap_size_state` shows that reconnect applies a state
snapshot near the start of each reconnect, while active nodes keep growing during the transfer.

### Metrics: `vmap_*`

Category: state / virtual-map lifecycle

Metrics covered:

- `vmap_size_state`
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
- `vmap_lifecycle_nodeCacheSizeB_state`
- `vmap_lifecycle_pipelineSize_state`

Definition:

- Code source: `VirtualMapStatistics`.
- `vmap_size_state`: `LongGauge`, total virtual-map entity count for the `state` virtual map.
- `vmap_queries_*`: `LongAccumulator` metrics updated by virtual-map add/read/remove/update operations.
- `vmap_lifecycle_flushCount_state`: monotonic `Counter` for virtual root copy flushes to the data source.
- `vmap_lifecycle_*DurationMs_state` and `*BackpressureMs_state`: accumulator samples for virtual-map lifecycle
  durations/backpressure.
- `vmap_lifecycle_nodeCacheSizeB_state` and `vmap_lifecycle_pipelineSize_state`: gauges.

Metric-usefulness split:

- `vmap_size_state` is highly relevant. It is the cleanest direct metric so far for total local benchmark tree scale
  and for when the learner's virtual-map root switches after reconnect.
- `vmap_queries_*` are not reliable for reconstructing total divergence from first/last CSV values. In the sampled CSVs
  they behave like interval/sample values and may decrease between samples, even though the code uses accumulators.
  Use store counts and `vmap_size_state` for size/divergence instead.
- `vmap_lifecycle_*` metrics are useful as storage/pipeline stage evidence, not as traversal or network parameters.

First confirmed node0/node2 reconnect window:

```text
Window: 2026-05-05 20:11:48-20:22:23 UTC

Node 0 learner:
  2026-05-05 20:11:50 UTC vmap_size_state=415,306,489
  2026-05-05 20:22:20 UTC vmap_size_state=415,306,489
  2026-05-05 20:22:23 UTC vmap_size_state=417,509,586
  observed completion jump: +2,203,097

Node 2 teacher:
  2026-05-05 20:11:48 UTC vmap_size_state=417,518,394
  2026-05-05 20:22:21 UTC vmap_size_state=418,154,650
  observed live growth: +636,256

Other active sampled nodes:
  start range: 417,518,207-417,518,415
  end range:   418,154,462-418,154,650
  growth:      about 636k virtual-map records
```

Interpretation:

- Node0 starts about `2.21M` virtual-map records behind the active nodes.
- Node0 stays flat until the receiver-end sample, then jumps by about `2.20M`.
- The post-jump value, `417,509,586`, is close to node2's start-of-window size, `417,518,394`, not node2's
  end-of-window size, `418,154,650`.
- Production reconnect semantics make an accept-time teacher snapshot plausible. The teacher reserves the latest complete
  signed state when it accepts the reconnect request, then builds a detached teacher view from that state before
  releasing the original reservation. Changes made after that view is built are not reflected in the reconnect stream,
  so the learner can receive a state close to the teacher's state at reconnect accept/start while active nodes continue
  advancing during the transfer.
- In this sample, active nodes process about `636k` more virtual-map records during the first transfer.
- Node0 clears `hasFallenBehind` at `20:22:23 UTC`, falls behind again at `20:22:26 UTC`, and starts a second receiver
  reconnect at `20:22:38 UTC`. The `vmap_size_state` evidence explains why that second reconnect is plausible: node0
  was no longer missing the original `2.2M` records, but it was still behind current active nodes by roughly `645k`.

Second node0 receiver reconnect window:

```text
Window: 2026-05-05 20:22:23-20:28:32 UTC

Node 0 learner:
  start vmap_size_state=417,509,586
  end   vmap_size_state=418,157,279
  observed completion jump: +647,693

Active sampled nodes:
  start range: 418,157,377-418,157,802
  end range:   418,523,271-418,523,603
  growth:      about 366k virtual-map records
```

Second-window interpretation:

- The second reconnect again moves node0 close to the active nodes' start-of-window state, not their end-of-window
  state.
- At `2026-05-05 20:28:32 UTC`, node0 ends the second receiver reconnect and reports `hasFallenBehind=false`.
- At `20:28:35 UTC`, node0's `tokenAssociationsUsed` finally jumps from `101,679,929` to `104,532,040`, and
  `vmap_size_state` resumes live growth. This means app-store counters can lag the virtual-map root switch in the CSV
  samples around reconnect completion.

Lifecycle observations:

- Node0 `vmap_lifecycle_flushCount_state` increases by `1,983` during the first reconnect window and by `1,198` during
  the second receiver window.
- Active non-reconnecting nodes increase `flushCount` by only about `72` during the first window.
- Node0 `vmap_lifecycle_flushDurationMs_state` shows interval samples near completion, including `2,589 ms`,
  `2,267 ms`, `6,719 ms`, and `4,848 ms` in the `20:22:08-20:22:23 UTC` region.
- Node0 `vmap_lifecycle_flushBackpressureMs_state` and `familySizeBackpressureMs_state` remain `0` in the first window;
  `pipelineSize_state` remains `2`.
- Node0 `nodeCacheSizeB_state` is stable at `1,485,959,799` bytes during the first window, then drops to `24,576`
  bytes after the first receiver-end sample. Treat this as a lifecycle/stage signal, not a local benchmark parameter.

Implication for local ReconnectBench:

- Use `vmap_size_state` for the total virtual-map scale. The relevant cluster scale is about `415M-418M` records during
  these reconnects, not just the `100M` service-store scale.
- For a fixed teacher/learner local benchmark, the first node0/node2 reconnect maps most directly to a starting gap of
  about `2.21M` virtual-map records at roughly `415.3M` learner size and `417.5M` teacher size.
- If trying to reproduce the repeated cluster behavior, model that active nodes grow while reconnect is running:
  about `636k` additional virtual-map records during the first `~10.5 min` reconnect, then about `366k` during the
  second `~6.1 min` window.
- Current static `ReconnectBench` does not model active teacher growth during the transfer; use the starting gap for
  first-pass local approximation and keep live-growth behavior as a cluster-validation concern.
- Do not use `vmap_queries_*` first/last deltas as `teacherAddProbability`, `teacherRemoveProbability`, or
  `teacherModifyProbability` inputs. Use the store counts and the `vmap_size_state` gaps instead.
- Keep lifecycle metrics separate from traversal ordering. They suggest learner-side flush/persistence work is heavy
  during reconnect, but they do not directly choose `pullTopToBottom` vs `pullParallelSync`.

Recommended local parameter impact:

```text
tree size / total virtual-map scale: 415M-418M totalRecords, if feasible
practical total-vmap profile: 400M totalRecords
first static teacher/learner gap: about 2.21M records
teacher/live growth during first reconnect: about 636k records
second static teacher/learner gap: about 648k records
teacher/live growth during second reconnect: about 366k records
vmap_queries_*: do not use for divergence totals
storage/lifecycle: track flushCount and flushDuration separately from traversal/network
```

Confidence: high for `vmap_size_state` scale and two-stage reconnect interpretation; medium for translating live
cluster growth into local benchmark parameters because current `ReconnectBench` compares fixed trees rather than a
teacher that continues to advance during the transfer.

### Metrics: `ds_files_*`

Category: state / MerkleDB storage shape

Metrics covered:

- `ds_files_hashesStoreFileCount_state`
- `ds_files_hashesStoreFileSizeMb_state`
- `ds_files_leavesStoreFileCount_state`
- `ds_files_leavesStoreFileSizeMb_state`
- `ds_files_leafKeysStoreFileCount_state`
- `ds_files_leafKeysStoreFileSizeMb_state`
- `ds_files_totalSizeMb_state`
- `ds_files_level_XX_hashesFileSizeByLevelMb_state`
- `ds_files_level_XX_leavesFileSizeByLevelMb_state`
- `ds_files_level_XX_leafKeysFileSizeByLevelMb_state`

Definition:

- Code source: `MerkleDbStatistics` and `MerkleDbStatisticsUpdater`.
- `ds_files_*StoreFileCount_state`: gauge of completed MerkleDB data files for a store.
- `ds_files_*StoreFileSizeMb_state`: gauge of total completed MerkleDB data-file size for a store, in MiB.
- `ds_files_totalSizeMb_state`: gauge sum of hashes, leaves, and leaf-key file sizes, in MiB.
- `ds_files_level_XX_*FileSizeByLevelMb_state`: compaction-level file-size samples reported by `DataFileCompactor`.
- Source-name caveat: the metric descriptions for `leavesStore*` and `leafKeysStore*` are confusing/swapped in places.
  The updater source is clearer:
  - `leavesStore*` comes from `dataSource.getKeyValueStore()`;
  - `leafKeysStore*` comes from `dataSource.getKeyToPath()`.

Metric-usefulness split:

- The main store file-count and file-size gauges are useful for local disk-footprint and MerkleDB file-shape
  calibration.
- They are not direct reconnect traversal or network parameters.
- The per-level `ds_files_level_XX_*` metrics are not reliable as first/last current-state distribution gauges in this
  CSV. They are accumulator-style compaction samples; nonzero sample sums can exceed `ds_files_totalSizeMb_state`.
  Use them only as compaction activity hints, and prefer `ds_compactions_*` for compaction analysis.

First confirmed node0/node2 reconnect window:

```text
Window: 2026-05-05 20:11:48-20:22:23 UTC

Node 0 learner:
  totalSizeMb: 87,408 -> 84,432 MiB, delta -2,976 MiB
  hashes:      116 files / 24,912 MiB -> 181 files / 20,466 MiB
  leaves:      310 files / 46,077 MiB -> 315 files / 46,342 MiB
  leafKeys:    134 files / 16,419 MiB -> 124 files / 17,624 MiB
  total files: 560 -> 620

Node 2 teacher:
  totalSizeMb: 89,679 -> 89,505 MiB, delta -174 MiB
  hashes:      126 files / 24,457 MiB -> 120 files / 24,685 MiB
  leaves:      345 files / 48,453 MiB -> 341 files / 48,230 MiB
  leafKeys:    223 files / 16,769 MiB -> 217 files / 16,590 MiB
  total files: 694 -> 678

Other active sampled nodes, totalSizeMb first -> last:
  node1: 89,543 -> 89,588 MiB, delta  +45 MiB
  node3: 88,871 -> 89,525 MiB, delta +654 MiB
  node5: 89,242 -> 89,379 MiB, delta +137 MiB
  node6: 89,360 -> 89,564 MiB, delta +204 MiB
```

Second node0 receiver reconnect window:

```text
Window: 2026-05-05 20:22:23-20:28:32 UTC

Node 0 learner:
  totalSizeMb: 84,432 -> 84,492 MiB, delta +60 MiB
  hashes:      181 files / 20,466 MiB -> 190 files / 20,853 MiB
  leaves:      315 files / 46,342 MiB -> 325 files / 47,040 MiB
  leafKeys:    124 files / 17,624 MiB -> 114 files / 16,599 MiB
  total files: 620 -> 629

Node 2 over same wall-clock interval:
  totalSizeMb: 89,693 -> 89,575 MiB, delta -118 MiB
```

Interpretation:

- The active nodes' MerkleDB data-source footprint is around `89k-90k MiB` (`~87-88 GiB`) while the virtual map is
  around `417M-418M` records.
- Node0's file footprint is smaller, around `84k-87k MiB` (`~82-85 GiB`), and drops by about `2.9 GiB` during the first
  reconnect window.
  This does not match root filesystem usage, which increases sharply on node0 in `DiskspaceUsed`.
- The file-size decrease while reconnect is running is likely MerkleDB flush/compaction/file replacement behavior, not
  a direct measure of state divergence. It does not explain traversal-mode selection by itself.
- Node0 has heavy file churn during reconnect: total file count increases from `560` to `620` in the first window, while
  node2 decreases from `694` to `678`.
- Around the first receiver-end sample, node0 reports:
  - `2026-05-05 20:22:20 UTC`: `totalSizeMb=84,007`, `vmap_size_state=415,306,489`, `endsReconnectAsReceiver=0`
  - `2026-05-05 20:22:23 UTC`: `totalSizeMb=84,432`, `vmap_size_state=417,509,586`, `endsReconnectAsReceiver=1`
  This supports the idea that learner-side storage finalization and virtual-map root switching occur around the same
  sampled instant.

Implication for local ReconnectBench:

- Use `ds_files_totalSizeMb_state` to budget local disk and sanity-check whether a local run has mainnet-like storage
  scale. A single active `400M` virtual-map data-source shape likely needs around `89k-90k MiB` (`~87-88 GiB`) of
  MerkleDB data-source files. Local teacher+learner snapshots and temporary files need materially more than a single
  `90 GiB` budget.
- If we add a future benchmark option to pre-shape MerkleDB file layout, the active-node teacher profile should be
  roughly:
  - hashes store: `120-130` files, `24k-25k MiB`;
  - leaves store: `340-355` files, about `48k MiB`;
  - leaf-key store: `180-220` files, `16k-17k MiB`;
  - total data-source files: about `670-700`.
- Do not use `ds_files_*` to choose `networkLatencyMicroseconds`, `networkBandwidthMegabitsPerSecond`, or
  `networkInflightBytesLimit`.
- Do not use first/last `ds_files_level_XX_*` values as a target compaction-level distribution. They need separate
  compaction-focused analysis if we suspect storage layout changes traversal timing.

Recommended local parameter impact:

```text
tree/storage scale: 400M virtual-map records maps to roughly 89k-90k MiB (~87-88 GiB) per active data-source shape here
disk headroom: materially above one data-source footprint; teacher+learner snapshots and root filesystem usage add overhead
teacher file-shape target if needed: ~670-700 total data files across hashes/leaves/leafKeys
network params: no direct impact
traversal mode: no direct impact
storage hypothesis: learner flush/compaction/file churn is real and should be tracked separately
```

Confidence: high for store file-count/size gauges; medium for tying file churn to reconnect stages without matching
logs; low for using per-level `ds_files_level_XX_*` metrics as current file-distribution evidence.

### Metrics: `DiskspaceFree` And `DiskspaceUsed`

Category: state / runtime environment

Related metric used for interpretation:

- `DiskspaceWhole`
- `ds_files_totalSizeMb_state`

Definition:

- Code source: `RuntimeMetrics`.
- `DiskspaceFree`: `ROOT_DIRECTORY.getFreeSpace()`.
- `DiskspaceUsed`: `ROOT_DIRECTORY.getTotalSpace() - ROOT_DIRECTORY.getFreeSpace()`.
- `DiskspaceWhole`: `ROOT_DIRECTORY.getTotalSpace()`.
- Units: bytes in the raw metric values.
- Description caveat: the descriptions for `DiskspaceFree` and `DiskspaceUsed` are swapped/misleading in the metric
  config and CSV. The implementation is the source of truth.

Observed values during the confirmed first node0/node2 reconnect window:

```text
Window: 2026-05-05 20:11:48-20:22:23 UTC

Node 0:
  first: free=6769.06 GiB, used=327.17 GiB, total=7096.23 GiB, ds_files_totalSizeMb_state=87408 MiB
  last:  free=6726.21 GiB, used=370.01 GiB, total=7096.23 GiB, ds_files_totalSizeMb_state=84432 MiB
  used delta: +42.84 GiB

Node 2:
  first: free=6694.77 GiB, used=401.46 GiB, total=7096.23 GiB, ds_files_totalSizeMb_state=89679 MiB
  last:  free=6674.82 GiB, used=421.41 GiB, total=7096.23 GiB, ds_files_totalSizeMb_state=89505 MiB
  used delta: +19.95 GiB

Other active sampled nodes:
  node1 used delta:  +7.57 GiB
  node3 used delta:  -2.23 GiB
  node5 used delta: +23.04 GiB
  node6 used delta:  -1.75 GiB
```

Interpretation:

- Disk capacity is not tight in this run. Active nodes have about `7096 GiB` (`~6.93 TiB`) total disk and multiple TiB
  free.
- These metrics measure root filesystem free/used space, not MerkleDB state size directly.
- Node0's root filesystem usage increases by about `43 GiB` during the first reconnect, but
  `ds_files_totalSizeMb_state` decreases over the same window. That means `DiskspaceUsed` includes activity beyond the
  current MerkleDB data-source file-size metric, such as temporary files, snapshots, logs, deleted-but-open files, or
  other node/runtime filesystem activity.
- The metric does not explain the node0/node2 state-size gap. Store-size metrics, especially `tokenAssociationsUsed`,
  are much cleaner for divergence shape.

Implication for local ReconnectBench:

- Do not use `DiskspaceFree` or `DiskspaceUsed` to choose `numFiles * numRecords`.
- These metrics do not provide a direct local benchmark parameter.
- They are useful only as environment sanity checks: local experiments should avoid disk-capacity pressure, but these
  values do not prove whether cluster reconnect is storage-I/O-bound.
- To assess storage bottlenecks, use MerkleDB file-size/read/write/flush/compaction metrics and stage timing logs, not
  root filesystem free/used space.

Recommended local parameter impact:

```text
totalRecords: no direct value
divergence shape: no direct value
disk capacity requirement: ensure ample free disk; cluster had multi-TiB free space
storage bottleneck evidence: unresolved; use MerkleDB and stage timing metrics instead
```

Confidence: high for capacity interpretation; low for using these metrics as direct reconnect/state-shape evidence.

### Metrics: `writeMerkleRootToDisk` And `writeStateToDisk`

Category: state / storage stage timing

Related metric used for interpretation:

- `stateToDisk`

Definition:

- Code source for `writeMerkleRootToDisk`: `MerkleRootSnapshotMetrics`.
- Code source for `writeStateToDisk` and `stateToDisk`: `StateSnapshotManagerMetrics`.
- `writeMerkleRootToDisk`: average time, in milliseconds, to write a Merkle tree/root snapshot to disk.
- `writeStateToDisk`: average time, in milliseconds, to write a signed state to disk.
- `stateToDisk`: average time, in milliseconds, to perform all signed-state-to-disk actions. In the sampled data it
  matches `writeStateToDisk`.
- These are running-average metrics, not counters. A changed value indicates a state write/sample updated the average.

Observed values during the confirmed first node0/node2 reconnect window:

```text
Window: 2026-05-05 20:11:48-20:22:23 UTC

Node 0:
  first sample: writeMerkleRootToDisk=0 ms, writeStateToDisk=0 ms
  last sample:  writeMerkleRootToDisk=0 ms, writeStateToDisk=0 ms

Node 2:
  first sample: writeMerkleRootToDisk=9160 ms, writeStateToDisk=10091 ms
  last sample:  writeMerkleRootToDisk=9023 ms, writeStateToDisk=10092 ms

Other active sampled nodes:
  node1 last: writeMerkleRootToDisk=9102 ms, writeStateToDisk=9980 ms
  node3 last: writeMerkleRootToDisk=9128 ms, writeStateToDisk=10301 ms
  node5 last: writeMerkleRootToDisk=8543 ms, writeStateToDisk=8925 ms
  node6 last: writeMerkleRootToDisk=8747 ms, writeStateToDisk=9935 ms
```

Node0 timeline around reconnect completion:

```text
2026-05-05 20:22:23 UTC  endsReconnectAsReceiver 0 -> 1, write metrics still 0
2026-05-05 20:22:38 UTC  startsReconnectAsReceiver 1 -> 2, write metrics still 0
2026-05-05 20:22:41 UTC  writeMerkleRootToDisk=19171 ms
2026-05-05 20:22:44 UTC  writeStateToDisk=20140 ms, stateToDisk=20140 ms
2026-05-05 20:28:32 UTC  endsReconnectAsReceiver 1 -> 2
2026-05-05 20:28:53 UTC  writeMerkleRootToDisk=20637 ms, writeStateToDisk=20883 ms
```

Interpretation:

- Node0 does not report a nonzero state-write metric during the first node0/node2 receiver reconnect window.
- The first node0 state-write samples appear about `18-21 seconds` after the first receiver reconnect end sample and
  shortly after the second receiver reconnect starts.
- The node0 post-reconnect write samples are around `20 seconds`, while active non-reconnecting nodes are around
  `9-10 seconds`.
- Because the write samples occur after `endsReconnectAsReceiver 0 -> 1`, they should not be folded into
  `receiverReconnectDurationSeconds`. That receiver duration already includes learner-side virtual-map finalization and
  signed-state construction, but not later controller/platform load or state-to-disk snapshot work.
- The write samples may matter if the measured cluster scenario is end-to-end recovery, including post-reconnect
  persistence after reconnect.

Implication for local ReconnectBench:

- These metrics do not provide traversal, network, or divergence parameters directly.
- They do suggest a possible cluster/local mismatch if local `ReconnectBench` measures only reconnect synchronization,
  while cluster observations include post-reconnect state write/persistence time.
- If comparing to `receiverReconnectDurationSeconds`, keep these write costs separate.
- If comparing to node recovery wall-clock, account for an additional post-reconnect state-write stage on the learner,
  approximately `20 seconds` in this node0 sample.

Recommended local parameter impact:

```text
traversal mode: no direct value
networkLatencyMicroseconds: no direct value
networkBandwidthMegabitsPerSecond: no direct value
storage stage: track separately from reconnect sync; learner write sample around 20s after reconnect
cluster/local comparison: confirm whether cluster timing includes post-reconnect write/finalization
```

Confidence: high for metric definition and node0 timing; medium for attributing the write samples to reconnect
finalization without matching logs.

### Metric: `ping`, `ping_us_XX`, `ping_us_XXMIN`, And `secGossipRoundtrip`

Category: network

Definition:

- Code source: `NetworkMetrics`, `RpcPingHandler`, `AverageAndMin`, and `MinStat`.
- `ping_us_XX`: gossip RPC ping round-trip time from this node to peer node `XX`, in microseconds.
- `ping_us_XXMIN`: minimum observed value for the same peer ping metric during the stat reset period.
- `ping`: aggregate node-level average round-trip message time, also in microseconds.
- `secGossipRoundtrip`: event gossip roundtrip timing in seconds. It is not a direct socket or RPC RTT metric and should
  not be used as the local reconnect network latency input.

Important interpretation details:

- `ping_us_XX` measures gossip RPC ping latency, not reconnect socket transfer latency.
- The peer-specific average is decay based (`PING_DECAY = 0.1`), so it can remain stale if no fresh ping samples arrive.
- `ping_us_XXMIN = 9999999` is the metric ceiling/default and means there was no new minimum sample during that reset
  interval. It should not be interpreted as a real RTT.

Source files were based on reconnect run that was done on May 6, 2026 (they were deleted after info was extracted).

Confirmed reconnect pair:

- The first confirmed reconnect is node 0 as receiver/learner and node 2 as sender/teacher.
- Relevant peer metrics are:
  - node 0 -> node 2: `ping_us_02`, `ping_us_02MIN`
  - node 2 -> node 0: `ping_us_00`, `ping_us_00MIN`

First reconnect window, approximately `2026-05-05 20:11:48 UTC` to `2026-05-05 20:22:23 UTC`:

```text
node 0 -> node 2 ping_us_02:
  samples=212, avg metric value=605.8 us, min=503.5 us, p50=606.3 us, p95=606.3 us, max=606.3 us

node 2 -> node 0 ping_us_00:
  samples=212, avg metric value=610.2 us, min=610.2 us, p50=610.2 us, p95=610.2 us, max=610.2 us

node 0 aggregate ping:
  samples=212, avg=211.8 us, min=180 us, p50=192 us, p95=313 us, p99=477 us, max=504 us

node 2 aggregate ping:
  samples=212, avg=242.6 us, min=205 us, p50=241 us, p95=256 us, p99=266 us, max=269 us
```

Caveat for the first reconnect window:

- During most of this window, `ping_us_02MIN` and `ping_us_00MIN` are `9999999`.
- That means the pair-specific `~606-610 us` values are mostly stale averages, not continuous fresh RTT samples during
  reconnect transfer.
- The aggregate `ping` values still support a low-latency cluster-private network shape, but they are not pair-specific
  reconnect RTT measurements.

Second node0 receiver reconnect / post-first-reconnect window, approximately `2026-05-05 20:22:23 UTC` to
`2026-05-05 20:28:32 UTC`:

```text
node 0 -> node 2 ping_us_02:
  samples=124, avg metric value=129.4 us, min=89.8 us, p50=110.2 us, p95=223.3 us, p99=355.3 us, max=503.5 us

node 0 -> node 2 ping_us_02MIN, excluding sentinel 9999999:
  samples=124, avg=79.9 us, min=51 us, p50=79 us, p95=111 us, p99=123 us, max=183 us

node 2 -> node 0 ping_us_00:
  samples=123, avg metric value=141.1 us, min=100.1 us, p50=124.9 us, p95=213.6 us, p99=297.3 us, max=459.0 us

node 2 -> node 0 ping_us_00MIN, excluding sentinel 9999999:
  samples=123, avg=88.5 us, min=48 us, p50=87 us, p95=118 us, p99=141 us, max=166 us

node 0 aggregate ping:
  samples=124, avg=117.0 us, min=105 us, p50=113 us, p95=151 us, p99=180 us, max=187 us

node 2 aggregate ping:
  samples=123, avg=210.0 us, min=169 us, p50=183 us, p95=370 us, p99=461 us, max=478 us
```

Implication for local ReconnectBench:

- These metrics do not support using `100 ms` or `200 ms` RTT as the cluster-calibration profile. Those runs remain useful
  WAN sensitivity tests, but they do not match this Kubernetes/Latitude cluster sample.
- For this cluster, the observed RTT shape is sub-millisecond: roughly `100-300 us` from fresh samples, with a conservative
  stale pair-specific upper hint around `600 us`.
- Current `ReconnectBench` applies `networkLatencyMicroseconds` as one-way delivery latency, so use approximately half
  the target RTT:
  - target RTT `100-300 us` -> `networkLatencyMicroseconds=50-150`
  - conservative target RTT `600 us` -> `networkLatencyMicroseconds=300`
- If the benchmark parameter is treated as full RTT in a future implementation, use `100-300` and `600` directly instead.
- With RTT in this range, bandwidth-delay product is tiny compared to MiB-scale in-flight caps. For example:
  - `300 Mbps * 0.2 ms / 8 ~= 7.5 KiB`
  - `1 Gbps * 0.6 ms / 8 ~= 75 KiB`
- Therefore, for this cluster profile, `networkInflightBytesLimit` should normally be large/neutral. A cap in the
  `13-25 MiB` range is far above the observed BDP and should not model a real cluster network window unless separate TCP
  evidence proves otherwise.

Recommended local parameter impact:

```text
networkLatencyMicroseconds:
  cluster-private one-way profile: 50-150
  conservative one-way profile: 300
  do not use 50_000 or 100_000 for cluster calibration based on these ping metrics

networkBandwidthMegabitsPerSecond:
  combine with bytes_per_sec_sent evidence; keep sweep around 150 Mbps, 200 Mbps, 300 Mbps, and 1 Gbps

networkInflightBytesLimit:
  use a large/neutral cap for cluster-profile runs, for example 128 MiB or larger
  keep smaller caps only as diagnostic sensitivity tests unless TCP window samples justify them
```

Confidence: high that the cluster has very low gossip RPC ping latency; medium that this exactly represents reconnect
socket RTT, because peer ping samples were stale during much of the first reconnect window.

### Metric: `bytes_per_sec_sent` And `bytes_per_sec_sent_XX`

Category: network

Definition:

- Code source: `NetworkMetrics`.
- `bytes_per_sec_sent`: number of bytes sent per second over the network, total for this node.
- `bytes_per_sec_sent_XX`: bytes per second sent from this node to peer node `XX`.
- These are observed application/gossip network byte rates. They are not direct link-capacity measurements.

Source files were based on reconnect run that was done on May 6, 2026 (they were deleted after info was extracted).

Confirmed reconnect window:

- Reconnect role metrics confirm node 0 as receiver/learner and node 2 as sender/teacher from about
  `2026-05-05 20:11:48 UTC` to `2026-05-05 20:22:23 UTC`.
- This uses the wider aligned window across both nodes: node 2 starts/ends as sender at about `20:11:48-20:22:15 UTC`,
  and node 0 starts/ends as receiver at about `20:11:50-20:22:23 UTC`.

Observed total send rate in this window:

```text
Node 0: avg 29.18 MB/s, max 33.75 MB/s at 2026-05-05 20:20:26 UTC
Node 1: avg  1.60 MB/s, max  1.63 MB/s
Node 2: avg 15.14 MB/s, max 25.73 MB/s at 2026-05-05 20:22:12 UTC
Node 3: avg  1.81 MB/s, max  1.83 MB/s
Node 5: avg  1.61 MB/s, max  1.63 MB/s
Node 6: avg  1.69 MB/s, max  1.71 MB/s
```

Peer-direction breakdown for the confirmed node0/node2 pair:

```text
learner-to-teacher, node 0 -> node 2: avg 29.18 MB/s, max 33.75 MB/s at 2026-05-05 20:20:26 UTC
teacher-to-learner, node 2 -> node 0: avg 13.46 MB/s, max 24.05 MB/s at 2026-05-05 20:22:12 UTC
```

For node 0, effectively all reconnect-window send traffic goes to node 2. Other node 0 peer columns stay near zero.
For node 2, the dominant peer send traffic goes to node 0; sends to other peers stay near `0.34 MB/s`.

Candidate pair signal:

- This metric strongly supports node 0 and node 2 as the relevant reconnect pair.
- Reconnect role metrics confirm node 0 as receiver/learner and node 2 as sender/teacher.

Implication for local ReconnectBench:

- This metric provides an observed throughput scale, not the maximum available cluster bandwidth.
- The observed learner-to-teacher direction, node 0 to node 2, averages about `233 Mbps` and peaks around `270 Mbps`.
- The observed teacher-to-learner direction, node 2 to node 0, averages about `108 Mbps` and peaks around `192 Mbps`.
- The local network simulation uses symmetric bandwidth, while the observed cluster rates are asymmetric. A `300 Mbps`
  profile mostly covers learner-to-teacher peak/sensitivity, while `150-200 Mbps` better brackets teacher-to-learner
  observed throughput.
- For local calibration, this suggests testing below the nominal `1 Gbps` profile as well as at `1 Gbps`. A useful
  first sweep is approximately `150 Mbps`, `200 Mbps`, `300 Mbps`, and `1 Gbps`.
- Combined with the `ping_us_*` evidence, the current cluster-profile in-flight cap should be large/neutral. At sub-ms
  RTT, the calculated BDP is far below even a `13 MiB` cap, so smaller caps are diagnostic sensitivity tests rather than
  evidence-backed cluster parameters unless TCP/window samples show otherwise.

Recommended local parameter impact:

```text
networkBandwidthMegabitsPerSecond: add 150-300 Mbps observed-throughput profiles, keep 1 Gbps as nominal-link profile
networkLatencyMicroseconds: combine with ping evidence; use sub-ms RTT profiles for cluster calibration
networkInflightBytesLimit: use large/neutral cap for cluster-profile runs until TCP/window evidence exists
```

Confidence: high that node0/node2 dominate network traffic during the confirmed reconnect window; high that the
directional interpretation is node 2 -> node 0 for teacher-to-learner and node 0 -> node 2 for learner-to-teacher; low
as a measure of true link capacity.
