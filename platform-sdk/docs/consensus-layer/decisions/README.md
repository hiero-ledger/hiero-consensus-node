# Decisions

Per-file ADRs. Each ADR records context, decision, alternatives considered, and consequences. Standard ADR pattern. This README is the chronological index.

## Naming convention

`ADR-NNN-short-slug.md`, where `NNN` is zero-padded to three digits (e.g., `ADR-007-replace-stale-events-detection.md`). Cross-references from other files use the ID only (e.g., "See ADR-007").

## Index

|   ID    |                                                      Title                                                      |    Date    |  Status  |                                                                                              Summary                                                                                               |
|---------|-----------------------------------------------------------------------------------------------------------------|------------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ADR-003 | [Clear Judge Metadata When a Same-Round Judge Is in Ancestry](ADR-003-clear-judge-metadata-on-roster-change.md) | 2026-05-15 | Accepted | Clear a judge's metadata at the start of a round if it has another judge from the same round in its ancestry, so `roundCreated(child) >= roundCreated(parent)` is preserved across roster changes. |
