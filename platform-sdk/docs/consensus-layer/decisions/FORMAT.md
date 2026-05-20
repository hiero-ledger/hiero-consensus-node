# Decisions — Entry Format

Specification for `decisions/ADR-NNN-short-slug.md` entries. This file defines
the structure only; the chronological catalog of allocated IDs, titles, topics,
dates, statuses, and one-line summaries lives in `decisions/README.md`.

## File naming

- `decisions/ADR-NNN-short-slug.md`
- `NNN` is zero-padded to three digits (`ADR-001`, `ADR-027`).
- The slug is a short kebab-case phrase that hints at the subject (e.g.,
  `pces-state-snapshot-coordination`).
- Cross-references from other files use the ID only (e.g., "See ADR-007").
- Allocate the next free `NNN` from `decisions/README.md` and add its index
  row in the same change.

## Frontmatter

```yaml
---
id: ADR-NNN
title: Short title — what the decision is about
topics: [event-intake, gossip]        # topic slugs this decision touches
related:
  invariants: []                       # optional; invariants this decision protects or relaxes
  decisions: []                        # optional; supersedes / superseded-by / related ADRs
  scenarios: []                        # optional; incidents that motivated this
  heuristics: []                       # optional; heuristics affected by this
  rules: []                            # optional; rules introduced or retired by this
status: accepted                       # proposed | accepted | superseded | deprecated
date: 2026-05-08                       # decision date, ISO YYYY-MM-DD
deciders:                              # people responsible for the decision
  - Michael Heinrichs (@netopyr)
  - Kelly Greco (@poulok)
curated_by: Kelly Greco (@poulok)      # who maintains this entry
provenance: design-session-2026-05-08  # optional; source discussion if recorded
---
```

Field discipline:

- **`status`** — every entry is born `proposed`. It becomes `accepted` once
  the deciders have signed off. `superseded` marks a decision replaced by a
  later ADR — name the successor under `related.decisions` and reference it
  in the body. `deprecated` marks a decision that no longer applies because
  the underlying code or design has changed but no replacement decision
  exists. Tools and readers treat this field as load-bearing — keep it in
  sync with the README row.
- **`date`** — ISO date of the decision (not file creation). If the decision
  is materially revised, update both this date and the body, and add a line
  to `## Notes` so the trail is preserved.
- **`deciders`** — list of the people responsible for the decision, each
  formatted as `Full Name (@github-handle)`. Both authorship and
  accountability live here; if the two differ in a meaningful way, note it
  in the body.
- **`curated_by`** — the person who maintains this entry, formatted as
  `Full Name (@github-handle)`. Often (but not necessarily) one of the
  deciders. Mirrors the `curated_by` field used in `scenarios/`.
- **`provenance`** — optional. ADRs are themselves the canonical record, so
  a separate provenance is useful only when there is a discussion, RFC, or
  thread worth pointing back to.
- **no `class` field** — ADRs are too heterogeneous to slot into a small
  fixed vocabulary; the `topics:` axis gives the useful slice.
- **no `confidence` field** — once accepted, the team has committed. Strength
  of belief is not the right axis for a decision.

## Body

Sections appear in the order below. `Context`, `Decision`, and `Consequences`
are mandatory. `Alternatives Considered` is mandatory whenever realistic
alternatives existed (almost always). The remaining sections are optional and
appear only when they have content.

### `# ADR-NNN — Title`

The H1 line. `<Title>` is a short, declarative phrase naming what the ADR
decides — the same string used in the README index row and the `title:`
frontmatter field. The ID appears in the heading to match `# HEU-NNN — Title`,
`# INV-NNN — Title`, `# RUL-NNN — Title`, and `# SCN-NNN — Title`.

### `## Context`

The forces in play: the system as it stands, the problem or pressure that
prompted the decision, the constraints. State the discriminating facts a
reader needs to evaluate the decision — what is true today, what was observed,
what is required. Subsections (`###`) are allowed when the context has
distinct parts. This section is descriptive, not prescriptive: no decision
verbs here.

### `## Decision`

What was decided, in imperative voice ("Do X", "Block Y until Z"). Lead with
the decision itself in one or two sentences a reader can quote, then add the
mechanism — how the decision is realized in code, configuration, or process.
Be specific enough that an implementer can act on it without re-deriving the
reasoning.

### `## Temporary Nature` (optional)

Include only if the decision is known to be temporary. State the condition
that will retire it (e.g., a future capability, a planned migration) and what
should replace it. A decision without a stated retirement condition is not
temporary — omit this section.

### `## Limitations` (optional)

Residual risks or guarantees the decision does **not** provide. Use this to
make the boundary of the decision explicit, so a future reader does not
assume properties that were never claimed. If there are no notable
limitations beyond those captured under `### Negative` consequences, omit.

### `## Consequences`

What follows from the decision, grouped into three required subsections:

- **`### Positive`** — what the decision buys, in concrete terms (specific
  property preserved, work avoided, risk eliminated). Avoid generic praise.
- **`### Negative`** — what the decision costs, including the failure modes
  that are now accepted. Each item should be acknowledgeable: a future reader
  can point to it and say "yes, we knew."
- **`### Neutral`** — facts that are neither wins nor losses but a reader
  needs to know (soft conventions established, future expectations created,
  properties that are now assumed but not enforced).

Empty subsections may be marked `None.` rather than deleted; the three-way
split is part of the format.

### `## Alternatives Considered`

Numbered subsections (`### 1. <name>`, `### 2. <name>`, …). For each, state
the alternative in one or two sentences, then `**Rejected because:**` (or
`**Selected because:**` for the chosen option) followed by a bulleted list of
reasons. Include the status-quo / do-nothing option explicitly when it was on
the table — readers should see it was weighed, not ignored. The chosen
alternative appears as the last numbered entry and points back to
`## Decision` rather than restating it.

### `## References` (optional)

Links to related documents, code paths, and specs that the reader may need to
follow up on. Use relative paths for in-repo links. Annotate each link with a
short note on what the reader will find there — bare links rot fastest.
Structured cross-references to other catalog entries (ADRs, invariants,
scenarios, heuristics, rules) belong in the frontmatter `related:` block;
this section is for prose context and external pointers.

### `## Notes` (optional)

Curation trail, revision history, and anything that doesn't fit above: one
line per change (`date — what changed — who`). Omit if empty.

## Skeleton

```markdown
---
id: ADR-NNN
title: ...
topics: [...]
related:
  invariants: []
  decisions: []
  scenarios: []
  heuristics: []
  rules: []
status: proposed
date: YYYY-MM-DD
deciders:
  - Full Name (@handle)
curated_by: Full Name (@handle)
---

# ADR-NNN — Title

## Context

...

## Decision

...

## Consequences

### Positive

- ...

### Negative

- ...

### Neutral

- ...

## Alternatives Considered

### 1. <alternative>

...

**Rejected because:**

- ...

### 2. Status quo — <what doing nothing looks like>

...

**Rejected because:**

- ...

### 3. <chosen option>

See **Decision** above.

## References

- ...

## Notes

- ...
```
