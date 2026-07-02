---
type: delta-map
title: Delta map — reconnect
last_reviewed: 2026-06-19
---

# Delta map: reconnect

## Summary

The proposal removes reconnect from Consensus entirely: Execution learns of a
fallen-behind node via `onBehind`, obtains a state, and destroys and recreates
the Consensus instance. None of that ownership transfer has begun — reconnect
remains a consensus-internal procedure, and detection is consumed internally
rather than surfaced to Execution.

## Changes

|                           Change                            |                                                      Proposal state                                                       |                                                                                                  Proposal source                                                                                                   |                                    Current state                                    |     Status      |                                    Anchor / TBD                                     |
|-------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|-----------------|-------------------------------------------------------------------------------------|
| Execution owns reconnect; Consensus destroyed and recreated | Reconnect is an Execution procedure; the Consensus instance is torn down on falling behind and re-initialized afterwards. | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind) | Reconnect remains implemented inside consensus-layer modules.                       | **not-started** | `ReconnectController`, `ReconnectCoordinator` (`consensus-reconnect-impl`)          |
| `onBehind` public-API callback                              | Falling behind surfaces to Execution as a public callback; Execution decides the response.                                | [§ onBehind](../../proposals/consensus-layer/Consensus-Layer.md#onbehind), [§ Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind)                                                   | Detection triggers the consensus-internal reconnect flow; no callback to Execution. | **not-started** | `FallenBehindMonitor` (`consensus-utility`) — consensus-internal consumption intact |

## Cross-references

- Topic: [../architecture/topics/reconnect.md](../architecture/topics/reconnect.md)
- Proposal: [`Consensus-Layer.md` § Falling Behind](../../proposals/consensus-layer/Consensus-Layer.md#falling-behind), [§ onBehind](../../proposals/consensus-layer/Consensus-Layer.md#onbehind), [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module)
- Reconnect refactor: [reconnect-refactor-proposal.md](../../proposals/reconnect-refactor/reconnect-refactor-proposal.md) — the interim shape current code is passing through; cited for context, not scored here; possibly stale in places
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
