# Cluster ReconnectBench Artifact Manifest

Updated: `2026-06-04`

## Purpose

This manifest is the source of truth for cluster ReconnectBench artifact batches that should be processed.

Use this file as the index of raw artifact roots. The processing protocol, extraction strategy, and generic atlas should
reference this manifest instead of duplicating raw artifact paths or concrete run-root tables.

## Rules

- Add one entry per collected artifact batch.
- A batch may contain multiple traversal-mode run roots.
- Keep concrete raw roots here. Other task docs may link to a manifest batch or run entry, but should not maintain their
  own scheduling/index copy of these paths.
- Extracted per-run files may record their absolute artifact run root once as extracted context.
- Batch summaries should point to manifest run IDs instead of repeating raw artifact roots.
- If a batch root or run root changes, update it here first, then update extracted evidence only where it is directly
  part of that run's recorded context.

## Batches

| Batch ID | Status | Purpose | Raw artifact parent/root | Output directory |
|---|---:|---|---|---|
| `2026-05-29-cluster-calibration` | extracted | Initial traversal-order cluster calibration batch for ReconnectBench. | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs` | `extracted-cluster-evidence/2026-05-29-cluster-calibration/` |

## 2026-05-29 Cluster Calibration

### Batch Context

Keep these facts with the batch so the collected artifacts are interpreted in the context in which they were produced.
Treat them as expected run context to verify from artifacts, not as substitutes for parsing logs, settings, metrics, and
script output.

- The collected data came from the performance-analysis reconnect workflow, not the single-day longevity workflow.
- The run strategy was one full workflow/job per traversal order, rather than an in-script traversal matrix.
- Cluster traversal artifacts are expected to be independent live-state workflow runs. Do not require a common restored
  baseline state for cluster extraction. Treat each run as a separate calibration anchor, and compare traversal modes
  locally only after reproducing comparable state size, state gap, work shape, and network profile in `ReconnectBench`.
- The intended learner was `network-node1-0` / node `0`.
- The intended reconnect shape used `warmtime=600`, `downtime=1800`, and `NofLoops=0`, chosen for one reconnect
  iteration with the script semantics used for this run.
- The intended NLG state/load shape used `24M` NLG accounts and the default `8K` TPS cap. This was chosen to target
  roughly `100M` Virtual Map records on the learner and about `10M` additional records of teacher/learner divergence.
- Load was not removed before the learner restarted; validate the actual workload rate and timing from NLG/client logs.
- Passive TCP/socket/network evidence was collected around the reconnect window, from learner restart through learner
  `ACTIVE`. Do not depend on old draft script details; DevOps debugged and changed the actual implementation.
- Production reconnect telemetry changes were not part of this pass.

### Run Entries

Use `runRoot` as the base path for top-level run artifacts, network sampler files, and `version_run.txt`. Use
`podLogRoot` as the base path for `network-node<N>_logs` directories.

| Run ID | Traversal mode | `runRoot` | `podLogRoot` | Workflow log root | Intended learner | Output file | Status |
|---|---|---|---|---|---|---|---:|
| `top-to-bottom` | `pullTopToBottom` | `NikitaReconnect1` | `NikitaReconnect1/podlog_solo-mdlt-n3` | `NikitaReconnect1` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-05-29-cluster-calibration/top-to-bottom.md` | accepted |
| `two-phase-pessimistic` | `pullTwoPhasePessimistic` | `NikitaReconnect2_2phase/report` | `NikitaReconnect2_2phase/report/podlog_solo-mdlt-n4` | `NikitaReconnect2_2phase` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-05-29-cluster-calibration/two-phase-pessimistic.md` | rejected |
| `parallel-sync` | `pullParallelSync` | `NikitaReconnect3_PullParallelSync/report` | `NikitaReconnect3_PullParallelSync/report/podlog_solo-mdlt-n4` | `NikitaReconnect3_PullParallelSync` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-05-29-cluster-calibration/parallel-sync.md` | rejected |

### Batch Outputs

| Output | Path |
|---|---|
| Batch summary | `extracted-cluster-evidence/2026-05-29-cluster-calibration/batch-summary.md` |
| Verification notes | `extracted-cluster-evidence/2026-05-29-cluster-calibration/verification-notes.md` |
| Global summary index | `extracted-cluster-evidence/global-summary.md` |
