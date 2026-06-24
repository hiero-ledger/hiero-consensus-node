---
type: invariant
id: INV-013
title: The minimum non-ancient round never decreases — the ancient boundary only moves forward
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusRounds.java — `updateAncientThreshold`; `RoundCalculationUtils.getOldestNonAncientRound`
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-013 — The minimum non-ancient round never decreases

## Statement

As the pending round advances, the minimum non-ancient round — the boundary below which events are ancient — never decreases. An event that has become ancient never becomes non-ancient again.

## Basis

The hashgraph consensus algorithm defines `minNonAncientRound(r) = max(prevMinNonAncientRound(r), prevMinJudgeBirthRound(r) − targetNumRoundsNonAncient(r))`, where `prevMinNonAncientRound(r)` is the previous round's minimum non-ancient round. Because the value is the maximum of the previous round's boundary and another term, it is always greater than or equal to the previous round's boundary; the boundary is monotonic non-decreasing by construction. The clip against the previous boundary is precisely what ensures that, even when the target setting changes by large amounts, the minimum non-ancient round does not decrease.

## Change risk

- **Dropping the lower-bound clip against the previous round's boundary**, so a shrinking `targetNumRoundsNonAncient` could move the boundary backward.
- **Deriving the boundary from a quantity that can decrease between rounds** without re-clipping against the previous value.

A boundary that moves backward would resurrect previously-ancient events, making an event's stale fate (INV-004) non-deterministic.

## Notes

The implementation reaches the same guarantee by a different route than the formula above: it derives the ancient boundary from the minimum judge birth round of a forward-only sliding window keyed on the only-increasing last-decided round, rather than by an explicit `max` against the previous boundary. Both are monotonic non-decreasing. This forward-only boundary is what makes an event's stale fate (INV-004) well-defined.
