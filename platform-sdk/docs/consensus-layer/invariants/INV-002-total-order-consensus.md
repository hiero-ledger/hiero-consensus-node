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
provenance: paper-extraction-2026-05-20
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
deterministic function of its local hashgraph (§IV-D-3 / p. 4):
events are sorted by round-received, then by consensus timestamp,
then by whitened signature. The monotonicity of every
deterministic conclusion on the hashgraph (INV-007) ensures that
once two honest nodes have enough of the hashgraph to compute the
ith output, they compute the same ith output.

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
  entry covers Total Order only. Agreement is INV-001, Liveness is
  INV-003.
- The event-level consensus-position finality stated in Theorem 1
  ("Each transaction ... will eventually be assigned the same
  consensus position in the total order of events by each honest
  node") is the conjunction of this invariant with INV-007
  (monotonicity of the consensus-order function); it is therefore
  not catalogued as a separate entry.
- `status` is [TBD: confirm enforced in current implementation].
