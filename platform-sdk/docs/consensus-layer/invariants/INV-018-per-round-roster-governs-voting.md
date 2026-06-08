---
id: INV-018
title: Each round's voting is computed against that round's own roster and stake
class: safety
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — each `ConsensusRound` is finalized with the roster in force (`rosterLookup.getRoster()`); supermajority and strongly-seeing thresholds resolve against that roster's weights
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-018 — Each round's voting is computed against that round's own roster

## Statement

The thresholds that drive consensus for a round — strongly-seeing, and the supermajority votes that elect that round's judges — are computed against that round's own node set `nodes(r)` and per-node stake `stake(r,m)`. As the address book changes across rounds, each round uses the roster and stakes in force for it, never a single global or stale roster.

## Basis

In the hashgraph consensus algorithm, `nodes(r)` is the list of nodes in the address book for round `r`, and `stake(r,m)` is the stake node `m` has for voting in that round. The decisive threshold is `n > (2/3) totalStake(r)`, where `totalStake(r)` sums `stake(r,m)` over `nodes(r)`. Every weight comparison in the algorithm is therefore parameterized by the round `r` and resolved against that round's roster and stakes.

Because membership and stake can change from round to round, both the supermajority a vote must clear and the very set of nodes whose witnesses are counted are round-specific. Resolving them against any other roster — a global one, or a neighbouring round's — would apply the wrong threshold and count the wrong voters.

## Change risk

The invariant is at risk from:

- **Computing supermajorities against a single global total stake** rather than `totalStake(r)` for the round being decided.
- **Counting witnesses or voters from a roster other than `nodes(r)`** — for example carrying a later round's membership back onto an earlier round's election.
- **Mismatching roster and round** in any per-round calculation, so a node added or removed at a round boundary is weighted in the wrong rounds.

Voting against the wrong roster mis-elects judges, which forks consensus after any membership or stake change.

## Notes

The roster in force changes today at software-upgrade boundaries (see INV-017), and each consensus round is computed against the roster carried for it: a round is finalized with the roster from the active address book, and the supermajority and strongly-seeing thresholds resolve against that roster's weights. Continuous in-protocol roster changes — a different roster for adjacent non-upgrade rounds — are the algorithm's generalization; the per-round-roster discipline already holds for the upgrade-boundary changes in force today. The companion property, that the active address book only moves forward, is INV-017.
