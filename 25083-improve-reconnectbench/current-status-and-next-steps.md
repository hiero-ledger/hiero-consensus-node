# Current Status And Next Steps

Date: `2026-06-01`

## Status

Cluster evidence has now been collected for every traversal order. The run artifacts are outside this repository at:

```text
/Users/thenswan/Work/LimeChain/playground/reconnect-cluster-runs
```

Those runs include the required cluster evidence for the first calibration pass. Passive TCP/socket/network evidence was
also collected around the reconnect window using passive sampling from learner restart through `ACTIVE`. Script code details are intentionally not captured here.

Production reconnect telemetry was not needed for this pass, and no production/runtime consensus-node behavior changes
are required for the current analysis.

The `25083-improve-reconnectbench` documentation cleanup pass has been completed. The remaining docs should now be
treated as the active task-local source of truth for the cluster calibration phase.

## Remaining Work

Before processing the collected runs, keep
`25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-processing-protocol.md` as the
high-level extraction protocol and build the exact source-location map in
`25083-improve-reconnectbench/evidence-and-calibration/cluster-reconnectbench-artifact-atlas.md`.

The atlas should stay source-location only: what evidence is needed, which collected artifact contains it, and which log
pattern, metric name, or file path should be used. Extracted values and analysis results belong in later processing
outputs, not in the atlas. Populate the atlas incrementally, starting with the easiest high-confidence artifacts and
adding sharper paths/patterns as each evidence family is processed.

The protocol-improvement brainstorm should cover at least:

1. Exact cluster-run artifact collection mechanics: what commands or access paths collect each artifact family, and
   what should be copied from each run.
2. Metric names: the concrete reconnect, VirtualMap, platform, application, NLG/client, and network metric names that
   should be extracted or cross-checked.
3. Log extraction rules: the exact log patterns, lifecycle events, timestamps, node IDs, peer IDs, reconnect status
   markers, and counter lines used to build each analysis field.
4. Artifact locations: where logs, metrics, settings/config snapshots, NLG/client output, passive TCP evidence, script
   output, and derived summaries are expected to live inside each collected run directory.
5. Collection output format and execution process: the per-run output files, normalized table/schema, raw evidence
   snippets, command transcript expectations, and a sub-agentic workflow for independent extraction/review passes.
6. Completeness checks: any missing-artifact handling, acceptance/rejection criteria, traceability requirements from
   derived values back to raw evidence, and open calibration gaps that should be recorded instead of inferred.

For the next calibration branch, the intended workflow is:

1. Create a new branch from the respective `main` commit where the cluster run happened.
2. Move the current branch's `ReconnectBench` changes onto that branch.
3. Process the collected run artifacts and use the results to guide calibration.
