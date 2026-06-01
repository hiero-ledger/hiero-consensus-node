# ReconnectBench Task Index

Last updated: 2026-06-01

> Summary: Routing map for the ReconnectBench improvement task docs and captured calibration artifacts.

---

## Root-Level Task State

- [Current Status And Next Steps](current-status-and-next-steps.md) - Temporary task-state note kept outside the durable documentation buckets.

## Design And Implementation

- [Original Design Specification](design-and-implementation/ReconnectBench-original-design-specification.md) - Initial planning document for the ReconnectBench redesign.
- [Traversal-Comparison MVP Design](design-and-implementation/ReconnectBench-traversal-comparison-mvp-design.md) - Current MVP design for comparing reconnect traversal modes with simulated network behavior.
- [Traversal-Comparison MVP Implementation Plan](design-and-implementation/ReconnectBench-traversal-comparison-mvp-implementation-plan.md) - Step-by-step implementation plan for the traversal-comparison MVP.

## Bugs And Improvements

- [ReconnectBench Critical Bugs And Improvements](bugs-and-improvements/reconnectbench-critical-bugs-and-improvements.md) - Critical findings from the local-vs-cluster traversal-ordering investigation.
- [SimulatedNetworkChannel Bugs And Improvements](bugs-and-improvements/simulated-network-channel-bugs-and-improvements.md) - Simulator and benchmark issues that affect correctness, portability, or interpretation.

## Cluster Evidence And Calibration

- [Cluster ReconnectBench Calibration Protocol](cluster-evidence-and-calibration/cluster-reconnectbench-run-protocol.md) - Procedure for running cluster calibration, extracting artifacts, and mapping cluster results back to local ReconnectBench runs.
- [Cluster Metrics Analysis](cluster-evidence-and-calibration/cluster-metrics-analysis.md) - Analysis of cluster metric signals relevant to reconnect calibration.
- [Network Evidence And State Shape Strategy](cluster-evidence-and-calibration/network-evidence-and-state-shape-strategy.md) - Strategy for passive network evidence, state-shape extraction, artifact confirmation, and future script shape.
- [Network Evidence Location Analysis](cluster-evidence-and-calibration/network-evidence-location-analysis.md) - Analysis of where to collect network evidence in automation and reconnect scripts.

## Results And Validation

- [Local Reconnect Artifact Extraction Results](results-and-validation/local-reconnect-telemetry-validation-results.md) - Local validation of reconnect artifact extraction and protocol coverage.
- [Local ReconnectBench Averaged Cluster-Profile Results](results-and-validation/local-reconnectbench-averaged-cluster-profile-results.md) - Local benchmark results using averaged cluster-profile settings.
