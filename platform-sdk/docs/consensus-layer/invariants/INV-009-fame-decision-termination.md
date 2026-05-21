---
id: INV-009
title: Fame is decided for every witness with probability 1
class: liveness
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

# INV-009 — Fame is decided for every witness with probability 1

## Statement
For every witness, the fame election eventually terminates with a
decided value, with probability 1.

## Basis
The paper specifies the termination argument in §IV-D-2 (p. 4).
In normal rounds, witnesses vote by majority of the strongly-seen
witnesses of the previous round; in periodic coin rounds,
witnesses vote pseudorandomly (the middle bit of their signature)
unless they already strongly see a >2n/3 supermajority. The
pseudorandom vote means an adversary controlling message timing
cannot keep votes carefully split forever — each coin round gives
an independent chance of crossing the >2n/3 threshold, so the
probability of remaining undecided after k coin rounds tends to
zero. The paper states the conclusion directly: "there is still a
chance that the community will randomly cross the 2n/3 threshold,
and so agreement is eventually reached, with probability one."

## Change risk
Any change that removes coin rounds, replaces the pseudorandom
coin with a deterministic tiebreak, or otherwise lets an
adversary indefinitely split the vote breaks this invariant.
Without termination, no round can be considered complete, no
events ever receive a round-received, findOrder never produces
output, and the system-wide liveness property (INV-003)
collapses.

## Notes
- Load-bearing for INV-003: the probability-1 qualifier on
  liveness is exactly the qualifier this invariant supplies.
- The paper notes a coin round is unlikely to occur in practice
  "because convergence is fast when the honest nodes are allowed
  to communicate freely" — but the invariant covers the worst
  case, where the adversary controls scheduling.
- `status` is [TBD: confirm enforced in current implementation].
