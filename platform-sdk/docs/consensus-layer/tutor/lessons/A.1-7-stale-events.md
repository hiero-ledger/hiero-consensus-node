---
lesson_id: A.1-7-stale-events
cluster: A.1
title: Stale events
prerequisites: [A.1-6-event-lifecycle-and-birth-round]
kb_refs:
  topics: [hashgraph]
  concepts: [stale-events, event-lifecycle, birth-round]
  invariants: []
  glossary_terms: []
learning_objectives:
  - Distinguish *stale* (a post-admission fate) from *ancient* (a lifecycle state) and explain why pre-admission rejection of a too-old event is not a stale event in current code.
  - Locate the single partition site in `DefaultConsensusEngine.addEvent` where the linker's newly-ancient list splits into "reached consensus" and "stale", and identify the `EventImpl.isConsensus()` predicate as the discriminator.
  - Trace the `staleEvents` output from `ConsensusEngineOutput` through the `DefaultHashgraphModule` transformer/splitter and the `PlatformWiring` soldering all the way to the application-registered `Consumer<PlatformEvent>` callback, and explain why `ConsensusEngineMetrics.reportStaleEvent` filters to self-events while the output wire does not.
  - Name the gap acknowledged in `PlatformBuilder.withStaleEventCallback`: events that went stale during a restart or reconnect window are not necessarily reported, because the in-memory linker that does the partitioning was not present to observe the transition.
estimated_read_minutes: 7
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---

# Stale events

## Where we are

This is the seventh lesson in cluster A.1 (Hashgraph Algorithm). [A.1-6 event lifecycle and birth round](A.1-6-event-lifecycle-and-birth-round.md) walked the four regions an event passes through relative to the running `EventWindow` — *future*, *admitted*, *ancient*, *expired* — and the per-event pipeline in `DefaultConsensusEngine.addEvent` that classifies each arriving event and evicts in batches when the window advances. It deferred one strand of that pipeline: the events that the linker unlinks on `setEventWindow` are partitioned, and the half that did not reach consensus is reported on a separate output wire. This lesson takes that strand. The next lesson, [A.1-8 hashgraph algorithm synthesis](A.1-8-hashgraph-algorithm-synthesis.md), is the cluster-closing walkthrough that puts every A.1 component on the same picture.

## Motivating problem

Once a node has admitted an event into the DAG, that event has a transaction payload the network may or may not eventually order. Most events do reach consensus — the algorithm of [A.1-2](A.1-2-rounds-and-witnesses.md) through [A.1-5](A.1-5-judges.md) is biased toward including everything in the non-ancient slice, and in a healthy steady-state network the slice is wide enough that gossip gets every event to enough peers to be voted on before its birth round goes ancient.

Some events, however, age out without being decided. A self-event a node creates while it is briefly partitioned, or while it is the slow node in an otherwise-fast network, may never spread far enough to participate in fame voting before the ancient threshold passes its birth round. The node will eventually catch up — it will see judges decided in rounds beyond that event's birth round, the rolling ancient window will move past it, and `ConsensusLinker.setEventWindow` will unlink it. At that moment the algorithm has finished with the event for good: an event below the ancient threshold cannot reach consensus afterwards (only non-ancient events vote and are voted on), so its transactions will never be ordered. If that event was a self-event, the application that submitted those transactions needs to know — otherwise its transactions are silently dropped, and the only way to recover them is to have the application resubmit on a fresh self-event.

That is the contract this lesson covers. The hashgraph reports, on a dedicated output wire, every event that crossed from *admitted* to *ancient* without reaching consensus. The application registers a callback to receive those events and decides what to do with their transactions. The fate is "stale", and the report is the only signal an application gets that a self-event's transactions are gone.

## Concept

Read [`concepts/stale-events.md`](../../concepts/stale-events.md) before continuing — it is short. Three framings to anchor on:

- **Stale is a fate, not a state.** [A.1-6](A.1-6-event-lifecycle-and-birth-round.md)'s lifecycle has four states (*future*, *admitted*, *ancient*, *expired*) that describe an event's position relative to the current `EventWindow`. *Stale* is not a fifth state — it is the specific outcome of an event whose lifecycle ended in *ancient* without ever reaching consensus. An event that *did* reach consensus also crosses from *admitted* to *ancient* eventually, but it is not stale, because by then its transactions are already part of the consensus order.
- **The fate is observable only post-admission.** Events rejected upstream — failed validation, failed deduplication, or already past the ancient threshold at intake — are silently dropped at intake or at the linker's first guard ([A.1-6](A.1-6-event-lifecycle-and-birth-round.md)'s ancient drop). Those events never enter the DAG, so they are *not* stale in current code; *stale* specifically denotes the post-admission outcome. The asymmetry is intentional: an event that never got admitted never had a creator-promised slot in the consensus order, so there is nothing for the application to recover.
- **The fate matters mostly for self-events.** Stale reports for events authored by other creators are informational — the local node does not own those transactions, and other creators are responsible for resubmitting their own. Stale reports for self-events are operationally load-bearing: their transactions belong to the application that submitted them, and the stale-event callback is how the application learns to resubmit. The output wire emits *every* stale event regardless of creator, but the platform-level metrics filter to self-events for the same reason — see [`ConsensusEngineMetrics.reportStaleEvent`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/metrics/ConsensusEngineMetrics.java#L203).

The conceptual definition is in [`concepts/stale-events.md`](../../concepts/stale-events.md); the lifecycle context is in [`concepts/event-lifecycle.md`](../../concepts/event-lifecycle.md). This lesson is the architectural lens onto how the current code wires the partition, the output, and the application contract.

## How it works

There is one partition site, one output wire, one transformer-and-splitter pair, and one registered consumer. The whole path is short.

**Partition.** Every iteration of the work loop in [`DefaultConsensusEngine.addEvent`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L104) declares a `staleEvents` accumulator at [line 124](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L124) and a per-event step. When `consensus.addEvent` returns a non-empty list of decided rounds, the engine takes the *last* round's `EventWindow` at [line 179](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L179) and calls `linker.setEventWindow(eventWindow)` at [line 183](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L183). [`ConsensusLinker.setEventWindow`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L105) walks every descriptor that just fell below the new ancient threshold, removes it from `parentHashMap`, calls `EventImpl.clear()` on it, and accumulates the unlinked `EventImpl` into a returned list (`ConsensusLinker.java` [lines 108–115](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L108)). The engine then partitions that list at [`DefaultConsensusEngine.java#L184`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L184):

```java
ancientEvents.stream()
        .filter(e -> !e.isConsensus())
        .map(EventImpl::getBaseEvent)
        .forEach(staleEvents::add);
```

The discriminator is `EventImpl.isConsensus()` — set when the algorithm decided the event's consensus position, unset otherwise. Events that reached consensus are dropped here because they have already left on `consensusRoundOutputWire`; the rest are projected back to their underlying `PlatformEvent` and accumulated. After the work loop completes, the engine forwards every stale event to the metrics handler at [line 194](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L194) and packages them into the `ConsensusEngineOutput` at [line 195](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L195) alongside the consensus rounds and pre-consensus events. The output is a tuple, not three separate emissions; the wired component fans the three fields out across three output wires downstream.

**Output wire.** [`HashgraphModule.staleEventOutputWire()`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java#L89) is the interface contract; its implementation in [`DefaultHashgraphModule.initialize`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultHashgraphModule.java#L77) pulls `staleEvents` out of the `ConsensusEngineOutput` with a transformer and splits the list into per-event emissions:

```java
this.staleEventOutputWire = consensusEngineWiring
        .getOutputWire()
        .buildTransformer("staleEvents", "consensusEngineOutput", ConsensusEngineOutput::staleEvents)
        .buildSplitter("staleEventsSplitter", "stale events");
```

The transformer projects the per-call `ConsensusEngineOutput` to its `List<PlatformEvent>` of stale events; the splitter unrolls that list so downstream consumers see one event per emission rather than a list per `addEvent` call. (The wiring framework's transformer/splitter pair is covered in [Cluster 0](../../tutor/lessons/0-2-wires-and-soldering.md); this lesson treats it as the standard plumbing for "fan a per-call list out as per-event events".)

**Soldering.** [`PlatformWiring`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L134) checks whether the application registered a stale-event consumer and, if so, solders the splitter's output to it:

```java
if (callbacks.staleEventConsumer() != null) {
    final OutputWire<PlatformEvent> staleEvent =
            components.hashgraphModule().staleEventOutputWire();
    staleEvent.solderTo("staleEventCallback", "stale events", callbacks.staleEventConsumer());
}
```

If no consumer is registered the splitter is still built — it has to exist for the wiring graph to be complete — but its emissions go nowhere. There is no internal subscriber in current code; the stale-event output is purely an application-facing signal.

**Application contract.** The consumer is registered through [`PlatformBuilder.withStaleEventCallback`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#L251):

```java
public PlatformBuilder withStaleEventCallback(@NonNull final Consumer<PlatformEvent> staleEventConsumer) {
    throwIfAlreadyUsed();
    this.staleEventConsumer = requireNonNull(staleEventConsumer);
    return this;
}
```

The callback is `Consumer<PlatformEvent>` — the platform delivers one `PlatformEvent` per stale event, on the wiring task scheduler that owns the soldered consumer. Two things from the JavaDoc at [line 240](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#L240) worth pulling forward: the platform recommends resubmitting the stale event's transactions on a fresh self-event when the application has its own ordering semantics that need them, and detection is *only* guaranteed for self-events that go stale while the node remains online. If the node restarts or reconnects, an event that became ancient in the gap — between the snapshot the node loaded and the snapshot it last saw — is not in the in-memory linker any more and so does not produce a `setEventWindow` partition entry. The platform documents this as a known limitation rather than a bug; recovery in the gap is the application's problem.

**Metrics filter.** [`ConsensusEngineMetrics.reportStaleEvent`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/metrics/ConsensusEngineMetrics.java#L203) early-returns if the stale event's creator is not the local node — the staleness of other creators' events is not the local node's metric. The output wire makes no such filter: it emits every stale event regardless of creator, and the application can decide whether to act on non-self-events. Most applications register a callback that early-returns on non-self-events for the same reason the metrics handler does.

## Worked example

Take the same node from [A.1-6](A.1-6-event-lifecycle-and-birth-round.md)'s worked example, just decided round 100 with `EventWindow = (latestConsensusRound = 100, newEventBirthRound = 101, ancientThreshold = 90, expiredThreshold = 80)`. Suppose the linker is still holding three events whose birth rounds are at the lower end of the non-ancient slice:

- `s_88`: a self-event of this node, birth round 88, admitted long ago, never reached consensus (the node was briefly partitioned around round 89 and `s_88` did not spread far enough).
- `o_88`: an other-event from a peer, birth round 88, admitted, never reached consensus (different peer, similar story).
- `o_89`: an other-event from another peer, birth round 89, admitted, *did* reach consensus in round 95.

A new event `e_99` arrives that triggers `RoundElections.isDecided()` for round 101. `roundDecided` runs and packages a `ConsensusRound` whose `EventWindow` carries `ancientThreshold = 91`. `DefaultConsensusEngine.addEvent` takes that window and:

1. Calls `linker.setEventWindow(eventWindow)`. The linker walks `parentDescriptorMap` for entries with birth round `< 91` — that includes `s_88`, `o_88`, and `o_89`. Each one is removed from `parentHashMap`, has `clear()` called on it, and is appended to the returned `ancientEvents` list.
2. Streams `ancientEvents` through `e -> !e.isConsensus()`. `s_88` and `o_88` pass the filter; `o_89` is filtered out because its `isConsensus()` flag was set when it was decided in round 95.
3. Maps the surviving entries through `EventImpl::getBaseEvent` and appends them to `staleEvents`. After the loop, `staleEvents` holds `[s_88, o_88]`.
4. After the loop, calls `ConsensusEngineMetrics.reportStaleEvent` on each. `s_88` increments the local stale-event counter (its creator is this node); `o_88` early-returns at the creator check.
5. Returns a `ConsensusEngineOutput` containing the round-101 `ConsensusRound`, the pre-consensus events accumulated this call, and the two-element `staleEvents` list.

Downstream, the `DefaultHashgraphModule`'s `staleEventOutputWire` transformer pulls the list out and the splitter emits two events: `s_88`, then `o_88`. `PlatformWiring`'s soldering delivers each to the application's registered `Consumer<PlatformEvent>`. A typical application's callback ignores `o_88` (creator is not the local node) and resubmits the transactions in `s_88` on a fresh self-event, which the event creator (cluster A.4) builds with the current `pendingConsensusRound` (now 102) as the new event's birth round.

Two corollaries worth surfacing from the example. First, the *moment* a stale event is reported is the moment its birth round goes below the new ancient threshold — not the moment it would have been decided, nor the moment some absolute timeout fires. The signal is driven by consensus advancing, not by wall-clock time. Second, the partition is per-`addEvent`-call, not per-decided-round: if a single call decides several rounds in a cascade (because the future-event buffer released several events into the linker, see [A.1-6](A.1-6-event-lifecycle-and-birth-round.md)), only the *last* round's `EventWindow` drives `setEventWindow`, but every event whose birth round falls below that last threshold is partitioned in one shot. The cascade collapses into a single batched stale-event report.

## Code anchor

- [`DefaultConsensusEngine.addEvent`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L104) — the orchestration. `staleEvents` accumulator declared at [line 124](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L124); partition at [lines 184–187](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L184); metrics handoff at [line 194](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L194); packaged into `ConsensusEngineOutput` at [line 195](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java#L195).
- [`ConsensusLinker.setEventWindow`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L105) — produces the `ancientEvents` list the engine partitions; each unlinked `EventImpl` is `clear()`-ed at [line 111](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L111) before it leaves.
- [`HashgraphModule.staleEventOutputWire`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java#L89) — the interface contract.
- [`DefaultHashgraphModule.initialize`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultHashgraphModule.java#L77) — the transformer/splitter pair that fans the per-call `staleEvents` list out as per-event emissions on the wire.
- [`PlatformWiring`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java#L134) — solders the splitter to the application's `Consumer<PlatformEvent>` only if a consumer is registered.
- [`PlatformBuilder.withStaleEventCallback`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#L251) — the application-facing registration point. JavaDoc at [line 240](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#L240) documents the restart/reconnect gap.
- [`ConsensusEngineMetrics.reportStaleEvent`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/metrics/ConsensusEngineMetrics.java#L203) — self-event filter on the metric side; the output wire is unfiltered.
- [`StaleEventDetectorOutput`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/StaleEventDetectorOutput.java) — a two-valued enum (`SELF_EVENT`, `STALE_SELF_EVENT`) that suggests a dedicated detector component but is not referenced anywhere outside its own declaration; see Open questions and Delta callout.

## Delta callout

The `delta-map/` catalog has not yet populated for the hashgraph topic, so there is no `delta-map/hashgraph.md` to reference. The candidate delta worth flagging anyway is the [`StaleEventDetectorOutput`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/StaleEventDetectorOutput.java) enum: it ships in `consensus-model` and distinguishes `SELF_EVENT` from `STALE_SELF_EVENT`, but no production code references it. The naming and the placement in `consensus-model` (where types shared across modules live) suggest a planned `StaleEventDetector` component that would emit a unified self-event stream tagged by fate; in current code the same information is split across the pre-consensus / consensus / stale output wires of `HashgraphModule` plus the self-event creation path in cluster A.4. Whether the enum is dead code or a forward-declaration for a planned detector component is the open question; either way the partition site in `DefaultConsensusEngine.addEvent` is the authoritative source for stale-event reporting today, and any future detector would be a consumer of that same partition rather than a parallel implementation. The proposal in [`Consensus-Layer.md`](../../../proposals/consensus-layer/Consensus-Layer.md) does not change the stale-event contract structurally; the report-by-output-wire model and the creator-agnostic emission survive into the proposed design.

## Comprehension prompt

Two openings to take into the tutor chat. First: the partition at `DefaultConsensusEngine.java#L184` filters on `e -> !e.isConsensus()`, not on creator. The metrics handler downstream filters on creator, and most application callbacks do too. Walk why the *output wire* is creator-agnostic — what would change in the application contract, the wiring graph, or the test surface if the partition itself filtered to self-events at the engine, and what use case (monitoring? pipeline-tracker accounting? a future detector) would lose information from that change? Second: the JavaDoc on `withStaleEventCallback` warns that events going stale "in the gap" of a restart or reconnect may not be reported. Trace where in the lifecycle of [A.1-6](A.1-6-event-lifecycle-and-birth-round.md) that gap opens — what state does `outOfBandSnapshotUpdate` clear that, if a stale event were sitting there, would have been reported under steady-state operation, and what would the application have to do (or have to remember from before the restart) to recover the lost reports?

## Open questions

> - [TBD] No `glossary.md`, `invariants.md`, or `delta-map/hashgraph.md` exists at the consensus-layer KB root yet — the same gap [A.1-1](A.1-1-hashgraph-dag.md) through [A.1-6](A.1-6-event-lifecycle-and-birth-round.md) flagged. The terms *stale event*, *fate*, *self-event*, and *stale-event callback* are load-bearing here. The global [`hashgraphGlossary.md`](../../../../hashgraphGlossary.md) carries a definition of *stale event* under "Events" (around line 510) that aligns with this lesson; consensus-layer-local consolidation versus cross-link is the same open question A.1-6 raised.
> - [TBD] Candidate invariant: *every event admitted to the linker is either marked consensus by the algorithm or reported on the stale-event output wire before its `EventImpl` is `clear()`-ed*. Conservation of self-event observability rests on this; named, it would let the application contract on `withStaleEventCallback` cite an invariant rather than describe behaviour. SME confirmation on whether the in-gap restart/reconnect case is the only documented exception, or whether there are others (e.g. exception thrown inside the `forEach` at `DefaultConsensusEngine.java#L187` short-circuiting later partitions) would settle the precise wording.
> - [TBD] Code-anchor drift: [`concepts/stale-events.md`](../../concepts/stale-events.md) cites `DefaultConsensusEngine.addEvent` "lines ~122, 180–185, 192–193" and `PlatformBuilder.withStaleEventCallback` line 250. Current locations in the verified working tree are line 124 (accumulator), lines 183–187 (partition + filter + map + accumulate), lines 194–195 (metrics + return) for the engine, and line 251 for the builder. Single- to two-line drift; refresh the concept file's anchors or convert to symbol-level references in one cluster-A.1 sweep.
> - [TBD] [`StaleEventDetectorOutput`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/StaleEventDetectorOutput.java) ships in `consensus-model` but is unused outside its declaration. Is this dead code (delete in a cleanup), or a forward-declaration for a planned `StaleEventDetector` component that aggregates self-event creation and self-event-stale fates into a single tagged output stream? If the latter, the planned component's relationship to the existing `staleEventOutputWire` and to the application callback contract needs to be specified before the lesson can describe it.
> - [TBD] Operational question: in steady state, how often does the stale-event output wire actually emit? In a healthy network the answer should be approximately never for self-events and rarely for other-events; spikes correlate with reconnects, network partitions, or sustained throughput overload. The `staleEventCount` metric updated in [`ConsensusEngineMetrics.reportStaleEvent`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/metrics/ConsensusEngineMetrics.java#L203) is the production-side handle. SME context on observed rates would calibrate the worked example for readers.
> - [TBD] The "in the gap" caveat in [`PlatformBuilder.withStaleEventCallback`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#L240) is the only place in the consensus-layer KB or code that documents the restart/reconnect detection gap. Whether the application is expected to durably track its outstanding self-event submissions and reconcile against ordered consensus rounds after a restart, or whether some component of the platform is expected to bridge the gap, is not specified. This is the kind of contract that belongs in `invariants.md` once the catalog populates.

## Where we're going next

[A.1-8 hashgraph algorithm synthesis](A.1-8-hashgraph-algorithm-synthesis.md) is the cluster-closing walkthrough. It puts the hashgraph DAG, the round/witness/strongly-seeing/voting/judges algorithm, the event lifecycle, and the stale-event reporting on a single picture: a single event arriving, the per-event pipeline through `DefaultConsensusEngine.addEvent` end to end, the algorithm processing that may decide a round, the window advance and its dual effects (linker eviction with stale-event partition; future-buffer release that may cascade), and the three output wires that carry the consensus rounds, the pre-consensus events, and the stale events out to the rest of the platform.
