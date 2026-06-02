# Verification Notes

## Scope

| Check | Status | Evidence |
|---|---:|---|
| Required output directory exists | present | `25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/` |
| Required files created | present | `top-to-bottom.md`, `two-phase-pessimistic.md`, `parallel-sync.md`, `verification-notes.md`, `cluster-calibration-summary.md` |
| Output format | present | Files are Markdown only. |
| Strategy/protocol/atlas value extraction | present | Extracted values were written to per-run evidence files, not to strategy, protocol, atlas, or status docs. |
| Production/runtime code edits | present | No production/runtime files were intentionally modified during this extraction. Pre-existing worktree changes were left untouched. |

## Verification Method

| Method item | Status | Notes |
|---|---:|---|
| Bootstrap docs read | present | `25083-improve-reconnectbench/Index.md` and `25083-improve-reconnectbench/current-status-and-next-steps.md` were used as entry points. |
| Active strategy cross-check | present | `agentic-evidence-extraction-strategy.md` was re-read during assembly; summary and verification notes were corrected to the required shapes. |
| Run anchor agents | present | One run-scoped anchor pass was used for each run before assembly. |
| Run-scoped verifier agents | present | Fresh verifier agents checked `top-to-bottom.md`, `two-phase-pessimistic.md`, and `parallel-sync.md`; findings were incorporated below. |
| Artifact access bounded | present | Extraction used targeted `rg`, `sed`, and CSV column summaries rather than loading full large artifacts into the evidence docs. |
| Performance workflow logs incorporated | present | Newly added `performance-tests-start.log` and `performance-tests-watch.log` files were used to close reconnect-loop control gaps and document skipped state upload evidence. |
| Source reference discipline | present | Extracted values include `log:`, `config:`, `csv:`, `sampler:`, or `derived:` references in the same row or block. |
| Summary discipline | present | `cluster-calibration-summary.md` links back to per-run Markdown evidence sections and does not cite raw artifacts independently. |

## Per-Run Results

| Run file | Status | Result |
|---|---:|---|
| `top-to-bottom.md` | ambiguous | Verifier found a prompt-root mismatch with the active atlas root and a missing teacher reconnect status row. Active atlas root was retained; teacher status was added. A docs-level verifier also found `teacherCandidate` incorrectly marked present; it was corrected to missing controlled run context. Performance workflow logs then closed reconnect-loop controls and added skipped state-upload evidence. |
| `two-phase-pessimistic.md` | ambiguous | Verifier found full-node cross-check source refs too narrow, missing actual transaction-rate evidence, and unresolved-register gaps. Full-node row was narrowed to nodes 1-6 plus node7 limitation, actual transaction-rate evidence was added, and register rows were added. Performance workflow logs then closed reconnect-loop controls and added skipped state-upload evidence. |
| `parallel-sync.md` | ambiguous | Verifier found CSV source references using raw line refs, missing learnerCandidate, and missing actual transaction-rate evidence. CSV refs were rewritten to column/timestamp style, learnerCandidate was added as ambiguous, and actual transaction-rate evidence was added. Performance workflow logs then closed reconnect-loop controls and added skipped state-upload evidence; this run's watch log is empty, so loop-control evidence comes from `../performance-tests-start.log`. |

## Source Reference Failures

| Source reference | Status | Finding | Correction |
|---|---:|---|---|
| `top-to-bottom.md` teacher reconnect status | present | Matching teacher start/end existed, but status was not explicit. | Added `Teacher reconnect status` sourced to teacher finish payload. |
| `two-phase-pessimistic.md` and `parallel-sync.md` teacher reconnect status | present | Final compliance audit found matching teacher reconnect status rows missing from two-phase and parallel. | Added teacher status rows sourced to teacher finish payloads. |
| `top-to-bottom.md` teacherCandidate | present | Observed teacher peer was recorded as `teacherCandidate` present despite no controlled expected teacher key. | Corrected `teacherCandidate` to `missing` controlled run context and kept observed peer in reconnect role evidence. |
| `two-phase-pessimistic.md` full node metrics cross-check | present | Row claimed nodes 1-6 while citing only nodes 1, 2, 3, and node7 limitation. | Added node 4-6 refs and changed status to `ambiguous` because node7 does not overlap the reconnect window. |
| Actual transaction rate rows | present | Protocol-required actual transaction-rate evidence was missing from per-run workload sections. | Added source-referenced reconnect-window transaction-rate rows for all three runs. |
| `parallel-sync.md` CSV source-reference style | present | Full-node row used raw CSV line refs. | Replaced with `csv:` refs containing observed columns plus rows or timestamps. |
| Unresolved-register gaps | present | Candidate, learner-behind, passive-window, and full-node limitations were not consistently indexed. | Added run-level unresolved-register rows where evidence remains missing or ambiguous. |
| Reconnect-loop control rows | present | Earlier extraction could not find controlled `warmtime`, `downtime`, or `NofLoops` values. | Added workflow-sourced `downtime=1800`, `warmtime=600`, and `NofLoops=0` rows for all three runs. |
| Baseline state upload rows | present | Earlier extraction did not establish whether a restored baseline state was uploaded. | Added workflow-sourced skipped upload evidence for all three runs; cross-mode state comparability remains low because there was no common uploaded baseline state. |
| Teacher/learner target equality | present | Summary needed first teacher target state size, learner target/end size, and gap side by side. | Added equality rows in per-run state sections and expanded the summary state table. |

## Ambiguous Or Unresolved Items

| Evidence gap | Status | Affected run files |
|---|---:|---|
| Exact stopped-pod script output | ambiguous | All three run files now have a generic workflow `Stopping java` marker, but none has an exact stopped-pod control line. |
| Common restored baseline state or identical state-shape control | missing | All three run files show skipped state upload; state sizes, gaps, and clean/dirty work shape differ across modes. |
| Controlled learnerCandidate expected node | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`; ambiguous in `parallel-sync.md`. |
| Controlled teacherCandidate expected node | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`, `parallel-sync.md` |
| Controlled learnerBehindDuration | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`, `parallel-sync.md` |
| Full-run average transaction rate | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`, `parallel-sync.md` |
| Full node metrics cross-check limitation | ambiguous | `two-phase-pessimistic.md` because node7 stats do not overlap the first reconnect window. |
| Direct passive socket attribution to learner/teacher node pair | ambiguous | `top-to-bottom.md`, `parallel-sync.md` |
| Passive TCP/window sample overlap with first reconnect | missing | `two-phase-pessimistic.md` |
| Passive sampler end-of-first-window coverage | ambiguous | `parallel-sync.md` |

## Corrections Required

| Correction | Status | Notes |
|---|---:|---|
| Summary required section shape | present | `cluster-calibration-summary.md` now includes strategy-required sections. |
| Verification notes required section shape | present | This file now includes strategy-required sections. |
| Verifier-driven corrections | present | Run-scoped and docs-level verifier findings were incorporated into per-run files, summary, and this verification note. |
| Performance-log gap closure | present | Reconnect-loop controls were moved out of the unresolved set; skipped state upload and cross-mode state comparability limits were added. |

## Final Verification Status

| Item | Status | Notes |
|---|---:|---|
| Per-run verifier completion | present | Run-scoped verifier agents completed and their findings were incorporated. |
| Final extraction status | ambiguous | Extraction is complete with documented unresolved evidence. Performance logs closed the reconnect-loop-control gap, but exact stopped-pod controls, candidate controls, passive socket attribution, and common state-shape control remain unresolved. |
