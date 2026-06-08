---
id: INV-002
title: Consensus order is agreed by all nodes — any two nodes that order an event report the same order
class: agreement
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — `setConsensusOrder` / `findConsensusEvents` (deterministic order assignment over `ConsensusSorter`-sorted events)
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-002 — Consensus order is agreed by all nodes

## Statement

Any two honest nodes that assign a consensus order to the same event assign it the same order. The order an event receives is a function of the agreed consensus state alone, not of the node computing it: once an event has reached consensus, every honest node that has reached consensus on it reports the identical order.

## Basis

It is a theorem of the hashgraph consensus algorithm that an event created by an honest node eventually reaches consensus (with probability 1): it either has a consensus timestamp and consensus order agreed to by all nodes, or it becomes stale (ancient before reaching consensus) and all nodes agree it is stale.

Agreement holds because consensus order is a deterministic function of inputs that are themselves agreed. An event's order is `1 + prevNumCons(r) + |{y : reachedCon(r,d,y) ∧ before(r,d,y,x)}|` — fixed by the count of consensus events that precede it under the total order `before`. That order in turn depends only on the decided judge set for the round and a tie-break that is a fixed function of the events. Because every node that decides a round agrees on its judges, every node computes the same predecessor set and hence the same order for the same event. The property follows from the algorithm's determinism over agreed inputs, not from any implementation choice.

## Change risk

The invariant is at risk whenever consensus order is made to depend on something not shared identically across nodes. Mechanisms to defend against:

- **Node-local state in the ordering computation** — breaking ties in `before` with a value that differs between nodes (wall-clock arrival time, local receive sequence, peer identity). The tie-break must be a deterministic function of the events alone.
- **Ordering an event before its round's judges are fully decided**, so nodes that decide at different times observe different predecessor sets.
- **Counting predecessors against a per-node view of which events reached consensus**, rather than the agreed decided set.

Any change that lets two correct nodes assign different orders to the same event is a defect: it forks the replicated state machine.

## Notes

This is the consensus-order facet of the algorithm's agreement guarantee; INV-003 covers the consensus-timestamp facet and INV-004 the staleness facet. The uniqueness/total-order structure of the order on a single node is INV-016. Agreement here rests on judge-set agreement (INV-007).
