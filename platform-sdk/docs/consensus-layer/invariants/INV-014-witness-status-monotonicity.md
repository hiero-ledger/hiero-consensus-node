---
id: INV-014
title: An event's witness status is monotone and eventually agreed
class: determinism
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, Algorithm 2 (divideRounds), §IV-D-1 / p. 4 + §IV-C end / p. 3
provenance: split-from-INV-007 against §IV-C-end meta-claim + Algorithm 2 definition of x.witness; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-014 — An event's witness status is monotone and eventually agreed

## Statement
Whether an event is a witness (`x.witness`) is a monotone
deterministic conclusion: once an honest node computes
`x.witness`, the value never changes as its hashgraph grows,
and every honest node eventually computes the same value.

## Basis
`x.witness` is computed by `divideRounds` (Baird & Luykx 2020,
Algorithm 2, §IV-D-1 / p. 4) as `(x has no self parent) or
(x.round > x.selfParent.round)`. Both inputs are monotone: x's
parent structure is fixed by its hash chain (INV-004, INV-005),
so the "has no self parent" check has a fixed answer the moment
x is known; and `x.round` and `x.selfParent.round` are monotone
by INV-013. A conclusion built from monotone inputs by a fixed
boolean predicate is itself monotone. The §IV-C end meta-claim
provides the broader license.

## Change risk
Any change that lets `x.witness` flip after first being
determined breaks this invariant. Mechanisms: redefining witness
in terms of post-derivation values (for example, a "is this
still the first event of its creator in this round" check that
re-evaluates as more events arrive); mutating `x.round` or
`x.selfParent.round` (which would also break INV-013); allowing
witness status to depend on neighbour-round events outside x's
ancestor sub-DAG; computing witness lazily against a moving
hashgraph state rather than committing on first computation.
Downstream effects: fame elections (INV-008) are defined over
witnesses, and findOrder (INV-015) reads witness status to
identify famous-witness sets.

## Notes
- Depends on INV-013 (round monotonicity).
- Load-bearing for INV-008 (fame elections range over witnesses)
  and INV-015 (round received depends on famous-witness sets,
  which are determined by witness status plus fame).
- One of six per-component instantiations of the deterministic-
  conclusion meta-property catalogued under INV-007.
- `status` is [TBD: confirm enforced in current implementation].
