---
title: Consensus / Execution boundary
kind: architecture-interface
last_reviewed: TBD
---

# Consensus / Execution boundary

## Overview

This document is the canonical reference for the API surface between the **consensus layer**
and the **execution layer** (the application built on top of it, e.g., the Hedera services
node). It catalogues the interfaces, the direction each one is called in, and the lifecycle
in which each is exercised.

The whole boundary is anchored by a single handshake:

> The execution layer hands its boundary implementations to **`PlatformBuilder`** — chiefly an
> **`ExecutionLayer`**, a **`ConsensusStateEventHandler`**, a **`StateLifecycleManager`**, and optional
> **`ApplicationCallbacks`** — and in return receives a **`Platform`** handle. Everything else hangs off
> those.

This document covers the **execution-facing boundary only**. The internal decomposition of the
consensus layer into wired modules (`HashgraphModule`, `EventCreatorModule`, `EventIntakeModule`,
`PcesModule`, `GossipModule`) is a separate, lower seam and is not documented here.

### Directions

Each entry below names two roles, to keep "who provides the type" and "who invokes it" separate:

- **Implemented by** — the layer that provides the type (implements the interface or supplies the callback).
- **Called by** — the layer that invokes it across the boundary.

The two halves of the boundary are:

- The consensus layer implements `Platform`; the execution layer calls it.
- The execution layer implements everything else (`ExecutionLayer`, `ConsensusStateEventHandler`,
  `StateLifecycleManager`, `ApplicationCallbacks`); the consensus layer calls it. This is the larger half.

## The construction handshake: `PlatformBuilder`

- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java`
- **Role:** The construction-time seam. The execution layer assembles its boundary implementations and
  passes them to the builder, then calls `build()` to obtain a `Platform`.

`PlatformBuilder.create(...)` takes the execution layer's boundary implementations directly. Its required
arguments include:

- `SemanticVersion softwareVersion`
- `ReservedSignedState initialState` — the genesis or loaded state the execution layer supplies
- `ConsensusStateEventHandler consensusStateEventHandler`
- `RosterHistory rosterHistory`
- `StateLifecycleManager stateLifecycleManager`
- `NodeId selfId`, the `appName` / `swirldName`, the consensus-event-stream name

Optional wiring is added with fluent `with...` methods, notably:

- `withExecutionLayer(ExecutionLayer)` — registers the `ExecutionLayer` implementation
- `withStaleEventCallback(Consumer<PlatformEvent>)` — one of the `ApplicationCallbacks`
- `withConfiguration`, `withPlatformContext`, `withKeysAndCerts`, `withModel`, …

`build()` returns the `Platform`.

## Implemented by the consensus layer

### `Platform`

- **Implemented by:** consensus layer
- **Called by:** execution layer
- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/system/Platform.java`
- **Role:** The handle the execution layer receives from `PlatformBuilder.build()`. It is the execution
  layer's entry point for signing, lifecycle control, and reaching the consensus layer's subsystems.
  `Platform` is a genuine boundary type (the execution layer holds it and calls it), but its surface is
  uneven — see the breakdown below.

**Live boundary operations** (called by the execution layer in production):

- `getSelfId()` → `NodeId` — this node's id; the most-called method (e.g. `Hedera`, `BlockStreamManagerImpl`).
- `sign(byte[])` → `Signature` — sign data with the node key (backs `AppContext.Gossip.sign`).
- `quiescenceCommand(QuiescenceCommand)` — execution→consensus control; instruct the consensus layer on
  its quiescence state (`BlockStreamManagerImpl`, `BlockRecordManagerImpl`, `QuiescedHeartbeat`).
- `start()` — start the consensus layer (`ServicesMain`).

**Subsystem accessors** (the thing crossing the boundary is the returned subsystem, not the method):

- `getNotificationEngine()` → `NotificationEngine` — the gateway through which the execution layer
  registers the [notification listeners](#notification-listeners) below.
- `getContext()` → `PlatformContext` — the execution layer reaches in for `Configuration` (and metrics /
  time / file system). Used sparingly.

**Lifecycle, asymmetric:**

- `destroy()` — documented as the terminal call (the consensus layer cannot be reused afterward). No
  production execution-layer caller was found; it is exercised by the Turtle/Container simulation
  harnesses. Note the asymmetry with `start()`, which the execution layer *does* call.

**Vestigial — no production caller** (candidates for removal):

- `getRoster()` → `Roster` — only a test mock calls it; the execution layer and consensus-internal code
  read the roster from state (`signedState.getRoster()` / `reservedState.getRoster()`) instead.
- `getLatestImmutableState(reason)` → `AutoCloseableWrapper<T extends State>` — no caller anywhere except
  the `NoOpPlatform` test stub. Immutable state is reached through `StateLifecycleManager` instead (by the
  consensus layer, not the execution layer).

## Implemented by the execution layer

These are the interfaces the execution layer implements (or supplies). The consensus layer calls into them.

### `ExecutionLayer`

- **Implemented by:** execution layer
- **Called by:** consensus layer
- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/builder/ExecutionLayer.java`
- **Role:** The explicitly-named boundary interface.
  `ExecutionLayer extends EventTransactionSupplier, SignatureTransactionCheck`.
- **Methods:**
  - `getTransactionsForEvent()` → `List<TimestampedTransaction>` *(inherited from `EventTransactionSupplier`)* —
    the event creator pulls transactions from the execution layer when it creates a self-event.
  - `hasBufferedSignatureTransactions()` → `boolean` *(inherited from `SignatureTransactionCheck`)*.
  - `submitStateSignature(StateSignatureTransaction)` — the consensus layer hands a state signature to the
    execution layer for later inclusion via `getTransactionsForEvent()`. *(Marked transitional — to be
    removed once state management moves into the execution layer.)*
  - `newPlatformStatus(PlatformStatus)` — the consensus layer notifies the execution layer that its status
    changed. This is the primary status path (the execution layer uses this rather than
    `PlatformStatusChangeListener`).
  - `getTransactionLimits()` → `TransactionLimits` — the execution layer declares per-transaction /
    per-event byte ceilings the consensus layer enforces on gossiped events. Default
    `DEFAULT_TRANSACTION_LIMITS` is `(133120, 245760)`.
  - `reportUnhealthyDuration(Duration)` — the consensus layer reports how long it has been unhealthy
    (`Duration.ZERO` when it returns to healthy).

### `ConsensusStateEventHandler`

- **Implemented by:** execution layer
- **Called by:** consensus layer
- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/state/ConsensusStateEventHandler.java`
- **Role:** The execution layer's hooks into the major state lifecycle events. Implementations are expected
  to be stateless / effectively immutable and to live for the lifetime of the execution layer.
- **Methods:**
  - `onPreHandle(Event, State, Consumer<ScopedSystemTransaction<StateSignatureTransaction>>)` — called when
    an event is added to the hashgraph; the execution layer pre-handles the event's transactions.
  - `onHandleConsensusRound(Round, State, Consumer<…>)` — called when a round reaches consensus and is ready
    to be applied to the working state.
  - `onSealConsensusRound(Round, State)` → `boolean` — called after the consensus layer has made all its
    changes to the state for the round; returns whether sealing completes a block (i.e. whether the round's
    state is safe to sign).
  - `onStateInitialized(State, Platform, InitTrigger, SemanticVersion previousVersion)` — called when the
    consensus layer initializes the network state. `InitTrigger` distinguishes `GENESIS` / `RESTART` /
    `RECONNECT` / `EVENT_STREAM_RECOVERY`.
  - `onNewRecoveredState(State)` — called when event-stream recovery finishes. Invoked **only by the
    offline `EventRecoveryWorkflow` CLI tool**, not by the running consensus node.

### `StateLifecycleManager<S, D>`

- **Implemented by:** execution layer
- **Called by:** consensus layer
- **Code anchor:** `swirlds-state-api/src/main/java/com/swirlds/state/StateLifecycleManager.java`
- **Role:** Owns the state lifecycle: the mutable state, the latest immutable state, and snapshot creation /
  loading.
- **Methods:**
  - `getMutableState()` → `S` — the working state; `DefaultTransactionHandler` passes it to
    `onHandleConsensusRound`, and uses it for freeze-period checks (also startup version stamping).
  - `copyMutableState()` → `S` — the central call: on round seal `DefaultTransactionHandler` freezes the
    just-handled state as the new latest-immutable and yields a fresh mutable copy (also the genesis / loaded
    copy at startup).
  - `getLatestImmutableState()` → `S` — `DefaultTransactionHandler` / `StartupStateUtils` grab that immutable
    copy to hash / sign / save.
  - `createSnapshotAsync(S, Path)` → `Future<Void>` — `SignedStateFileWriter` writes a hashed state to disk
    without blocking the `VirtualMap` flush pipeline; used for `PERIODIC_SNAPSHOT` states when
    `saveStateAsync` is enabled.
  - `createSnapshot(S, Path)` — the synchronous fallback `SignedStateFileWriter` uses for other states
    (e.g. freeze states) or when `saveStateAsync` is off.
  - `loadSnapshot(Path)` → `Hash` — startup (`SignedStateFileReader`): replaces the eager genesis with a
    saved state when one exists on disk.
  - `createStateFrom(D)` → `S` — reconnect (`ReconnectStateLearner`): wraps the Merkle tree received from the
    teacher into a state.
  - `initWithState(S)` — reconnect (`ReconnectController`): installs the received state as the current state.

## Execution-supplied callbacks

### `ApplicationCallbacks`

- **Implemented by:** execution layer (a record of optional consumers it registers)
- **Called by:** consensus layer
- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/builder/ApplicationCallbacks.java`
- **Fields (all `@Nullable`):**
  - `staleEventConsumer` — called when a stale self-event is detected. The only field wired in production
    (`ServicesMain` registers it via `PlatformBuilder.withStaleEventCallback`).
  - `preconsensusEventConsumer` — no builder setter; always null. Candidate for removal.
  - `snapshotOverrideConsumer` — no builder setter; always null. Candidate for removal.
- **Note:** Each callback is optional; an unset consumer means the consensus layer drops that signal at this
  seam. `ApplicationCallbacks.EMPTY` registers none.

## Notification listeners

In addition to the interfaces above, the execution layer can register listeners on the
`NotificationEngine` obtained from `Platform.getNotificationEngine()`. Each listener is a typed
`Listener<N>` whose dispatch mode and ordering are fixed by a `@DispatchModel` annotation on the
interface.

|                           Listener                            |              Notification              |    Dispatch     |                                                     Trigger                                                      |
|---------------------------------------------------------------|----------------------------------------|-----------------|------------------------------------------------------------------------------------------------------------------|
| `PlatformStatusChangeListener` (`…/listeners/`)               | `PlatformStatusChangeNotification`     | SYNC, ORDERED   | Platform status changed. *(The execution layer instead consumes status via `ExecutionLayer.newPlatformStatus`.)* |
| `ReconnectCompleteListener` (`…/listeners/`)                  | `ReconnectCompleteNotification`        | SYNC, ORDERED   | A reconnect has completed.                                                                                       |
| `StateWriteToDiskCompleteListener` (`…/listeners/`)           | `StateWriteToDiskCompleteNotification` | SYNC, ORDERED   | A state has been written to disk.                                                                                |
| `IssListener` (`…/system/state/notifications/`)               | `IssNotification`                      | SYNC, ORDERED   | Any ISS (invalid state signature) event.                                                                         |
| `AsyncFatalIssListener` (`…/system/state/notifications/`)     | `IssNotification`                      | ASYNC, ORDERED  | Fatal ISS events only (`SELF` or `CATASTROPHIC`). The execution layer registers this rather than `IssListener`.  |
| `NewRecoveredStateListener` (`…/system/state/notifications/`) | `NewRecoveredStateNotification`        | SYNC, UNORDERED | A state was produced by event-stream recovery.                                                                   |
| `StateHashedListener` (`…/system/state/notifications/`)       | `StateHashedNotification`              | SYNC, UNORDERED | A state has been hashed.                                                                                         |

All paths above are under `swirlds-platform-core/src/main/java/com/swirlds/platform/`.

**Registration anchor (execution layer):** `hedera-node/hedera-app/src/main/java/com/hedera/node/app/Hedera.java`
registers `ReconnectCompleteListener`, `StateWriteToDiskCompleteListener`, `AsyncFatalIssListener`, and
`StateHashedListener` and unregisters them at teardown.

## Adjacent: types that are *not* part of the boundary

Two types are easy to mistake for boundary interfaces. They are documented here only to disambiguate them.

### `SwirldMain`

`SwirldMain` (`swirlds-platform-core/src/main/java/com/swirlds/platform/system/SwirldMain.java`) is an
**execution-layer aggregator**, not a type the consensus layer calls across the boundary:

- `SwirldMain extends Runnable, ExecutionLayer` and adds factories (`getStateLifecycleManager()`,
  `newConsensusStateEvenHandler()`, `getSemanticVersion()`). It bundles the boundary implementations the
  execution layer owns, but it is the *parts* — `ExecutionLayer`, `ConsensusStateEventHandler`,
  `StateLifecycleManager`, `SemanticVersion` — that the execution layer hands to `PlatformBuilder`.
- The running-node `PlatformBuilder` API never takes a `SwirldMain`; `build()` never receives one.
- A whole `SwirldMain` instance is consumed only by **offline tooling** — `HederaUtils.createHederaAppMain(...)`
  (reflective construction) and `EventRecoveryWorkflow.reapplyTransactions(SwirldMain, …)` in `swirlds-cli` —
  not by the consensus/execution runtime path.

### `AppContext.Gossip`

`AppContext.Gossip` (`hedera-node/hedera-app-spi/src/main/java/com/hedera/node/app/spi/AppContext.java`)
is how Hedera *services* submit node transactions. It is **not** a consensus/execution boundary type:

- It lives outside `platform-sdk`, in the Hedera execution layer.
- Its `submit(TransactionBody)` path stays **inside** the execution layer. In `Hedera.java` it routes
  through `SubmissionManager` to `TransactionPoolNexus.submitApplicationTransaction(...)`, and that pool is
  owned by the execution layer (constructed in `Hedera.java`; it `implements EventTransactionSupplier`).
- The consensus layer never calls `Gossip.submit`. The consensus layer crosses the boundary by *pulling*
  from the execution-owned pool through `ExecutionLayer.getTransactionsForEvent()`.

In other words, the execution→consensus transaction flow is a **pull** at `getTransactionsForEvent()`, not a
push through `Gossip`.

## Supporting types crossing the boundary

These data types appear in the signatures above and travel across the seam:

- `InitTrigger` (`…/system/InitTrigger.java`) — `GENESIS` / `RESTART` / `RECONNECT` / `EVENT_STREAM_RECOVERY`.
- `TransactionLimits`, `PlatformStatus`, `QuiescenceCommand`.
- Payloads: `Event`, `Round`, `PlatformEvent`, `ConsensusSnapshot`, `StateSignatureTransaction`,
  `ScopedSystemTransaction`, `TimestampedTransaction`, `State`, `Roster`, `Signature`.

## Lifecycle

1. **Construct.** The execution layer loads or creates an initial state, builds its
   `ConsensusStateEventHandler`, `StateLifecycleManager`, and `ExecutionLayer`, and passes them to
   `PlatformBuilder.create(...)` / `with...(...)`. `build()` returns the `Platform`.
2. **Register.** The execution layer registers any `ApplicationCallbacks` (at build time) and notification
   listeners via `Platform.getNotificationEngine()`.
3. **Initialize.** The consensus layer calls `ConsensusStateEventHandler.onStateInitialized(...)` with the
   appropriate `InitTrigger`.
4. **Start.** The execution layer calls `Platform.start()`.
5. **Steady state.** The consensus layer pulls transactions via `ExecutionLayer.getTransactionsForEvent()`,
   pushes events through `onPreHandle`, delivers consensus rounds through `onHandleConsensusRound` /
   `onSealConsensusRound`, reports status via `ExecutionLayer.newPlatformStatus` and health via
   `reportUnhealthyDuration`, and fires notification listeners as events occur. The execution layer submits
   its own transactions into its pool (surfaced again to the consensus layer at `getTransactionsForEvent`).
6. **Reconnect / restart.** `StateLifecycleManager.initWithState(...)` / `loadSnapshot(...)` swap the state;
   `snapshotOverrideConsumer` and `ReconnectCompleteListener` fire.
7. **Destroy.** The execution layer calls `Platform.destroy()`. The handle is then unusable.

## Cross-references

- Orientation: [overview](../overview.md).
- Topics that exercise this boundary:
  - [Event creator](../topics/event-creator.md) — pulls transactions via `getTransactionsForEvent`; reports
    health via `reportUnhealthyDuration` and reads `PlatformStatus`.
  - [Hashgraph](../topics/hashgraph.md) — produces the consensus rounds and stale events that drive
    `onHandleConsensusRound` and `staleEventConsumer`.
  - [Quiescence](../topics/quiescence.md) — driven by `Platform.quiescenceCommand`.
  - [Freeze and upgrade](../topics/freeze-and-upgrade.md) — freeze-period checks and `PlatformStatus`
    transitions across the seam.
  - [Restart and PCES](../topics/restart-and-pces.md) — `StateLifecycleManager.loadSnapshot` and
    `onStateInitialized` with `InitTrigger.RESTART`.
- Invariants / Decisions: _(TBD: catalogs not yet populated)_.
