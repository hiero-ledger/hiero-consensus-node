---
type: delta-map
title: Delta map — wiring-framework
last_reviewed: TBD
---

# Delta map: wiring-framework

## Summary

The component framework already provides what the proposal assumes
internally: declarative wires, wire-level backpressure, and a health signal
published to module inputs. The outstanding delta is at the seam — the
proposal moves the Consensus/Execution boundary off wires onto a pulled
public API (`nextRound`) and makes Execution construct and own the
Consensus instance, neither of which has begun.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Module-level pull boundary (`nextRound`) above wire-level backpressure | Execution paces Consensus by pulling each round through a public API; wires stay an internal detail. | Consensus rounds are pushed to execution-side handlers via output wires; wire backpressure is the only mechanism at the seam. | **not-started** | `PlatformWiring` (`swirlds-platform-core`) solders `HashgraphModule.consensusRoundOutputWire()` — push shape intact |
| Wire-level backpressure retained inside Consensus | Internal wiring keeps queue-counter backpressure beneath the module-level overlay. | Counter-based scheduler backpressure plus an opt-in hard-backpressure mode. | **done** | `ObjectCounter`, `WiringConfig.hardBackpressureEnabled` (`swirlds-component-framework`) |
| Health signal published to module inputs | Components react to a system-health signal from the wiring model. | `HealthMonitor` emits the unhealthy duration; modules expose health input wires that consume it. | **done** | `HealthMonitor` (`swirlds-component-framework`), `EventCreatorModule.healthStatusInputWire()` (`consensus-event-creator`) |
| Wiring hidden behind an Execution-constructed Consensus facade | Execution creates and destroys Consensus instances; wiring is not visible at the boundary. | Component composition lives in platform-core; Execution does not construct the Consensus library. | **not-started** | `SwirldsPlatform`, `PlatformBuilder` (`swirlds-platform-core`) — pre-proposal orchestration intact |
| Top-level Consensus API/impl module pair | Execution compile-depends on a Consensus API module and runtime-depends on its implementation module. | No top-level Consensus module exists; the execution layer depends on platform-core directly. | **not-started** | `hedera-app` `module-info.java` requires `com.swirlds.platform.core` — direct dependency intact |
| `destroy` lifecycle operation | Execution destroys a Consensus instance via the public API, shutting down gossip connections, executors, and background tasks. | The platform manages its own teardown; Execution does not manage Consensus instances. | **not-started** | `SwirldsPlatform.destroy()` (`swirlds-platform-core`) — platform-managed teardown intact |

## Cross-references

- Topic: [../architecture/topics/wiring-framework.md](../architecture/topics/wiring-framework.md)
- Proposal: [`Consensus-Layer.md` § Design](../../proposals/consensus-layer/Consensus-Layer.md#design), [§ Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress), [§ Public API](../../proposals/consensus-layer/Consensus-Layer.md#public-api)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
