# Cluster Calibration Summary

## Scope

| Item | Status | Source |
|---|---:|---|
| Summary source of truth | present | This file summarizes only the per-run Markdown evidence files in `extracted-cluster-evidence/`. |
| Raw artifact extraction | not_applicable | Raw artifact values are extracted in [top-to-bottom.md](top-to-bottom.md), [two-phase-pessimistic.md](two-phase-pessimistic.md), and [parallel-sync.md](parallel-sync.md). |
| Summary discipline | present | Every comparison row below points back to per-run evidence sections. |

## Run Mapping

| Mode | Artifact run root | Per-run source |
|---|---|---|
| `pullTopToBottom` | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect1` | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullTwoPhasePessimistic` | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect2_2phase/report` | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| `pullParallelSync` | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect3_PullParallelSync/report` | [parallel-sync.md](parallel-sync.md#run-context) |

## Verification Status

| Check | Status | Source |
|---|---:|---|
| Verification notes file present | present | [verification-notes.md](verification-notes.md#scope) |
| Per-run source-reference verification | ambiguous | [verification-notes.md](verification-notes.md#per-run-results) |
| Source reference failures | present | [verification-notes.md](verification-notes.md#source-reference-failures) |
| Final verification status | ambiguous | [verification-notes.md](verification-notes.md#final-verification-status) |

## Per-Mode Acceptance Summary

| mode | artifact directory | commit | image | learner node | teacher node | first reconnect start | first reconnect end | learner duration | teacher reconnect context present | reconnect stats present | teacher/learner state size present | workload profile present | RTT evidence present | bandwidth evidence present | TCP/window evidence present | later reconnects observed | accepted for calibration | reason if not accepted | source |
|---|---|---|---|---:|---:|---|---|---:|---|---|---|---|---|---|---|---|---|---|---|
| `pullTopToBottom` | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect1` | `796905b12784f90d8b12b9ee0d9a6a91de0e9b85` | version context present; exact tag/digest missing | `0` | `2` | `2026-05-29 06:41:57.343` | `2026-05-29 06:45:00.693` | `183.350 s` | yes | yes | yes | yes | yes, via stats ping; passive direct attribution ambiguous | yes | ambiguous | no | no | Passive TCP/window fields are present during the reconnect window, but direct learner/teacher socket attribution is unresolved, so network-inflight calibration is incomplete. | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode) |
| `pullTwoPhasePessimistic` | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect2_2phase/report` | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | version context present; exact tag/digest missing | `0` | `2` | `2026-05-29 18:26:08.709` | `2026-05-29 18:29:04.992` | `176.283 s` | yes | yes | yes | yes | yes, via stats ping; passive window missing | yes | missing | no | no | Passive TCP/window sampler coverage skips the first reconnect window, so network-inflight calibration is incomplete. | [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode) |
| `pullParallelSync` | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect3_PullParallelSync/report` | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | version context present; exact tag/digest missing | `0` | `1` | `2026-05-29 22:50:04.496` | `2026-05-29 22:56:15.846` | `371.350 s` | yes | yes | yes | yes | yes, via stats ping; passive direct attribution ambiguous | yes | ambiguous | yes | no | Passive TCP/window evidence is partial and endpoint-to-node attribution is unresolved, so network-inflight calibration is incomplete. | [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) |

## Cluster Network Evidence Summary

| Mode | RTT evidence | Bandwidth or throughput evidence | TCP/window evidence | Source |
|---|---|---|---|---|
| `pullTopToBottom` | stats ping present | learner data/time and teacher stats throughput present | passive sampler fields present but direct socket attribution ambiguous | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTwoPhasePessimistic` | stats ping present | learner data/time and teacher stats throughput present | passive sampler gap skips the first reconnect window | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |
| `pullParallelSync` | stats ping present | learner data/time and teacher stats throughput present | passive sampler fields partial and direct socket attribution ambiguous | [parallel-sync.md](parallel-sync.md#network-evidence) |

## Full Node Metrics Cross-Check Summary

| Mode | Status | Observation | Source |
|---|---:|---|---|
| `pullTopToBottom` | present | Seven node stats CSVs are present; reconnect-window cross-check uses teacher node `2` and learner node `0` metrics. | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTwoPhasePessimistic` | ambiguous | Nodes 1-6 overlap the first reconnect window; node 7 stats end before the first reconnect window. | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| `pullParallelSync` | present | Seven node stats CSVs overlap the first reconnect window; teacher node `1` exposes learner-directed throughput, ping, and state-size columns. | [parallel-sync.md](parallel-sync.md#network-evidence) |

## State And Divergence Summary

| Mode | First state-size gap | Service/store metrics | Divergence shape | Source |
|---|---:|---|---|---|
| `pullTopToBottom` | `7643884` leaves | present | Growth-heavy reconnect with substantial clean and dirty leaf work. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `6445909` leaves | present | Growth-heavy reconnect with a substantial dirty component. | [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullParallelSync` | `4304565` leaves | present | Growth-heavy reconnect with immediate later growth pressure. | [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence), [parallel-sync.md](parallel-sync.md#later-reconnects) |

## Service Store Size Metrics Summary

| Mode | accountsUsed | contractsUsed | nftsUsed | tokenAssociationsUsed | tokensUsed | topicsUsed | Source |
|---|---:|---:|---:|---:|---:|---:|---|
| `pullTopToBottom` | `24000712` | `6` | `24000000` | `1796550` | `1000` | `100000` | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `24000712` | `6` | `24000000` | `1469889` | `1000` | `100000` | [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullParallelSync` | `24000712` | `6` | `24000000` | `1611530` | `1000` | `100000` | [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |

## Reconnect-Window Transaction Rate Summary

| Mode | Status | Observed reconnect-window transaction-rate evidence | Source |
|---|---:|---|---|
| `pullTopToBottom` | present | Aggregate `WorkingQueue` `TPS(current)` `10358..10415`; `TPS(EMA)` `10379..10380`. | [top-to-bottom.md](top-to-bottom.md#workload-evidence) |
| `pullTwoPhasePessimistic` | present | Transaction `TPS(current)` `9297..9486`; transaction `TPS(EMA)` `9441..9502`; receipt `TPS(current)` `8882..9901`. | [two-phase-pessimistic.md](two-phase-pessimistic.md#workload-evidence) |
| `pullParallelSync` | present | Transaction `TPS(current)` `2928..10708`; receipt `TPS(current)` `5191..18628`. | [parallel-sync.md](parallel-sync.md#workload-evidence) |

## Reconnect Work-Shape Summary

| Mode | Leaf clean data | Leaf dirty data | Counter source |
|---|---:|---:|---|
| `pullTopToBottom` | `36037864` | `35396063` | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters) |
| `pullTwoPhasePessimistic` | `12846292` | `30002441` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters) |
| `pullParallelSync` | `10163254` | `20961464` | [parallel-sync.md](parallel-sync.md#reconnect-work-shape-counters) |

## Traversal Ordering Summary

| Ordering observation | Status | Source |
|---|---:|---|
| First learner duration order is two-phase pessimistic, then top-to-bottom, then parallel sync. | derived | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode), [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode), [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) |
| Parallel sync has an immediate second reconnect that is excluded from first traversal timing. | present | [parallel-sync.md](parallel-sync.md#later-reconnects) |

## Calibration Inputs For Local ReconnectBench

| cluster evidence | local ReconnectBench input | recommended value or sweep | source per-run files | confidence or gaps |
|---|---|---|---|---|
| Stats RTT evidence | `networkLatencyMicroseconds` | Use per-run stats ping values as initial latency anchors; keep direct passive RTT unresolved. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium for stats-ping modeling; low for direct socket RTT because passive attribution is missing or ambiguous. |
| Reconnect throughput evidence | `networkBandwidthMegabitsPerSecond` | Use per-run lower-bound data/time and teacher stats throughput as sweep anchors, not as link capacity. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium for observed reconnect transfer behavior; not a true cluster link-capacity measurement. |
| TCP/window/backpressure evidence | `networkInflightBytesLimit` | Do not pick a single value from these artifacts alone; use as a gap-driven sweep dimension. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Low until passive TCP/window samples are linkable and bounded for all modes. |
| Teacher and learner state sizes | `numFiles * numRecords` target | Use per-run state-size ranges as target-size anchors. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) | Medium; exact state range evidence is present, exact teacher reserved snapshot end is missing. |
| State gap and workload profile | add/modify/remove probabilities or future divergence controls | Use extracted state gaps and workload profile to shape future divergence controls; do not derive final probabilities yet. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence), [top-to-bottom.md](top-to-bottom.md#workload-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#workload-evidence), [parallel-sync.md](parallel-sync.md#workload-evidence) | Medium for divergence shape; low for final local mutation probabilities until a modeling step is performed. |
| Clean and dirty reconnect counters | local state-shape validation | Use per-run clean/dirty counters as validation targets for local benchmark shape. | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters), [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters), [parallel-sync.md](parallel-sync.md#reconnect-work-shape-counters) | High for extracted counter presence. |
| Cluster traversal ordering | local traversal-mode ordering target | Use extracted first-duration ordering as a local comparison target, while preserving unresolved network gaps. | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode), [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode), [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) | Medium; do not make causal or winner claims. |

## Remaining Gaps

| Evidence gap | Affected modes | Source |
|---|---|---|
| Exact image tag or digest | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Baseline restore identifier | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Exact stopped-pod script output and controlled warmtime/downtime/loop count | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Controlled learnerCandidate expected node | top-to-bottom and two-phase missing; parallel ambiguous | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Controlled teacherCandidate expected node | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Controlled learnerBehindDuration | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Full-run average transaction rate | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Full node metrics cross-check limitation | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Direct passive socket attribution | top-to-bottom and parallel-sync | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Passive TCP/window samples during first reconnect | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Passive sampler end-of-first-window coverage | parallel-sync | [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
