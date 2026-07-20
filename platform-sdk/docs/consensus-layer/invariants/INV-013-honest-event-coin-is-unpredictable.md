---
type: invariant
id: INV-013
title: An honest event's coin value is unpredictable to the creators of its ancestors
class: integrity
topics: [hashgraph]
related:
  rules: []
  decisions: []
  scenarios: []
  heuristics: []
status: enforced
source: The hashgraph consensus algorithm (protocol definition).
verification: consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java — coin drawn per event from a cryptographically secure `SecureRandom`; consumed in `ConsensusUtils.coin`
provenance: 2026-06-08 extraction run
curated_by: Michael Heinrichs (@netopyr)
---

# INV-013 — An honest event's coin value is unpredictable

## Statement

For an event created by an honest node, no creator of any ancestor of that event can predict the event's coin value at the time that ancestor is created. Moreover, each possible coin value occurs with probability bounded below by some fixed ε > 0.

## Basis

The hashgraph consensus algorithm requires both properties of the coin. Unpredictability: if an event is created by an honest node, then at the moment any ancestor of it is created, that ancestor's creator cannot guess the event's coin value. Probability floor: there exists some ε such that the probability of each possible value is greater than ε.

These are what make the coin able to break asynchronous stalemates. Unpredictability denies an adversary controlling earlier events any ability to steer a coin round's vote; the probability floor guarantees that, each time a coin round occurs, the coin lands on either outcome with positive probability, so with probability 1 an undecided election is eventually broken. Together they are the precondition the liveness results invoke.

## Change risk

- **Deriving the coin from a value an earlier event can compute or influence** — a deterministic function of visible state, or a weak or seedable pseudo-random source.
- **Collapsing the value space** so some outcomes fall below the probability floor.
- **Reusing a coin value an adversary has already observed.**

A predictable or biased coin lets an adversary hold an election undecided indefinitely, defeating liveness (INV-005, INV-006).

## Notes

This unpredictability is a precondition for the liveness invariants (INV-005, INV-006): their termination-with-probability-1 argument relies on the coin breaking ties.
