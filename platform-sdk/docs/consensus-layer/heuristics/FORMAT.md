# Heuristics — Entry Format

Specification for `heuristics/HEU-NNN-short-slug.md` entries. This file defines the
structure only; allowed values for the `symptoms` field live in the top-level
`symptoms.md` catalog.

## File naming

- `heuristics/HEU-NNN-short-slug.md`
- `NNN` is zero-padded to three digits (`HEU-001`, `HEU-027`).
- Cross-references from other files use the ID only (e.g., "See HEU-014").

## Frontmatter

```yaml
---
id: HEU-NNN
title: Short imperative title — what the heuristic is about
symptoms: [SYM-NNN]                   # one or more IDs from symptoms.md
topics: [event-intake, gossip]        # topic slugs this heuristic touches
related:
  scenarios: [SCN-012]                 # optional; instances this generalizes from
  invariants: [INV-004]                # optional; invariants it helps protect
  decisions: []                        # optional
status: unvalidated                    # unvalidated | validated | retired
confidence: medium                     # low | medium | high
provenance: elicitation-2026-05-xx     # where this entry came from
---
```

Field discipline:

- **`symptoms`** — one or more `SYM-NNN` IDs from `symptoms.md`. This is the queryable
  link between entries that share an observable symptom. Never free text; if the symptom
  is not yet in `symptoms.md`, add it there first.
- **`status`** — every entry is born `unvalidated`. It becomes `validated` only when a
  real instance has confirmed it (a scenario, a Diagnostician handoff, a post-mortem).
  `retired` marks a rule superseded by a code or design change. Tools treat this field
  as load-bearing.
- **`confidence`** — the author's strength of belief, independent of `status`. A
  high-confidence unvalidated entry is still unvalidated; `confidence` is advisory only.
- **`provenance`** — traceability back to the source (elicitation session date, SCN ID,
  Diagnostician run), so a future reader can re-check the rule against its origin.

## Body

Exactly three sections, in this order.

### `## Observable Symptom`

The observable condition that brings this heuristic into play, in prose. State the
discriminating detail: what distinguishes this real pattern from superficially similar
but benign cases. The `symptoms` frontmatter tag is the coarse, queryable handle; this
prose is where the fine distinction lives.

### `## Suspected cause`

The likely underlying cause(s) this symptom points to, ordered most-likely first. State
the mechanism briefly — why this symptom implies this cause.

### `## Validation`

The concrete next step(s) to confirm or rule out the suspected cause: what to check,
what result confirms, what result exonerates. Without this section the entry is an
observation, not a heuristic.

### `## Notes` (optional)

Cost asymmetry, known false-alarm conditions, when NOT to apply this, links to related
HEU entries.

## Skeleton

```markdown
---
id: HEU-NNN
title: ...
symptoms: [SYM-NNN]
topics: [...]
related:
  scenarios: []
  invariants: []
  decisions: []
status: unvalidated
confidence: medium
provenance: ...
---

# HEU-NNN — Title

## Observable Symptom
...

## Suspected cause
...

## Validation
...

## Notes
...
```
