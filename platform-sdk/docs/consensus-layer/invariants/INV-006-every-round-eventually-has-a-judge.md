---
type: invariant
id: INV-006
title: Every round eventually has at least one judge, for infinitely many rounds (probability 1)
class: liveness
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java — `isDecided` / `findAllJudges`; coin rounds in `ConsensusImpl`
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-006 — Every round eventually has at least one judge

## Statement

With probability 1, every round eventually has at least one judge, and this continues for an infinite number of rounds. Round elections do not dry up: the supply of judges that anchors ordering and timestamps never runs out.

## Basis

It is a theorem of the hashgraph consensus algorithm that every round will have at least one judge, and this continues for an infinite number of rounds (with probability 1).

Fame voting for a round's candidate witnesses always terminates — coin rounds guarantee that any asynchronous stalemate is eventually broken with probability 1 — and the decided outcome leaves at least one judge for the round. Rounds advance without bound as honest nodes continue to create and gossip events. Together these give an unending sequence of rounds each with a judge.

## Change risk

- **A fame-voting change that can leave all of a round's witnesses permanently undecided** (for example, removing coin rounds).
- **Halting round advancement**, so the sequence of rounds is finite.
- **A condition under which a decided round can have an empty judge set.**

A judgeless round provides no anchor for ordering or timestamps, so consensus cannot progress past it.

## Notes

This is the existence/liveness claim for judges. Agreement on *which* events are the judges of a round is INV-007. The guarantee holds under the algorithm's standard assumptions — an honest supermajority of stake, eventual delivery, and the coin's unpredictability (INV-013).
