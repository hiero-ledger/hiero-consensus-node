---
title: Consensus / Execution boundary
kind: architecture-interface
last_reviewed: TBD
---

# Consensus / Execution boundary

## Overview

This file documents the public API surface between the Consensus library and the Execution layer (the application). It is the canonical reference for the methods, wires, and lifecycle hooks that cross that boundary.

The boundary in current code is split across three shapes: direct method calls on `ExecutionLayer` (Execution-side interface) and on the Consensus module interfaces (e.g. `HashgraphModule.initialize`); component-framework wires (output wires on Consensus modules soldered to input wires on Execution-side handlers); and a polled monitor (`FallenBehindMonitor`).

The proposal in `platform-sdk/docs/proposals/consensus-layer/Consensus-Layer.md` frames the design around inverted backpressure — Execution explicitly drives round production by calling `nextRound`, so a slow Execution layer naturally throttles consensus. That pull-shaped `nextRound` is **proposed and not yet in code**: today, consensus rounds are pushed from `HashgraphModule.consensusRoundOutputWire()`, with backpressure expressed at the receiving wire's task scheduler (capacity + `put` blocking). See `nextRound` below for the gap.

## Methods (current code)

### `initialize`

- **Direction:** Execution → Consensus.
- **Signature:** `void initialize(WiringModel model, Configuration configuration, Metrics metrics, Time time, Roster roster, NodeId selfId, FreezePeriodChecker freezeChecker, EventPipelineTracker eventPipelineTracker, long transactionOffsetNanos)`
- **Code anchor:** `consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java#HashgraphModule.initialize`. Sister methods exist on `EventCreatorModule`, `EventIntakeModule`, `PcesModule`, and `GossipModule`.
- **Purpose:** Wires up a Consensus module against the platform's wiring model and starting roster. Each Consensus sub-module exposes its own `initialize`; the proposal's single unified `initialize` is the union of these calls.
- **Backpressure / ordering contract:** Not applicable — synchronous setup. No queue between caller and callee.
- **Lifecycle:** Called exactly once per module from `PlatformBuilder.build()` via the per-module `initializeXxxModule(...)` helpers, before `PlatformWiring.wire(...)` solders the wires together (see `swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java`, around the `initializeHashgraphModule` / `initializeGossipModule` calls). Not callable in steady state, freeze, or post-destroy.

### `destroy`

- **Direction:** Execution → Consensus.
- **Signature:** `void destroy()` (per-module).
- **Code anchors:**
  - `consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/EventCreatorModule.java#EventCreatorModule.destroy`
  - `consensus-event-intake/src/main/java/org/hiero/consensus/event/intake/EventIntakeModule.java#EventIntakeModule.destroy`
- **Shape divergence:** The proposal frames `destroy` as a single call on the Consensus instance. Current code spreads teardown across modules with inconsistent shapes: `EventCreatorModule` and `EventIntakeModule` expose `destroy()`; `HashgraphModule` exposes `startSquelching()` / `stopSquelching()` / `flush()` instead (no `destroy`); `GossipModule` exposes a `stopInputWire()` (`@InputWireLabel("stop")`) rather than a method. See `consensus-hashgraph/.../HashgraphModule.java` and `consensus-gossip/.../GossipModule.java`.
- **Purpose:** Tear down per-module state, executors, and (for Gossip) network connections.
- **Backpressure / ordering contract:** [TBD: question for engineer — what is the required order of `destroy` / `stop` calls across modules at shutdown? Is it documented anywhere or only encoded in `PlatformBuilder` shutdown?]
- **Lifecycle:** Callable once after steady state. The reconnect path uses `startSquelching` / `flush` / `stopSquelching` (not `destroy`) to drain and re-prime modules; see the [reconnect topic](../topics/reconnect.md) for the handoff.

### `getTransactionsForEvent`

- **Direction:** Consensus → Execution (Consensus calls a method implemented by Execution).
- **Signature:** `List<TimestampedTransaction> getTransactionsForEvent()`
- **Code anchor:** `consensus-model/src/main/java/org/hiero/consensus/model/transaction/EventTransactionSupplier.java#EventTransactionSupplier.getTransactionsForEvent`. `ExecutionLayer` (`swirlds-platform-core/.../ExecutionLayer.java`) extends `EventTransactionSupplier`. The production transaction pool implements it at `consensus-utility/src/main/java/org/hiero/consensus/transaction/TransactionPoolNexus.java#TransactionPoolNexus.getTransactionsForEvent`.
- **Caller:** `consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java#TipsetEventCreator.assembleEventObject` (call site at line 480).
- **Purpose:** When the Event Creator decides to create an event, it pulls a list of timestamped transactions from Execution to include in that event.
- **Backpressure / ordering contract:** Synchronous return. The implementation in `TransactionPoolNexus` is `synchronized` and bounded by `ExecutionLayer.getTransactionLimits()` (`DEFAULT_TRANSACTION_LIMITS` is `(133120, 245760)` — per-transaction and per-event byte ceilings; see `ExecutionLayer.java`). [TBD: question for engineer — is each call expected to return a fresh batch (consume-on-pull), or may the same transactions be returned across retries within a single event-creation attempt?]
- **Lifecycle:** Called repeatedly during steady state (once per self-event creation). Not called pre-`initialize` or post-`destroy`.

### `onPreHandleEvent`

- **Direction:** Consensus → Execution.
- **Shape:** Output wire, not a named callback. Soldered to an Execution-side input wire.
- **Signature (wire):** `OutputWire<PlatformEvent> preconsensusEventOutputWire()`
- **Code anchor:** `consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java#HashgraphModule.preconsensusEventOutputWire`. The wire is soldered in `swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java` (around lines 141–147) to `swirlds-platform-core/src/main/java/com/swirlds/platform/eventhandling/TransactionPrehandler.java#TransactionPrehandler.prehandleApplicationTransactions`.
- **Purpose:** Forwards each event the Hashgraph module accepts to Execution before the event reaches consensus, so Execution can pre-handle its transactions in parallel with consensus computation.
- **Backpressure / ordering contract:** Backpressure is expressed at the soldered `InputWire` of the prehandler's task scheduler. The wire framework's input methods are `put` (may block when backpressure is enabled), `offer` (non-blocking, returns `false` on full), and `inject` (forces acceptance regardless of capacity); see `swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java`. The default `solderTo(...)` here uses the blocking `put` path. The prehandler's scheduler capacity is configured via `PlatformSchedulersConfig.applicationTransactionPrehandler` (see `swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformSchedulersConfig.java`). Topological order is preserved on emission (events leave Hashgraph in topological order); however the proposal explicitly notes "no guarantee Execution sees `preHandle` before `handle`" because pre-handle and handle run on different schedulers and may race. [TBD: question for engineer — is the absence of pre-handle-before-handle ordering a contract guarantee Execution must tolerate, or a current limitation slated to be tightened?]
- **Lifecycle:** Active in steady state once `PlatformWiring.wire` has soldered the wire. Squelched during reconnect via `HashgraphModule.startSquelching()`.

### `onRound`

- **Direction:** Consensus → Execution.
- **Shape:** Output wire, not a named callback. Multiple Execution-side and platform-side consumers are soldered.
- **Signature (wire):** `OutputWire<ConsensusRound> consensusRoundOutputWire()`
- **Code anchor:** `consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java#HashgraphModule.consensusRoundOutputWire`. Soldered in `swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java` (around lines 202–217) to `swirlds-platform-core/src/main/java/com/swirlds/platform/eventhandling/TransactionHandler.java#TransactionHandler.handleConsensusRound`, plus `EventWindowManager.extractEventWindow`, `ConsensusEventStream.addEvents` (after a `RoundsToCesEvents` transformer), and `PlatformMonitor.consensusRound`.
- **Purpose:** Delivers each consensus round to Execution (and to platform-side consumers that need round metadata).
- **Backpressure / ordering contract:** Push from Consensus to receivers. Backpressure today lives at the receiving `InputWire` (e.g. the `transactionHandler` task scheduler in `PlatformSchedulersConfig`); the default soldering uses `put` (may block). Rounds are emitted in monotonically-increasing round-number order from `HashgraphModule`. [TBD: question for engineer — under the proposed pull model, would `nextRound` replace this push, or would it gate the push at source (i.e. consensus runs only when a `nextRound` token has been deposited)?]
- **Lifecycle:** Active in steady state. Squelched during reconnect via `HashgraphModule.startSquelching()`.

### `onStaleEvent`

- **Direction:** Consensus → Execution.
- **Shape:** Output wire, soldered to an application-supplied `Consumer<PlatformEvent>` callback.
- **Signature (wire):** `OutputWire<PlatformEvent> staleEventOutputWire()`
- **Code anchor:** `consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java#HashgraphModule.staleEventOutputWire`. Soldered in `swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java` (around lines 134–138) to the optional `staleEventConsumer` field of `swirlds-platform-core/src/main/java/com/swirlds/platform/builder/ApplicationCallbacks.java#ApplicationCallbacks` (registered via `PlatformBuilder.withStaleEventCallback`).
- **Purpose:** Notifies Execution that an event has become stale (will not reach consensus), so Execution can inspect its transactions and resubmit any that are still valid.
- **Backpressure / ordering contract:** The wire is only soldered if `callbacks.staleEventConsumer() != null`; absent a registered callback, stale events are dropped at this seam. Backpressure follows the wire-framework defaults of the soldered handler. The proposal states an event will be reported via `onStaleEvent` only if it was previously emitted via `onPreHandleEvent`. [TBD: question for engineer — is that "previously seen via prehandle" guarantee enforced anywhere in code, or a property emerging from how Hashgraph emits events?]
- **Lifecycle:** Active in steady state once `PlatformWiring.wire` has soldered the wire (and the callback is registered).

### `onBehind`

- **Direction:** Consensus → Execution.
- **Shape:** Polled monitor with a blocking-await method, not a callback.
- **Signature:** `boolean hasFallenBehind()` and `void awaitFallenBehind() throws InterruptedException`
- **Code anchor:** `consensus-utility/src/main/java/org/hiero/consensus/monitoring/FallenBehindMonitor.java#FallenBehindMonitor.awaitFallenBehind` (lines 179–188) and `FallenBehindMonitor.hasFallenBehind` (lines 125–132). The monitor is constructed in `swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#PlatformBuilder.build` (around line 566) and passed to `ReconnectModule` (`swirlds-platform-core/src/main/java/com/swirlds/platform/reconnect/ReconnectModule.java`) and `GossipModule` (`consensus-gossip/src/main/java/org/hiero/consensus/gossip/GossipModule.java`).
- **Shape divergence:** The proposal frames `onBehind` as a callback Consensus pushes into Execution. Current code exposes a `FallenBehindMonitor` that Execution-side reconnect logic polls via `hasFallenBehind()` or blocks on via `awaitFallenBehind()`. Peer reports flow into the monitor through `report(NodeId)` and `check(EventWindow self, EventWindow other, NodeId peer)`.
- **Purpose:** Signals to Execution-side reconnect logic that this node has fallen far enough behind to require a reconnect. The monitor flips to `isBehind` when the count of peers reporting "you're behind" exceeds `FallenBehindConfig.fallenBehindThreshold` of total peers (or all peers report).
- **Backpressure / ordering contract:** No backpressure — the monitor is a single boolean predicate guarded by a `ReentrantLock`. Threshold logic is enforced inside `FallenBehindMonitor.checkAndNotify()` (lines 77–85). Tests of this contract are not catalogued here. [TBD: question for engineer — is the polled-monitor shape the canonical contract, or is the proposal's callback shape still the target?]
- **Lifecycle:** Constructed during `PlatformBuilder.build`. Active in steady state. Reset via `clear()` after a successful reconnect. Not callable post-`destroy` (lifecycle of the monitor itself is not currently expressed as a method).

## Methods (proposed, not yet in code)

### `nextRound`

> **Proposed:** No code counterpart.

- **Direction (proposed):** Execution → Consensus.
- **Purpose (proposed):** Execution explicitly requests the next consensus round, optionally supplying a new roster. The proposal frames this as the source of inverted backpressure: "Hashgraph **never** runs the consensus algorithm until it is told to do so from the Execution module" (`Consensus-Layer.md` lines 231–232; pull semantics at lines 518–521).
- **Current state:** No method named `nextRound` exists on `HashgraphModule` or any other Consensus-side interface. Today consensus rounds are pushed from `HashgraphModule.consensusRoundOutputWire()` whenever the algorithm produces them; backpressure is expressed at the *receiving* wire (Execution-side `transactionHandler` scheduler capacity in `PlatformSchedulersConfig`). [TBD: question for engineer — is the proposed pull intended to replace wire-level backpressure entirely, or to wrap it (e.g. `nextRound` deposits a token that gates the existing push)?]
- **Lifecycle (proposed):** Steady state, after `initialize`, before `destroy`.

### `onBadNode`

> **Proposed:** No code counterpart.

- **Direction (proposed):** Consensus → Execution.
- **Purpose (proposed):** Notify Execution when a node enters or leaves a "bad" status under the Sheriff component.
- **Current state:** No `Sheriff`, `BadNode`, `onBadNode`, or `badNode` symbols appear in the codebase under `platform-sdk/`. The Sheriff component is described in the proposal but not implemented.

### `badNode`

> **Proposed:** No code counterpart.

- **Direction (proposed):** Execution → Consensus.
- **Purpose (proposed):** Notify Consensus of network-wide bad-node decisions that Execution has reached consensus on (so Consensus can apply coordinated enforcement).
- **Current state:** Same as `onBadNode` — no Sheriff in code.

## Lifecycle

The boundary is wired up exactly once per node startup, exercised in steady state, and torn down at shutdown / freeze / reconnect. The drivers are:

1. **Init.** `PlatformBuilder.build()` (`swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java`) constructs each Consensus sub-module via `createModule(...)` (ServiceLoader), constructs `FallenBehindMonitor`, then calls per-module `initializeXxxModule(...)` helpers. Each helper calls the module's `initialize(...)` (e.g. `HashgraphModule.initialize`).
2. **Wire.** `PlatformWiring.wire(platformContext, execution, components, callbacks)` (`swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java`) solders Consensus output wires to Execution-side input wires and direct callbacks: `preconsensusEventOutputWire` → `TransactionPrehandler::prehandleApplicationTransactions`; `consensusRoundOutputWire` → `TransactionHandler::handleConsensusRound` (and platform-side fanouts); `staleEventOutputWire` → `ApplicationCallbacks.staleEventConsumer` (if non-null); execution health input → `execution::reportUnhealthyDuration`; state-signer output → `execution::submitStateSignature`. `ExecutionLayer.getTransactionsForEvent` is plumbed into the Event Creator module via `EventTransactionSupplier`.
3. **Steady state.** Events flow Execution↔Consensus across the wires and through `ExecutionLayer` method calls. `FallenBehindMonitor` is polled or awaited by reconnect logic.
4. **Reconnect handoff.** `HashgraphModule.startSquelching()` halts wire intake, `flush()` drains in-flight tasks, `consensusSnapshotInputWire()` aligns module state to a new snapshot, `stopSquelching()` resumes intake. See [reconnect topic](../topics/reconnect.md) for the full sequence.
5. **Destroy.** `EventCreatorModule.destroy()` and `EventIntakeModule.destroy()` are the explicit teardown methods today; `HashgraphModule` and `GossipModule` have no symmetric `destroy` (squelching/flush and a stop input wire respectively). [TBD: question for engineer — is unified `destroy()` across modules a planned harmonization, or is the current asymmetric shape intentional?]

## Cross-references

- Topics that exercise this boundary:
  - [reconnect](../topics/reconnect.md) — boundary handoff at reconnect [TBD: target file not yet written].
  - [freeze and upgrade](../topics/freeze-and-upgrade.md) — boundary handoff at freeze [TBD: target file not yet written].
  - [health monitor and backpressure](../topics/health-monitor-and-backpressure.md) — where the proposed `nextRound`-driven backpressure (and the current wire-level backpressure) is discussed [TBD: target file not yet written].
- Orientation: [overview](../overview.md) [TBD: target file not yet written].
- Invariants: [TBD: INV-NNN once `invariants.md` catalog populates].
- Decisions: [TBD: ADR-NNN once `decisions/` catalog populates — the inverted-backpressure choice is a likely first ADR].
