# 2026-07-01 Cluster Calibration Batch Summary

## Scope

| Item | Status | Source |
|---|---:|---|
| Summary source of truth | present | This file summarizes only the per-run Markdown evidence files in this batch directory. |
| Manifest source of truth | present | Raw artifact roots and manifest run IDs are owned by [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration). |
| Raw artifact extraction | not_applicable | Raw artifact values are extracted in [top-to-bottom.md](top-to-bottom.md), [two-phase-pessimistic.md](two-phase-pessimistic.md), and [parallel-sync.md](parallel-sync.md). |
| Summary discipline | present | Every comparison row below points back to per-run evidence sections. |

## Run Mapping

| Mode | Manifest batch | Manifest run | Per-run source |
|---|---|---|---|
| `pullTopToBottom` | [`2026-07-01-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration) | `top-to-bottom` | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullTwoPhasePessimistic` | [`2026-07-01-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration) | `two-phase-pessimistic` | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| `pullParallelSync` | [`2026-07-01-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration) | `parallel-sync` | [parallel-sync.md](parallel-sync.md#run-context) |

## Verification Status

| Check | Status | Source |
|---|---:|---|
| Verification notes file present | present | [verification-notes.md](verification-notes.md) |
| Per-run source-reference verification | present | Fresh verifier sub-agents checked all three per-run files and the batch/global rollup; required corrections were incorporated. See [verification-notes.md](verification-notes.md). |
| Final verification status | present | Pass after corrections and local structural checks; [verification-notes.md](verification-notes.md) records verifier outcomes and follow-up edits. |

## Per-Mode Acceptance Summary

| mode | manifest batch | manifest run | commit | network disease preflight | network disease reason if failed | learner node | episode complete | episode incomplete reason | iteration count | complete catch-up start | complete catch-up end | complete catch-up duration | active confirmation | first iteration teacher node | first iteration start | first iteration end | first iteration duration | teacher reconnect context present | reconnect stats present | teacher/learner state size present | workload profile present | RTT evidence present | bandwidth evidence present | TCP/window evidence present | additional iterations observed | accepted for calibration | reason if not accepted | source |
|---|---|---|---|---|---|---:|---|---|---:|---|---|---:|---|---:|---|---|---:|---|---|---|---|---|---|---|---|---|---|---|
| `pullTopToBottom` | `2026-07-01-cluster-calibration` | `top-to-bottom` | `0cc709860be30d5892ba5fa70ed9300ce4107628` | pass | not_applicable | `0` | yes | not_applicable | `3` | `2026-07-01 00:25:06.448` | `2026-07-01 00:37:52.970` | `766.522 s` | `2026-07-01 00:41:18.024` | `2` | `2026-07-01 00:25:06.448` | `2026-07-01 00:33:53.480` | `527.032 s` | yes | yes | yes | yes | yes | yes | partial/ambiguous for iter3 | yes | yes | not_applicable | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode) |
| `pullTwoPhasePessimistic` | `2026-07-01-cluster-calibration` | `two-phase-pessimistic` | `0cc709860be30d5892ba5fa70ed9300ce4107628` | pass | not_applicable | `0` | yes | not_applicable | `28` | `2026-07-01 00:31:26.780` | `2026-07-01 04:18:07.786` | `13,601.006 s` | `2026-07-01 04:18:28.825` | `3` | `2026-07-01 00:31:26.780` | `2026-07-01 00:40:47.633` | `560.853 s` | partial, 24/28 teacher windows | yes | yes, partial teacher coverage | yes, but not through final completion | yes | yes | partial, early only | yes | no | Passive TCP/window evidence is absent for later/final reconnect windows, workload ends before final receiver finish/`ACTIVE`, and four teacher windows are missing due absent peer log. | [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode) |
| `pullParallelSync` | `2026-07-01-cluster-calibration` | `parallel-sync` | `0cc709860be30d5892ba5fa70ed9300ce4107628` | pass | not_applicable | `0` | yes | not_applicable | `8` | `2026-07-01 00:19:55.595` | `2026-07-01 00:49:32.202` | `1,776.607 s` | `2026-07-01 00:54:27.859` | `5` | `2026-07-01 00:19:55.595` | `2026-07-01 00:31:51.682` | `716.087 s` | yes | yes | yes | yes | yes | yes | yes | yes | yes | not_applicable | [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) |

## Catch-Up Episode Summary

| Mode | Episode status | Iterations before `ACTIVE` | Complete catch-up start | Complete catch-up end | Complete catch-up duration | Active confirmation | Source |
|---|---:|---:|---|---|---:|---|---|
| `pullTopToBottom` | complete | `3` | `2026-07-01 00:25:06.448` | `2026-07-01 00:37:52.970` | `766.522 s` | `2026-07-01 00:41:18.024` | [top-to-bottom.md](top-to-bottom.md#reconnect-episodes-and-iterations) |
| `pullTwoPhasePessimistic` | complete, incomplete for full network calibration | `28` | `2026-07-01 00:31:26.780` | `2026-07-01 04:18:07.786` | `13,601.006 s` | `2026-07-01 04:18:28.825` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-episodes-and-iterations) |
| `pullParallelSync` | complete | `8` | `2026-07-01 00:19:55.595` | `2026-07-01 00:49:32.202` | `1,776.607 s` | `2026-07-01 00:54:27.859` | [parallel-sync.md](parallel-sync.md#reconnect-episodes-and-iterations) |

## Network Disease Preflight Summary

| Mode | Status | Observation | Source |
|---|---:|---|---|
| `pullTopToBottom` | present | Pass. No post-startup `ACTIVE -> CHECKING` and no missing-parent evidence were found in seven node logs. | [top-to-bottom.md](top-to-bottom.md#network-disease-preflight) |
| `pullTwoPhasePessimistic` | present | Pass. No fatal disease found; one peer plain `swirlds.log` file is absent. | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-disease-preflight) |
| `pullParallelSync` | present | Pass. Missing-parent evidence exists, but no post-startup `ACTIVE -> CHECKING` evidence was found, so fatal criteria are not met. | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Cluster Network Evidence Summary

| Mode | RTT evidence | Bandwidth or throughput evidence | TCP/window evidence | Source |
|---|---|---|---|---|
| `pullTopToBottom` | CSV stats and passive socket RTT present | learner data/time, CSV send-rate, and passive socket context present | passive sampler fields overlap exact iterations 1 and 2; iteration 3 is connection-level/ambiguous | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTwoPhasePessimistic` | CSV stats present | CSV send-rate and learner data/time present | partial only; passive sampler covers early catch-up but not middle, late, or final receiver windows | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |
| `pullParallelSync` | CSV stats and passive socket RTT present | learner data/time, CSV send-rate, and passive observed socket fields present | passive sampler fields overlap all eight reconnect iterations | [parallel-sync.md](parallel-sync.md#network-evidence) |

## State And Divergence Summary

| Mode | Learner start size | First teacher target size | First state-size gap | Final/episode target size | Target equality verified | Service/store metrics | Divergence shape | Source |
|---|---:|---:|---:|---:|---:|---|---|---|
| `pullTopToBottom` | `294,610,462` leaves | `320,220,709` leaves | `25,610,247` leaves | `322,243,786` leaves after third iteration | yes, via learner target ranges and teacher roots | present | Mixed modify-heavy plus append/growth-heavy, remove-light. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `294,615,073` leaves | `320,694,320` leaves | `26,079,247` leaves | `354,499,751` leaves after final iteration | yes for matched/learner ranges; partial teacher coverage | present | Long growth-heavy catch-up with final convergence to zero path-size gap. | [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullParallelSync` | `294,620,525` leaves | `319,844,811` leaves | `25,224,286` leaves | `324,731,094` leaves after eighth iteration | yes, via learner target ranges and teacher sent ranges | present | Growth-heavy repeated incremental catch-up. | [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |

## Reconnect Work-Shape Summary

| Mode | Counter coverage | Leaf clean data | Leaf dirty data | Counter source |
|---|---|---:|---:|---|
| `pullTopToBottom` | three per-iteration rows | aggregate `155,097,415` | aggregate `130,912,727` | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters) |
| `pullTwoPhasePessimistic` | selected rows plus aggregate 28-iteration sums | aggregate `8,744,541,887` | aggregate `357,388,055` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters) |
| `pullParallelSync` | eight per-iteration rows plus selected aggregates | aggregate `71,461,709` | aggregate `148,200,461` | [parallel-sync.md](parallel-sync.md#reconnect-work-shape-counters) |

## Traversal Ordering Summary

| Ordering observation | Status | Source |
|---|---:|---|
| No three-mode traversal ordering should be derived from this batch. | present | [top-to-bottom.md](top-to-bottom.md#acceptance-notes), [two-phase-pessimistic.md](two-phase-pessimistic.md#acceptance-notes), [parallel-sync.md](parallel-sync.md#acceptance-notes) |
| `pullTopToBottom` and `pullParallelSync` are accepted for calibration evidence in this batch. | present | [top-to-bottom.md](top-to-bottom.md#acceptance-notes), [parallel-sync.md](parallel-sync.md#acceptance-notes) |
| `pullTwoPhasePessimistic` is diagnostic/incomplete for full local network calibration because passive TCP/window evidence does not cover later/final windows, workload ends before final completion, and teacher-window coverage is partial. | present | [two-phase-pessimistic.md](two-phase-pessimistic.md#acceptance-notes) |

## Calibration Inputs For Local ReconnectBench

| cluster evidence | local ReconnectBench input | recommended value or sweep | source per-run files | confidence or gaps |
|---|---|---|---|---|
| Accepted top-to-bottom and parallel-sync CSV/passive RTT evidence | `networkLatencyMicroseconds` | Use accepted top-to-bottom and parallel-sync RTT as the 07-01 anchors; keep two-phase RTT diagnostic because later/final passive TCP/window evidence is incomplete. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) | Medium for accepted runs; two-phase incomplete. |
| Accepted top-to-bottom and parallel-sync reconnect throughput evidence | `networkBandwidthMegabitsPerSecond` | Use learner data/time and CSV send-rate from accepted top-to-bottom and parallel-sync as sweep anchors, not as link capacity. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium; observed rates are workload/socket behavior, not measured capacity. |
| Accepted passive TCP/window evidence | `networkInflightBytesLimit` | Use parallel-sync passive sampler evidence as the strongest 07-01 in-flight/backpressure anchor; use top-to-bottom as supporting evidence with iteration 3 attribution caveat. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium; samples are connection-level, not frame-level reconnect-only telemetry. |
| State sizes and gaps | `numFiles * numRecords` target and divergence controls | Use accepted top-to-bottom and parallel-sync as full-evidence anchors; two-phase may inform high-state diagnostic ranges only. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) | Medium; runs are independent live-state histories. |
| Clean and dirty reconnect counters | local state-shape validation | Use top-to-bottom and parallel-sync counters as accepted 07-01 validation targets; two-phase aggregate counters are diagnostic. | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters), [parallel-sync.md](parallel-sync.md#reconnect-work-shape-counters), [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters) | High for extracted learner logs. |

## Remaining Gaps

| Evidence gap | Affected modes | Source |
|---|---|---|
| Workflow-control files absent; stopped-pod timing inferred from reconnect logs/config only | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Baseline/restored-state upload evidence absent | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Passive socket attribution is connection-level rather than frame-level reconnect-only telemetry | top-to-bottom, parallel-sync | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) |
| Passive TCP/window evidence missing after early catch-up | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Teacher windows missing because peer plain `swirlds.log` is absent | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#teacher-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Workload does not continue through final receiver finish / `ACTIVE` | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#workload-evidence) |
