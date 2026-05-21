---
id: INV-015
title: An event's round received is monotone and eventually agreed
class: determinism
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, Algorithm 4 (findOrder), §IV-D-3 / p. 5 + §IV-C end / p. 3
provenance: split-from-INV-007 against §IV-C-end meta-claim + Algorithm 4 definition of x.roundReceived; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-015 — An event's round received is monotone and eventually agreed

## Statement
An event's round received (`x.roundReceived`) is a monotone
deterministic conclusion: once an honest node assigns
`x.roundReceived`, the value never changes as its hashgraph
grows, and every honest node eventually assigns the same value.

## Basis
`x.roundReceived` is defined by `findOrder` (Baird & Luykx 2020,
Algorithm 4, §IV-D-3 / p. 5) as the first round `r` such that
there is no witness in or before round `r` whose fame is
undecided, and x is an ancestor of every round-`r` unique famous
witness — with the property not holding for any earlier round.
Every input is monotone: fame decisions are final once made
(INV-008), witness status is monotone (INV-014), and the
ancestor predicate is monotone (INV-010). The "no earlier round
satisfies the condition" clause is itself monotone, because
adding events to the hashgraph can only move fame from undecided
to decided (never the reverse, by INV-008), so an earlier round
that did not qualify at the time `r` was chosen cannot
retroactively qualify. The §IV-C end meta-claim provides the
broader license.

## Change risk
Any change that lets `x.roundReceived` change after first being
assigned breaks this invariant. Mechanisms: allowing fame to be
re-decided (would also break INV-008); changing the unique-
famous-witness selection rule retroactively; allowing the
ancestor predicate to flip (would also break INV-010); choosing
`r` from a non-deterministic search order; lazily evaluating
`x.roundReceived` against a moving hashgraph state rather than
committing on first assignment. The consensus-order computation
(INV-002) sorts events first by round received, so any breakage
here propagates immediately to total order.

## Notes
- Depends on INV-008 (fame decision finality), INV-014
  (witness-status monotonicity), and INV-010 (ancestry
  monotonicity).
- Load-bearing for INV-002 (consensus order sorts first by round
  received).
- One of six per-component instantiations of the deterministic-
  conclusion meta-property catalogued under INV-007.
- `status` is [TBD: confirm enforced in current implementation].
