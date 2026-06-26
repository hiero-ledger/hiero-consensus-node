# 2026-06-30 Cluster Calibration Verification Notes

## Scope

| Item | Status | Source |
|---|---:|---|
| Batch verified | present | `2026-06-30-cluster-calibration` |
| Verification method | present | Fresh read-only verifier sub-agents checked each per-run extraction and the batch/global summary. |
| Artifact protocol | present | [cluster-reconnectbench-artifact-processing-protocol.md](../../cluster-reconnectbench-artifact-processing-protocol.md) |
| Manifest | present | [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration) |

## Verifier Results

| Verifier | Scope | Initial result | Required correction | Final disposition |
|---|---|---:|---|---:|
| Lorentz | [top-to-bottom.md](top-to-bottom.md) | fail | Correct passive TCP/window row: wrong peer sampler files and unsupported small-queue/no-`rwnd_limited` claim. | pass after row correction |
| Kuhn | [two-phase-pessimistic.md](two-phase-pessimistic.md) | fail | Add direct support for `27/40` teacher-window count and narrow acceptance derivation to missing TCP/window evidence with additional limitations. | pass after source and wording corrections |
| Euler | [parallel-sync.md](parallel-sync.md) | pass | No blocking corrections. | pass |
| Ohm | [batch-summary.md](batch-summary.md), manifest, global summary | fail | Add this verification notes file, update manifest final statuses, add global summary row, and tighten batch-summary links to per-run sources. | pass after closeout corrections |
| Halley | [top-to-bottom.md](top-to-bottom.md), raw `network-node1_logs/swirlds.log` | fail | Corrective re-extraction found exact learner receiver windows, data usage, path ranges, `ReconnectMapMetrics`, dirty counters, and complete catch-up timing in learner `swirlds.log`. | pass after top-to-bottom acceptance correction |
| Dirac | [top-to-bottom.md](top-to-bottom.md), [batch-summary.md](batch-summary.md) | fail | Re-evaluate top-to-bottom network/workload/rollup impact with exact learner windows; passive TCP/window evidence can be accepted with a connection-level caveat. | pass after rollup correction |
| Schrodinger | [top-to-bottom.md](top-to-bottom.md), raw `network-node1_logs/swirlds.log` | pass | No blocking corrections after corrective re-extraction. | pass |
| Bohr | [batch-summary.md](batch-summary.md), manifest, global summary | pass | No blocking corrections after corrective re-extraction; `38` literal-value checks had `0` failures. | pass |

## Corrections Applied

| File | Correction | Source |
|---|---|---|
| [top-to-bottom.md](top-to-bottom.md#network-evidence) | Replaced the passive TCP/window claim with corrected peer sampler files and qualitative-only wording. | Verifier Lorentz; raw sampler refs now recorded in [top-to-bottom.md](top-to-bottom.md#network-evidence). |
| [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-window-and-roles) | Added same-row derived teacher-log counts for the `27/40` teacher-window coverage claim. | Verifier Kuhn; raw/derived refs now recorded in [two-phase-pessimistic.md](two-phase-pessimistic.md#reconnect-window-and-roles). |
| [two-phase-pessimistic.md](two-phase-pessimistic.md#analysis-output-per-mode) | Reworded acceptance derivation so missing TCP/window evidence is the hard protocol blocker; load continuity and teacher coverage remain additional calibration limitations. | Verifier Kuhn. |
| [batch-summary.md](batch-summary.md#verification-status) | Replaced pending verification status with this verification record. | Verifier Ohm. |
| [batch-summary.md](batch-summary.md#calibration-inputs-for-local-reconnectbench) | Replaced internal summary-only links with direct per-run evidence links. | Verifier Ohm. |
| [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration) | Updated batch status to `extracted`; run statuses to `rejected`, `rejected`, and `accepted`. | Verifier Ohm. |
| [global-summary.md](../global-summary.md) | Added `2026-06-30-cluster-calibration` to the batch index. | Verifier Ohm. |
| [top-to-bottom.md](top-to-bottom.md) | Re-extracted top-to-bottom from learner `network-node1_logs/swirlds.log`: exact receiver windows `00:21:08.306..00:27:30.674`, `00:27:37.090..00:29:33.338`, `00:29:43.231..00:30:43.868`; complete catch-up `575.562 s`; raw work-shape counters present. | Verifiers Halley and Schrodinger; raw refs recorded in [top-to-bottom.md](top-to-bottom.md). |
| [batch-summary.md](batch-summary.md) | Updated top-to-bottom acceptance, catch-up, iteration timing/state-gap, network, state/divergence, work-shape, traversal-order, calibration-input, and remaining-gap rows. | Verifiers Dirac and Bohr. |
| [cluster-reconnectbench-artifact-manifest.md](../../cluster-reconnectbench-artifact-manifest.md#2026-06-30-cluster-calibration) | Corrected 2026-06-30 top-to-bottom run status from `rejected` to `accepted`; final 2026-06-30 run statuses are `accepted`, `rejected`, and `accepted`. | Verifier Bohr. |
| [global-summary.md](../global-summary.md) | Corrected 2026-06-30 note: top-to-bottom and parallel-sync are accepted; two-phase remains diagnostic/rejected; no valid three-mode traversal ordering. | Verifier Bohr. |

## Final Status

| Check | Status | Evidence |
|---|---:|---|
| Required per-run section order | present | Verified by sub-agents and structural `rg` check. |
| One absolute run root per run file | present | Verified by sub-agent and path/status `rg` check. |
| Uppercase status words avoided | present | Verified by sub-agent and path/status `rg` check. |
| Source-reference corrections incorporated | present | Top-to-bottom corrective re-extraction and two-phase corrections applied before closeout. |
| Corrective re-extraction verified | present | Schrodinger verified top-to-bottom against raw learner `swirlds.log`; Bohr verified batch/manifest/global rollups with `38` checks and `0` failures. |
| Final extraction disposition | present | `top-to-bottom` and `parallel-sync` accepted; `two-phase-pessimistic` rejected/diagnostic. |
