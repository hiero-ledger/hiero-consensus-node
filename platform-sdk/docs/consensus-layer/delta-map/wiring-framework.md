---
type: delta-map
title: Delta map — wiring-framework
last_reviewed: TBD
---

# Delta map: wiring-framework

## Summary

The proposal specifies the wiring framework only at the seam — the
`nextRound` pull boundary, the API/impl module pairs, and Execution owning
the Consensus instance — and explicitly disclaims internal implementation
detail. The component framework already provides the internal substrate
(declarative wires, wire-level backpressure, a health signal), but the
proposal neither describes nor requires it; whether the pull model retains
those mechanisms is open. None of the seam-level deltas has begun.

## Changes

| Change | Proposal state | Proposal source | Current state | Status | Anchor / TBD |
|---|---|---|---|---|---|
| Pull-based Consensus/Execution boundary (`nextRound`) | Execution paces Consensus by pulling each round through a public API; internal wiring is left a private detail. | [§ nextRound](../../proposals/consensus-layer/Consensus-Layer.md#nextround), [§ Slow Execution](../../proposals/consensus-layer/Consensus-Layer.md#slow-execution) | Consensus rounds are pushed to execution-side handlers via output wires; wire backpressure is the only mechanism at the seam. | **not-started** | `PlatformWiring` (`swirlds-platform-core`) solders `HashgraphModule.consensusRoundOutputWire()` — push shape intact |
| Wire-level backpressure under the pull model | Not specified — the proposal disclaims implementation detail and names only `nextRound` as backpressure; whether the pull model retains internal wire-level backpressure or makes it redundant is open. |  | Counter-based scheduler backpressure plus an opt-in hard-backpressure mode. | **not-started** | `ObjectCounter`, `WiringConfig.hardBackpressureEnabled` (`swirlds-component-framework`); [TBD: question for engineer — does the `nextRound` pull model keep internal wire-level backpressure as a detail, or supersede it?] |
| Health signal under the pull model | Not specified — the proposal describes no health monitor or system-health signal; its stress handling is birth-round filtering, tipset backoff, and `nextRound`. Whether the health signal survives the pull model is open. |  | `HealthMonitor` emits the unhealthy duration; modules expose health input wires that consume it. | **not-started** | `HealthMonitor` (`swirlds-component-framework`), `EventCreatorModule.healthStatusInputWire()` (`consensus-event-creator`); [TBD: question for engineer — is the health-monitor signal retained under the `nextRound` model, or subsumed by Execution-paced backpressure?] |
| Wiring hidden behind an Execution-constructed Consensus facade | Execution creates and destroys Consensus instances; wiring is not visible at the boundary. | [§ Design](../../proposals/consensus-layer/Consensus-Layer.md#design), [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module) | Component composition lives in platform-core; Execution does not construct the Consensus library. | **not-started** | `SwirldsPlatform`, `PlatformBuilder` (`swirlds-platform-core`) — pre-proposal orchestration intact |
| Top-level Consensus API/impl module pair | Execution compile-depends on a Consensus API module and runtime-depends on its implementation module. | [§ Design](../../proposals/consensus-layer/Consensus-Layer.md#design) | No top-level Consensus module exists; the execution layer depends on platform-core directly. | **not-started** | `hedera-app` `module-info.java` requires `com.swirlds.platform.core` — direct dependency intact |
| `destroy` lifecycle operation | Execution destroys a Consensus instance via the public API, shutting down gossip connections, executors, and background tasks. | [§ destroy](../../proposals/consensus-layer/Consensus-Layer.md#destroy), [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module) | The platform manages its own teardown; Execution does not manage Consensus instances. | **not-started** | `SwirldsPlatform.destroy()` (`swirlds-platform-core`) — platform-managed teardown intact |

## Cross-references

- Topic: [../architecture/topics/wiring-framework.md](../architecture/topics/wiring-framework.md)
- Proposal: [`Consensus-Layer.md` § Design](../../proposals/consensus-layer/Consensus-Layer.md#design), [§ Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress), [§ Public API](../../proposals/consensus-layer/Consensus-Layer.md#public-api)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
