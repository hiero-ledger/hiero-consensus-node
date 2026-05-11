---
title: Hashgraph DAG
kind: concept
last_reviewed: TBD
---

# Hashgraph DAG

## Definition

A hashgraph is a directed acyclic graph (DAG) whose vertices are events
and whose edges are parent references. Each event has up to two parents:
a *self-parent* (the previous event by the same creator) and an
*other-parent* (an event by a peer the creator just gossiped with).
Either parent may be absent for the very first event a creator ever
makes.

## Mechanics

Events reference parents by hash; once an event is built, its parent
edges are immutable. The DAG grows monotonically as nodes gossip and
create new events. The hashgraph layer holds only the non-ancient
slice — the events whose birth round is at or above the current ancient
threshold; older events fall out of the DAG once consensus has passed
them by.

## Example

Four nodes A, B, C, D. A creates `a₀` with no parents (A's first
event). After A gossips with B, B creates `b₁` whose self-parent is B's
prior event and whose other-parent is `a₀`. Continuing across the four
nodes, every new event has up to two parent edges — at most one to its
own previous event, at most one to an event from another creator. The
union of all events and parent edges is the hashgraph DAG.

## In current code

The linked DAG node is
[`EventImpl`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java),
which holds parent pointers in an `allParents` array. Parent
descriptors travel with each event via `PlatformEvent.getAllParents()`
([`PlatformEvent.java`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java)).
The DAG itself — the in-memory map of non-ancient events — is held by
[`ConsensusLinker`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java).

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md).
- Sibling concepts:
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md),
  [`birth-round.md`](birth-round.md).
- Glossary entry: [`../glossary.md`](../glossary.md).
