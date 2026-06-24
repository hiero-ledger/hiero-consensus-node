---
type: invariant
id: INV-004
title: Staleness is network-wide — a stale event is never ordered on any node
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

# INV-004 — Staleness is network-wide

## Statement

If an honest event becomes ancient before reaching consensus, no honest node ever assigns it a consensus order or timestamp.

## Basis

It is a theorem of the hashgraph consensus algorithm that staleness is the alternative final fate to consensus: an honest event either reaches consensus or becomes stale (ancient before reaching consensus), and once stale it is ordered by no node.

An event is stale exactly when its birth round falls below the ancient boundary before it reaches consensus. The birth round is immutable and gossiped, and the boundary `minNonAncientRound` is a function of the agreed round state. The decision "reached consensus before going ancient" versus "went ancient first" therefore yields the same answer on every node that holds the event, and a node that never received it never orders it either way — so no node ever orders an event another treats as stale. This is a consequence of applying an agreed boundary to an immutable field, not of any implementation choice.

## Change risk

- **A node-local ancient boundary** — deriving the boundary from per-node state so two nodes age out different events.
- **Ordering an event past the boundary on one node** while another has already discarded it as stale.
- **Mutating an event's birth round** after creation, so the staleness test gives different answers on different nodes.

Any change that lets one correct node order an event another has declared stale forks the ordered stream.

## Notes

The network-wide guarantee here is the *fate* — that a stale event is ordered nowhere. Whether a node *reports* an event as stale is a separate, local matter: a node can only report an event it actually saw age out, so the stale-report stream differs across nodes (see [stale events](../concepts/stale-events.md)).

This is the staleness facet of the algorithm's agreement guarantee; INV-002 and INV-003 cover the consensus-order and consensus-timestamp facets. The boundary's forward-only movement — which guarantees an event never returns from stale to live — is INV-013.
