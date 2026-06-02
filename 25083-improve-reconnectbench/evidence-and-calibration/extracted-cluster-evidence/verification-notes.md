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
| Source reference discipline | present | Extracted values include `log:`, `config:`, `csv:`, `sampler:`, or `derived:` references in the same row or block. |
| Summary discipline | present | `cluster-calibration-summary.md` links back to per-run Markdown evidence sections and does not cite raw artifacts independently. |

## Per-Run Results

| Run file | Status | Result |
|---|---:|---|
| `top-to-bottom.md` | ambiguous | Verifier found a prompt-root mismatch with the active atlas root and a missing teacher reconnect status row. Active atlas root was retained; teacher status was added. A docs-level verifier also found `teacherCandidate` incorrectly marked present; it was corrected to missing controlled run context. |
| `two-phase-pessimistic.md` | ambiguous | Verifier found full-node cross-check source refs too narrow, missing actual transaction-rate evidence, and unresolved-register gaps. Full-node row was narrowed to nodes 1-6 plus node7 limitation, actual transaction-rate evidence was added, and register rows were added. |
| `parallel-sync.md` | ambiguous | Verifier found CSV source references using raw line refs, missing learnerCandidate, and missing actual transaction-rate evidence. CSV refs were rewritten to column/timestamp style, learnerCandidate was added as ambiguous, and actual transaction-rate evidence was added. |

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

## Ambiguous Or Unresolved Items

| Evidence gap | Status | Affected run files |
|---|---:|---|
| Exact image tag/digest | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`, `parallel-sync.md` |
| Baseline restore identifier | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`, `parallel-sync.md` |
| Exact stopped-pod script output | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`; ambiguous in `parallel-sync.md` because only sampler stop marker was present. |
| Controlled learnerCandidate expected node | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`; ambiguous in `parallel-sync.md`. |
| Controlled teacherCandidate expected node | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`, `parallel-sync.md` |
| Controlled learnerBehindDuration | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`, `parallel-sync.md` |
| Controlled warmtime/downtime/loop count | missing | `top-to-bottom.md`, `two-phase-pessimistic.md`, `parallel-sync.md` |
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

## Final Verification Status

| Item | Status | Notes |
|---|---:|---|
| Per-run verifier completion | present | Run-scoped verifier agents completed and their findings were incorporated. |
| Final extraction status | ambiguous | Extraction is complete with documented unresolved evidence; a final post-correction audit is required before treating the verification notes as clean. |
