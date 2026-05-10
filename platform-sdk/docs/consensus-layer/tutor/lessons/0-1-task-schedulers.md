---
lesson_id: 0-1-task-schedulers
cluster: '0'
title: Task schedulers
prerequisites: [pass1-4-event-creation-under-stress]
kb_refs:
  topics: [wiring-framework]
  concepts: []
  invariants: []
  glossary_terms: []
learning_objectives:
  - Name what a TaskScheduler<OUT> is and what it owns — a queue, a thread-execution policy, and a primary output wire.
  - Distinguish the six TaskSchedulerType values and the threading guarantees each provides.
  - Pick a scheduler type for a new component given its concurrency requirement and the wiring model's structural constraints.
estimated_read_minutes: 8
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---

# Task schedulers

## Where we are

This is the first lesson in cluster 0 (Wiring Framework Foundation). Pass 1 handed off scenarios that named "schedulers", "queues", and "wires" without defining them — most visibly in [event creation under stress](pass1-4-event-creation-under-stress.md), where step 1 spoke of "every scheduler in the wiring graph that was built with an `unhandledTaskCapacity`". Cluster 0 is the substrate every later lesson stands on. Before we get to the hashgraph algorithm or the gossip protocol, we need a precise vocabulary for the runtime fabric they execute against. This first lesson is about that fabric's unit of execution: the `TaskScheduler`.

## Motivating problem

The consensus layer is a graph of components that each own a piece of state and perform a tightly bounded job — buffer orphan events, build the hashgraph, write a self-event to disk, push events out the gossip socket. Each component has its own concurrency requirement: some must serialize state changes, some are stateless and embarrassingly parallel, some are short enough that the cost of a queue is itself the bottleneck. Coding each one's threading model directly into its business logic would couple the two — change the threading and you would touch the logic; change the logic and you would have to re-reason about the threading.

Worse, the system needs the right to *change its mind* about threading without rewriting components. A component currently on a dedicated thread might move to the fork-join pool tomorrow; a component running directly on the caller might be moved behind a queue when load increases. Hand-rolled threading inside each component makes that retuning expensive and risky.

The wiring framework's answer is to lift threading and queueing out of business logic and into a single named abstraction the component is bound to: the task scheduler. Business logic is a `Function` or `Consumer`; the scheduler decides which thread runs it, whether it has a queue, and whether tasks happen one at a time or in parallel.

## Concept

A `TaskScheduler<OUT>` is the per-component execution unit of the wiring framework. From the [responsibilities list](../../architecture/topics/wiring-framework.md#responsibilities) in the wiring-framework topic, it provides:

- **A queue.** The unit of work is a *task*: an input value plus the bound business logic that processes it. The scheduler owns the queue tasks land in, and the policy by which queued tasks are picked up. Two scheduler types (`DIRECT`, `DIRECT_THREADSAFE`) skip the queue entirely; the rest enforce ordering and capacity through it.
- **A thread-execution policy.** The scheduler decides which thread executes each task — a dedicated thread, a fork-join pool worker, or the caller's own thread. This is the entire concurrency contract for the bound business logic; the logic itself does not name a thread.
- **A primary output wire.** Each scheduler exposes one built-in `OutputWire<OUT>` whose values are auto-forwarded from the bound function's return. Wires themselves — input wires, output wires, soldering — are the subject of [lesson 0-2](0-2-wires-and-soldering.md). For this lesson the relevant fact is only that the scheduler's output is the seam that attaches it to the rest of the graph.

Components are *built* by obtaining a scheduler from the [`WiringModel`](../../architecture/topics/wiring-framework.md#wiringmodel), constructing input wires on it, and binding the business logic. The scheduler then forwards each handler's return value onto its primary output wire; downstream schedulers pick those values up through soldering. The choice that makes every scheduler different from every other is its **type**.

## How it works

`TaskSchedulerType` enumerates the six concurrency policies the framework knows how to provide. Each has its own threading and ordering guarantees, and the choice shapes everything else about the component — its hot-path latency, the synchronization (or lack of it) the bound logic must use, the back-pressure surface, and what the wiring model will let it connect to.

- **`SEQUENTIAL`** — the framework default. Tasks run on the shared fork-join pool, one at a time, in enqueue order, with happens-before between consecutive tasks. The bound logic owns its state without locks; the framework simply guarantees no two tasks run concurrently.
- **`SEQUENTIAL_THREAD`** — same one-at-a-time-in-order semantics, but on a single dedicated thread instead of a fork-join pool worker. Useful when the work is long-running enough that holding a pool worker is undesirable, or when a stable thread identity matters for downstream tooling. Performance characteristics differ from `SEQUENTIAL`; the contract for the bound logic does not.
- **`CONCURRENT`** — tasks run on the fork-join pool in parallel, with no ordering guarantee. The bound logic must be threadsafe. Useful for stateless or per-input-isolated work where parallelism wins.
- **`DIRECT`** — no queue at all. The "task" is executed synchronously on the calling thread when an input value is put. The framework places strong constraints on who is allowed to call into a `DIRECT` scheduler so the calling-thread story stays sound: only one logical thread of execution may feed it. The wiring model verifies this with a depth-first walk over the graph (`SEQUENTIAL` and `SEQUENTIAL_THREAD` schedulers are permitted upstream, `CONCURRENT` is not, and at most one such upstream sequential scheduler may reach a given `DIRECT`). The full algorithm is documented on the enum value itself.
- **`DIRECT_THREADSAFE`** — like `DIRECT`, but the bound logic guarantees its own thread safety, so the framework lifts the single-feeder restriction. There is no enforcement of the threadsafety claim; misusing this type is how races get introduced.
- **`NO_OP`** — discards everything. Wires into and out of it are runtime no-ops. The use case is configuration-toggleable components: leave the wiring intact, swap to `NO_OP`, and the component's behaviour disappears without disturbing the graph.

Two threading guarantees fall out of this enumeration that are worth naming explicitly, because they shape what the bound logic is allowed to assume. First, the three queued types (`SEQUENTIAL`, `SEQUENTIAL_THREAD`, `CONCURRENT`) decouple the producing thread from the consuming thread; the producer's call returns once the task is enqueued, and execution happens later on the scheduler's thread. Second, the two direct types do not decouple — execution happens on the producer's thread, before the producer's call returns. The scheduler type is therefore not a tuning knob you can flip casually; it is part of each component's contract with its callers.

## Worked example

A representative current call site lives in `GossipWiring`. The `gossip` scheduler is built like this:

```java
scheduler = model.<Void>schedulerBuilder("gossip")
        .configure(configuration.getConfigData(GossipWiringConfig.class).gossip())
        .build();
```

A few things to read out of this snippet:

- **Output type is `Void`.** Gossip does not have a single canonical output value per task, so its primary output wire is parameterized as `Void`. Its actual outputs — events received from peers, sync progress reports — are exposed through *secondary* output wires (`buildSecondaryOutputWire(...)` in the same constructor). The choice between primary and secondary output wires belongs to [lesson 0-2](0-2-wires-and-soldering.md); here, note only that `<Void>` does not mean "no output", it means "no auto-forwarded primary output".
- **The type is not in the snippet.** It is set by `.configure(...)` from the `GossipWiringConfig`. Externalising the choice of `TaskSchedulerType` to configuration is the pattern that lets the same component be retuned without code change — exactly the property the framework was built to provide.
- **Many input wires share one scheduler.** The constructor goes on to build several input wires on the same `scheduler` (`events to gossip`, `event window`, `start`, `stop`, `clear`, `health info`, `PlatformStatus`, `pause`, `resume`). All of them feed the same queue and execute under the same threading policy. For a sequential scheduler, this is the rule: many input wires, one queue, one logical thread of execution through the bound logic.

## Code anchor

- [`TaskScheduler.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java) — the abstract class; the class-level javadoc is the authoritative description of the unscheduled / scheduled-but-not-processed / processed task lifecycle.
- [`TaskSchedulerType.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java) — the enum, with per-value javadoc that is the authoritative description of each type's semantics. The `DIRECT` value's javadoc contains the depth-first algorithm the wiring model uses to validate `DIRECT` placement.
- [`GossipWiring.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/GossipWiring.java) — the call site shown above, in the constructor.

## Delta callout

No `delta-map/wiring-framework.md` entry exists yet. The wiring-framework topic file's [Future state sidebar](../../architecture/topics/wiring-framework.md#future-state-sidebar) notes that the consensus-layer proposal adds a module-API-level back-pressure mechanism on top of — not in place of — the per-scheduler back-pressure the wiring model provides. That sidebar belongs more to [lesson 0-3](0-3-backpressure-at-the-wire.md) (back-pressure at the wire) than here; this lesson sits below it.

## Comprehension prompt

Two openings to take into the tutor chat. First: a component you are wiring up needs to serialize its state changes (no two tasks running at once), but the work is short, latency-sensitive, and does not need a stable thread identity for any downstream tooling. Which of the two sequential types fits, and what would change if you reached for the other one? Second: the framework lets you reach for `DIRECT_THREADSAFE` to bypass the single-feeder rule that constrains plain `DIRECT`. What is the failure mode that constraint is protecting you from on `DIRECT`, and what concretely makes it your problem rather than the framework's once you switch?

## Open questions

> - [TBD] No `glossary.md` exists at the consensus-layer KB root yet. The wiring-specific terms introduced in this lesson — *task scheduler*, *primary output wire*, *queued vs direct scheduler* — should be promoted to the glossary once it is created, and the lesson updated to link to them rather than introducing them inline. Same gap is flagged in `pass1-4-event-creation-under-stress`.
> - [TBD] No `invariants.md` catalog exists yet, so `kb_refs.invariants` is empty. Two candidate invariants surface here: *every component is bound to exactly one task scheduler*; *the framework guarantees the threading policy of a `TaskScheduler` to its bound business logic, but does not enforce the threadsafety claim under `DIRECT_THREADSAFE` or `CONCURRENT`*. Cite as `INV-NNN` once registered.
> - [TBD] No `delta-map/wiring-framework.md` exists. The Delta callout above references the topic file's Future-state sidebar instead, which is the correct authority for now but should be replaced with a delta-map link when one lands.
> - [TBD] The wiring-framework topic itself (lines 39–43) shows the `GossipWiring` snippet with line numbers that have drifted; the actual `model.<Void>schedulerBuilder("gossip")` call now lives at line 92 of `GossipWiring.java`. This lesson uses the snippet without a hard line reference, but the topic file should be re-pinned the next time it is touched.
> - [TBD] The wiring-framework topic's first open question asks whether one `TaskSchedulerType` value is canonical for new consensus-layer components, or whether the choice is always topic-specific. This lesson presents the six types as a menu without recommending a default. SME confirmation would let the lesson promote `SEQUENTIAL` (the framework default) as the new-component starting point and frame the others as deviations.

## Where we're going next

The next lesson, [0-2 Wires and soldering](0-2-wires-and-soldering.md), picks up the seam this lesson closed at: each scheduler exposes a primary output wire and accepts input wires, but how those wires are typed, how multiple input wires share one scheduler, and how `solderTo` connects an output to one or more downstream inputs is the next layer of the substrate. Once wires are in hand, [lesson 0-3](0-3-backpressure-at-the-wire.md) takes the queue back-pressure mentioned briefly here and walks the three `SolderType` values, the cyclic-back-pressure hazard, and the connection from a scheduler's queue depth to the health-monitor signal that drove the Pass 1 stress scenario.
