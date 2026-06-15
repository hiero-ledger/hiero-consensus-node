---
title: Hashgraph DAG
kind: concept
last_reviewed: TBD
---

# Hashgraph DAG

## Definition

The hashgraph is, formally, a directed acyclic graph (DAG) whose
vertices are events and whose edges are parent references. Each event
has at most one *self-parent* (the previous event by the same
creator) and zero or more *other-parents* (events by peers that the
creator just gossiped with). The data model supports an arbitrary
number of other-parents; how many an event actually references is
bounded by the `maxOtherParents` tunable (TUN-140). Either kind of
parent may be absent for the very first event a creator ever makes.

In the rest of these docs, the structure is just called *the
hashgraph*; that is the term engineers maintaining this code use.

## Mechanics

Events reference parents by hash; once an event is built, its content
and parent edges are immutable. The hashgraph itself only grows: a
node adds an event to its local view when the event is created
locally or arrives via gossip, and the only way an event leaves the
hashgraph is by aging out — when its birth round falls below the
ancient threshold and it is evicted from the non-ancient window.
Nothing in normal operation modifies, replaces, or retracts an event
that has already been added.

## Example

Four nodes A, B, C, D. A creates `a₀` with no parents (A's first
event). After A gossips with B, B creates `b₁` whose self-parent is
B's prior event and whose other-parent is `a₀`. An event can fan in
from several peers at once: after C gossips with both A and B it may
create `c₁` whose other-parents are `a₀` and `b₁`. So every new event
has at most one self-parent edge and up to `maxOtherParents`
other-parent edges. The union of all events and parent edges is the
hashgraph. Denser other-parent linking lets each event see more of the
graph sooner, speeding the progression to consensus.

## In current code

The linked hashgraph node is
[`EventImpl`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java),
which holds parent pointers in an `allParents` array. Parent
descriptors travel with each event via `PlatformEvent.getAllParents()`
([`PlatformEvent.java`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java)).
The other-parent count is bounded by
`EventCreationConfig.maxOtherParents`
([`EventCreationConfig.java`](../../../consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/config/EventCreationConfig.java))
and applied at parent selection in
[`TipsetEventCreator`](../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java).
The hashgraph itself — the in-memory map of non-ancient events — is
held by
[`ConsensusLinker`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java).

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Sibling concepts:
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md),
  [`birth-round.md`](birth-round.md).
- Glossary entry: [`../glossary.md`](../glossary.md).
