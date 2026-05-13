---
title: Wiring framework
kind: architecture-topic
last_reviewed: TBD
---

# Wiring framework

## Responsibilities

The wiring framework is the substrate the consensus layer is built on. It supplies the primitives — task schedulers, typed input and output wires, soldering, and transformers — that every consensus-layer topic uses to express data flow between components. The framework owns concurrency, queueing, and graph validation. It does **not** own topic logic; per-topic files (gossip, event-intake, hashgraph, …) describe what runs on which scheduler and how their wires connect.

What the framework provides:

- **Thread confinement of business logic** — each scheduler decides which thread executes the bound handler; business logic does not need synchronization for state owned by a single component.
- **Type-safe data flow** — input and output wires are generic; soldering is checked at the type level.
- **Graph validation** — cycle detection, illegal `DIRECT` usage detection, unbound input wires, and Mermaid-style diagram generation.
- **Queue-health observability** — per-scheduler queue depth feeds the health monitor wire that the rest of the consensus layer subscribes to.

## Core abstractions

### TaskScheduler / TaskSchedulerType

A `TaskScheduler<OUT>` (`swirlds-component-framework :: TaskScheduler`) owns a queue, a thread-execution policy, and a built-in primary `OutputWire<OUT>`. The framework primitive for obtaining one is `WiringModel.schedulerBuilder(name).withType(...).build()`. Consensus-layer modules rarely call that directly: the canonical wrapper is `ComponentWiring<COMPONENT, OUT>` (`swirlds-component-framework :: ComponentWiring`), which combines a scheduler with method-reference-based input-wire creation and a deferred binding step.

`TaskSchedulerType` (`swirlds-component-framework :: TaskSchedulerType`) chooses the threading policy. Six values exist; see the enum's javadoc for the authoritative descriptions:

- `SEQUENTIAL` — fork-join pool, one task at a time, happens-before between consecutive tasks.
- `SEQUENTIAL_THREAD` — dedicated thread, one task at a time.
- `CONCURRENT` — fork-join pool, parallel, no ordering guarantee.
- `DIRECT` — execute on the calling thread; no queue. Subject to graph-walk validation rules.
- `DIRECT_THREADSAFE` — like `DIRECT` but the handler must itself be threadsafe.
- `NO_OP` — discards everything; useful for disabling a component without removing its wiring.

Components are assembled in three steps inside each module's `initialize(...)` method. The pattern below is taken from `DefaultEventIntakeModule` (`consensus-event-intake-impl :: DefaultEventIntakeModule`), and is representative of every default module:

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

The framework primitive (`WiringModel.schedulerBuilder(...).build()`) is still used directly for the small set of cases that do not fit the `ComponentWiring` shape — for example, `PassThroughWiring` constructs a no-op identity scheduler, and `GossipWiring` self-builds its scheduler with its own `GossipWiringConfig` (treat the latter as an exception, not a template).

> **Delta vs. componentFramework.md:** the source doc shows `TaskScheduler<String> fooTaskScheduler = WiringModel.schedulerBuilder("Foo");`. In current code, `WiringModel` is an interface and `schedulerBuilder(...)` is an instance method that returns a `TaskSchedulerBuilder<O>` — not a `TaskScheduler<O>`. The current shape always terminates in `.build()`, and in the canonical consensus-layer pattern that call lives inside `ComponentWiring`, not in the module's wiring code. The source doc also narrates only Sequential / Direct / Concurrent; current `TaskSchedulerType` has six values (the additional three are `SEQUENTIAL_THREAD`, `DIRECT_THREADSAFE`, `NO_OP`).

[TBD: question for engineer — Six `TaskSchedulerType` values exist. Is one canonical for new consensus-layer components (e.g., `SEQUENTIAL`), or is the choice always topic-specific and entirely delegated to `*WiringConfig`?]

[TBD: question for engineer — `flush()` is opt-in via `withFlushingEnabled(true)`. Does it wait for queued-but-not-yet-started tasks to drain, only for in-flight tasks to complete, or both? And does it transitively wait for downstream-soldered schedulers? `DefaultEventIntakeModule.flush()` calls `flush()` on each `ComponentWiring` sequentially — is the intent module-wide drain?]

### InputWire and OutputWire

`InputWire<IN>` (`swirlds-component-framework :: InputWire`) is the entry point of a component. It exposes three put-paths: `put(data)` blocks the caller if the scheduler's queue is at capacity, `offer(data)` returns `false` rather than blocking, and `inject(data)` bypasses capacity entirely. These three paths correspond to the three `SolderType` values used when wires are connected via soldering — see [Backpressure (wire level)](#backpressure-wire-level).

In the canonical pattern, modules do not call `BindableInputWire.bind(...)` directly. They obtain an `InputWire` via `componentWiring.getInputWire(MethodReference)`; `ComponentWiring` lazily creates a `BindableInputWire<IN, OUT>` (`swirlds-component-framework :: BindableInputWire`) for the targeted method on first use, caches it, and binds it later when `componentWiring.bind(component)` is called. The framework method `BindableInputWire.bind(Function<IN, OUT>)` / `bindConsumer(Consumer<IN>)` is therefore an implementation detail that consensus-layer modules normally don't touch directly.

`OutputWire<OUT>` (`swirlds-component-framework :: OutputWire`) is the source of every connection out of a component. Soldering, filters, transformers, and splitters are all methods on `OutputWire`. A scheduler always owns one primary output wire (`getOutputWire()`); secondary output wires for fan-out side-channels are created with `buildSecondaryOutputWire()`. When a component's primary output type is a `List<T>`, `ComponentWiring.getSplitOutput()` returns a per-element output wire (built internally via `buildSplitter(...)`) — `DefaultEventIntakeModule.validatedEventsOutputWire()` returns the orphan-buffer's split output for this reason.

[TBD: question for engineer — `OutputWire` exposes both a primary (auto-forwarded from the bound function's return) and **secondary** output wires (`buildSecondaryOutputWire`). Is the convention that a component owns a primary wire when it has a single canonical output type, and reaches for secondaries only for fan-out side-channels — or is there a different rule of thumb?]

[TBD: question for engineer — Do secondary output wires participate in `flush()` and squelching the same way primary wires do?]

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

[TBD: question for engineer — Is soldering performed once at wiring assembly time and immutable thereafter, or can wires be soldered/unsoldered after `WiringModel.start()` has been called?]

[TBD: question for engineer — `OFFER` drops on a full input wire. Is the drop silent, counted as a metric, or logged? `PlatformWiring` uses it for the heartbeat handoff; is that the only documented use today?]

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

[TBD: question for engineer — `AdvancedTransformation`'s `inputCleanup`/`outputCleanup` hooks are used here for reservation management. Are there other documented use cases, or is reservation handoff the canonical pattern?]

### WiringModel

`WiringModel` (`swirlds-component-framework :: WiringModel`) owns every scheduler and every wire in a process. It is an interface (constructed via `WiringModelBuilder`); concrete implementations are `StandardWiringModel` and `DeterministicWiringModel`. Its responsibilities are:

- Hand out scheduler builders (`schedulerBuilder(name)`).
- Validate the assembled graph: `checkForCyclicalBackpressure`, `checkForIllegalDirectSchedulerUsage`, `checkForUnboundInputWires`.
- Generate Mermaid-style wiring diagrams for documentation and debugging (`generateWiringDiagram`).
- Expose process-wide infrastructure wires: a heartbeat wire (`buildHeartbeatWire`) and a health-monitor wire (`getHealthMonitorWire`) — the latter is the integration point with the queue-health subsystem described below.
- Drive the lifecycle: `start()` / `stop()` (`WiringModel` extends `Startable, Stoppable`).

A single `WiringModel` instance flows through the whole platform: it is constructed once by `PlatformComponents.create(...)`, passed into each module's `initialize(model, configuration, ...)` so the module can build its internal `ComponentWiring`s on it, and threaded into `PlatformWiring.wire(...)` so inter-module solders share the same graph. Soldering against the model-owned heartbeat and health-monitor wires is one of the few places `PlatformWiring` reaches back into the model directly.

[TBD: question for engineer — On `WiringModel.stop()`, do queued tasks drain or are they dropped? Can a model be re-started after `stop()`, or is the lifecycle one-shot?]

[TBD: question for engineer — `WiringModelBuilder.deterministic()` switches to a deterministic mode used for testing. Is this exclusively a test concern, or is there a production use of `DeterministicWiringModel`?]

## Backpressure (wire level)

Wire-level backpressure is per-scheduler and configured at build time. In the canonical consensus-layer setup, capacity comes from the `TaskSchedulerConfiguration` record passed to `ComponentWiring`'s constructor; the underlying framework method is `TaskSchedulerBuilder.withUnhandledTaskCapacity(...)`. When a producing thread solders to a consumer with `SolderType.PUT` and the consumer is at capacity, the producer's call into `put` blocks until the consumer drains. `INJECT` bypasses the cap entirely; `OFFER` drops the item rather than blocking. The three `SolderType` values exist precisely so the wiring author can choose, edge-by-edge, which of these three behaviours is appropriate.

The principal hazard is cyclic data flow under `PUT`: a producer waiting on a downstream consumer that is itself waiting (transitively) on the producer will deadlock. The wiring model detects cyclic backpressure during validation (`checkForCyclicalBackpressure`) and logs a warning. The standard remedy is to flip exactly one edge in the cycle to `INJECT`, so the cycle no longer transmits backpressure pressure. Both module-internal and inter-module solders use this: `DefaultEventIntakeModule` injects on every control edge into the intake components (event-window updates, clear commands), and `PlatformWiring` injects on the persisted-event → gossip edge and the event-creator → event-intake feedback for the same reason.

Backpressure feeds queue-health detection. `WiringModel.getHealthMonitorWire()` exposes the duration each scheduler has been over its capacity, and the health monitor turns that signal into the unhealthy-duration reports that the rest of the consensus layer (event creation, gossip, transaction acceptance, PCES replay, …) reacts to. This file describes only the wire-level mechanism; the reaction side — what each subsystem does when the system is unhealthy — lives in `../topics/health-monitor-and-backpressure.md` *(planned)* and the existing `platform-sdk/docs/core/health-monitor.md`.

[TBD: question for engineer — Default `unhandledTaskCapacity` is `1` per the builder; in practice consensus-layer schedulers override this through `PlatformSchedulersConfig`. Is there a documented default policy, or is it tuned per scheduler?]

[TBD: question for engineer — `withExternalBackPressure(true)` declares that backpressure is supplied externally. What does this actually change beyond cycle-validation behaviour — does it also affect the unhealthy-duration calculation in the health monitor?]

[TBD: question for engineer — Is `unhandledTaskCapacity` strictly per-scheduler, or can different input wires on the same scheduler have independent capacities? (Code suggests per-scheduler — confirm.)]

[TBD: question for engineer — The default `UncaughtExceptionHandler` "logs and continues" per the source doc. Confirm the current default behaviour, and document whether `DIRECT` / `DIRECT_THREADSAFE` schedulers behave the same as queued schedulers on uncaught exceptions.]

## Cross-references

- Topics: `../topics/health-monitor-and-backpressure.md` *(planned)* — reaction side of queue health.
- Source doc (long-form treatment): `platform-sdk/docs/components/componentFramework.md`.
- Related core doc: `platform-sdk/docs/core/health-monitor.md` — existing reaction-side detail (event creation throttling, gossip permits, PCES replay gating).
- Module-API boundary: `../interfaces/consensus-execution-boundary.md` *(planned)* — where the proposal-level module-API backpressure differs from wire-level backpressure (see [Future state](#future-state-sidebar) below).
- Invariants: [TBD: INV-NNN once invariants.md catalog populates].
- Decisions: [TBD: ADR-NNN once decisions/ catalog populates].
- Glossary: `../../glossary.md` *(planned)* — entries for "wire", "scheduler", "soldering", "transformer".

## Future state (sidebar)

> The consensus-layer proposal (`platform-sdk/docs/proposals/consensus-layer/Consensus-Layer.md`) introduces a **module-API-level** backpressure that operates above wire-level backpressure, not in place of it. Execution drives a `nextRound` pull, which throttles Consensus end-to-end across the module boundary. The two layers compose: wire-level backpressure remains the per-component mechanism; the `nextRound` pull is an additional throttle at the Consensus / Execution boundary. See `../interfaces/consensus-execution-boundary.md` *(planned)* for where the module-API backpressure differs from the wire-level mechanism described above.
