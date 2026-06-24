---
type: invariant
id: INV-003
title: Consensus timestamp is agreed by all nodes — any two nodes that timestamp an event report the same timestamp
class: agreement
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — `setIsConsensusTrue` (median of judge/ancestor receipt times)
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-003 — Consensus timestamp is agreed by all nodes

## Statement

Any two honest nodes that assign a consensus timestamp to the same event assign it the same timestamp. The timestamp an event receives is a function of the agreed consensus state alone, not of the node computing it.

## Basis

It is a theorem of the hashgraph consensus algorithm that a consensus event has a consensus timestamp and consensus order on which no two nodes disagree.

The timestamp is a deterministic function of agreed inputs: it is a median over the creation times of a fixed set of events tied to the round's judges. The set of events entering the median is part of the agreed consensus state, and the median selection is deterministic. Every node computing the timestamp from the same decided round therefore obtains the same value. The agreement is a consequence of determinism over agreed inputs, independent of implementation.

## Change risk

- **Feeding node-local time into the timestamp** — for example, a node's local receive time or wall-clock reading, which no other node can reproduce. The inputs to the median must be agreed event fields.
- **Computing the median over a per-node event set** rather than the agreed set derived from the round's judges.
- **A non-deterministic median or weighting** — any tie-handling or rounding that can differ between nodes.

Any change that lets two correct nodes assign different timestamps to the same event forks time-dependent state.

## Notes

This is the consensus-timestamp facet of the algorithm's agreement guarantee; INV-002 covers consensus order and INV-004 staleness. Agreement here, like INV-002, rests on judge-set agreement (INV-007).
