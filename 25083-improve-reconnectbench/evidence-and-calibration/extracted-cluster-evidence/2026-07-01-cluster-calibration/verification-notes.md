# 2026-07-01 Cluster Calibration Verification Notes

## Scope

| Item | Status | Source |
|---|---:|---|
| Batch verified | present | `2026-07-01-cluster-calibration` |
| Verification method | present | Fresh read-only verifier sub-agents checked each per-run extraction and the batch/global summary after extraction. |
| Artifact protocol | present | [cluster-reconnectbench-artifact-processing-protocol.md](../../cluster-reconnectbench-artifact-processing-protocol.md) |
| Extraction strategy | present | [agentic-evidence-extraction-strategy.md](../../agentic-evidence-extraction-strategy.md) |
| Manifest | present | [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration) |

## Verification Method

| Check | Status | Source |
|---|---:|---|
| Sub-agent extraction pass | present | Dedicated extraction sub-agents processed preflight, reconnect anchors, logs/counters, workload/config, stats CSV, passive network, and state/divergence families before file assembly. |
| Sub-agent verification pass | present | Fresh verifier sub-agents checked [top-to-bottom.md](top-to-bottom.md), [two-phase-pessimistic.md](two-phase-pessimistic.md), [parallel-sync.md](parallel-sync.md), and [batch-summary.md](batch-summary.md). |
| Local structural checks | present | `rg` checks confirmed no uppercase status cells and one absolute artifact root per per-run file before closeout; final checks were run after corrections. |

## Per-Run Results

| Verifier | Scope | Initial result | Required correction | Final disposition |
|---|---|---:|---|---:|
| Hilbert | [top-to-bottom.md](top-to-bottom.md) | fail | Update passive sampler inventory endpoint refs and include all node sampler refs; all substantive raw-value checks passed. | pass after source-reference correction |
| Wegener | [two-phase-pessimistic.md](two-phase-pessimistic.md) | fail | Update passive sampler end-of-coverage refs. A section-order finding was adjudicated against the strategy-mandated output order. | pass after source-reference correction and adjudication |
| Ohm | [parallel-sync.md](parallel-sync.md) | fail | Mark teacher root-response lines as present under `TeachingSynchronizer`, update CSV row refs for RTT/send-rate and state snapshots, and remove the root-response unresolved gap. | pass after source-reference and status corrections |
| Euclid | [batch-summary.md](batch-summary.md), manifest, global summary | fail | Align two-phase rejection reason with per-run wording, add per-run support for top-to-bottom aggregate summary values, add explicit `Episode incomplete reason` rows, update manifest/global closeout. | pass after corrections |

## Source Reference Failures

| File | Initial failure | Correction applied |
|---|---|---|
| [top-to-bottom.md](top-to-bottom.md#network-evidence) | Passive sampler inventory cited ranges ending before the claimed coverage endpoint. | Updated inventory refs to `network_sampler_network-node1-0.log:1-6470` and `network_sampler_network-node2/3/4/5/6/7-0.log:1-8300`. |
| [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) | Passive sampler inventory cited only early ranges while claiming coverage through `02:05:17Z`. | Updated refs to include end-of-coverage lines `network-node1-0_network_sampler.log:34633-34646` and `network-node4-0_network_sampler.log:37133-37146`. |
| [parallel-sync.md](parallel-sync.md#teacher-evidence) | Teacher root-response evidence was marked missing because the wrong logger string was searched. | Marked present and cited all eight `TeachingSynchronizer: Teacher sending root node response` refs. |
| [parallel-sync.md](parallel-sync.md#network-evidence) | CSV row refs were adjacent rows that did not reproduce stated values. | Updated refs to `MainNetStats0.csv:rows=77-315`, `MainNetStats5.csv:rows=6188-6426`, `MainNetStats0.csv:rows=638-668`, and `MainNetStats3.csv:rows=6749-6780`. |
| [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) | Learner state snapshot refs needed row offsets for exact stated values. | Updated refs to include rows `77`, `669`, and `767` for the stated learner `vmap_size_state` values. |

## Ambiguous Or Unresolved Items

| Item | Status | Resolution |
|---|---:|---|
| Two-phase section-order verifier finding | ambiguous | No file reorder applied. The user-provided extraction order and [agentic-evidence-extraction-strategy.md](../../agentic-evidence-extraction-strategy.md) place `Reconnect Episodes And Iterations` after `State And Divergence Evidence`, matching the current per-run files and prior batches. |
| Top-to-bottom passive iteration 3 attribution | ambiguous | Kept as connection-level context, not fresh reconnect-only proof. The run remains accepted with this calibration caveat. |
| Two-phase calibration acceptance | present | Kept rejected/diagnostic because passive TCP/window evidence does not cover later/final reconnect windows, workload ends before final completion, and teacher-window coverage is partial. |
| Passive socket telemetry granularity | ambiguous | Kept as a general caveat for accepted runs: sampler rows are connection-level and timestamped at whole-second precision, not frame-level reconnect telemetry. |

## Corrections Required

| File | Correction | Source |
|---|---|---|
| [top-to-bottom.md](top-to-bottom.md#reconnect-work-shape-counters) | Added explicit derived aggregate counter rows for `transfersFromTeacher`, `leafCleanData`, and `leafDirtyData` so [batch-summary.md](batch-summary.md) does not introduce summary-only values. | Verifier Euclid |
| [top-to-bottom.md](top-to-bottom.md#network-evidence) | Updated passive sampler inventory endpoint refs. | Verifier Hilbert |
| [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode) | Added explicit `Episode incomplete reason` row. | Verifier Euclid |
| [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence) | Updated passive sampler inventory end-of-coverage refs. | Verifier Wegener |
| [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode) | Added explicit `Episode incomplete reason` row. | Verifier Euclid |
| [parallel-sync.md](parallel-sync.md#teacher-evidence) | Corrected teacher root-response evidence from `missing` to `present` and cited all eight refs. | Verifier Ohm |
| [parallel-sync.md](parallel-sync.md#network-evidence) | Corrected CSV row refs for RTT/send-rate values. | Verifier Ohm |
| [parallel-sync.md](parallel-sync.md#state-and-divergence-evidence) | Corrected CSV row refs for learner state snapshots and teacher growth examples. | Verifier Ohm |
| [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) | Added explicit `Episode incomplete reason` row. | Verifier Euclid |
| [parallel-sync.md](parallel-sync.md#unresolved-evidence-register) | Removed the root-response unresolved gap after evidence was found. | Verifier Ohm |
| [batch-summary.md](batch-summary.md#per-mode-acceptance-summary) | Aligned two-phase rejection reason exactly with per-run wording. | Verifier Euclid |
| [batch-summary.md](batch-summary.md#verification-status) | Replaced initial verification placeholder rows with this verification record. | Verifier Euclid |
| [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-07-01-cluster-calibration) | Updated batch status to `extracted`; run statuses to `accepted`, `rejected`, and `accepted`. | Verifier Euclid |
| [global-summary.md](../global-summary.md) | Added `2026-07-01-cluster-calibration` to the batch index and updated date. | Verifier Euclid |

## Final Verification Status

| Check | Status | Evidence |
|---|---:|---|
| Required per-run section order | present | Matches [agentic-evidence-extraction-strategy.md](../../agentic-evidence-extraction-strategy.md) and prior extracted batches. |
| One absolute run root per run file | present | Final structural `rg` check. |
| Uppercase status words avoided | present | Final structural `rg` check. |
| Source-reference corrections incorporated | present | Verifier findings above were patched. |
| Manifest/global closeout | present | Manifest and global summary updated after verifier closeout. |
| Final extraction disposition | present | `top-to-bottom` and `parallel-sync` accepted; `two-phase-pessimistic` rejected/diagnostic. |
