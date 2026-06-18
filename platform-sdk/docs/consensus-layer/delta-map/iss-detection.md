---
type: delta-map
title: Delta map — iss-detection
last_reviewed: 2026-06-18
---

# Delta map: iss-detection

## Summary

The proposal never names ISS detection; the deltas below are implied by
its lifecycle inversion. Under the proposed split, Execution owns state
hashing and signature collection, so detecting hash disagreement becomes
Execution-internal rather than a consensus-layer concern. Current
detection and response both sit in platform-core — the pre-proposal
shape.

## Changes

| Change | Proposal state | Proposal source | Current state | Status | Anchor / TBD |
|---|---|---|---|---|---|
| ISS detection moves with the Execution-owned state lifecycle | Implied by the lifecycle split, not named: Execution collects state signatures, so comparing the local hash against the consensus of peer hashes happens on the Execution side. | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ Assumptions](../../proposals/consensus-layer/Consensus-Layer.md#assumptions) (implied) | The detector and its supporting machinery live in platform-core, fed by platform wiring; its output is currently forwarded to Execution-side app callbacks via `AppNotifier.sendIssNotification`. | **not-started** | `IssDetector`, `RoundHashValidator`, `ConsensusHashFinder` (`swirlds-platform-core`) — pre-proposal shape intact |
| ISS response under the Execution-owned lifecycle | With Execution owning state and restart, the response to an ISS is an Execution concern. | [§ Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module), [§ Assumptions](../../proposals/consensus-layer/Consensus-Layer.md#assumptions) (implied) | The handler lives in platform-core, and the offline ISS-recovery procedure remains part of platform startup — the same gap recorded in [restart-and-pces.md](restart-and-pces.md). | **not-started** | `DefaultIssHandler` (`swirlds-platform-core`) — pre-proposal shape intact |

## Cross-references

- Topic: [../architecture/topics/iss-detection.md](../architecture/topics/iss-detection.md)
- Proposal: [`Consensus-Layer.md` § Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module) — the proposal's only textual ISS mention is a passing one in [§ Roster and Configuration Changes](../../proposals/consensus-layer/Consensus-Layer.md#roster-and-configuration-changes)
- Related delta maps: [signed-state-management.md](signed-state-management.md), [restart-and-pces.md](restart-and-pces.md)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
