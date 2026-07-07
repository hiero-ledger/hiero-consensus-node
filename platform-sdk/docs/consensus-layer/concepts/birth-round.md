---
type: concept
title: Birth round
last_reviewed: 2026-05-22
---

# Birth round

## Definition

An event's *birth round* is a round number stamped on the event when
its creator builds it: the *pending consensus round* — the round the
creator is currently working on reaching consensus for, equal to the
latest decided consensus round + 1. Birth round is immutable per event
and travels with it through gossip, intake, and consensus.

## Mechanics

The hashgraph uses birth round to decide what enters and stays in the
DAG. Because events are given a birth round equal to the creator's pending consensus round at the time of creation, it
must be possible to reach consensus on the previous round using events that already exist in the network. Events whose
birth round is below the current ancient threshold
are dropped (treated as ancient). Events whose birth round exceeds the
current pending consensus round are buffered and released later when
the window advances. Birth round is also the key the linker uses to
window-retain non-ancient events.

## Roster lookup

Birth round identifies the roster against which an event is validated.
The active roster can change on round boundaries — in principle on
every round, though today it changes only at upgrade, which is itself a
round boundary. The roster for an event's birth round defines the valid
set of event creators and their weights at that round. Because birth
round is fixed at creation, an event authored by a member that later
leaves the network remains valid against the roster of its birth round;
an event purporting to be authored by that member at a later birth
round, after the member's removal, would not be valid.

## Parent invariant

A child's birth round must be greater than or equal to every one of
its parents' birth rounds — the property cataloged as INV-011. If the network ever allowed a child whose
birth round was less than a parent's birth round, the ancient
threshold could advance past the child while leaving the parent
non-ancient. The child (and any of its own descendants with the same
or lower birth rounds) would be evicted from the DAG, while the
parent stayed in. If every child of that parent suffered the same
fate, the parent would be left with no living descendants — and an
event without descendants in the live DAG can never be ancestrally
reached by future events, so it can never accumulate the
strongly-seeing relationships it needs to reach consensus. The event
would be stranded: non-ancient by birth round, but effectively cut
off from the rest of the graph.

The invariant is enforced upstream of event creation. The event
creator runs incoming events through its own `FutureEventBuffer`
configured with `FutureEventBufferingOption.EVENT_BIRTH_ROUND`
([
`DefaultEventCreationManager.java`](../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/DefaultEventCreationManager.java)).
Any event whose birth round is above the local node's
*desired event birth round* (the birth round it would stamp on a new
event right now) is held in the buffer and not registered with the
creator — so the creator can never select it as a parent. Once the
event window advances enough that the buffered event's birth round is
no longer in the future, the buffer releases it to the creator. This
is a different `FutureEventBuffer` than the one inside the consensus
engine (which is configured with `PENDING_CONSENSUS_ROUND`); the two
buffers gate different stages with different thresholds, but the
event-creator one is what guarantees the parent-birth-round invariant
on locally-created events.

## Example

The pending consensus round is 100; the ancient threshold is 90. An
event with birth round 99 is admitted to the DAG. An
event with birth round 100 is admitted to the DAG. An event with birth
round 89 is dropped as ancient and reported as stale if it never
reached consensus. An event with birth round 101 sits in the future
buffer until the window advances such that the pending round is 101.

## In current code

Field accessor: `PlatformEvent.getBirthRound()` (line 266 of
[`PlatformEvent.java`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java)).
Sentinel: `EventConstants.BIRTH_ROUND_UNDEFINED`
([`EventConstants.java`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/EventConstants.java)).
Ancient drop happens in two places, both inside `ConsensusLinker`.
`ConsensusLinker.linkEvent` calls `EventWindow.isAncient` to reject
any incoming event whose birth round is below the ancient threshold,
so ancient events never enter the DAG. When the ancient threshold
advances, `ConsensusLinker.setEventWindow` evicts the newly-ancient
events from `parentDescriptorMap`/`parentHashMap` and calls
`EventImpl.clear()` on each one — which severs that event's parent
pointers so the DAG fragment behind the ancient boundary becomes
eligible for garbage collection (`EventImpl.clear()`'s JavaDoc spells
this out). `ConsensusImpl` separately removes ancient entries from
its own `recentEvents` queue and calls `clearMetadata()` to drop the
algorithm's scratch fields, but it does not touch parent pointers —
the DAG-level link-severing is the linker's job.
Future buffering:
[`FutureEventBuffer`](../../../consensus-utility/src/main/java/org/hiero/consensus/event/FutureEventBuffer.java)
configured with `FutureEventBufferingOption.PENDING_CONSENSUS_ROUND`.

Birth round replaces an older generation-based ancient/expiry scheme;
the paper uses an event's deterministic generation (max parent
generation + 1) to play the same role.

## Cross-references

- Architectural lens:
  [
  `../architecture/topics/hashgraph.md#birth-round-filtering`](../architecture/topics/hashgraph.md#birth-round-filtering).
- Sibling concept:
  [`rounds-and-witnesses.md`](rounds-and-witnesses.md).
- Invariants: INV-011 (birth round is monotonic along ancestry), INV-012 (the minimum non-ancient round never decreases).
- Rules: RUL-004 (consensus intake admits only non-ancient parent links whose claimed birth round matches the actual parent).
- Glossary entry: [`../glossary.md`](../glossary.md).
