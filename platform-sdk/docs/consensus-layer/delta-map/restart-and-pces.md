---
type: delta-map
title: Delta map — restart-and-pces
last_reviewed: 2026-06-19
---

# Delta map: restart-and-pces

## Summary

PCES itself is done: dedicated modules, inline durable writes ahead of every
consumer, and replay through the normal intake path — and by team decision
PCES will permanently remain its own module. The lifecycle inversion is not
started: restart orchestration, startup state load, and ISS response remain
in the consensus layer, with the offline ISS-recovery procedure still part of
platform startup.

## Changes

| Change | Proposal state | Proposal source | Current state | Status | Anchor / TBD |
|---|---|---|---|---|---|
| PCES extracted to modules | Standalone persistence modules behind an API. | [§ Persistence](../../proposals/consensus-layer/Consensus-Layer.md#persistence), [§ Design](../../proposals/consensus-layer/Consensus-Layer.md#design) | `consensus-pces` / `consensus-pces-impl` / `consensus-pces-noop-impl` with `module-info.java`; permanent by team decision. | **done** | `DefaultInlinePcesWriter` (`consensus-pces-impl`) |
| Events durable before emission | Pre-consensus events are persisted before any downstream component observes them. | [§ Persistence](../../proposals/consensus-layer/Consensus-Layer.md#persistence) | Inline writer; consensus, gossip, and parent selection consume the written-events output. | **done** | `InlinePcesWriter` (`consensus-pces-impl`); write-then-emit soldering in `PlatformWiring` (`swirlds-platform-core`) |
| PCES replay through normal intake on startup | Restart rebuilds the in-memory hashgraph by replaying persisted events through the regular pipeline. | [§ Persistence](../../proposals/consensus-layer/Consensus-Layer.md#persistence), [§ Self Events](../../proposals/consensus-layer/Consensus-Layer.md#self-events) | Reader and replayer implemented in the PCES module, with health-aware throttling. | **done** | `PcesFileReader`, `PcesReplayer` (`consensus-pces-impl`) |
| Restart orchestration owned by Execution | Execution loads the state, creates the Consensus instance, and drives replay. | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module) | Startup orchestration and initial state load live in the consensus layer. | **not-started** | `PlatformBuilder`, `SwirldsPlatform`, `StartupStateUtils` (`swirlds-platform-core`) — pre-proposal shape intact |
| ISS response under the Execution-owned lifecycle | With Execution owning state and restart, ISS response is an Execution concern. | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ Assumptions](../../proposals/consensus-layer/Consensus-Layer.md#assumptions) (implied) | ISS handling sits in the consensus layer, and the offline ISS-recovery procedure remains part of platform startup. | **not-started** | `DefaultIssHandler` (`swirlds-platform-core`, `state/iss/internal`) — pre-proposal shape intact |

## Cross-references

- Topic: [../architecture/topics/restart-and-pces.md](../architecture/topics/restart-and-pces.md)
- Proposal: [`Consensus-Layer.md` § Persistence](../../proposals/consensus-layer/Consensus-Layer.md#persistence), [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
