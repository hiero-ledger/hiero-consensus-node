---
type: delta-map
title: Delta map — gossip
last_reviewed: TBD
---

# Delta map: gossip

## Summary

Gossip is fully extracted into JPMS API/impl modules, with RPC-based sync,
birth-round filtering, validate-before-retransmit, event buffering for
catch-up, and persist-before-gossip ordering all in place. The remaining
proposal work is Sheriff-driven discipline (see [sheriff.md](sheriff.md)),
the per-round roster path, and surfacing fallen-behind detection to
Execution through the public API instead of triggering reconnect internally.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Gossip module split (API/impl, JPMS) | Standalone gossip module with a public interface and an SPI-provided implementation. | `consensus-gossip` / `consensus-gossip-impl` with `module-info.java`; no gossip code remains in platform-core main sources. | **done** | `GossipModule` (`consensus-gossip`), `SyncGossipModular` (`consensus-gossip-impl`) |
| Birth-round filtering in the sync protocol | Syncs exchange and filter events by birth round, not generation. | `EventWindow` birth-round thresholds drive shadowgraph sync; no generation-based code remains. | **done** | `ShadowgraphSynchronizer` (`consensus-gossip-impl`), `EventWindow` (`consensus-model`) |
| Events durably persisted before gossiping onward | Only durable events are emitted to peers, so a crash cannot force an honest branch. | Gossip consumes the PCES writer's written-events output, downstream of the durable write. | **done** | `DefaultInlinePcesWriter` (`consensus-pces-impl`); solder ordering in `PlatformWiring` (`swirlds-platform-core`) |
| Validate-before-retransmit redistribution | Received events are not immediately retransmitted; Event Intake returns valid, ordered events to Gossip for redistribution. | Gossip's outbound events come from the written-events output downstream of intake validation. | **done** | `GossipModule.eventToGossipInputWire()` solder in `PlatformWiring` (`swirlds-platform-core`) |
| Event buffering for catch-up | Gossip must cache all non-ancient events and may cache non-expired events so lagging neighbors can catch up without reconnecting. | The shadowgraph retains events until the expired threshold. | **done** | `Shadowgraph` (`consensus-gossip-impl`) — retains to `EventWindow.expiredThreshold()` |
| Lagging-behind backoff | At a configurable lag level (delay versus the network's median round) a node stops creating events and accepting transactions, before declaring itself fallen behind. | Event creation is gated on sync lag; transaction acceptance is gated on health and platform status, not on lag. | **partial** | `SyncLagRule` (`consensus-event-creator-impl`), `TransactionPoolNexus` (`consensus-utility`) |
| Per-round roster metadata consumption | Gossip receives roster-per-round metadata from the Hashgraph module so it knows which roster applies to which round. | Gossip receives the roster at initialization; no per-round roster path exists. | **not-started** | `GossipModule.initialize(currentRoster)` (`consensus-gossip`) — construction-time roster intact |
| Sheriff peer discipline | Gossip reports misbehaviour to a Sheriff module and enforces its shunning/welcome verdicts. | No Sheriff type or reputation scoring exists; discipline is local per-peer rate limiting only (see [sheriff.md](sheriff.md)). | **not-started** | `LruSyncGuard`, `SyncPermitProvider` (`consensus-gossip-impl`) — local-only, pre-proposal shape |
| Fallen-behind surfaced to Execution (`onBehind`) | Falling behind is reported to Execution via a public callback; Execution decides the response. | Detection exists but feeds the consensus-internal reconnect flow; no Execution-facing callback. | **not-started** | `FallenBehindMonitor` (`consensus-utility`) — consensus-internal consumption intact |

## Cross-references

- Topic: [../architecture/topics/gossip.md](../architecture/topics/gossip.md)
- Proposal: [`Consensus-Layer.md` § Gossip](../../proposals/consensus-layer/Consensus-Layer.md#gossip), [§ Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind), [§ Sheriff Module](../../proposals/consensus-layer/Consensus-Layer.md#sheriff-module)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
