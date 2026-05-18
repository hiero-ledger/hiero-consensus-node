# Decisions

Per-file ADRs. Each ADR records context, decision, consequences, and (where applicable) alternatives considered. Standard ADR pattern. This README is the chronological index.

## Naming convention

`ADR-NNN-short-slug.md`, where `NNN` is zero-padded to three digits (e.g., `ADR-007-replace-stale-events-detection.md`). Cross-references from other files use the ID only (e.g., "See ADR-007").

## Index

|                             ID                              |                                            Title                                             |    Date    |  Status  |                                                                                                                                                   Summary                                                                                                                                                    |
|-------------------------------------------------------------|----------------------------------------------------------------------------------------------|------------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [ADR-001](ADR-001-pces-state-snapshot-coordination.md)      | No Coordination Between PCES Writer and Signed State Writer for Snapshot PCES Copy           | 2026-05-08 | Accepted | The signed state writer copies PCES files into the snapshot directory on a best-effort basis after the merkle state is written, accepting occasional copy failures rather than coordinating with the PCES writer.                                                                                            |
| [ADR-002](ADR-002-execution-freeze-signature-handoff.md)    | Block `onSealConsensusRound` to Hand Off Freeze Block Signatures from Execution to Consensus | 2026-05-12 | Accepted | The execution layer blocks the return of `onSealConsensusRound` at freeze time until its freeze-block signature transaction is in the pool and a threshold of freeze-block signatures has been collected, bounded by a maximum timeout. Temporary; to be removed once blocks are signed with TSS signatures. |
| [ADR-003](ADR-003-clear-judge-metadata-on-roster-change.md) | Clear Judge Metadata When a Same-Round Judge Is in Ancestry                                  | 2026-05-15 | Accepted | Clear a judge's metadata at the start of a round if it has another judge from the same round in its ancestry, so `roundCreated(child) >= roundCreated(parent)` is preserved across roster changes.                                                                                                           |
