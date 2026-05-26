---
title: Event creator
kind: architecture-topic
last_reviewed: TBD
---

# Event creator

## Responsibilities

The event creator is the consensus-layer component that decides when this
node should create a new self-event, selects the other parents that
maximise progress of the hashgraph, fills the event with user
transactions, signs it, and emits it to event intake. The decision logic
is the tipset algorithm: each new self-event must improve the partial
weighted advancement score against a moving snapshot of recent tipsets,
and the event creator periodically chooses ignored peers as other parents
to keep them from being starved out of consensus.

The event creator does **not**:

- gossip events — events leave through `createdEventOutputWire` and are
  routed by the wiring framework through event intake and persistence to
  gossip;
- persist events — inline PCES persistence happens downstream of event
  intake (see [Self-event persistence](#self-event-persistence));
- run the hashgraph algorithm — it consumes the hashgraph's event window
  to determine ancient events but does not reach consensus itself.

## State

All state is owned, directly or transitively, by the implementation of
`EventCreator` (`consensus-event-creator-impl/.../EventCreator.java`).
The default implementation is `TipsetEventCreator`
(`consensus-event-creator-impl/.../tipset/TipsetEventCreator.java`), which
holds four collaborating objects:

- **Per-event tipsets** — `TipsetTracker`
  (`consensus-event-creator-impl/.../tipset/TipsetTracker.java`) keeps a
  `SequenceMap<EventDescriptorWrapper, Tipset>` of every non-ancient
  event's tipset, plus a `latestGenerations` tipset summarising the
  highest sequence number observed per node.
- **Snapshot and score accumulators** — `TipsetWeightCalculator`
  (`consensus-event-creator-impl/.../tipset/TipsetWeightCalculator.java`)
  owns the current `snapshot` tipset, a bounded `snapshotHistory` deque
  (sized by `tipsetSnapshotHistorySize`), the `previousAdvancementWeight`
  carried from the last self-event, and `latestSelfEventTipset`.
- **Other-parent candidates** — `ChildlessEventTracker`
  (`consensus-event-creator-impl/.../tipset/ChildlessEventTracker.java`)
  keeps the set of non-ancient peer events that have no observed
  children; these are the eligible other-parent pool.
- **Last self-event and event window** — `TipsetEventCreator` itself
  retains `lastSelfEvent` (used as self-parent) and the most recent
  `EventWindow`.

Around the `EventCreator`, `DefaultEventCreationManager`
(`consensus-event-creator-impl/.../DefaultEventCreationManager.java`)
holds the orchestration state: the aggregated `EventCreationRule` chain,
the most recent `unhealthyDuration` reported by the health monitor, and
a `FutureEventBuffer` that defers events whose birth round has not yet
arrived.

## Inputs and outputs

The module's public surface is the `EventCreatorModule` interface
(`consensus-event-creator/.../EventCreatorModule.java`), which exposes
input and output wires plus an `EventTransactionSupplier` passed at
`initialize`-time.

**Inputs**

- **Validated events from event intake** — `orderedEventInputWire`
  delivers `PlatformEvent`s; they pass through `FutureEventBuffer` in
  `DefaultEventCreationManager#registerEvent` and reach
  `TipsetEventCreator#registerEvent`, which routes self-events to
  `TipsetTracker#addSelfEvent` and peer events to
  `TipsetTracker#addPeerEvent` (the latter advances both the per-event
  tipset and `latestGenerations` using the event's sequence number). See
  [event-intake.md](event-intake.md).
- **Event window from hashgraph** — `eventWindowInputWire` flows to
  `TipsetEventCreator#setEventWindow`, which prunes ancient tipsets and
  childless events and also caries the birth round for newly created 
  events. See [hashgraph.md](hashgraph.md).
- **Health duration from health monitor** —
  `healthStatusInputWire` calls
  `DefaultEventCreationManager#reportUnhealthyDuration`, which feeds the
  `PlatformHealthRule`
  (`consensus-event-creator-impl/.../rules/PlatformHealthRule.java`).
  See [Backpressure interaction](#backpressure-interaction).
- **Platform status, sync progress, quiescence** — `platformStatusInputWire`,
  `syncProgressInputWire`, and `quiescenceCommandInputWire` feed the
  `PlatformStatusRule`, `SyncLagRule`, and `QuiescenceRule` respectively.
- **Transactions from execution** — `EventTransactionSupplier` is a
  functional interface
  (`consensus-model/.../transaction/EventTransactionSupplier.java`)
  whose single method is `getTransactionsForEvent()`. It is invoked
  synchronously inside `TipsetEventCreator#assembleEventObject` when a
  new event is being built.

**Outputs**

- **Self-events** — `createdEventOutputWire` carries each new
  `PlatformEvent` returned by
  `TipsetEventCreator#maybeCreateEvent`. The event is hashed and signed
  at `TipsetEventCreator#signEvent` before being returned. The wiring
  framework forwards the event to event intake, inline PCES and gossip;
  the event creator itself does not call those subsystems.

## Algorithm

The detailed definitions and worked examples live in
[../../../core/tipset-algorithm.md](../../../core/tipset-algorithm.md);
this section summarises the shape and anchors each concept to its
computation site under
`consensus-event-creator-impl/.../tipset/`.

### Tipset

A tipset is an array of event sequence numbers indexed by node position
in the roster; entry `i` holds the highest sequence number known for
node `i` among the ancestors that contributed to the tipset. The tipset
of an event is the per-node maximum over its parents' tipsets, with the
event's own creator entry advanced to the event's sequence number.

The data structure is `Tipset` (`tipset/Tipset.java`). Two operations
matter most: `Tipset#merge(List<Tipset>)` (lines 63–80) takes the
per-node maximum across a list of parent tipsets, and `Tipset#advance(NodeId, long)`
(lines 113–117) raises a single entry to the supplied sequence number.

### Advancement score

Given two tipsets `X` and `Y`, the advancement score counts the entries
of `Y` that are strictly greater than the corresponding entry of `X`.
The weighted form sums each advancing node's consensus weight; the
*partial* weighted form, computed from a particular self-id, ignores
the self entry.

The score is a `TipsetAdvancementWeight` record
(`tipset/TipsetAdvancementWeight.java`) with two fields:
`advancementWeight` for non-zero-weight nodes (which contribute to
quorum) and `zeroWeightAdvancementCount` for zero-weight nodes (which
must still be allowed to advance, but separately). The score itself is
computed by `Tipset#getTipAdvancementWeight(NodeId selfId, Tipset that)`
(lines 140–163), which iterates the roster and skips the self index.

### Snapshot updates

The snapshot is the moving baseline against which improvement is
measured. `TipsetWeightCalculator` holds the current `snapshot` and a
bounded `snapshotHistory` (default `tipsetSnapshotHistorySize = 10`).
Whenever `TipsetWeightCalculator#addEventAndGetAdvancementWeight`
(lines 165–198) is called for a new self-event, it computes the
partial-weighted score of that event's tipset against the current
snapshot and, when the score plus this node's own weight reaches
super-majority of total weight, replaces the snapshot with the new
event's tipset:

```java
if (SUPER_MAJORITY.isSatisfiedBy(advancementWeight.advancementWeight() + selfWeight, totalWeight)) {
    snapshot = eventTipset;
    snapshotHistory.add(snapshot);
    if (snapshotHistory.size() > maxSnapshotHistorySize) {
        snapshotHistory.remove();
    }
    previousAdvancementWeight = ZERO_ADVANCEMENT_WEIGHT;
}
```

Per-event tipsets for peer events are constructed in
`TipsetTracker#addPeerEvent` (lines 125–138); the call
`new Tipset(roster).merge(parentTipsets).advance(event.getCreatorId(), event.getSequenceNumber())`
shows that tipset entries are event sequence numbers.

The >2/3 threshold mirrors the super-majority that hashgraph consensus
itself requires to strongly see another node's event, so a snapshot
stored at this point captures a slice of hashgraph that genuinely
advances consensus. This threshold also underlies the network's
automatic stop during quorum loss — see [Behavior during quorum
loss](#behavior-during-quorum-loss).

### Event-creation rule

Before the tipset algorithm runs, a chain of permission gates in
`DefaultEventCreationManager#maybeCreateEvent` (rate limit, platform
status, health, sync lag, quiescence) must allow creation; these live
outside `TipsetEventCreator` and are described in [Backpressure
interaction](#backpressure-interaction).

Once those gates pass, the tipset algorithm chooses other parents. It
considers each non-ancient childless peer event as a candidate, computes
the advancement score it would produce against the current snapshot,
and keeps only the candidates whose advancement weight is non-zero. If
no candidate qualifies and this is not a genesis event, no event is
created. Otherwise the event creator builds the new event with the
top-ranked candidate(s) up to `maxOtherParents`. This is the
snapshot-improvement-score gate from the source doc.

The gate is implemented in
`TipsetEventCreator#createEventCombinedAlgorithm` (lines 273–352): the
non-zero filter is at line 287, and the no-eligible-parent branch is at
lines 301–312 (returns `null` unless this is the genesis event). The
actual snapshot-update happens later in
`TipsetWeightCalculator#addEventAndGetAdvancementWeight` once the event
has been assembled.

To keep peers from being permanently starved by this ranking, the
top-ranked candidate can be probabilistically swapped for an event from
an ignored peer, weighted by that peer's [selfishness
score](#selfishness-score).

### Selfishness score

The selfishness score against a peer is the number of recent snapshots
(walking `snapshotHistory` back from the most recent) that elapsed
without that peer's sequence number advancing. A high score means the
node has been ignoring that peer for several snapshots in a row. To
prevent permanent starvation, the event creator probabilistically
swaps the lowest-scoring tipset parent for an event from an ignored
peer, weighted by the peer's selfishness score.

The score is computed in
`TipsetWeightCalculator#getSelfishnessScoreForNode` (lines 275–315);
the maximum across all childless peers is `getMaxSelfishnessScore`
(lines 255–261). The pity-pick selection is
`TipsetEventCreator#selectParentToReduceSelfishness` (lines 374–439),
called probabilistically with `beNiceChance = (selfishness - 1) / antiSelfishnessFactor`
(line 319, with `antiSelfishnessFactor` defaulting to `10`).

The value `10` is a heuristic. It has no derivation in the code,
comments, or commit history, and has been carried forward unchanged
since it was introduced.

### Behavior during quorum loss

When too much weight is offline, no event can reach the >2/3 threshold
from [Snapshot updates](#snapshot-updates), so the snapshot freezes.
The advancement headroom above a frozen snapshot is finite: after a
few events every candidate produces zero score improvement, and the
legality rule from [Event-creation rule](#event-creation-rule) rejects
them all — the node stops creating events without ever deciding to.
When the partition heals, gossiped events reopen advancement above the
snapshot and each node individually resumes, with no coordination
between nodes.

## Backpressure interaction

The orchestration permission chain in
`DefaultEventCreationManager#maybeCreateEvent` (lines 133–157) consults
an `AggregateEventCreationRules` chain before delegating to the tipset
algorithm. `PlatformHealthRule`
(`consensus-event-creator-impl/.../rules/PlatformHealthRule.java`)
blocks event creation whenever the duration reported via
`reportUnhealthyDuration` exceeds `maximumPermissibleUnhealthyDuration`
(default `1s`). The other rules in the chain (`MaximumRateRule`,
`PlatformStatusRule`, `SyncLagRule`, `QuiescenceRule`) gate creation on
related signals. Detail on the framework that produces these signals
lives in [health-monitor-and-backpressure.md](health-monitor-and-backpressure.md).

## Self-event persistence

`TipsetEventCreator#maybeCreateEvent` returns a signed `PlatformEvent`
on the `createdEventOutputWire`; the event creator does not itself
persist the event. The wiring framework routes the new event through
event intake and inline PCES, and only after PCES has flushed it to
disk does it reach gossip — so a self-event is never gossiped before it
is persisted, even though the persistence call site is downstream of
this module. See [restart-and-pces.md](restart-and-pces.md).

## Cross-references

- Topics: [hashgraph.md](hashgraph.md), [event-intake.md](event-intake.md),
  [health-monitor-and-backpressure.md](health-monitor-and-backpressure.md),
  [restart-and-pces.md](restart-and-pces.md),
  [reasons-not-to-gossip.md](reasons-not-to-gossip.md).
- Tunables: [../../tunables.md](../../tunables.md) — for
  `antiSelfishnessFactor`, `tipsetSnapshotHistorySize`,
  `eventIntakeThrottle`, `maximumPermissibleUnhealthyDuration`,
  `maxAllowedSyncLag`, `maxOtherParents`, `maxCreationRate`, `period`.
- Source doc: [../../../core/tipset-algorithm.md](../../../core/tipset-algorithm.md).
- Invariants: [TBD: INV-NNN once invariants.md catalog populates].
- Decisions: [TBD: ADR-NNN once decisions/ catalog populates].

## Future state

The `Consensus-Layer.md` proposal describes a clean Consensus/Execution
split in which the event creator is a discrete module exposing a
defined public API to Execution (`getTransactionsForEvent`) and to
event intake. Current code already implements that boundary in spirit:
`EventCreatorModule` is wire-driven and `EventTransactionSupplier` is
the only synchronous Execution-facing call. The proposal is marked
*not started* in the delta-map; updates land here as the formal split
progresses.
