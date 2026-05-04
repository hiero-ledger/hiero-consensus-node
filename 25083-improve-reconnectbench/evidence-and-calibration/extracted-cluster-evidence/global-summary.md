# Global Cluster Evidence Summary

Updated: `2026-06-04`

## Purpose

This file is the global index for extracted cluster ReconnectBench evidence batches.

Raw artifact roots are owned by
[Cluster ReconnectBench Artifact Manifest](../cluster-reconnectbench-artifact-manifest.md). Batch summaries should link
back to that manifest instead of duplicating raw artifact roots.

## Batch Index

| Batch ID | Status | Batch summary | Notes |
|---|---:|---|---|
| `2026-05-29-cluster-calibration` | extracted | [batch-summary.md](2026-05-29-cluster-calibration/batch-summary.md) | Initial traversal-order calibration batch. No valid three-mode ordering is available because parallel sync is `NETWORK_DISEASE_FATAL`, and two-phase is rejected for missing first-window passive TCP/window samples. |

## Trend Rule

Future trend/ranking tables should use completed, non-diseased learner catch-up episodes. A completed episode starts at
the first learner receiver reconnect start payload and ends at the last learner receiver reconnect finish payload before a
subsequent learner `ACTIVE` status. If no subsequent `ACTIVE` is observed, keep iteration-level evidence as diagnostic
only and exclude `completeCatchUpDuration` from trend/ranking.
