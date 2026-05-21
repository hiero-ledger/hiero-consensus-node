---
id: INV-008
title: A fame decision, once made, never changes and is the same on every honest node
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §IV-D-2 / p. 4
provenance: paper-extraction-2026-05-20; cross-refs-updated-2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-008 — A fame decision, once made, never changes and is the same on every honest node

## Statement
Once more than 2n/3 witnesses in some round agree on whether a
given witness x is famous, the decision is fixed: the same
decision is reached by every honest node, and no subsequent vote
in any later round can flip it.

## Basis
The paper specifies the decision rule in Baird & Luykx 2020,
§IV-D-2 / p. 4: "If more than 2n/3 witnesses agree on whether x
is famous then the community has decided, and the election is
over." The decision itself is the value of a deterministic
function of the local hashgraph — the tally of votes over
strongly-seen previous-round witnesses — and that strongly-seen
relation is monotone (INV-012), so the tally cannot subsequently
lose votes; once it crosses the >2n/3 threshold it stays crossed.
Across honest nodes, the >2n/3 threshold combined with the
strongly-seeing fork-resolution property (INV-006) prevents an
attacker from manufacturing two disagreeing >2n/3 majorities: any
two >2n/3 sets overlap in more than n/3 nodes, and the overlap
includes at least one honest node whose vote is the same
regardless of which observer looks at it.

This entry absorbs the fame component of the deterministic-
conclusion meta-property (INV-007): fame monotonicity — once a
fame value transitions from undecided to decided, it never
changes — is encoded directly in this entry's Statement rather
than carrying a separate split.

## Change risk
Any change that lets the same witness x receive two different
fame decisions on different honest nodes — for example, by
reducing the decision threshold below >2n/3, by basing the tally
on something other than strongly-seen previous-round witnesses,
or by introducing non-determinism into the vote computation —
breaks this invariant. A flipped fame decision changes which
events have a round-received r and therefore retroactively
changes the consensus order, violating INV-015 and INV-002.

## Notes
- Depends on INV-006 (strongly-seeing fork resolution) and
  INV-012 (strongly-seeing monotonicity) for its proof. A
  weakening of either weakens this invariant.
- Absorbs fame monotonicity from the umbrella meta-property
  INV-007 — this entry catalogues both the cross-node finality
  and the intra-node monotonicity of fame decisions in a single
  Statement, so no separate fame-monotonicity split exists.
- Load-bearing for INV-015 (round received depends on fame being
  decided for all witnesses in rounds ≤ r) and indirectly for
  INV-002 (consensus order sorts by round received).
- `status` is [TBD: confirm enforced in current implementation].
