---
type: invariant
id: INV-009
title: A decided election never flips — no self-descendant ever decides it differently
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java — `isFameDecided`; `CandidateWitness.isDecided`
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-009 — A decided election never flips

## Statement

Once an event has decided an election, its vote for that election is final — no self-descendant ever decides it differently.

## Basis

The hashgraph consensus algorithm records, for each election, a decision flag that is true exactly when the event has decided that election — and once it is set, that verdict stands.

A decision is recorded only when an event observes a supermajority of the previous round's voters agreeing. A supermajority once observed within the hashgraph cannot be contradicted by any later event in the same self-chain — later self-descendants see a superset of what the deciding event saw — so the vote, once decided, cannot change. The decision is therefore monotonic along self-ancestry.

## Change risk

- **Recording a decision on less than a supermajority**, so a later, larger view could disagree.
- **A voting rule under which seeing more ancestors could change an event's vote** — self-descendants always see a superset of what the deciding event saw, so any such non-monotonicity lets a decided vote be re-evaluated differently.
- **Decision logic that depends on anything beyond the event's ancestors** — local wall-clock, traversal order, or other non-deterministic input — so re-evaluating the same election could yield a different outcome.

A flipped decision changes the round's judge set after the fact, breaking judge-set agreement (INV-007) and consensus permanence (INV-008).

## Notes

This finality is the mechanism behind judge-set agreement (INV-007) and consensus permanence (INV-008).
