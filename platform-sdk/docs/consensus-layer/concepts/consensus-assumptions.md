---
type: concept
title: Consensus assumptions
last_reviewed: 2026-06-24
---

# Consensus assumptions

## Definition

The consensus algorithm's safety and liveness guarantees are not unconditional: they hold under a fixed set of assumptions about the network, the nodes, and the events they create. These are the *preconditions* of the protocol — they are not themselves invariants (a correct implementation cannot make "honest nodes hold more than two-thirds of the stake" true), but every guarantee in the catalog is stated relative to them. When a hashgraph property is said to hold "by design," it means *given these assumptions*.

## The assumptions

1. **At least two nodes.** The network has two or more participating nodes.
2. **No node holds a majority of stake.** No single node has more than half of the total stake.
3. **Events form a finite DAG.** Each event has zero or more parents, and all events together form a finite directed acyclic graph.
4. **Honest nodes never branch.** An honest node never signs two events where neither is a self-ancestor of the other; its events form a single self-parent chain. Byzantine nodes may branch, and the algorithm tolerates this structurally — see [`branching.md`](branching.md).
5. **Honest supermajority of stake.** The honest nodes' combined stake is more than two-thirds of the total. (Equivalently, Byzantine nodes hold less than one-third.)
6. **Eventual delivery.** If an honest node creates an event, that event eventually has descendants created by every honest node — honest events eventually propagate to everyone through gossip. The network is otherwise fully asynchronous: there is no bound on message delay.
7. **Judges eventually reach an honest node.** Each judge eventually has a descendant event created by an honest node. (Stated as an axiom; believed provable from the others.)

## Why they are needed

The honest-supermajority assumption (5) is the classic asynchronous-BFT threshold: every supermajority quorum ([`strongly-seeing.md`](strongly-seeing.md), voting) intersects every other in more than a third of the weight, so the overlap always contains an honest node. The no-branching assumption (4) is what lets that honest node pin down a single answer — it cannot have shown two conflicting histories. Together they give the agreement results (judges, order, timestamp). Eventual delivery (6) together with the coin ([`coin-rounds.md`](coin-rounds.md)) is what upgrades "elections can be decided" to "elections *are* eventually decided, with probability 1" — the liveness results. The finite-DAG (3) and two-node (1) assumptions keep the computation well-defined.

If an assumption fails, the corresponding guarantee is forfeit, not merely degraded: with a Byzantine supermajority of stake, two honest nodes can be driven to disagree on consensus order (a fork); without eventual delivery, an honest event can stay undecided forever. These are boundary conditions, not failure modes the code defends against.

## In current code

The trust model is largely *environmental* — it is not encoded in any single place, because an implementation cannot make it true. Where it does surface:

- **The supermajority threshold** (assumption 5) is the `> 2/3 of total stake` test applied throughout voting and strong-seeing (`Threshold.SUPER_MAJORITY` in `ConsensusImpl`); the safety proofs assume the honest part of that stake is itself a supermajority.
- **Branching tolerance** (assumption 4) is structural and detector-free — see [`branching.md`](branching.md).
- **Eventual delivery** (assumption 6) is provided by the gossip layer (a separate module), not the hashgraph engine.

The remaining assumptions (two or more nodes, finite DAG, judge reachability) are preconditions on the environment and the input graph, not code paths.

## Cross-references

- Sibling concepts:
  [`branching.md`](branching.md),
  [`coin-rounds.md`](coin-rounds.md),
  [`strongly-seeing.md`](strongly-seeing.md),
  [`judges.md`](judges.md),
  [`voting.md`](voting.md).
- Invariants that hold relative to these assumptions: INV-005 (honest events eventually reach consensus or become stale), INV-006 (every round eventually has a judge), INV-013 (an honest event's coin value is unpredictable).
- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Whitepaper: SWIRLDS-TR-2016-01 establishes the asynchronous, more-than-two-thirds-honest Byzantine fault-tolerance model that these assumptions refine.
- Glossary entry: [`../glossary.md`](../glossary.md).
