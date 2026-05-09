---

title: Rounds and witnesses
kind: concept
last_reviewed: TBD
------------------

# Rounds and witnesses

## Definition

- A *round* (or *round-created*) is a non-negative integer the
  algorithm assigns to each event when it enters the DAG.
- A *witness* is the first event by a creator in a given round —
  equivalently, an event whose round-created exceeds its self-parent's
  round-created, or which has no self-parent.
- *Round-received* is a separate quantity: the round in which an event
  achieves consensus order. An event's round-received is set only when
  the algorithm decides the round.

## Mechanics

An event's round-created normally inherits the maximum of its parents'
rounds. The exception is the round-bump: when an event strongly sees a
super-majority of weight on the witnesses of the parent round, it bumps
to *parent round + 1*. Witnesses are the events on which fame voting
runs. Once every witness in round *r* has a fame verdict, the round is
"decided", and every event that is an ancestor of all judges of *r*
takes round-received *r*.

## Example

Four equal-weight nodes (super-majority = 3 of 4). Round 1 has four
witnesses, one per creator. An event *x* at round 1 by node A whose
ancestors include witnesses by B, C, and D — three distinct creators —
strongly sees a super-majority of round-1 witnesses, so *x* takes round
2 and is round 2's witness for A.

## In current code

Round assignment: `ConsensusImpl.round`
([`ConsensusImpl.java`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java)).
Witness predicate: `ConsensusImpl.witness`. Per-event accessors:
`EventImpl.getRoundCreated` (line 334) and
`EventImpl.getRoundReceived` (line 112). Per-round election state:
[`RoundElections`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java).

The paper computes round bumps against an event's non-deterministic
generation (NGen); the current code drives the same logic with event
sequence numbers and uses [`birth-round.md`](birth-round.md) for
ancient filtering.

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Sibling concepts:
  [`strongly-seeing.md`](strongly-seeing.md),
  [`judges.md`](judges.md),
  [`birth-round.md`](birth-round.md).
- Glossary entry: [`../glossary.md`](../glossary.md).
