---
id: INV-003
title: Every transaction submitted to an honest node is eventually output by every honest node
class: liveness
topics: [hashgraph, gossip]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: "[TBD: confirm enforced in current implementation]"
source: Baird & Luykx 2020, §III Definition 1 (Liveness) + §IV Theorem 1 / p. 2
provenance: paper-extraction-2026-05-20
curated_by: Michael Heinrichs (@netopyr)
---

# INV-003 — Every transaction submitted to an honest node is eventually output by every honest node

## Statement
If a transaction is input to an honest node, then every honest
node eventually outputs that transaction, with probability 1.

## Basis
This is the Liveness property of atomic broadcast, stated as part
of Definition 1 in Baird & Luykx 2020, §III / p. 2 ("if a
transaction is input to an honest node, then it is eventually
output by every honest node") and established for the hashgraph
protocol by Theorem 1 (§IV / p. 2, with the probability-1
qualifier). Mechanism, given the §III network assumption that
messages between honest nodes succeed if repeatedly retried:
gossip propagates every event to every honest node (§IV-A); fame
decisions on every witness terminate with probability 1 thanks to
the coin-round mechanism (INV-009, §IV-D-2); findOrder then
assigns each event a round-received, and the event — along with
its transactions — enters the consensus output stream.

## Change risk
Any change that lets transactions get stuck somewhere in the
pipeline — gossip-sync that fails to retry, a fame election that
can stall without termination, a findOrder step that can leave
events without a round-received — breaks this invariant. The
probability-1 qualifier depends on the coin-round mechanism (or
an equivalent randomisation) being preserved; removing coin
rounds in favour of a purely deterministic election scheme would
make liveness conditional on adversarial scheduling and thus
violate this invariant under arbitrary asynchrony.

## Notes
- Theorem 1 bundles Agreement, Total Order, and Liveness; this
  entry covers Liveness only. Agreement is INV-001, Total Order is
  INV-002.
- `topics` includes `gossip` because gossip is the propagation
  mechanism on which the liveness property load-bears. The
  property itself is hashgraph-level (it ranges over consensus
  outputs).
- `status` is [TBD: confirm enforced in current implementation].
