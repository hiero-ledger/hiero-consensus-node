# Cluster ReconnectBench Artifact Manifest

Updated: `2026-07-17`

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
| `2026-06-29-cluster-calibration` | extracted | Follow-up traversal-order cluster calibration batch for ReconnectBench. | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/29-06-2026` | `extracted-cluster-evidence/2026-06-29-cluster-calibration/` |
| `2026-06-30-cluster-calibration` | extracted | Follow-up high-state traversal-order cluster calibration batch for ReconnectBench. | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/30-06-2026` | `extracted-cluster-evidence/2026-06-30-cluster-calibration/` |
| `2026-07-01-cluster-calibration` | extracted | Follow-up high-state traversal-order cluster calibration batch for ReconnectBench. | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/01-07-2026` | `extracted-cluster-evidence/2026-07-01-cluster-calibration/` |
| `2026-07-16-1b-observational` | extracted | Single-run, approximately one-billion-record observational reconnect extraction with SocketFactory and focused `ss -tinm` evidence. | `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/16-07-2026-1B` | `extracted-cluster-evidence/2026-07-16-1b-observational/` |

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

## 2026-06-29 Cluster Calibration

### Batch Context

Keep these facts with the batch so the collected artifacts are interpreted in the context in which they were produced.
Treat them as expected run context to verify from artifacts, not as substitutes for parsing logs, settings, metrics, and
script output.

- The collected data came from traversal-order reconnect workflow runs under the dated artifact parent
  `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/29-06-2026`.
- The run strategy was one full workflow/job per traversal order, rather than an in-script traversal matrix.
- Cluster traversal artifacts are expected to be independent live-state workflow runs. Do not require a common restored
  baseline state for cluster extraction. Treat each run as a separate calibration anchor, and compare traversal modes
  locally only after reproducing comparable state size, state gap, work shape, and network profile in `ReconnectBench`.
- The intended learner is expected to be `network-node1-0` / node `0`; extraction must validate the observed learner
  from reconnect lifecycle logs.
- The intended NLG state/load shape used `30M` NLG accounts, `6h` duration, and the default `8K` TPS cap; extraction
  must validate the actual workload rate and timing from NLG/client logs.
- `version_run.txt` records commit `0cc709860be30d5892ba5fa70ed9300ce4107628` for all three runs; extraction must still
  source the commit from each run artifact.
- No `performance-tests-start.log` or `performance-tests-watch.log` files were visible during manifest pre-inspection;
  extraction must record workflow-control evidence as missing if no workflow logs or equivalent script output are found.
- Passive per-node TCP/socket sampler logs were visible during manifest pre-inspection for the `dallas11` and `dallas12`
  runs. No passive sampler files were visible under the `dallas14` run root during manifest pre-inspection; extraction
  must still search the run root and record sampler evidence as missing if absent.

### Run Entries

Use `runRoot` as the base path for top-level run artifacts, network sampler files, and `version_run.txt`. Use
`podLogRoot` as the base path for `network-node<N>_logs` directories.

| Run ID | Traversal mode | `runRoot` | `podLogRoot` | Workflow log root | Intended learner | Output file | Status |
|---|---|---|---|---|---|---|---:|
| `top-to-bottom` | `pullTopToBottom` | `dallas11_pullTopToBottom/report` | `dallas11_pullTopToBottom/report/podlog_solo-mdlt-n11` | `dallas11_pullTopToBottom/report` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-06-29-cluster-calibration/top-to-bottom.md` | accepted |
| `parallel-sync` | `pullParallelSync` | `dallas12_pullParallelSync/report` | `dallas12_pullParallelSync/report/podlog_solo-mdlt-n12` | `dallas12_pullParallelSync/report` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-06-29-cluster-calibration/parallel-sync.md` | accepted |
| `two-phase-pessimistic` | `pullTwoPhasePessimistic` | `dallas14_pullTwoPhasePessimistic/report` | `dallas14_pullTwoPhasePessimistic/report/podlog_solo-sdpt-n14` | `dallas14_pullTwoPhasePessimistic/report` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-06-29-cluster-calibration/two-phase-pessimistic.md` | rejected |

### Batch Outputs

| Output | Path |
|---|---|
| Batch summary | `extracted-cluster-evidence/2026-06-29-cluster-calibration/batch-summary.md` |
| Verification notes | `extracted-cluster-evidence/2026-06-29-cluster-calibration/verification-notes.md` |
| Global summary index | `extracted-cluster-evidence/global-summary.md` |

## 2026-06-30 Cluster Calibration

### Batch Context

Keep these facts with the batch so the collected artifacts are interpreted in the context in which they were produced.
Treat them as expected run context to verify from artifacts, not as substitutes for parsing logs, settings, metrics, and
script output.

- The collected data came from traversal-order reconnect workflow runs under the dated artifact parent
  `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/30-06-2026`.
- The run strategy was one full workflow/job per traversal order, rather than an in-script traversal matrix.
- Cluster traversal artifacts are expected to be independent live-state workflow runs. Do not require a common restored
  baseline state for cluster extraction. Treat each run as a separate calibration anchor, and compare traversal modes
  locally only after reproducing comparable state size, state gap, work shape, and network profile in `ReconnectBench`.
- The intended learner is expected to be `network-node1-0` / node `0`; extraction must validate the observed learner
  from reconnect lifecycle logs.
- The intended NLG state/load shape used `97.5M` NLG accounts, `6h` duration, and the default `8K` TPS cap; extraction
  must validate the actual workload rate and timing from NLG/client logs.
- `version_run.txt` records commit `0cc709860be30d5892ba5fa70ed9300ce4107628` for all three runs; extraction must still
  source the commit from each run artifact.
- No `performance-tests-start.log` or `performance-tests-watch.log` files were visible during manifest pre-inspection;
  extraction must record workflow-control evidence as missing if no workflow logs or equivalent script output are found.
- Passive per-node TCP/socket sampler logs were visible during manifest pre-inspection for the `dallas10` and `dallas12`
  runs. No passive sampler files were visible under the `dallas11` run root during manifest pre-inspection; extraction
  must still search the run root and record sampler evidence as missing if absent.
- Some node directories were missing a plain `swirlds.log` file during manifest pre-inspection; extraction must search
  available log files and record missing or ambiguous evidence rather than infer values from intended run shape.

### Run Entries

Use `runRoot` as the base path for top-level run artifacts, network sampler files, and `version_run.txt`. Use
`podLogRoot` as the base path for `network-node<N>_logs` directories.

| Run ID | Traversal mode | `runRoot` | `podLogRoot` | Workflow log root | Intended learner | Output file | Status |
|---|---|---|---|---|---|---|---:|
| `top-to-bottom` | `pullTopToBottom` | `dallas10_pullTopToBottom/report` | `dallas10_pullTopToBottom/report/podlog_solo-mdlt-n10` | `dallas10_pullTopToBottom/report` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-06-30-cluster-calibration/top-to-bottom.md` | accepted |
| `two-phase-pessimistic` | `pullTwoPhasePessimistic` | `dallas11_pullTwoPhasePessimistic/report` | `dallas11_pullTwoPhasePessimistic/report/podlog_solo-mdlt-n11` | `dallas11_pullTwoPhasePessimistic/report` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-06-30-cluster-calibration/two-phase-pessimistic.md` | rejected |
| `parallel-sync` | `pullParallelSync` | `dallas12_pullParallelSync/report` | `dallas12_pullParallelSync/report/podlog_solo-mdlt-n12` | `dallas12_pullParallelSync/report` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-06-30-cluster-calibration/parallel-sync.md` | accepted |

### Batch Outputs

| Output | Path |
|---|---|
| Batch summary | `extracted-cluster-evidence/2026-06-30-cluster-calibration/batch-summary.md` |
| Verification notes | `extracted-cluster-evidence/2026-06-30-cluster-calibration/verification-notes.md` |
| Global summary index | `extracted-cluster-evidence/global-summary.md` |

## 2026-07-01 Cluster Calibration

### Batch Context

Keep these facts with the batch so the collected artifacts are interpreted in the context in which they were produced.
Treat them as expected run context to verify from artifacts, not as substitutes for parsing logs, settings, metrics, and
script output.

- The collected data came from traversal-order reconnect workflow runs under the dated artifact parent
  `/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs/01-07-2026`.
- The run strategy was one full workflow/job per traversal order, rather than an in-script traversal matrix.
- Cluster traversal artifacts are expected to be independent live-state workflow runs. Do not require a common restored
  baseline state for cluster extraction. Treat each run as a separate calibration anchor, and compare traversal modes
  locally only after reproducing comparable state size, state gap, work shape, and network profile in `ReconnectBench`.
- The intended learner is expected to be `network-node1-0` / node `0`; extraction must validate the observed learner
  from reconnect lifecycle logs.
- The intended NLG state/load shape used `97.5M` NLG accounts, `6h` duration, and the default `8K` TPS cap; extraction
  must validate the actual workload rate and timing from NLG/client logs.
- `version_run.txt` records commit `0cc709860be30d5892ba5fa70ed9300ce4107628` for all three runs; extraction must still
  source the commit from each run artifact.
- No `performance-tests-start.log` or `performance-tests-watch.log` files were visible during manifest pre-inspection;
  extraction must record workflow-control evidence as missing if no workflow logs or equivalent script output are found.
- Passive per-node TCP/socket sampler logs were visible during manifest pre-inspection for all three runs, but the
  two-phase pessimistic run uses the alternate `network-node<N>-0_network_sampler.log` naming pattern. Extraction must
  still verify sampler coverage against exact reconnect windows.
- Some node directories were missing a plain `swirlds.log` file during manifest pre-inspection; extraction must search
  available log files and record missing or ambiguous evidence rather than infer values from intended run shape.
- The `parallel-sync` artifact uses an `sdpt` pod log root while the other two artifacts use `mdlt`; extraction must use
  the per-run pod log root below instead of assuming a common log-root suffix.

### Run Entries

Use `runRoot` as the base path for top-level run artifacts, network sampler files, and `version_run.txt`. Use
`podLogRoot` as the base path for `network-node<N>_logs` directories.

| Run ID | Traversal mode | `runRoot` | `podLogRoot` | Workflow log root | Intended learner | Output file | Status |
|---|---|---|---|---|---|---|---:|
| `top-to-bottom` | `pullTopToBottom` | `dallas10_pullTopToBottom/report` | `dallas10_pullTopToBottom/report/podlog_solo-mdlt-n10` | `dallas10_pullTopToBottom/report` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-07-01-cluster-calibration/top-to-bottom.md` | accepted |
| `two-phase-pessimistic` | `pullTwoPhasePessimistic` | `dallas11_pullTwoPhasePessimistic/report` | `dallas11_pullTwoPhasePessimistic/report/podlog_solo-mdlt-n11` | `dallas11_pullTwoPhasePessimistic/report` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-07-01-cluster-calibration/two-phase-pessimistic.md` | rejected |
| `parallel-sync` | `pullParallelSync` | `dallas14_pullParallelSync/report` | `dallas14_pullParallelSync/report/podlog_solo-sdpt-n14` | `dallas14_pullParallelSync/report` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-07-01-cluster-calibration/parallel-sync.md` | accepted |

### Batch Outputs

| Output | Path |
|---|---|
| Batch summary | `extracted-cluster-evidence/2026-07-01-cluster-calibration/batch-summary.md` |
| Verification notes | `extracted-cluster-evidence/2026-07-01-cluster-calibration/verification-notes.md` |
| Global summary index | `extracted-cluster-evidence/global-summary.md` |

## 2026-07-16 1B Observational Reconnect

### Collection Context

- This is one observational reconnect run, not a traversal-order comparison batch.
- The intended state scale is approximately one billion VirtualMap records; extraction must report observed path-range
  and stats evidence rather than treating the intended scale as observed fact.
- `version_run.txt` is expected to contain `inputs.NLG_Accounts=300000000` and producing commit
  `09f7ef40e031fc3e1a06db6f7db5e7dcfe9abc73`; extraction must verify both from the artifact.
- The intended learner is `network-node1-0` / node `0`; extraction must verify the learner from reconnect lifecycle
  logs.
- SocketFactory pre/post bind/connect telemetry is expected from the producing branch; extraction must source observed
  values from node logs.
- Seven passive sampler files are expected to contain `ss -tinm` output; extraction must verify `skmem` presence and
  exact reconnect-window coverage.
- Calibration acceptance, traversal ordering, local parameter mapping, and historical comparison are not applicable.

### Run Entry

| Run ID | Purpose | `runRoot` | `podLogRoot` | Workflow log root | Expected learner | Output file | Status |
|---|---|---|---|---|---|---|---:|
| `reconnect-run` | Single-run large-state observational reconnect extraction | `.` | `podlog_solo-mdlt-n12` | `.` | `network-node1-0` / node `0` | `extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md` | extracted |

### Collection Outputs

| Output | Path |
|---|---|
| Run extraction | `extracted-cluster-evidence/2026-07-16-1b-observational/reconnect-run.md` |
| Verification notes | `extracted-cluster-evidence/2026-07-16-1b-observational/verification-notes.md` |
| Extraction summary | `extracted-cluster-evidence/2026-07-16-1b-observational/extraction-summary.md` |
