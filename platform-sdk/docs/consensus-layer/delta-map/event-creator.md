---
type: delta-map
title: Delta map — event-creator
last_reviewed: TBD
---

# Delta map: event-creator

## Summary

Tipset-based event creation with orphan-buffer sequence numbers (per the
2026-04-30 proposal update) and the transaction-pull model are implemented
in a dedicated module pair. The remaining gap is the proposal's single
public Consensus API above the module's wires; stale self-events already
reach the application through a callback, but not yet through that API.

## Changes

| Change | Proposal state | Proposal source | Current state | Status | Anchor / TBD |
|---|---|---|---|---|---|
| Creator module split (API/impl, JPMS) | Standalone event-creator module with a public interface and an SPI-provided implementation. | [§ Event Creator Module](../../proposals/consensus-layer/Consensus-Layer.md#event-creator-module), [§ Design](../../proposals/consensus-layer/Consensus-Layer.md#design) | `consensus-event-creator` / `consensus-event-creator-impl` with `module-info.java`. | **done** | `EventCreatorModule` (`consensus-event-creator`) |
| Tipset-based other-parent selection | Other parents chosen to maximise hashgraph progress via the tipset algorithm. | [§ Creating Events](../../proposals/consensus-layer/Consensus-Layer.md#creating-events), [§ Filling Events](../../proposals/consensus-layer/Consensus-Layer.md#filling-events) | Implemented as proposed. | **done** | `TipsetEventCreator`, `TipsetWeightCalculator`, `ChildlessEventTracker` (`consensus-event-creator-impl`) |
| Sequence-number ordering for tipset | The orphan buffer assigns sequence numbers that the tipset algorithm consumes (2026-04-30 update). |  | Implemented as updated. | **done** | `OrphanBuffer` (`consensus-utility`) assigns; consumed in `TipsetEventCreator` (`consensus-event-creator-impl`) |
| Transaction pull at event fill (`getTransactionsForEvent`) | The creator pulls transactions from Execution when filling an event, inverting the old push model. | [§ getTransactionsForEvent](../../proposals/consensus-layer/Consensus-Layer.md#gettransactionsforevent), [§ Filling Events](../../proposals/consensus-layer/Consensus-Layer.md#filling-events) | Pull model in place via a supplier interface. | **done** | `EventTransactionSupplier` (`consensus-model`) |
| Multiple other-parents support | The module is designed to support multiple other parents per event, even if an implementation may choose to use one. | [§ Creating Events](../../proposals/consensus-layer/Consensus-Layer.md#creating-events) | Selection picks up to a configured number of best parents, and the event model carries an other-parent list. | **done** | `EventCreationConfig.maxOtherParents`, `TipsetEventCreator` (`consensus-event-creator-impl`); `PlatformEvent.getOtherParents()` (`consensus-model`) |
| Stale self-event handling surfaced to Execution (`onStaleEvent`) | Execution is notified of stale self-events so it can resubmit their transactions. | [§ Stale Events](../../proposals/consensus-layer/Consensus-Layer.md#stale-events), [§ onStaleEvent](../../proposals/consensus-layer/Consensus-Layer.md#onstaleevent) | Stale events are emitted from the hashgraph output wire and notify the application via the `staleEventConsumer` callback; the proposal's `onStaleEvent` method on the public Consensus API does not exist. | **partial** | `HashgraphModule.staleEventOutputWire()` (`consensus-hashgraph`), `ApplicationCallbacks.staleEventConsumer` (`swirlds-platform-core`) |

## Cross-references

- Topic: [../architecture/topics/event-creator.md](../architecture/topics/event-creator.md)
- Proposal: [`Consensus-Layer.md` § Event Creator Module](../../proposals/consensus-layer/Consensus-Layer.md#event-creator-module), [§ Slow Execution](../../proposals/consensus-layer/Consensus-Layer.md#slow-execution), [§ getTransactionsForEvent](../../proposals/consensus-layer/Consensus-Layer.md#gettransactionsforevent), [§ onStaleEvent](../../proposals/consensus-layer/Consensus-Layer.md#onstaleevent)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
