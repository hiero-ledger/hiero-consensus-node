# Consensus Layer KB — Layout

## Purpose

Canonical structure for the consensus-layer knowledge base in the repo. Tools (Tutor, Workbench, Test Scaffold, Diagnostician, Change Reviewer) read from this structure; humans navigate it directly. Stable cross-references depend on the conventions below.

## Scope

The consensus layer of the platform-sdk — the topics under `architecture/topics/` and the cross-cutting catalogs that support them. Out of scope: execution-layer internals, block production, TSS, transaction-handling internals, application semantics.

## Directory tree

```
platform-sdk/docs/consensus-layer/
├── README.md                              entry point: navigation; which sections each tool consumes
│
├── concepts/                              foundational concepts, canonical definitions
│   ├── README.md
│   ├── hashgraph-dag.md
│   ├── rounds-and-witnesses.md
│   ├── strongly-seeing.md
│   ├── birth-round.md
│   ├── coin-rounds.md
│   ├── judges.md
│   ├── voting.md
│   ├── event-lifecycle.md
│   ├── stale-events.md
│   └── branching.md
│
├── glossary.md                            single file, ~50 terms
├── symptoms.md                            single file (catalog of SYM-NNN entries)
├── tunables.md                            single file (catalog of parameters)
│
├── architecture/
│   ├── README.md
│   ├── overview.md                        high-level shape; adapts from Consensus-Layer.md
│   ├── topics/                            one file per topic
│   │   ├── wiring-framework.md
│   │   ├── gossip.md
│   │   ├── event-intake.md
│   │   ├── event-creator.md
│   │   ├── hashgraph.md
│   │   ├── health-monitor-and-backpressure.md
│   │   ├── reasons-not-to-gossip.md
│   │   ├── quiescence.md
│   │   ├── signed-state-management.md
│   │   ├── iss-detection.md
│   │   ├── restart-and-pces.md
│   │   ├── freeze-and-upgrade.md
│   │   └── reconnect.md
│   └── interfaces/
│       └── consensus-execution-boundary.md
│
├── decisions/                             per-file ADRs (ADR-NNN)
│   ├── README.md
│   ├── FORMAT.md
│   └── ADR-NNN-short-slug.md
│
├── invariants/                            per-file invariants (INV-NNN)
│   ├── README.md
│   ├── FORMAT.md
│   └── INV-NNN-short-slug.md
│
├── rules/                                 per-file rules (RUL-NNN)
│   ├── README.md
│   ├── FORMAT.md
│   └── RUL-NNN-short-slug.md
│
├── scenarios/                             per-file scenario entries (SCN-NNN)
│   ├── README.md
│   ├── FORMAT.md
│   └── SCN-NNN-short-slug.md
│
├── heuristics/                            per-file heuristic entries (HEU-NNN)
│   ├── README.md
│   ├── FORMAT.md
│   └── HEU-NNN-short-slug.md
│
├── delta-map/                             one file per topic, flat
│   ├── README.md
│   ├── FORMAT.md
│   ├── wiring-framework.md
│   ├── gossip.md
│   ├── event-intake.md
│   ├── event-creator.md
│   ├── hashgraph.md
│   ├── health-monitor-and-backpressure.md
│   ├── reasons-not-to-gossip.md
│   ├── quiescence.md
│   ├── signed-state-management.md
│   ├── iss-detection.md
│   ├── restart-and-pces.md
│   ├── freeze-and-upgrade.md
│   ├── reconnect.md
│   └── sheriff.md
│
└── tutor/                                 curriculum content; internal structure deferred
```

`tools/` is intentionally absent — see deferred decisions below.

## File naming and IDs

- Catalog entries use sequential IDs with descriptive slugs:
  - ADRs: `decisions/ADR-NNN-short-slug.md`
  - Invariants: `invariants/INV-NNN-short-slug.md`
  - Rules: `rules/RUL-NNN-short-slug.md`
  - Scenarios: `scenarios/SCN-NNN-short-slug.md`
  - Heuristics: `heuristics/HEU-NNN-short-slug.md`
- IDs are zero-padded to three digits.
- Titles live in YAML frontmatter or as the H1 inside the file.
- Cross-references from other files use the ID only (e.g., "See ADR-007").
- Architecture topic files and delta-map files are named directly after the topic (no ID prefix), e.g., `architecture/topics/hashgraph.md`, `delta-map/hashgraph.md`.
- Single-file catalogs (`symptoms.md`, `tunables.md`) carry sequential IDs (`SYM-NNN`, etc.) within their internal tables. Cross-references use the ID only.

## Curator and decider conventions

People are recorded in frontmatter using the format **`Full Name (@github-handle)`** (e.g., `Artur Biesiadowski (@abies)`).

- **`curated_by`** — required on every catalog entry that carries frontmatter (`invariants/`, `rules/`, `scenarios/`, `heuristics/`, `decisions/`). The person responsible for the entry now: who to ask whether it still holds. Scalar by default; a YAML list is allowed when curation is genuinely joint, but the field is not a touch-log — git records authorship.
- **`deciders`** — required on `decisions/` entries only. The accountable group for the decision. YAML list, even for a single decider, for consistent shape. Replaces the prose "Authors / Deciders" body section that ADRs previously carried; that section is dropped from the body once `deciders` is in frontmatter.
- **`provenance`** — distinct from `curated_by`. Captures the *source* an entry was drawn from (an elicitation date, an SME interview, a war-story session), not the person currently responsible. If a scribe writes up another person's tribal knowledge, the scribe is in git's author history and the SME's name belongs in `provenance`, not `curated_by`.

`curated_by` and `deciders` may differ on an ADR, and that is fine: it is the case where the writer or current owner of the doc is not the person on the hook for the decision.

## Section conventions

### `README.md` (top-level)

Entry point. What's in this directory; how to navigate; which sections each tool consumes.

### `concepts/`

Foundational definitions and canonical mental models. One file per concept (hashgraph DAG, rounds and witnesses, strongly-seeing, birth-round, etc.). Used by the Tutor curriculum to ground later content.

### `glossary.md`

Single file. ~50 terms. The canonical definition for each term referenced anywhere else in the KB. Disambiguates overloaded vocabulary (round / consensus round / birth round; ancient / expired / stale). Still being worked on; shape (one file in the KB root) is committed.

### `symptoms.md`

Single file. Controlled vocabulary of observable symptoms (`SYM-NNN`) referenced by per-file catalogs that key off observation — currently `heuristics/` and `scenarios/`. It is an index, not a knowledge file: it carries no diagnostic content of its own. The knowledge — suspected cause, validation, incident timeline — lives in the `heuristics/` and `scenarios/` entries that cite the `SYM-NNN`. Each entry has an ID, name, description, and source of observation. A symptom here is something observable and recorded, independent of cause; many heuristics or scenarios may share one symptom, and that is the point of the catalog.

### `architecture/`

The topic-organized lens on the consensus layer.
- `architecture/overview.md` — adapts the high-level shape from `Consensus-Layer.md` for KB use.
- `architecture/topics/` — one file per topic. Each describes the topic's responsibilities, state, contracts, and links to related concepts, invariants, decisions, and scenarios.
- `architecture/interfaces/consensus-execution-boundary.md` — the Consensus public API (`initialize`, `destroy`, `nextRound`, `onBehind`, `onPreHandleEvent`, `getTransactionsForEvent`, etc.).

### `decisions/`

Per-file ADRs. Each ADR records context, decision, alternatives considered, and consequences. Standard ADR pattern. Follows the catalog convention: `README.md` is the chronological index, `FORMAT.md` specifies the entry shape.

### `invariants/`

Per-file catalog of design-guaranteed properties of the form **this is true, and stays true under any correct implementation**. Each entry has an ID (`INV-NNN`), statement, design argument (no code citation allowed — that is the discriminator from `rules/`), and account of what its violation would mean. An invariant observed false means the system is wrong, not that the documentation is stale. Load-bearing for the Change Reviewer, Diagnostician, and Test Scaffold tools. Pairs with `rules/`: an entry that turns out to be implementation-contingent is reclassified into `rules/`.

### `rules/`

Per-file catalog of implementation-true properties of the form **this is true of the code as it stands, and a correct change could make it false**. Each entry has an ID (`RUL-NNN`), statement, code anchor (`components:`), `status`, and account of what would break it. A rule observed false is a divergence signal — the code moved, or the rule was mis-stated — not, by itself, a bug. Pairs with `invariants/`: an entry that turns out to be design-guaranteed is promoted to `invariants/`.

### `tunables.md`

Single file. Catalog of configurable parameters with effects, ranges, and fragility (`N`, `Z`, `max_event_creation_frequency`, health-monitor thresholds, etc.).

### `scenarios/`

Per-file scenario entries. Each captures an edge case, near-miss, or historical incident with timeline, observable signature, contributing factors, and (where known) mitigation. Linked to `symptoms.md` by `SYM-NNN` tags. Catalog grows by manual curation only (war-story interviews, Diagnostician/Workbench handoffs); nothing auto-feeds it.

### `heuristics/`

Per-file heuristic entries of the form **observable symptom → suspected cause → validation**. Pattern-match diagnostic knowledge that the team otherwise carries as tribal lore. Linked to `symptoms.md` by `SYM-NNN` tags. Entries are born `unvalidated` and become `validated` only when a real instance confirms them. Load-bearing for the Diagnostician and Change Reviewer.

### `delta-map/`

Per-topic status of "current code vs. proposed design": done / partial / not-started / divergent. One flat file per architecture topic, plus `sheriff.md` for a proposal-only module with no architecture topic yet.

### `tutor/`

Curriculum content for the Tutor tool. Internal organization deferred — left to the Tutor implementation.

## Deferred decisions

### `tools/`

Location of tool configurations and briefs (Tutor, Workbench, Test Scaffold, Diagnostician, Change Reviewer) is deferred. The team's convention typically splits code from docs; the right home for each tool's `CLAUDE.md` and brief will be decided when the tools are specified in detail.

### Tutor internal structure

Internal organization of `tutor/` is left to the Tutor implementation — authoring will reveal what shape works.

## Index and format conventions

Every populated directory has a `README.md` that serves as the canonical index — a table mapping IDs (where applicable) to titles, with brief descriptions. Tools cross-reference by ID; humans navigate by title in listings.

Every catalog directory with per-file entries (currently `decisions/`, `invariants/`, `rules/`, `scenarios/`, `heuristics/`, `delta-map/`) additionally carries a `FORMAT.md` that specifies the entry shape — file naming, frontmatter, mandatory body sections, status discipline. The `README.md` is the catalog; the `FORMAT.md` is the schema. New entries are checked against `FORMAT.md`; tools that read the catalog rely on its conventions holding.

### Frontmatter conventions

`type` is the first key in every non-scaffolding `.md` frontmatter block. Non-scaffolding
means every file except `README.md`, `FORMAT.md`, and `LAYOUT.md`. The value is lowercase
and fixed per document class.

Two header orderings apply:

- **Catalog entries** (`decisions/`, `invariants/`, `rules/`, `scenarios/`, `heuristics/`):
  `type` / `id` / `title` / … (all other existing fields unchanged)
- **Narrative and single-file catalog files** (`concepts/`, `architecture/**`, `glossary.md`,
  `symptoms.md`, `tunables.md`): `type` / `title` / `description` (catalog files only) /
  `last_reviewed`

Type vocabulary:

|          Path pattern          |       `type` value       |
|--------------------------------|--------------------------|
| `concepts/*.md`                | `concept`                |
| `glossary.md`                  | `glossary`               |
| `architecture/overview.md`     | `architecture-overview`  |
| `architecture/interfaces/*.md` | `architecture-interface` |
| `architecture/topics/*.md`     | `architecture-topic`     |
| `decisions/ADR-*.md`           | `decision`               |
| `invariants/INV-*.md`          | `invariant`              |
| `rules/RUL-*.md`               | `rule`                   |
| `scenarios/SCN-*.md`           | `scenario`               |
| `heuristics/HEU-*.md`          | `heuristic`              |
| `delta-map/*.md`               | `delta-map`              |
| `symptoms.md`                  | `symptom-catalog`        |
| `tunables.md`                  | `tunable-catalog`        |

## Canonicalization and non-duplication

Each fact has exactly one canonical home in this KB. Every other mention
references that home — by ID, by link, or by name — rather than restating the
fact. This is the structural rule that keeps the KB maintainable: when a fact
changes, there is one place to change it, not a scattered set that must all be
found and edited in lockstep.

Canonical homes, by kind of fact:

|              Kind of fact              |                          Canonical home                          |
|----------------------------------------|------------------------------------------------------------------|
| Term definition / mental model         | `glossary.md` (term), `concepts/` (model)                        |
| Observable symptom                     | `symptoms.md` (`SYM-NNN`)                                        |
| Configurable parameter, threshold      | `tunables.md`                                                    |
| Why a load-bearing choice was made     | `decisions/` (`ADR-NNN`)                                         |
| Property true under any correct impl   | `invariants/` (`INV-NNN`)                                        |
| Property true of the code as it is     | `rules/` (`RUL-NNN`)                                             |
| Catalog entry title + one-line summary | the entry file (mirrored into its `README.md` index — see below) |

### Narrative restatement vs. duplicated source of truth

Repetition is not absolutely forbidden — an ADR or rule may restate orienting
context so a human can read it without chasing links, and that is fine. The
line is between two kinds of repetition:

- **Narrative restatement** — a prose summary that orients the reader. Allowed.
  Keep it a summary, and link to the canonical home for the authoritative version.
- **Duplicated source of truth** — a load-bearing value copied verbatim: a
  parameter value or threshold, an ID, a file path, a `status`, a commit hash.
  **Not allowed.** State it once in its canonical home and reference it.

The test: *if this fact changes, must I also edit it here?* If yes, it is a
duplicated source of truth and must become a reference instead.

### Sanctioned duplication

One duplication is built into the structure: each catalog entry's title and
one-line summary appear both in the entry file and in its `README.md` index
row. This is deliberate — the index is the navigable catalog. Because it is a
duplication, it carries a sync obligation: the index row is updated in the same
change as the entry it describes, never separately.

The procedure a contributor (human or agent) follows to honor these rules —
including how to find every existing reference before changing a fact — lives in
[`CLAUDE.md`](CLAUDE.md). This section states the rule; that file states how to apply it.

## When to update this file

This document is the structural contract for the KB; structural changes should land in the same PR as a corresponding update here. Cases that require an update:

- **New catalog directory** — add a section under "Section conventions" and update the per-file-catalog list under "Index and format conventions".
- **New entry kind or ID prefix** — add to "File naming and IDs".
- **Move path or discriminator change between catalogs** — update the affected sections so the rule for sorting entries between them stays explicit.
- **Deferred decision resolved** — move it out of "Deferred decisions" into the relevant convention section.
- **Convention change** (e.g., FORMAT.md requirement, frontmatter pattern, README index shape) — update wherever the convention is stated, and check for restatements that need to follow.
- **Frontmatter-pattern change** — any change to the `type` vocabulary, or the header ordering.

Non-structural content updates — new ADRs, new scenarios, new lessons, new entries to existing catalogs — do not require an update here. That is what the catalog READMEs are for.
