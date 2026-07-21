# ReconnectBench Task Index

Last updated: 2026-07-21

> Summary: Routing map for the ReconnectBench improvement task docs and captured calibration artifacts.

---

## Root-Level Task State

- [Current Status And Next Steps](current-status-and-next-steps.md) - Temporary task-state note kept outside the durable documentation buckets.
- [ReconnectBench GitHub Issues](future-work/tasks.md) - Consolidated GitHub issue tasks for remaining ReconnectBench work.

## Design And Implementation

- [Refined A1 Socket-Network Design And Real-Network Gap Analysis](design-and-implementation/2026-07-21-refined-a1-socket-network-design-and-real-network-gap-analysis.md) - Implemented, falsifiable prototype for sender-observed one-way latency and progressive payload eligibility over the production loopback socket stack while leaving real socket buffers authoritative; correctness/functional smokes pass but the provisional `270 us` timing gate currently fails; includes exact timing, diagnostics, validation status, no-`4L`/no-ticket explanation, and remaining fidelity gaps versus real TCP.
- [Runtime Network Architecture Analysis](design-and-implementation/2026-07-20-runtime-network-architecture-analysis.md) - Cross-check draft comparing the simulator, raw loopback socket, current read-paced socket, coordinated hybrid, and relay designs; records bandwidth/latency semantics, TCP-ACK trust boundaries, 5M/10M evidence, competing arguments, and the conditional architecture conclusion.
- [Original Design Specification](design-and-implementation/ReconnectBench-original-design-specification.md) - Initial planning document for the ReconnectBench redesign.
- [Traversal-Comparison MVP Design](design-and-implementation/ReconnectBench-traversal-comparison-mvp-design.md) - Current MVP design for comparing reconnect traversal modes with simulated network behavior.
- [Traversal-Comparison MVP Implementation Plan](design-and-implementation/ReconnectBench-traversal-comparison-mvp-implementation-plan.md) - Archived step-by-step implementation plan for the already-executed traversal-comparison MVP.
- [Loopback Socket Transport Design](design-and-implementation/ReconnectBench-loopback-socket-transport-design.md) - Approved design for adding `NetworkTransport`-selected simulated vs `SocketFactory`-configured loopback socket transports.
- [Loopback Socket Transport Implementation Plan](design-and-implementation/ReconnectBench-loopback-socket-transport-implementation-plan.md) - Step-by-step execution plan for the benchmark-only loopback TCP transport validation work.
- [Socket-Buffer Read-Pacing Design (Option C)](design-and-implementation/ReconnectBench-socket-buffer-read-pacing-design.md) - Historical design for the removed periodic `W / RTT` read pacer that produced the 5M/10M socket-buffer evidence; superseded by refined A1 for the current `REALISTIC` profile.
- [Production Sync-Stream Reuse Implementation Plan](design-and-implementation/2026-07-16-reuse-production-sync-streams-implementation-plan.md) - Test-first implementation plan for making the socket transport honor production stream buffering, compression, and wire-byte counting.
- [1B Observational Reconnect Extraction Execution Plan](design-and-implementation/2026-07-17-1b-observational-extraction-execution-plan.md) - Phase-split execution plan for extracting the single large-state reconnect collection with layered recovery outcomes, standard reconnect evidence, SocketFactory telemetry, focused `ss -tinm` analysis, and fresh verification.

## Evidence And Calibration

- [Cluster ReconnectBench Artifact Manifest](evidence-and-calibration/cluster-reconnectbench-artifact-manifest.md) - Source-of-truth index of raw cluster artifact batches and traversal run roots to process.
- [Cluster ReconnectBench Artifact Processing Protocol](evidence-and-calibration/cluster-reconnectbench-artifact-processing-protocol.md) - Current extraction protocol for processing collected traversal-order cluster artifacts and mapping results back to local ReconnectBench runs.
- [Cluster ReconnectBench Artifact Atlas](evidence-and-calibration/cluster-reconnectbench-artifact-atlas.md) - Generic source-location map and template for manifest-listed cluster run artifacts.
- [Agentic Evidence Extraction Strategy](evidence-and-calibration/agentic-evidence-extraction-strategy.md) - Active operating procedure for agentic, Markdown-only cluster evidence extraction and verification.
- [Observational Extraction Profile](evidence-and-calibration/cluster-reconnectbench-observational-extraction-profile.md) - Reusable profile for factual extraction of a single large-state reconnect run, including layered recovery outcomes, SocketFactory lifecycle telemetry, compact `ss -tinm` aggregates and selected windows, and no calibration or traversal-ranking claims.
- [2026-07-16 1B Observational Reconnect Summary](evidence-and-calibration/extracted-cluster-evidence/2026-07-16-1b-observational/extraction-summary.md) - Verified single-run large-state extraction covering the unresolved final recovery outcome, 342 receiver attempts, SocketFactory lifecycle telemetry, and per-iteration focused `ss -tinm` evidence.
- [Global Cluster Evidence Summary](evidence-and-calibration/extracted-cluster-evidence/global-summary.md) - Batch-level index for extracted cluster evidence summaries.
- [2026-05-29 Cluster Calibration Batch Summary](evidence-and-calibration/extracted-cluster-evidence/2026-05-29-cluster-calibration/batch-summary.md) - Migrated summary for the initial traversal-order calibration batch.
- [Historical Cluster Metrics Analysis](evidence-and-calibration/historical-cluster-metrics-analysis.md) - Historical analysis of the deleted May 6, 2026 cluster metric artifact set, kept as calibration context.
- [Local ReconnectBench Calibration Notes](evidence-and-calibration/local-reconnectbench-calibration-notes/local-reconnectbench-calibration-notes.md) - Date-grouped local ReconnectBench calibration and validation notes, including averaged cluster-profile diagnostics.
- [Cluster-Evidence Local ReconnectBench Calibration Runs](evidence-and-calibration/local-reconnectbench-calibration-notes/2026-06-26-cluster-evidence-profile-run.md) - Local run log generated from the accepted May 29 cluster evidence profile, with fixed state parameters plus appendable traversal/network result tables.
- [2026-07-08 Read-Pacing Smoke Matrix](evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-08-read-pacing-smoke-matrix.md) - Initial 5M socket-buffer/read-pacing matrix with low-latency controls, high-latency candidate-binding cells, pacing diagnostics, and the limits imposed by its noise/confounds.
- [2026-07-16 Read-Pacing 10M Matrix](evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-read-pacing-10m-matrix.md) - Fresh internally paired 10M-state repeat of the socket-buffer/read-pacing matrix, including pacing diagnostics and comparison with the July 8 matrix shape.
- [2026-07-16 Compression 10M Comparison](evidence-and-calibration/local-reconnectbench-calibration-notes/2026-07-16-compression-10m-comparison.md) - Counterbalanced production sync-stream comparison on the same 10M state; compression reduced total wire bytes by 60.1% but increased median reconnect time by 71.8% at 270 us and 200 Mbit/s.
