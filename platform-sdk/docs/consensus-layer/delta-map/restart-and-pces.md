---
title: Delta map — restart-and-pces
kind: delta-map
last_reviewed: TBD
---

# Delta map: restart-and-pces

## Summary

PCES itself is done: dedicated modules, inline durable writes ahead of every
consumer, and replay through the normal intake path. The lifecycle inversion
is not — restart orchestration, startup state load, and ISS response remain
in platform-core, and the documented offline ISS-recovery procedure modifies
platform startup in a way that conflicts with the proposed Execution-owned
restart.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| PCES extracted to modules | Standalone persistence modules behind an API. | `consensus-pces` / `consensus-pces-impl` / `consensus-pces-noop-impl` with `module-info.java`. | **done** | `DefaultInlinePcesWriter` (`consensus-pces-impl`) |
| Events durable before emission | Pre-consensus events are persisted before any downstream component observes them. | Inline writer; consensus, gossip, and parent selection consume the written-events output. | **done** | `InlinePcesWriter` (`consensus-pces-impl`); write-then-emit soldering in `PlatformWiring` (`swirlds-platform-core`) |
| PCES replay through normal intake on startup | Restart rebuilds the in-memory hashgraph by replaying persisted events through the regular pipeline. | Reader and replayer implemented in the PCES module, with health-aware throttling. | **done** | `PcesFileReader`, `PcesReplayer` (`consensus-pces-impl`) |
| Restart orchestration owned by Execution | Execution loads the state, creates the Consensus instance, and drives replay. | Startup orchestration and initial state load live in platform-core. | **not-started** | `PlatformBuilder`, `SwirldsPlatform`, `StartupStateUtils` (`swirlds-platform-core`) — pre-proposal shape intact |
| ISS response under the Execution-owned lifecycle | With Execution owning state and restart, ISS response is an Execution concern. | ISS handling sits in platform-core, and the offline ISS-recovery procedure modifies platform startup — pulling against the proposed consensus/execution split. | **divergent** | `DefaultIssHandler` (`swirlds-platform-core`, `state/iss/internal`) |

## Cross-references

- Topic: [../architecture/topics/restart-and-pces.md](../architecture/topics/restart-and-pces.md)
- Proposal: [`Consensus-Layer.md` § Persistence](../../proposals/consensus-layer/Consensus-Layer.md#persistence), [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
