---
id: c0-01-components-and-schedulers
cluster: c0
title: "Components, schedulers, and queues"
pass: 2
prerequisites:
  - pass1-01-tx-to-consensus
  - pass1-02-node-falls-behind
  - pass1-03-coordinated-upgrade
  - pass1-04-event-creation-under-stress
kb_topics:
  - architecture/topics/wiring-framework.md
kb_concepts: []
kb_glossary_terms: []
kb_invariants: []
kb_deltas:
  - delta-map/wiring-framework.md
kb_decisions: []
learning_objectives:
  - Name the three things a `TaskScheduler` owns and the one thing it does not.
  - Pick a `TaskSchedulerType` for a component given its threading, ordering, and queueing needs, and justify the choice.
  - Walk the three-step `ComponentWiring` pattern (construct, solder, bind), say which step-orderings the framework enforces and which are convention, and explain why the convention exists.
  - Connect the scheduler's on-ramp counter to `getUnprocessedTaskCount()` and explain why it is the signal the health monitor watches.
threshold_concepts:
  - "`TaskSchedulerType` as a deliberate three-axis choice (ordering × parallelism × thread-affinity)"
estimated_session_minutes: 35
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# Components, schedulers, and queues

## Prerequisites

The orientation pass establishes the components this cluster's substrate runs underneath. The lesson assumes the learner has each of those mental sketches.

- **`pass1-01-tx-to-consensus`** — a transaction passes through event creator → intake → PCES → fan-out to hashgraph, gossip, and back to the creator. The fan-out shape and the back-edge are visible at orientation altitude.
- **`pass1-02-node-falls-behind`** — gossip detects falling-behind, the health monitor signals when scheduler queues stay over capacity, and reconnect is the recovery path.
- **`pass1-03-coordinated-upgrade`** — freeze quiesces the system, a signed state is taken at the freeze boundary, restart resumes from that state, and PCES replays preconsensus events on the new version.
- **`pass1-04-event-creation-under-stress`** — under pressure the event creator declines to build, the health monitor's unhealthy duration drives that decision, and gossip's reasons-not-to-gossip catalog gates outgoing sync.

This is the first Pass 2 lesson, so the prerequisites are orientation only. No deeper substrate is assumed.

## Incoming retrieval probes

Two probes. Both target Pass 1 material on which this lesson lays foundations.

- **Components on the steady-state path.** Prompt: "Without looking, name the consensus-layer components a self-event passes through from creation to its first appearance on the hashgraph, in order." Canonical answer: event creator → event intake → PCES writer → hashgraph (with parallel branches to gossip and back to the event creator). Source: [`pass1-01-tx-to-consensus.md` — Trace](pass1-01-tx-to-consensus.md). The tutor consolidates against this list because every example in this lesson uses one of these components as its referent.
- **What the health monitor is.** Prompt: "What signal does the health monitor publish, and what does it not do?" Canonical answer: it publishes the longest continuous duration any one watched scheduler has been over its capacity; it does not block, throttle, or reject. Source: [`pass1-02-node-falls-behind.md`](pass1-02-node-falls-behind.md) at orientation altitude and [`health-monitor-and-backpressure.md` — Responsibilities](../../architecture/topics/health-monitor-and-backpressure.md#responsibilities) at full depth. This probe primes the chunk that connects `getUnprocessedTaskCount()` to the health monitor's input.

## Misconception watchlist

Four wrong models are likely on this material. Two are adjacent-protocol imports; two are over-generalisations from familiar parts of this codebase or systems work elsewhere.

- **"A scheduler is a thread"** — imported from textbook actor-model or thread-per-component pictures. Learner-side tell: "the deduplicator's thread does X." Correction: a `SEQUENTIAL` scheduler runs its tasks on a shared `ForkJoinPool`, not a dedicated thread. The happens-before relation comes from the scheduler's serial dispatch ([`SequentialTaskScheduler.scheduleTask`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/internal/SequentialTaskScheduler.java#L114-L132)), not from thread identity. The type that does pin to a dedicated thread is `SEQUENTIAL_THREAD`.
- **"`DIRECT` is faster, so prefer it"** — a systems-engineering over-generalisation: skipping the queue must be cheaper. Learner-side tell: a suggestion to use `DIRECT` for a stateful component, or a question of the form "why isn't this `DIRECT`?". Correction: `DIRECT` carries reachability constraints that the framework enforces by graph walk — at most one `SEQUENTIAL` predecessor, no `CONCURRENT` predecessor — and these are non-trivial. The choice is governed by the legality rules in the [`DIRECT` javadoc](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L25-L50), not by performance heuristics.
- **"The queue is a problem to be eliminated"** — imported from low-latency systems work where any queue depth above one is treated as evidence of stalling. Learner-side tell: anxiety about non-zero `getUnprocessedTaskCount()`. Correction: the queue is the buffer that lets producers and consumers run at different rates without blocking each other every cycle, and the health monitor's signal is precisely the time *over capacity*, not the time at depth > 0. The capacity is the threshold below which queueing is expected and healthy ([`HealthMonitor.checkSystemHealth`](../../architecture/topics/health-monitor-and-backpressure.md#detection) compares count to capacity, not to zero).
- **"The `*WiringConfig` record is configuration plumbing"** — under-reading. Learner-side tell: treating `wiringConfig.eventDeduplicator()` as boilerplate. Correction: this is where the scheduler-type decision lives, per-component, in source. The wiring framework offers no per-component default ([topic file](../../architecture/topics/wiring-framework.md#taskscheduler--taskschedulertype): "There is no canonical scheduler type for new components"); the `TaskSchedulerConfiguration` record is the lever the wiring author pulls.

## Mechanism

The wiring framework's substrate decomposes into three primitives — `TaskScheduler`, `InputWire`, `OutputWire` — plus a wrapper (`ComponentWiring`) that is how consensus-layer modules normally reach the first one. This lesson covers `TaskScheduler` and `ComponentWiring`. The wires themselves are the subject of `c0-02-wires-and-soldering`; the three put-paths and `SolderType` belong to `c0-03-backpressure-modes`. Drawing those boundaries is part of the lesson — the framework is small enough to want to teach as a unit, and Cluster 0 only works if each lesson respects the others.

### Pre-training: terms

Three terms with one-sentence semantics. The tutor sets these before the chunked walk begins.

- **Component** — a Java interface whose methods are bound to a `TaskScheduler` that runs them. The component class supplies the business logic; the scheduler supplies the threading discipline. The framework requires the class to be an interface so it can resolve method references via a proxy ([`ComponentWiring` constructor check](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L153-L155)).
- **`TaskScheduler<OUT>`** — the framework abstraction that owns the queue, the thread-execution policy, and the component's primary output wire. One scheduler per component in the canonical pattern.
- **`ComponentWiring<COMPONENT, OUT>`** — the canonical wrapper consensus-layer modules use to obtain a `TaskScheduler` plus method-reference-based `InputWire` access plus a deferred bind step. It is not part of the framework's primitive surface; it is the convenience layer the platform was built on top of.

### Chunk 1 — What a `TaskScheduler` is and what it owns

**moment_id**: `moment-scheduler-anatomy`

A `TaskScheduler<OUT>` ([`TaskScheduler.java#L36-L88`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L36-L88)) owns three things:

- **A queue** of unprocessed tasks (or, for `DIRECT` and `NO_OP`, no queue at all). The current count is `getUnprocessedTaskCount()` ([abstract on `TaskScheduler` L186-L201](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201); concrete in [`SequentialTaskScheduler.getUnprocessedTaskCount`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/internal/SequentialTaskScheduler.java#L137-L140), which returns the on-ramp counter's current value).
- **A thread-execution policy** — which thread (or pool) runs the bound handler, and whether multiple handlers can run in parallel. This is captured in the scheduler's `TaskSchedulerType`, accessible via [`getType()`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L157-L165).
- **A primary `OutputWire<OUT>`** ([`getOutputWire()` L116-L126](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L116-L126)) that receives whatever the bound handler returns. The scheduler itself pushes onto this wire — business logic never does. Secondary output wires exist for fan-out side-channels ([`buildSecondaryOutputWire` L128-L145](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L128-L145)) and are owned by the business logic, not by the scheduler. This is a load-bearing distinction for `c0-02-wires-and-soldering` — secondary wires do not participate in the on-ramp counter and therefore not in `flush()` either ([topic file](../../architecture/topics/wiring-framework.md#inputwire-and-outputwire)).

What the scheduler does *not* own: the business logic. The handler is bound separately and the scheduler holds it as a reference. Thread confinement is the consequence: because the scheduler dispatches every task on the same logical lane (one thread for `SEQUENTIAL_THREAD`, one task at a time on the pool for `SEQUENTIAL`), state owned by a single component does not need internal synchronisation.

**Load-bearing.** The lifecycle of a task ([`TaskScheduler.java#L22-L35`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L22-L35) javadoc) has three stages — *unscheduled* (not yet handed to the scheduler), *scheduled but not processed* (in the queue or in flight), *processed* (handler has returned). The on-ramp counter increments on the boundary between the first and the second, decrements on the boundary between the second and the third. Every later mechanism in this cluster — capacity, backpressure, `flush()`, the health monitor's "over capacity" signal — is defined in terms of those two counters and these three lifecycle stages.

### Chunk 2 — `TaskSchedulerType` and what it collapses

**moment_id**: `moment-type-choice` (threshold concept)

`TaskSchedulerType` ([the enum L8-L68](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L8-L68)) has six values. The temptation is to read them as a flat menu — pick the one whose label sounds right. The threshold concept this lesson establishes is that they are not flat: each value collapses three independent design choices into one pick, and the right way to read the menu is to know which three choices each value is making.

The three axes:

1. **Ordering** — are consecutive tasks guaranteed to happen-before each other? Yes for `SEQUENTIAL`, `SEQUENTIAL_THREAD`, `DIRECT` (only one logical producer, so single-threaded); no for `CONCURRENT` and `DIRECT_THREADSAFE`.
2. **Parallelism** — can two tasks execute at once? No for the three sequential / direct variants; yes for `CONCURRENT` and `DIRECT_THREADSAFE`.
3. **Thread-affinity / queue presence** — does the scheduler own a dedicated thread, share a pool, or skip the queue and execute on the calling thread? `SEQUENTIAL_THREAD` owns a dedicated thread; `SEQUENTIAL` and `CONCURRENT` share `ForkJoinPool`; `DIRECT` / `DIRECT_THREADSAFE` skip the queue. `NO_OP` discards everything ([javadoc L62-L68](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L62-L68)).

So the menu, read on those axes:

| Type | Ordering | Parallelism | Thread | Queue |
|---|---|---|---|---|
| `SEQUENTIAL` | yes | no | shared pool | yes |
| `SEQUENTIAL_THREAD` | yes | no | dedicated | yes |
| `CONCURRENT` | no | yes | shared pool | yes |
| `DIRECT` | yes | no | caller's thread | no |
| `DIRECT_THREADSAFE` | no | yes | caller's thread | no |
| `NO_OP` | n/a | n/a | n/a | n/a (discards) |

The default is `SEQUENTIAL` ([javadoc on `TaskSchedulerType` L5-L7](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L5-L7) and [`TaskSchedulerConfiguration` field docs](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerConfiguration.java#L16-L17)). The default exists for safety, not as a recommendation. There is no canonical default for new components in the consensus layer — the type lives in the per-component `*WiringConfig` record and is picked deliberately.

**Load-bearing.** `DIRECT` carries the framework's most intricate validation rule. The [`DIRECT` javadoc L25-L50](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L25-L50) defines a directed-graph reachability check the wiring model enforces: a `DIRECT` scheduler can only be reached by at most one `SEQUENTIAL` / `SEQUENTIAL_THREAD` scheduler, never by a `CONCURRENT` scheduler. The model runs this as `checkForIllegalDirectSchedulerUsage` at construction ([topic file — WiringModel](../../architecture/topics/wiring-framework.md#wiringmodel)). Picking `DIRECT` without that analysis fails the graph walk.

### Chunk 3 — `ComponentWiring` and the three-step pattern

**moment_id**: `moment-three-step-pattern`

`ComponentWiring<COMPONENT, OUT>` ([class L41-L60](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L41-L60)) is the wrapper that combines a `TaskScheduler` with method-reference-based input wiring and a deferred bind step. Consensus-layer modules use it for every standard component; the cases that bypass it (`PassThroughWiring`, `GossipWiring`) are exceptions documented as such in the [topic file](../../architecture/topics/wiring-framework.md#taskscheduler--taskschedulertype).

The canonical constructor ([L113-L156](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L113-L156)) takes the model, the component interface class, and a `TaskSchedulerConfiguration`. Inside, it calls `model.schedulerBuilder(...).configure(...).build()` ([L146-L151](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L146-L151)). So the `WiringModel.schedulerBuilder` call that the framework names as its scheduler primitive lives inside `ComponentWiring`, not in the module's wiring code. From the module's perspective the scheduler is acquired in one line.

The pattern, drawn from [`DefaultEventIntakeModule.initialize`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L72), has three steps in order:

**Step 1 — construct.** ([`DefaultEventIntakeModule.java#L96-L97`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L96-L97))

```java
this.eventDeduplicatorWiring =
        new ComponentWiring<>(model, EventDeduplicator.class, wiringConfig.eventDeduplicator());
```

The `Class<EventDeduplicator>` argument is the component's interface (the constructor rejects non-interfaces at [L153-L155](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L153-L155)). The `TaskSchedulerConfiguration` from `wiringConfig.eventDeduplicator()` decides the type, capacity, flushing, and squelching. The scheduler is built immediately. The component instance does not exist yet.

**Step 2 — solder.** ([`DefaultEventIntakeModule.java#L108-L114`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L108-L114))

```java
hashEventsWiring
        .getOutputWire()
        .solderTo(eventDeduplicatorWiring.getInputWire(EventDeduplicator::handleEvent));
```

`ComponentWiring.getInputWire(MethodReference)` ([L176-L207](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L176-L207)) lazily creates and caches a `BindableInputWire` for the target method. It works through a `WiringComponentProxy` ([L196-L206](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L196-L206)) that records which method the reference points at; this is why the class argument in step 1 must be an interface. The method-reference machinery is the load-bearing part of this step — the lesson does not go deep on it (it belongs to `c0-02-wires-and-soldering`), but the order of operations matters here: the reference is captured before any concrete component instance exists.

**Step 3 — bind.** ([`DefaultEventIntakeModule.java#L170`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/DefaultEventIntakeModule.java#L170))

```java
eventDeduplicatorWiring.bind(eventDeduplicator);
```

The `bind` method ([L662-L702](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L662-L702)) sets `this.component` and iterates `inputsToBind`, calling `inputWire.bind(...)` or `inputWire.bindConsumer(...)` on each cached `BindableInputWire`. From this point the wiring is live: data put on the input wires reaches the instance's methods, and the methods' return values reach the primary output wire.

Two orderings are enforceable and one is conventional. **Construct must come first**: solder and bind both reference the `ComponentWiring` variable, so a module that calls `bind` or `getInputWire` before construct fails at compile time. **Inside a single component, solder and bind are interchangeable in either order at the framework level.** The branch at [`getOrBuildInputWire` L599-L621](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L599-L621) is what allows this: if `component == null` (solder before bind), the new `BindableInputWire` is added to `inputsToBind` and the later `bind(...)` call resolves it; if `component != null` (solder after bind), the wire is bound inline against the already-stored instance. The only hard runtime guard is that no input wire can be created after the wiring model has started ([L580-L582](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L580-L582) throws `IllegalStateException` if `model.isRunning()`).

The convention is **construct-all → solder-all → bind-all across the whole module**, not "construct → solder → bind one component at a time." The reason is module-level: components in a module reach for each other's input and output wires during solder (`A.getOutputWire().solderTo(B.getInputWire(...))`), so step 1 across every component must run before step 2 across any of them. Bind happens last because the business-logic instances are often built from the now-fully-soldered wiring — for example, an instance's constructor takes a soldered output wire as a constructor argument so the instance can push onto it from inside its handler. The deferred bind is a *capability* the framework supplies, not a constraint it enforces; the canonical module pattern uses that capability to keep wiring assembly and instance construction in separate phases.

### Chunk 4 — `TaskSchedulerConfiguration` as the lever

The configuration record ([L27-L34](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerConfiguration.java#L27-L34)) has seven nullable fields:

```java
public record TaskSchedulerConfiguration(
        @Nullable TaskSchedulerType type,
        @Nullable Long unhandledTaskCapacity,
        @Nullable Boolean unhandledTaskMetricEnabled,
        @Nullable Boolean inflightTaskMetricEnabled,
        @Nullable Boolean busyFractionMetricEnabled,
        @Nullable Boolean flushingEnabled,
        @Nullable Boolean squelchingEnabled) { ... }
```

The two fields that change runtime behaviour most are `type` (default `SEQUENTIAL`) and `unhandledTaskCapacity` (default `0`, meaning unbounded — per the field's javadoc at [L18](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerConfiguration.java#L18)). `flushingEnabled` and `squelchingEnabled` are opt-in: calling `flush()` on a scheduler whose `flushingEnabled` is `false` throws `UnsupportedOperationException` ([`TaskScheduler.throwIfFlushDisabled` L239-L246](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L239-L246)). The three metric flags toggle observability rather than mechanism.

The record can be parsed from a string ([`parse(...)` L83-L177](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerConfiguration.java#L83-L177)) — syntax like `SEQUENTIAL CAPACITY(500) !FLUSHABLE UNHANDLED_TASK_METRIC`. The string form is what lives in `*WiringConfig` records that come out of platform configuration files, which is why the configuration is per-component and externally tunable without recompilation.

Three preset constants exist for the cases where every flag should be a known default: `NO_OP_CONFIGURATION`, `DIRECT_CONFIGURATION`, `DIRECT_THREADSAFE_CONFIGURATION` ([L36-L55](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerConfiguration.java#L36-L55)). The presence of presets for `DIRECT` and `NO_OP` and the absence of one for `SEQUENTIAL` is a hint at how the types are used — `SEQUENTIAL` is the case where capacity, flushing, and squelching are *typically* tuned per component, while `DIRECT` and `NO_OP` are degenerate cases where there is nothing to tune.

### Chunk 5 — How a scheduler connects to the health monitor

The scheduler's on-ramp counter is the input to queue-health detection. Each watched scheduler exposes its current depth via [`TaskScheduler.getUnprocessedTaskCount()`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201) — abstract on the base class, concrete in `SequentialTaskScheduler` as the on-ramp counter's [`getCount()`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/internal/SequentialTaskScheduler.java#L137-L140). The health monitor reads `getUnprocessedTaskCount()` and `getCapacity()` for every registered scheduler on a heartbeat and publishes the longest continuous over-capacity duration ([`health-monitor-and-backpressure.md` — Detection](../../architecture/topics/health-monitor-and-backpressure.md#detection)).

Two structural details matter for this lesson, even though their consequences are owned by `c0-04-health-monitor-mechanics`:

- **The counter is per-scheduler, not per-input-wire.** Every input wire bound to a scheduler shares the same on-ramp counter, so a scheduler's "depth" is total backlog across all inputs ([topic file — Backpressure](../../architecture/topics/wiring-framework.md#backpressure-wire-level)).
- **`getUnprocessedTaskCount()` returns `COUNT_UNDEFINED` unless tracking is enabled.** The base-class javadoc ([L186-L201](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L186-L201)) enumerates the three conditions under which a scheduler tracks the count: the unhandled-task metric is enabled, capacity is positive, or a non-no-op on-ramp counter is supplied. Any of the three triggers tracking. The first two are the configuration-record fields; the third is a framework-internal lever. In the consensus-layer pattern, capacity > 0 is the usual reason tracking exists.

## Engagement moves

Three moments warrant a choice of teaching technique. The tutor varies move type across them so the session does not become a sequence of similar predictions or walks. The moments are ordered so prediction-and-reveal first appears only after the lesson has built up enough material for the prediction to be answerable.

### Moment `moment-scheduler-anatomy` — what a scheduler owns

Sits at Chunk 1. Load-bearing because the rest of the cluster — backpressure, health, flush, squelch — is defined in terms of the queue / type / output-wire trio established here. If the learner does not internalise that a scheduler is more than "a thing that runs code," the later mechanism will feel arbitrary.

**Move A — worked example with self-explanation.**

- **Diagnosis tag**: the learner is new to this framework (true for almost any first-time Cluster 0 session) and asks for an example or hesitates on the abstractions.
- **Example**: walk a single `SequentialTaskScheduler` from a freshly-constructed state through one task's lifecycle: `put` enters via the input wire, the on-ramp counter increments, `scheduleTask` enqueues a node in the placeholder list, the pool runs the handler, the return value is forwarded to the primary output wire, the off-ramp counter decrements. Anchor against [`SequentialTaskScheduler.scheduleTask`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/internal/SequentialTaskScheduler.java#L114-L132) and [`put`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/internal/SequentialTaskScheduler.java#L86-L91). Mark the on-ramp increment and the off-ramp decrement as the load-bearing lines.
- **Self-explanation prompt at the on-ramp**: "Which later mechanism is going to read this counter, and what would it report if the increment were skipped?" Canonical inference: the health monitor reads it; skipping the increment would silently understate queue depth and the unhealthy duration would never trigger.
- **Self-explanation prompt at the off-ramp**: "What invariant does decrementing here guarantee, and what breaks if the decrement happens before the handler returns?" Canonical inference: the counter measures *unprocessed* tasks (per the lifecycle javadoc); decrementing before the handler returns would mean the counter no longer matches the lifecycle definition, and `flush()` could return while a handler was still in flight.

**Move B — direct walk with cued check.**

- **Diagnosis tag**: the learner has wiring-framework experience already (e.g. has worked on `PlatformWiring`) and the example would be redundant.
- **Move**: state the three things a scheduler owns and the one thing it does not, then cue: "Given a `SEQUENTIAL` scheduler with one bound handler and an unhandled-task capacity of 100, what is the maximum value of `getUnprocessedTaskCount()` under `PUT` insertion, and why?" Canonical answer: 100, because `PUT` blocks on the on-ramp counter at the capacity threshold. The check verifies that the learner has connected the counter to the capacity rather than treating capacity as advisory.

### Moment `moment-type-choice` — the three-axis collapse

Sits at Chunk 2. This is the threshold-concept moment: the conceptual hinge of the lesson is treating `TaskSchedulerType` as a deliberate three-axis decision rather than a flat menu. Transfer to later lessons (`c0-03-backpressure-modes`, `c0-04-health-monitor-mechanics`, every `*WiringConfig` decision the learner will read) depends on this landing.

**Move A — contrasting cases with comparison prompt.**

- **Diagnosis tag**: threshold concept; transfer is the explicit goal.
- **Reference**: see the *Contrasting cases material* section below for the three cases (`SEQUENTIAL` vs `SEQUENTIAL_THREAD` vs `CONCURRENT` for a hypothetical component; `SEQUENTIAL` vs `DIRECT` for a stateless filter; `CONCURRENT` vs `DIRECT_THREADSAFE` for a thread-safe transformer).
- **Comparison prompt**: "What is the same across each pair of cases? What is different? Which axis (ordering, parallelism, thread-affinity / queueing) is each pair isolating?"
- **Deep invariant the cases surface**: the type collapses three independent design choices into one pick, and the framework offers no value for every combination — `DIRECT` and `DIRECT_THREADSAFE` are the only choices that combine "no queue" with "executes on caller's thread," and the choice between them is a parallelism choice. Surface the gap in the table explicitly: there is no "no-queue, dedicated-thread" type, because the dedicated thread *is* the queue boundary.

**Move B — worked example with self-explanation.**

- **Diagnosis tag**: the learner is new to this framework and the contrasting-cases move is too abstract until at least one concrete example anchors the axes.
- **Example**: walk the `EventDeduplicator`'s `wiringConfig.eventDeduplicator()` from the per-platform configuration — pick a real value, e.g. `SEQUENTIAL CAPACITY(500)`, and trace what the three axes resolve to (ordering: yes, because consecutive deduplicate calls must observe each other's writes; parallelism: no, because the deduplicator's state is not thread-safe; thread-affinity: shared pool, because a dedicated thread is not justified). Then ask: "What would change if you flipped this to `CONCURRENT`?" Canonical answer: the deduplicator's `eventsBySignature` map would need synchronisation, and the topology might still violate the `DIRECT` reachability rules downstream.

**Move C — prediction-and-reveal (use only after the framework has been introduced).**

- **Diagnosis tag**: the learner is showing fluency on the axes — they have correctly described one case and the tutor wants to probe transfer.
- **Framing**: "You are adding a new component, a `GossipFanoutDispatcher`, that takes an event and pushes it to several downstream consumers. It is stateless, the downstream consumers do their own ordering, and it sits between gossip and intake on the hot path. Before I tell you the canonical pick, what's your prediction on the type, and why?"
- **`answer_shape`**: a single type name plus a justification that names two of the three axes.
- **`alternative_correct_answers`**:
  - `DIRECT` with the reasoning "stateless, on a hot path, no queueing needed, only one upstream — fits the reachability rules." Credit; consolidate by noting that the legality check would still need to verify only one `SEQUENTIAL`/`SEQUENTIAL_THREAD` predecessor at graph-walk time.
  - `DIRECT_THREADSAFE` with "stateless and so threadsafe by definition; this also relaxes the single-predecessor rule." Credit; consolidate by noting that `DIRECT_THREADSAFE` is the right pick if the predecessor is `CONCURRENT` or if multiple sequential predecessors exist.
  - `SEQUENTIAL` with "default and safe." Half-credit — correct that it would work, but misses the on-axis reasoning the prompt asked for. Consolidate by walking the three axes and noting that `SEQUENTIAL` over-pays on the parallelism axis where the component does not need ordering at all.
- **Canonical answer**: `DIRECT_THREADSAFE`, anchored at [the javadoc L52-L61](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L52-L61), because a stateless fanout that runs on the caller's thread does not need its own queue and does not need ordering, and the threadsafe variant relaxes the reachability constraint.
- **Consolidation**: name the three axes the prediction's justification touched, name any axis it missed, and surface the `DIRECT` vs `DIRECT_THREADSAFE` choice as the local instance of the parallelism axis.

### Moment `moment-three-step-pattern` — why the order is enforceable

Sits at Chunk 3. Load-bearing because every default consensus-layer module repeats this pattern, and an engineer reading any `initialize(...)` method is reading three sequential steps that look interchangeable on first pass.

**Move A — worked example with self-explanation.**

- **Diagnosis tag**: the learner is unfamiliar with the deferred-bind machinery (likely for any first-pass Cluster 0 session). Predictions about reordering land badly here because the actual rules are non-obvious — only one ordering is enforceable, and which one is not the one most learners guess.
- **Example**: walk `getOrBuildInputWire` at [L567-L621](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L567-L621) — the method `getInputWire` ultimately calls — and trace what it does on two paths: (i) `component == null` (the canonical solder-before-bind path) and (ii) `component != null` (a hypothetical solder-after-bind path). Mark line L580 (`model.isRunning()` guard), line L599 (the `component == null` branch), and line L607 (the `else` branch that binds inline) as the three load-bearing lines.
- **Self-explanation prompt at L599**: "Why does this branch defer binding instead of throwing? What kind of module-level pattern is this supporting?" Canonical inference: the framework is supporting the construct-all-then-solder-all-then-bind-all module pattern. If this branch threw, every module would have to construct its instances before any wire was soldered, which would force solder lines to be interleaved with instance construction.
- **Self-explanation prompt at L580**: "What does this guard prevent, and why is it the only hard runtime guard?" Canonical inference: it prevents the wiring graph from growing after the model has started running. The model's validation passes (`checkForCyclicalBackpressure`, `checkForUnboundInputWires`) run at start time; adding a wire afterward would skip validation.
- **Self-explanation prompt at L607**: "What does the else branch tell you about the order of solder and bind?" Canonical inference: at the framework level, the two are interchangeable. The canonical "construct → solder → bind" order is a module-level convention, not a framework-level rule.

**Move B — prediction-and-reveal.**

- **Diagnosis tag**: the learner has already worked through Move A or has prior experience with `ComponentWiring`, and is ready for a transfer probe rather than a walked example.
- **Framing**: "We've seen the canonical three-step pattern. Two orderings are actually enforced by the framework — at compile time or at runtime — and one is convention. Predict which is which: construct vs. solder, construct vs. bind, solder vs. bind."
- **`answer_shape`**: three pairs of orderings, each labeled "enforced at compile time," "enforced at runtime," or "convention only."
- **`alternative_correct_answers`**:
  - "Construct before solder, enforced at compile time; construct before bind, enforced at compile time; solder vs. bind, convention only." Credit fully — this is the canonical answer.
  - "Construct before solder, enforced at compile time; construct before bind, enforced at compile time; solder before bind, enforced at runtime by the `inputsToBind` machinery." Half-correct — the first two are right, the third over-claims. Consolidate by walking the L607 branch.
  - "All three are convention." Wrong. Consolidate by noting the variable reference: a module that calls `bind(...)` or `getInputWire(...)` before the `ComponentWiring` is constructed does not compile.
- **Canonical answer**: construct-before-solder and construct-before-bind are enforced at compile time (the variable does not exist). Solder-vs-bind for a single component is convention; either ordering works at the framework level. The only hard runtime guard in the area is `model.isRunning()`, which prevents *any* `getInputWire(...)` call after the wiring model has started ([L580-L582](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L580-L582)).
- **Consolidation**: name explicitly that the canonical "construct → solder → bind" order is a *module-level* discipline, not a framework-level enforcement. The reason it exists is module-level too: components reach for each other's wires during solder, so construct-all has to precede solder-all, and instances often consume already-soldered wires in their constructors so bind-all goes last. Tie the consolidation back to the load-bearing observation: the framework supplies a capability (deferred bind via `inputsToBind`), and the module uses that capability to keep wiring and instance construction in separate phases.

**Move C — direct walk with cued check.**

- **Diagnosis tag**: the learner has read enough `initialize(...)` methods that the prediction is redundant.
- **Move**: walk the three lines from `DefaultEventIntakeModule.initialize` directly, naming what each one accomplishes. Then cue: "If `EventDeduplicator` is constructed inside step 1 and passed to `new ComponentWiring<>(...)` instead of in step 3, what changes — and does that change work?" Canonical answer: it does not work because the canonical constructor takes `Class<COMPONENT>`, not `COMPONENT` ([L113-L116](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L113-L118)). The deprecated constructor at [L91-L104](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L91-L104) takes a pre-built `TaskScheduler` rather than an instance; there is no `ComponentWiring` constructor that takes the component instance. The deferred bind is the only path to register a component instance with a `ComponentWiring`.

## Contrasting cases material

Three pairs of cases. The Engagement moves section references this material at `moment-type-choice` when it lists the contrasting-cases move.

### Case pair 1 — `SEQUENTIAL` vs `SEQUENTIAL_THREAD`

- **Case A — `SEQUENTIAL`**: tasks run one at a time on a shared `ForkJoinPool`. Between tasks there is a happens-before relation. The pool thread that runs task N may not be the pool thread that runs task N+1.
- **Case B — `SEQUENTIAL_THREAD`**: tasks run one at a time on a dedicated thread. Between tasks there is a happens-before relation. The thread is pinned to this scheduler for the lifetime of the wiring model.
- **Surface differences**: pool vs dedicated thread.
- **What is the same**: ordering (yes), parallelism (no), queue presence (yes).
- **Deep invariant**: ordering and thread-affinity are independent axes. `SEQUENTIAL_THREAD` exists for cases where the dedicated thread matters (real-time work, thread-local state, debugging) and `SEQUENTIAL` exists for the common case where it does not.

### Case pair 2 — `SEQUENTIAL` vs `DIRECT`

- **Case A — `SEQUENTIAL`**: a stateless filter is given its own scheduler with a queue. Producer enqueues, filter dequeues, output is forwarded.
- **Case B — `DIRECT`**: the same filter has no queue. The upstream scheduler's task, while running, calls into the filter on its own thread; the filter's return value is forwarded inline.
- **Surface differences**: queue / no queue; separate dispatch / inline dispatch.
- **What is the same**: ordering (yes), parallelism (no).
- **Deep invariant**: queue presence is a thread-affinity choice, not an ordering choice. Both cases preserve the ordering invariant. `DIRECT` trades the buffer between producer and consumer (and therefore the ability to absorb bursts) for elimination of dispatch overhead. The trade is only legal under the reachability constraints in the `DIRECT` javadoc.

### Case pair 3 — `CONCURRENT` vs `DIRECT_THREADSAFE`

- **Case A — `CONCURRENT`**: a threadsafe transformer runs on its own scheduler with a queue. Multiple tasks execute in parallel on the shared pool. Producer enqueues, the framework dispatches; ordering is not preserved.
- **Case B — `DIRECT_THREADSAFE`**: the same transformer has no queue. Each upstream caller invokes it inline on its own thread. Multiple callers may invoke it concurrently.
- **Surface differences**: queue / no queue; separate dispatch / inline dispatch.
- **What is the same**: ordering (no), parallelism (yes).
- **Deep invariant**: the parallelism axis is independent of the queueing axis. Both cases require the handler to be threadsafe (the framework does not enforce this in either case — see [`DIRECT_THREADSAFE` javadoc L57-L60](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/builders/TaskSchedulerType.java#L57-L60)). The choice between them is again about whether the buffer between producer and consumer is needed — `CONCURRENT` keeps it, `DIRECT_THREADSAFE` skips it.

The deep invariant across all three pairs: the type collapses ordering, parallelism, and thread-affinity / queueing into one pick. Reading the menu as three axes (and noticing which combinations the framework does *not* offer) is the transfer-producing rule.

## Completion problems

Three problems with progressive fading. The tutor escalates each problem's hint ladder if the learner stalls.

### Problem 1 — pick a type, given the constraints (small step blank)

**Statement.** A component called `BranchDetector` keeps a per-creator map of recently-seen events to detect branches. The map is mutated on every event. It sits one hop downstream of the signature validator, which is `SEQUENTIAL`. You have decided the type should be `SEQUENTIAL` and the capacity should be 500 — but justify in two sentences why neither `CONCURRENT` nor `DIRECT_THREADSAFE` is appropriate.

**Hint ladder.**

1. *Look first at*: the three-axis table in Chunk 2.
2. *Focused question*: which axis is the mutable map sensitive to?
3. *Partial walkthrough*: the map is not threadsafe, so the type must impose either ordering or thread-affinity such that two tasks never execute concurrently.
4. *Full answer*: `CONCURRENT` and `DIRECT_THREADSAFE` both have "parallelism: yes" (Chunk 2 table). Either would let two tasks mutate the map concurrently without external synchronisation. `SEQUENTIAL` constrains parallelism to "no" so the map can be left unsynchronised, which is the consensus-layer pattern for stateful components.

**Mechanism it exercises**: the parallelism axis of the type choice and its interaction with handler-internal state.

### Problem 2 — pick a type and capacity (one step blank)

**Statement.** A component called `EventHashLogger` takes an event and writes a line to a structured log. It is stateless, holds no resources between calls, and sits on the hot path between intake's primary output and PCES. The upstream scheduler is `SEQUENTIAL`. Pick a `TaskSchedulerType` and a capacity, with a one-sentence justification for each.

**Hint ladder.**

1. *Look first at*: the `DIRECT` javadoc reachability rules and the `*WiringConfig` pattern from Chunk 4.
2. *Focused question*: does this component need to absorb bursts from its single upstream?
3. *Partial walkthrough*: stateless + single upstream + on the hot path → candidate is `DIRECT`. Capacity is moot for `DIRECT` because there is no queue.
4. *Full answer*: `DIRECT` with capacity `0` (unbounded, but irrelevant because the scheduler has no queue). The reachability check passes because the upstream is a single `SEQUENTIAL` scheduler. If the upstream were `CONCURRENT`, the answer would change to `DIRECT_THREADSAFE` and the same capacity reasoning would apply.

**Mechanism it exercises**: the queueing axis and the `DIRECT` reachability rules.

### Problem 3 — walk the three-step pattern (more blank)

**Statement.** You are adding a new component, `EventReplayCounter`, to `DefaultEventIntakeModule`. It is a stateful counter (ordering matters) with capacity 200. Write the three lines of `initialize(...)` that construct, solder (from `pcesWriterWiring.getOutputWire()` to `EventReplayCounter::handleEvent`), and bind. Then say which of the three orderings are enforced (and at what point — compile or runtime) and which are convention.

**Hint ladder.**

1. *Look first at*: the canonical example in Chunk 3 and the `getOrBuildInputWire` branch at [L599-L621](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L599-L621).
2. *Focused question*: which line, if moved earlier, refers to a variable that does not yet exist? And what does `getInputWire(...)` do when called after `bind(...)`?
3. *Partial walkthrough*: solder and bind both call methods on `eventReplayCounterWiring`. Construct must precede both at compile time. Solder and bind for a single component can interleave at the framework level because `getOrBuildInputWire` handles both `component == null` and `component != null` paths.
4. *Full answer*:
   ```java
   this.eventReplayCounterWiring = new ComponentWiring<>(model, EventReplayCounter.class, wiringConfig.eventReplayCounter());
   pcesWriterWiring.getOutputWire().solderTo(eventReplayCounterWiring.getInputWire(EventReplayCounter::handleEvent));
   eventReplayCounterWiring.bind(new StandardEventReplayCounter(...));
   ```
   - **Construct before solder** — enforced at compile time (variable does not exist otherwise).
   - **Construct before bind** — enforced at compile time (same reason).
   - **Solder before bind** — convention only. Reversing them still works: when `getInputWire(...)` is called after `bind(...)`, the `component != null` branch at [L607-L621](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/component/ComponentWiring.java#L607-L621) binds the new wire inline. The canonical convention reflects the module-level pattern (construct-all → solder-all → bind-all across every component), not a framework-level rule.

**Mechanism it exercises**: the deferred-bind machinery, the difference between framework-enforced and module-convention ordering, and the `model.isRunning()` runtime guard that *is* enforced (no new input wires after the model has started).

## Delta callout

`[TBD: delta-map/wiring-framework.md is not yet written]` — `delta-map/` currently contains only a README ([note in `curriculum.md`](../curriculum.md)). When the delta-map entry lands, this callout should summarise its status (`done` / `partial` / `not started` / `divergent`) for the material this lesson covers and link the file. Surfaced under Open questions until then.

## Transfer prompt

The learner now has a scheduler that owns a queue, a thread-execution policy, and a primary output wire, and a wrapper that produces input wires from method references. The next lesson (`c0-02-wires-and-soldering`) covers the wire layer — how output wires connect to input wires, and what the connection means.

Before that lesson begins, predict: given two components, each with its own scheduler, what would the framework need to provide to let data flow from the first's primary output wire to the second's input? Name at least two operations the framework would have to support, and one validity constraint it would have to enforce. The answer in `c0-02` will introduce `solderTo(...)` and the wiring graph's validation pass; comparing the prediction to that answer is the bridge.

## Close-out retrieval

**Free-recall summary.** Prompt: "In your own words, what does `TaskSchedulerType` collapse into a single choice, and what is the consequence for the rest of the wiring lifecycle?" Canonical answer for consolidation: three axes — ordering, parallelism, thread-affinity / queueing. The consequence is that every later mechanism that interacts with the scheduler (capacity, flush, squelch, health-monitor input) inherits the choice. A `CONCURRENT` scheduler cannot have ordering retro-fitted; a `DIRECT` scheduler cannot have a queue retro-fitted; a `SEQUENTIAL_THREAD` scheduler's thread is the dispatch boundary. The type is upstream of every later decision.

**Successive-relearning tags.** One threshold concept this lesson establishes: `TaskSchedulerType` as a deliberate three-axis choice. The tutor adds it to the learner's relearning queue at:

- **Day 1** — a quick recall: "name the three axes and one type that lives on each combination."
- **Day 3** — a transfer probe: pose a hypothetical component (provided by the tutor at probe time) and ask for the type pick plus justification on two of the three axes.
- **Two weeks** — a deeper transfer: pose a wiring violation (e.g. a `CONCURRENT` predecessor of a `DIRECT` scheduler) and ask the learner to name the failure mode and the fix.

## Open questions

- `[TBD: delta-map/wiring-framework.md]` — file does not yet exist; the Delta callout cannot reference current status. Filed under [curriculum.md notes for future authoring runs](../curriculum.md).
- `[TBD: kb_glossary_terms]` — the consensus-layer glossary at `platform-sdk/docs/hashgraphGlossary.md` does not currently contain the wiring-specific terms ("wire", "scheduler", "soldering", "transformer"), per the cross-references in [`wiring-framework.md`](../../architecture/topics/wiring-framework.md#cross-references). Once those entries are added, populate `kb_glossary_terms` on this lesson's frontmatter.
- `[TBD: kb_invariants]` — `consensus-layer/invariants.md` does not yet exist. When it does, the most likely invariants this lesson rests on are: (a) thread confinement for non-`CONCURRENT` schedulers; (b) the `DIRECT` reachability rule; (c) the lifecycle stages of a task ([`TaskScheduler.java#L22-L35`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/schedulers/TaskScheduler.java#L22-L35)). Populate once the catalog lands.
- `[TBD: kb_decisions]` — `decisions/` currently contains only a README. If an ADR is added for the choice to keep `ComponentWiring` as the canonical wrapper rather than expose `WiringModel.schedulerBuilder` directly, link it.
