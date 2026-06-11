---
title: Delta map — reasons-not-to-gossip
kind: delta-map
last_reviewed: TBD
---

# Delta map: reasons-not-to-gossip

## Summary

The gating rules in current code — durability before gossip, health-based
permit revocation, fallen-behind and freeze status gating — are in place
and compatible with the proposal. The centralized Sheriff verdict system
that would unify peer discipline is not started, and the proposal's
network-wide dynamic throttles have no confirmed counterpart.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Sheriff module (centralized peer verdicts) | A dedicated module aggregates misbehaviour reports and issues shunning/welcome verdicts that gossip enforces. | No Sheriff type or reputation scoring exists; discipline is local to each mechanism. | **not-started** | `LruSyncGuard`, `SyncPermitProvider` (`consensus-gossip-impl`) — local-only, pre-proposal shape |
| Durability gate before gossip | An event is never gossiped before it is durable. | Gossip is fed from the written-events output of the PCES writer. | **done** | `DefaultInlinePcesWriter` (`consensus-pces-impl`); solder ordering in `PlatformWiring` (`swirlds-platform-core`) |
| Health-based sync withholding | An unhealthy node stops initiating and serving syncs. | Sync permits are revoked while the unhealthy duration persists and restored afterwards. | **done** | `SyncPermitProvider` (`consensus-gossip-impl`) |
| Fallen-behind gating | A node that has fallen behind abandons normal gossip and pursues recovery. | Detection and BEHIND status gating are in place. | **done** | `FallenBehindMonitor` (`consensus-utility`), `PlatformStatus` (`consensus-model`) |
| Freeze-status gating | Gossip activity is gated during the freeze procedure. | FREEZING / FREEZE_COMPLETE statuses gate behaviour. | **done** | `PlatformStatus` FREEZING / FREEZE_COMPLETE (`consensus-model`) |
| Dynamic network throttles | Network-wide throttles reduce transaction load when enough nodes are stressed. | Adaptive local permits exist; no confirmed network-wide mechanism. | **not-started** | [TBD: question for engineer — the proposal calls for dynamic network-wide throttles; code shows adaptive local permits (`SyncPermitProvider`). Is that mechanism considered the implementation, in flight, or not yet begun?] |

## Cross-references

- Topic: [../architecture/topics/reasons-not-to-gossip.md](../architecture/topics/reasons-not-to-gossip.md)
- Proposal: [`Consensus-Layer.md` § Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module), [§ Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind), [§ Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
