---
title: Delta map — freeze-and-upgrade
kind: delta-map
last_reviewed: TBD
---

# Delta map: freeze-and-upgrade

## Summary

The proposal makes Execution wholly own freeze and upgrade, with Consensus
reduced to honoring a "stop after round N" directive. Today the
responsibility is spread across consensus-hashgraph (freeze-period
checking), consensus-model (freeze statuses), platform-core (freeze-state
save), and hedera-app — which already owns the freeze-metadata and
upgrade-action side, though as pre-proposal behaviour rather than
proposal-driven work.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Freeze trigger as an Execution-supplied round directive | Execution tells Consensus to stop after round N; Consensus does no freeze-time arithmetic. | Consensus checks the freeze period against consensus timestamps and shapes the freeze round itself. | **not-started** | `FreezePeriodChecker` (`consensus-hashgraph`), `FreezeRoundController` (`consensus-hashgraph-impl`) — pre-proposal shape intact |
| Freeze lifecycle signaling moved out of Consensus | Execution owns freeze progress and completion. | FREEZING / FREEZE_COMPLETE live in the consensus-layer platform statuses. | **not-started** | `PlatformStatus` (`consensus-model`, `model/status`) — pre-proposal shape intact |
| Execution-side freeze/upgrade metadata and actions | Execution owns freeze transactions, upgrade file handling, and upgrade actions. | The metadata side is in hedera-app's network-admin service (pre-proposal); orchestration of the actual freeze remains consensus/platform-side. | **partial** | `FreezeServiceImpl`, `FreezeUpgradeActions` (`hedera-node/hedera-network-admin-service-impl`) |
| Freeze-state save owned by Execution | Execution triggers and performs the freeze-state save. | The save decision flows through platform-core's saved-state controller. | **not-started** | `DefaultSavedStateController` (`swirlds-platform-core`); [TBD: question for engineer — which component decides and performs the freeze-state save today, and does it move to Execution with the rest of state saving?] |

## Cross-references

- Topic: [../architecture/topics/freeze-and-upgrade.md](../architecture/topics/freeze-and-upgrade.md) (see its future-state note)
- Proposal: [`Consensus-Layer.md` § Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
