---
type: delta-map
title: Delta map — freeze-and-upgrade
last_reviewed: 2026-06-18
---

# Delta map: freeze-and-upgrade

## Summary

The proposal does not name freeze or upgrade. These deltas are not a mapping
the proposal states outright; they are inferred from its lifecycle-inversion
principle — that reconnect, state saving, and lifecycle move to Execution.

The reasoning: a freeze is itself a lifecycle transition — halt the consensus
pipeline at a designated round, then resume, on new software for an upgrade —
whose purpose is a clean, deterministic state save at that boundary. The
proposal moves both lifecycle and state saving to Execution, and it makes round
production Execution-driven: Consensus produces rounds only on demand, so
halting after a designated round is just Execution declining to ask for the
next one, a channel it already controls. On that reading the freeze trigger
lands on Execution. Because the proposal never says "freeze," this stays a
reasoned inference, not a stated requirement.

Today the responsibility is spread across consensus-hashgraph (freeze-period
checking), consensus-model (freeze statuses), platform-core (freeze-state
save), and hedera-app, which already owns the freeze-metadata and
upgrade-action side.

## Changes

| Change | Proposal state | Proposal source | Current state | Status | Anchor / TBD |
|---|---|---|---|---|---|
| Freeze trigger and round cutoff | Not stated by the proposal; inferred from the lifecycle inversion. Round production is Execution-driven, so the freeze trigger — halting after a designated round — falls to Execution rather than to consensus-side freeze-time arithmetic. | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ Purpose and Context](../../proposals/consensus-layer/Consensus-Layer.md#purpose-and-context) (implied) | Consensus detects the freeze period from consensus timestamps and cuts off the freeze round itself. | **not-started** | `FreezePeriodChecker` (`consensus-hashgraph`), `FreezeRoundController` (`consensus-hashgraph-impl`) — pre-proposal shape intact |
| Freeze lifecycle management | Not stated by the proposal; inferred from the lifecycle inversion: tracking freeze progress and completion is lifecycle management, which moves to Execution. | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ Purpose and Context](../../proposals/consensus-layer/Consensus-Layer.md#purpose-and-context) (implied) | FREEZING / FREEZE_COMPLETE live in the consensus-layer platform statuses. | **not-started** | `PlatformStatus` (`consensus-model`, `model/status`) — pre-proposal shape intact |
| Freeze-state save owned by Execution | Not named for freeze specifically; the proposal makes Execution wholly responsible for state, so the freeze-state save moves to Execution with all state saving — nothing freeze-specific stays in Consensus. | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ Purpose and Context](../../proposals/consensus-layer/Consensus-Layer.md#purpose-and-context) (implied) | The consensus layer owns the full path: `DefaultTransactionHandler` marks the freeze round's `SignedState` as a freeze state, `DefaultSavedStateController` turns that flag into the save decision, and `DefaultStateSnapshotManager` writes it to disk. | **not-started** | `DefaultTransactionHandler`, `DefaultSavedStateController`, `DefaultStateSnapshotManager` (`swirlds-platform-core`), `SignedState#isFreezeState` (`consensus-state`) — pre-proposal shape intact |

## Cross-references

- Topic: [../architecture/topics/freeze-and-upgrade.md](../architecture/topics/freeze-and-upgrade.md)
- Proposal: the proposal does not name freeze or upgrade; Execution ownership is inferred from [`Consensus-Layer.md` § Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module) and [§ Purpose and Context](../../proposals/consensus-layer/Consensus-Layer.md#purpose-and-context) ("reconnect, state saving, lifecycle, etc."), neither of which mentions freeze
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
