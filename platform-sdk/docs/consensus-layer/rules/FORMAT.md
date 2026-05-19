# Rules — Entry Format

Specification for `rules/RUL-NNN-short-slug.md` entries. This file defines the
structure only; allowed values for the `topics` field live in the top-level
`topics.md` catalog. The test for whether something is a rule rather than an
invariant is defined in `README.md` — apply it before filing here.

## File naming

- `rules/RUL-NNN-short-slug.md`
- `NNN` is zero-padded to three digits (`RUL-001`, `RUL-027`).
- Cross-references from other files use the ID only (e.g., "See RUL-014").

## Frontmatter

```yaml
---
id: RUL-NNN
title: Short declarative title — the property in a phrase
class: structural                 # structural | protocol | state-machine | config | operational | determinism
topics: [event-intake]            # topic slugs this rule touches
components:                       # code this rule depends on — required
  - consensus-hashgraph-impl/.../DefaultConsensusEngine.java
related:
  invariants: [INV-004]            # optional; invariants this rule helps enforce
  decisions: []                    # optional
  scenarios: []                    # optional
  heuristics: []                   # optional
status: holds                      # holds | divergent | retired
confidence: medium                 # low | medium | high
provenance: extraction-2026-05-xx  # where this entry came from
---
```

Field discipline:

- **`class`** — the kind of implementation property. Drives coverage review.
  Never free text.
- **`components`** — the code paths the rule depends on. This is the
  queryable link the Change Reviewer uses to map a changed file to the rules
  at risk, and it is **required**. A rule with no code dependency is either an
  invariant (file it in `invariants/`) or a property not yet pinned down well
  enough to catalog.
- **`status`** — every rule is born `holds`. It becomes `divergent` when the
  code is found to already violate it (a suspected defect pending triage), and
  `retired` when a design or code change has deliberately superseded it. A
  `retired` rule is kept for history and must not be enforced by review.
  Tools treat this field as load-bearing.
- **`confidence`** — the author's strength of belief that the rule genuinely
  holds and is load-bearing rather than vestigial, independent of `status`. A
  rule inferred from a single code guard of unclear intent is `low`; one
  confirmed by the engineer is `high`. Advisory only — it does not gate
  enforcement.
- **`provenance`** — traceability back to the source (extraction run,
  elicitation session date, SCN ID, post-mortem, code path), so a future
  reader can re-check the rule against its origin.
- **no `source` field** — a rule has no external authority; that absence is
  precisely what separates it from an invariant. Its justification lives in
  the `## Why it holds now` body section, not in a citation.

## Body

Exactly three sections, in this order.

### `## Statement`

The property as it holds today, in prose, as a single declarative claim with
scope and conditions explicit.

### `## Why it holds now`

The implementation reason the property is currently true: the specific code
structure, guard, ordering, or configuration it rests on. State the
contingency explicitly — if this reasoning stops applying, the rule no longer
holds, and that may be a legitimate change rather than a bug. This section is
what distinguishes a rule from an invariant.

### `## Change risk`

What kind of change would break the rule, phrased as mechanisms. Then the
reviewer disposition: breaking this rule is a **flag for confirmation** — the
change may be a deliberate, correct redesign or an accidental regression, and
the two are indistinguishable until intent is confirmed. State what
confirmation would look like (the question to ask, the signal that settles
it).

### `## Notes` (optional)

Known false-alarm conditions, the invariant this rule protects (if any),
related RUL entries, retirement history. Omit the section if empty.

## Skeleton

```markdown
---
id: RUL-NNN
title: ...
class: structural
topics: [...]
components:
  - ...
related:
  invariants: []
  decisions: []
  scenarios: []
  heuristics: []
status: holds
confidence: medium
provenance: ...
---

# RUL-NNN — Title

## Statement
...

## Why it holds now
...

## Change risk
...

## Notes
...
```
