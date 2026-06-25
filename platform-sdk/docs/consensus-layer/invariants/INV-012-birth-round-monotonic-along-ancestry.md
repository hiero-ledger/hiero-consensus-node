---
type: invariant
id: INV-012
title: Birth round is monotonic along ancestry — a parent's birth round never exceeds its child's
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-utility/src/main/java/org/hiero/consensus/event/validation/DefaultEventFieldValidator.java — `isEventBirthRoundValid` rejects an event whose birth round is below a parent's
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-012 — Birth round is monotonic along ancestry

## Statement

For every event, its birth round is greater than or equal to the birth round of each of its parents. Birth round never decreases as ancestry is traversed from an event toward its descendants.

## Basis

The hashgraph consensus algorithm requires an event's birth round to be greater than or equal to all claimed birth rounds of its parents.

A birth round records the creator's pending round at the moment the event is created, and a parent must already exist when its child is created, so a child cannot carry a smaller birth round than any parent. Combined with the matching-claims rule (RUL-004) — which guarantees that, for parents present in the consensus hashgraph, the claimed birth rounds equal the parents' real birth rounds — the relation holds for the real parents, not merely the claimed values.

## Change risk

- **Assigning a child a birth round below a parent's.**
- **Admitting an event whose birth round is below a parent's** without rejecting it.
- **A creation path that selects a parent with a higher birth round than the new event will carry.**

A child with a lower birth round than a parent breaks the ancient boundary and every consensus computation downstream of that boundary inherits the damage.

## Notes

This is the immutable birth round. The analogous monotonicity for the *calculated* voting round is INV-001. Matching of claimed parent birth rounds to actual parents is RUL-004.
