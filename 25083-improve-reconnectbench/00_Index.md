# ReconnectBench Task Index

Last updated: 2026-05-28

> Summary: Routing map for the ReconnectBench improvement task docs and captured calibration artifacts. Use this first to decide which design, protocol, result, or evidence file to read.

---

## Core Planning

- [Original Design Specification](ReconnectBench-original-design-specification.md) - Initial planning document for the ReconnectBench redesign. Read this first for the task motivation, architecture, network model, workload model, parameters, lifecycle, reporting, limitations, and future work.
- [Traversal-Comparison MVP Design](ReconnectBench-traversal-comparison-mvp-design.md) - Current MVP design for comparing reconnect traversal modes with simulated network behavior. Read this when checking intended benchmark behavior, parameters, diagnostics, validation, and calibration notes.
- [Traversal-Comparison MVP Implementation Plan](ReconnectBench-traversal-comparison-mvp-implementation-plan.md) - Step-by-step implementation plan for the traversal-comparison MVP. Read this when implementing or auditing planned code changes.
- [Current Status And Next Steps](current-status-and-next-steps.md) - Short status snapshot and remaining work list. Read this first when resuming the task and deciding what to do next.
- [SimulatedNetworkChannel Bugs And Improvements](simulated-network-channel-bugs-and-improvements.md) - Focused list of important simulator and benchmark issues that affect correctness, portability, or interpretation.
- [ReconnectBench Critical Bugs And Improvements](reconnectbench-critical-bugs-and-improvements.md) - Critical-only findings from the local-vs-cluster traversal-ordering investigation. Read this when deciding what must be fixed or validated before trusting local cluster-profile results.

## Cluster Calibration And Evidence Strategy

- [Cluster ReconnectBench Calibration Protocol](cluster-reconnectbench-run-protocol.md) - Procedure for running cluster calibration, extracting artifacts, and mapping cluster results back to local ReconnectBench runs. Read this before requesting or interpreting cluster runs.
- [Cluster Metrics Analysis](cluster-metrics-analysis.md) - Detailed analysis of cluster metric signals relevant to reconnect calibration. Read this when validating which production metrics can support benchmark calibration.
- [Network Evidence And State Shape Strategy](network-evidence-and-state-shape-strategy.md) - Strategy for passive network evidence, state-shape extraction, artifact confirmation, and future script shape. Read this when deciding what evidence the benchmark needs before adding active throughput collection.
- [Network Evidence Location Analysis](network-evidence-location-analysis.md) - Analysis of where to collect network evidence in automation and reconnect scripts. Read this when choosing the workflow or script layer for evidence extraction.

## Local Results And Validation

- [Local Reconnect Artifact Extraction Results](local-reconnect-telemetry-validation-results.md) - Local validation of reconnect artifact extraction and protocol coverage. Read this when checking whether local logs expose the fields needed by the cluster protocol.
- [Local ReconnectBench Averaged Cluster-Profile Results](local-reconnectbench-averaged-cluster-profile-results.md) - Local benchmark results using averaged cluster-profile settings. Read this when comparing traversal modes under the local simulated cluster profile.
