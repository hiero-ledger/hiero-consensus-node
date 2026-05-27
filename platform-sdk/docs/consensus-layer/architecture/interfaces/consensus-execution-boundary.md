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

> The application hands its boundary implementations to **`PlatformBuilder`** — chiefly an
> **`ExecutionLayer`**, a **`ConsensusStateEventHandler`**, a **`StateLifecycleManager`**, and optional
> **`ApplicationCallbacks`** — and in return receives a **`Platform`** handle. Everything else hangs off
> those.

This document covers the **application-facing boundary only**. The internal decomposition of the
consensus layer into wired modules (`HashgraphModule`, `EventCreatorModule`, `EventIntakeModule`,
`PcesModule`, `GossipModule`) is a separate, lower seam and is not documented here.

### Directions

Two directions cross the boundary:

- **Platform → App** — the platform provides a handle (`Platform`) that the application calls into.
- **App → Platform** — the application implements interfaces (or supplies callbacks) that the platform
  calls into. This is the larger half of the boundary.

## The construction handshake: `PlatformBuilder`

- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java`
- **Role:** The construction-time seam. The application assembles its boundary implementations and passes
  them to the builder, then calls `build()` to obtain a `Platform`.

`PlatformBuilder.create(...)` takes the application's boundary implementations directly. Its required
arguments include:

- `SemanticVersion softwareVersion`
- `ReservedSignedState initialState` — the genesis or loaded state the app supplies
- `ConsensusStateEventHandler consensusStateEventHandler`
- `RosterHistory rosterHistory`
- `StateLifecycleManager stateLifecycleManager`
- `NodeId selfId`, app/swirld names, consensus-event-stream name

Optional wiring is added with fluent `with...` methods, notably:

- `withExecutionLayer(ExecutionLayer)` — registers the `ExecutionLayer` implementation
- `withStaleEventCallback(Consumer<PlatformEvent>)` — one of the `ApplicationCallbacks`
- `withConfiguration`, `withPlatformContext`, `withKeysAndCerts`, `withModel`, …

`build()` returns the `Platform`.

## Platform → App

### `Platform`

- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/system/Platform.java`
- **Direction:** Platform → App (consensus layer implements it; the app calls it).
- **Role:** The handle the application receives from `PlatformBuilder.build()`. It is the application's
  entry point for signing, lifecycle control, and reaching the platform's subsystems. `Platform` is a
  genuine boundary type (the app holds it and calls it), but its surface is uneven — see the breakdown
  below.

**Live boundary operations** (called by the execution layer in production):

  - `getSelfId()` → `NodeId` — this node's id; the most-called method (e.g. `Hedera`, `BlockStreamManagerImpl`).
  - `sign(byte[])` → `Signature` — sign data with the node key (backs `AppContext.Gossip.sign`).
  - `quiescenceCommand(QuiescenceCommand)` — app→platform control; instruct the platform on its quiescence
    state (`BlockStreamManagerImpl`, `BlockRecordManagerImpl`, `QuiescedHeartbeat`).
  - `start()` — start the platform (`ServicesMain`).

**Subsystem accessors** (the thing crossing the boundary is the returned subsystem, not the method):

  - `getNotificationEngine()` → `NotificationEngine` — the gateway through which the app registers the
    [notification listeners](#notification-listeners) below.
  - `getContext()` → `PlatformContext` — the app reaches in for `Configuration` (and metrics / time / file
    system). Used sparingly.

**Lifecycle, asymmetric:**

  - `destroy()` — documented as the terminal call (the platform cannot be reused afterward). No production
    execution-layer caller was found; it is exercised by the Turtle/Container simulation harnesses. Note the
    asymmetry with `start()`, which the app *does* call.

**Vestigial — no production caller** (candidates for removal, in the same sense `SwirldMain` is not part of
the boundary):

  - `getRoster()` → `Roster` — only a test mock calls it; the app and platform-internal code read the
    roster from state (`signedState.getRoster()` / `reservedState.getRoster()`) instead.
  - `getLatestImmutableState(reason)` → `AutoCloseableWrapper<T extends State>` — no caller anywhere except
    the `NoOpPlatform` test stub; the app uses `StateLifecycleManager.getLatestImmutableState()` instead.

## App → Platform

These are the interfaces the application implements (or supplies). The platform calls into them.

### `ExecutionLayer`

- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/builder/ExecutionLayer.java`
- **Direction:** App → Platform (the platform calls these on the app).
- **Role:** The explicitly-named boundary interface.
  `ExecutionLayer extends EventTransactionSupplier, SignatureTransactionCheck`.
- **Methods:**
  - `getTransactionsForEvent()` → `List<TimestampedTransaction>` *(inherited from `EventTransactionSupplier`)* —
    the event creator pulls transactions from the app when it creates a self-event.
  - `hasBufferedSignatureTransactions()` → `boolean` *(inherited from `SignatureTransactionCheck`)*.
  - `submitStateSignature(StateSignatureTransaction)` — the platform hands a state signature to the app for
    later inclusion via `getTransactionsForEvent()`. *(Marked transitional — to be removed once state
    management moves into the execution layer.)*
  - `newPlatformStatus(PlatformStatus)` — the platform notifies the app that its status changed. This is
    the primary status path (the Hedera app uses this rather than `PlatformStatusChangeListener`).
  - `getTransactionLimits()` → `TransactionLimits` — the app declares per-transaction / per-event byte
    ceilings the consensus layer enforces on gossiped events. Default `DEFAULT_TRANSACTION_LIMITS` is
    `(133120, 245760)`.
  - `reportUnhealthyDuration(Duration)` — the platform reports how long it has been unhealthy
    (`Duration.ZERO` when it returns to healthy).

### `ConsensusStateEventHandler`

- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/state/ConsensusStateEventHandler.java`
- **Direction:** App → Platform.
- **Role:** The application's hooks into the major state lifecycle events. Implementations are expected to
  be stateless / effectively immutable and to live for the lifetime of the application.
- **Methods:**
  - `onPreHandle(Event, State, Consumer<ScopedSystemTransaction<StateSignatureTransaction>>)` — called when
    an event is added to the hashgraph; the app pre-handles the event's transactions.
  - `onHandleConsensusRound(Round, State, Consumer<…>)` — called when a round reaches consensus and is ready
    to be applied to the working state.
  - `onSealConsensusRound(Round, State)` → `boolean` — called after the platform has made all its changes
    to the state for the round; returns whether sealing completes a block (i.e. whether the round's state is
    safe to sign).
  - `onStateInitialized(State, Platform, InitTrigger, SemanticVersion previousVersion)` — called when the
    platform initializes the network state. `InitTrigger` distinguishes `GENESIS` / `RESTART` / `RECONNECT`
    / `EVENT_STREAM_RECOVERY`.
  - `onNewRecoveredState(State)` — called when event-stream recovery finishes.

### `StateLifecycleManager<S, D>`

- **Code anchor:** `swirlds-state-api/src/main/java/com/swirlds/state/StateLifecycleManager.java`
- **Direction:** App → Platform.
- **Role:** Owns the state lifecycle: the mutable state, the latest immutable state, snapshot creation /
  loading, and producing the next mutable copy. The application supplies it via `SwirldMain` /
  `PlatformBuilder`; the platform drives it.
- **Methods:** `createStateFrom(D)`, `getMutableState()`, `getLatestImmutableState()`,
  `createSnapshot(S, Path)`, `createSnapshotAsync(S, Path)` → `Future<Void>`,
  `loadSnapshot(Path)` → `Hash`, `initWithState(S)`, `copyMutableState()` → `S`.
- **Note:** A genesis state is created eagerly on construction. `loadSnapshot(...)` replaces it on restart;
  `initWithState(...)` replaces the current state on reconnect.

## App-supplied callbacks

### `ApplicationCallbacks`

- **Code anchor:** `swirlds-platform-core/src/main/java/com/swirlds/platform/builder/ApplicationCallbacks.java`
- **Direction:** App → Platform (a record of optional consumers the app registers).
- **Fields (all `@Nullable`):**
  - `preconsensusEventConsumer` — called on preconsensus events in topological order.
  - `snapshotOverrideConsumer` — called when the current consensus snapshot is overridden (reconnect /
    restart boundaries).
  - `staleEventConsumer` — called when a stale self-event is detected (registered via
    `PlatformBuilder.withStaleEventCallback`).
- **Note:** Each callback is optional; an unset consumer means the platform drops that signal at this seam.
  `ApplicationCallbacks.EMPTY` registers none.

## Notification listeners

In addition to the interfaces above, the application can register listeners on the
`NotificationEngine` obtained from `Platform.getNotificationEngine()`. Each listener is a typed
`Listener<N>` whose dispatch mode and ordering are fixed by a `@DispatchModel` annotation on the
interface.

| Listener | Notification | Dispatch | Trigger |
|---|---|---|---|
| `PlatformStatusChangeListener` (`…/listeners/`) | `PlatformStatusChangeNotification` | SYNC, ORDERED | Platform status changed. *(The Hedera app instead consumes status via `ExecutionLayer.newPlatformStatus`.)* |
| `ReconnectCompleteListener` (`…/listeners/`) | `ReconnectCompleteNotification` | SYNC, ORDERED | A reconnect has completed. |
| `StateWriteToDiskCompleteListener` (`…/listeners/`) | `StateWriteToDiskCompleteNotification` | SYNC, ORDERED | A state has been written to disk. |
| `IssListener` (`…/system/state/notifications/`) | `IssNotification` | SYNC, ORDERED | Any ISS (invalid state signature) event. |
| `AsyncFatalIssListener` (`…/system/state/notifications/`) | `IssNotification` | ASYNC, ORDERED | Fatal ISS events only (`SELF` or `CATASTROPHIC`). The Hedera app registers this rather than `IssListener`. |
| `NewRecoveredStateListener` (`…/system/state/notifications/`) | `NewRecoveredStateNotification` | SYNC, UNORDERED | A state was produced by event-stream recovery. |
| `StateHashedListener` (`…/system/state/notifications/`) | `StateHashedNotification` | SYNC, UNORDERED | A state has been hashed. |

All paths above are under `swirlds-platform-core/src/main/java/com/swirlds/platform/`.

**Registration anchor (Hedera app):** `hedera-node/hedera-app/src/main/java/com/hedera/node/app/Hedera.java`
registers `ReconnectCompleteListener`, `StateWriteToDiskCompleteListener`, `AsyncFatalIssListener`, and
`StateHashedListener` and unregisters them at teardown.

## Adjacent: types that are *not* part of the boundary

Two types are easy to mistake for boundary interfaces. They are documented here only to disambiguate them.

### `SwirldMain`

`SwirldMain` (`swirlds-platform-core/src/main/java/com/swirlds/platform/system/SwirldMain.java`) is an
**application-side aggregator**, not a type the platform calls across the boundary:

- `SwirldMain extends Runnable, ExecutionLayer` and adds factories (`getStateLifecycleManager()`,
  `newConsensusStateEvenHandler()`, `getSemanticVersion()`). It bundles the boundary implementations the
  app owns, but it is the *parts* — `ExecutionLayer`, `ConsensusStateEventHandler`, `StateLifecycleManager`,
  `SemanticVersion` — that the app hands to `PlatformBuilder`.
- The running-node `PlatformBuilder` API never takes a `SwirldMain`; `build()` never receives one.
- A whole `SwirldMain` instance is consumed only by **offline tooling** — `HederaUtils.createHederaAppMain(...)`
  (reflective construction) and `EventRecoveryWorkflow.reapplyTransactions(SwirldMain, …)` in `swirlds-cli` —
  not by the consensus/execution runtime path.

### `AppContext.Gossip`

`AppContext.Gossip` (`hedera-node/hedera-app-spi/src/main/java/com/hedera/node/app/spi/AppContext.java`)
is how Hedera *services* submit node transactions. It is **not** a consensus/execution boundary type:

- It lives outside `platform-sdk`, in the Hedera application layer.
- Its `submit(TransactionBody)` path stays **inside** the execution layer. In `Hedera.java` it routes
  through `SubmissionManager` to `TransactionPoolNexus.submitApplicationTransaction(...)`, and that pool is
  owned by the application (constructed in `Hedera.java`; it `implements EventTransactionSupplier`).
- The platform never calls `Gossip.submit`. The platform crosses the boundary by *pulling* from the
  app-owned pool through `ExecutionLayer.getTransactionsForEvent()`.

In other words, the app→platform transaction flow is a **pull** at `getTransactionsForEvent()`, not a push
through `Gossip`.

## Supporting types crossing the boundary

These data types appear in the signatures above and travel across the seam:

- `InitTrigger` (`…/system/InitTrigger.java`) — `GENESIS` / `RESTART` / `RECONNECT` / `EVENT_STREAM_RECOVERY`.
- `TransactionLimits`, `PlatformStatus`, `QuiescenceCommand`.
- Payloads: `Event`, `Round`, `PlatformEvent`, `ConsensusSnapshot`, `StateSignatureTransaction`,
  `ScopedSystemTransaction`, `TimestampedTransaction`, `State`, `Roster`, `Signature`.

## Lifecycle

1. **Construct.** The application loads or creates an initial state, builds its
   `ConsensusStateEventHandler`, `StateLifecycleManager`, and `ExecutionLayer`, and passes them to
   `PlatformBuilder.create(...)` / `with...(...)`. `build()` returns the `Platform`.
2. **Register.** The application registers any `ApplicationCallbacks` (at build time) and notification
   listeners via `Platform.getNotificationEngine()`.
3. **Initialize.** The platform calls `ConsensusStateEventHandler.onStateInitialized(...)` with the
   appropriate `InitTrigger`.
4. **Start.** The application calls `Platform.start()`.
5. **Steady state.** The platform pulls transactions via `ExecutionLayer.getTransactionsForEvent()`,
   pushes events through `onPreHandle`, delivers consensus rounds through `onHandleConsensusRound` /
   `onSealConsensusRound`, reports status via `ExecutionLayer.newPlatformStatus` and health via
   `reportUnhealthyDuration`, and fires notification listeners as events occur. The application submits its
   own transactions into its pool (surfaced again to the platform at `getTransactionsForEvent`).
6. **Reconnect / restart.** `StateLifecycleManager.initWithState(...)` / `loadSnapshot(...)` swap the state;
   `snapshotOverrideConsumer` and `ReconnectCompleteListener` fire.
7. **Destroy.** The application calls `Platform.destroy()`. The handle is then unusable.

## Cross-references

- Orientation: [overview](../overview.md) _(TBD: target not yet written)_.
- Topics that exercise this boundary: reconnect, freeze and upgrade, health monitor and backpressure
  _(TBD: target topics not yet written)_.
- Invariants / Decisions: _(TBD: catalogs not yet populated)_.
