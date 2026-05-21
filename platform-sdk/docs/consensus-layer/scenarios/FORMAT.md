# Scenarios — Entry Format

Specification for `scenarios/SCN-NNN-short-slug.md` entries. This file defines the
structure only; allowed values for the `symptoms` field live in the top-level
`symptoms.md` catalog (the same catalog `heuristics/` draws from).

## File naming

- `scenarios/SCN-NNN-short-slug.md`
- `NNN` is zero-padded to three digits (`SCN-001`, `SCN-014`).
- Cross-references from other files use the ID only (e.g., "See SCN-014").
- Allocate the next free `NNN` from `scenarios/README.md` and add its index row in
  the same change.

## Frontmatter

```yaml
---
id: SCN-NNN
title: Short descriptive title — what happens in this scenario
symptoms: [SYM-NNN]                   # observable signature; IDs from symptoms.md
topics: [reconnect, freeze-and-upgrade]
kind: historical-incident             # historical-incident | near-miss | edge-case-probe | hypothetical
verification: reasoned-only           # observed | test-reproduced | reasoned-only
severity: medium                      # critical | high | medium | low | informational
related:
  invariants: [INV-004]               # invariants this scenario stresses or violates
  decisions: [ADR-007]                # ADR that introduced the relevant guard/path
  scenarios: [SCN-009]                # related or contrasting scenarios
  tests: []                           # durable tests that exercise this, if any
status: draft                         # draft | reviewed | verified
provenance: war-story-interview-2026-05-xx
curated_by: Full Name (@github-handle) # the person responsible for this entry
---
```

Field discipline:

- **`symptoms`** — one or more `SYM-NNN` IDs from `symptoms.md`. This is the queryable
  link between a scenario and the heuristics or diagnostics that share its observable
  signature. Never free text; if the symptom is not yet catalogued, add it to
  `symptoms.md` first. If the real-world signature was never captured, leave the list
  empty and say so in the body — do not invent a symptom to fill the field.
- **`kind`** — the triage axis: what sort of scenario this is. Independent of
  `verification`. A remembered production incident is `historical-incident` even if
  nothing now confirms it.
- **`verification`** — the trust axis, independent of `kind`. `observed` (seen in a
  real incident or run), `test-reproduced` (a durable test drives it; that test belongs
  in `related.tests`), or `reasoned-only` (inferred from code/invariants, not verified).
  Anti-hallucination field: a `reasoned-only` scenario must never read as authoritative.
  Tools treat this field as load-bearing.
- **`severity`** — operator-facing impact. Drives Diagnostician ranking and Tutor case
  selection.
- **`status`** — every entry is born `draft`. `reviewed` = a second person has checked
  it for plausibility and internal consistency. `verified` = the sequence is backed by
  an `observed` incident or a `test-reproduced` run. A `reasoned-only` scenario can
  reach `reviewed` but never `verified`. Tools treat this field as load-bearing.
- **`provenance`** — traceability back to origin (interview date, Diagnostician handoff,
  Workbench session), so a future reader can re-check the scenario against its source.
  Same discipline as the heuristics catalog.
- **`curated_by`** — the person responsible for this entry now. See
  [LAYOUT.md](../LAYOUT.md#curator-and-decider-conventions) for the canonical
  format and the distinction from `provenance`.

## Body

Eight sections, in this order. Headings are mandatory even when content is not yet
known — write `Not captured`, `None — open`, or `Unknown` rather than deleting a
heading. An explicit unknown is information; tools and humans rely on stable section
order.

### `## Summary`

Two to three sentences, plain terms: what the scenario is and why it matters. A reader
decides relevance from this alone. No vocabulary that isn't in `glossary.md`.

### `## Setup`

Preconditions and trigger. Preconditions: the state required — configuration, node
count, tunable values (named as in `tunables.md`), lifecycle phase. Trigger: the one
specific event that sets it off, not a category. Mark any suspected-but-unconfirmed
precondition as such.

### `## Sequence`

Numbered, step by step. For a historical incident, the actual chronology; for an
edge-case probe, the observed-or-reasoned progression. Mark each step whose basis is
not self-evident: `(observed)` seen in a real incident or run, `(reasoned)` inferred
and not verified. Do not smooth over the observed/reasoned boundary — a reasoned
sequence that reads as fact is the catalog's primary failure mode.

### `## Observable signature`

What an engineer or operator actually sees: specific log lines, metric shapes, error
text, state-dump symptoms. This is the prose behind the `symptoms` tags and the
Diagnostician's match surface — be concrete. If never captured, write `Not captured`;
do not reconstruct a plausible-looking signature from imagination.

### `## Contributing factors`

The systemic conditions that allowed it — the "second story". Describe how the
situation arose, not who erred. Multiple factors are normal; a single root cause is
usually not the right shape. Theory-of-cause that isn't established belongs in Open
questions, flagged as theory — not here.

### `## Mitigation`

What prevents or limits it now: the guard, config change, or code path. Cross-reference
the `ADR-NNN` and `INV-NNN` where the fix or rule lives. `None — open` if unmitigated;
if partial, state what it does and does not cover.

### `## Verification`

Restate and justify the `verification` frontmatter value in prose. For `reasoned-only`,
state plainly that it is unverified and name what would verify it — the specific test
or observation. This section is the handoff to the Test Scaffold Generator.

### `## Open questions`

What is still unknown. Each item names what would answer it: a test, a person, a code
reading. Feeds the `questions/` artifacts and the Test Scaffold Generator.

### `## Notes` (optional)

Curation trail and anything that doesn't fit above: one line per change
(`date — what changed — who`); the first line records origin and the observed/reasoned
split at creation.

## Skeleton

```markdown
---
id: SCN-NNN
title: ...
symptoms: [SYM-NNN]
topics: [...]
kind: historical-incident
verification: reasoned-only
severity: medium
related:
  invariants: []
  decisions: []
  scenarios: []
  tests: []
status: draft
provenance: ...
curated_by: ...
---

# SCN-NNN — Title

## Summary
...

## Setup
...

## Sequence
1. ...
2. ...

## Observable signature
...

## Contributing factors
...

## Mitigation
...

## Verification
...

## Open questions
...

## Notes
...
```
