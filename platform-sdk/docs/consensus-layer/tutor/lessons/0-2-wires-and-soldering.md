---
lesson_id: 0-2-wires-and-soldering
cluster: '0'
title: Wires and soldering
prerequisites: [0-1-task-schedulers]
kb_refs:
  topics: [wiring-framework]
  concepts: []
  invariants: []
  glossary_terms: []
learning_objectives:
  - Name the role of `InputWire<IN>` and `OutputWire<OUT>` in the wiring framework, and the distinction between a scheduler's primary and secondary output wires.
  - Distinguish the three `SolderType` values (`PUT`, `INJECT`, `OFFER`) and the three corresponding put-paths on an input wire, and explain the per-edge policy decision each `solderTo` call makes.
  - Recognise the in-line `OutputWire` transforms (`buildFilter`, `buildTransformer`, `buildAdvancedTransformer`, `buildSplitter`) and the threading model they run under.
estimated_read_minutes: 9
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---

# Wires and soldering

## Where we are

[Lesson 0-1](0-1-task-schedulers.md) closed at the seam where each scheduler exposes a primary output wire and accepts input wires built on top of it. Cluster 0 is the substrate every later lesson stands on, and the second piece of that substrate is the wires themselves: the typed, multi-fan-out connections between schedulers, and the `solderTo` call that joins one scheduler's output to another's input. This lesson defines the wires and the soldering API. It deliberately defers per-scheduler queue capacity, the cyclic-backpressure hazard, and the health-monitor signal to [lesson 0-3](0-3-backpressure-at-the-wire.md), where the three `SolderType` values introduced here become the policy knobs that shape backpressure in practice.

## Motivating problem

Each scheduler from lesson 0-1 owns one component's threading and queueing. But components do not exist in isolation — gossip hands events to event-intake; event-intake hands validated events to PCES; PCES, once an event is durable, hands it to the hashgraph and to gossip. That set of hand-offs is the consensus layer's main data flow, and it has three demands the substrate has to satisfy.

It must be **typed**. Compile time should refuse a connection between an `OutputWire<PlatformEvent>` and an `InputWire<EventWindow>`; the consensus layer is too large to debug that mistake at runtime.

It must be **fan-outable**. The same persisted event is consumed by hashgraph, by gossip, and by the event creator. None of them should know about the others; the producer should not have a list of consumers in its source.

It must be **graph-walkable**. The wiring model needs to validate the assembled topology — cycles, illegal `DIRECT` placements, unbound input wires — and emit Mermaid diagrams for documentation. Connections that exist only as method references inside business logic are invisible to that walk.

The wiring framework's answer is to lift connections into first-class objects: an `InputWire<IN>` per entry on a scheduler, an `OutputWire<OUT>` per exit, and a `solderTo` call that registers each edge with the model. Business logic does not name its consumers; it returns values, and the wires carry them.

## Concept

An `InputWire<IN>` (see the [InputWire and OutputWire](../../architecture/topics/wiring-framework.md#inputwire-and-outputwire) section of the topic file for the authoritative description) is the entry point of a scheduler. It exposes three put-paths — `put(data)` blocks the caller when the scheduler's queue is at capacity, `offer(data)` returns `false` rather than blocking, and `inject(data)` bypasses capacity entirely. The choice between the three is not made at the call site; it is made *at soldering time* by picking a `SolderType` for the edge.

`scheduler.buildInputWire(name)` actually returns a `BindableInputWire<IN, OUT>`. The caller attaches business logic with `bind(Function<IN, OUT>)` — the return value is auto-forwarded to the scheduler's primary output wire — or `bindConsumer(Consumer<IN>)` when the bound logic produces no value. Many input wires can share one scheduler; they all feed the same queue and execute under the same threading policy.

An `OutputWire<OUT>` is the source of every connection out of a scheduler, and it is the object on which soldering and in-line transforms are method calls. Each scheduler always has one **primary output wire** (`scheduler.getOutputWire()`) whose values are auto-forwarded from `bind`'s return; per the topic file's [Soldering](../../architecture/topics/wiring-framework.md#soldering) section, additional **secondary output wires** are created with `scheduler.buildSecondaryOutputWire()` and are pushed explicitly by code running inside the scheduler. The convention is named in `TaskScheduler`'s javadoc: pushing into a secondary output wire from outside the owning scheduler is a violation. Secondary wires exist for fan-out side-channels — a component whose primary output type is `Void` because no single canonical value comes out per task, but whose work nonetheless produces *several* output streams.

## How it works

The connection from one scheduler to another is a single call:

```java
outputWire.solderTo(inputWire);                       // SolderType.PUT, the default
outputWire.solderTo(inputWire, SolderType.INJECT);    // chosen explicitly
```

`SolderType` has three values, and each one selects which of `InputWire`'s three put-paths the soldered edge will use:

- **`PUT`** — the consumer's `put(data)`. Blocks the producer when the consumer is at capacity. The default and the only path that participates in backpressure. Pick `PUT` when it is correct for the producer to slow down if the consumer falls behind.
- **`INJECT`** — the consumer's `inject(data)`. Bypasses capacity unconditionally. Pick `INJECT` when the producer must not be slowed down — typically because slowing it down would deadlock a cycle, or because the data is already authoritative and dropping it would corrupt downstream state. Cycle-driven uses of `INJECT` are developed in [lesson 0-3](0-3-backpressure-at-the-wire.md).
- **`OFFER`** — the consumer's `offer(data)`. Drops the item silently when the consumer is at capacity. Pick `OFFER` when it is acceptable to lose data under load — typically a low-priority observability or heartbeat stream.

The choice is per-edge, not per-component. The same `OutputWire<T>` may solder `PUT` to one consumer and `INJECT` to another in adjacent lines, because the policy decision belongs to the edge, not to either endpoint.

`OutputWire` also carries a small in-line-transform surface. Each method below builds an internal `DIRECT_THREADSAFE` scheduler — execution happens on whichever thread is forwarding data along the upstream output wire — and returns a new `OutputWire` that can be soldered or transformed further:

- `buildFilter(name, inputLabel, predicate)` — predicate-gated fan-through.
- `buildTransformer(name, inputLabel, function)` — in-line type change. `null` returns are not forwarded, so a transformer can also act as a typed filter.
- `buildAdvancedTransformer(transformation)` — same shape with `inputCleanup` and `outputCleanup` hooks for resource management. Used when the transformed value carries a reference count or other lifecycle obligation.
- `buildSplitter(name, inputLabel)` — the canonical concrete transformer, backed by `WireListSplitter`: a `List<T>` arriving on the input produces one downstream task per element.

Because transforms run on `DIRECT_THREADSAFE`, the function you pass in must be threadsafe — the framework offers no synchronisation around it.

## Worked example

The build side and the solder side of a real edge sit in different files. Use `GossipWiring` to see wires built, and `PlatformWiring` to see the same wires connected.

`GossipWiring`'s constructor builds the wires gossip exposes:

```java
eventInput = scheduler.buildInputWire("events to gossip");
eventWindowInput = scheduler.buildInputWire("event window");
eventOutput = scheduler.buildSecondaryOutputWire();
syncProgressOutput = scheduler.buildSecondaryOutputWire();

startInput = scheduler.buildInputWire("start");
stopInput = scheduler.buildInputWire("stop");
clearInput = scheduler.buildInputWire("clear");
```

A few things to read out of this snippet. First, multiple input wires share one scheduler — every one of these `BindableInputWire`s feeds the same queue under the same threading policy. Second, gossip uses *secondary* output wires for its outputs. Its scheduler is parameterised `<Void>` because no single canonical value comes out per input task; the events received from peers and the sync-progress reports are pushed by code running inside the gossip scheduler into wires it owns. Third, the input wire names ("events to gossip", "start", "clear") are the labels that show up in the model's wiring diagram — they are documentation, and worth picking carefully.

`PlatformWiring`'s constructor connects those wires to the rest of the system. The interesting passage is the persistence-then-fan-out around `writtenEventOutputWire`:

```java
final OutputWire<PlatformEvent> writtenEventOutputWire =
        components.pcesModule().writtenEventsOutputWire();

// Make sure that an event is persisted before being sent to consensus...
writtenEventOutputWire.solderTo(components.hashgraphModule().eventInputWire());

// Make sure events are persisted before being gossipped. This prevents accidental
// branching in the case where an event is created, gossipped, and then the node
// crashes before the event is persisted.
writtenEventOutputWire.solderTo(components.gossipModule().eventToGossipInputWire(), INJECT);

// Avoid using events as parents before they are persisted
writtenEventOutputWire.solderTo(components.eventCreatorModule().orderedEventInputWire());
```

Three solders off one output wire, and the policy choice is on the middle one. The hashgraph and event-creator edges take the default `PUT` — if either falls behind, it is correct for PCES to slow down on the next written event. The gossip edge takes `INJECT` — gossip *must* receive every persisted event regardless of its queue depth, because dropping or stalling here is what the rationale comment is preventing: a written-but-never-gossiped event becomes invisible to peers, and the next event the node creates risks being a fork. The `SolderType` is where that "must not slow down" decision is captured in the wiring graph.

A second pattern from the same file shows the in-line transforms in use:

```java
final OutputWire<ReservedSignedState> splitReservedSignedStateWire = components
        .stateSignatureCollectorWiring()
        .getOutputWire()
        .buildSplitter("reservedStateSplitter", "reserved state lists");

final OutputWire<ReservedSignedState> allReservedSignedStatesWire =
        splitReservedSignedStateWire.buildAdvancedTransformer(new SignedStateReserver("allStatesReserver"));
```

`buildSplitter` turns a `List<ReservedSignedState>` stream into a stream of individual `ReservedSignedState`s, and `buildAdvancedTransformer` adds a reservation-management hook so the downstream consumers can fan out without losing the reference count. Both run on the in-line `DIRECT_THREADSAFE` schedulers the framework hides; the threading guarantee for the bound logic is the producer's thread.

## Code anchor

- [`InputWire.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/InputWire.java) — the abstract entry point with the `put` / `offer` / `inject` triple.
- [`BindableInputWire.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/input/BindableInputWire.java) — what `buildInputWire` returns; carries `bind(Function)` and `bindConsumer(Consumer)`.
- [`OutputWire.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/output/OutputWire.java) — the soldering surface and the in-line-transform surface live here.
- [`SolderType.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/wires/SolderType.java) — the three-value enum with per-value javadoc that is the authoritative description of `PUT`, `INJECT`, `OFFER`.
- [`WireListSplitter.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-component-framework/src/main/java/com/swirlds/component/framework/transformers/WireListSplitter.java) — the canonical concrete transformer; built via `OutputWire.buildSplitter`.
- [`GossipWiring.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/GossipWiring.java) — the constructor shown above, building input wires and secondary output wires on the gossip scheduler.
- [`PlatformWiring.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java) — the connection site for the consensus layer's main data flow; the `writtenEventOutputWire` block and the `splitReservedSignedStateWire` block are both in this file.

## Delta callout

No `delta-map/wiring-framework.md` entry exists yet. The wiring-framework topic's [Future state sidebar](../../architecture/topics/wiring-framework.md#future-state-sidebar) describes a module-API-level backpressure mechanism that sits above the wire-level mechanism; that material concerns capacity and pull-style throttling, not the wire and soldering primitives covered here, and is developed in [lesson 0-3](0-3-backpressure-at-the-wire.md). No wires-and-soldering delta is currently captured.

## Comprehension prompt

Two openings to take into the tutor chat. First: imagine you have a known cycle in the wiring graph — component A solders to component B, B to C, and C back to A. The wiring model has flagged it as cyclic backpressure. Which edge would you flip from `PUT` to `INJECT`, and what would you have to know about the work along that edge before being comfortable with the choice? Second: the in-line transforms (`buildFilter`, `buildTransformer`, `buildAdvancedTransformer`, `buildSplitter`) all run on a hidden `DIRECT_THREADSAFE` scheduler. Walk through what that says about the latency cost of inserting one of them into a hot edge, and what it says about the demands placed on the function you pass in.

## Open questions

> - [TBD] The wiring-framework topic asks whether a component reaches for a *secondary* output wire only when it has no single canonical output type per task, or whether there is a different rule of thumb (topic line 59). This lesson presents the `Void` primary plus secondary fan-out as the gossip-style pattern; SME confirmation would let the lesson promote that to a recommendation and name the symmetric case (single-canonical-output-per-task ⇒ primary-only) explicitly.
> - [TBD] Do secondary output wires participate in `flush()` and squelching the same way primary wires do (topic line 61)? The lesson defers to the topic file; SME confirmation belongs in `wiring-framework.md` and would be cited by `INV-NNN` once the invariants catalog populates.
> - [TBD] Is soldering performed once at wiring assembly time and immutable thereafter, or can wires be soldered/unsoldered after `WiringModel.start()` (topic line 71)? `OutputWire.solderTo`'s javadoc says forwarding "should be fully configured prior to data being inserted into the system" and that adding destinations after that is "not thread safe and has undefined behavior" — i.e., not enforced but contractually one-shot. SME confirmation would let the lesson promote this to a hard rule.
> - [TBD] When a soldered `OFFER` edge drops on a full input wire, is the drop silent, counted as a metric, or logged (topic line 73)? And is `OFFER` actually used anywhere in the consensus layer today? The lesson presents `OFFER` as the "low-priority observability or heartbeat stream" choice; the only `OFFER` site this run found in `PlatformWiring` is the heartbeat-into-platform-monitor edge, which fits that framing but is the only data point.
> - [TBD] The topic file flags `AdvancedTransformation` as possibly speculative (topic line 81). It is not — `PlatformWiring` constructs `splitReservedSignedStateWire.buildAdvancedTransformer(new SignedStateReserver(...))` to manage signed-state reservations across fan-out. SME confirmation would let the topic file's [TBD] be closed and a real worked example pinned.
> - [TBD] No `glossary.md` exists at the consensus-layer KB root yet. The wiring-specific terms introduced here — *input wire*, *output wire*, *primary vs secondary output wire*, *soldering*, `SolderType`, *transformer*, *splitter* — should be promoted to the glossary once it is created, and the lesson updated to link to them rather than introducing them inline. Same gap is flagged in 0-1.
> - [TBD] No `invariants.md` catalog exists yet, so `kb_refs.invariants` is empty. Two candidate invariants surface here: *every soldered edge has exactly one `SolderType`*; *a secondary output wire is only pushed to from code executing inside its owning scheduler*. Cite as `INV-NNN` once registered.
> - [TBD] No `delta-map/wiring-framework.md` exists. The Delta callout above defers to the topic file's Future-state sidebar and to lesson 0-3, which is the correct authority for now but should be replaced with a delta-map link when one lands.
> - [TBD] The wiring-framework topic file's pinned `GossipWiring` line numbers were already noted as drifted in 0-1's Open questions; this lesson uses the snippet without a hard line reference but verifies the call sites at `GossipWiring.java` lines 96–107 and `PlatformWiring.java` lines 65–93 against the current working tree.

## Where we're going next

The next lesson, [0-3 Backpressure at the wire](0-3-backpressure-at-the-wire.md), takes the queue capacity from 0-1 and the three `SolderType` values from this lesson and develops the wire-level backpressure story: how `PUT` actually transmits pressure through the graph, the cyclic-backpressure hazard the model detects via `checkForCyclicalBackpressure`, the `INJECT`-an-edge remedy named here as a forward reference, and the connection from a scheduler's queue depth to the health-monitor signal that drove the [Pass 1 stress scenario](pass1-4-event-creation-under-stress.md).
