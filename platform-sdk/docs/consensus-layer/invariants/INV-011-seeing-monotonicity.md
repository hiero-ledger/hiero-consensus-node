---
id: INV-011
title: Seeing is monotone and eventually agreed
class: determinism
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §IV-C + §IV-C end / p. 3
provenance: split-from-INV-007 against §IV-C-end meta-claim + §IV-C definition of seeing; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-011 — Seeing is monotone and eventually agreed

## Statement
Whether one event sees another (descendant relation with paths
through forks by y's creator excluded) is a monotone
deterministic conclusion: once an honest node concludes that x
sees y, the conclusion never reverses as its hashgraph grows,
and every honest node eventually reaches the same conclusion.

## Basis
Seeing is defined in Baird & Luykx 2020, §IV-C / p. 3: "the
event x sees y if x is a descendant of y, and y's ancestors do
not include a fork by y's creator." Both clauses are functions
of frozen sub-DAGs: descendant is the inverse of ancestor, whose
monotonicity is INV-010; and "y's ancestors do not include a
fork by y's creator" is a predicate over y's ancestor sub-DAG,
which is frozen by INV-004. The §IV-C end meta-claim ("All
components of the hashgraph consensus algorithm have mathematical
proofs that they have this consistency property") licenses the
monotonicity property for seeing as one of those components.

## Change risk
Any change that lets the seeing predicate flip as the local
hashgraph grows breaks this invariant. Mechanisms: deriving the
"no fork in y's ancestors" check from a global view rather than
y's ancestor sub-DAG (a later-arriving fork by y's creator,
outside y's ancestors, then retroactively changes whether x sees
y); admitting events that mutate parent edges (then ancestry,
and thus seeing, are no longer frozen); evaluating seeing
against a dynamic set of forks rather than what is present in
y's frozen ancestors. Strongly-seeing (INV-012) is a direct
consumer and inherits any breakage here.

## Notes
- Depends on INV-010 (ancestry monotonicity) and INV-004
  (consistent hashgraphs).
- Load-bearing for INV-012 (strongly-seeing is defined as a
  quorum over events that each see y).
- One of six per-component instantiations of the deterministic-
  conclusion meta-property catalogued under INV-007.
- `status` is [TBD: confirm enforced in current implementation].
