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
provenance: paper-extraction-2026-05-20
curated_by: Michael Heinrichs (@netopyr)
---

# INV-008 — A fame decision, once made, never changes and is the same on every honest node

## Statement
Once more than 2n/3 witnesses in some round agree on whether a
given witness x is famous, the decision is fixed: the same
decision is reached by every honest node, and no subsequent vote
in any later round can flip it.

## Basis
The paper specifies the decision rule in §IV-D-2 (p. 4): "If more
than 2n/3 witnesses agree on whether x is famous then the
community has decided, and the election is over." The decision
itself is the value of a deterministic function of the local
hashgraph — the tally of votes over strongly-seen previous-round
witnesses — so it inherits monotonicity from INV-007: once
non-undecided, it cannot change. Across honest nodes, the >2n/3
threshold combined with the strongly-seeing fork-resolution
property (INV-006) prevents an attacker from manufacturing two
disagreeing >2n/3 majorities: any two >2n/3 sets overlap in more
than n/3 nodes, and the overlap includes at least one honest node
whose vote is the same regardless of which observer looks at it.

## Change risk
Any change that lets the same witness x receive two different
fame decisions on different honest nodes — for example, by
reducing the decision threshold below >2n/3, by basing the tally
on something other than strongly-seen previous-round witnesses,
or by introducing non-determinism into the vote computation —
breaks this invariant. A flipped fame decision changes which
events have a round-received r and therefore retroactively
changes the consensus order, violating INV-002.

## Notes
- Depends on INV-006 (strongly-seeing fork resolution) and INV-007
  (conclusion monotonicity) for its proof. A weakening of either
  weakens this invariant.
- `status` is [TBD: confirm enforced in current implementation].
