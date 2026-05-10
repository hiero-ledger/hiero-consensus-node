---
lesson_id: 0-4-wiring-runtime-synthesis
cluster: '0'
title: Wiring runtime synthesis
prerequisites: [0-3-backpressure-at-the-wire]
kb_refs:
  topics: [wiring-framework]
  concepts: []
  invariants: []
  glossary_terms: []
learning_objectives:
  - Explain the assembly-then-running two-phase model the wiring framework imposes, and identify `WiringModel.start()` as the seam between them.
  - Walk the steps `StandardWiringModel.start()` performs in order — anchor start, HealthMonitor instantiation, mark-as-started, the three validation checks, heartbeat scheduler, sequential-thread schedulers — and explain what each step protects against.
  - Locate the seam between graph assembly (`PlatformWiring.wire`) and lifecycle (`PlatformCoordinator.start` → `model.start`), and state the contract that wires are soldered before `start()` is called.
estimated_read_minutes: 10
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---

# Wiring runtime synthesis

## Where we are

This is the final lesson in cluster 0. Three lessons have stocked the substrate: [0-1](0-1-task-schedulers.md) put the `TaskScheduler` and its six type values in your hands; [0-2](0-2-wires-and-soldering.md) added typed input and output wires and the three-value `SolderType` menu that joins them; [0-3](0-3-backpressure-at-the-wire.md) developed `unhandledTaskCapacity` plus the `PUT` / `INJECT` / `OFFER` choice into wire-level backpressure, named the cyclic-backpressure hazard, and introduced the heartbeat-fed health-monitor wire as the out-of-band signal cluster B will react to. Each piece has been correct in isolation. This lesson is about what makes them a working runtime — the lifecycle that turns assembled wiring into live data flow, the validation suite that runs at that boundary, and the seam between the two in the consensus layer's actual startup.

## Motivating problem

A wiring graph passes through two phases. In the **assembly phase**, schedulers are built, input wires are constructed on them, business logic is bound, and `solderTo` registers each edge with the model. The graph is mutable; nothing is yet executing. In the **running phase**, schedulers are dispatching, threads are alive, and tasks are flowing along the soldered edges. Most of the hazards the cluster has discussed — a `PUT`-only cycle, a `DIRECT` scheduler with two sequential feeders, an input wire built but never bound — are properties of the assembled graph. They become catastrophic only once the graph is running, but they are *detectable* before that. The framework wants to catch them at the boundary, not later.

Two questions follow. *When* does the wiring model become live — what is the call that transitions assembly into running, and what does it guarantee about everything that comes after it? And *how* does the framework catch assembly mistakes at that transition without stopping the system from booting in the wrong places? The answer to both is small enough to fit in this lesson: a single `WiringModel.start()` call that runs the validation suite as part of its body, and a soldering-before-start contract that the platform's startup respects by structure rather than enforcement.

## Concept

The wiring model has a one-shot lifecycle exposed through two methods on the [`WiringModel`](../../architecture/topics/wiring-framework.md#wiringmodel) interface:

- `start()` — transition from assembly to running. Internal infrastructure (heartbeat, health monitor, dedicated-thread schedulers) starts, and the assembled graph is checked once for the structural hazards 0-1 / 0-2 / 0-3 named.
- `stop()` — reverse. Heartbeat and dedicated-thread schedulers are halted and the model becomes inert.

Three validation checks, each declared on `WiringModel`, compose at the start boundary into a single startup gate:

- `checkForCyclicalBackpressure()` — scans for the `PUT`-only cycle from [0-3](0-3-backpressure-at-the-wire.md).
- `checkForIllegalDirectSchedulerUsage()` — confirms that every `DIRECT` scheduler obeys the single-sequential-feeder rule from [0-1](0-1-task-schedulers.md).
- `checkForUnboundInputWires()` — confirms that every `BindableInputWire` built has had `bind` or `bindConsumer` called on it. An unbound input wire silently drops everything pushed at it.

All three log a message rather than throwing; the message is the kind that fails standard platform tests. Validation is therefore a *test-time gate* invoked at runtime: the failure mode is "the test suite catches it next CI run", not "the node refuses to boot." The framework is choosing where it wants the failure to land.

The contract for assembly is dual to all of this. `OutputWire.solderTo` documents itself plainly:

> Forwarding should be fully configured prior to data being inserted into the system. Adding forwarding destinations after data has been inserted into the system is not thread safe and has undefined behavior.

So the rule is: solder everything you intend to solder *before* `start()` is called. Nothing in the framework enforces that rule — there is no guard inside `solderTo` that fails after `start()` — but every honest assembly site obeys it, because the alternative is the undefined behaviour the javadoc names.

## How it works

The body of [`StandardWiringModel.start()`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java) is short enough to read straight through. In order:

1. **Throw if already started.** The lifecycle is one-shot.
2. **Start the JVM anchor**, if one was supplied. The anchor is a non-daemon-thread holder used so the JVM does not exit while the model is alive; it is unrelated to the wiring graph itself.
3. **Instantiate the `HealthMonitor`** with the model's full scheduler list, and bind it to the internal `healthMonitorInputWire`. Note the timing — the `HealthMonitor` is built at `start()`, not at construction. The list of schedulers it watches is whatever was registered with the model up to that point.
4. **Mark the model as started.** Subsequent attempts to register more schedulers or to `start()` again fail.
5. **Run the three validation checks**, in this order: `checkForCyclicalBackpressure`, `checkForIllegalDirectSchedulerUsage`, `checkForUnboundInputWires`. Each delegates to a static helper (`CycleFinder`, `DirectSchedulerChecks`, `InputWireChecks` in [`TraceableWiringModel`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java)) that walks the graph and logs on detection. The boolean return values are deliberately ignored — the log message is the signal.
6. **Start the heartbeat scheduler**, if one was created during assembly. The heartbeat is the source the `HealthMonitor` ticks on, and it is also what `OutputWire<Instant>` heartbeat consumers (the platform monitor, for example) subscribe to.
7. **Start each `SequentialThreadTaskScheduler`** in the model. These are the schedulers built with `SEQUENTIAL_THREAD` from [0-1](0-1-task-schedulers.md); they need a dedicated-thread launch step that pool-backed schedulers do not. After this loop returns, every component bound to a sequential-thread scheduler has its thread alive and dequeueing tasks.

`stop()` reverses the order: heartbeat scheduler stops, sequential-thread schedulers stop, anchor stops. The validation suite is not re-run.

A point worth pulling out from step 5: the order of the three checks is not load-bearing — each runs independently and logs independently — but the placement *inside* `start()` is. By the time any task is dispatched, all three checks have already been performed against the final graph; nothing soldered after this point would be visible to them. That is the structural reason for the soldering-before-start contract above.

The deterministic-mode counterpart, [`DeterministicWiringModel`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/DeterministicWiringModel.java), has a different `start()` body — it does not invoke the validation checks at all. The deterministic model is intended for tests, where graph correctness is asserted by other means. If you are reading the framework and find yourself surprised that validation is missing, check which `WiringModel` impl you have in hand.

## Worked example

Imagine the consensus-layer wiring graph at the moment `model.start()` is invoked, with two structural mistakes worth following through.

The first is the cycle from [0-3](0-3-backpressure-at-the-wire.md): event creator → event intake → … → PCES → event creator. The live code already breaks this cycle by soldering `eventCreator → eventIntake` with `INJECT`. When `checkForCyclicalBackpressure` walks the graph at `start()` time, it filters edges by `SolderType` and looks only for `PUT`-only cycles. The `INJECT` edge is excluded; the cycle is not detected; the check returns false and logs nothing. The remedy applied at assembly time made this check pass at startup time. Now imagine that same edge had been left at the default `PUT`. The check would return true, log a message naming the cycle, and the next CI run would fail on it. The graph would still boot; the failure surface would be the test suite, not the running node.

The second is a hypothetical `DIRECT` scheduler whose `DIRECT` placement constraints are violated — say it has been wired downstream of a `CONCURRENT` parent rather than the single sequential feeder its placement rule requires. `checkForIllegalDirectSchedulerUsage` performs a depth-first walk from each `DIRECT` scheduler and detects this; it logs the offending placement. Same failure mode: the node continues to boot, the test suite goes red.

The third check, `checkForUnboundInputWires`, fires when assembly built an input wire on a scheduler but the constructor never called `bind` or `bindConsumer` on it — typically because a downstream component was wired up speculatively and then not yet implemented. The framework cannot catch this at construction (the graph is mutable, and an unbound wire might be bound later in the assembly phase) but it can catch it at `start()`, because by then assembly is over.

Once `start()` returns, validation has already happened. From this point on the per-edge `SolderType` policy from [0-2](0-2-wires-and-soldering.md), the per-scheduler capacity from [0-3](0-3-backpressure-at-the-wire.md), and the dispatch policy of each scheduler type from [0-1](0-1-task-schedulers.md) are the only things controlling how data moves. The framework has done its structural work; the live runtime is now what the cluster's first three lessons described.

## Code anchor

- [`WiringModel.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/WiringModel.java) — the interface. `extends Startable, Stoppable`; declares the three validation methods, `getHealthMonitorWire()`, `buildHeartbeatWire(...)`, and `generateWiringDiagram(...)`. The class-level javadoc on `start()` is the authoritative description of its dual role: "Performs static analysis of the wiring topology and writes errors to the logs if problems are detected."
- [`StandardWiringModel.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/StandardWiringModel.java) — the production implementation. The seven-step `start()` body and the reversing `stop()` body live here.
- [`TraceableWiringModel.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/TraceableWiringModel.java) — the abstract base. The three validation methods delegate from here to `CycleFinder`, `DirectSchedulerChecks`, and `InputWireChecks` respectively; `generateWiringDiagram` is also implemented here.
- [`DeterministicWiringModel.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/model/DeterministicWiringModel.java) — the test-mode implementation; its `start()` does not run the validation suite.
- [`OutputWire.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java) — the `solderTo` javadoc quoted above ("forwarding should be fully configured prior to data being inserted into the system") is the contractual statement of the soldering-before-start rule.
- [`PlatformWiring.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java) — the consensus layer's single end-to-end soldering site. The static `wire(...)` method is pure assembly: it does not call `model.start()` and does not invoke the validation methods.
- [`PlatformCoordinator.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java) — `start()` is a single-line method that calls `components.model().start()`; `stop()` is its mirror. This is where the model's lifecycle attaches to the platform's boot sequence.

## Delta callout

No `delta-map/wiring-framework.md` entry exists yet. The wiring-framework topic file's [Future state sidebar](../../architecture/topics/wiring-framework.md#future-state-sidebar) introduces a module-API-level backpressure mechanism on the Consensus / Execution boundary that sits *above* the wire-level mechanism this cluster has covered, not in place of it. That mechanism does not change `WiringModel.start()` or the validation suite — both remain the per-process lifecycle and topology-checking story for the consensus layer.

## Comprehension prompt

Two openings to take into the tutor chat. First: the validation suite logs a message rather than throwing at startup, and the failure surface is "standard platform tests fail" rather than "the node refuses to boot." Walk through what that says about who the framework expects to catch wiring mistakes, and what would change about the ergonomics of editing a wiring graph if `start()` instead refused to run when any of the three checks tripped. Second: `PlatformWiring.wire(...)` builds the graph but never calls `model.start()`; `PlatformCoordinator.start()` does, in a different file at a different point in the platform's boot sequence. What does separating assembly from lifecycle buy that doing both inside one method would not, and what would the `OutputWire.solderTo` javadoc start saying if the two were merged?

## Cluster worked example

Trace one persisted event through the cluster's primitives end-to-end, using the live solders in [`PlatformWiring.wire`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java).

PCES has just finished writing a `PlatformEvent` to disk. Its scheduler — a queued, sequential scheduler from [0-1](0-1-task-schedulers.md) — runs the bound logic that produces the persisted event on `pcesModule().writtenEventsOutputWire()`. That output wire ([0-2](0-2-wires-and-soldering.md)) carries the event to three downstream consumers, soldered in the same constructor:

```java
final OutputWire<PlatformEvent> writtenEventOutputWire =
        components.pcesModule().writtenEventsOutputWire();

writtenEventOutputWire.solderTo(components.hashgraphModule().eventInputWire());
writtenEventOutputWire.solderTo(components.gossipModule().eventToGossipInputWire(), INJECT);
writtenEventOutputWire.solderTo(components.eventCreatorModule().orderedEventInputWire());
```

Three edges, three policy choices ([0-3](0-3-backpressure-at-the-wire.md)). Hashgraph and event creator take the default `PUT`: if either falls behind, PCES is correct to slow down on the next written event. Gossip takes `INJECT`: a written-but-never-gossiped event would let the next event the node creates become a fork, so this edge must not block. Each consumer is bound to its own scheduler with its own type from the [0-1](0-1-task-schedulers.md) menu; each of these solders registered an edge with the model, where the validation suite will look at it during `start()`.

When `model.start()` runs (called from `PlatformCoordinator.start()`), `checkForCyclicalBackpressure` walks the graph, sees that the eventCreator → eventIntake `INJECT` from elsewhere in the same constructor breaks the only cycle that crosses these edges, and returns false. `checkForIllegalDirectSchedulerUsage` confirms that no `DIRECT` placement here violates its single-feeder rule. `checkForUnboundInputWires` confirms that `eventInputWire`, `eventToGossipInputWire`, and `orderedEventInputWire` were all bound by their owning module's constructor. None of these methods would have succeeded a moment earlier — all three depend on the assembled-graph state — and once `start()` returns they will not be re-run.

After `start()` returns, the same `OutputWire<Duration>` returned by `model.getHealthMonitorWire()` is fanning ticks out to the event creator, gossip, and execution — three default `PUT` solders also visible in the same constructor, one of them with a name and lambda rather than a precomposed input wire. Whichever scheduler in the graph is currently most over capacity drives that duration, and each consumer reacts locally. This closes the loop: the cluster's primitives produce a runtime in which an event is persisted on a sequential scheduler, fanned out across three consumers under three different per-edge policies, and overall queue health is reported out-of-band on a heartbeat-fed wire that is itself a soldered edge in the same graph. The substrate is in place.

## Where we're going next

Cluster A.1 leaves the substrate behind and starts on the consensus algorithm proper. The first lesson — [A.1-1 The hashgraph DAG](A.1-1-hashgraph-dag.md) — assumes everything cluster 0 has built without re-explaining it: events arrive at a scheduler's input wire, business logic processes them under the scheduler's threading guarantee, output wires fan results out to the next stage, and any back-up is either propagated as backpressure or surfaced through the health-monitor wire. From here on, the runtime is taken for granted; what runs *on* it is the new material.

## Open questions

> - [TBD] No `glossary.md` exists at the consensus-layer KB root yet. The wiring-specific terms sharpened in this lesson — *assembly phase / running phase*, *startup gate*, *one-shot lifecycle*, *soldering-before-start contract* — should be promoted to the glossary once it is created. Same gap is flagged in 0-1, 0-2, 0-3.
> - [TBD] No `invariants.md` catalog exists yet, so `kb_refs.invariants` is empty. Three candidate invariants surface here: *the assembled wiring graph is immutable across `WiringModel.start()`*; *every `BindableInputWire` is bound by the time `start()` returns*; *`getHealthMonitorWire()` is fed by an internal scheduler ticked on a heartbeat, not soldered into the data flow it observes*. Cite as `INV-NNN` once registered.
> - [TBD] No `delta-map/wiring-framework.md` exists. The Delta callout above defers to the topic file's Future-state sidebar, which is the correct authority for now but should be replaced with a delta-map link when one lands.
> - [TBD] Validation runs at `start()` and logs failures rather than throwing. `WiringModelBuilder.withHardBackpressureEnabled(...)` exists and `TraceableWiringModel.isHardBackpressureEnabled()` records it; SME confirmation would clarify whether any production configuration converts the cycle-detector's "log + fail tests" behaviour into a startup refusal, or whether the flag tightens something else entirely. Same [TBD] is flagged in 0-3.
> - [TBD] `DeterministicWiringModel.start()` does not run the validation checks. Is the contract that deterministic mode is exclusively a test concern, or is there a production path that exercises it? The wiring-framework topic file flags the same question.
> - [TBD] `WiringModel.generateWiringDiagram(...)` is not invoked from `PlatformWiring` or `PlatformCoordinator` in the working tree. Is there a documented operational practice of dumping the diagram during boot for debugging, or is the diagram strictly a test/CLI tool? SME confirmation would either pull the practice into a future revision of this lesson or close it as a non-load-bearing internal concern.
