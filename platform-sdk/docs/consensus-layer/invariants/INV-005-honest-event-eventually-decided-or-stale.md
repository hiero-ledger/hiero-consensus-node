---
type: invariant
id: INV-005
title: Every honest event eventually reaches consensus or becomes stale (probability 1)
class: liveness
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java — election pipeline (`voteInAllElections`, `firstVote`, `coinVote`) and ancient eviction
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-005 — Every honest event eventually reaches a final fate

## Statement

Under the algorithm's standard assumptions, every event created by an honest node eventually reaches a final fate — a consensus order and timestamp, or staleness — with probability 1. No honest event remains forever undecided.

## Basis

It is a theorem of the hashgraph consensus algorithm that an event created by an honest node eventually reaches a final fate (with probability 1): it either receives a consensus timestamp and consensus order on which no two nodes disagree, or it becomes stale and is ordered by no node.

Termination rests on two facts. First, virtual voting on each round's witnesses always terminates: a coin round periodically injects a vote drawn from a source no adversary can predict (the `coin`), which with probability 1 eventually breaks any asynchronous stalemate the network might sustain. Second, once witnesses are decided the round has judges, against which every event is either ordered or — if it fails to reach consensus before the advancing ancient boundary — declared stale. The theorem is conditional on the algorithm's standard assumptions (honest stake exceeds two-thirds; an honest event eventually reaches every honest node; and the rest).

## Change risk

- **Weakening or removing coin rounds**, letting an adversary keep an election undecided indefinitely.
- **A voting rule that need not terminate** — one without the coin's eventual tie-break.
- **An ancient boundary that never advances**, leaving non-consensus events neither ordered nor aged out.

Any change under which an honest event can stay undecided forever defeats liveness: consensus stalls and no transactions finalize.

## Notes

This is the liveness core of the algorithm's eventual-consensus guarantee; the agreement facets are INV-002 / INV-003 / INV-004, and the existence of judges that ordering depends on is INV-006. The coin unpredictability this proof invokes is itself an invariant (INV-015).
