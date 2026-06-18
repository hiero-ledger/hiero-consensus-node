---
type: delta-map
title: Delta map — freeze-and-upgrade
last_reviewed: TBD
---

# Delta map: freeze-and-upgrade

## Summary

The proposal does not name freeze or upgrade. These deltas are implied by
its lifecycle-inversion principle — that reconnect, state saving, and
lifecycle move to Execution (assumption 4) — and by the architecture
topic's future-state note, which anticipates Execution owning the freeze
trigger while the consensus side receives a simple "stop after round N"
signal. Today the responsibility is spread across consensus-hashgraph
(freeze-period checking), consensus-model (freeze statuses), platform-core
(freeze-state save), and hedera-app, which already owns the freeze-metadata
and upgrade-action side.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Freeze trigger as an Execution-supplied round directive | Implied by the lifecycle inversion, not named: freeze ownership moves to Execution. The architecture topic anticipates a "stop after round N" signal replacing consensus-side freeze-time arithmetic. | Consensus checks the freeze period against consensus timestamps and shapes the freeze round itself. | **not-started** | `FreezePeriodChecker` (`consensus-hashgraph`), `FreezeRoundController` (`consensus-hashgraph-impl`) — pre-proposal shape intact |
| Freeze lifecycle signaling moved out of Consensus | Implied, not named: freeze progress and completion are lifecycle concerns owned by Execution. | FREEZING / FREEZE_COMPLETE live in the consensus-layer platform statuses. | **not-started** | `PlatformStatus` (`consensus-model`, `model/status`) — pre-proposal shape intact |
| Execution-side freeze/upgrade metadata and actions | Not named by the proposal; ADR-006 records upgrading via a coordinated network-wide freeze, with the freeze-transaction and upgrade-action side on the execution layer. | Already in hedera-app's network-admin service (pre-proposal); orchestration of the actual freeze remains consensus/platform-side. | **partial** | `FreezeServiceImpl`, `FreezeUpgradeActions` (`hedera-node/hedera-network-admin-service-impl`) |
| Freeze-state save owned by Execution | Implied by the state-saving inversion (assumption 4), not named for freeze specifically: Execution triggers and performs the save. | The save decision flows through platform-core's saved-state controller. | **not-started** | `DefaultSavedStateController` (`swirlds-platform-core`); [TBD: question for engineer — which component decides and performs the freeze-state save today, and does it move to Execution with the rest of state saving?] |

## Cross-references

- Topic: [../architecture/topics/freeze-and-upgrade.md](../architecture/topics/freeze-and-upgrade.md) — its Future-state note is the source of the "stop after round N" anticipated mechanism
- Proposal: the proposal does not name freeze or upgrade; Execution ownership is implied by [`Consensus-Layer.md` § Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module) and assumption 4 ("reconnect, state saving, lifecycle, etc."), neither of which mentions freeze
- Decision: [ADR-006](../decisions/ADR-006-coordinated-network-wide-upgrade.md) — upgrade via a coordinated network-wide freeze
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
