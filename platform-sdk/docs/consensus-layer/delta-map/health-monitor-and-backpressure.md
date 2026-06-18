---
type: delta-map
title: Delta map — health-monitor-and-backpressure
last_reviewed: 2026-06-18
---

# Delta map: health-monitor-and-backpressure

## Summary

The proposal's per-node backpressure is emergent — birth-round filtering
and tipset backoff slow a stressed node until it refuses transactions — and
that mechanism is built. The module-level `nextRound` pull that lets
Execution pace Consensus is not started. A temporary `HealthMonitor`
supplies interim backpressure in its place; whether the proposal's
mechanisms will ultimately suffice under stress is unresolved.

## Changes

| Change | Proposal state | Proposal source | Current state | Status | Anchor / TBD |
|---|---|---|---|---|---|
| Emergent per-node CPU-pressure response | A stressed node naturally slows event creation — birth-round filtering bounds incoming events, and the tipset algorithm stops creating when no parent advances consensus — and ultimately refuses user transactions (§ CPU Pressure). | [§ CPU Pressure](../../proposals/consensus-layer/Consensus-Layer.md#cpu-pressure), [§ Consensus Bottlenecks](../../proposals/consensus-layer/Consensus-Layer.md#consensus-bottlenecks) | Realized: tipset backoff halts creation, and transaction acceptance is gated under stress. | **done** | `TipsetEventCreator` (`consensus-event-creator-impl`), `EventWindow.isAncient()` (`consensus-model`), `TransactionPoolNexus` (`consensus-utility`) |
| `nextRound` module-level backpressure | Execution paces Consensus by pulling each round; Consensus never blocks on slow Execution (§ Slow Execution). | [§ Slow Execution](../../proposals/consensus-layer/Consensus-Layer.md#slow-execution), [§ nextRound](../../proposals/consensus-layer/Consensus-Layer.md#nextround) | Consensus output is soldered directly to execution-side handlers; no `nextRound` exists. A temporary `HealthMonitor` supplies interim backpressure — it tracks how long the system has been unhealthy (component queues backed up) and several components throttle in response, most importantly halting event creation and revoking gossip sync permits — until module-level backpressure is built. | **not-started** | `PlatformWiring` (`swirlds-platform-core`) — push wiring intact, no `nextRound` in code; interim signal `HealthMonitor` (`swirlds-component-framework`), consumed via `PlatformHealthRule` (`consensus-event-creator-impl`) and `SyncPermitProvider` (`consensus-gossip-impl`); [TBD: question for engineer — whether the proposal's emergent + `nextRound` mechanisms prove sufficient under stress is unresolved; the interim `HealthMonitor` may be retained or replaced by real backpressured wires] |

## Cross-references

- Topic: [../architecture/topics/health-monitor-and-backpressure.md](../architecture/topics/health-monitor-and-backpressure.md)
- Proposal: [`Consensus-Layer.md` § Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress), [§ CPU Pressure](../../proposals/consensus-layer/Consensus-Layer.md#cpu-pressure), [§ Slow Execution](../../proposals/consensus-layer/Consensus-Layer.md#slow-execution)
- Related delta maps: [event-creator.md](event-creator.md), [hashgraph.md](hashgraph.md) — the emergent tipset/birth-round path
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
