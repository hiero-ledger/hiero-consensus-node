---
type: delta-map
title: Delta map — hashgraph
last_reviewed: 2026-06-18
---

# Delta map: hashgraph

## Summary

The hashgraph engine is split into API/impl JPMS modules and is fully
birth-round based. Its output is still pushed over wires, though: the proposal's
signature change — `nextRound(roster)` as the public pull API with per-round
roster supply, and the `onRound` / `onPreHandleEvent` callbacks — has not begun.
Execution-driven `initialize` is tracked in
[consensus-boundary.md](consensus-boundary.md).

## Changes

| Change | Proposal state | Proposal source | Current state | Status | Anchor / TBD |
|---|---|---|---|---|---|
| Hashgraph module split (API/impl, JPMS) | Standalone hashgraph module with a public interface and an SPI-provided implementation. | [§ Hashgraph Module](../../proposals/consensus-layer/Consensus-Layer.md#hashgraph-module), [§ Design](../../proposals/consensus-layer/Consensus-Layer.md#design) | `consensus-hashgraph` / `consensus-hashgraph-impl` with `module-info.java`. | **done** | `HashgraphModule` (`consensus-hashgraph`), `ConsensusEngine`, `ConsensusImpl` (`consensus-hashgraph-impl`) |
| Birth-round ancient threshold in linking and consensus | Birth-round thresholds replace generation-based ancient handling throughout. | [§ Birth-Round Filtering](../../proposals/consensus-layer/Consensus-Layer.md#birth-round-filtering), [§ Assumptions](../../proposals/consensus-layer/Consensus-Layer.md#assumptions) | `EventWindow` drives the linker and consensus; no generation-based code remains. | **done** | `ConsensusLinker` (`consensus-hashgraph-impl`), `EventWindow` (`consensus-model`) |
| `nextRound(roster)` public pull API | Execution pulls each consensus round on demand; Consensus never advances unasked. | [§ nextRound](../../proposals/consensus-layer/Consensus-Layer.md#nextround), [§ Roster and Configuration Changes](../../proposals/consensus-layer/Consensus-Layer.md#roster-and-configuration-changes) | No `nextRound` exists; rounds are pushed via the output wire (the topic file carries `nextRound` only as a future-state callout). | **not-started** | `HashgraphModule.consensusRoundOutputWire()` (`consensus-hashgraph`) — push shape intact |
| Per-round roster supply with deterministic activation | Execution supplies the roster with each `nextRound` call; the hashgraph activates it a deterministic offset of rounds ahead. | [§ Roster and Configuration Changes](../../proposals/consensus-layer/Consensus-Layer.md#roster-and-configuration-changes) | Rosters reach consensus by a different path; no per-round supply. | **not-started** | [TBD: question for engineer — how does `ConsensusImpl` obtain the active roster today, and is any per-round roster change supported?] |
| Round delivery callback (`onRound`) | Consensus calls Execution once per consensus round through the public API. | [§ onRound](../../proposals/consensus-layer/Consensus-Layer.md#onround) | Rounds are pushed via the output wire to the platform's transaction handler; no API callback. | **partial** | `TransactionHandler.handleConsensusRound` (`swirlds-platform-core`) |
| Pre-handle emission (`onPreHandleEvent`) | Consensus calls Execution once per topologically ordered event emitted from the Hashgraph module, each guaranteed to later reach consensus or become stale. | [§ onPreHandleEvent](../../proposals/consensus-layer/Consensus-Layer.md#onprehandleevent), [§ Emitting Events](../../proposals/consensus-layer/Consensus-Layer.md#emitting-events) | The hashgraph module emits pre-consensus events — responsibility moved here from intake to uphold the consensus-or-stale contract introduced for quiescence — delivered to the application's prehandler via platform wiring; the public-API method does not exist. | **partial** | `HashgraphModule.preconsensusEventOutputWire()` (`consensus-hashgraph`), `DefaultTransactionPrehandler` (`swirlds-platform-core`) |
| Consensus state in round metadata for Execution persistence | Round metadata carries a state section (the round's judges and related data); Execution persists it and supplies it back via `initialize`. | [§ State](../../proposals/consensus-layer/Consensus-Layer.md#state) | Rounds carry a consensus snapshot, but its persistence remains platform-side. | **partial** | `ConsensusRound` snapshot field (`consensus-model`); persistence via `consensus-platformstate` |

## Cross-references

- Topic: [../architecture/topics/hashgraph.md](../architecture/topics/hashgraph.md)
- Proposal: [`Consensus-Layer.md` § Hashgraph Module](../../proposals/consensus-layer/Consensus-Layer.md#hashgraph-module), [§ Roster and Configuration Changes](../../proposals/consensus-layer/Consensus-Layer.md#roster-and-configuration-changes), [§ nextRound](../../proposals/consensus-layer/Consensus-Layer.md#nextround)
- Related delta maps: [consensus-boundary.md](consensus-boundary.md) — `initialize` and the overarching Consensus/Execution seam (module pair, unified API, instance lifecycle)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
