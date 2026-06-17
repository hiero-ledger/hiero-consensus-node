---
type: invariant
id: INV-016
title: Consensus order is a strict total order with unique, gap-free ranks
class: ordering
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — `setConsensusOrder` assigns a strictly-incrementing order over `ConsensusSorter`-sorted events
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-016 — Consensus order is a strict total order with unique ranks

## Statement

Each event that reaches consensus receives a distinct consensus order. The orders assigned to consensus events form a strict total order: no two events share a rank, and the ranks are gap-free.

## Basis

The hashgraph consensus algorithm assigns each consensus event `1 + prevNumCons(r) + |{y : reachedCon(r,d,y) ∧ before(r,d,y,x)}|` — its rank is one more than the number of consensus events strictly before it. The relation `before` is a total order: events are ordered by consensus timestamp, and ties among events with equal timestamp are broken by a fixed total order on events (such as lexicographic comparison of their hashes).

Counting predecessors under a strict total order yields a distinct rank for every event — two events cannot have the same count because one precedes the other under `before` — and the ranks run consecutively from `prevNumCons(r) + 1`. The consensus order is therefore itself a strict total order.

## Change risk

- **A `before` relation that is not total** (leaves two events incomparable) or **not antisymmetric** (a non-deterministic or non-strict tie-break), which would let two events receive the same rank.
- **Counting predecessors against an inconsistent or incomplete set** of consensus events, producing gaps or collisions.

Two events sharing a rank make transaction order ambiguous, which diverges state.

## Notes

This entry is about the *structure* of the order (uniqueness and totality) as computed on a node's agreed state. That the order is *the same across nodes* is INV-002.
