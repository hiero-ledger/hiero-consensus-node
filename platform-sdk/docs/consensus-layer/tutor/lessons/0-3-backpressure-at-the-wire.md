---
lesson_id: 0-3-backpressure-at-the-wire
cluster: '0'
title: Backpressure at the wire
prerequisites: [0-2-wires-and-soldering]
kb_refs:
  topics: [wiring-framework]
  concepts: []
  invariants: []
  glossary_terms: []
learning_objectives:
  - Explain how an `unhandledTaskCapacity` and a chain of `PUT`-soldered edges combine to transmit a slow consumer's slowness back up the graph.
  - Identify a `PUT`-only cycle in a wiring graph and pick the edge to flip to `INJECT` to break it, using the consensus layer's eventCreator-eventIntake-PCES cycle as the worked example.
  - Trace the queue-depth signal from a scheduler through `WiringModel.getHealthMonitorWire()` to the consensus-layer subsystems that react to unhealthy duration.
estimated_read_minutes: 10
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---

# Backpressure at the wire

## Where we are

[Lesson 0-1](0-1-task-schedulers.md) introduced the `TaskScheduler` and its queue. [Lesson 0-2](0-2-wires-and-soldering.md) introduced the typed wires soldered between schedulers and named the three `SolderType` values (`PUT`, `INJECT`, `OFFER`) without yet mapping them onto a runtime story. This lesson takes the queue capacity from 0-1 and the `SolderType` menu from 0-2 and develops the wire-level backpressure mechanism: how slowness propagates backwards along `PUT` edges, the cyclic-backpressure hazard the model detects at startup, and the out-of-band health-monitor signal that drove [the Pass 1 stress scenario](pass1-4-event-creation-under-stress.md). It is the third of four lessons in cluster 0; the cluster's synthesis comes in [lesson 0-4](0-4-wiring-runtime-synthesis.md).

## Motivating problem

A scheduler's queue is bounded. The wiring framework sets `unhandledTaskCapacity` to a small finite number on every queued scheduler precisely so that a runaway producer cannot push a slow consumer's queue to infinity and OOM the JVM. The interesting question is what should happen when the queue *does* fill up. If the producer keeps feeding it, the system loses data, leaks memory, or crashes. If the producer stops, the slowness has to be communicated back to *its* producer too, and so on, until the slowness reaches a point in the graph where there is something useful to do with it — slow down event creation, defer a sync round, throttle the transaction acceptor. Communicating that slowness backwards across the graph, edge by edge, is wire-level backpressure.

The mechanism comes with two complications the framework has to address head-on. First, naive backwards propagation can deadlock: if the slowness travels along a cycle in the graph, every component on the cycle ends up waiting for some other component on the same cycle to drain. Second, the slowness is also a *signal* — the rest of the consensus layer wants to know when a scheduler has been over capacity for a while, so it can shed load before the local backpressure cascade has propagated all the way to the load source. Wire-level backpressure has to support both the propagation and the signal without confusing them.

## Concept

Backpressure has two pieces in the wiring framework: a **per-scheduler** capacity (a single integer per `TaskScheduler`, `unhandledTaskCapacity`) and a **per-edge** policy (the `SolderType` chosen at each `solderTo` call). The capacity decides when an input wire is "full"; the `SolderType` decides what the producer does about it.

- `PUT` participates in backpressure: the producer's call into the consumer's `put(...)` blocks until the consumer's queue has room, holding the producer's scheduler thread for the duration of the block. Because the producer's thread is blocked, the producer's *own* queue stops draining, which eventually backpressures the producer's producers in turn. This is how slowness propagates backwards across a chain of `PUT` edges.
- `INJECT` does not participate: `inject(...)` bypasses the capacity check entirely. The producer never blocks on this edge; the consumer's queue is allowed to grow past `unhandledTaskCapacity`. `INJECT` is how you put an edge *outside* the backpressure circuit on purpose.
- `OFFER` does not participate either: `offer(...)` returns when the queue is full rather than blocking, dropping the item silently. `OFFER` is for low-priority streams where losing a value under load is acceptable.

The mental model is "backpressure is a property of the edge, not the component." A single `OutputWire<T>` may solder `PUT` to one consumer, `INJECT` to another, and `OFFER` to a third; the three downstream consumers see three different relationships with the same producer. The detailed per-value semantics are documented on [`SolderType`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/SolderType.java) and the put-paths on [`InputWire`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java); see also the [Backpressure (wire level)](../../architecture/topics/wiring-framework.md#backpressure-wire-level) section of the wiring-framework topic.

## How it works

Four moving parts: capacity, transmission, the cyclic-backpressure hazard, and the health-monitor wire.

**Capacity.** `TaskSchedulerBuilder.withUnhandledTaskCapacity(long)` sets the maximum number of unhandled tasks the scheduler will hold. The framework default is `1`, but in practice every consensus-layer scheduler overrides this through `.configure(...)` from a `TaskSchedulerConfiguration` value, the same mechanism that sets `TaskSchedulerType` (lesson 0-1). Capacity is a property of the scheduler, not of the input wire — many input wires can share one scheduler (lesson 0-2), and the capacity bounds the union of their unhandled tasks. The two `DIRECT` types have no queue and no meaningful capacity; the value is ignored for them.

**Transmission.** When a `PUT`-soldered consumer is at capacity, the producer's bound function blocks inside the consumer's `put(...)` until a slot opens. While the producer's thread is blocked, the producer's own queue stops being serviced, so its capacity tightens; once that capacity is reached, *its* producers blocking on `PUT` edges start to block too. Slowness ripples backwards across `PUT` edges, hop by hop, in the natural direction of data flow. `INJECT` and `OFFER` edges do not participate, so they cleanly partition the graph: the connected components reachable from a given consumer along `PUT` edges only are the components whose schedulers will share its slowness.

**Cyclic-backpressure hazard.** A `PUT`-only cycle through schedulers `A → B → C → A` deadlocks under load: `A` blocks on `B`'s capacity, `B` blocks on `C`'s capacity, and `C` blocks on `A`'s capacity, with no thread free to drain anyone. The wiring model checks for this at validation time. [`WiringModel.checkForCyclicalBackpressure()`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java) walks the graph using `CycleFinder` and, if it finds a `PUT`-only cycle, logs a message that fails standard platform tests. The check is a static graph property — it does not catch cycles that form only at runtime through dynamic dispatch — and it does not refuse the graph at runtime; the failure surfaces in tests, which is where the wiring author is expected to notice it. The remedy is structural: flip exactly one edge in the cycle from `PUT` to `INJECT`, so the cycle no longer transmits backpressure pressure. Which edge is the right one to flip is a design call — the developer needs to know that the consumer on the flipped edge will not be overwhelmed when its capacity is bypassed, because that consumer is no longer protected by the framework.

**Health-monitor wire.** Backpressure also has to be observable out-of-band. [`WiringModel.getHealthMonitorWire()`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java) returns an `OutputWire<Duration>` that emits the longest concurrent unhealthy duration across all schedulers — `Duration.ZERO` when every scheduler is currently healthy, otherwise the wall-clock time the worst-off scheduler has been over capacity. The wire is fed by an internal `HealthMonitor` scheduler the wiring model builds during construction; that scheduler is a `SEQUENTIAL` scheduler ticked by an internal heartbeat wire (`StandardWiringModel`). Crucially, `getHealthMonitorWire()` is *not* a `PUT` consumer of the queues it observes — it samples them on a heartbeat. It exists exactly to communicate "a scheduler is unhealthy" without being soldered into the data flow it is observing, so it cannot itself be deadlocked by the condition it is reporting on. The reaction side — what each subsystem does when the duration exceeds its threshold — lives in cluster B and in the existing `platform-sdk/docs/core/health-monitor.md`; this lesson stops at the wire.

## Worked example

The cleanest cycle-break in the consensus layer's main wiring sits in [`PlatformWiring`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java). Three solders form the relevant fragment:

```java
// Avoid using events as parents before they are persisted
writtenEventOutputWire.solderTo(components.eventCreatorModule().orderedEventInputWire());

// ... later in the same constructor ...

components
        .eventCreatorModule()
        .createdEventOutputWire()
        .solderTo(components.eventIntakeModule().nonValidatedEventsInputWire(), INJECT);
```

Trace the cycle. PCES emits a persisted event on `writtenEventOutputWire`; that solders into the event creator with default `PUT` so the creator only uses persisted events as parents. The event creator emits a newly created event on `createdEventOutputWire`; that solders into event intake. Event intake validates and routes the event; once it is durable, PCES sees it next on `writtenEventOutputWire`. The path is *event creator → event intake → … → PCES → event creator*: a cycle. If every edge on the cycle were `PUT`, a backed-up event-intake queue would block the event creator on the second solder, which would slow the rate at which the creator finishes its bound task, which would tighten the creator's capacity, which would block PCES on the first solder, which would tighten PCES's capacity, which would block whatever pushes events into PCES — and on the next round of created events, intake's queue is still full, so the creator blocks again, and the cycle has nowhere to go. Standard deadlock.

The remedy in the live code is to flip the second solder to `INJECT`. Bypassing capacity on `eventCreator → eventIntake` removes the cycle's backpressure circuit; intake's queue is now allowed to grow past its nominal capacity in this direction, but the creator never blocks, so the deadlock condition cannot form. The choice of *which* edge to flip is the design call: intake is the right place to absorb the bypass because intake fans out into the rest of the graph and its downstream queues are themselves bounded; it would have been wrong to flip PCES's `writtenEventOutputWire → eventCreator` edge instead, because that `PUT` is doing real work — it makes the creator wait until events are durable before they are used as parents, which is the property that protects the network against branching. Backpressure choice is wired into safety choices.

The same constructor also illustrates the health-monitor wire's consumer side. A few lines below the persisted-event solders:

```java
components
        .model()
        .getHealthMonitorWire()
        .solderTo(components.eventCreatorModule().healthStatusInputWire());

components
        .model()
        .getHealthMonitorWire()
        .solderTo(components.gossipModule().healthStatusInputWire());

components
        .model()
        .getHealthMonitorWire()
        .solderTo("executionHealthInput", "healthyDuration", execution::reportUnhealthyDuration);
```

A single `OutputWire<Duration>` fans out to three consumers — event creator, gossip, and execution — each of which decides locally what unhealthy duration means for its own work. Note that these solders take the default `PUT`: the health monitor's per-tick output is small and the consumers are sequential, so transmitting backpressure on this wire is fine. The cycle-deadlock argument from above does not apply because the health monitor is not soldered into the data flow it is observing.

## Code anchor

- [`TaskSchedulerBuilder.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerBuilder.java) — the `withUnhandledTaskCapacity(long)` declaration; companion `withFlushingEnabled` and `withExternalBackPressure` knobs are also defined here.
- [`InputWire.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java) — the three put-paths that the three `SolderType` values select between.
- [`SolderType.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/SolderType.java) — the per-value javadoc is the authoritative description of `PUT`, `INJECT`, `OFFER` participation in backpressure.
- [`WiringModel.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java) — `checkForCyclicalBackpressure()` and `getHealthMonitorWire()` are declared here; the javadoc on the former describes the "logs a message that fails standard platform tests" behaviour, and the javadoc on `getHealthMonitorWire` is the authoritative description of the unhealthy-duration semantics.
- [`TraceableWiringModel.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java) — the `checkForCyclicalBackpressure()` implementation delegates to `CycleFinder.checkForCyclicalBackPressure(...)`, which is the actual graph walk.
- [`StandardWiringModel.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java) — the `HealthMonitor` scheduler is constructed in the model's constructor and ticked by an internal heartbeat wire; this is where `getHealthMonitorWire()` gets its values.
- [`PlatformWiring.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java) — the cycle-break solder on `eventCreator.createdEventOutputWire() → eventIntake.nonValidatedEventsInputWire()` and the three `getHealthMonitorWire().solderTo(...)` consumers shown above.

## Delta callout

No `delta-map/wiring-framework.md` entry exists yet. The wiring-framework topic's [Future state sidebar](../../architecture/topics/wiring-framework.md#future-state-sidebar) describes a module-API-level backpressure mechanism on the Consensus / Execution boundary that sits *above* — not in place of — the wire-level mechanism this lesson covers. The module-API-level mechanism is a `nextRound`-style pull, conceptually distinct from the per-scheduler capacity and per-edge `SolderType` story here. Wire-level backpressure remains the per-component substrate; the module-API-level pull is a second throttle at the boundary. The interface-level details belong to the planned `architecture/interfaces/consensus-execution-boundary.md`.

## Comprehension prompt

Two openings to take into the tutor chat. First: imagine `checkForCyclicalBackpressure` flagged a new `PUT`-only cycle through schedulers `A → B → C → A` that you have just added to the wiring graph. Both `A → B` and `C → A` carry safety-critical ordering (the consumer needs the producer's value to have happened first), while `B → C` carries an observability stream that can tolerate occasional unbounded queueing. Walk through which edge you would flip to `INJECT`, and what you would have to confirm about the consumer on that edge before being comfortable with the choice. Second: the health-monitor wire is fed on a heartbeat rather than being soldered into the queues it observes. What concretely would go wrong if the health monitor instead consumed from each scheduler's queue directly with `PUT`, and which property of `getHealthMonitorWire()`'s contract would that change?

## Open questions

> - [TBD] The framework default for `unhandledTaskCapacity` is `1`, but every consensus-layer scheduler observed so far overrides this through configuration. Is there a documented default policy for new components, or is the value always tuned per scheduler? SME confirmation would let this lesson promote a recommended starting value rather than naming the framework default.
> - [TBD] `TaskSchedulerBuilder.withExternalBackPressure(true)` declares that backpressure is supplied externally. The wiring-framework topic flags this open: what does it actually change beyond cycle-validation behaviour — does it also affect the unhealthy-duration calculation in the health monitor? This lesson does not mention it; the answer would belong in the **How it works** section under capacity.
> - [TBD] Is `unhandledTaskCapacity` strictly per-scheduler, or can different input wires on the same scheduler have independent capacities? The topic file flags this and the code suggests per-scheduler — confirm with SME so the lesson can state it as a hard rule rather than as the *de facto* observation it is now.
> - [TBD] The wiring-framework topic file describes `checkForCyclicalBackpressure` as logging "a warning" (line 101 of the topic). The actual `WiringModel` javadoc says it logs a message that "will fail standard platform tests" — i.e., the failure mode is test failure, not a startup refusal. The lesson uses the javadoc wording; the topic file should be re-pinned the next time it is touched.
> - [TBD] `WiringModelBuilder.withHardBackpressureEnabled(...)` exists and `TraceableWiringModel` records `isHardBackpressureEnabled()` in its constructor. What does this flag do at runtime — does it convert the cycle-detector's "log + fail tests" behaviour into a startup refusal, or does it tighten something else? Not currently in the wiring-framework topic; SME confirmation would either pull it into this lesson or close it as a non-load-bearing internal concern.
> - [TBD] `OFFER` was framed in lesson 0-2 as the heartbeat-style observability path; the only `OFFER` site this run found in `PlatformWiring` is the heartbeat-into-platform-monitor edge (line 127). Is there documented guidance on when `OFFER` is the right choice beyond that single pattern, and is the drop silent / metered / logged? The topic file flags the latter as open.
> - [TBD] No `glossary.md` exists at the consensus-layer KB root yet. The terms introduced or sharpened in this lesson — *unhandledTaskCapacity*, *backpressure transmission*, *cyclic backpressure*, *health-monitor wire*, *unhealthy duration* — should be promoted to the glossary once it is created. Same gap is flagged in 0-1 and 0-2.
> - [TBD] No `invariants.md` catalog exists yet, so `kb_refs.invariants` is empty. Two candidate invariants surface here: *the wiring graph contains no `PUT`-only cycles* (enforced via `checkForCyclicalBackpressure` in the test suite); *`getHealthMonitorWire()` does not solder into the data-flow graph it observes*. Cite as `INV-NNN` once registered.
> - [TBD] No `delta-map/wiring-framework.md` exists. The Delta callout above defers to the topic file's Future-state sidebar and to the planned `architecture/interfaces/consensus-execution-boundary.md`; this is the correct authority for now but should be replaced with a delta-map link when one lands.

## Where we're going next

The next and final cluster-0 lesson, [0-4 Wiring runtime synthesis](0-4-wiring-runtime-synthesis.md), takes the substrate this cluster has built — schedulers (0-1), wires and soldering (0-2), and backpressure (this lesson) — and walks the runtime as a single working system: how `WiringModel.start()` orders the schedulers' lifecycles, how the validation checks (`checkForCyclicalBackpressure`, `checkForIllegalDirectSchedulerUsage`, `checkForUnboundInputWires`) compose at startup, and how the `PlatformWiring` constructor we have been quoting in fragments fits together end-to-end. With that synthesis in hand, cluster A.1 then leaves the substrate behind and starts the consensus algorithm proper, beginning with the [hashgraph DAG](A.1-1-hashgraph-dag.md).
