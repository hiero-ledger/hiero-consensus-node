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
  Tolerance" (SWIRLDS-TR-2016-01) — the theorem that each round elects a stake
  supermajority of unique famous witnesses (judges).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — `checkJudges` flags any decided round whose judges are not a stake supermajority; judges built in `RoundElections.findAllJudges`
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-014 — Every round has a supermajority of judges

## Statement

The set of judges for every round represents a supermajority of the stake — more than two-thirds of the total stake of the round's nodes is attributable to the creators of that round's judges.

## Basis

It is a theorem of the hashgraph consensus algorithm — proved in the hashgraph paper (SWIRLDS-TR-2016-01) — that each round's fame election elects a supermajority of unique famous witnesses (judges). The supermajority is measured by stake: the creators of a round's judges carry more than two-thirds of the round's total stake, not a mere majority by head.

The property follows from the algorithm's quorum structure — every vote is gated on strongly seeing a supermajority of the round's witnesses — and not from any implementation choice. Any consistent realization of the algorithm elects a stake supermajority of judges in each round.

## Change risk

- **Counting judges by head rather than weighting by stake**, so a numeric majority masks a stake minority.
- **A see, strongly-see, or vote-counting change that lets an election settle on a judge set that is not a stake supermajority.**

A round whose judges fall short of a supermajority undermines the strongly-see and voting thresholds that the rest of the algorithm depends on.

## Notes

The supermajority is upheld through fresh elections: `RoundElections.findAllJudges` returns the freshly elected unique famous witnesses for each round, and `ConsensusImpl.checkJudges` watches the property at runtime, logging any decided round whose judge weight is not a supermajority. Existence of at least one judge per round is INV-006; agreement on which events are judges is INV-007.
