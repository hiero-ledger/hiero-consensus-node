# 2026-06-29 Cluster Calibration Batch Summary

## Scope

| Item | Status | Source |
|---|---:|---|
| Summary source of truth | present | This file summarizes only the per-run Markdown evidence files in this batch directory. |
| Manifest source of truth | present | Raw artifact roots and manifest run IDs are owned by [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration). |
| Raw artifact extraction | not_applicable | Raw artifact values are extracted in [top-to-bottom.md](top-to-bottom.md), [parallel-sync.md](parallel-sync.md), and [two-phase-pessimistic.md](two-phase-pessimistic.md). |
| Summary discipline | present | Every comparison row below points back to per-run evidence sections. |

## Run Mapping

| Mode | Manifest batch | Manifest run | Per-run source |
|---|---|---|---|
| `pullTopToBottom` | [`2026-06-29-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration) | `top-to-bottom` | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullParallelSync` | [`2026-06-29-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration) | `parallel-sync` | [parallel-sync.md](parallel-sync.md#run-context) |
| `pullTwoPhasePessimistic` | [`2026-06-29-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration) | `two-phase-pessimistic` | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |

## Verification Status

| Check | Status | Source |
|---|---:|---|
| Verification notes file present | present | [verification-notes.md](verification-notes.md#scope) |
| Per-run source-reference verification | pass | [verification-notes.md](verification-notes.md#per-run-results) |
| Final verification status | pass | [verification-notes.md](verification-notes.md#final-verification-status) |

## Per-Mode Acceptance Summary

| mode | manifest batch | manifest run | commit | network disease preflight | network disease reason if failed | learner node | episode complete | episode incomplete reason | iteration count | complete catch-up start | complete catch-up end | complete catch-up duration | active confirmation | first iteration teacher node | first iteration start | first iteration end | first iteration duration | teacher reconnect context present | reconnect stats present | teacher/learner state size present | workload profile present | RTT evidence present | bandwidth evidence present | TCP/window evidence present | additional iterations observed | accepted for calibration | reason if not accepted | source |
|---|---|---|---|---|---|---:|---|---|---:|---|---|---:|---|---:|---|---|---:|---|---|---|---|---|---|---|---|---|---|---|
| `pullTopToBottom` | `2026-06-29-cluster-calibration` | `top-to-bottom` | `0cc709860be30d5892ba5fa70ed9300ce4107628` | pass | not applicable | `0` | yes | not applicable | `2` | `2026-06-26 17:12:54.502` | `2026-06-26 17:17:05.885` | `251.383 s` | `2026-06-26 17:19:52.830` | `4` | `2026-06-26 17:12:54.502` | `2026-06-26 17:16:06.085` | `191.583 s` | yes | yes | yes | yes | yes | yes | yes | yes | yes | not applicable | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode) |
| `pullParallelSync` | `2026-06-29-cluster-calibration` | `parallel-sync` | `0cc709860be30d5892ba5fa70ed9300ce4107628` | pass | not applicable | `0` | yes | not applicable | `2` | `2026-06-26 17:05:36.942` | `2026-06-26 17:10:23.456` | `286.514 s` | `2026-06-26 17:13:58.029` | `2` | `2026-06-26 17:05:36.942` | `2026-06-26 17:09:10.079` | `213.137 s` | yes | yes | yes | yes | yes | yes | yes | yes | yes | not applicable | [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) |
| `pullTwoPhasePessimistic` | `2026-06-29-cluster-calibration` | `two-phase-pessimistic` | `0cc709860be30d5892ba5fa70ed9300ce4107628` | pass | not applicable | `0` | yes | not applicable | `89` | `2026-06-26 17:09:42.975` | `2026-06-26 22:22:03.807` | `18,740.832 s` | `2026-06-26 22:22:14.244` | `3` | `2026-06-26 17:09:42.975` | `2026-06-26 17:13:10.799` | `207.824 s` | yes | yes | yes | yes, limited | yes, via CSV stats only | yes, via learner data/time and CSV stats | no | yes | no | Passive TCP/window evidence is absent, and load ends before final receiver finish/ACTIVE. | [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode) |

## Catch-Up Episode Summary

| Mode | Episode status | Iterations before `ACTIVE` | Complete catch-up start | Complete catch-up end | Complete catch-up duration | Active confirmation | Source |
|---|---:|---:|---|---|---:|---|---|
| `pullTopToBottom` | complete | `2` | `2026-06-26 17:12:54.502` | `2026-06-26 17:17:05.885` | `251.383 s` | `2026-06-26 17:19:52.830` | [top-to-bottom.md](top-to-bottom.md#reconnect-episodes-and-iterations) |
| `pullParallelSync` | complete | `2` | `2026-06-26 17:05:36.942` | `2026-06-26 17:10:23.456` | `286.514 s` | `2026-06-26 17:13:58.029` | [parallel-sync.md](parallel-sync.md#reconnect-episodes-and-iterations) |
| `pullTwoPhasePessimistic` | complete, incomplete for full network calibration | `89` | `2026-06-26 17:09:42.975` | `2026-06-26 22:22:03.807` | `18,740.832 s` | `2026-06-26 22:22:14.244` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-episodes-and-iterations), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |

## Network Disease Preflight Summary

| Mode | Status | Observation | Source |
|---|---:|---|---|
| `pullTopToBottom` | pass | No post-startup `ACTIVE -> CHECKING` transitions and no missing-parent evidence found. | [top-to-bottom.md](top-to-bottom.md#network-disease-preflight) |
| `pullParallelSync` | pass | No post-startup `ACTIVE -> CHECKING` transitions and no missing-parent evidence found. | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |
| `pullTwoPhasePessimistic` | pass | No post-startup `ACTIVE -> CHECKING` transitions and no missing-parent evidence found. | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-disease-preflight) |

## Cluster Network Evidence Summary

| Mode | RTT evidence | Bandwidth or throughput evidence | TCP/window evidence | Source |
|---|---|---|---|---|
| `pullTopToBottom` | CSV stats and passive socket RTT present | learner data/time, CSV send-rate, and passive observed socket fields present | passive sampler fields overlap both reconnect iterations | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullParallelSync` | CSV stats and passive socket RTT present | learner data/time, CSV send-rate, and passive observed socket fields present | passive sampler fields overlap both reconnect iterations | [parallel-sync.md](parallel-sync.md#network-evidence) |
| `pullTwoPhasePessimistic` | CSV stats present | learner data/time and CSV send-rate present | missing; no passive sampler files or embedded socket samples found | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |

## State And Divergence Summary

| Mode | Learner start size | First teacher target size | First state-size gap | Final/episode target size | Target equality verified | Service/store metrics | Divergence shape | Source |
|---|---:|---:|---:|---:|---:|---|---|---|
| `pullTopToBottom` | `92,086,114` leaves | `100,898,114` leaves | `8,812,000` leaves | `101,458,071` leaves after second iteration | yes | present | Growth-heavy, two-iteration reconnect with substantial dirty leaf data. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullParallelSync` | `92,085,451` leaves | `100,384,349` leaves | `8,298,898` leaves | `101,007,154` leaves after second iteration | yes | present | Growth-heavy, two-iteration reconnect with substantial clean plus dirty work. | [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `92,069,525` leaves | `100,676,194` leaves | `8,606,669` leaves | `132,364,614` leaves after 89 iterations | yes | present | Very long growth-heavy reconnect; final iteration has zero state-size gap. | [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |

## First Iteration Timing And State-Size Diff

| Traversal order | First iteration time | State size diff | Source |
|---|---:|---:|---|
| `pullTopToBottom` | `191.583 s` | `8,812,000` leaves | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode), [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullParallelSync` | `213.137 s` | `8,298,898` leaves | [parallel-sync.md](parallel-sync.md#analysis-output-per-mode), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `207.824 s` | `8,606,669` leaves | [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |

## Second Iteration Timing And State-Size Diff

| Traversal order | Second iteration time | State size diff | Source |
|---|---:|---:|---|
| `pullTopToBottom` | `52.451 s` | `559,957` leaves | [top-to-bottom.md](top-to-bottom.md#reconnect-window-and-roles), [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullParallelSync` | `66.991 s` | `622,805` leaves | [parallel-sync.md](parallel-sync.md#reconnect-window-and-roles), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `169.314 s` | `605,820` leaves | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-window-and-roles), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |

## Reconnect Work-Shape Summary

| Mode | Counter coverage | Leaf clean data | Leaf dirty data | Counter source |
|---|---|---:|---:|---|
| `pullTopToBottom` | two per-iteration rows | iter1 `50,132,805`; iter2 `13,571,712` | iter1 `40,948,229`; iter2 `3,766,883` | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters) |
| `pullParallelSync` | two per-iteration rows | iter1 `16,385,947`; iter2 `2,802,067` | iter1 `38,931,674`; iter2 `4,130,591` | [parallel-sync.md](parallel-sync.md#reconnect-work-shape-counters) |
| `pullTwoPhasePessimistic` | first/last plus aggregate compact coverage for 89 rows | aggregate `9,856,850,986` | aggregate `432,233,406` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters) |

## Traversal Ordering Summary

| Ordering observation | Status | Source |
|---|---:|---|
| No causal traversal-mode ranking should be derived directly from this cluster batch. | present | [top-to-bottom.md](top-to-bottom.md#acceptance-notes), [parallel-sync.md](parallel-sync.md#acceptance-notes), [two-phase-pessimistic.md](two-phase-pessimistic.md#acceptance-notes) |
| Two modes are accepted for full calibration evidence (`pullTopToBottom`, `pullParallelSync`); `pullTwoPhasePessimistic` is rejected only for full network calibration because passive TCP/window evidence is absent and workload ends before final completion. | present | [Cluster Network Evidence Summary](#cluster-network-evidence-summary), [Per-Mode Acceptance Summary](#per-mode-acceptance-summary) |
| Future trend/ranking should use complete, non-diseased, accepted catch-up episodes and should reproduce comparable state size, gap, work shape, and network profile locally. | present | [State And Divergence Summary](#state-and-divergence-summary), [Reconnect Work-Shape Summary](#reconnect-work-shape-summary) |

## Calibration Inputs For Local ReconnectBench

| cluster evidence | local ReconnectBench input | recommended value or sweep | source per-run files | confidence or gaps |
|---|---|---|---|---|
| Stats and passive RTT evidence | `networkLatencyMicroseconds` | Use top-to-bottom and parallel-sync CSV/passive RTT as primary anchors; use two-phase CSV RTT as diagnostic only. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) | Medium for accepted runs; two-phase lacks passive socket RTT. |
| Reconnect throughput evidence | `networkBandwidthMegabitsPerSecond` | Use learner data/time, CSV send-rate, and passive observed socket fields from accepted runs as sweep anchors, not as link capacity. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium; observed rates are workload/socket behavior, not measured capacity. |
| TCP/window/backpressure evidence | `networkInflightBytesLimit` | Use top-to-bottom and parallel-sync passive sampler evidence as anchors; do not use two-phase for this input. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) | Medium for accepted runs; absent for two-phase. |
| State sizes and gaps | `numFiles * numRecords` target and divergence controls | Use each accepted run as a separate anchor; do not assume common baseline state across modes. | [State And Divergence Summary](#state-and-divergence-summary) | Medium; independent live-state histories differ. |
| Clean and dirty reconnect counters | local state-shape validation | Use accepted per-iteration counters as validation targets; use two-phase aggregate as long-run diagnostic. | [Reconnect Work-Shape Summary](#reconnect-work-shape-summary) | High for extracted logs; compact two-phase per-iteration detail is not expanded inline. |

## Remaining Gaps

| Evidence gap | Affected modes | Source |
|---|---|---|
| Workflow-control files absent; stopped pod inferred as `network-node1-0` | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Baseline/restored-state upload evidence absent | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Passive TCP/window evidence absent | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Workload does not continue through final receiver finish / `ACTIVE` | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#workload-evidence) |
| Full 89-row metric table represented compactly rather than expanded inline | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
