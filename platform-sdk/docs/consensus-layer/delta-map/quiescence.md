---
type: delta-map
title: Delta map — quiescence
last_reviewed: 2026-06-18
---

# Delta map: quiescence

## Summary

The proposal predates HIP-1238 and is silent on quiescence; current code
is canonical. The feature's division of labour already matches the
proposal's minimal-boundary philosophy — all detection lives on the
Execution side (hedera-app), and the consensus layer only obeys the latest
command (`QuiescenceRule`, `TipsetEventCreator` in
`consensus-event-creator-impl`) — so there is no delta there to track. The
concrete gaps are that the proposed public Consensus API has no quiescence
operation, and that quiescence's interaction with the `nextRound` pull
model is unspecified.

## Changes

|                     Change                     |                                                              Proposal state                                                              | Proposal source |                                                          Current state                                                          |     Status      |                                                                                                                         Anchor / TBD                                                                                                                          |
|------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|-----------------|---------------------------------------------------------------------------------------------------------------------------------|-----------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Quiescence command on the public Consensus API | The proposal's Public API enumerates the boundary operations; none carries quiescence — the proposal predates HIP-1238.                  |                 | Execution pushes a `QuiescenceCommand` through the platform boundary, fanned out to the event creator and the platform monitor. | **divergent**   | `Platform.quiescenceCommand` (`swirlds-platform-core`), `QuiescenceCommand` (`consensus-model`); [TBD: question for engineer — should the proposed public Consensus API gain a quiescence operation, or is quiescence to be re-specified when the API lands?] |
| Quiescence under the pull-based round API      | Unspecified: with `nextRound`, Execution paces Consensus directly; whether a separate command remains needed for event creation is open. |                 | Quiescence gates event creation by command; rounds are pushed.                                                                  | **not-started** | [TBD: question for engineer — under the proposed `nextRound` pull model, does quiescence remain a separate command gating event creation, or is it subsumed by Execution's pacing?]                                                                           |

## Cross-references

- Topic: [../architecture/topics/quiescence.md](../architecture/topics/quiescence.md)
- Proposal: [`Consensus-Layer.md` § Public API](../../proposals/consensus-layer/Consensus-Layer.md#public-api), [§ Liveness Under Stress](../../proposals/consensus-layer/Consensus-Layer.md#liveness-under-stress) — the proposal does not mention quiescence
- Spec: HIP-1238, *Network Quiescence* — the authoritative specification of current behaviour (see the topic file)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
