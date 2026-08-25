---
type: architecture-topic
title: Platform status
last_reviewed: 2026-07-28
---

# Platform status

Platform status is the node's single, network-facing lifecycle state — one
value of the
[`PlatformStatus`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java)
enum (`STARTING_UP`, `REPLAYING_EVENTS`, `OBSERVING`, `CHECKING`, `ACTIVE`,
`BEHIND`, `RECONNECT_COMPLETE`, `FREEZING`, `FREEZE_COMPLETE`,
`CATASTROPHIC_FAILURE`). It answers one question the rest of the system keeps
asking: *what is this node allowed to do right now* — gossip, create events,
accept application transactions, or none of these. Many components gate their
own behaviour on it, and Execution is notified of every change.

The value is not set directly. It is the output of a small **state machine**
that consumes typed *actions* (a self event reached consensus, replay finished,
the node fell behind, a state was written to disk, time elapsed) and, for each,
either stays put or moves to exactly one successor status. This topic covers
that machine — its statuses, the actions that drive it, the transitions, who
feeds it, and who reads its output.

## Responsibilities

- Owns: the status state machine
  ([`StatusStateMachine`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/internal/StatusStateMachine.java))
  and the per-status transition logic
  ([`PlatformStatusLogic`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/logic/PlatformStatusLogic.java)
  and its ten implementations); the action vocabulary
  ([`PlatformStatusAction`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/actions/PlatformStatusAction.java)
  and subtypes); translating domain events into actions and fanning the
  resulting status out to consumers
  ([`DefaultPlatformMonitor`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/internal/DefaultPlatformMonitor.java),
  wired by
  [`StatusMonitorModule`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/StatusMonitorModule.java)).
- Owns: the boundary notification to the application —
  [`PlatformStatusChangeNotification`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/notification/PlatformStatusChangeNotification.java)
  and the direct `ExecutionLayer.newPlatformStatus` feed (see
  [Consumers](#consumers)).
- Does not own: the **decisions** that produce actions. Whether a self event
  reached consensus, whether the node fell behind, whether a freeze round was
  crossed — each is decided by another topic (hashgraph, reconnect, freeze) and
  arrives here already reduced to an action. This machine only sequences them.
- Does not own: what each consumer *does* with a status. The event-creation
  gate lives in [`event-creator.md`](event-creator.md); how a quiescing node
  holds `ACTIVE` lives in [`quiescence.md`](quiescence.md). This topic states
  which status is current and why; the consuming topics own the reaction.

## The statuses

Each status is a standing answer to "what may the node do." The
[`PlatformStatus`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java)
javadoc is canonical for the per-value semantics; the table below is an
orientation summary, not a second source of truth. The authoritative list of
which statuses permit gossip is the sync allow-list owned by
[`reasons-not-to-gossip.md`](reasons-not-to-gossip.md) (the "Gossips" column
mirrors it).

|         Status         | Gossips | Creates events | Accepts app txns |                                                                Role                                                                 |
|------------------------|:-------:|:--------------:|:----------------:|-------------------------------------------------------------------------------------------------------------------------------------|
| `STARTING_UP`          |    —    |       —        |        —         | Initial state before replay.                                                                                                        |
| `REPLAYING_EVENTS`     |    —    |       —        |        —         | Replaying the preconsensus event stream (see [restart-and-pces.md](restart-and-pces.md)).                                           |
| `OBSERVING`            |    ✓    |       —        |        —         | Listening to gossip to relearn self events before creating any.                                                                     |
| `CHECKING`             |    ✓    |       ✓        |        —         | Creating events but withholding app transactions until self events reach consensus.                                                 |
| `ACTIVE`               |    ✓    |       ✓        |        ✓         | Fully participating (subject to other gates — health, sync lag, quiescence).                                                        |
| `BEHIND`               |    —    |       —        |        —         | Fallen behind; not gossiping; awaiting reconnect (see [reconnect.md](reconnect.md)).                                                |
| `RECONNECT_COMPLETE`   |    ✓    |       —        |        —         | Reconnected; gossiping but not creating events until the received state is on disk.                                                 |
| `FREEZING`             |    ✓    |       ✓        |        —         | Freeze boundary crossed; event creation continues until Execution releases it (see [freeze-and-upgrade.md](freeze-and-upgrade.md)). |
| `FREEZE_COMPLETE`      |    ✓    |       —        |        —         | Freeze done; still gossiping so laggards collect freeze-state signatures.                                                           |
| `CATASTROPHIC_FAILURE` |    —    |       —        |        —         | Unrecoverable failure; node idle.                                                                                                   |

Three statuses exist for reasons that are not obvious from the summary, and are
recorded as decisions rather than restated here:

- `OBSERVING` — the node waits, gossiping without creating events, so it can
  relearn self events it gossiped before a crash and avoid branching. Kept
  deliberately even though PCES now durably persists those events; see
  [ADR-004](../../decisions/ADR-004-retain-observing-status-for-self-event-recovery.md).
- `RECONNECT_COMPLETE` — a reconnected node does not create events until the
  received state is written to disk, so it always has a valid PCES replay
  starting point; see
  [ADR-007](../../decisions/ADR-007-save-reconnect-state-before-resuming-event-creation.md).
- `CHECKING` — the node creates events but withholds app transactions until one
  of its own events reaches consensus, gaining confidence that accepted
  transactions can actually be ordered.

## The state machine

[`StatusStateMachine`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/internal/StatusStateMachine.java)
holds one field of interest: the
[`PlatformStatusLogic`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/logic/PlatformStatusLogic.java)
object for the current status. There is one logic implementation per status
(`StartingUpStatusLogic`, `ObservingStatusLogic`, `ActiveStatusLogic`, …), and
the current status *is* the type of the current logic object. `submitStatusAction`
dispatches the action via `PlatformStatusLogic#process`, which switches to the
matching per-action `on…` hook (e.g. `onTimeElapsed`) on that object, returning:

1. **a new logic object** — the status transitions to the new object's status;
2. **`this`** — the action is valid but causes no transition;
3. **a thrown `IllegalPlatformStatusException`** — the action is not legal in
   the current status. The machine logs it and stays put
   (`getNewLogic` catches it and returns `null`).

Only on a genuine transition does the machine log the
`Platform spent … in X. Now in Y` line, update its metrics
([`PlatformStatusMetrics`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/internal/PlatformStatusMetrics.java)),
and emit the new status. This per-status-object design keeps every status's
legal actions and successors in one small class instead of one large switch.

### Actions and their sources

Actions are the machine's only input. Each is produced by the topic that owns
the underlying event; that topic is handed the
[`StatusMonitorModule`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/StatusMonitorModule.java)
and puts the action on its `platformStatusActionInputWire()`. Some are instead
translated by
[`DefaultPlatformMonitor`](../../../../consensus-status-monitor/src/main/java/org/hiero/consensus/status/monitor/internal/DefaultPlatformMonitor.java)
from a richer domain input (a `ConsensusRound`, an `IssNotification`, a
heartbeat) into the corresponding action.

|              Action               |                                          Produced by (`<module>/.../<File>.java`)                                          |                             Meaning                              |
|-----------------------------------|----------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `StartedReplayingEventsAction`    | `consensus-pces-impl/.../PcesCoordinator.java`                                                                             | PCES replay began.                                               |
| `DoneReplayingEventsAction`       | `consensus-pces-impl/.../PcesCoordinator.java`                                                                             | PCES replay finished.                                            |
| `SelfEventReachedConsensusAction` | `consensus-status-monitor/.../status/monitor/internal/DefaultPlatformMonitor.java` (from a `ConsensusRound`)               | One of this node's own events reached consensus.                 |
| `FallenBehindAction`              | `consensus-reconnect-impl/.../ReconnectController.java`                                                                    | The node determined it is behind the network.                    |
| `ReconnectCompleteAction`         | `consensus-reconnect-impl/.../ReconnectController.java`                                                                    | A reconnect finished.                                            |
| `StateWrittenToDiskAction`        | `consensus-status-monitor/.../status/monitor/internal/DefaultPlatformMonitor.java` (from a state-saving result)            | A signed state was written to disk (carries the freeze flag).    |
| `FreezePeriodEnteredAction`       | `consensus-transaction-handling/.../DefaultTransactionHandler.java`                                                        | A round crossed the freeze boundary (carries the freeze round).  |
| `CatastrophicFailureAction`       | `consensus-status-monitor/.../status/monitor/internal/DefaultPlatformMonitor.java` (from a catastrophic `IssNotification`) | An unrecoverable failure occurred.                               |
| `TimeElapsedAction`               | `consensus-status-monitor/.../status/monitor/internal/DefaultPlatformMonitor.java` (heartbeat)                             | Wall-clock tick; carries the current instant and quiescing flag. |

The heartbeat is periodic — its interval is
[`platformStatus.statusStateMachineHeartbeatPeriod`](../../tunables.md)
(TUN-021) — and drives the two purely time-based transitions: leaving
`OBSERVING` after
[`platformStatus.observingStatusDelay`](../../tunables.md) (TUN-019) and the
`ACTIVE` → `CHECKING` drop governed by
[`platformStatus.activeStatusDelay`](../../tunables.md) (TUN-020).

### Transitions

Under normal startup a node walks
`STARTING_UP → REPLAYING_EVENTS → OBSERVING → CHECKING → ACTIVE`; a node that
falls behind runs `… → BEHIND → RECONNECT_COMPLETE → CHECKING → ACTIVE`; a
freeze ends at `FREEZE_COMPLETE`. The transition-defining rules live in the
`*StatusLogic` classes; the exact edges each status allows are best read there.
Two structural facts hold across the whole machine:

- **`FREEZE_COMPLETE` and `CATASTROPHIC_FAILURE` are the only terminal
  statuses** — their logic classes return `this` for every action. Every other
  status has at least one exit.
- **`CATASTROPHIC_FAILURE` is reachable from every non-terminal status** — each
  of those logic classes handles `CatastrophicFailureAction` by transitioning to
  it. `FREEZE_COMPLETE`, being terminal, is the one status from which it is not.

A full transition diagram is maintained (outside this KB) at
[`../../../core/platform-status-transitions.svg`](../../../core/platform-status-transitions.svg).

## Consumers

The state machine's output — a new `PlatformStatus` on each change, emitted on
the module's `platformStatusOutputWire()` — is soldered by
[`ConsensusLayerWiring`](../../../../swirlds-platform-core/src/main/java/org/hiero/consensus/ConsensusLayerWiring.java)`#wirePlatformMonitorOutputs`
to every component that gates on it:

- **Event creator** — the
  [`PlatformStatusRule`](../../../../consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java)
  permits event creation only in `ACTIVE` or `CHECKING`, and in `FREEZING`
  only while Execution still needs events created. That release condition is
  owned by Execution and read through
  [`SignatureTransactionCheck`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/transaction/SignatureTransactionCheck.java)`#hasBufferedSignatureTransactions`
  — the name understates it: the predicate stays true while Execution has any
  signing work left, not only while the transaction pool holds a signature
  transaction. This is the single largest consumer; see
  [event-creator.md](event-creator.md).
- **Hashgraph**, **gossip**, and the **state module** each receive the status
  on their `platformStatusInputWire`. Gossip uses it to decide whether to sync;
  the allow-list and its rationale live in
  [`reasons-not-to-gossip.md`](reasons-not-to-gossip.md).
- **Execution** — delivered directly via `ExecutionLayer.newPlatformStatus`
  (see [`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md)) and
  [`PlatformStatusChangeNotification`](../../../../consensus-model/src/main/java/org/hiero/consensus/model/notification/PlatformStatusChangeNotification.java)
  through the notification engine, consumed via `PlatformStatusChangeListener`.

## Rationale

The status enum is deliberately a **single, flat, heavily-relied-upon flag**:
much of the system keys off `ACTIVE`, and any new status multiplies the
transitions every consumer must reason about. That pressure shapes two
decisions recorded elsewhere:

- Quiescence carries its signal *alongside* the status rather than adding a
  status, keeping a quiescing node `ACTIVE`; see
  [quiescence.md](quiescence.md#rationale).
- `OBSERVING` was kept, not removed, even after PCES made it redundant for
  ordinary crashes, because it remains the only recovery path after disk loss;
  see [ADR-004](../../decisions/ADR-004-retain-observing-status-for-self-event-recovery.md).

## Cross-references

Topics:

- [`event-creator.md`](event-creator.md) — `PlatformStatusRule`, the primary
  gate on status.
- [`quiescence.md`](quiescence.md) — how a quiescing node holds `ACTIVE`, and
  why no quiescence status was added.
- [`freeze-and-upgrade.md`](freeze-and-upgrade.md) — the `FREEZING` →
  `FREEZE_COMPLETE` path and the Execution-controlled gate that keeps event
  creation alive during `FREEZING`.
- [`reconnect.md`](reconnect.md) — the `BEHIND` → `RECONNECT_COMPLETE` path.
- [`restart-and-pces.md`](restart-and-pces.md) — `REPLAYING_EVENTS` and the
  self-event recovery `OBSERVING` guards against.
- [`iss-detection.md`](iss-detection.md) — a catastrophic ISS is the source of
  the `CatastrophicFailureAction`.
- [`reasons-not-to-gossip.md`](reasons-not-to-gossip.md) — the authoritative
  allow-list of which statuses permit gossip.

Interface:

- [`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md)
  — `ExecutionLayer.newPlatformStatus` and `PlatformStatusChangeNotification`
  are the boundary operations that carry status to Execution.

Decisions:

- [ADR-004](../../decisions/ADR-004-retain-observing-status-for-self-event-recovery.md)
  — retain `OBSERVING` for self-event recovery after disk loss.
- [ADR-007](../../decisions/ADR-007-save-reconnect-state-before-resuming-event-creation.md)
  — save the reconnect state before resuming event creation (the
  `RECONNECT_COMPLETE` gate).

Tunables:

- [`../../tunables.md`](../../tunables.md) — TUN-019 (`observingStatusDelay`),
  TUN-020 (`activeStatusDelay`), TUN-021 (`statusStateMachineHeartbeatPeriod`).

Symptoms:

- [`../../symptoms.md`](../../symptoms.md) — SYM-001 (`ACTIVE` → `CHECKING`), a
  status transition with many possible causes.
