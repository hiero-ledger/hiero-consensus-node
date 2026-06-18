---
type: rule
id: RUL-003
title: Every node contributing to consensus is independently restartable
class: structural
topics: [restart-and-pces, signed-state-management, reconnect, event-creator]
components:
  - swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java
  - consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/writer/DefaultInlinePcesWriter.java
  - consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/common/CommonPcesWriter.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SignedStateFileWriter.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/components/DefaultSavedStateController.java
  - consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/ReconnectCompleteStatusLogic.java
  - consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectController.java
related:
  invariants: []
  decisions: [ADR-007]
  scenarios: []
  heuristics: []
status: holds
confidence: high
provenance: extraction-2026-06-09
curated_by: Kelly Greco (@poulok)
---

# RUL-003 — Every node contributing to consensus is independently restartable

## Statement

Every node that is contributing to the advancement of consensus — i.e. that is
creating events — is independently restartable: it holds an on-disk state it can
restart from on its own, with no gap in its Preconsensus Event Stream (PCES)
above it.

## Context

"Independently restartable" means a node that crashes can recover on its own:
reload its latest on-disk signed state and replay its PCES on top to rebuild the
in-memory hashgraph. Because the only way a node contributes to consensus is by
creating events, tying restartability to event creation makes "contributes to
consensus" and "is crash resilient" the same condition for every node.

The property exists for **network-wide recoverability**. Consensus can only
advance through nodes that are creating events. If every such node is
independently restartable, no reachable consensus position can leave the whole
network unable to restart after a simultaneous crash — there is always at least
one startable on-disk state covering the current position.
[ADR-007](../decisions/ADR-007-save-reconnect-state-before-resuming-event-creation.md)
works through the rolling-reconnect scenario in which losing this property would
make the network unrecoverable.

This is a property of the current implementation, not a requirement of the
protocol: nothing *demands* that only crash-resilient nodes create events. A
correct reimplementation could, for example, resume event creation earlier and
backfill the missing events from gossip. It holds today because several
independent mechanisms each keep a contributing node's own history on disk.

## Why it holds now

No single component owns the property; it is the combined effect of the event
persistence path, signed-state saving, and the reconnect gate.

- **Steady state — persisted before observed.** Every validated event is written
  to PCES *before* any downstream component observes it: the writer's output wire
  is soldered ahead of consensus, gossip, and the event creator's parent-selection
  input (`PlatformWiring.java:86-96`), and the inline writer writes the event to
  the current file before emitting it (`DefaultInlinePcesWriter.java:71-75`). So
  every event a node needs to reach consensus is durable on disk before consensus
  acts on it; on an ordinary restart the node rebuilds its hashgraph by replaying
  its own PCES, with no help from peers. Graceful shutdown flushes the OS buffer
  to disk through a JVM shutdown hook (`CommonPcesWriter.java:136-150`); the
  residual loss window on `SIGKILL` or power loss is an accepted risk that does
  not by itself produce a network-wide unrecoverable state (see
  [restart-and-pces.md](../architecture/topics/restart-and-pces.md)).
- **Periodic — a recent on-disk base state.** A signed state is produced at every
  block boundary (and at the freeze round) and marked for saving on a period
  (`DefaultSavedStateController.java:111`), written to disk by
  `SignedStateFileWriter` (see
  [signed-state-management.md](../architecture/topics/signed-state-management.md)).
  This gives the node a recent, complete on-disk state to restart from — the base
  that PCES replay builds on top of — and bounds how far back the stream must be
  replayed.
- **Reconnect — re-establish restartability before resuming.** Reconnect is the
  one operation that breaks the property: the learner receives a signed state and
  the events above it, but not the events connecting its last on-disk state to the
  learned one, leaving a PCES gap so its previous on-disk state is no longer a
  valid restart point. The node therefore persists the learned state before it
  resumes creating events — it enters `RECONNECT_COMPLETE` before the disk save
  begins (`ReconnectController.java:247-250`), marks the learned state to be saved
  with reason `RECONNECT` (`DefaultSavedStateController.java:62-67`), gossips but
  does not create events while in that status (`PlatformStatusRule.java:37-45`),
  and leaves it only when a `StateWrittenToDiskAction` reports the reconnect state
  (or later) on disk (`ReconnectCompleteStatusLogic.java:156-187`). See
  [ADR-007](../decisions/ADR-007-save-reconnect-state-before-resuming-event-creation.md).

The property is contingent on this combination. If event persistence stopped
preceding observation, if the node stopped retaining a loadable base state and
the PCES above it, or if the status gates stopped withholding event creation
until the node's own history is on disk, the rule would no longer hold — and any
of those could be a deliberate redesign rather than a bug.

## Change risk

Changes in several distinct areas would break this rule:

- **Breaking persisted-before-observed.** Making the PCES write asynchronous or
  non-blocking, or re-soldering consensus, gossip, or parent selection to observe
  events before the write returns. Events the node has already acted on could then
  be lost on a crash, leaving its on-disk PCES unable to rebuild the hashgraph.
- **Weakening durability on shutdown.** Removing the graceful-shutdown sync, or
  moving to a model that can lose events the node has already acted on.
- **Losing the base state or the PCES above it.** No longer retaining a recent,
  loadable on-disk signed state to restart from, or pruning source PCES files that
  replay from the latest saved state still needs, so on-disk replay can no longer
  bridge from the base state up to the current consensus position.
- **Resuming event creation with a reconnect PCES gap still present.** After a
  reconnect the node's latest on-disk state is still the old one and its PCES
  cannot bridge to the learned state, so a node that creates events before
  persisting the learned state is contributing to consensus while unable to
  rebuild from its own disk. This happens if `RECONNECT_COMPLETE` is added to the
  event-creation permit set, if the node exits it before the reconnect-state write
  completes, or if the `RECONNECT` save is never requested.

Breaking this rule is a **flag for confirmation**, not automatically a defect. A
deliberate redesign of how a contributing node is made crash resilient — for
example, resuming event creation earlier and backfilling missing events from
gossip — could legitimately change or retire it. Confirmation looks like
answering: *after this change, can a node still rebuild its hashgraph from its own
disk before it resumes creating events?* If the new design preserves that by other
means, the change is correct and this rule should be revised or retired; if it does
not, the change reintroduces the network-wide unrecoverability described in ADR-007
and must be rejected.

## Notes

- The property is upheld jointly by normal PCES operation, signed-state saving,
  and the reconnect gate; no single component owns it, so a change in any of those
  areas can put it at risk.
- Related to [RUL-002](RUL-002-intake-flush-ordering.md), which governs the same
  restart boundary from the other side: it flushes the intake pipeline before
  event creation resumes so the event creator observes the latest self event.
- See [ADR-007](../decisions/ADR-007-save-reconnect-state-before-resuming-event-creation.md)
  for the reconnect gate and the network-wide unrecoverability scenario this rule
  prevents.
