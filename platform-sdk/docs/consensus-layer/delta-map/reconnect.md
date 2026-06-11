---
title: Delta map — reconnect
kind: delta-map
last_reviewed: TBD
---

# Delta map: reconnect

## Summary

Two proposals pull on this topic. The consensus-layer proposal removes
reconnect from Consensus entirely: Execution learns of a fallen-behind node
via `onBehind`, obtains a state, and destroys and recreates the Consensus
instance. The reconnect-refactor proposal instead restructures reconnect
*inside* the consensus layer, and current code tracks it — so rows below are
scored against the consensus-layer proposal, with the reconnect-refactor
proposal noted where it is the active target.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Execution owns reconnect; Consensus destroyed and recreated | Reconnect is an Execution procedure; the Consensus instance is torn down on falling behind and re-initialized afterwards. | Reconnect is implemented inside consensus-layer modules and actively developed there per the reconnect-refactor proposal. | **divergent** | `ReconnectController`, `ReconnectCoordinator` (`consensus-reconnect-impl`) |
| `onBehind` public-API callback | Falling behind surfaces to Execution as a public callback; Execution decides the response. | Detection triggers the consensus-internal reconnect flow; no callback to Execution. | **not-started** | `FallenBehindMonitor` (`consensus-utility`) — consensus-internal consumption intact |
| Reconnect-refactor target types (`PlatformReconnecter`, `StateSyncProtocol`, `ReservedSignedStatePromise`) | The reconnect-refactor proposal consolidates orchestration into these types. | None exist; `ReservedSignedStatePromise` appears only in a comment. | **not-started** | comment-only reference in `ReconnectController` (`consensus-reconnect-impl`) |
| `FallenBehindManager` → `FallenBehindMonitor` rename | The reconnect-refactor proposal renames the manager to a direct monitor. | Done. | **done** | `FallenBehindMonitor` (`consensus-utility`, `monitoring`) |
| Learner/teacher/protocol restructure | The reconnect-refactor proposal reshapes the learner, teacher, and protocol classes. | `ReconnectStateLearner` / `ReconnectStateTeacher` / `ReconnectStateSyncProtocol` / `ReconnectStatePeerProtocol` track the refactor's naming — which itself diverges from the consensus-layer proposal's removal of reconnect from Consensus. | **divergent** | `ReconnectStateLearner`, `ReconnectStateTeacher`, `ReconnectStateSyncProtocol`, `ReconnectStatePeerProtocol` (`consensus-reconnect-impl`) |

## Cross-references

- Topic: [../architecture/topics/reconnect.md](../architecture/topics/reconnect.md)
- Proposal: [`Consensus-Layer.md` § Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind), [§ onBehind](../../proposals/consensus-layer/Consensus-Layer.md#onbehind), [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module)
- Reconnect refactor: [reconnect-refactor-proposal.md](../../proposals/reconnect-refactor/reconnect-refactor-proposal.md) — the proposal current code is tracking; possibly stale in places
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
