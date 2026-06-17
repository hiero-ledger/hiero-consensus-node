---
type: invariant
id: INV-007
title: All deciders of a round agree on its judge set
class: agreement
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java — `findAllJudges`; judge set frozen in `ConsensusSnapshot.judgeIds`
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-007 — All deciders of a round agree on its judge set

## Statement

Any two events that decide a given round agree on that round's complete set of judges. The judge set for a round is well-defined independent of which deciding event is used to read it.

## Basis

It is a theorem of the hashgraph consensus algorithm that any two events deciding a given round will always agree on the set of judges, and the downstream functions give identical results regardless of which deciding event is used.

Fame for each candidate witness is decided by a supermajority vote that no later event can overturn. Once a deciding event observes every election settled, the resulting set of judges is fixed; any other event that also decides the round observes the same settled elections and therefore the same set. The judge set is thus a property of the round, not of the decider.

## Change risk

- **A voting rule under which a later event could flip a decided election** (see INV-009), so two deciders that read the round at different times disagree.
- **Making the judge set depend on the deciding event `d`** in any way beyond "is the round decided yet."
- **A non-deterministic collapse of branched creators to a single judge**, so the per-creator choice differs between nodes.

Disagreement on judges propagates to every downstream order and timestamp, forking the state machine.

## Notes

Judge-set agreement underpins consensus-order and consensus-timestamp agreement (INV-002, INV-003). The no-flip property it relies on is INV-009; existence of at least one judge is INV-006; the supermajority strengthening is INV-014.
