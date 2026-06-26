# 2026-06-29 Cluster Calibration Verification Notes

## Scope

| Item | Status | Source |
|---|---:|---|
| Verification target | present | [top-to-bottom.md](top-to-bottom.md), [parallel-sync.md](parallel-sync.md), [two-phase-pessimistic.md](two-phase-pessimistic.md), and [batch-summary.md](batch-summary.md). |
| Verification method | present | Fresh read-only sub-agent verification after extraction, followed by a correction pass and local structural checks. |
| Raw artifact roots | not_applicable | Raw roots are indexed in [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-06-29-cluster-calibration). |

## Verification Agents

| Agent nickname | Scope | Result |
|---|---|---|
| Fermat | `top-to-bottom.md` against raw `dallas11_pullTopToBottom/report` artifacts | no blockers; source values checked; requested sampler window suffix cleanup. |
| Dirac | `parallel-sync.md` against raw `dallas12_pullParallelSync/report` artifacts | no blockers; source values checked; requested sampler window suffix cleanup, stronger acceptance input refs, status normalization, and active-confirmation wording cleanup. |
| Dewey | `two-phase-pessimistic.md` against raw `dallas14_pullTwoPhasePessimistic/report` artifacts | no blockers; source values checked; requested status normalization, expanded CSV column names, and all-node traversal-mode agreement source cleanup. |
| Pascal | `batch-summary.md`, per-run files, and manifest consistency | no blockers; batch summary values trace to per-run files; requested this verification notes file and status normalization. |

## Corrections Applied

| Correction | Status | Files |
|---|---:|---|
| Added bounded `;window=...` suffixes to passive sampler source references. | present | [top-to-bottom.md](top-to-bottom.md#network-evidence), [parallel-sync.md](parallel-sync.md#network-evidence) |
| Normalized non-canonical status labels in per-run evidence tables. | present | [top-to-bottom.md](top-to-bottom.md), [parallel-sync.md](parallel-sync.md), [two-phase-pessimistic.md](two-phase-pessimistic.md) |
| Expanded former shorthand CSV source references in two-phase evidence. | present | [two-phase-pessimistic.md](two-phase-pessimistic.md#network-evidence), [two-phase-pessimistic.md](two-phase-pessimistic.md#state-and-divergence-evidence) |
| Replaced two-endpoint traversal-mode agreement refs with all-node derived scan refs. | present | [top-to-bottom.md](top-to-bottom.md#run-context), [parallel-sync.md](parallel-sync.md#run-context), [two-phase-pessimistic.md](two-phase-pessimistic.md#run-context) |
| Expanded accepted-run source references to cite all protocol acceptance input families. | present | [top-to-bottom.md](top-to-bottom.md#analysis-output-per-mode), [parallel-sync.md](parallel-sync.md#analysis-output-per-mode) |
| Clarified that learner `CHECKING -> ACTIVE` confirmations are post-reconnect, not startup, for learner node0. | present | [top-to-bottom.md](top-to-bottom.md#network-disease-preflight), [parallel-sync.md](parallel-sync.md#network-disease-preflight), [two-phase-pessimistic.md](two-phase-pessimistic.md#network-disease-preflight) |

## Per-Run Results

| Run | Verification status | Findings after correction |
|---|---:|---|
| `top-to-bottom` | pass | Required section order present. Preflight, mode/commit, learner/teacher windows, two-iteration duration, reconnect counters, CSV stats, passive sampler evidence, workload samples, and acceptance `yes` were spot-checked against raw artifacts. No known remaining source-reference failures. |
| `parallel-sync` | pass | Required section order present. Preflight, mode/commit, learner/teacher windows, two-iteration duration, reconnect counters, CSV stats, passive sampler evidence, workload samples, and acceptance `yes` were spot-checked against raw artifacts. No known remaining source-reference failures. |
| `two-phase-pessimistic` | pass | Required section order present. Preflight, mode/commit, first/last/89-iteration episode, active confirmation, first/last/aggregate reconnect counters, CSV stats, passive sampler absence, workload limitation, and acceptance `no` were spot-checked against raw artifacts. No known remaining source-reference failures. |

## Batch Summary Results

| Check | Status | Result |
|---|---:|---|
| Summary values trace to per-run files | present | Batch summary values were verified as sourced from per-run Markdown files; no raw numeric value was found only in the summary. |
| Required Per-Mode Acceptance Summary columns | present | The summary table matches the required strategy column list. |
| Acceptance status defensibility | present | `pullTopToBottom` and `pullParallelSync` are accepted; `pullTwoPhasePessimistic` is rejected for full calibration because passive TCP/window evidence is absent and workload ends before final completion. |
| Manifest path consistency | present | Batch output paths and run roots are consistent with the manifest. |

## Final Verification Status

| Item | Status | Note |
|---|---:|---|
| Sub-agent verification pass completed | present | Per-run and summary verification was completed by sub-agents after extraction. |
| Corrections from verification applied | present | All correction-class findings from the verification pass were applied. |
| Remaining blocker findings | missing | No blocker findings are known after the correction pass. |
| Source-reference failures | missing | No known remaining source-reference failures after the correction pass. |
