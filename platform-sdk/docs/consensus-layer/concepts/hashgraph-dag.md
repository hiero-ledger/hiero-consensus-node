---
type: concept
title: Hashgraph DAG
last_reviewed: 2026-05-22
---

# Hashgraph DAG

## Definition

The hashgraph is, formally, a directed acyclic graph (DAG) whose
vertices are events and whose edges are parent references. Each event
has at most one *self-parent* (the previous event by the same
creator) and zero or more *other-parents* (events by peers that the
creator just gossiped with). The data model supports an arbitrary
number of other-parents; the current default configuration caps it at
one (`EventCreationConfig.maxOtherParents` defaults to `1`), but the
limit is a config knob that is expected to be raised in the future to
allow events to reference multiple other-parents. Either kind of
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
B's prior event and whose other-parent is `a₀`. Continuing across the
four nodes, every new event has at most one self-parent edge and (in
the current default configuration) at most one other-parent edge; if
`maxOtherParents` is raised, an event may carry several other-parent
edges at once. The union of all events and parent edges is the
hashgraph.

## In current code

The linked hashgraph node is
[`EventImpl`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java),
which holds parent pointers in an `allParents` array. Parent
descriptors travel with each event via `PlatformEvent.getAllParents()`
([`PlatformEvent.java`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java)).
The other-parent count is bounded by
`EventCreationConfig.maxOtherParents`
([`EventCreationConfig.java`](../../../consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/config/EventCreationConfig.java),
default `1`) and applied at parent selection in
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
- Invariants: INV-001 (voting round is monotonic along ancestry), INV-012 (birth round is monotonic along ancestry).
- Glossary entry: [`../glossary.md`](../glossary.md).
