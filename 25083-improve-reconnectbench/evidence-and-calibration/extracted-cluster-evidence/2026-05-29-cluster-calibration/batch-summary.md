# 2026-05-29 Cluster Calibration Batch Summary

## Scope

| Item | Status | Source |
|---|---:|---|
| Summary source of truth | present | This file summarizes only the per-run Markdown evidence files in this batch directory. |
| Manifest source of truth | present | Raw artifact roots and manifest run IDs are owned by [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration). |
| Raw artifact extraction | not_applicable | Raw artifact values are extracted in [top-to-bottom.md](top-to-bottom.md), [two-phase-pessimistic.md](two-phase-pessimistic.md), and [parallel-sync.md](parallel-sync.md). |
| Summary discipline | present | Every comparison row below points back to per-run evidence sections. |

## Run Mapping

| Mode | Manifest batch | Manifest run | Per-run source |
|---|---|---|---|
| `pullTopToBottom` | [`2026-05-29-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration) | `top-to-bottom` | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullTwoPhasePessimistic` | [`2026-05-29-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration) | `two-phase-pessimistic` | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| `pullParallelSync` | [`2026-05-29-cluster-calibration`](../../cluster-reconnectbench-artifact-manifest.md#2026-05-29-cluster-calibration) | `parallel-sync` | [parallel-sync.md](parallel-sync.md#run-context) |

## Endpoint Identity Map

Node ID follows the platform/log convention: `network-node1_logs` is node `0`, `network-node2_logs` is node `1`, and so on. Role is the role in the first reconnect window for that run.

| Mode | Node ID | Node name | Pod | Log directory | Pod IP | First reconnect role | Source |
|---|---:|---|---|---|---|---|---|
| `pullTopToBottom` | `0` | `network-node1` | `network-node1-0` | `network-node1_logs` | `10.36.24.218` | learner | [top-to-bottom.md](top-to-bottom.md#run-context), [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTopToBottom` | `1` | `network-node2` | `network-node2-0` | `network-node2_logs` | `10.36.22.146` | non-first-window peer | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullTopToBottom` | `2` | `network-node3` | `network-node3-0` | `network-node3_logs` | `10.36.7.246` | teacher | [top-to-bottom.md](top-to-bottom.md#run-context), [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTopToBottom` | `3` | `network-node4` | `network-node4-0` | `network-node4_logs` | `10.36.23.124` | non-first-window peer | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullTopToBottom` | `4` | `network-node5` | `network-node5-0` | `network-node5_logs` | `10.36.5.105` | non-first-window peer | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullTopToBottom` | `5` | `network-node6` | `network-node6-0` | `network-node6_logs` | `10.36.4.143` | non-first-window peer | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullTopToBottom` | `6` | `network-node7` | `network-node7-0` | `network-node7_logs` | `10.36.6.45` | non-first-window peer | [top-to-bottom.md](top-to-bottom.md#run-context) |
| `pullTwoPhasePessimistic` | `0` | `network-node1` | `network-node1-0` | `network-node1_logs` | `10.36.16.208` | learner | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |
| `pullTwoPhasePessimistic` | `1` | `network-node2` | `network-node2-0` | `network-node2_logs` | `10.36.28.152` | non-first-window peer | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| `pullTwoPhasePessimistic` | `2` | `network-node3` | `network-node3-0` | `network-node3_logs` | `10.36.68.154` | teacher | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |
| `pullTwoPhasePessimistic` | `3` | `network-node4` | `network-node4-0` | `network-node4_logs` | `10.36.27.27` | non-first-window peer | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| `pullTwoPhasePessimistic` | `4` | `network-node5` | `network-node5-0` | `network-node5_logs` | `10.36.9.166` | non-first-window peer | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| `pullTwoPhasePessimistic` | `5` | `network-node6` | `network-node6-0` | `network-node6_logs` | `10.36.11.147` | non-first-window peer | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| `pullTwoPhasePessimistic` | `6` | `network-node7` | `network-node7-0` | `network-node7_logs` | `10.36.63.147` | non-first-window peer | [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| `pullParallelSync` | `0` | `network-node1` | `network-node1-0` | `network-node1_logs` | `10.36.68.227` | learner | [parallel-sync.md](parallel-sync.md#run-context), [parallel-sync.md](parallel-sync.md#network-evidence) |
| `pullParallelSync` | `1` | `network-node2` | `network-node2-0` | `network-node2_logs` | `10.36.16.71` | teacher | [parallel-sync.md](parallel-sync.md#run-context), [parallel-sync.md](parallel-sync.md#network-evidence) |
| `pullParallelSync` | `2` | `network-node3` | `network-node3-0` | `network-node3_logs` | `10.36.9.138` | non-first-window peer | [parallel-sync.md](parallel-sync.md#run-context) |
| `pullParallelSync` | `3` | `network-node4` | `network-node4-0` | `network-node4_logs` | `10.36.27.241` | non-first-window peer | [parallel-sync.md](parallel-sync.md#run-context) |
| `pullParallelSync` | `4` | `network-node5` | `network-node5-0` | `network-node5_logs` | `10.36.28.146` | second-reconnect teacher, excluded from first-window timing | [parallel-sync.md](parallel-sync.md#run-context), [parallel-sync.md](parallel-sync.md#reconnect-episodes-and-iterations) |
| `pullParallelSync` | `5` | `network-node6` | `network-node6-0` | `network-node6_logs` | `10.36.11.79` | non-first-window peer | [parallel-sync.md](parallel-sync.md#run-context) |
| `pullParallelSync` | `6` | `network-node7` | `network-node7-0` | `network-node7_logs` | `10.36.63.182` | non-first-window peer | [parallel-sync.md](parallel-sync.md#run-context) |

## Verification Status

| Check | Status | Source |
|---|---:|---|
| Verification notes file present | present | [verification-notes.md](verification-notes.md#scope) |
| Per-run source-reference verification | ambiguous | [verification-notes.md](verification-notes.md#per-run-results) |
| Source reference failures | present | [verification-notes.md](verification-notes.md#source-reference-failures) |
| Final verification status | ambiguous | [verification-notes.md](verification-notes.md#final-verification-status) |

## Per-Mode Acceptance Summary

| mode | manifest batch | manifest run | commit | network disease preflight | network disease reason if failed | learner node | episode complete | episode incomplete reason | iteration count | complete catch-up start | complete catch-up end | complete catch-up duration | active confirmation | first iteration teacher node | first iteration start | first iteration end | first iteration duration | teacher reconnect context present | reconnect stats present | teacher/learner state size present | workload profile present | RTT evidence present | bandwidth evidence present | TCP/window evidence present | additional iterations observed | accepted for calibration | reason if not accepted | source |
|---|---|---|---|---|---|---:|---|---|---:|---|---|---:|---|---:|---|---|---:|---|---|---|---|---|---|---|---|---|---|---|
| `pullTopToBottom` | `2026-05-29-cluster-calibration` | `top-to-bottom` | `796905b12784f90d8b12b9ee0d9a6a91de0e9b85` | pass | not applicable | `0` | yes | not applicable | `1` | `2026-05-29 06:41:57.343` | `2026-05-29 06:45:00.693` | `183.350 s` | `2026-05-29 06:54:45.837` | `2` | `2026-05-29 06:41:57.343` | `2026-05-29 06:45:00.693` | `183.350 s` | yes | yes | yes | yes | yes, via stats ping; passive endpoint attribution resolved | yes | present | no | yes | Not applicable; re-evaluated with TCP endpoint attribution resolved. | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode) |
| `pullTwoPhasePessimistic` | `2026-05-29-cluster-calibration` | `two-phase-pessimistic` | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | pass | not applicable; missing-parent evidence diagnostic only | `0` | yes | not applicable | `1` | `2026-05-29 18:26:08.709` | `2026-05-29 18:29:04.992` | `176.283 s` | `2026-05-29 18:36:13.782` | `2` | `2026-05-29 18:26:08.709` | `2026-05-29 18:29:04.992` | `176.283 s` | yes | yes | yes | yes | yes, via stats ping; passive window missing | yes | missing | no | no | Passive TCP/window sampler coverage skips the first reconnect window, so network-inflight calibration is incomplete. | [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode) |
| `pullParallelSync` | `2026-05-29-cluster-calibration` | `parallel-sync` | `eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2` | fatal | `NETWORK_DISEASE_FATAL` | `0` | yes, diagnostic only | not applicable | `2` | `2026-05-29 22:50:04.496`, diagnostic only | `2026-05-29 22:59:43.644`, diagnostic only | `579.148 s`, diagnostic only | `2026-05-29 23:03:54.174`, diagnostic only | `1` | `2026-05-29 22:50:04.496` | `2026-05-29 22:56:15.846` | `371.350 s`, diagnostic only | yes, diagnostic only | yes, diagnostic only | yes, diagnostic only | yes, diagnostic only | diagnostic only | diagnostic only | diagnostic only | yes, second receiver reconnect before `ACTIVE` | no | `NETWORK_DISEASE_FATAL`; post-startup `ACTIVE -> CHECKING` churn is corroborated by widespread missing-parent evidence. | [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) |

## Catch-Up Episode Summary

| Mode | Episode status | Iterations before `ACTIVE` | Complete catch-up start | Complete catch-up end | Complete catch-up duration | Active confirmation | Source |
|---|---:|---:|---|---|---:|---|---|
| `pullTopToBottom` | complete | `1` | `2026-05-29 06:41:57.343` | `2026-05-29 06:45:00.693` | `183.350 s` | `2026-05-29 06:54:45.837` | [top-to-bottom.md](top-to-bottom.md#reconnect-episodes-and-iterations) |
| `pullTwoPhasePessimistic` | complete | `1` | `2026-05-29 18:26:08.709` | `2026-05-29 18:29:04.992` | `176.283 s` | `2026-05-29 18:36:13.782` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-episodes-and-iterations) |
| `pullParallelSync` | complete, diagnostic only | `2` | `2026-05-29 22:50:04.496` | `2026-05-29 22:59:43.644` | `579.148 s`, diagnostic only | `2026-05-29 23:03:54.174` | [parallel-sync.md](parallel-sync.md#reconnect-episodes-and-iterations), [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Network Disease Preflight Summary

| Mode | Status | Observation | Source |
|---|---:|---|---|
| `pullTopToBottom` | pass | No post-startup `ACTIVE -> CHECKING` transitions and no missing-parent evidence found in the retrospective scan. | [top-to-bottom.md](top-to-bottom.md#network-disease-preflight) |
| `pullTwoPhasePessimistic` | pass | Missing-parent evidence is present, but no post-startup `ACTIVE -> CHECKING` transitions were found; missing-parent evidence alone is diagnostic, not fatal. | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-disease-preflight) |
| `pullParallelSync` | fatal | `NETWORK_DISEASE_FATAL`: node7 has repeated post-startup `ACTIVE -> CHECKING -> ACTIVE` churn, and missing-parent evidence is widespread. The artifact should be re-run. | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Cluster Network Evidence Summary

| Mode | RTT evidence | Bandwidth or throughput evidence | TCP/window evidence | Source |
|---|---|---|---|---|
| `pullTopToBottom` | stats ping present | learner data/time and teacher stats throughput present | passive sampler fields present and attributed to the learner/teacher socket pair | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTwoPhasePessimistic` | stats ping present | learner data/time and teacher stats throughput present | learner/teacher endpoint identity resolved, but passive sampler gap skips the first reconnect window | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |
| `pullParallelSync` | diagnostic only; run invalidated by network disease | diagnostic only; run invalidated by network disease | diagnostic only; run invalidated by network disease | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Attributed TCP Socket Samples

Values are selected raw `ss -tin` samples for the first learner/teacher socket pair. `rtt`, `minrtt`, and `rwnd_limited` values are recorded in the units emitted by `ss`. Two-phase samples are explicitly post-window examples and are not calibration evidence for the first reconnect.

| Mode | Calibration use | Timestamp | Sample side | Socket | Recv-Q | Send-Q | rtt | cwnd/ssthresh | bytes_sent | bytes_retrans | delivery_rate | rwnd_limited | snd_wnd | Source |
|---|---|---|---|---|---:|---:|---|---|---:|---:|---:|---|---:|---|
| `pullTopToBottom` | first-window start anchor | `2026-05-29T06:41:57Z` | learner | `[::ffff:10.36.24.218]:41024 -> [::ffff:10.36.7.246]:50111` | `0` | `0` | `3.611/6.298` | `10/not_emitted` | `1923` | not_emitted | `151424832bps` | not_emitted | `31744` | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTopToBottom` | first-window end anchor | `2026-05-29T06:44:59Z` | learner | `[::ffff:10.36.24.218]:41024 -> [::ffff:10.36.7.246]:50111` | `12938354` | `1205840` | `0.244/0.068` | `161/120` | `6058178797` | `310935` | `4565458816bps` | `151024ms(88.8%)` | `1024` | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTopToBottom` | first-window end cross-check | `2026-05-29T06:45:00Z` | teacher | `[::ffff:10.36.7.246]:50111 -> [::ffff:10.36.24.218]:41024` | `0` | `0` | `5.745/11.152` | `10/60` | `5039032007` | `33694` | `735492056bps` | `109156ms(62.9%)` | `31744` | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTwoPhasePessimistic` | post-window only, not first-window calibration | `2026-05-29T18:35:54Z` | learner | `[::ffff:10.36.16.208]:56440 -> [::ffff:10.36.68.154]:50111` | `0` | `0` | `10.126/12.639` | `10/108` | `5570169979` | `162052` | `289600000bps` | `121792ms(51.7%)` | `1302528` | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |
| `pullTwoPhasePessimistic` | post-window only, not first-window calibration | `2026-05-29T18:36:12Z` | teacher | `[::ffff:10.36.68.154]:50111 -> [::ffff:10.36.16.208]:56440` | `0` | `0` | `3.26/6.117` | `26/18` | `6240463923` | `1557813` | `1210268656bps` | `143012ms(61.0%)` | `31744` | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) |
| `pullParallelSync` | diagnostic only; `NETWORK_DISEASE_FATAL` | `2026-05-29T22:50:08Z` | learner | `[::ffff:10.36.68.227]:48896 -> [::ffff:10.36.16.71]:50111` | `0` | `0` | `1.211/1.556` | `10/not_emitted` | `2104` | not_emitted | `131636360bps` | not_emitted | `31744` | [parallel-sync.md](parallel-sync.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight) |
| `pullParallelSync` | diagnostic only; `NETWORK_DISEASE_FATAL` | `2026-05-29T22:50:08Z` | teacher | `[::ffff:10.36.16.71]:50111 -> [::ffff:10.36.68.227]:48896` | `0` | `0` | `0.802/0.737` | `10/not_emitted` | `5071` | not_emitted | `229386136bps` | not_emitted | `31744` | [parallel-sync.md](parallel-sync.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight) |
| `pullParallelSync` | diagnostic only; `NETWORK_DISEASE_FATAL` | `2026-05-29T22:55:33Z` | teacher | `[::ffff:10.36.16.71]:50111 -> [::ffff:10.36.68.227]:48896` | `914150` | `266` | `0.08/0.011` | `20/16` | `2971488276` | `808246` | `498236552bps` | `20976ms(6.5%)` | `31744` | [parallel-sync.md](parallel-sync.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight) |
| `pullParallelSync` | diagnostic only; `NETWORK_DISEASE_FATAL` | `2026-05-29T22:55:38Z` | learner | `[::ffff:10.36.68.227]:48896 -> [::ffff:10.36.16.71]:50111` | `0` | `0` | `6.426/8.663` | `22/128` | `5586559434` | `5614570` | `1300244896bps` | `47080ms(7.6%)` | `1128448` | [parallel-sync.md](parallel-sync.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Full Node Metrics Cross-Check Summary

| Mode | Status | Observation | Source |
|---|---:|---|---|
| `pullTopToBottom` | present | Seven node stats CSVs are present; reconnect-window cross-check uses teacher node `2` and learner node `0` metrics. | [top-to-bottom.md](top-to-bottom.md#network-evidence) |
| `pullTwoPhasePessimistic` | ambiguous | Nodes 1-6 overlap the first reconnect window; node 7 stats end before the first reconnect window. | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| `pullParallelSync` | diagnostic_only | Seven node stats CSVs overlap the first reconnect window, but the run is invalidated by network disease. | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## State And Divergence Summary

| Mode | Learner start size | Learner target/end size | First teacher target size | First state-size gap | Target equality verified | Service/store metrics | Divergence shape | Source |
|---|---:|---:|---:|---:|---:|---|---|---|
| `pullTopToBottom` | `74090175` leaves | `81734059` leaves | `81734059` leaves | `7643884` leaves | yes | present | Growth-heavy reconnect with substantial clean and dirty leaf work. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `73728940` leaves | `80174849` leaves | `80174849` leaves | `6445909` leaves | yes | present | Growth-heavy reconnect with a substantial dirty component. | [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullParallelSync` | `73886545` leaves, diagnostic only | `78191110` leaves, diagnostic only | `78191110` leaves, diagnostic only | `4304565` leaves, diagnostic only | yes, diagnostic only | diagnostic only | Diagnostic-only; run invalidated by network disease. | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Setup Control Evidence From Performance Logs

| Evidence item | Status | Observation | Source |
|---|---:|---|---|
| Restored baseline state upload | absent | All three workflow logs show Solo skipped `Upload state files network nodes`. This is expected for independent cluster workflow runs and is not a remaining extraction gap. | [top-to-bottom.md](top-to-bottom.md#run-context), [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context), [parallel-sync.md](parallel-sync.md#run-context) |
| Reconnect loop controls | present | All three runs used `downtime=1800`, `warmtime=600`, and `NofLoops=0`. | [top-to-bottom.md](top-to-bottom.md#run-context), [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context), [parallel-sync.md](parallel-sync.md#run-context) |
| Nominal NLG controls | present | All three runs used `LongevityLoadTest`, `24000000` accounts, `6h`, and `-Dbenchmark.maxtps=8000`. | [top-to-bottom.md](top-to-bottom.md#run-context), [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context), [parallel-sync.md](parallel-sync.md#run-context) |
| Independent-run interpretation | present | Identical nominal controls did not produce identical learner starts, teacher targets, gaps, or clean/dirty work shape. This is expected for independent live-state histories. Use each run as a separate calibration anchor; traversal-mode ranking belongs in local `ReconnectBench` runs that reproduce comparable state size, gap, work shape, and network profile. | [State And Divergence Summary](#state-and-divergence-summary), [Reconnect Work-Shape Summary](#reconnect-work-shape-summary) |

## First Reconnect Diagnostic Table

This table is useful for spotting cluster-run symptoms, but `sec / million gap leaves` is not a fair traversal normalization because the runs did not reuse the same state pair or work shape.

| Mode | Learner start | Learner target/end | Gap | Duration | sec / million gap leaves | RTT | Bandwidth / throughput | Workload TPS during reconnect | TCP evidence |
|---|---:|---:|---:|---:|---:|---|---|---|---|
| `pullTopToBottom` | `74090175` | `81734059` | `7643884` | `183.350 s` | `23.986` | Stats ping average `525.75 us`. | Learner lower-bound `25.162 MiB/s` / `211.076 Mbit/s`; teacher-to-learner stats average `22352583.89 B/s`, max `51816363.39 B/s`. | Aggregate `WorkingQueue` `TPS(current)` `10358..10415`; `TPS(EMA)` `10379..10380`. | Passive sampler fields present and attributed to the first learner/teacher socket pair. |
| `pullTwoPhasePessimistic` | `73728940` | `80174849` | `6445909` | `176.283 s` | `27.348` | Stats ping average `556.99 us`. | Learner lower-bound `23.138 MiB/s` / `194.092 Mbit/s`; teacher-to-learner stats average `22865350.21 B/s`, max `59004332.32 B/s`. | Transaction `TPS(current)` `9297..9486`; transaction `TPS(EMA)` `9441..9502`; receipt `TPS(current)` `8882..9901`. | No passive sampler block overlaps the first reconnect window; sampler gap skips the window. |
| `pullParallelSync` | `73886545` | `78191110` | `4304565` | `371.350 s`, diagnostic only | diagnostic only | Diagnostic only; run invalidated by `NETWORK_DISEASE_FATAL`. | Diagnostic only; run invalidated by `NETWORK_DISEASE_FATAL`. | Diagnostic only; run invalidated by `NETWORK_DISEASE_FATAL`. | Diagnostic only; run invalidated by `NETWORK_DISEASE_FATAL`. |

Sources: [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight), and [State And Divergence Summary](#state-and-divergence-summary).

## Service Store Size Metrics Summary

| Mode | accountsUsed | contractsUsed | nftsUsed | tokenAssociationsUsed | tokensUsed | topicsUsed | Source |
|---|---:|---:|---:|---:|---:|---:|---|
| `pullTopToBottom` | `24000712` | `6` | `24000000` | `1796550` | `1000` | `100000` | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence) |
| `pullTwoPhasePessimistic` | `24000712` | `6` | `24000000` | `1469889` | `1000` | `100000` | [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| `pullParallelSync` | `24000712`, diagnostic only | `6`, diagnostic only | `24000000`, diagnostic only | `1611530`, diagnostic only | `1000`, diagnostic only | `100000`, diagnostic only | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Reconnect-Window Transaction Rate Summary

| Mode | Status | Observed reconnect-window transaction-rate evidence | Source |
|---|---:|---|---|
| `pullTopToBottom` | present | Aggregate `WorkingQueue` `TPS(current)` `10358..10415`; `TPS(EMA)` `10379..10380`. | [top-to-bottom.md](top-to-bottom.md#workload-evidence) |
| `pullTwoPhasePessimistic` | present | Transaction `TPS(current)` `9297..9486`; transaction `TPS(EMA)` `9441..9502`; receipt `TPS(current)` `8882..9901`. | [two-phase-pessimistic.md](two-phase-pessimistic.md#workload-evidence) |
| `pullParallelSync` | diagnostic_only | Transaction-rate evidence was extracted, but the run is invalidated by network disease and must not be used for calibration. | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Reconnect Work-Shape Summary

| Mode | Leaf clean data | Leaf dirty data | Counter source |
|---|---:|---:|---|
| `pullTopToBottom` | `36037864` | `35396063` | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters) |
| `pullTwoPhasePessimistic` | `12846292` | `30002441` | [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters) |
| `pullParallelSync` | `10163254`, diagnostic only | `20961464`, diagnostic only | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |

## Traversal Ordering Summary

| Ordering observation | Status | Source |
|---|---:|---|
| No valid three-mode traversal ordering can be derived from these artifacts. | present | [parallel-sync.md](parallel-sync.md#network-disease-preflight), [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode), [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode) |
| Parallel sync timing and multi-iteration reconnect evidence are diagnostic-only because the run is `NETWORK_DISEASE_FATAL`. | fatal | [parallel-sync.md](parallel-sync.md#network-disease-preflight), [parallel-sync.md](parallel-sync.md#reconnect-episodes-and-iterations) |

## Calibration Inputs For Local ReconnectBench

| cluster evidence | local ReconnectBench input | recommended value or sweep | source per-run files | confidence or gaps |
|---|---|---|---|---|
| Stats RTT evidence | `networkLatencyMicroseconds` | Use top-to-bottom and two-phase stats ping values as initial latency anchors; exclude parallel-sync because it is `NETWORK_DISEASE_FATAL`. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight) | Medium for stats-ping modeling from non-diseased artifacts; limited for direct socket RTT where sampler coverage is missing. |
| Reconnect throughput evidence | `networkBandwidthMegabitsPerSecond` | Use non-diseased per-run lower-bound data/time and teacher stats throughput as sweep anchors, not as link capacity; exclude parallel-sync. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight) | Medium for observed reconnect transfer behavior in non-diseased artifacts; not a true cluster link-capacity measurement. |
| TCP/window/backpressure evidence | `networkInflightBytesLimit` | Use top-to-bottom attributed first-window samples as the only concrete anchor from this batch; two-phase lacks first-window samples and parallel-sync is invalidated. | [top-to-bottom.md](top-to-bottom.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight) | Medium for top-to-bottom attributed samples; low for cross-mode calibration until a healthy replacement parallel-sync run exists and two-phase sampler coverage is closed. |
| Teacher and learner state sizes | `numFiles * numRecords` target | Use non-diseased state-size ranges as separate target-size anchors; exclude parallel-sync from calibration targets. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight), [Setup Control Evidence From Performance Logs](#setup-control-evidence-from-performance-logs) | Medium for non-diseased per-run anchors; a healthy parallel-sync replacement is needed before comparing all traversal modes. |
| State gap and workload profile | add/modify/remove probabilities or future divergence controls | Use non-diseased extracted state gaps and workload profile to shape future divergence controls; exclude parallel-sync. | [top-to-bottom.md](top-to-bottom.md#state-and-divergence-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence), [top-to-bottom.md](top-to-bottom.md#workload-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#workload-evidence), [parallel-sync.md](parallel-sync.md#network-disease-preflight), [Setup Control Evidence From Performance Logs](#setup-control-evidence-from-performance-logs) | Medium for non-diseased divergence shape; derive final local mutation probabilities only after local modeling reproduces target state size and shape. |
| Clean and dirty reconnect counters | local state-shape validation | Use non-diseased clean/dirty counters as validation targets; exclude parallel-sync counters from calibration. | [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters), [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-work-shape-counters), [parallel-sync.md](parallel-sync.md#network-disease-preflight) | High for extracted counter presence in non-diseased artifacts; incomplete for all-mode comparison until parallel-sync is re-run. |
| Cluster traversal ordering | local traversal-mode smoke check | Do not use this artifact batch for traversal ordering. Parallel-sync is `NETWORK_DISEASE_FATAL`, and two-phase remains rejected for missing passive TCP/window samples. | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode), [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode), [parallel-sync.md](parallel-sync.md#network-disease-preflight), [Setup Control Evidence From Performance Logs](#setup-control-evidence-from-performance-logs) | Low for causal ordering; a healthy replacement parallel-sync run is required. |

## Remaining Gaps

| Evidence gap | Affected modes | Source |
|---|---|---|
| Direct stopped-pod script output absent, stoppedPod inferred as `network-node1-0` | all three modes | [top-to-bottom.md](top-to-bottom.md#unresolved-evidence-register), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) |
| Full node metrics cross-check limitation | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Passive TCP/window samples during first reconnect | two-phase pessimistic | [two-phase-pessimistic.md](two-phase-pessimistic.md#unresolved-evidence-register) |
| Fatal network disease | parallel-sync | [parallel-sync.md](parallel-sync.md#network-disease-preflight) |
| Passive sampler end-of-first-window coverage, diagnostic only after fatal preflight | parallel-sync | [parallel-sync.md](parallel-sync.md#unresolved-evidence-register), [parallel-sync.md](parallel-sync.md#network-disease-preflight) |
