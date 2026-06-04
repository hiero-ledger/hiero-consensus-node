# Current Status And Next Steps

Date: `2026-06-04`

## Current Status

Cluster evidence was collected and the first Markdown-only extraction/verification pass has completed for every traversal
order in the first manifest batch. Raw artifact batch roots are indexed in:

```text
25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md
```

Extracted, source-referenced evidence now lives under:

```text
25083-improve-reconnectbench/evidence-and-calibration/extracted-cluster-evidence/<batch-id>/
```

The current working phase is analysis of the extracted evidence. The batch summary and verification notes under the
batch directory are the practical references for the completed extraction pass; per-run evidence files are available
when a source reference, verifier finding, or unresolved item needs detail.

Known unresolved evidence remains documented in the per-run unresolved registers and rollup summary. The
`pullParallelSync` artifact is now separately invalidated by the network-disease preflight (`NETWORK_DISEASE_FATAL`) and
must be re-run before it is used for calibration. Other gaps are inputs to continued analysis, possible follow-up
extraction from the existing artifacts, and planning for future cluster runs.

## Next Steps

- Analyze the extracted evidence to identify benchmark calibration decisions, remaining confidence gaps, and any
  reconnect-benchmark issues that should be carried into implementation work.
- Treat manifest run `parallel-sync` in batch `2026-05-29-cluster-calibration` as diagnostic-only because post-startup
  `ACTIVE -> CHECKING` churn is corroborated by missing-parent evidence. Do not use its timing, counters, network
  evidence, workload evidence, or state shape for calibration.
- Attempt additional extraction from the existing cluster artifacts only where the summary, verification notes, or
  unresolved registers show a concrete gap that may still be answerable from the collected files.
- Keep preparing the extraction process for future cluster runs by preserving the current Markdown-only evidence flow:
  per-run extraction first, verification next, then summary updates after verifier findings are incorporated.
- For future cluster artifacts, add a manifest batch entry first, then write extracted Markdown under
  `extracted-cluster-evidence/<batch-id>/` so new evidence stays separated from this completed pass.
