# Invariants — Entry Format

Specification for `invariants/INV-NNN-short-slug.md` entries. This file
defines the structure only; allowed values for the `topics` field are the
eleven topic slugs under `architecture/topics/`. The test for whether
something is an invariant rather than a rule is defined in `README.md` —
apply it before filing here.

## File naming

- `invariants/INV-NNN-short-slug.md`
- `NNN` is zero-padded to three digits (`INV-001`, `INV-027`).
- Cross-references from other files use the ID only (e.g., "See INV-004").

## Frontmatter

```yaml
---
id: INV-NNN
title: Short declarative title — the property in a phrase
class: safety                         # safety | liveness | agreement | ordering | integrity | determinism
topics: [hashgraph]                   # topic slugs this invariant touches
related:
  rules: [RUL-012]                     # optional; implementation rules that enforce it
  decisions: []                        # optional
  scenarios: []                        # optional
  heuristics: []                       # optional
status: enforced                       # enforced | proposed | divergent
source: paper §IV, Theorem 1           # the authority that makes this permanent — required
verification: ConsensusEndToEndTest::testTotalOrder   # optional; how adherence is checked
provenance: elicitation-2026-05-xx     # where this entry came from
curated_by: Full Name (@github-handle) # the person responsible for this entry
---
```

Field discipline:

- **`class`** — the kind of permanent property. Drives coverage review: a
  topic with no `safety` invariant is a suspicious gap. Never free text.
- **`source`** — the external authority the invariant derives from: a paper
  theorem, a protocol definition, a proposal guarantee. This is what makes
  the property permanent rather than contingent, and it is **required**. An
  entry with no citable authority is not an invariant — it is a rule, and
  belongs in `rules/`.
- **`status`** — `enforced` if the current implementation upholds it,
  `proposed` if specified but not yet implemented, `divergent` if the code
  currently violates it. Unlike a rule, an invariant is never `retired`: a
  permanent truth is not superseded by a redesign, only failed by a broken
  one. Tools treat this field as load-bearing.
- **`verification`** — optional single pointer to how adherence is checked (a
  test, an assertion site, a runtime monitor). The invariant is true by
  authority regardless of whether anything checks it; this field only records
  how the implementation is watched.
- **`provenance`** — traceability for the catalog entry itself: when and how
  this invariant was added (extraction run, elicitation session, post-mortem).
  Distinct from `source`, which is the external authority the property derives
  from. The pair answers two different questions: *where does this truth come
  from?* (`source`) versus *who put this entry here, and from what?*
  (`provenance`).
- **`curated_by`** — the person responsible for this entry now. See
  [LAYOUT.md](../LAYOUT.md#curator-and-decider-conventions) for the canonical
  format and the distinction from `provenance`.
- **no `confidence` field** — an invariant is asserted by an authority, not
  believed with a strength. If there is genuine uncertainty about whether a
  property is permanent, that uncertainty itself means it belongs in
  `rules/` with a `confidence`, not here.

## Body

Exactly three sections, in this order.

### `## Statement`

The permanent property, in prose, as a single declarative claim with scope
and conditions explicit. An optional parenthesized semi-formal phrasing may
follow.

### `## Basis`

Why this is permanently true: the theorem, protocol rule, or design guarantee
it follows from. This section is what distinguishes an invariant from a rule
— the property holds because of the protocol, not because of how the code
happens to be written. Cite the `source`.

### `## Change risk`

What kind of implementation change would make the system stop upholding this
invariant, phrased as mechanisms rather than a code-review checklist. Because
the property is permanent, any such change is a defect to be stopped, not a
tradeoff to be weighed. This is the Change Reviewer's primary hook.

### `## Notes` (optional)

Known divergence detail, links to the rules that enforce it, related INV
entries, history. Omit the section if empty.

## Skeleton

```markdown
---
id: INV-NNN
title: ...
class: safety
topics: [...]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: ...
verification: ...
provenance: ...
curated_by: ...
---

# INV-NNN — Title

## Statement
...

## Basis
...

## Change risk
...

## Notes
...
```
