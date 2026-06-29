---
type: architecture-topic
title: Freeze and upgrade
last_reviewed: 2026-06-08
---

# Freeze and upgrade

A freeze is a coordinated, network-wide halt: every node stops applying
new rounds at the same consensus point, writes a marked signed state to
disk, and remains halted until restarted. Gossip continues until the
node shuts down; what stops is event creation, which is permitted after
the freeze round only long enough for each node to emit the signature
transactions on the freeze state. The freeze state is the handoff
between the running process and a clean restart on (potentially) a new
software version. In current code the freeze procedure is not owned by
a single component; the trigger originates on the Execution side, the
round-level cutoff lives in `consensus-hashgraph-impl`, the per-rule
guards live across `consensus-event-creator-impl` and
`consensus-gossip-impl`, and the state-save and status transitions live
in `swirlds-platform-core`. This file documents the current behaviour
and points each rule at the file that enforces it.

## Responsibilities

This topic covers how a freeze signal propagates through the consensus
layer, the per-topic behaviour changes that occur during a freeze, the
freeze-state save trigger, and the upgrade startup path that follows.

- Owns: how `freezeTime` becomes a stop signal for the consensus
  pipeline, the `FREEZE_STATE` save trigger, the `FREEZING` →
  `FREEZE_COMPLETE` transition, and the documented points where the
  upgrade restart path picks back up.
- Does not own: the on-disk layout of a saved state (see
  [`signed-state-management.md`](signed-state-management.md)); the PCES
  replay procedure (see [`restart-and-pces.md`](restart-and-pces.md));
  the Execution-side
  transaction handler that produces a freeze transaction; the operator
  tooling that orchestrates the actual binary swap and JVM restart.

## Trigger

The only input to the consensus layer is the value of `freezeTime` on
the platform state, written by the Execution side via
[`WritablePlatformStateStore`](../../../../consensus-platformstate/src/main/java/org/hiero/consensus/platformstate/WritablePlatformStateStore.java)`#setFreezeTime`.
How Execution decides to set or clear that field is out of scope for
this document.

The consensus side reads those fields back out through
[`PlatformStateAccessor`](../../../../consensus-platformstate/src/main/java/org/hiero/consensus/platformstate/PlatformStateAccessor.java)
(`#getFreezeTime`, `#getLastFrozenTime`, `#getLatestFreezeRound`). The
canonical "are we in a freeze period" predicate is
[`PlatformStateUtils`](../../../../consensus-platformstate/src/main/java/org/hiero/consensus/platformstate/PlatformStateUtils.java)`#isInFreezePeriod(Instant, State)`,
which returns true when `freezeTime` is set, the consensus time has
reached or passed it, and `lastFrozenTime` has not yet caught up to it.
That predicate is exposed to the consensus modules as the
[`FreezePeriodChecker`](../../../../consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/FreezePeriodChecker.java)
interface; the live binding is built as a lambda in
[`PlatformBuilder`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java)
that closes over the mutable platform state.

`lastFrozenTime` is written by
[`DefaultTransactionHandler`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/eventhandling/DefaultTransactionHandler.java)`#createSignedState`
(via `PlatformStateUtils#updateLastFrozenTime`) before `copyMutableState`
is called, so the `lastFrozenTime = freezeTime` write is committed to
disk only as part of the freeze state itself. Execution always writes
a new `freezeTime` strictly later than the recorded `lastFrozenTime`,
which re-arms the predicate; the predicate disarms only when
`lastFrozenTime = freezeTime` is committed atomically with the
freeze-state save. This ordering is what makes the trigger crash-safe:
a crash before the freeze state reaches disk leaves the on-disk
`lastFrozenTime` strictly before `freezeTime`, so the freeze fires again on
restart once consensus re-crosses the timestamp.

## Freeze-time behaviour

### Event creation

Self-event creation is gated by platform status. In
[`PlatformStatusRule`](../../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java)`#isEventCreationPermitted`,
status `FREEZING` permits creation only when the signature-transaction
buffer is non-empty; otherwise creation is blocked. Creation is
otherwise permitted only in `ACTIVE` and `CHECKING`
([`PlatformStatus`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java)).
Detail in [`event-creator.md`](event-creator.md).

The buffer-drain guard lets the node emit as many events as it needs to
flush its required signature transactions on the freeze state, then
stops. Creation cannot continue indefinitely after the freeze: once the
freeze round is reached, consensus does not advance, so the non-ancient
window does not advance and no events are purged from memory.
Unbounded event creation in that state would exhaust memory; halting
at signature-buffer drain is what keeps the freezing node bounded.

### Gossip

Gossip continues throughout the freeze and into `FREEZE_COMPLETE`, with
no behavioural difference between the two statuses. The permit set
[`SyncStatusChecker`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/sync/protocol/SyncStatusChecker.java)`.STATUSES_THAT_PERMIT_SYNC`
explicitly contains both, and
[`RpcPeerProtocol`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcPeerProtocol.java)`#shouldSwitchToRpc`
gates only on that set — there is no separate freeze branch. Gossip
continues for three reasons: to send this node's event carrying its
signature transactions on the freeze state and freeze block (see
ADR-002
for detail on the block signatures), to collect those signatures from
peers, and to relay them to any peers that still need them. Detail in
[`gossip.md`](gossip.md) and
[`reasons-not-to-gossip.md`](reasons-not-to-gossip.md).

### Hashgraph

The hashgraph engine has two freeze-time behaviours, both in
[`DefaultConsensusEngine`](../../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java)
and the
[`FreezeRoundController`](../../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/FreezeRoundController.java)
it owns.

`FreezeRoundController#filterAndModify` runs on every batch of rounds
the consensus algorithm produces, and does two things. First, if any
round in the batch falls in the freeze period, every round after the
first such round is dropped — a single added event can cause several
rounds to reach consensus at once, but the freeze round must be the
last round handled before the upgrade restart. Second, the freeze
round's `EventWindow` is rewritten so that `newEventBirthRound` equals
the freeze round number itself. Event creation reads
`newEventBirthRound` from the latest `EventWindow` to assign birth
rounds to new events, so under the rewrite any new events an honest
node creates have birth round equal to the freeze round. This is
required because birth round defines which roster validates an event:
events with birth round at or before the freeze round must be
validated against the roster that signed them — the one holding the
membership, keys, and weights valid at creation time — while events
with birth round after the freeze round are validated against the new
roster the network adopts at `freezeRound + 1`. The rewrite is what
keeps pre-upgrade events validating against the pre-upgrade roster,
even when the new roster drops some of the nodes that signed them, and
it gives the upgrade a clean boundary if the event format itself
changes. See [`birth-round.md`](../../concepts/birth-round.md) for detail
on birth round values.

Once `FreezeRoundController#isFrozen` is true, `addEvent` stops feeding
events into the consensus algorithm. Events still pass through the
future event buffer (FEB), and any event that is not a future event is
returned to the caller as a preconsensus event so the application can
prehandle it. This is the path freeze-block signature transactions take
to reach the application after the freeze round (see
ADR-002).

The FEB only buffers events whose birth round is greater than the
pending round. Once the freeze round reaches consensus the pending
round is `freezeRound + 1`, and because consensus does not advance past
the freeze round it stays there — so the FEB only buffers events with
birth round ≥ `freezeRound + 2`. Honest nodes' post-freeze events
have birth round equal to the freeze round (per the `EventWindow`
rewrite above), so honest events always pass through the FEB
immediately rather than being buffered.

### State save

A signed state is marked for disk via
[`DefaultSavedStateController`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/components/DefaultSavedStateController.java)`#shouldSaveToDisk`.
The first branch is `if (signedState.isFreezeState()) return FREEZE_STATE`,
which short-circuits the periodic-snapshot logic and uses the
[`StateToDiskReason`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/snapshot/StateToDiskReason.java)`.FREEZE_STATE`
marker. The actual write happens in
[`DefaultStateSnapshotManager`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/DefaultStateSnapshotManager.java)`#saveStateTask`,
which runs downstream of the handle thread (not on it); the resulting
`StateSavingResult` carries the freeze flag further down the pipeline.

## Freeze procedure

The steps below are anchored individually; there is no single
orchestrator class.

1. Execution sets `freezeTime` on the platform state (see
   [Trigger](#trigger)).
2. The first consensus round whose timestamp falls in the freeze
   period is detected in
   [`DefaultTransactionHandler`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/eventhandling/DefaultTransactionHandler.java)`#handleConsensusRound`.
   The handler submits a `FreezePeriodEnteredAction(round)` and sets a
   `freezeRoundReceived` flag; subsequent rounds are then ignored by
   the same handler.
3. In parallel, the hashgraph engine keeps the first freeze round,
   discards any later rounds in the same batch, rewrites the freeze
   round's birth round, and stops feeding further events into the
   consensus algorithm — no additional rounds will be produced (see
   [Hashgraph](#hashgraph)).
4. The signed state for the freeze round is marked as a freeze state
   and written to disk (see [State save](#state-save)).
5. The status state machine transitions `FREEZING` → `FREEZE_COMPLETE`
   when the freeze state has been written. The transition logic lives
   in
   [`FreezingStatusLogic`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/FreezingStatusLogic.java)
   and the terminal status in
   [`FreezeCompleteStatusLogic`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/FreezeCompleteStatusLogic.java).
6. Gossip continues in `FREEZE_COMPLETE` so that signatures on the
   freeze state can be distributed to laggards; event creation does
   not resume because neither `ACTIVE` nor `CHECKING` is reached again
   (see [Gossip](#gossip) and [Event creation](#event-creation)).

Steps 4 and 5 are sequenced by the handle thread itself: the same
thread that applies the freeze round to the state creates the
`SignedState` for it and hands the object down the pipeline. The freeze
state cannot be saved until it has been created and passed out of
`DefaultTransactionHandler`, and the transition to `FREEZE_COMPLETE`
is the response to the freeze state being written to disk. The
crash-safety properties of the `lastFrozenTime` write across this
sequence are covered in [Trigger](#trigger).

After `FREEZE_COMPLETE`, JVM exit for the consensus node is triggered
by the **Node Management Tool (NMT)**, an external operator tool that
continuously monitors for a marker file written by the execution
layer. The execution layer writes the marker only after the consensus
layer reports `FREEZE_COMPLETE` (or, equivalently, after it receives
the notification that the freeze state has been written to disk). Any
further conditions the execution layer waits on before writing the
marker are out of scope for this document.

## Upgrade startup

The consensus-layer boot path does not branch on whether the loaded
state is a freeze state — startup from a freeze state follows exactly
the same code path as any other startup, via
[`StartupStateUtils`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/signed/StartupStateUtils.java)`#loadStateFile`.
The `SavedStateMetadata#freezeState` flag is preserved at save time,
but the consensus layer's startup logic does not consult it.

What the freeze procedure guarantees is not a special boot path but a
coordinated starting point: every node restarts from exactly the same
state, which is what makes it safe to bring up a new software version
that may interpret transactions or state differently than the prior
one. This property is intended to be captured by a future invariant.

See [`restart-and-pces.md`](restart-and-pces.md) for the PCES side of
the restart sequence.

## Cross-references

Topics:

- [`signed-state-management.md`](signed-state-management.md)
- [`restart-and-pces.md`](restart-and-pces.md)
- [`event-creator.md`](event-creator.md)
- [`gossip.md`](gossip.md)
- [`reasons-not-to-gossip.md`](reasons-not-to-gossip.md)
- [`reconnect.md`](reconnect.md)

Interface:

- [`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md)

Outdated source (do not cite as authority; included only because
operators may search log archives for its vocabulary):

- [`platform-sdk/docs/core/freeze/freeze.md`](../../../core/freeze/freeze.md)

Pending catalogs:

- Invariants — INV-008 — consensus, once reached, is permanent.
- Decisions:
  - ADR-002 — blocking
    `onSealConsensusRound` to hand off freeze-block signatures from Execution to consensus.
  - ADR-006 — why upgrades use a
    coordinated network-wide freeze rather than rolling upgrades.
- Scenarios — [TBD: SCN-NNN — freeze-time anomalies (failed save,
  missed freeze round, post-freeze branching, re-freeze on the same
  trigger) are likely scenario seeds].

## Future state

> **Future state.** The
> [`Consensus-Layer.md`](../../../proposals/consensus-layer/Consensus-Layer.md)
> proposal places freeze and lifecycle entirely on the Execution side.
> In current code the responsibility is distributed across
> `consensus-platformstate` (the freeze fields on platform state and
> the `isInFreezePeriod` predicate), `consensus-hashgraph-impl` (the
> round-level cutoff in `FreezeRoundController`), `consensus-event-creator-impl`
> (the per-status guard in `PlatformStatusRule`),
> `consensus-gossip-impl` (status-driven sync gating), and
> `swirlds-platform-core` (the trigger handler at round handling, the
> save-controller, the snapshot manager, and the status state machine).
> The anticipated move is for the freeze trigger and procedure to
> consolidate under Execution; the consensus side would receive a
> simpler "stop after round N" signal rather than reading and gating
> on `freezeTime` itself.

## Historical note

> **Historical note.** A prior document at
> [`platform-sdk/docs/core/freeze/freeze.md`](../../../core/freeze/freeze.md)
> describes a pre-Hiero version of the freeze procedure that uses
> vocabulary (`DualState`, `SwirldState`, `transThrottle`,
> `handleTransaction()`) that no longer exists in current code. It is
> referenced here only because diagnosticians may search log archives
> and historical tickets for that terminology; it is not authoritative
> for current behaviour.
