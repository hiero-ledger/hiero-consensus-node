---
type: delta-map
title: Delta map — signed-state-management
last_reviewed: 2026-06-18
---

# Delta map: signed-state-management

## Summary

The proposal hands the entire signed-state lifecycle — signing, signature
collection, saving — to Execution and leaves Consensus with no persisted
merkle state. None of that ownership transfer has begun: today the
lifecycle is split between a consensus-layer state module and
platform-core, with Execution a passive observer. The extraction of state
types into `consensus-state` is internal modularization, not progress on
(or movement away from) the proposed end state.

## Changes

|                   Change                    |                               Proposal state                                |                                                                                               Proposal source                                                                                                |                                        Current state                                         |     Status      |                                                    Anchor / TBD                                                    |
|---------------------------------------------|-----------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|-----------------|--------------------------------------------------------------------------------------------------------------------|
| State types relocated to the execution side | Execution owns the signed-state types; Consensus holds none.                | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ State](../../proposals/consensus-layer/Consensus-Layer.md#state)             | Types were extracted into a consensus-layer module; execution-side ownership has not begun.  | **not-started** | `SignedState` (`consensus-state`, `org.hiero.consensus.state.signed`)                                              |
| Signature collection owned by Execution     | Execution collects and validates round signatures.                          | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ Assumptions](../../proposals/consensus-layer/Consensus-Layer.md#assumptions) | The collector remains in the consensus layer; hedera-app only observes signatures.           | **not-started** | `StateSignatureCollector`, `DefaultStateSignatureCollector` (`swirlds-platform-core`) — pre-proposal shape intact  |
| State saving owned by Execution             | Execution decides when to save and performs state persistence.              | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ Assumptions](../../proposals/consensus-layer/Consensus-Layer.md#assumptions) | Save decision and snapshot writing live in the consensus layer.                              | **not-started** | `DefaultSavedStateController`, `DefaultStateSnapshotManager` (`swirlds-platform-core`) — pre-proposal shape intact |
| Consensus holds no persisted merkle state   | Consensus operates on events and rounds only; state is Execution's concern. | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ State](../../proposals/consensus-layer/Consensus-Layer.md#state)             | Consensus-layer modules still hold and operate on signed states, including during reconnect. | **not-started** | `SignedState` (`consensus-state`), `ReconnectStateLearner` (`consensus-reconnect-impl`)                            |

## Cross-references

- Topic: [../architecture/topics/signed-state-management.md](../architecture/topics/signed-state-management.md)
- Proposal: [`Consensus-Layer.md` § Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ State](../../proposals/consensus-layer/Consensus-Layer.md#state)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
