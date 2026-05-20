# Consensus Layer Knowledge Base

Reference documentation for the consensus layer of the platform-sdk — the subsystem that reaches Byzantine-fault-tolerant agreement on event ordering using a hashgraph-based algorithm.

The KB covers eleven topics — wiring, gossip, event intake, event creation, the hashgraph algorithm, health monitoring and backpressure, reasons not to gossip, signed state management, restart and PCES, freeze and upgrade, and reconnect — and the cross-cutting catalogs that support them.

**Current implementation is canonical.** Entries describe what runs today, anchored to specific files, classes, and methods. The proposed future shape from `../proposals/consensus-layer/Consensus-Layer.md` appears only as clearly-marked sidebars in topic files and is tracked per-topic under `delta-map/`.

For the structural contract — file naming, ID conventions, catalog formats, the discriminator rules between catalogs (invariant vs. rule, scenario vs. heuristic), and what changes require a structural update — see [LAYOUT.md](LAYOUT.md).

## What's here

|      Path       |                                                                                Contents                                                                                 |
|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `architecture/` | Topic-organized lens on the consensus layer: `overview.md`, one file per topic in `topics/`, and module-boundary APIs in `interfaces/`. The recommended starting point. |
| `concepts/`     | Foundational mental models — hashgraph DAG, rounds and witnesses, strongly-seeing, judges, birth round, voting, coin rounds, event lifecycle, stale events.             |
| `glossary.md`   | Consensus-specific vocabulary.                                                                                                                                          |
| `symptoms.md`   | Controlled vocabulary of observable symptoms (`SYM-NNN`), referenced by `scenarios/` and `heuristics/`.                                                                 |
| `tunables.md`   | Catalog of configurable parameters with effects, ranges, and fragility.                                                                                                 |
| `decisions/`    | Per-file ADRs (`ADR-NNN`) — why a load-bearing choice was made and what was considered.                                                                                 |
| `invariants/`   | Per-file design-guaranteed properties (`INV-NNN`) — claims that hold under any correct implementation.                                                                  |
| `rules/`        | Per-file implementation-true properties (`RUL-NNN`) — claims that hold for the code as it stands, anchored to a verifying commit.                                       |
| `scenarios/`    | Per-file edge cases, near-misses, and historical incidents (`SCN-NNN`) with timeline, observable signature, and mitigation.                                             |
| `heuristics/`   | Per-file diagnostic patterns (`HEU-NNN`): observable symptom → suspected cause → validation.                                                                            |
| `delta-map/`    | Per-topic status of current code vs. proposed design — done, partial, not started, or divergent.                                                                        |
| `tutor/`        | Curriculum content for learners new to the consensus layer.                                                                                                             |

## Finding your way

- **Touring the system**: start at `architecture/overview.md`, then the topic files under `architecture/topics/`.
- **Definitions**: `glossary.md` for vocabulary, `concepts/` for mental models that take more than a sentence.
- **Rationale questions** ("why is N small?", "why does PCES gate gossip?"): `decisions/`.
- **Reasoning about a code change**: `invariants/` for what must hold under any correct implementation; `rules/` for what holds in the code as it stands; `delta-map/` for where the current code stands against the proposed design.
- **Investigating observed behavior**: `scenarios/` for known incidents and edge cases; `heuristics/` for symptom-keyed pattern-match. Both link to `symptoms.md` by `SYM-NNN`.

Every populated subdirectory has a `README.md` that indexes its contents.
