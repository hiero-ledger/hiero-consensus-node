---
type: architecture-topic
title: Wiring framework
last_reviewed: 2026-07-28
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

A `TaskScheduler<OUT>` ([`TaskScheduler`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java)) owns a queue, a thread-execution policy, and a built-in primary `OutputWire<OUT>`. The framework primitive for obtaining one is `WiringModel.schedulerBuilder(name).withType(...).build()`. Consensus-layer code rarely calls that directly: the standard wrapper is `ComponentWiring<COMPONENT, OUT>` ([`ComponentWiring`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java)), which combines a scheduler with method-reference-based input-wire creation and a deferred binding step.

`TaskSchedulerType` ([`TaskSchedulerType`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java)) chooses the threading policy. Six values exist; see the enum's javadoc for the authoritative descriptions:

- `SEQUENTIAL` — fork-join pool, one task at a time, happens-before between consecutive tasks, thread-confined handler.
- `SEQUENTIAL_THREAD` — dedicated thread, one task at a time, thread-confined handler.
- `CONCURRENT` — fork-join pool, parallel, no ordering guarantee.
- `DIRECT` — execute on the calling thread; no queue. Subject to graph-walk validation rules.
- `DIRECT_THREADSAFE` — like `DIRECT` but the handler must itself be threadsafe.
- `NO_OP` — discards everything; useful for disabling a component without removing its wiring.

Components are assembled in three steps. The pattern below is taken from [`DefaultEventIntakeModule.initialize`](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java), and is representative throughout the consensus layer:

**Step 1 — construct a `ComponentWiring` per component**, configured from a typed `*WiringConfig` record:

```java
// consensus-event-intake-impl :: DefaultEventIntakeModule.initialize
this.eventDeduplicatorWiring =
        new ComponentWiring<>(model, EventDeduplicator.class, wiringConfig.eventDeduplicator());
```

The `Class` argument is the component's interface (which `ComponentWiring` requires so it can resolve method references reflectively). `wiringConfig.eventDeduplicator()` returns a `TaskSchedulerConfiguration` ([`TaskSchedulerConfiguration`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerConfiguration.java)) — the scheduler's type, capacity, flushing, and other options come from configuration.

**Step 2 — solder** the component's input and output wires to its neighbours; see [Soldering](#soldering).

**Step 3 — bind** the component instance:

```java
// consensus-event-intake-impl :: DefaultEventIntakeModule.initialize
final EventDeduplicator eventDeduplicator = new StandardEventDeduplicator(metrics, intakeEventCounter);
eventDeduplicatorWiring.bind(eventDeduplicator);
```

Binding resolves the method references attached during step 2 to the actual `eventDeduplicator` instance; from this point the wiring is live.

`WiringModel.schedulerBuilder(...)` is an instance method that returns a `TaskSchedulerBuilder<O>` ([`TaskSchedulerBuilder`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java)), not a ready scheduler — the call always terminates in `.build()`. In the usual consensus-layer pattern that terminating call lives inside `ComponentWiring`, not in the module's wiring code. The framework primitive is still used directly for the small set of cases that do not fit the `ComponentWiring` shape — for example, [`PassThroughWiring`](../../../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/components/PassThroughWiring.java) constructs a no-op identity scheduler, and [`GossipWiring`](../../../../consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/GossipWiring.java) self-builds its scheduler with its own [`GossipWiringConfig`](../../../../consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/GossipWiringConfig.java) (treat the latter as an exception, not a template).

There is no preferred scheduler type for new components — the choice is made case-by-case and lives in the component's `*WiringConfig` record.

Each queued scheduler keeps a single **on-ramp counter** of the tasks that have entered it but not yet been handled. A task is on-ramped when it enters the scheduler and *off-ramped* once the task finishes.

`flush()` is opt-in via `withFlushingEnabled(true)` and calling it on a scheduler that did not opt in throws `UnsupportedOperationException` ([`TaskScheduler`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java)). It waits for the scheduler's on-ramp counter to drain to zero — every task that has entered the scheduler has finished processing — and does **not** transitively flush downstream-soldered schedulers ([`SequentialTaskScheduler`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/internal/SequentialTaskScheduler.java) / [`ConcurrentTaskScheduler`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/internal/ConcurrentTaskScheduler.java)). `flush()` is often paired with `startSquelching()` / `stopSquelching()`, the scheduler's "drop new tasks" toggle, when a caller must both stop accepting new work and drain the backlog: squelch new arrivals, flush the existing backlog, then stop squelching to resume acceptance. A module-wide flush walks each `ComponentWiring` in turn — [`DefaultEventIntakeModule.flush()`](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java) flushes its components in order — while squelching is applied separately by the callers that need it (for example the reconnect clear path in [`ReconnectCoordinator`](../../../../consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectCoordinator.java)). The combined pattern is intricate and a known candidate for future simplification.

The default `UncaughtExceptionHandler` (installed when none is supplied to the builder) logs at ERROR via log4j and the scheduler continues with the next task ([`ExceptionHandlers`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/ExceptionHandlers.java)); `DIRECT` / `DIRECT_THREADSAFE` schedulers, having no queue, invoke the handler on the calling thread but otherwise behave the same.

### InputWire and OutputWire

`InputWire<IN>` ([`InputWire`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java)) is the entry point of a component. It exposes three put-paths (`put(data)`, `offer(data)`, and `inject(data)`) that an edge selects between via its `SolderType`; their handoff semantics are described under [Soldering](#soldering).

Normally, modules do not call `BindableInputWire.bind(...)` directly. They obtain an `InputWire` via `componentWiring.getInputWire(MethodReference)`; `ComponentWiring` lazily creates a `BindableInputWire<IN, OUT>` ([`BindableInputWire`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java)) for the targeted method on first use, caches it, and binds it later when `componentWiring.bind(component)` is called. The framework method `BindableInputWire.bind(Function<IN, OUT>)` / `bindConsumer(Consumer<IN>)` is therefore an implementation detail that consensus-code normally doesn't touch directly.

`OutputWire<OUT>` ([`OutputWire`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java)) is the source of every connection out of a component. Soldering, filters, transformers, and splitters are all methods on `OutputWire`. A scheduler always owns one primary output wire (`getOutputWire()`); secondary output wires for additional output streams are created with `buildSecondaryOutputWire()`.

The two are fed differently: the scheduler forwards a handler's return value onto the primary wire automatically ([`BindableInputWire`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java)), whereas a secondary wire is pushed explicitly by the component's own code — a rare case, used only when business logic must emit something a return value cannot carry. Primary and secondary are output-wire terms; there is no secondary input wire.

### Soldering

Soldering connects an `OutputWire<T>` to one or more `InputWire<T>`s. The call is `outputWire.solderTo(inputWire)` (defaults to `SolderType.PUT`) or `outputWire.solderTo(inputWire, solderType)`. `SolderType` ([`SolderType`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/SolderType.java)) has three values:

- `PUT` — handoff via `put`; blocks the producing thread when the consumer is at capacity. The default and the only path that participates in backpressure.
- `INJECT` — handoff via `inject`; bypasses capacity. Used both to break cycles that would otherwise deadlock under backpressure and to deliver rare out-of-band control signals that must never block or be dropped.
- `OFFER` — handoff via `offer`; drops the item when the consumer is at capacity rather than blocking.

These at-capacity reactions take effect only when **hard backpressure** is enabled (off by default — see [Backpressure (wire level)](#backpressure-wire-level)); without it capacity is not enforced, so `put` does not block, `offer` does not drop, and the three handoffs differ only in how graph validation treats them.

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

The first line is the default `PUT` case — the regular data path from deduplicator to signature validator. The second uses `INJECT` so a clear command always reaches the deduplicator, even when its queue is full; clear commands are out-of-band control signals that must not be backpressured against the data path. `INJECT` is also used to break deadlocks in cyclic data flow — see [Backpressure (wire level)](#backpressure-wire-level). `OFFER` is rarer; the modules use it for the heartbeat handoff — each builds a heartbeat wire off the model (`buildHeartbeatWire`) and OFFER-solders it to its own scheduler — where a missed beat is preferable to a backed-up queue.

Soldering is treated as a one-shot operation performed at wiring assembly: there is no API for unsoldering, and the consensus layer treats the graph as immutable once `WiringModel.start()` has run (immutability is convention, not enforced by the framework).

### Transformers and splitters (`WireListSplitter`)

Transformers are lightweight, in-line conversions on the data flowing along an output wire. `OutputWire.buildFilter(...)` (predicate-based gating), `buildTransformer(...)` (function-based type change, `null` not forwarded), and `buildAdvancedTransformer(...)` (transformer with `inputCleanup` / `outputCleanup` hooks for resource management) each produce a new `OutputWire` that can be soldered or transformed further. `buildFilter` and `buildTransformer` run on an internal `DIRECT_THREADSAFE` scheduler, while `buildAdvancedTransformer` is a forwarding output wire with no scheduler of its own; either way the conversion executes on whichever thread happens to forward data along the upstream output wire.

Modules also use a free-standing `WireTransformer<A, B>` ([`WireTransformer`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireTransformer.java)) as a fan-out dispatcher — a single input wire whose output wire is soldered to several consumers. [`DefaultEventIntakeModule`](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java) constructs identity dispatchers for event-window updates and clear commands so each value is delivered to every component that subscribes:

```java
// consensus-event-intake-impl :: DefaultEventIntakeModule.initialize
this.eventWindowDispatcher =
        new WireTransformer<>(model, "EventWindowDispatcher", "event window", UnaryOperator.identity());
```

`WireListSplitter<T>` ([`WireListSplitter`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireListSplitter.java)) is the framework's list-splitting transformer: a `List<T>` arriving on its input wire produces one task per element on its output wire. It is built via `OutputWire.buildSplitter(...)`, and `ComponentWiring` exposes it as `getSplitOutput()` for any component whose primary output type is a list. For example, [`DefaultEventIntakeModule.validatedEventsOutputWire()`](../../../../consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java) returns the orphan buffer's split output so that downstream components receive one event at a time rather than a batch.

### WiringModel

`WiringModel` ([`WiringModel`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java)) owns every scheduler and every wire in a process. It is an interface (constructed via [`WiringModelBuilder`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModelBuilder.java)); concrete implementations are [`StandardWiringModel`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java) and [`DeterministicWiringModel`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/DeterministicWiringModel.java). Its responsibilities are:

- Hand out scheduler builders (`schedulerBuilder(name)`).
- Validate the assembled graph: `checkForCyclicalBackpressure`, `checkForIllegalDirectSchedulerUsage`, `checkForUnboundInputWires`.
- Generate Mermaid-style wiring diagrams for documentation and debugging (`generateWiringDiagram`).
- Expose process-wide infrastructure wires: a heartbeat wire (`buildHeartbeatWire`) and a health-monitor wire (`getHealthMonitorWire`) — the latter is the integration point with the queue-health subsystem described below.
- Drive the lifecycle: `start()` / `stop()` (`WiringModel` extends `Startable, Stoppable`).

A single `WiringModel` instance flows through the whole consensus layer: it is constructed once in [`ConsensusLayerFactory`](../../../../swirlds-platform-core/src/main/java/org/hiero/consensus/ConsensusLayerFactory.java) (via `WiringModelBuilder`), threaded into each module's `initialize(model, configuration, ...)` so the module can build its internal `ComponentWiring`s on it, and passed to [`ConsensusLayerWiring.wire`](../../../../swirlds-platform-core/src/main/java/org/hiero/consensus/ConsensusLayerWiring.java) so inter-module solders share the same graph. Reaching back into the model directly happens in two places: `ConsensusLayerWiring` solders the model-owned health-monitor wire to its consumers, and each module builds its own heartbeat wire off the model.

`stop()` is not a graceful drain: it stops the heartbeat scheduler and the schedulers that own dedicated threads, but does not wait for in-flight or queued tasks ([`StandardWiringModel`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java)). The lifecycle is one-shot — calling `start()` a second time throws an `IllegalStateException`. `DeterministicWiringModel` is reached via `WiringModelBuilder.deterministic()` and exists solely for testing and debugging; production always uses `StandardWiringModel`.

## Backpressure (wire level)

**Capacity** is a property of the *consumer*: each scheduler is built with an unhandled-task limit (`TaskSchedulerBuilder.withUnhandledTaskCapacity(...)`, supplied via the `TaskSchedulerConfiguration` passed to `ComponentWiring`). What that limit *does* depends on `platform.wiring.hardBackpressureEnabled` ([`WiringConfig`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java)), which defaults to `false` and is left off in production:

- **Off (the default).** The on-ramp counter only tracks depth and never blocks ([`StandardObjectCounter`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/StandardObjectCounter.java)); the limit is *not* enforced — `put` does not block and `offer` does not drop. It serves only as a **health threshold**. Production backpressure is therefore *soft*: it comes from the health monitor (below) detecting the growing backlog, not from wires blocking at capacity.
- **On.** A bounded, non-`DIRECT` scheduler gets a blocking on-ramp counter ([`BackpressureObjectCounter`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/counters/BackpressureObjectCounter.java)) and the `SolderType` reactions become real: `PUT` blocks the producer at capacity, `OFFER` drops, `INJECT` bypasses the limit. A `PUT` producer blocked on a full consumer cannot off-ramp its own task, so its counter fills and backpressure propagates upstream.

Cyclic data flow under `PUT` is a *runtime* deadlock hazard only when hard backpressure is on: a producer waiting on a consumer that is (transitively) waiting on it cannot make progress. Independently of the flag, the wiring model detects such cycles at assembly time (`checkForCyclicalBackpressure`, which logs at ERROR via [`CycleFinder`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/internal/analysis/CycleFinder.java)), and the standard remedy — flip exactly one cycle edge to `INJECT` — is applied defensively so the graph stays deadlock-safe if the flag is ever enabled. [`ConsensusLayerWiring`](../../../../swirlds-platform-core/src/main/java/org/hiero/consensus/ConsensusLayerWiring.java) does this on the genuine feedback loops between modules — for example the event-creator → event-intake edge, where created events flow back into intake and would otherwise close a backpressure cycle.

Regardless of the flag, `WiringModel.getHealthMonitorWire()` exposes how long each scheduler has been over capacity (`getUnprocessedTaskCount() > getCapacity()`), and the health monitor turns that into the unhealthy-duration reports that the rest of the consensus layer (event creation, gossip, transaction acceptance, PCES replay, …) reacts to. This file describes only the wire-level mechanism; the reaction side — what each subsystem does when the system is unhealthy — lives in `../topics/health-monitor-and-backpressure.md`.

A few specifics worth pinning down. The default `unhandledTaskCapacity` on the framework builder is `1` ([`AbstractTaskSchedulerBuilder`](../../../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/internal/AbstractTaskSchedulerBuilder.java)); consensus-layer schedulers always override this via `TaskSchedulerConfiguration`, so the default is effectively never used in production. The capacity is per-scheduler: every input wire on the same scheduler shares a single on-ramp counter, so the limit applies to total backlog across all inputs, not independently per wire.

`withExternalBackPressure(true)` tells the wiring model that insertion into this scheduler can block even though its capacity is unlimited, so that graph validation still treats it as a backpressure point. It is used only in test and benchmark code (`WiringBenchmark`, `SequentialTaskSchedulerTests`) and is not relevant for production: it cannot be set through `TaskSchedulerConfiguration`, so no `ComponentWiring`-built scheduler can enable it.

## Cross-references

- Topics: `../topics/health-monitor-and-backpressure.md` — reaction side of queue health.
- Module-API boundary: `../interfaces/consensus-execution-boundary.md` — where the future module-API-level backpressure differs from wire-level backpressure (see [Future state](#future-state-sidebar) below).
- Invariants: none directly.
- Rules: RUL-002 — the intake pipeline flushes component-by-component in topological order; this substrate is what it depends on.
- Decisions: ADR-005 — embedded future-event buffers.
- Glossary: `../../glossary.md` — entries for "wire", "scheduler", "soldering", "transformer".

## Future state (sidebar)

> A planned **module-API-level** backpressure (the `nextRound` pull; see the [overview's Future state](../overview.md#future-state)) will operate *above* wire-level backpressure, not in place of it. The two compose: wire-level backpressure stays the per-component mechanism, and the `nextRound` pull adds a throttle at the Consensus / Execution boundary. See [`../interfaces/consensus-execution-boundary.md`](../interfaces/consensus-execution-boundary.md) for the boundary view.
