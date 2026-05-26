---
title: Wiring framework
kind: architecture-topic
last_reviewed: TBD
---

# Wiring framework

## Responsibilities

The wiring framework is the substrate the consensus layer is built on. It supplies the primitives — task schedulers, typed input and output wires, soldering, and transformers — that every consensus-layer topic uses to express data flow between components. The framework owns concurrency, queueing, and graph validation. It does **not** own topic logic; per-topic files (gossip, event-intake, hashgraph, …) describe what runs on which scheduler and how their wires connect.

What the framework provides:

- **Type-safe data flow** — input and output wires are generic; soldering is checked at the type level.
- **Declarative inter-component wiring** — `solderTo` connects output wires to input wires; `PUT` / `INJECT` / `OFFER` select per-edge handoff semantics.
- **In-line data transformations** — filters, transformers, advanced transformers, and list-splitters reshape data along a wire without a dedicated component.
- **Configurable concurrency and queueing** — six scheduler types, per-scheduler capacity, and `flush()` / squelching for graceful draining.
- **Graph validation** — cycle detection, illegal `DIRECT` usage detection, unbound input wires, and Mermaid-style diagram generation.
- **Queue-health observability** — per-scheduler queue depth feeds the health monitor wire that the rest of the consensus layer subscribes to.
- **Process-wide heartbeat** — periodic tick wire (`buildHeartbeatWire`) that components can subscribe to for cadence-driven work.

## Core abstractions

### TaskScheduler / TaskSchedulerType

A `TaskScheduler<OUT>` (`swirlds-component-framework :: TaskScheduler`) owns a queue, a thread-execution policy, and a built-in primary `OutputWire<OUT>`. The framework primitive for obtaining one is `WiringModel.schedulerBuilder(name).withType(...).build()`. Consensus-layer code rarely calls that directly: the canonical wrapper is `ComponentWiring<COMPONENT, OUT>` (`swirlds-component-framework :: ComponentWiring`), which combines a scheduler with method-reference-based input-wire creation and a deferred binding step.

`TaskSchedulerType` (`swirlds-component-framework :: TaskSchedulerType`) chooses the threading policy. Six values exist; see the enum's javadoc for the authoritative descriptions:

- `SEQUENTIAL` — fork-join pool, one task at a time, happens-before between consecutive tasks, thread-confined handler.
- `SEQUENTIAL_THREAD` — dedicated thread, one task at a time, thread-confined handler.
- `CONCURRENT` — fork-join pool, parallel, no ordering guarantee.
- `DIRECT` — execute on the calling thread; no queue. Subject to graph-walk validation rules.
- `DIRECT_THREADSAFE` — like `DIRECT` but the handler must itself be threadsafe.
- `NO_OP` — discards everything; useful for disabling a component without removing its wiring.

Components are assembled in three steps. The pattern below is taken from `DefaultEventIntakeModule.initialize` (`consensus-event-intake-impl :: DefaultEventIntakeModule`), and is representative throughout the consensus layer:

**Step 1 — construct a `ComponentWiring` per component**, configured from a typed `*WiringConfig` record:

```java
// consensus-event-intake-impl :: DefaultEventIntakeModule.initialize
this.eventDeduplicatorWiring =
        new ComponentWiring<>(model, EventDeduplicator.class, wiringConfig.eventDeduplicator());
```

The `Class` argument is the component's interface (which `ComponentWiring` requires so it can resolve method references reflectively). `wiringConfig.eventDeduplicator()` returns a `TaskSchedulerConfiguration` (`swirlds-component-framework :: TaskSchedulerConfiguration`) — the scheduler's type, capacity, flushing, and other options come from configuration.

**Step 2 — solder** the component's input and output wires to its neighbours; see [Soldering](#soldering).

**Step 3 — bind** the component instance:

```java
// consensus-event-intake-impl :: DefaultEventIntakeModule.initialize
final EventDeduplicator eventDeduplicator = new StandardEventDeduplicator(metrics, intakeEventCounter);
eventDeduplicatorWiring.bind(eventDeduplicator);
```

Binding resolves the method references attached during step 2 to the actual `eventDeduplicator` instance; from this point the wiring is live.

`WiringModel.schedulerBuilder(...)` is an instance method that returns a `TaskSchedulerBuilder<O>`, not a ready scheduler — the call always terminates in `.build()`. In the canonical consensus-layer pattern that terminating call lives inside `ComponentWiring`, not in the module's wiring code. The framework primitive is still used directly for the small set of cases that do not fit the `ComponentWiring` shape — for example, `PassThroughWiring` constructs a no-op identity scheduler, and `GossipWiring` self-builds its scheduler with its own `GossipWiringConfig` (treat the latter as an exception, not a template).

There is no canonical scheduler type for new components — the choice is made case-by-case and lives in the component's `*WiringConfig` record. Type selection is part of the scheduler tuning surface, not a default.

`flush()` is opt-in via `withFlushingEnabled(true)` and calling it on a scheduler that did not opt in throws `UnsupportedOperationException` (`swirlds-component-framework :: TaskScheduler`). It waits for the scheduler's on-ramp counter to drain to zero — every task that has entered the scheduler has finished processing — and does **not** transitively flush downstream-soldered schedulers (`swirlds-component-framework :: SequentialTaskScheduler` / `ConcurrentTaskScheduler`). In practice `flush()` is always paired with `startSquelching()` / `stopSquelching()`, the scheduler's "drop new tasks" toggle: squelch new arrivals, flush the existing backlog, then stop squelching to resume acceptance. The composite drain is performed module-wide (or system-wide) by calling the trio on each `ComponentWiring` in turn — see `DefaultEventIntakeModule.flush()`. The combined pattern is intricate and a known candidate for future simplification.

### InputWire and OutputWire

`InputWire<IN>` (`swirlds-component-framework :: InputWire`) is the entry point of a component. It exposes three put-paths: `put(data)` blocks the caller if the scheduler's queue is at capacity, `offer(data)` returns `false` rather than blocking, and `inject(data)` bypasses capacity entirely. These three paths correspond to the three `SolderType` values used when wires are connected via soldering — see [Backpressure (wire level)](#backpressure-wire-level).

In the canonical pattern, modules do not call `BindableInputWire.bind(...)` directly. They obtain an `InputWire` via `componentWiring.getInputWire(MethodReference)`; `ComponentWiring` lazily creates a `BindableInputWire<IN, OUT>` (`swirlds-component-framework :: BindableInputWire`) for the targeted method on first use, caches it, and binds it later when `componentWiring.bind(component)` is called. The framework method `BindableInputWire.bind(Function<IN, OUT>)` / `bindConsumer(Consumer<IN>)` is therefore an implementation detail that consensus-code normally doesn't touch directly.

`OutputWire<OUT>` (`swirlds-component-framework :: OutputWire`) is the source of every connection out of a component. Soldering, filters, transformers, and splitters are all methods on `OutputWire`. A scheduler always owns one primary output wire (`getOutputWire()`); secondary output wires for fan-out side-channels are created with `buildSecondaryOutputWire()`. When a component's primary output type is a `List<T>`, `ComponentWiring.getSplitOutput()` returns a per-element output wire (built internally via `buildSplitter(...)`) — `DefaultEventIntakeModule.validatedEventsOutputWire()` returns the orphan-buffer's split output for this reason.

Primary and secondary output wires behave differently. The primary output wire receives whatever the bound function returns and is fed by the scheduler itself — it participates in the scheduler's on-ramp counter and therefore in `flush()` and `startSquelching()` / `stopSquelching()`. Secondary output wires are owned by the business logic and pushed explicitly; the scheduler is unaware of them, so data emitted via a secondary wire does **not** count towards the scheduler's on-ramp counter and does **not** participate in `flush()`. The component is the sole owner of its output wires: only the scheduler should push onto the primary wire, and only the business logic should push onto any secondaries. As a rule of thumb, a component has a primary wire for its canonical return value and reaches for secondaries only when business logic needs to emit something the scheduler would not naturally produce.

### Soldering

Soldering connects an `OutputWire<T>` to one or more `InputWire<T>`s. The call is `outputWire.solderTo(inputWire)` (defaults to `SolderType.PUT`) or `outputWire.solderTo(inputWire, solderType)`. `SolderType` (`swirlds-component-framework :: SolderType`) has three values:

- `PUT` — handoff via `put`; blocks the producing thread when the consumer is at capacity. The default and the only path that participates in backpressure.
- `INJECT` — handoff via `inject`; bypasses capacity. Used to break cycles that would otherwise deadlock under backpressure.
- `OFFER` — handoff via `offer`; drops the item when the consumer is at capacity rather than blocking.

A representative pair of call sites from inside a module, both from `DefaultEventIntakeModule.initialize(...)`:

```java
// consensus-event-intake-impl :: DefaultEventIntakeModule.initialize
eventDeduplicatorWiring
        .getOutputWire()
        .solderTo(eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::validateSignature));

clearCommandDispatcher
        .getOutputWire()
        .solderTo(eventDeduplicatorWiring.getInputWire(EventDeduplicator::clear), INJECT);
```

The first line is the default `PUT` case — events flow from deduplicator to signature validator under backpressure. The second uses `INJECT` so a clear command always reaches the deduplicator, even when its queue is full; clear commands are out-of-band control signals that must not be backpressured against the data path. The same `INJECT` pattern appears between modules in `PlatformWiring.wire(...)` — the persisted-event → gossip edge and the event-creator → event-intake edge both use it. `OFFER` is rarer; `PlatformWiring` uses it for the heartbeat handoff, where a missed beat is preferable to a backed-up queue.

Soldering is treated as a one-shot operation performed at wiring assembly: there is no API for unsoldering, and the consensus layer treats the graph as immutable once `WiringModel.start()` has run (immutability is convention, not enforced by the framework). `OFFER` is used in only four production sites today, all heartbeat-cadence solders — to `PlatformMonitor`, `StateGarbageCollector`, and `SignedStateSentinel` from `PlatformWiring`, and to `EventCreationManager::maybeCreateEvent` from `DefaultEventCreatorModule`. The drop is silent (the consumer's input wire `offer(...)` returns `false`); a missed tick is preferable to a queued one at these consumers.

### Transformers and splitters (`WireListSplitter`)

Transformers are lightweight, in-line conversions on the data flowing along an output wire. `OutputWire.buildFilter(...)` (predicate-based gating), `buildTransformer(...)` (function-based type change, `null` not forwarded), and `buildAdvancedTransformer(...)` (transformer with `inputCleanup` / `outputCleanup` hooks for resource management) each produce a new `OutputWire` that can be soldered or transformed further. They run on a `DIRECT_THREADSAFE` scheduler internally, so they execute on whichever thread happens to forward data along the upstream output wire.

Modules also use a free-standing `WireTransformer<A, B>` (`swirlds-component-framework :: WireTransformer`) as a fan-out dispatcher — a single input wire whose output wire is soldered to several consumers. `DefaultEventIntakeModule` constructs identity dispatchers for event-window updates and clear commands so each value is delivered to every component that subscribes:

```java
// consensus-event-intake-impl :: DefaultEventIntakeModule.initialize
this.eventWindowDispatcher =
        new WireTransformer<>(model, "EventWindowDispatcher", "event window", UnaryOperator.identity());
```

`WireListSplitter<T>` (`swirlds-component-framework :: WireListSplitter`) is the canonical concrete transformer: a `List<T>` arriving on its input wire produces one task per element on its output wire. It is built via `OutputWire.buildSplitter(...)`, and `ComponentWiring` exposes it as `getSplitOutput()` for any component whose primary output type is a list. A more advanced chain appears in `PlatformWiring` for the signed-state fan-out:

```java
// swirlds-platform-core :: PlatformWiring
final OutputWire<ReservedSignedState> splitReservedSignedStateWire = components
        .stateSignatureCollectorWiring()
        .getOutputWire()
        .buildSplitter("reservedStateSplitter", "reserved state lists");
final OutputWire<ReservedSignedState> allReservedSignedStatesWire =
        splitReservedSignedStateWire.buildAdvancedTransformer(new SignedStateReserver("allStatesReserver"));
```

`SignedStateReserver` implements `AdvancedTransformation<ReservedSignedState, ReservedSignedState>` (`swirlds-component-framework :: AdvancedTransformation`) so each element gets an additional reservation as it passes through and the original is released on cleanup. Simpler conversions look like `outputWire.buildTransformer("RoundsToCesEvents", "consensus rounds", ConsensusRound::getStreamedEvents)`.

All three concrete `AdvancedTransformation` implementations in the codebase live under `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/`: `SignedStateReserver`, `StateWithHashComplexityReserver`, and `StateWithHashComplexityToStateReserver`. Every one of them exists to add or transfer a reservation as state objects flow along a wire — reservation handoff is the only current use of the `inputCleanup` / `outputCleanup` hooks.

### WiringModel

`WiringModel` (`swirlds-component-framework :: WiringModel`) owns every scheduler and every wire in a process. It is an interface (constructed via `WiringModelBuilder`); concrete implementations are `StandardWiringModel` and `DeterministicWiringModel`. Its responsibilities are:

- Hand out scheduler builders (`schedulerBuilder(name)`).
- Validate the assembled graph: `checkForCyclicalBackpressure`, `checkForIllegalDirectSchedulerUsage`, `checkForUnboundInputWires`.
- Generate Mermaid-style wiring diagrams for documentation and debugging (`generateWiringDiagram`).
- Expose process-wide infrastructure wires: a heartbeat wire (`buildHeartbeatWire`) and a health-monitor wire (`getHealthMonitorWire`) — the latter is the integration point with the queue-health subsystem described below.
- Drive the lifecycle: `start()` / `stop()` (`WiringModel` extends `Startable, Stoppable`).

A single `WiringModel` instance flows through the whole platform: it is constructed once by `PlatformComponents.create(...)`, passed into each module's `initialize(model, configuration, ...)` so the module can build its internal `ComponentWiring`s on it, and threaded into `PlatformWiring.wire(...)` so inter-module solders share the same graph. Soldering against the model-owned heartbeat and health-monitor wires is one of the few places `PlatformWiring` reaches back into the model directly.

`stop()` is not a graceful drain: it stops the heartbeat scheduler and the schedulers that own dedicated threads, but does not wait for in-flight or queued tasks (`swirlds-component-framework :: StandardWiringModel`). The lifecycle is one-shot — calling `start()` a second time throws. `DeterministicWiringModel` is reached via `WiringModelBuilder.deterministic()` and exists solely for testing and debugging; production always uses `StandardWiringModel`.

## Backpressure (wire level)

Wire-level backpressure is per-scheduler and configured at build time. In the canonical consensus-layer setup, capacity comes from the `TaskSchedulerConfiguration` record passed to `ComponentWiring`'s constructor; the underlying framework method is `TaskSchedulerBuilder.withUnhandledTaskCapacity(...)`. When a producing thread solders to a consumer with `SolderType.PUT` and the consumer is at capacity, the producer's call into `put` blocks until the consumer drains. `INJECT` bypasses the cap entirely; `OFFER` drops the item rather than blocking. The three `SolderType` values exist precisely so the wiring author can choose, edge-by-edge, which of these three behaviours is appropriate.

The principal hazard is cyclic data flow under `PUT`: a producer waiting on a downstream consumer that is itself waiting (transitively) on the producer will deadlock. The wiring model detects cyclic backpressure during validation (`checkForCyclicalBackpressure`) and logs a warning. The standard remedy is to flip exactly one edge in the cycle to `INJECT`, so the cycle no longer transmits backpressure pressure. Both module-internal and inter-module solders use this: `DefaultEventIntakeModule` injects on every control edge into the intake components (event-window updates, clear commands), and `PlatformWiring` injects on the persisted-event → gossip edge and the event-creator → event-intake feedback for the same reason.

Backpressure feeds queue-health detection. `WiringModel.getHealthMonitorWire()` exposes the duration each scheduler has been over its capacity, and the health monitor turns that signal into the unhealthy-duration reports that the rest of the consensus layer (event creation, gossip, transaction acceptance, PCES replay, …) reacts to. This file describes only the wire-level mechanism; the reaction side — what each subsystem does when the system is unhealthy — lives in `../topics/health-monitor-and-backpressure.md` *(planned)*.

A few specifics worth pinning down. The default `unhandledTaskCapacity` on the framework builder is `1` (`swirlds-component-framework :: AbstractTaskSchedulerBuilder`); consensus-layer schedulers always override this via `TaskSchedulerConfiguration`, so the default is effectively never used in production. The capacity is per-scheduler: every input wire on the same scheduler shares a single on-ramp counter, so the cap applies to total backlog across all inputs, not independently per wire. `withExternalBackPressure(true)` declares that the producer applies backpressure externally and forces blocking insertion even when no capacity limit is set; it does not change the unhealthy-duration calculation in the health monitor. The default `UncaughtExceptionHandler` (installed when none is supplied to the builder) logs at ERROR via log4j and the scheduler continues with the next task (`swirlds-component-framework :: ExceptionHandlers`); `DIRECT` / `DIRECT_THREADSAFE` schedulers, having no queue, invoke the handler on the calling thread but otherwise behave the same.

## Cross-references

- Topics: `../topics/health-monitor-and-backpressure.md` *(planned)* — reaction side of queue health.
- Module-API boundary: `../interfaces/consensus-execution-boundary.md` *(planned)* — where the future module-API-level backpressure differs from wire-level backpressure (see [Future state](#future-state-sidebar) below).
- Invariants: [TBD: INV-NNN once invariants.md catalog populates].
- Decisions: [TBD: ADR-NNN once decisions/ catalog populates].
- Glossary: `../../glossary.md` *(planned)* — entries for "wire", "scheduler", "soldering", "transformer".

## Future state (sidebar)

> A planned **module-API-level** backpressure will operate above wire-level backpressure, not in place of it. Execution will drive a `nextRound` pull that throttles Consensus end-to-end across the module boundary. The two layers compose: wire-level backpressure remains the per-component mechanism; the `nextRound` pull is an additional throttle at the Consensus / Execution boundary. See `../interfaces/consensus-execution-boundary.md` *(planned)* for where the module-API backpressure differs from the wire-level mechanism described above.
