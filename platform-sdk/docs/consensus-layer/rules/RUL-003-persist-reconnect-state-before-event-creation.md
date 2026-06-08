---
id: RUL-003
title: A reconnected node must write the learned state to disk before it resumes creating events
class: state-machine
topics: [reconnect, event-creator, restart-and-pces, signed-state-management]
components:
  - consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/ReconnectCompleteStatusLogic.java
  - swirlds-platform-core/src/main/java/com/swirlds/platform/components/DefaultSavedStateController.java
  - consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectController.java
related:
  invariants: []
  decisions: [ADR-007]
  scenarios: []
  heuristics: []
status: holds
confidence: high
provenance: adr-007
curated_by: Kelly Greco (@poulok)
---

# RUL-003 — A reconnected node must write the learned state to disk before it resumes creating events

## Statement

After a reconnect, a node does not resume creating events until the learned
(reconnect) state has been written to disk. Until that write completes the node
stays in `RECONNECT_COMPLETE` — gossiping, but not creating events — so any node
that is contributing to consensus by creating events is also crash resilient:
it holds an on-disk state it can restart from, with no gap in its Preconsensus
Event Stream (PCES) above it.

## Context

A reconnect leaves a PCES gap: the learner receives the signed state and the
events above it, but not the events connecting its last on-disk state to the
newly learned one, so its previous on-disk state is no longer a valid restart
point. A node that resumed creating events before persisting the learned state
would be contributing to consensus while unable to restart from its own disk. If
that condition were allowed to ripple across a rotating minority of the
membership, every node's on-disk state could end up predating its reconnect,
leaving the whole network unable to restart cleanly after a simultaneous crash.
Holding event creation until the learned state is on disk is what keeps "creates
events" and "is crash resilient" the same condition for every node.
[ADR-007](../decisions/ADR-007-save-reconnect-state-before-resuming-event-creation.md)
records the full rationale, including the network-wide unrecoverability scenario
this prevents.

This is a property of the current implementation, not a requirement of the
protocol: the network does not *demand* that only crash-resilient nodes create
events, and a correct reimplementation could instead backfill the PCES gap from
gossip and resume event creation immediately (the alternative weighed in
ADR-007).

## Why it holds now

The guarantee is enforced through the platform status state machine and the
event-creation gate:

- The reconnect path transitions the learner to `RECONNECT_COMPLETE` **before**
  beginning the disk save, so the status is `RECONNECT_COMPLETE` by the time the
  save runs (`ReconnectController.java:247-250`), and the same path marks the
  learned state to be saved with reason `RECONNECT`
  (`DefaultSavedStateController.java:62-67`).
- The event-creation gate permits creation only in `ACTIVE`, `CHECKING`, or
  `FREEZING` (the last only to emit the freeze-state signature), so
  `RECONNECT_COMPLETE` gossips but does not create events
  (`PlatformStatusRule.java:37-45`).
- The node leaves `RECONNECT_COMPLETE` only when a `StateWrittenToDiskAction`
  reports that the reconnect state — or a later state — has reached disk; a
  write for a round *prior* to the reconnect state is treated as stale and the
  node keeps waiting. Once the reconnect state is persisted it transitions to
  `CHECKING` (or `FREEZING` if a freeze boundary was crossed) and event creation
  resumes (`ReconnectCompleteStatusLogic.java:156-187`).

The property rests on this routing: that the reconnect path passes through
`RECONNECT_COMPLETE`, that the gate excludes that status, and that the exit is
keyed to the reconnect state reaching disk. If any of those stops being true,
the rule no longer holds.

## Change risk

Several distinct mechanisms would break this rule:

- **Adding `RECONNECT_COMPLETE` to the event-creation permit set** in
  `PlatformStatusRule`, letting a reconnected node create events before its
  learned state is on disk.
- **Transitioning out of `RECONNECT_COMPLETE` before the reconnect-state disk
  write completes** — for example, exiting on a timer or on an earlier
  state's write, rather than on a `StateWrittenToDiskAction` for the reconnect
  state or later.
- **The reconnect path no longer requesting the state save**, or requesting it
  without the `RECONNECT` reason, so no `StateWrittenToDiskAction` ever
  satisfies the exit condition (also a liveness hazard).

Breaking this rule is a **flag for confirmation**, not automatically a defect.
A deliberate redesign of how a reconnected node is made crash resilient — for
example, backfilling the PCES gap from gossip and resuming event creation
immediately, rather than persisting the learned state first — could legitimately
change or retire it. Confirmation looks like answering: *after this
change, is a node still provably crash resilient — startable from its own disk
with no PCES gap above it — before it resumes creating events?* If the new
design preserves that by other means, the change is correct and this rule should
be revised or retired; if it does not, the change reintroduces the risk of
network-wide unrecoverability described in ADR-007 and must be rejected.

## Notes

- See [ADR-007](../decisions/ADR-007-save-reconnect-state-before-resuming-event-creation.md)
  for the decision and the network-wide unrecoverability scenario that motivates
  this rule.
