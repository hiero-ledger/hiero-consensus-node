---
id: INV-018
title: A round-received r implies fame is decided for every witness in rounds ≤ r
class: ordering
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, Algorithm 4 (findOrder), §IV-D-3 / p. 5
provenance: algorithmic-consequences-extraction against Algorithm 4 (findOrder) §IV-D-3; 2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-018 — A round-received r implies fame is decided for every witness in rounds ≤ r

## Statement
If an honest node has assigned `x.roundReceived = r` for some
event x, then for every witness y in that node's hashgraph with
`y.round ≤ r`, `y.famous` is decided (i.e., not UNDECIDED).

## Basis
Algorithm 4 (findOrder, Baird & Luykx 2020, §IV-D-3 / p. 5)
assigns `x.roundReceived ← r` only when, among other
conditions, "there is no event y in or before round r that has
`y.witness = TRUE` and `y.famous = UNDECIDED`". The
fame-decidedness clause is a precondition for the assignment;
whenever the assignment fires, the precondition held at that
moment. INV-008 (fame finality) then keeps the fame decisions
stable for all subsequent moments, so the universally-quantified
fame-decidedness still holds for witnesses in rounds ≤ r at any
later time.

## Change risk
Any change that lets `x.roundReceived` be set while some witness
in rounds ≤ r still has undecided fame breaks this invariant.
Mechanisms: optimistically assigning round-received based on the
ancestor condition alone (skipping the fame-decided check); using
a relaxed "fame decided for round r only" precondition that
omits earlier rounds; processing rounds out of order in a way
that lets findOrder run before decideFame has converged for a
relevant prior round; pipelining findOrder with decideFame in a
way that breaks the Algorithm 1 sequencing (`divideRounds` →
`decideFame` → `findOrder`). Downstream effects: the consensus
order sorts first by round-received (INV-002); a round-received
assigned without fame finality could subsequently shift if a
prior witness's fame is later decided in a way the optimistic
assignment didn't anticipate, retroactively changing which
events qualify for round r and corrupting total order.

## Notes
- Captures the design rule that orders the three consensus
  stages as data dependencies, not just as a pipeline: round
  assignment (Algorithm 2) must complete before fame decision
  (Algorithm 3), which must complete before round-received
  (Algorithm 4). Algorithm 1 mirrors this as a control-flow
  pipeline; INV-018 records it as a property of the *state*
  that findOrder is allowed to commit.
- Depends on INV-008 (fame finality) for the "still holds after
  assignment" half of the argument: the precondition holds at
  the moment of assignment by Algorithm 4 directly, and it
  continues to hold thereafter because fame is final.
- Load-bearing for INV-015 (round-received monotonicity), which
  presupposes the fame inputs to findOrder are themselves
  monotone, and indirectly for INV-002 (consensus order sorts
  by round-received).
- `status` is [TBD: confirm enforced in current implementation].
