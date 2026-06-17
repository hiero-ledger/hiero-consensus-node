---
type: delta-map
title: Delta map — health-monitor-and-backpressure
last_reviewed: TBD
---

# Delta map: health-monitor-and-backpressure

## Summary

Wire-level health monitoring and the pressure-mitigation chain — pause
event creation, revoke sync permits, throttle PCES replay — are done. The
module-level backpressure overlay, where Execution paces Consensus by
pulling rounds via `nextRound` so that Consensus never blocks on slow
Execution, is not started.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Wire-level health monitor | The wiring framework detects backed-up components and broadcasts an unhealthy-duration signal. | Implemented in the component framework. | **done** | `HealthMonitor` (`swirlds-component-framework`) |
| Health-driven mitigation chain | Under pressure the node stops creating events, stops accepting transactions, and sheds gossip and replay work. | The health signal gates event creation and transaction acceptance, revokes sync permits, and throttles PCES replay. | **done** | `SyncPermitProvider` (`consensus-gossip-impl`), `EventCreatorModule.healthStatusInputWire()` (`consensus-event-creator`), `TransactionPoolNexus` (`consensus-utility`), `PcesReplayer` (`consensus-pces-impl`) |
| Module-level (`nextRound`) backpressure overlay | Execution controls the rate at which Consensus produces rounds by pulling them; Consensus never blocks on Execution. | Consensus output is soldered directly to execution-side handlers; wire-level backpressure is the only mechanism. | **not-started** | `PlatformWiring` (`swirlds-platform-core`) — push wiring intact, no `nextRound` in code |

## Cross-references

- Topic: [../architecture/topics/health-monitor-and-backpressure.md](../architecture/topics/health-monitor-and-backpressure.md)
- Proposal: [`Consensus-Layer.md` § Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress), [§ CPU Pressure](../../proposals/consensus-layer/Consensus-Layer.md#cpu-pressure), [§ Slow Execution](../../proposals/consensus-layer/Consensus-Layer.md#slow-execution)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
