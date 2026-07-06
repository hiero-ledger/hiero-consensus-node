---
type: rule
id: RUL-002
title: The intake pipeline is flushed component-by-component in topological order so every event advances as far as it can
class: structural
topics: [restart-and-pces, event-intake, wiring-framework]
components:
  - swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/SwirldsPlatform.java
  - consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java
  - consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/DefaultEventCreationManager.java
  - consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java
related:
  invariants: []
  decisions: [ADR-005]
  scenarios: []
  heuristics: []
status: holds
confidence: high
provenance: elicitation-2026-06-03
curated_by: Kelly Greco (@poulok)
---

# RUL-002 — The intake pipeline is flushed component-by-component in topological order so every event advances as far as it can

## Statement

After PCES replay completes and before the event creator may create new
events, the consensus layer flushes the event intake pipeline component-by-
component in topological (upstream-to-downstream) order, waiting for each
component's task queue to drain and its last task to be fully handled before
advancing to the next. When the flush returns, every event in the pipeline has
advanced as far as it can — so the event creator has observed the latest self
event before creating its next one.

## Context

When the flush returns, each event in the pipeline has either reached
consensus, been buffered as a future event inside the component that will later
release it, or been discarded as ancient or stale. The load-bearing
consequence is that the **event creator has observed the latest self event**
before it creates its next event: creating a new self event without knowing the
most recent prior self event would produce two self events with the same
self-parent — a **branch**, which is **byzantine behavior** that the network
detects and can penalize.

The same ordered pass also flushes the transaction handler (pre-handler then
handler) and the state hasher, in the same method. Those extensions are **not** a
correctness requirement against branching; they are a performance choice —
finish the work already in flight before admitting new work that may push the
system to its throughput limit.

## Why it holds now

The order is enforced by `PlatformCoordinator.flushPrimaryPipeline()`, whose
body is a fixed sequence of per-component `flush()` calls and carries an
explicit warning that the order must not be changed without consulting the
wiring diagram:

```java
public void flushPrimaryPipeline() {
    components.eventIntakeModule().flush();
    components.pcesModule().flush();
    components.gossipModule().flush();
    components.hashgraphModule().flush();
    components.transactionHandlingModule().flush();
    components.eventCreatorModule().flush();
    components.stateManagementModule().flush();
}
```

The method only flushes; it does not decide whether the flushed work is
delivered or discarded. That depends on the state of the components when it is
called — live components deliver (the PCES-replay caller), squelched components
discard (the reconnect caller). The branching guarantee below concerns the
replay caller, where the components are live.

Each `flush()` is a wiring-framework primitive that blocks until the
component's input queue has drained and the in-progress task has finished.
Because the calls run sequentially and the order matches the direction events
flow through the component graph, flushing an upstream component first guarantees that
everything it was going to emit has already been handed to the next component
*before* that next component is flushed. Walking the whole chain this way
leaves no event stranded mid-pipeline: by the time the event-creator module is
flushed, every event that could become a parent has already reached it.

The ordering is only sufficient because each component that can *hold an event
back* does its holding **internally**, so "queue empty + last task handled"
truly means "nothing more will come out of this component on its own." This is
the property established by ADR-005:
the hashgraph's future-event buffer lives inside `DefaultConsensusEngine` and
the event creator's future-event buffer lives inside
`DefaultEventCreationManager`, rather than in a standalone component upstream
of both. In particular, `DefaultConsensusEngine.addEvent(...)` closes the
consensus-advance → event-window-advance → release-buffered-events loop
*within the handling of a single task* (its internal `while (!eventsToAdd
.isEmpty())` loop drains the future-event buffer as the event window
advances). So when the hashgraph module's queue is empty, no further rounds
will reach consensus on their own and no further events will be released —
which is exactly what makes a single in-order pass of `flush()` calls
sufficient. Had the buffer been a free-standing component feeding both the
hashgraph and the event creator, consensus progress would feed back into that
upstream component and a single ordered pass would not converge.

The hashgraph's future-event buffer is not the only event-holding component.
There *is* a real feedback edge in the wiring:

1. Each consensus round makes the hashgraph emit a new event window.
2. The window is injected back into the **orphan buffer** in the intake module
   (`DefaultOrphanBuffer.setEventWindow`).
3. A window advance can un-orphan events whose missing parents have just become
   ancient (`missingParentBecameAncient`), feeding fresh events back toward a
   hashgraph that has already been flushed.

The pass is single and never re-flushes the intake module, so any such release
would strand events behind it. Single-pass sufficiency therefore needs a third
condition: **the orphan buffer is inert during the flush.**

On the replay path it is — because of the seeded window, not the PCES stream
order. Before replay, the orphan buffer is seeded with the loaded state's event
window (`SwirldsPlatform` startup → `PlatformCoordinator.updateEventWindow`),
and the replay lower bound is that same loaded-state ancient threshold
(`SwirldsPlatform` sets `pcesReplayLowerBound = initialAncientThreshold`). So
the replay set holds only events non-ancient at the seeded window, and every
parent of a replayed event is either:

- present — non-ancient, so itself replayed; or
- already ancient — excluded by `DefaultOrphanBuffer.getMissingParents`.

No event is ever held for later release by a window advance. Stream order is not
the reason: an event released live only because a never-arriving parent went
ancient has no parent in the stream at all, yet still passes straight through —
that parent is already ancient in the seeded window.

The transaction-handler and state-hasher flushes are part of the same single
ordered pass (the last entries in `flushPrimaryPipeline()`), not separate calls,
and are included for the performance reason stated above rather than to protect
against branching.

## Change risk

Several distinct mechanisms would break this rule:

- **Reordering or removing lines in `flushPrimaryPipeline()`.** Flushing a
  component before an upstream one that still feeds it leaves events stranded
  between them. The event creator could then create a self event without
  having seen the latest self event still sitting in an unflushed upstream
  queue — a branch.
- **Moving an event-holding buffer back out into a standalone upstream
  component** (reversing ADR-005), or otherwise creating a feedback edge where
  a downstream component's progress causes an upstream component to emit more
  events. A single ordered pass would no longer converge, and "queue empty"
  on one component would no longer imply "no more events will be produced."
- **Making the orphan buffer release events during the flush.** The
  event-window feedback edge into `DefaultOrphanBuffer` is real but inert on the
  replay path only because the buffer is seeded with the loaded state's window
  (see *Why it holds now*). Anything that breaks that seeding — replaying events
  ancient or non-ancient relative to a *different* window than the orphan buffer
  holds, or seeding the orphan buffer after replay starts — could cause a
  window advance mid-flush to un-orphan events behind the already-flushed
  hashgraph, stranding them.
- **Making `flush()` non-blocking** (returning before the queue drains or
  before the last task is fully handled) for any component in the chain.
- **Allowing event creation to start before `flushPrimaryPipeline()` returns**
  at the post-replay boundary.

Breaking this rule is a **flag for confirmation**, not automatically a defect:
a deliberate redesign of how events are held and released, or of the wiring
topology, could legitimately change or retire this rule. The two are
indistinguishable from the diff alone. Confirmation looks like answering: *does
the event creator still provably observe the latest self event before creating
its next event after this change?* If the new design preserves that
guarantee by other means, the change is correct and this rule should be
revised or retired; if it does not, the change reintroduces a branching risk
and must be rejected.

## Notes

- The no-branching property the rule protects (a node must never create two
  self events sharing a self-parent) is a permanent byzantine-safety
  requirement and is a candidate to be cataloged as an invariant; this rule
  records only the *current implementation mechanism* — ordered, blocking,
  topological flush — by which that property is upheld after PCES replay.
- See ADR-005 for why
  the future-event buffers are embedded in `DefaultConsensusEngine` and
  `DefaultEventCreationManager` rather than factored into a single standalone
  component, which is the structural precondition that makes one ordered
  flush pass sufficient.
- The orphan buffer is a second event-holding component; single-pass
  sufficiency also depends on it being inert during the flush. *Why it holds
  now* covers the replay path (the seeded window). On the reconnect path it is
  inert for a different reason: the upstream components are squelched, so the
  hashgraph produces no event windows. This "orphan buffer releases no events"
  condition is load-bearing enough to be a candidate for its own rule.
