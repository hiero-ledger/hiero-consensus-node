# Decisions

Per-file ADRs. Each ADR records context, decision, alternatives considered, and consequences. Standard ADR pattern. This README is the chronological index.

## Naming convention

`ADR-NNN-short-slug.md`, where `NNN` is zero-padded to three digits (e.g., `ADR-007-replace-stale-events-detection.md`). Cross-references from other files use the ID only (e.g., "See ADR-007").

## Index

| ID                                                | Title                                                                             | Date       | Status   | Summary                                                                                                                                                            |
|---------------------------------------------------|-----------------------------------------------------------------------------------|------------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [ADR-003](ADR-003-remove-pces-recovery-method.md) | Remove `SwirldsPlatform.performPcesRecovery()` and Drive ISS Recovery On the Spot | 2026-05-19 | Accepted | The platform no longer carries a built-in offline ISS-recovery entry point. When recovery is needed, a one-off driver is written against the platform of the day. |
