---
id: ADR-005
title: Save the Reconnect State to Disk Before Resuming Event Creation
topics: [reconnect, platform-status, event-creation, pces, signed-state]
related:
  invariants: []
  decisions: []
  scenarios: []
  heuristics: []
  rules: []
status: accepted
date: 2026-06-04
deciders:
  - Kelly Greco (@poulok)
curated_by: Kelly Greco (@poulok)
---

# ADR-005 — Save the Reconnect State to Disk Before Resuming Event Creation

## Context

When a node falls too far behind the network it can no longer follow consensus from gossip alone. It enters the
`BEHIND` status (`platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java:18-21`)
and **reconnects**: a *teacher* peer sends the *learner* a recent signed state, and the learner then rebuilds its
hashgraph from that state forward using events it receives from gossip.

This recovery has a gap. The learner receives the state and the events *above* it, but it does **not** receive the
events that connect its **last state on disk** to the **newly learned state**. Those intervening events are missing from
its Preconsensus Event Stream (PCES). PCES is what a node replays on startup to rebuild the hashgraph from its last
on-disk state (see [`../architecture/topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md)). With a
gap in PCES, the node's last on-disk state is no longer a valid restart point — replay cannot bridge the missing rounds.

A node that has reconnected but has not yet written the learned state to disk is therefore **not crash resilient**: if
it crashed, it could not restart from its own disk.

### The network-wide unrecoverability scenario

Consider a network where reconnects ripple across the membership faster than the normal state-save cadence:

1. One third of the nodes fall behind; consensus stalls until they reconnect. Consensus resumes.
2. A *different* third falls behind; consensus stalls until they reconnect. Consensus resumes.
3. The *remaining* third falls behind and reconnects. Consensus resumes.

If all of this happens **between normal state-save boundaries**, every node now carries a PCES gap between its last
on-disk state and the state it learned via reconnect. No node can restart cleanly from its own disk. A simultaneous
crash of the whole network would be **unrecoverable** — there is no node anywhere holding a startable on-disk state that
covers the current consensus position.

### The invariant that prevents this

The network stays recoverable as long as **at least one node that is contributing to the advancement of consensus is
crash resilient** — i.e. holds an on-disk state it can actually restart from, with no PCES gap above it. If consensus
can only advance through nodes that are each individually startable, then no reachable consensus position can leave the
whole network stuck.

A node "contributes to the advancement of consensus" precisely when it **creates events**. So the cleanest place to
enforce the invariant is the event-creation gate: a node must not resume creating events after a reconnect until it has
made itself crash resilient again, which means **writing the learned state to disk**.

## Decision

**After a reconnect, the learner must write the learned state to disk before it resumes creating events.**

This is enforced through the platform status state machine and the event-creation gate:

- The reconnect path transitions the learner to the `RECONNECT_COMPLETE` status
  (`PlatformStatus.java:47-51`), and **before** beginning the disk save, so the status is guaranteed to be
  `RECONNECT_COMPLETE` by the time the save runs
  (`platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectController.java:247-250`).
  The same path immediately marks the learned state to be saved to disk with reason `RECONNECT`
  (`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/components/DefaultSavedStateController.java:62-67`).
- In `RECONNECT_COMPLETE` the platform **gossips but does not create events**. The event-creation gate permits creation
  only in `ACTIVE`, `CHECKING`, or `FREEZING` (the last only to emit the freeze-state signature)
  (`platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java:37-45`).
- The node leaves `RECONNECT_COMPLETE` only when a `StateWrittenToDiskAction` reports that the **reconnect state (or a
  later state) has been written to disk**. A disk write for a round *prior* to the reconnect state is treated as stale
  and the node keeps waiting. Once the reconnect state is persisted, the node transitions to `CHECKING` — or to
  `FREEZING` if a freeze boundary was crossed — and event creation resumes
  (`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/ReconnectCompleteStatusLogic.java:156-187`).

Writing the learned state to disk closes the PCES gap as a recovery concern: the node now has a startable on-disk point
covering the post-reconnect consensus position. Only then is it allowed to rejoin event creation and again contribute to
advancing consensus.

## Consequences

### Positive

- **Crash resilience is preserved across reconnect cascades.** Any node creating events has a startable on-disk state,
  so the rippling-reconnect scenario above can never reach a position from which the whole network is unrecoverable.
- **The guarantee is enforced where it matters.** Tying it to the event-creation gate makes "contributes to consensus"
  and "is crash resilient" the same condition for every node, rather than relying on the normal state-save cadence to
  happen to land in time.
- **Reuses existing machinery.** The status state machine, the saved-state controller, and the `StateWrittenToDiskAction`
  signal already exist; the guarantee is expressed as a status that withholds event creation until a disk write it
  already requested completes.

### Negative

- **Added latency before a reconnected node creates events.** The node must complete a full state write to disk before
  leaving `RECONNECT_COMPLETE`. On large states this write is not instant, so there is a delay between finishing
  reconnect and resuming contribution to consensus.
- **A reconnected node that cannot persist cannot rejoin event creation.** If the disk write never succeeds (for
  example, a full or failing disk), the node stays in `RECONNECT_COMPLETE` indefinitely — gossiping but never creating
  events. This is intentional: a node that cannot make itself crash resilient should not be advancing consensus. It does
  mean a persistence fault manifests as a node stuck out of event creation rather than as an explicit error at the gate.

### Neutral

- **Gossip continues throughout `RECONNECT_COMPLETE`.** Withholding *event creation* does not withhold *gossip*; the
  node still helps distribute events while it waits for its own state write, so the pause does not isolate it from the
  network.
- **The transition out is data-driven, not timed.** Unlike `OBSERVING`, which exits after a fixed delay
  (see [ADR-004](ADR-004-retain-observing-status-for-self-event-recovery.md)), `RECONNECT_COMPLETE` exits on a concrete
  event — the reconnect state (or later) reaching disk — so the wait is exactly as long as the write takes, no more and
  no less.

## Alternatives Considered

### 1. Resume event creation immediately after reconnect, before the state is saved

Let the learner transition straight from reconnect to `CHECKING` and rely on the normal periodic state-save cadence to
persist a startable state eventually.

**Rejected because:**

- It permits exactly the network-wide unrecoverability scenario in **Context**. If reconnects cascade across the
  membership between state-save boundaries, every node ends up with a PCES gap above its last on-disk state, and a
  simultaneous crash leaves the network with no startable state anywhere.
- It breaks the invariant that every node contributing to consensus is independently restartable. Crash resilience would
  then depend on timing (did a save happen to land before the next reconnect?) rather than being guaranteed.

### 2. Backfill the PCES gap from gossip instead of saving the state

Have the learner request the intervening events — those between its last on-disk state and the learned state — so its
existing on-disk state becomes a valid restart point again.

**Rejected because:**

- Those events may no longer be available from any peer, and fetching an arbitrary historical range is far more complex
  and fragile than writing the state the node already holds in memory.
- It optimizes to preserve an *old* on-disk state when the node already has a *newer*, complete state in hand. Persisting
  the learned state is simpler and yields a better (more recent) restart point.

### 3. Save the reconnect state to disk before resuming event creation (selected)

See **Decision** above.

## References

- [`../../core/platform-status.md`](../../core/platform-status.md) — the platform status explainer; describes
  `BEHIND`, `RECONNECT_COMPLETE`, `CHECKING`, and when the node creates events.
- [`../architecture/topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md) — how a node replays PCES
  from its last on-disk state on startup, which is what a PCES gap invalidates.
- [ADR-004](ADR-004-retain-observing-status-for-self-event-recovery.md) — the related startup safeguard (`OBSERVING`);
  contrast its fixed-delay exit with `RECONNECT_COMPLETE`'s state-written-to-disk exit.
- `platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java:18-51` — the `BEHIND`
  and `RECONNECT_COMPLETE` status definitions and their javadoc.
- `platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectController.java:247-250`
  — the reconnect path transitions to `RECONNECT_COMPLETE` and then marks the learned state for disk save.
- `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/components/DefaultSavedStateController.java:62-67`
  — `reconnectStateReceived(...)` marks the learned state to be written to disk with reason `RECONNECT`.
- `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java:37-45`
  — the event-creation gate; creation is withheld in `RECONNECT_COMPLETE`.
- `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/ReconnectCompleteStatusLogic.java:156-187`
  — exit from `RECONNECT_COMPLETE` on `StateWrittenToDiskAction`: wait while the persisted round is below the reconnect
  round, then transition to `CHECKING` (or `FREEZING`).
