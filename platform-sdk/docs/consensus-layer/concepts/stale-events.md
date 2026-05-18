---
title: Stale events
kind: concept
last_reviewed: TBD
---

# Stale events

## Definition

A *stale event* in current code is an event that was admitted to the
hashgraph and then aged past the ancient threshold without ever
reaching consensus on this node. By definition, a stale event did
not reach consensus and never will — its transactions are not part
of the consensus order anywhere in the network. Stale is a *fate*,
not one of the [event lifecycle](event-lifecycle.md) states: the
lifecycle describes states an admitted event passes through, and
stale is the specific outcome of an admitted event whose lifecycle
ended in *ancient* without consensus.

Because a stale event is by definition an event that does not reach
consensus and never will, stale event reports are **not deterministic
across nodes**. The same underlying event may show up as stale on
some nodes and never be observed at all on others. The creating node
may simply have never gossiped it, or did gossip it but the event was
not propagated through the network quickly enough to be well connected
in the graph (i.e. not propagated to enough peers in time to end up
in the ancestry of the judges of the rounds before its birth
round went ancient). There is no consensus-level
guarantee that every node sees the
same stale set; the stale-events output is a local-observation
stream.

The fate matters mostly for self-events. Their transactions never
became part of the consensus order, so the application can resubmit
them on a fresh self-event. Stale reports for events authored by
other creators are informational; the local node does not own those
transactions.

## Mechanics

When the ancient threshold advances, the linker unlinks events that
just became ancient and returns the list. The hashgraph engine
partitions that list into events that reached consensus (no further
action) and events that did not. The latter are reported as stale on
the stale-events output wire; an application-layer consumer
registered with the platform builder receives them and may resubmit
their transactions.

Events that arrive at intake already past the ancient threshold are
silently dropped at validation or linking — they never enter the
hashgraph, so they never become stale in the sense above. In current
code *stale* specifically denotes the post-admission outcome, not
pre-admission rejection.

## Example

Node A creates self-event `s` at birth round 50, but A is briefly
partitioned before `s` propagates widely enough to end up in the
ancestry of any decided round's judges. An event reaches judge
ancestry only when peers select it as an other-parent when they
create their own events (see
[`../architecture/topics/event-creator.md`](../architecture/topics/event-creator.md));
peers fail to do that for `s` either because they never received it
(gossip never delivered it — partition is one cause, and
[`reasons-not-to-gossip.md`](reasons-not-to-gossip.md) covers the
others) or because they had `s` but chose different other-parents.
Eventually the ancient threshold advances past 50; the linker
unlinks `s` as ancient, and because `s` never reached consensus the
engine emits `s` on the stale-events output. A's stale-event
callback receives `s` and resubmits its transactions on a new
self-event.

## In current code

Stale events are produced by
[
`DefaultConsensusEngine.addEvent`](../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java)
(lines ~122, 180–185, 192–193): non-consensus events returned by
`ConsensusLinker.setEventWindow` are appended to the stale-events
list and emitted via
[
`HashgraphModule.staleEventOutputWire`](../../../consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java).
Application consumers register through
[
`PlatformBuilder.withStaleEventCallback`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java)
(line 250).

No `StaleEventDetector` class exists in current code. The legacy
[
`StaleEventDetectorOutput`](../../../consensus-model/src/main/java/org/hiero/consensus/model/event/StaleEventDetectorOutput.java)
enum still ships in `consensus-model` but is dead code; its removal
is tracked in
[hiero-consensus-node#25505](https://github.com/hiero-ledger/hiero-consensus-node/issues/25505).

## Cross-references

- Architectural lens:
  [`../architecture/topics/hashgraph.md`](../architecture/topics/hashgraph.md),
  [`../architecture/topics/event-intake.md`](../architecture/topics/event-intake.md),
  [`../architecture/topics/gossip.md`](../architecture/topics/gossip.md).
- Sibling concept: [`event-lifecycle.md`](event-lifecycle.md).
- Glossary entry: [`../../hashgraphGlossary.md`](../../hashgraphGlossary.md).
