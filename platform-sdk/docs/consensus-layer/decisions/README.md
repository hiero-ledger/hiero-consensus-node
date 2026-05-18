# Decisions

Per-file ADRs. Each ADR records context, decision, alternatives considered, and consequences. Standard ADR pattern. This README is the chronological index.

## Naming convention

`ADR-NNN-short-slug.md`, where `NNN` is zero-padded to three digits (e.g., `ADR-007-replace-stale-events-detection.md`). Cross-references from other files use the ID only (e.g., "See ADR-007").

## Index

| ID | Title | Date | Status | Summary |
|----|-------|------|--------|---------|
| [ADR-001](ADR-001-pces-state-snapshot-coordination.md) | No Coordination Between PCES Writer and Signed State Writer for Snapshot PCES Copy | 2026-05-08 | Accepted | The signed state writer copies PCES files into the snapshot directory on a best-effort basis after the merkle state is written, accepting occasional copy failures rather than coordinating with the PCES writer. |
| [ADR-002](ADR-002-execution-freeze-signature-handoff.md) | Block `onSealConsensusRound` to Hand Off Freeze Block Signatures from Execution to Consensus | 2026-05-12 | Accepted | The consensus layer blocks in `onSealConsensusRound` at freeze time until execution submits its freeze-block signature transaction, ensuring the signature is gossiped before event creation stops at `FREEZE_COMPLETE`. |
