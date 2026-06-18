---
type: delta-map
title: Delta map — event-creator
last_reviewed: TBD
---

# Delta map: event-creator

## Summary

Tipset-based event creation and the transaction-pull model are implemented
in a dedicated module pair, and the creator consumes per-round event-window
metadata. Two gaps remain: the proposal's single public Consensus API above
the module's wires — stale self-events already reach the application through
a callback, but not yet through that API — and per-round roster supply, the
roster still being fixed at initialization.

## Changes

| Change | Proposal state | Proposal source | Current state | Status | Anchor / TBD |
|---|---|---|---|---|---|
| Creator module split (API/impl, JPMS) | Standalone event-creator module with a public interface and an SPI-provided implementation. | [§ Event Creator Module](../../proposals/consensus-layer/Consensus-Layer.md#event-creator-module), [§ Design](../../proposals/consensus-layer/Consensus-Layer.md#design) | `consensus-event-creator` / `consensus-event-creator-impl` with `module-info.java`. | **done** | `EventCreatorModule` (`consensus-event-creator`) |
| Tipset-based other-parent selection | Other parents chosen to maximise hashgraph progress via the tipset algorithm. | [§ Creating Events](../../proposals/consensus-layer/Consensus-Layer.md#creating-events), [§ Filling Events](../../proposals/consensus-layer/Consensus-Layer.md#filling-events) | Implemented as proposed. | **done** | `TipsetEventCreator`, `TipsetWeightCalculator`, `ChildlessEventTracker` (`consensus-event-creator-impl`) |
| Transaction pull at event fill (`getTransactionsForEvent`) | The creator pulls transactions from Execution when filling an event, inverting the old push model. | [§ getTransactionsForEvent](../../proposals/consensus-layer/Consensus-Layer.md#gettransactionsforevent), [§ Filling Events](../../proposals/consensus-layer/Consensus-Layer.md#filling-events) | Pull model in place via a supplier interface. | **done** | `EventTransactionSupplier` (`consensus-model`) |
| Multiple other-parents support | The module is designed to support multiple other parents per event, even if an implementation may choose to use one. | [§ Creating Events](../../proposals/consensus-layer/Consensus-Layer.md#creating-events) | Selection picks up to a configured number of best parents, and the event model carries an other-parent list. | **done** | `EventCreationConfig.maxOtherParents`, `TipsetEventCreator` (`consensus-event-creator-impl`); `PlatformEvent.getOtherParents()` (`consensus-model`) |
| Maximum event-creation frequency | Events are created at no more than the network-wide `maximum_event_creation_frequency` (events/sec). | [§ Creating Events](../../proposals/consensus-layer/Consensus-Layer.md#creating-events) | The creator caps its own creation rate; reporting peers that exceed the cap to the Sheriff is tracked in [sheriff.md](sheriff.md). | **done** | `MaximumRateRule` (`consensus-event-creator-impl`) |
| Per-round roster/metadata consumption | The creator receives per-round metadata from the Hashgraph module — the event window for birth-round assignment and ancient pruning, and the active roster for other-parent selection. | [§ Roster and Configuration Changes](../../proposals/consensus-layer/Consensus-Layer.md#roster-and-configuration-changes) | The event window is consumed each round and sets the new event's birth round; the roster is fixed at `initialize`, with no per-round supply path. | **partial** | `EventCreatorModule.eventWindowInputWire()` (`consensus-event-creator`); `TipsetEventCreator.setEventWindow()`, `eventWindow.newEventBirthRound()` (`consensus-event-creator-impl`) |
| Stale self-event handling surfaced to Execution (`onStaleEvent`) | Execution is notified of stale self-events so it can resubmit their transactions. | [§ Stale Events](../../proposals/consensus-layer/Consensus-Layer.md#stale-events), [§ onStaleEvent](../../proposals/consensus-layer/Consensus-Layer.md#onstaleevent) | Stale events are emitted from the hashgraph output wire and notify the application via the `staleEventConsumer` callback; the proposal's `onStaleEvent` method on the public Consensus API does not exist. | **partial** | `HashgraphModule.staleEventOutputWire()` (`consensus-hashgraph`), `ApplicationCallbacks.staleEventConsumer` (`swirlds-platform-core`) |

## Cross-references

- Topic: [../architecture/topics/event-creator.md](../architecture/topics/event-creator.md)
- Proposal: [`Consensus-Layer.md` § Event Creator Module](../../proposals/consensus-layer/Consensus-Layer.md#event-creator-module), [§ Creating Events](../../proposals/consensus-layer/Consensus-Layer.md#creating-events), [§ Slow Execution](../../proposals/consensus-layer/Consensus-Layer.md#slow-execution), [§ getTransactionsForEvent](../../proposals/consensus-layer/Consensus-Layer.md#gettransactionsforevent), [§ Roster and Configuration Changes](../../proposals/consensus-layer/Consensus-Layer.md#roster-and-configuration-changes), [§ onStaleEvent](../../proposals/consensus-layer/Consensus-Layer.md#onstaleevent)
- Related delta maps: [sheriff.md](sheriff.md) — creation-rate reporting and shun/welcome; [health-monitor-and-backpressure.md](health-monitor-and-backpressure.md), [quiescence.md](quiescence.md) — the should-create gating path
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
