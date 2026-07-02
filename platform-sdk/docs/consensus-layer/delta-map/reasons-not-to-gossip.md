---
type: delta-map
title: Delta map — reasons-not-to-gossip
last_reviewed: 2026-06-19
---

# Delta map: reasons-not-to-gossip

## Summary

Current code pauses gossip for several reasons. Persistence before gossip and
fallen-behind gating are proposal-stated and in place; freeze also pauses
gossip today, but the proposal's lifecycle inversion implies its trigger moves
to Execution, so that delta is not-started (tracked in
[freeze-and-upgrade.md](freeze-and-upgrade.md)). The proposal's centralized Sheriff verdict system and network-wide dynamic
throttles are both not started.

## Changes

|                Change                 |                                                                                                     Proposal state                                                                                                     |                                                                                    Proposal source                                                                                     |                                             Current state                                             |     Status      |                                                   Anchor / TBD                                                   |
|---------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|-----------------|------------------------------------------------------------------------------------------------------------------|
| Gossip withheld from shunned peers    | The proposal's Sheriff issues shun verdicts that Gossip enforces by refusing and dropping a shunned peer (full module tracked in [sheriff.md](sheriff.md)).                                                            | [§ Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module), [§ Neighbor Discipline](../../proposals/consensus-layer/Consensus-Layer.md#neighbor-discipline) | No shun mechanism; the peer set is roster-driven and discipline is local per-mechanism rate limiting. | **not-started** | `LruSyncGuard`, `SyncPermitProvider` (`consensus-gossip-impl`) — local-only, pre-proposal shape                  |
| Persistence gate before gossip        | An event is never gossiped before it is persisted.                                                                                                                                                                     | [§ Persistence](../../proposals/consensus-layer/Consensus-Layer.md#persistence)                                                                                                        | Gossip is fed from the written-events output of the PCES writer.                                      | **done**        | `DefaultInlinePcesWriter` (`consensus-pces-impl`); solder ordering in `PlatformWiring` (`swirlds-platform-core`) |
| Fallen-behind gating                  | A node that has fallen behind abandons normal gossip and pursues recovery.                                                                                                                                             | [§ Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind)                                                                                                  | Detection and BEHIND status gating are in place.                                                      | **done**        | `FallenBehindMonitor` (`consensus-utility`), `PlatformStatus` (`consensus-model`)                                |
| Freeze-pause becomes Execution-driven | Not stated by the proposal; inferred from the lifecycle inversion — freeze lifecycle moves to Execution, which reworks this freeze-driven gossip pause (full delta in [freeze-and-upgrade.md](freeze-and-upgrade.md)). | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module) (implied)                                                  | Gossip is gated by the consensus-layer FREEZING / FREEZE_COMPLETE statuses.                           | **not-started** | `PlatformStatus` FREEZING / FREEZE_COMPLETE (`consensus-model`) — pre-proposal shape intact                      |
| Dynamic network throttles             | Network-wide throttles reduce transaction load when enough nodes are stressed.                                                                                                                                         | [§ Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress), [§ Assumptions](../../proposals/consensus-layer/Consensus-Layer.md#assumptions)   | Adaptive local permits exist; no network-wide mechanism.                                              | **not-started** | `SyncPermitProvider` (`consensus-gossip-impl`) — adaptive local permits only, no network-wide throttle           |

## Cross-references

- Topic: [../architecture/topics/reasons-not-to-gossip.md](../architecture/topics/reasons-not-to-gossip.md)
- Proposal: [`Consensus-Layer.md` § Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module), [§ Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind), [§ Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress)
- Related delta maps: [sheriff.md](sheriff.md) — shun-verdict withholding; [freeze-and-upgrade.md](freeze-and-upgrade.md) — freeze-pause ownership; [health-monitor-and-backpressure.md](health-monitor-and-backpressure.md) — health-based sync withholding and the proposal's backpressure successor
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
