---
title: Delta map — gossip
kind: delta-map
last_reviewed: TBD
---

# Delta map: gossip

## Summary

Gossip is fully extracted into JPMS API/impl modules, with RPC-based sync,
birth-round filtering, and persist-before-gossip ordering all in place. The
remaining proposal work is the Sheriff peer-discipline module and surfacing
fallen-behind detection to Execution through the public API instead of
triggering reconnect internally.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Gossip module split (API/impl, JPMS) | Standalone gossip module with a public interface and an SPI-provided implementation. | `consensus-gossip` / `consensus-gossip-impl` with `module-info.java`; no gossip code remains in platform-core main sources. | **done** | `GossipModule` (`consensus-gossip`), `SyncGossipModular` (`consensus-gossip-impl`) |
| Birth-round filtering in the sync protocol | Syncs exchange and filter events by birth round, not generation. | `EventWindow` birth-round thresholds drive shadowgraph sync; no generation-based code remains. | **done** | `ShadowgraphSynchronizer` (`consensus-gossip-impl`), `EventWindow` (`consensus-model`) |
| Events durably persisted before gossiping onward | Only durable events are emitted to peers, so a crash cannot force an honest branch. | Gossip consumes the PCES writer's written-events output, downstream of the durable write. | **done** | `DefaultInlinePcesWriter` (`consensus-pces-impl`); solder ordering in `PlatformWiring` (`swirlds-platform-core`) |
| Sheriff peer discipline | Gossip reports misbehaviour to a Sheriff module and enforces its shunning/welcome verdicts. | No Sheriff type or reputation scoring exists; discipline is local per-peer rate limiting only. | **not-started** | `LruSyncGuard`, `SyncPermitProvider` (`consensus-gossip-impl`) — local-only, pre-proposal shape |
| Fallen-behind surfaced to Execution (`onBehind`) | Falling behind is reported to Execution via a public callback; Execution decides the response. | Detection exists but feeds the consensus-internal reconnect flow; no Execution-facing callback. | **not-started** | `FallenBehindMonitor` (`consensus-utility`) — consensus-internal consumption intact |

## Cross-references

- Topic: [../architecture/topics/gossip.md](../architecture/topics/gossip.md)
- Proposal: [`Consensus-Layer.md` § Gossip](../../proposals/consensus-layer/Consensus-Layer.md#gossip), [§ Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind), [§ Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
