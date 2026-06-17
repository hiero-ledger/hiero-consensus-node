---
type: invariant
id: INV-008
title: Consensus, once reached, is permanent — a decision is never reverted while the event is non-ancient
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java — `setConsensus(true)` is one-way; finalized `ConsensusRound` is immutable
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-008 — Consensus, once reached, is permanent

## Statement

Once an event has reached consensus, it remains in consensus in every later round for as long as it stays non-ancient. A consensus decision is monotonic: it can turn from "not yet decided" to "decided", never back.

## Basis

It is a provable property of the hashgraph consensus algorithm that if an event has reached consensus in some round, it continues to be in consensus in every future round, for as long as it is still non-ancient.

Whether an event has reached consensus is a function determined by the decided judge sets of the rounds it has reached. A decided judge set is immutable once a supermajority has voted (see INV-007, INV-009), so an event already established as received-by/ordered-against those judges remains so in every later round. The predicate therefore only ever turns on.

## Change risk

- **Recomputing consensus against a judge set that can still change**, rather than a decided one.
- **Allowing a decided election to change** (INV-009), which would let consensus status regress.
- **A path that un-orders an event still inside the non-ancient window** — e.g., a reconnect or replay that re-derives consensus and drops a previously-final event.

An already-final event becoming non-final invalidates state the network has already acted on.

## Notes

The decision-finality that makes this hold is INV-009; the agreement on judges it builds on is INV-007.
