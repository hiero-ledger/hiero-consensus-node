---
id: INV-016
title: An event's round is at least as large as every ancestor's round
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, Algorithm 2 (divideRounds), §IV-D-1 / p. 4
provenance: algorithmic-consequences-extraction against Algorithm 2 (divideRounds) §IV-D-1; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-016 — An event's round is at least as large as every ancestor's round

## Statement
For any event x and any ancestor a of x in an honest node's
hashgraph, `x.round ≥ a.round`.

## Basis
Algorithm 2 (divideRounds, Baird & Luykx 2020, §IV-D-1 / p. 4)
computes `x.round` as follows: `r ← max round of parents of x`
(or 1 if x has no parents), and then `x.round ← r + 1` if x
strongly sees more than 2n/3 round-`r` witnesses, else
`x.round ← r`. Either branch yields `x.round ≥ r`, i.e.,
`x.round ≥` every parent's round. Applying this fact along
parent edges by transitive closure gives `x.round ≥` every
ancestor's round. The paper does not state this property
explicitly; the algorithm forces it in one inference step.

## Change risk
Any change that lets an event's round drop below an ancestor's
round breaks this invariant. Mechanisms: redefining `r` as
something other than the maximum of parent rounds (a min, an
average, or a value derived from non-parent inputs); allowing
`x.round` to be assigned independently of parents (for example,
from creation timestamp or sync arrival order); allowing parent
edges to be added to an event after its round is computed in a
way that does not trigger recomputation. Downstream effects:
round-along-ancestry feeds every consensus stage that ranges
over rounds — the witness predicate (which compares against the
self-parent's round), fame elections (which vote across rounds),
round-received determination, and consensus output ordering.

## Notes
- Structural counterpart to INV-013. INV-013 is *temporal*
  monotonicity: once `x.round` is computed for one event, it
  doesn't change. INV-016 is *structural* monotonicity: the
  function `event → round` respects the partial order of the
  DAG. Both can be violated independently; together they pin
  down round assignment as a stable function of frozen ancestry.
- Load-bearing for INV-017 (witness uniqueness uses parent-edge
  round monotonicity along the self-parent chain) and implicitly
  for findOrder's ancestor condition (`x.roundReceived = r`
  requires x to be an ancestor of every round-r unique famous
  witness, which combined with INV-016 yields `x.round ≤ r`).
- `status` is [TBD: confirm enforced in current implementation].
