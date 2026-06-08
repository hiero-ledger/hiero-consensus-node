---
id: INV-004
title: Staleness is agreed by all nodes — a stale event is stale on every node
class: agreement
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — `ancient(x)` (`birthRound < ancientThreshold`); stale-event emission in `DefaultConsensusEngine`
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-004 — Staleness is agreed by all nodes

## Statement

If an honest event becomes ancient before reaching consensus, every honest node agrees it is stale: it is assigned no consensus order or timestamp on any node. No node orders an event that another node treats as stale.

## Basis

It is a theorem of the hashgraph consensus algorithm that staleness is the alternative final fate to consensus: an honest event that does not reach consensus becomes stale (ancient before reaching consensus), and all nodes agree it is stale.

An event is stale exactly when its birth round falls below the ancient boundary before it reaches consensus. The birth round is immutable and gossiped, and the boundary `minNonAncientRound` is a function of the agreed round state; both are identical across nodes. The decision "reached consensus before going ancient" versus "went ancient first" is therefore the same on every node, so the stale set is agreed. The agreement is a consequence of applying an agreed boundary to an immutable field, not of any implementation choice.

## Change risk

- **A node-local ancient boundary** — deriving the boundary from per-node state so two nodes age out different events.
- **Ordering an event past the boundary on one node** while another has already discarded it as stale.
- **Mutating an event's birth round** after creation, so the staleness test gives different answers on different nodes.

Any change that lets one correct node order an event another has declared stale forks the ordered stream.

## Notes

This is the staleness facet of the algorithm's agreement guarantee; INV-002 and INV-003 cover the consensus-order and consensus-timestamp facets. The boundary's forward-only movement — which guarantees an event never returns from stale to live — is INV-013.
