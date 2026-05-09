---

title: Wiring framework
kind: architecture-topic
last_reviewed: TBD
------------------

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

A `TaskScheduler<OUT>` (`swirlds-component-framework :: TaskScheduler`) owns a queue, a thread-execution policy, and a built-in primary `OutputWire<OUT>`. Components are assembled by obtaining a scheduler from the wiring model, building one or more input wires on it (`buildInputWire`), and binding business logic to those input wires. The scheduler then forwards each handler's return value onto the primary output wire automatically.

`TaskSchedulerType` (`swirlds-component-framework :: TaskSchedulerType`) chooses the threading policy. Six values exist; see the enum's javadoc for the authoritative descriptions:

- `SEQUENTIAL` — fork-join pool, one task at a time, happens-before between consecutive tasks.
- `SEQUENTIAL_THREAD` — dedicated thread, one task at a time.
- `CONCURRENT` — fork-join pool, parallel, no ordering guarantee.
- `DIRECT` — execute on the calling thread; no queue. Subject to graph-walk validation rules.
- `DIRECT_THREADSAFE` — like `DIRECT` but the handler must itself be threadsafe.
- `NO_OP` — discards everything; useful for disabling a component without removing its wiring.

A representative current call site:

```java
// platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/GossipWiring.java
scheduler = model.<Void>schedulerBuilder("gossip")
        .configure(configuration.getConfigData(GossipWiringConfig.class).gossip())
        .build();
```

> **Delta vs. componentFramework.md:** the source doc shows `TaskScheduler<String> fooTaskScheduler = WiringModel.schedulerBuilder("Foo");`. In current code, `WiringModel` is an interface and `schedulerBuilder(...)` is an instance method that returns a `TaskSchedulerBuilder<O>` — not a `TaskScheduler<O>`. The current shape always terminates in `.build()`, as in the `GossipWiring` snippet above. The source doc also narrates only Sequential / Direct / Concurrent; current `TaskSchedulerType` has six values (the additional three are `SEQUENTIAL_THREAD`, `DIRECT_THREADSAFE`, `NO_OP`).

[TBD: question for engineer — Inside `WiringModel`, six `TaskSchedulerType` values exist. Is one canonical for new consensus-layer components, or is the choice always topic-specific?]

[TBD: question for engineer — `flush()` is opt-in via `withFlushingEnabled(true)`. Does it wait for queued-but-not-yet-started tasks to drain, only for in-flight tasks to complete, or both? And does it transitively wait for downstream-soldered schedulers?]

### InputWire and OutputWire

`InputWire<IN>` (`swirlds-component-framework :: InputWire`) is the entry point of a component. It exposes three put-paths: `put(data)` blocks the caller if the scheduler's queue is at capacity, `offer(data)` returns `false` rather than blocking, and `inject(data)` bypasses capacity entirely. These three paths correspond to the three `SolderType` values used when wires are connected via soldering — see [Backpressure (wire level)](#backpressure-wire-level).

`buildInputWire(name)` actually returns a `BindableInputWire<IN, OUT>` (`swirlds-component-framework :: BindableInputWire`); callers attach business logic with `bind(Function<IN, OUT>)` (return value forwarded to the primary output wire) or `bindConsumer(Consumer<IN>)` (no return value forwarded).

`OutputWire<OUT>` (`swirlds-component-framework :: OutputWire`) is the source of every connection out of a component. Soldering, filters, transformers, and splitters are all methods on `OutputWire`. A scheduler always owns one primary output wire (`getOutputWire()`); secondary output wires for fan-out side-channels are created with `buildSecondaryOutputWire()`.

[TBD: question for engineer — `OutputWire` exposes both a primary (auto-forwarded from the bound function's return) and **secondary** output wires (`buildSecondaryOutputWire`, used heavily in `GossipWiring`). Is the convention that a component owns a primary wire when it has a single canonical output type, and reaches for secondaries only for fan-out side-channels — or is there a different rule of thumb?]

[TBD: question for engineer — Do secondary output wires participate in `flush()` and squelching the same way primary wires do?]

### Soldering

Soldering connects an `OutputWire<T>` to one or more `InputWire<T>`s. The call is `outputWire.solderTo(inputWire)` (defaults to `SolderType.PUT`) or `outputWire.solderTo(inputWire, solderType)`. `SolderType` (`swirlds-component-framework :: SolderType`) has three values:

- `PUT` — handoff via `put`; blocks the producing thread when the consumer is at capacity. The default and the only path that participates in backpressure.
- `INJECT` — handoff via `inject`; bypasses capacity. Used to break cycles that would otherwise deadlock under backpressure.
- `OFFER` — handoff via `offer`; drops the item when the consumer is at capacity rather than blocking.

[TBD: question for engineer — Is soldering performed once at wiring assembly time and immutable thereafter, or can wires be soldered/unsoldered after `WiringModel.start()` has been called?]

[TBD: question for engineer — `OFFER` drops on a full input wire. Is the drop silent, counted as a metric, or logged? And is there any documented use of `OFFER` in the consensus layer today?]

### Transformers and splitters (`WireListSplitter`)

Transformers are lightweight, in-line conversions on the data flowing along an output wire. `OutputWire.buildFilter(...)` (predicate-based gating), `buildTransformer(...)` (function-based type change, `null` not forwarded), and `buildAdvancedTransformer(...)` (transformer with `inputCleanup` / `outputCleanup` hooks for resource management) each produce a new `OutputWire` that can be soldered or transformed further. They run on a `DIRECT_THREADSAFE` scheduler internally, so they execute on whichever thread happens to forward data along the upstream output wire.

`WireListSplitter<T>` (`swirlds-component-framework :: WireListSplitter`) is the canonical concrete transformer: a `List<T>` arriving on its input wire produces one task per element on its output wire. It is built via `OutputWire.buildSplitter(...)`.

[TBD: question for engineer — `AdvancedTransformation` adds `inputCleanup`/`outputCleanup` hooks for resource management. Is this used anywhere in the consensus layer today, or is it speculative API surface?]

### WiringModel

`WiringModel` (`swirlds-component-framework :: WiringModel`) owns every scheduler and every wire in a process. It is an interface (constructed via `WiringModelBuilder`); concrete implementations are `StandardWiringModel` and `DeterministicWiringModel`. Its responsibilities are:

- Hand out scheduler builders (`schedulerBuilder(name)`).
- Validate the assembled graph: `checkForCyclicalBackpressure`, `checkForIllegalDirectSchedulerUsage`, `checkForUnboundInputWires`.
- Generate Mermaid-style wiring diagrams for documentation and debugging (`generateWiringDiagram`).
- Expose process-wide infrastructure wires: a heartbeat wire (`buildHeartbeatWire`) and a health-monitor wire (`getHealthMonitorWire`) — the latter is the integration point with the queue-health subsystem described below.
- Drive the lifecycle: `start()` / `stop()` (`WiringModel` extends `Startable, Stoppable`).

[TBD: question for engineer — On `WiringModel.stop()`, do queued tasks drain or are they dropped? Can a model be re-started after `stop()`, or is the lifecycle one-shot?]

[TBD: question for engineer — `WiringModelBuilder.deterministic()` switches to a deterministic mode used for testing. Is this exclusively a test concern, or is there a production use of `DeterministicWiringModel`?]

## Backpressure (wire level)

Wire-level backpressure is per-scheduler and configured at build time via `TaskSchedulerBuilder.withUnhandledTaskCapacity(...)`. When a producing thread solders to a consumer with `SolderType.PUT` and the consumer is at capacity, the producer's call into `put` blocks until the consumer drains. `INJECT` bypasses the cap entirely; `OFFER` drops the item rather than blocking. The three `SolderType` values exist precisely so the wiring author can choose, edge-by-edge, which of these three behaviours is appropriate.

The principal hazard is cyclic data flow under `PUT`: a producer waiting on a downstream consumer that is itself waiting (transitively) on the producer will deadlock. The wiring model detects cyclic backpressure during validation (`checkForCyclicalBackpressure`) and logs a warning. The standard remedy is to flip exactly one edge in the cycle to `INJECT`, so the cycle no longer transmits backpressure pressure.

Backpressure feeds queue-health detection. `WiringModel.getHealthMonitorWire()` exposes the duration each scheduler has been over its capacity, and the health monitor turns that signal into the unhealthy-duration reports that the rest of the consensus layer (event creation, gossip, transaction acceptance, PCES replay, …) reacts to. This file describes only the wire-level mechanism; the reaction side — what each subsystem does when the system is unhealthy — lives in `../topics/health-monitor-and-backpressure.md` *(planned)* and the existing `platform-sdk/docs/core/health-monitor.md`.

[TBD: question for engineer — Default `unhandledTaskCapacity` is `1` per the builder; in practice consensus-layer schedulers override this. Is there a documented default policy, or is it tuned per scheduler?]

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
- Glossary: [`../../../hashgraphGlossary.md`](../../../hashgraphGlossary.md) — note: the wiring-specific terms ("wire", "scheduler", "soldering", "transformer") are not yet in the glossary.

## Future state (sidebar)

> The consensus-layer proposal (`platform-sdk/docs/proposals/consensus-layer/Consensus-Layer.md`) introduces a **module-API-level** backpressure that operates above wire-level backpressure, not in place of it. Execution drives a `nextRound` pull, which throttles Consensus end-to-end across the module boundary. The two layers compose: wire-level backpressure remains the per-component mechanism; the `nextRound` pull is an additional throttle at the Consensus / Execution boundary. See `../interfaces/consensus-execution-boundary.md` *(planned)* for where the module-API backpressure differs from the wire-level mechanism described above.
