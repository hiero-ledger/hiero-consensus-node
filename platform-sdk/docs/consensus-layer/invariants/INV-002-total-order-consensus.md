---
id: INV-002
title: Honest nodes agree on the order of every consensus output
class: ordering
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §III Definition 1 (Total Order) + §IV Theorem 1 / p. 2
provenance: paper-extraction-2026-05-20; cross-refs-updated-2026-05-21
curated_by: Michael Heinrichs (@netopyr)
---

# INV-002 — Honest nodes agree on the order of every consensus output

## Statement
For all i > 0, if T is the ith output of one honest node and T′
is the ith output of another honest node, then T = T′.

## Basis
This is the Total Order property of atomic broadcast, stated as
part of Definition 1 in Baird & Luykx 2020, §III / p. 2 and
established for the hashgraph protocol by Theorem 1 (§IV / p. 2).
Mechanism: each honest node computes the consensus order by a
deterministic function of its local hashgraph (Algorithm 4,
§IV-D-3 / p. 5): events are sorted by round received, then by
consensus timestamp, then by whitened signature. The monotonicity
of the inputs to this computation — round received (INV-015),
the fame decisions that feed round received (INV-008), and the
strongly-seeing relation that feeds fame voting (INV-012) —
ensures that once two honest nodes have enough of the hashgraph
to compute the ith output, they compute the same ith output. The
umbrella meta-property (INV-007) covers the design principle
that licenses these per-component monotonicity claims.

## Change risk
Any change that introduces wall-clock, receive-order, or
node-local randomness into the consensus-order computation breaks
this invariant. Replacing the deterministic tiebreak (whitened
signature) with a non-deterministic one — for example, sort by
local arrival time — would also break it. Any change to the
round-received or consensus-timestamp derivation must preserve
the property that the computation depends only on the local
hashgraph's frozen ancestor structure.

## Notes
- Theorem 1 bundles Agreement, Total Order, and Liveness; this
  entry covers Total Order only. Agreement is INV-001, Liveness
  is INV-003.
- This entry absorbs consensus-order monotonicity from the
  umbrella meta-property INV-007: the event-level
  consensus-position finality stated in Theorem 1 ("Each
  transaction ... will eventually be assigned the same consensus
  position in the total order of events by each honest node") is
  encoded here in the Basis as the "once two honest nodes have
  enough of the hashgraph to compute the ith output, they
  compute the same ith output" mechanism. The other components
  that feed this computation are catalogued separately: INV-015
  (round received), INV-008 (fame finality, which also absorbs
  fame monotonicity), INV-012 (strongly-seeing monotonicity),
  INV-010 (ancestry monotonicity).
- `status` is [TBD: confirm enforced in current implementation].
