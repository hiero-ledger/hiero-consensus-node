---
title: Delta map — hashgraph
kind: delta-map
last_reviewed: TBD
---

# Delta map: hashgraph

## Summary

The hashgraph engine is split into API/impl JPMS modules and is fully
birth-round based. Its output is still pushed over wires, though: the
proposal's signature change — `nextRound(roster)` as the public pull API
with per-round roster supply and Execution-driven initialization — has not
begun.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Hashgraph module split (API/impl, JPMS) | Standalone hashgraph module with a public interface and an SPI-provided implementation. | `consensus-hashgraph` / `consensus-hashgraph-impl` with `module-info.java`. | **done** | `HashgraphModule` (`consensus-hashgraph`), `ConsensusEngine`, `ConsensusImpl` (`consensus-hashgraph-impl`) |
| Birth-round ancient threshold in linking and consensus | Birth-round thresholds replace generation-based ancient handling throughout. | `EventWindow` drives the linker and consensus; no generation-based code remains. | **done** | `ConsensusLinker` (`consensus-hashgraph-impl`), `EventWindow` (`consensus-model`) |
| `nextRound(roster)` public pull API | Execution pulls each consensus round on demand; Consensus never advances unasked. | No `nextRound` exists; rounds are pushed via the output wire (the topic file carries `nextRound` only as a future-state callout). | **not-started** | `HashgraphModule.consensusRoundOutputWire()` (`consensus-hashgraph`) — push shape intact |
| Per-round roster supply with deterministic activation | Execution supplies the roster with each `nextRound` call; the hashgraph activates it a deterministic offset of rounds ahead. | Rosters reach consensus by a different path; no per-round supply. | **not-started** | [TBD: question for engineer — how does `ConsensusImpl` obtain the active roster today, and is any per-round roster change supported?] |
| Execution initializes Consensus from starting-round judges | On restart or reconnect, Execution creates the Consensus instance and initializes it from the judges of its chosen starting round. | Platform code loads the state and initializes consensus itself. | **not-started** | `StartupStateUtils`, `SwirldsPlatform` (`swirlds-platform-core`) — platform-driven startup intact |

## Cross-references

- Topic: [../architecture/topics/hashgraph.md](../architecture/topics/hashgraph.md)
- Proposal: [`Consensus-Layer.md` § Hashgraph Module](../../proposals/consensus-layer/Consensus-Layer.md#hashgraph-module), [§ Roster and Configuration Changes](../../proposals/consensus-layer/Consensus-Layer.md#roster-and-configuration-changes), [§ nextRound](../../proposals/consensus-layer/Consensus-Layer.md#nextround)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
