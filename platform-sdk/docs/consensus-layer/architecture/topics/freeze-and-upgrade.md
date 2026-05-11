---
title: Freeze and upgrade
kind: architecture-topic
last_reviewed: TBD
---

# Freeze and upgrade

A freeze is a coordinated, network-wide pause: every node stops applying
new rounds at the same consensus point, writes a marked signed state to
disk, and continues gossiping only long enough to distribute signatures
on that state. The freeze state is the handoff between the running
process and a clean restart on (potentially) a new software version. In
current code the freeze procedure is not owned by a single component;
the trigger originates on the Execution side, the round-level cutoff
lives in `consensus-hashgraph-impl`, the per-rule guards live across
`consensus-event-creator-impl` and `consensus-gossip-impl`, and the
state-save and status transitions live in `swirlds-platform-core`. This
file documents the current shape and points each behavioural rule at
the file that enforces it.

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
  reconnect (see [`reconnect.md`](reconnect.md)); the Execution-side
  transaction handler that produces a freeze transaction; the operator
  tooling that orchestrates the actual binary swap and JVM restart.

## Trigger

The freeze signal originates on the Execution side. A freeze
transaction (`FREEZE_ONLY`, `FREEZE_UPGRADE`, `TELEMETRY_UPGRADE`,
`PREPARE_UPGRADE`, or `FREEZE_ABORT`) is dispatched to
[`FreezeHandler`](../../../../../hedera-node/hedera-network-admin-service-impl/src/main/java/com/hedera/node/app/service/networkadmin/impl/handlers/FreezeHandler.java)`#handle`,
which delegates to
[`FreezeUpgradeActions`](../../../../../hedera-node/hedera-network-admin-service-impl/src/main/java/com/hedera/node/app/service/networkadmin/impl/handlers/FreezeUpgradeActions.java)`#scheduleFreezeOnlyAt`
or `#scheduleFreezeUpgradeAt`. Both write a `freezeTime` timestamp into
state via `WritableFreezeStore#freezeTime`. The same transaction stream
populates the platform state's freeze fields through
[`WritablePlatformStateStore`](../../../../consensus-platformstate/src/main/java/org/hiero/consensus/platformstate/WritablePlatformStateStore.java)`#setFreezeTime`
and `#setLastFrozenTime`.

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

[TBD: question for engineer — `PlatformStateUtils#isInFreezePeriod`
defines the freeze window in terms of `freezeTime` and `lastFrozenTime`.
Who writes `lastFrozenTime`, when relative to the freeze-state save,
and what is the intended contract — "exactly one freeze per `freezeTime`
value", or "first freeze at-or-after `freezeTime` consumes the
trigger, subsequent advancement of `freezeTime` re-arms"?]

[TBD: question for engineer — `FreezeUpgradeActions` distinguishes
freeze-only, freeze-upgrade, telemetry-upgrade, prepare-upgrade, and
freeze-abort. From the consensus-layer point of view, do all five
produce the same `freezeTime` write, or are there observable
differences (e.g., what gets staged on disk before the freeze) that the
consensus layer needs to be aware of?]

## Freeze-time behaviour

### Event creation

Self-event creation is gated by platform status. In
[`PlatformStatusRule`](../../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java)`#isEventCreationPermitted`,
status `FREEZING` permits creation only when the signature-transaction
buffer is non-empty; otherwise creation is blocked. Creation is
otherwise permitted only in `ACTIVE` and `CHECKING`. The intent
documented on `PlatformStatus.FREEZING`
([`PlatformStatus`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java))
is that a node will not produce events after one carrying its
"self signature for the freeze state". Detail in
[`event-creator.md`](event-creator.md).

[TBD: question for engineer — `PlatformStatusRule` permits creation in
`FREEZING` whenever `signatureTransactionCheck.hasBufferedSignatureTransactions()`
returns true. Is this rule "no new self-events after the freeze round,
except a single signing event" or "as many signing events as the
buffer holds, but no new application events"? What symptom would appear
if this guard misregistered the buffer as non-empty after the freeze
state was already signed?]

### Gossip

Gossip continues throughout the freeze. The permit set
[`SyncStatusChecker`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/sync/protocol/SyncStatusChecker.java)`.STATUSES_THAT_PERMIT_SYNC`
explicitly contains both `FREEZING` and `FREEZE_COMPLETE`, and
[`RpcPeerProtocol`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcPeerProtocol.java)`#shouldSwitchToRpc`
gates only on that set — there is no separate freeze branch. Continuing
to gossip in `FREEZE_COMPLETE` is intentional, so signatures on the
freeze state can reach laggards. Detail in
[`gossip.md`](gossip.md) and
[`reasons-not-to-gossip.md`](reasons-not-to-gossip.md).

[TBD: question for engineer — `STATUSES_THAT_PERMIT_SYNC` admits both
`FREEZING` and `FREEZE_COMPLETE`. Is there any gossip behavioural
difference between the two (e.g., what events are sent, peer
scoring, hold-down on falling-behind detection)? If not, what
distinguishes the two statuses operationally?]

### State save

A signed state is marked for disk via
[`DefaultSavedStateController`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/components/DefaultSavedStateController.java)`#shouldSaveToDisk`.
The first branch is `if (signedState.isFreezeState()) return FREEZE_STATE`,
which short-circuits the periodic-snapshot logic and uses the
[`StateToDiskReason`](../../../../consensus-state/src/main/java/org/hiero/consensus/state/snapshot/StateToDiskReason.java)`.FREEZE_STATE`
marker. The actual write happens in
[`DefaultStateSnapshotManager`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/DefaultStateSnapshotManager.java)`#saveStateTask`,
synchronously, and the resulting `StateSavingResult` carries the freeze
flag downstream. The persisted
[`SavedStateMetadata`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SavedStateMetadata.java)
includes a `freezeState` field so that a freeze save can be recognized
on the next boot. Detail in
[`signed-state-management.md`](signed-state-management.md).

[TBD: question for engineer — `DefaultSavedStateController#shouldSaveToDisk`
relies on `signedState.isFreezeState()`. Where is that flag set on a
`SignedState` — is it set inside `DefaultTransactionHandler#createSignedState`
based on `freezeRoundReceived`, and is the flag durable across state
reservations and copy-on-write?]

### Health monitor / backpressure

No freeze-specific behaviour on the health-monitor / backpressure
surface is anchored in the current reading. See
[`health-monitor-and-backpressure.md`](health-monitor-and-backpressure.md).

[TBD: question for engineer — Is there any freeze-aware change in
backpressure behaviour, or does the freeze halt naturally drain
downstream queues without any topic-specific gating?]

### PCES

PCES write behaviour during freeze is not anchored in the current
reading. See [`restart-and-pces.md`](restart-and-pces.md).

[TBD: question for engineer — Does PCES write behaviour change at
freeze (e.g., the last segment is rolled at the freeze round; no
further events are written), and does the ordering between PCES roll
and freeze-state save matter for upgrade-startup replay correctness?]

## Freeze procedure

The steps below are anchored individually; there is no single
orchestrator class.

1. Execution writes `freezeTime` into the platform state via
   `WritablePlatformStateStore#setFreezeTime`, driven by a freeze
   transaction processed by `FreezeHandler` /
   `FreezeUpgradeActions` (see [Trigger](#trigger)).
2. The first consensus round whose timestamp falls in the freeze
   period is detected in
   [`DefaultTransactionHandler`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/eventhandling/DefaultTransactionHandler.java)`#handleConsensusRound`.
   The handler submits a `FreezePeriodEnteredAction(round)` and sets a
   `freezeRoundReceived` flag; subsequent rounds are then ignored by
   the same handler.
3. In parallel,
   [`FreezeRoundController`](../../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/FreezeRoundController.java)`#filterAndModify`
   keeps the first freeze round, modifies its `EventWindow` so the
   birth round equals the latest consensus round (so that pre- and
   post-upgrade events can be distinguished), discards any later
   rounds in the same batch, and flips `isFrozen = true`.
4. Once `isFrozen`,
   [`DefaultConsensusEngine`](../../../../consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java)`#addEvent`
   ignores all further events; no additional rounds will be produced.
5. The signed state for the freeze round is marked as a freeze state
   and saved synchronously via
   `DefaultSavedStateController#shouldSaveToDisk` →
   `DefaultStateSnapshotManager#saveStateTask`; metadata records
   `freezeState=true` (see [State save](#state-save)).
6. The status state machine transitions `FREEZING` → `FREEZE_COMPLETE`
   when the freeze state has been written to disk. The transition
   logic lives in
   [`FreezingStatusLogic`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/FreezingStatusLogic.java)
   and the terminal status in
   [`FreezeCompleteStatusLogic`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/logic/FreezeCompleteStatusLogic.java).
7. Gossip continues in `FREEZE_COMPLETE` so that signatures on the
   freeze state can be distributed to laggards; event creation is
   blocked because neither `ACTIVE` nor `CHECKING` is reached again
   (see [Event creation](#event-creation)).

[TBD: question for engineer — Steps 5 and 6 share an edge: the freeze
state is saved by `DefaultStateSnapshotManager#saveStateTask`, then
`StateWrittenToDiskAction(isFreezeState=true)` drives the status
transition in `FreezingStatusLogic`. What is the precise ordering
guarantee between the `lastFrozenTime` write, the freeze-state save,
and the `FREEZE_COMPLETE` transition, and what symptom appears if the
platform crashes between any two of those points?]

[TBD: question for engineer — `FreezeRoundController#modifyFreezeRound`
sets the freeze round's birth round equal to its latest consensus round
"in case some migration logic is needed". Is that migration logic a
runtime concern of the next boot, a compatibility shim only, or
something else? What concretely depends on the rewritten birth round?]

[TBD: question for engineer — After `FREEZE_COMPLETE`, what triggers
JVM exit / graceful shutdown for the upgrade? It is not visible in the
consensus-layer or platform-core code anchored above; is the shutdown
driven by node-operator orchestration, by code in the Hedera modules,
or by something within the platform that has not yet been anchored?]

## Upgrade startup

On the next boot,
[`StartupStateUtils`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/signed/StartupStateUtils.java)`#loadStateFile`
locates the latest saved state on disk and deserializes it; the
`SavedStateMetadata#freezeState` flag preserved at save time tells the
runtime whether that state was the result of a freeze. From the
consensus-layer code reading, the boot-path branch that consumes that
flag (to gate PCES replay, event creation, or reconnect) is not
anchored cleanly. See [`restart-and-pces.md`](restart-and-pces.md) for
the PCES side of the restart sequence.

[TBD: question for engineer — Where does the boot path branch on
`SavedStateMetadata#freezeState`? Specifically: (a) does PCES replay
run on a freeze-state boot or is it skipped; (b) is event creation
gated until something completes (status transition out of
`STARTING_UP` / `OBSERVING`?); (c) is reconnect available pre-replay
on a freeze-state boot?]

[TBD: question for engineer — Once the runtime has loaded a freeze
state and crossed into normal operation, the `lastFrozenTime` field on
the platform state is what prevents `PlatformStateUtils#isInFreezePeriod`
from re-firing on the same `freezeTime`. Is the post-restart write of
`lastFrozenTime` the same path as the in-freeze write, and is there a
window where a re-freeze could fire spuriously?]

## Cross-references

Topics:

- [`signed-state-management.md`](signed-state-management.md)
- [`restart-and-pces.md`](restart-and-pces.md)
- [`event-creator.md`](event-creator.md)
- [`gossip.md`](gossip.md)
- [`health-monitor-and-backpressure.md`](health-monitor-and-backpressure.md)
- [`reasons-not-to-gossip.md`](reasons-not-to-gossip.md)
- [`reconnect.md`](reconnect.md)

Interface:

- [`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md)

Outdated source (do not cite as authority; included only because
operators may search log archives for its vocabulary):

- [`platform-sdk/docs/core/freeze/freeze.md`](../../../core/freeze/freeze.md)

Pending catalogs:

- Invariants — [TBD: INV-NNN once `../invariants.md` catalog populates].
- Decisions — [TBD: ADR-NNN once `../decisions/` catalog populates].
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
> describes a pre-Hiero shape of the freeze procedure that uses
> vocabulary (`DualState`, `SwirldState`, `transThrottle`,
> `handleTransaction()`) that no longer exists in current code. It is
> referenced here only because diagnosticians may search log archives
> and historical tickets for that terminology; it is not authoritative
> for current behaviour.
