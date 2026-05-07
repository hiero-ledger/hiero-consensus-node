# Consensus Layer Knowledge Base

Canonical structure for the consensus-layer knowledge base. Tools (Tutor, Workbench, Test Scaffold, Diagnostician, Change Reviewer) read from this structure; humans navigate it directly. Stable cross-references depend on the conventions captured here and in the per-directory READMEs.

## Layout

| Path | Contents |
|---|---|
| `concepts/` | Foundational definitions and canonical mental models. |
| `glossary.md` | Single-file catalog of ~50 terms (pending). |
| `architecture/` | Topic-organized lens on the consensus layer (overview, 11 topics, interfaces). |
| `invariants.md` | Single-file catalog of INV-NNN entries (pending). |
| `decisions/` | Per-file ADRs. |
| `tunables.md` | Single-file catalog of configurable parameters (pending). |
| `delta-map/` | Per-topic status of "current code vs. proposed design". |
| `scenarios/` | Per-file scenario entries (SCN-NNN). |
| `questions/` | Sprint Q&A artifacts. |
| `tutor/` | Curriculum content for the Tutor tool (internal structure deferred). |

## Tool consumers

Each tool draws from a specific subset of the KB:

| Tool | Reads from |
|---|---|
| Tutor | `concepts/`, `glossary.md`, `architecture/`, `tutor/` |
| Workbench | `architecture/`, `decisions/`, `delta-map/`, `scenarios/` |
| Test Scaffold | `architecture/`, `invariants.md`, `scenarios/` |
| Diagnostician | `architecture/`, `invariants.md`, `tunables.md`, `scenarios/`, `delta-map/` |
| Change Reviewer | `architecture/`, `invariants.md`, `decisions/`, `delta-map/` |

## Conventions

- Catalog entries use sequential, zero-padded IDs with descriptive slugs (`ADR-007-short-slug.md`, `SCN-012-short-slug.md`).
- Architecture topic and delta-map files are named after the topic, no ID prefix (e.g., `architecture/topics/hashgraph.md`, `delta-map/hashgraph.md`).
- Cross-references from other files use the ID only (e.g., "See ADR-007").
- Every populated directory has a `README.md` index that maps IDs/files to titles and summaries.
