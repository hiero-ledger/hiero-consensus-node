---
type: delta-map
title: Delta map — health-monitor-and-backpressure
last_reviewed: TBD
---

# Delta map: health-monitor-and-backpressure

## Summary

The proposal's per-node backpressure is emergent — birth-round filtering
and tipset backoff slow a stressed node until it refuses transactions — and
that mechanism is built. The module-level `nextRound` pull that lets
Execution pace Consensus is not started. The wire-level health monitor and
its sync-permit and replay-throttle reactions are current infrastructure
the proposal does not describe; whether they survive the `nextRound` model
is open.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Emergent per-node CPU-pressure response | A stressed node naturally slows event creation — birth-round filtering bounds incoming events, and the tipset algorithm stops creating when no parent advances consensus — and ultimately refuses user transactions (§ CPU Pressure). | Realized: tipset backoff halts creation, and transaction acceptance is gated under stress. | **done** | `TipsetEventCreator` (`consensus-event-creator-impl`), `EventWindow.isAncient()` (`consensus-model`), `TransactionPoolNexus` (`consensus-utility`) |
| `nextRound` module-level backpressure | Execution paces Consensus by pulling each round; Consensus never blocks on slow Execution (§ Slow Execution). | Consensus output is soldered directly to execution-side handlers; no `nextRound` exists. | **not-started** | `PlatformWiring` (`swirlds-platform-core`) — push wiring intact, no `nextRound` in code |
| Wire-level health monitor and its mitigation chain | Not specified — the proposal's stress response is emergent slowdown plus `nextRound`; it describes no health monitor, sync-permit revocation, or replay throttle. | `HealthMonitor` emits an unhealthy duration; the signal revokes sync permits and throttles PCES replay (and also drives the stress responses above). | **not-started** | `HealthMonitor` (`swirlds-component-framework`), `SyncPermitProvider` (`consensus-gossip-impl`), `PcesReplayer` (`consensus-pces-impl`); [TBD: question for engineer — is the health monitor retained under the `nextRound` model, or made redundant by Execution-paced backpressure?] |

## Cross-references

- Topic: [../architecture/topics/health-monitor-and-backpressure.md](../architecture/topics/health-monitor-and-backpressure.md)
- Proposal: [`Consensus-Layer.md` § Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress), [§ CPU Pressure](../../proposals/consensus-layer/Consensus-Layer.md#cpu-pressure), [§ Slow Execution](../../proposals/consensus-layer/Consensus-Layer.md#slow-execution)
- Related delta maps: [event-creator.md](event-creator.md), [hashgraph.md](hashgraph.md) — the emergent tipset/birth-round path
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
