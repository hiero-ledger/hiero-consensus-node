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

| mode | artifact directory | commit | learner node | teacher node | first reconnect start | first reconnect end | learner duration | teacher reconnect context present | reconnect stats present | teacher/learner state size present | workload profile present | RTT evidence present | bandwidth evidence present | TCP/window evidence present | later reconnects observed | accepted for calibration | reason if not accepted | source |
|---|---|---|---:|---:|---|---|---:|---|---|---|---|---|---|---|---|---|---|---|
| `pullTopToBottom` | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect1` | `796905b12784f90d8b12b9ee0d9a6a91de0e9b85` | `0` | `2` | `2026-05-29 06:41:57.343` | `2026-05-29 06:45:00.693` | `183.350 s` | yes | yes | yes | yes | yes, via stats ping; passive endpoint attribution resolved | yes | present | no | yes | Not applicable; re-evaluated with TCP endpoint attribution resolved. | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode) |
| `pullTwoPhasePessimistic` | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect2_2phase/report` | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | `0` | `2` | `2026-05-29 18:26:08.709` | `2026-05-29 18:29:04.992` | `176.283 s` | yes | yes | yes | yes | yes, via stats ping; passive window missing | yes | missing | no | no | Passive TCP/window sampler coverage skips the first reconnect window, so network-inflight calibration is incomplete. | [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode) |
| `pullParallelSync` | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/NikitaReconnect3_PullParallelSync/report` | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | `0` | `1` | `2026-05-29 22:50:04.496` | `2026-05-29 22:56:15.846` | `371.350 s` | yes | yes | yes | yes | yes, via stats ping; passive endpoint attribution resolved where sampled | yes | ambiguous | yes | no | Passive TCP/window evidence is attributed but partial near the first reconnect finish, so network-inflight calibration is incomplete. | [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) |

## Cluster Network Evidence Summary

| Mode | RTT evidence | Bandwidth or throughput evidence | TCP/window evidence | Source |
|---|---|---|---|---|
| `pullTopToBottom` | stats ping present | learner data/time and teacher stats throughput present | passive sampler fields present and attributed to the learner/teacher socket pair | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTwoPhasePessimistic` | stats ping present | learner data/time and teacher stats throughput present | learner/teacher endpoint identity resolved, but passive sampler gap skips the first reconnect window | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |
| `pullParallelSync` | stats ping present | learner data/time and teacher stats throughput present | passive sampler fields are attributed where sampled, but end-of-window coverage remains partial | [parallel-sync.md](parallel-sync.md#network-evidence) |

## Full Node Metrics Cross-Check Summary

| Mode | Status | Observation | Source |
|---|---:|---|---|
| `pullTopToBottom` | present | Seven node stats CSVs are present; reconnect-window cross-check uses teacher node `2` and learner node `0` metrics. | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTwoPhasePessimistic` | ambiguous | Nodes 1-6 overlap the first reconnect window; node 7 stats end before the first reconnect window. | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| `pullParallelSync` | present | Seven node stats CSVs overlap the first reconnect window; teacher node `1` exposes learner-directed throughput, ping, and state-size columns. | [parallel-sync.md](parallel-sync.md#network-evidence) |

## State And Divergence Summary

| Mode | Learner start size | Learner target/end size | First teacher target size | First state-size gap | Target equality verified | Service/store metrics | Divergence shape | Source |
|---|---:|---:|---:|---:|---:|---|---|---|
| `pullTopToBottom` | `74090175` leaves | `81734059` leaves | `81734059` leaves | `7643884` leaves | yes | present | Growth-heavy reconnect with substantial clean and dirty leaf work. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `73728940` leaves | `80174849` leaves | `80174849` leaves | `6445909` leaves | yes | present | Growth-heavy reconnect with a substantial dirty component. | [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullParallelSync` | `73886545` leaves | `78191110` leaves | `78191110` leaves | `4304565` leaves | yes | present | Growth-heavy reconnect with immediate later growth pressure. | [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence), [parallel-sync.md](parallel-sync.md#later-reconnects) |

## Setup Control Evidence From Performance Logs

| Evidence item | Status | Observation | Source |
|---|---:|---|---|
| Restored baseline state upload | absent | All three workflow logs show Solo skipped `Upload state files network nodes`, so the cluster runs were not restored from a common uploaded baseline state. | [top-to-bottom.md](top-to-bottom.md#run-context), [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context), [parallel-sync.md](parallel-sync.md#run-context) |
| Reconnect loop controls | present | All three runs used `downtime=1800`, `warmtime=600`, and `NofLoops=0`. | [top-to-bottom.md](top-to-bottom.md#run-context), [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context), [parallel-sync.md](parallel-sync.md#run-context) |
| Nominal NLG controls | present | All three runs used `LongevityLoadTest`, `24000000` accounts, `6h`, and `-Dbenchmark.maxtps=8000`. | [top-to-bottom.md](top-to-bottom.md#run-context), [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context), [parallel-sync.md](parallel-sync.md#run-context) |
| Cross-mode state comparability | low | Identical nominal controls did not produce identical learner starts, teacher targets, gaps, or clean/dirty work shape. The runs are separate live-state histories, so first reconnect wall-clock duration is diagnostic evidence, not a controlled traversal-mode ranking. | [State And Divergence Summary](#state-and-divergence-summary), [Reconnect Work-Shape Summary](#reconnect-work-shape-summary) |

## First Reconnect Diagnostic Table

This table is useful for spotting cluster-run symptoms, but `sec / million gap leaves` is not a fair traversal normalization because the runs did not reuse the same state pair or work shape.

| Mode | Learner start | Learner target/end | Gap | Duration | sec / million gap leaves | RTT | Bandwidth / throughput | Workload TPS during reconnect | TCP evidence |
|---|---:|---:|---:|---:|---:|---|---|---|---|
| `pullTopToBottom` | `74090175` | `81734059` | `7643884` | `183.350 s` | `23.986` | Stats ping average `525.75 us`. | Learner lower-bound `25.162 MiB/s` / `211.076 Mbit/s`; teacher-to-learner stats average `22352583.89 B/s`, max `51816363.39 B/s`. | Aggregate `WorkingQueue` `TPS(current)` `10358..10415`; `TPS(EMA)` `10379..10380`. | Passive sampler fields present and attributed to the first learner/teacher socket pair. |
| `pullTwoPhasePessimistic` | `73728940` | `80174849` | `6445909` | `176.283 s` | `27.348` | Stats ping average `556.99 us`. | Learner lower-bound `23.138 MiB/s` / `194.092 Mbit/s`; teacher-to-learner stats average `22865350.21 B/s`, max `59004332.32 B/s`. | Transaction `TPS(current)` `9297..9486`; transaction `TPS(EMA)` `9441..9502`; receipt `TPS(current)` `8882..9901`. | No passive sampler block overlaps the first reconnect window; sampler gap skips the window. |
| `pullParallelSync` | `73886545` | `78191110` | `4304565` | `371.350 s` | `86.269` | Stats ping average `647.14 us`. | Learner lower-bound `8.161 MiB/s` / `68.461 Mbit/s`; teacher-to-learner stats average `7202969.59 B/s`, max `39370697.56 B/s`. | Transaction `TPS(current)` `2928..10708`; receipt `TPS(current)` `5191..18628`. | Passive sampler fields are attributed where sampled through most of the first window; end-of-window coverage remains unresolved. |

Sources: [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence), and [State And Divergence Summary](#state-and-divergence-summary).

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
| First learner duration order is two-phase pessimistic, then top-to-bottom, then parallel sync. | derived_but_not_causal | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode), [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode), [parallel-sync.md](parallel-sync.md#analysis-output-per-mode), [Setup Control Evidence From Performance Logs](#setup-control-evidence-from-performance-logs) |
| Parallel sync has an immediate second reconnect that is excluded from first traversal timing. | present | [parallel-sync.md](parallel-sync.md#later-reconnects) |

## Calibration Inputs For Local ReconnectBench

| cluster evidence | local ReconnectBench input | recommended value or sweep | source per-run files | confidence or gaps |
|---|---|---|---|---|
| Stats RTT evidence | `networkLatencyMicroseconds` | Use per-run stats ping values as initial latency anchors; passive endpoint identity is resolved for top-to-bottom and parallel where sampled, but two-phase lacks first-window passive samples and parallel lacks end-of-window coverage. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium for stats-ping modeling; limited for direct socket RTT where sampler coverage is missing or partial. |
| Reconnect throughput evidence | `networkBandwidthMegabitsPerSecond` | Use per-run lower-bound data/time and teacher stats throughput as sweep anchors, not as link capacity. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium for observed reconnect transfer behavior; not a true cluster link-capacity measurement. |
| TCP/window/backpressure evidence | `networkInflightBytesLimit` | Use top-to-bottom attributed first-window samples as a concrete anchor, but do not pick a single cross-mode value from these artifacts alone; keep two-phase missing first-window samples and parallel partial end-window coverage as sweep dimensions. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) | Medium for top-to-bottom attributed samples; low for cross-mode calibration until two-phase and parallel sampler coverage gaps are closed. |
| Teacher and learner state sizes | `numFiles * numRecords` target | Use per-run state-size ranges as separate target-size anchors; do not mix them into one traversal comparison. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence), [Setup Control Evidence From Performance Logs](#setup-control-evidence-from-performance-logs) | Medium for per-run anchors; low for cross-mode wall-clock comparison because no common restored baseline state was used. |
| State gap and workload profile | add/modify/remove probabilities or future divergence controls | Use extracted state gaps and workload profile to shape future divergence controls; do not derive final probabilities yet. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence), [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence), [top-to-bottom.md](top-to-bottom.md#workload-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#workload-evidence), [parallel-sync.md](parallel-sync.md#workload-evidence), [Setup Control Evidence From Performance Logs](#setup-control-evidence-from-performance-logs) | Medium for per-run divergence shape; low for final local mutation probabilities and cross-mode timing until a modeling step reproduces the same state size and shape locally. |
| Clean and dirty reconnect counters | local state-shape validation | Use per-run clean/dirty counters as validation targets for local benchmark shape. | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters), [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters), [parallel-sync.md](parallel-sync.md#reconnect-work-shape-counters) | High for extracted counter presence. |
| Cluster traversal ordering | local traversal-mode smoke check | Treat extracted first-duration ordering as a smoke-test observation only. A local traversal comparison must reproduce the same state size, state shape, gap, and workload shape before using wall-clock time as a target. | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode), [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode), [parallel-sync.md](parallel-sync.md#analysis-output-per-mode), [Setup Control Evidence From Performance Logs](#setup-control-evidence-from-performance-logs) | Low for causal ordering; observed cluster wall-clock order is not a valid traversal-mode ranking by itself. |

## Remaining Gaps

| Evidence gap | Affected modes | Source |
|---|---|---|
| Exact stopped-pod script output | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Common restored baseline state or identical state-shape control | all three modes | [Setup Control Evidence From Performance Logs](#setup-control-evidence-from-performance-logs) |
| Controlled learnerCandidate expected node | top-to-bottom and two-phase missing; parallel ambiguous | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Controlled teacherCandidate expected node | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Controlled learnerBehindDuration | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Full-run average transaction rate | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Full node metrics cross-check limitation | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Passive TCP/window samples during first reconnect | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Passive sampler end-of-first-window coverage | parallel-sync | [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
