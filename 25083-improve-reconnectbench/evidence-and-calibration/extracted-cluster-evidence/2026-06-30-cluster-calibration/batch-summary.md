# 2026-06-30 Cluster Calibration Batch Summary

## Scope

| Item | Status | Source |
|---|---:|---|
| Summary source of truth | present | This file summarizes only the per-run Markdown evidence files in this batch directory. |
| Manifest source of truth | present | Raw artifact roots and manifest run IDs are owned by [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration). |
| Raw artifact extraction | not_applicable | Raw artifact values are extracted in [top-to-bottom.md](top-to-bottom.md), [two-phase-pessimistic.md](two-phase-pessimistic.md), and [parallel-sync.md](parallel-sync.md). |
| Summary discipline | present | Every comparison row below points back to per-run evidence sections. |

## Run Mapping

| Mode | Manifest batch | Manifest run | Per-run source |
|---|---|---|---|
| `pullTopToBottom` | [`2026-06-30-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration) | `top-to-bottom` | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullTwoPhasePessimistic` | [`2026-06-30-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration) | `two-phase-pessimistic` | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| `pullParallelSync` | [`2026-06-30-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration) | `parallel-sync` | [parallel-sync.md](parallel-sync.md#run-context) |

## Verification Status

| Check | Status | Source |
|---|---:|---|
| Verification notes file present | present | [verification-notes.md](verification-notes.md) |
| Per-run source-reference verification | present | Fresh verifier sub-agents checked all three per-run files; required corrections were incorporated. See [verification-notes.md](verification-notes.md). |
| Final verification status | present | Pass after corrections; [verification-notes.md](verification-notes.md) records verifier outcomes and follow-up edits. |

## Per-Mode Acceptance Summary

| mode | manifest batch | manifest run | commit | network disease preflight | network disease reason if failed | learner node | episode complete | episode incomplete reason | iteration count | complete catch-up start | complete catch-up end | complete catch-up duration | active confirmation | first iteration teacher node | first iteration start | first iteration end | first iteration duration | teacher reconnect context present | reconnect stats present | teacher/learner state size present | workload profile present | RTT evidence present | bandwidth evidence present | TCP/window evidence present | additional iterations observed | accepted for calibration | reason if not accepted | source |
|---|---|---|---|---|---|---:|---|---|---:|---|---|---:|---|---:|---|---|---:|---|---|---|---|---|---|---|---|---|---|---|
| `pullTopToBottom` | `2026-06-30-cluster-calibration` | `top-to-bottom` | `0cc709860be30d5892ba5fa70ed9300ce4107628` | pass | not applicable | `0` | yes | not applicable | `3` | `2026-06-30 00:21:08.306` | `2026-06-30 00:30:43.868` | `575.562 s` | `2026-06-30 00:33:36.552` | `6` | `2026-06-30 00:21:08.306` | `2026-06-30 00:27:30.674` | `382.368 s` | yes | yes | yes | yes | yes | yes | yes | yes | yes | not applicable | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode) |
| `pullTwoPhasePessimistic` | `2026-06-30-cluster-calibration` | `two-phase-pessimistic` | `0cc709860be30d5892ba5fa70ed9300ce4107628` | pass | not applicable | `0` | yes | not applicable | `40` | `2026-06-30 00:08:40.750` | `2026-06-30 05:26:06.913` | `19,046.163 s` | `2026-06-30 05:26:29.186` | `5` | `2026-06-30 00:08:40.750` | `2026-06-30 00:16:51.301` | `490.551 s` | partial, 27/40 teacher windows | yes | yes, partial teacher coverage | yes, limited | yes, CSV only | yes, CSV only | no | yes | no | Passive TCP/window evidence is absent, workload ends before final receiver finish/`ACTIVE`, and 13 teacher windows are missing. | [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode) |
| `pullParallelSync` | `2026-06-30-cluster-calibration` | `parallel-sync` | `0cc709860be30d5892ba5fa70ed9300ce4107628` | pass | not applicable | `0` | yes | not applicable | `4` | `2026-06-30 00:05:56.122` | `2026-06-30 00:19:39.127` | `823.005 s` | `2026-06-30 00:24:34.219` | `4` | `2026-06-30 00:05:56.122` | `2026-06-30 00:13:06.372` | `430.250 s` | yes | yes | yes | yes | yes | yes | yes | yes | yes | not applicable | [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) |

## Catch-Up Episode Summary

| Mode | Episode status | Iterations before `ACTIVE` | Complete catch-up start | Complete catch-up end | Complete catch-up duration | Active confirmation | Source |
|---|---:|---:|---|---|---:|---|---|
| `pullTopToBottom` | complete | `3` | `2026-06-30 00:21:08.306` | `2026-06-30 00:30:43.868` | `575.562 s` | `2026-06-30 00:33:36.552` | [top-to-bottom.md](top-to-bottom.md#reconnect-episodes-and-iterations) |
| `pullTwoPhasePessimistic` | complete, incomplete for full network calibration | `40` | `2026-06-30 00:08:40.750` | `2026-06-30 05:26:06.913` | `19,046.163 s` | `2026-06-30 05:26:29.186` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-episodes-and-iterations) |
| `pullParallelSync` | complete | `4` | `2026-06-30 00:05:56.122` | `2026-06-30 00:19:39.127` | `823.005 s` | `2026-06-30 00:24:34.219` | [parallel-sync.md](parallel-sync.md#reconnect-episodes-and-iterations) |

## Iteration Timing And State Gap Summary

Two-phase middle iterations are not expanded here because the per-run extraction records first/final state-gap anchors plus
complete catch-up coverage, not all 40 state-gap rows inline.

| Traversal order | Row | Iteration time | State size start | Target state size | Target minus start | Source |
|---|---|---:|---:|---:|---:|---|
| `pullTopToBottom` | iteration 1 | `382.368 s` | `294,597,233` | `304,720,427` | `10,123,194` | [top-to-bottom.md](top-to-bottom.md#reconnect-window-and-roles), [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTopToBottom` | iteration 2 | `116.248 s` | `304,720,427` | `305,935,865` | `1,215,438` | [top-to-bottom.md](top-to-bottom.md#reconnect-window-and-roles), [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTopToBottom` | iteration 3 | `60.637 s` | `305,935,865` | `306,331,790` | `395,925` | [top-to-bottom.md](top-to-bottom.md#reconnect-window-and-roles), [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTopToBottom` | complete catch-up | `575.562 s` | `294,597,233` | `306,331,790` | `11,734,557` | [top-to-bottom.md](top-to-bottom.md#reconnect-episodes-and-iterations), [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | iteration 1 | `490.551 s` | `294,590,652` | `303,660,501` | `9,069,849` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-window-and-roles), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | iteration 40 | `15.741 s` | `354,501,361` | `354,501,032` | `-329` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-window-and-roles), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | complete catch-up | `19,046.163 s` | `294,590,652` | `354,501,032` | `59,910,380` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-episodes-and-iterations), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullParallelSync` | iteration 1 | `430.250 s` | `294,604,932` | `304,701,795` | `10,096,863` | [parallel-sync.md](parallel-sync.md#reconnect-window-and-roles), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |
| `pullParallelSync` | iteration 2 | `163.863 s` | `304,701,795` | `306,079,122` | `1,377,327` | [parallel-sync.md](parallel-sync.md#reconnect-window-and-roles), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |
| `pullParallelSync` | iteration 3 | `114.139 s` | `306,079,122` | `306,617,246` | `538,124` | [parallel-sync.md](parallel-sync.md#reconnect-window-and-roles), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |
| `pullParallelSync` | iteration 4 | `91.635 s` | `306,617,246` | `306,993,222` | `375,976` | [parallel-sync.md](parallel-sync.md#reconnect-window-and-roles), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |
| `pullParallelSync` | complete catch-up | `823.005 s` | `294,604,932` | `306,993,222` | `12,388,290` | [parallel-sync.md](parallel-sync.md#reconnect-episodes-and-iterations), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |

## Network Disease Preflight Summary

| Mode | Status | Observation | Source |
|---|---:|---|---|
| `pullTopToBottom` | pass | Missing-parent evidence exists on peer logs, but no post-startup `ACTIVE -> CHECKING` evidence was found; fatal criteria not met. | [top-to-bottom.md](top-to-bottom.md#network-disease-preflight) |
| `pullTwoPhasePessimistic` | pass | No fatal disease found; two expected peer plain `swirlds.log` files are absent. | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-disease-preflight) |
| `pullParallelSync` | pass | No post-startup `ACTIVE -> CHECKING` transitions and no missing-parent evidence found. | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Cluster Network Evidence Summary

| Mode | RTT evidence | Bandwidth or throughput evidence | TCP/window evidence | Source |
|---|---|---|---|---|
| `pullTopToBottom` | CSV stats and passive socket RTT present | learner data/time, CSV send-rate, and passive socket context present | passive sampler fields overlap all three exact receiver iterations; connection-level caveat remains | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTwoPhasePessimistic` | CSV stats present | CSV send-rate and learner data/time present | missing; no passive sampler files or socket samples found | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |
| `pullParallelSync` | CSV stats and passive socket RTT present | learner data/time, CSV send-rate, and passive observed socket fields present | passive sampler fields overlap all four reconnect iterations | [parallel-sync.md](parallel-sync.md#network-evidence) |

## State And Divergence Summary

| Mode | Learner start size | First teacher target size | First state-size gap | Final/episode target size | Target equality verified | Service/store metrics | Divergence shape | Source |
|---|---:|---:|---:|---:|---:|---|---|---|
| `pullTopToBottom` | `294,597,233` leaves | `304,720,427` leaves | `10,123,194` leaves | `306,331,790` leaves after third iteration | yes, via learner target ranges and teacher roots | present | Growth-heavy multi-iteration reconnect with decreasing gaps, data MB, and dirty counters. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `294,590,652` leaves | `303,660,501` leaves | `9,069,849` leaves | `354,501,032` leaves after final iteration | yes, via learner target/final CSV | present | Long growth-heavy catch-up; final iteration is a tiny shrink/correction and aggregate counters show high clean leaf work. | [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullParallelSync` | `294,604,932` leaves | `304,701,795` leaves | `10,096,863` leaves | `306,993,222` leaves after fourth iteration | yes | present | Growth-heavy multi-iteration reconnect with decreasing gaps and decreasing dirty work. | [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) |

## Reconnect Work-Shape Summary

| Mode | Counter coverage | Leaf clean data | Leaf dirty data | Counter source |
|---|---|---:|---:|---|
| `pullTopToBottom` | three per-iteration rows | iter1 `90,357,040`; iter2 `17,689,297`; iter3 `6,201,246` | iter1 `51,569,191`; iter2 `7,105,988`; iter3 `2,410,344` | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters) |
| `pullTwoPhasePessimistic` | first, last, and aggregate 40-iteration rows | aggregate `12,432,836,830` | aggregate `387,232,629` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters) |
| `pullParallelSync` | four per-iteration rows | iter1 `27,847,605`; iter2 `5,177,918`; iter3 `2,145,374`; iter4 `1,538,244` | iter1 `51,454,920`; iter2 `8,023,830`; iter3 `3,236,770`; iter4 `2,297,804` | [parallel-sync.md](parallel-sync.md#reconnect-work-shape-counters) |

## Traversal Ordering Summary

| Ordering observation | Status | Source |
|---|---:|---|
| No three-mode traversal ordering should be derived from this batch. | present | [top-to-bottom.md](top-to-bottom.md#acceptance-notes), [two-phase-pessimistic.md](two-phase-pessimistic.md#acceptance-notes), [parallel-sync.md](parallel-sync.md#acceptance-notes) |
| `pullTopToBottom` and `pullParallelSync` are accepted for full calibration evidence in this batch. | present | [top-to-bottom.md](top-to-bottom.md#acceptance-notes), [parallel-sync.md](parallel-sync.md#acceptance-notes) |
| `pullTwoPhasePessimistic` is diagnostic/incomplete for full local network calibration because passive TCP/window evidence is absent, workload ends before final completion, and teacher-window coverage is partial. | present | [two-phase-pessimistic.md](two-phase-pessimistic.md#acceptance-notes) |

## Calibration Inputs For Local ReconnectBench

| cluster evidence | local ReconnectBench input | recommended value or sweep | source per-run files | confidence or gaps |
|---|---|---|---|---|
| Accepted top-to-bottom and parallel-sync CSV/passive RTT evidence | `networkLatencyMicroseconds` | Use accepted top-to-bottom and parallel-sync RTT as the 06-30 anchors; keep two-phase RTT diagnostic because that run is incomplete for full calibration. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) | Medium for accepted runs; two-phase incomplete. |
| Accepted top-to-bottom and parallel-sync reconnect throughput evidence | `networkBandwidthMegabitsPerSecond` | Use learner data/time and CSV send-rate from accepted top-to-bottom and parallel-sync as sweep anchors, not as link capacity. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium; observed rates are workload/socket behavior, not measured capacity. |
| Accepted top-to-bottom and parallel-sync passive TCP/window evidence | `networkInflightBytesLimit` | Use accepted top-to-bottom and parallel-sync passive sampler evidence as the 06-30 in-flight/backpressure anchors. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium; endpoint attribution is strong, but not frame-level reconnect-only telemetry. |
| State sizes and gaps | `numFiles * numRecords` target and divergence controls | Use accepted top-to-bottom and parallel-sync as full-evidence anchors; two-phase may inform high-state diagnostic ranges only. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) | Medium; runs are independent live-state histories. |
| Clean and dirty reconnect counters | local state-shape validation | Use top-to-bottom and parallel-sync per-iteration counters as accepted 06-30 validation targets; two-phase aggregate counters are diagnostic. | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters), [parallel-sync.md](parallel-sync.md#reconnect-work-shape-counters), [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters) | High for extracted logs. |

## Remaining Gaps

| Evidence gap | Affected modes | Source |
|---|---|---|
| Workflow-control files absent; stopped-pod timing inferred from reconnect logs/config only | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Baseline/restored-state upload evidence absent | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Passive TCP/window evidence absent | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Teacher windows missing because peer plain `swirlds.log` files are absent | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#teacher-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Workload does not continue through final receiver finish / `ACTIVE` | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#workload-evidence) |
| Full 40-row metric table represented compactly rather than expanded inline | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
