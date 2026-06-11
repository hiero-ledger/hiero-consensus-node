---
title: Delta map — signed-state-management
kind: delta-map
last_reviewed: TBD
---

# Delta map: signed-state-management

## Summary

The proposal hands the entire signed-state lifecycle — signing, signature
collection, saving — to Execution and leaves Consensus with no persisted
merkle state. Today ownership is split between a consensus-layer state
module and platform-core, with Execution a passive observer; the dominant
character of this topic is divergence rather than absence, because state
types have moved — but into the consensus layer, not out of it.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| State types relocated out of the platform monolith | Execution owns the signed-state types; Consensus holds none. | Types were extracted, but into a consensus-layer module rather than to the execution side. | **divergent** | `SignedState` (`consensus-state`, `org.hiero.consensus.state.signed`) |
| Signature collection owned by Execution | Execution collects and validates round signatures. | The collector remains in platform-core; hedera-app only observes signatures. | **not-started** | `StateSignatureCollector`, `DefaultStateSignatureCollector` (`swirlds-platform-core`) — pre-proposal shape intact |
| State saving owned by Execution | Execution decides when to save and performs state persistence. | Save decision and snapshot writing live in platform-core. | **not-started** | `DefaultSavedStateController`, `DefaultStateSnapshotManager` (`swirlds-platform-core`) — pre-proposal shape intact |
| Consensus holds no persisted merkle state | Consensus operates on events and rounds only; state is Execution's concern. | Consensus-layer modules still hold and operate on signed states, including during reconnect. | **divergent** | `SignedState` (`consensus-state`), `ReconnectStateLearner` (`consensus-reconnect-impl`) |

## Cross-references

- Topic: [../architecture/topics/signed-state-management.md](../architecture/topics/signed-state-management.md)
- Proposal: [`Consensus-Layer.md` § Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ State](../../proposals/consensus-layer/Consensus-Layer.md#state)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
