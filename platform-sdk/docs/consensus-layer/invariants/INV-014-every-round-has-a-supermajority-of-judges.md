---
id: INV-014
title: Every round has a supermajority of judges
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: >
  Baird, "The Swirlds Hashgraph Consensus Algorithm: Fair, Fast, Byzantine Fault
  Tolerance" (SWIRLDS-TR-2016-01) — the proof that each round elects a supermajority
  of famous witnesses (judges).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java — `findAllJudges`
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-014 — Every round has a supermajority of judges

## Statement

The set of judges for every round represents a supermajority of the stake — more than two-thirds of the total stake of the round's nodes is attributable to the creators of that round's judges.

## Basis

It is a theorem of the hashgraph consensus algorithm — proved in Baird, "The Swirlds Hashgraph Consensus Algorithm: Fair, Fast, Byzantine Fault Tolerance" (SWIRLDS-TR-2016-01) — that each round elects a supermajority of famous witnesses (judges). The algorithm preserves this even as membership changes: when the freshly elected judges for a round are not a supermajority, it reuses the previous round's judges, and the definition of `voteD` ensures a supermajority is elected. Thus each round's judge set always carries more than two-thirds of the stake — either from a fresh supermajority election or by inheriting the previous round's supermajority set.

## Change risk

- **Removing the fallback that reuses the previous round's judges** when too few are freshly elected.
- **A `voteD` or election change that can leave a round with a sub-supermajority judge set.**
- **Counting judges by head rather than weighting by stake**, so a numeric majority masks a stake minority.

A round whose judges fall short of a supermajority undermines the strongly-see and voting thresholds that the rest of the algorithm depends on.

## Notes

Existence of at least one judge per round is INV-006; agreement on which events the judges are is INV-007. The previous-judges-reuse fallback is exercised under in-protocol address-book changes, which are not yet active in the implementation; the current regime upholds the supermajority guarantee through fresh elections.
