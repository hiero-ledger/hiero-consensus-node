---
id: RUL-002
title: The intake pipeline is flushed component-by-component in topological order so every event advances as far as it can
class: structural
topics: [restart-and-pces, event-intake, wiring-framework]
components:
  - swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java
  - consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java
  - consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/DefaultEventCreationManager.java
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

After PCES replay completes — and before the event creator is permitted to
create new events — the consensus layer flushes the event intake pipeline by
visiting each component **in topological (upstream-to-downstream) order** and,
for each one, waiting until its task queue is empty **and** its last task has
been fully handled before moving to the next component. When this flush
returns, every event already in the pipeline has advanced as far as it can go:
each one has either reached consensus, been buffered as a future event inside
the component that will later release it, or been discarded as ancient or stale.

The load-bearing consequence is that the **event creator has observed the
latest self event** before it creates its next event. Creating a new self
event without knowing the most recent prior self event would produce two self
events with the same self-parent — a **branch**, which is **byzantine
behavior** and is detected and penalized by the network.

The same flush sequencing is also applied (via separate calls) to the
transaction handler and state hasher. That extension is **not** a correctness
requirement against branching; it is a performance choice — finish the work
already in flight before admitting new work that may push the system to its
throughput limit.

## Why it holds now

The order is enforced by `PlatformCoordinator.flushIntakePipeline()`, whose
body is a fixed sequence of per-component `flush()` calls and carries an
explicit warning that the order must not be changed without consulting the
wiring diagram:

```java
public void flushIntakePipeline() {
    components.eventIntakeModule().flush();
    components.pcesModule().flush();
    components.gossipModule().flush();
    components.hashgraphModule().flush();
    components.applicationTransactionPrehandlerWiring().flush();
    components.eventCreatorModule().flush();
    components.branchDetectorWiring().flush();
}
```

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
the property established by [ADR-005](../decisions/ADR-005-embedded-future-event-buffers.md):
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

The transaction-handler and state-hasher flushes are issued separately
(`PlatformCoordinator.flushTransactionHandler()`,
`PlatformCoordinator.flushStateHasher()`) as part of the restart
sequencing, for the performance reason stated above rather than to protect
against branching.

## Change risk

Several distinct mechanisms would break this rule:

- **Reordering or removing lines in `flushIntakePipeline()`.** Flushing a
  component before an upstream one that still feeds it leaves events stranded
  between them. The event creator could then create a self event without
  having seen the latest self event still sitting in an unflushed upstream
  queue — a branch.
- **Moving an event-holding buffer back out into a standalone upstream
  component** (reversing ADR-005), or otherwise creating a feedback edge where
  a downstream component's progress causes an upstream component to emit more
  events. A single ordered pass would no longer converge, and "queue empty"
  on one component would no longer imply "no more events will be produced."
- **Making `flush()` non-blocking** (returning before the queue drains or
  before the last task is fully handled) for any component in the chain.
- **Allowing event creation to start before `flushIntakePipeline()` returns**
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
- See [ADR-005](../decisions/ADR-005-embedded-future-event-buffers.md) for why
  the future-event buffers are embedded in `DefaultConsensusEngine` and
  `DefaultEventCreationManager` rather than factored into a single standalone
  component, which is the structural precondition that makes one ordered
  flush pass sufficient.
