---
title: Delta map — event-creator
kind: delta-map
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

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Creator module split (API/impl, JPMS) | Standalone event-creator module with a public interface and an SPI-provided implementation. | `consensus-event-creator` / `consensus-event-creator-impl` with `module-info.java`. | **done** | `EventCreatorModule` (`consensus-event-creator`) |
| Tipset-based other-parent selection | Other parents chosen to maximise hashgraph progress via the tipset algorithm. | Implemented as proposed. | **done** | `TipsetEventCreator`, `TipsetWeightCalculator`, `ChildlessEventTracker` (`consensus-event-creator-impl`) |
| Sequence-number (nGen) ordering for tipset | The orphan buffer assigns sequence numbers that the tipset algorithm consumes (2026-04-30 update). | Implemented as updated. | **done** | `OrphanBuffer` (`consensus-utility`) assigns; consumed in `TipsetEventCreator` (`consensus-event-creator-impl`) |
| Transaction pull at event fill (`getTransactionsForEvent`) | The creator pulls transactions from Execution when filling an event, inverting the old push model. | Pull model in place via a supplier interface. | **done** | `EventTransactionSupplier` (`consensus-model`) |
| Public API into the event creator | Creator inputs (health, sync progress, event window) arrive through the public Consensus API. | The module API exposes the input wires, but the unified public Consensus facade above them does not exist. | **partial** | `EventCreatorModule.healthStatusInputWire()` and sibling input wires (`consensus-event-creator`) |
| Stale self-event handling surfaced to Execution (`onStaleEvent`) | Execution is notified of stale self-events so it can resubmit their transactions. | Stale events are emitted from the hashgraph output wire and notify the application via the `staleEventConsumer` callback; the proposal's `onStaleEvent` method on the public Consensus API does not exist. | **partial** | `HashgraphModule.staleEventOutputWire()` (`consensus-hashgraph`), `ApplicationCallbacks.staleEventConsumer` (`swirlds-platform-core`) |

## Cross-references

- Topic: [../architecture/topics/event-creator.md](../architecture/topics/event-creator.md)
- Proposal: [`Consensus-Layer.md` § Event Creator Module](../../proposals/consensus-layer/Consensus-Layer.md#event-creator-module), [§ Slow Execution](../../proposals/consensus-layer/Consensus-Layer.md#slow-execution), [§ getTransactionsForEvent](../../proposals/consensus-layer/Consensus-Layer.md#gettransactionsforevent), [§ onStaleEvent](../../proposals/consensus-layer/Consensus-Layer.md#onstaleevent)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
