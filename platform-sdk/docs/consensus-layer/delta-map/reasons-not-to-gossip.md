---
type: delta-map
title: Delta map — reasons-not-to-gossip
last_reviewed: TBD
---

# Delta map: reasons-not-to-gossip

## Summary

Current code pauses gossip for several reasons. Two are proposal-stated and
in place — durability before gossip and fallen-behind gating. Two are
current-only with no proposal counterpart — health-based sync withholding
and freeze-status gating — whose futures are tracked in
[health-monitor-and-backpressure.md](health-monitor-and-backpressure.md) and
[freeze-and-upgrade.md](freeze-and-upgrade.md). The proposal's centralized
Sheriff verdict system is not started, and its network-wide dynamic
throttles have no confirmed counterpart.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Sheriff module (centralized peer verdicts) | A dedicated module aggregates misbehaviour reports and issues shunning/welcome verdicts that gossip enforces. | No Sheriff type or reputation scoring exists; discipline is local to each mechanism (see [sheriff.md](sheriff.md)). | **not-started** | `LruSyncGuard`, `SyncPermitProvider` (`consensus-gossip-impl`) — local-only, pre-proposal shape |
| Durability gate before gossip | An event is never gossiped before it is durable. | Gossip is fed from the written-events output of the PCES writer. | **done** | `DefaultInlinePcesWriter` (`consensus-pces-impl`); solder ordering in `PlatformWiring` (`swirlds-platform-core`) |
| Health-based sync withholding | No proposal counterpart — the proposal describes no health monitor; this is a current-code reason whose future is tied to the health monitor (see [health-monitor-and-backpressure.md](health-monitor-and-backpressure.md)). | An unhealthy node stops initiating and serving syncs: sync permits are revoked while the unhealthy duration persists and restored afterwards. | **done** | `SyncPermitProvider` (`consensus-gossip-impl`) |
| Fallen-behind gating | A node that has fallen behind abandons normal gossip and pursues recovery. | Detection and BEHIND status gating are in place. | **done** | `FallenBehindMonitor` (`consensus-utility`), `PlatformStatus` (`consensus-model`) |
| Freeze-status gating | No proposal counterpart — the proposal never names freeze; this is a current-code reason. Freeze ownership is tracked in [freeze-and-upgrade.md](freeze-and-upgrade.md). | Gossip activity is gated during the freeze procedure: FREEZING / FREEZE_COMPLETE statuses gate behaviour. | **done** | `PlatformStatus` FREEZING / FREEZE_COMPLETE (`consensus-model`) |
| Dynamic network throttles | Network-wide throttles reduce transaction load when enough nodes are stressed. | Adaptive local permits exist; no confirmed network-wide mechanism. | **not-started** | [TBD: question for engineer — the proposal calls for dynamic network-wide throttles; code shows adaptive local permits (`SyncPermitProvider`). Is that mechanism considered the implementation, in flight, or not yet begun?] |

## Cross-references

- Topic: [../architecture/topics/reasons-not-to-gossip.md](../architecture/topics/reasons-not-to-gossip.md)
- Proposal: [`Consensus-Layer.md` § Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module), [§ Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind), [§ Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
