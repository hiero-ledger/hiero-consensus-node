---
title: Delta map — iss-detection
kind: delta-map
last_reviewed: TBD
---

# Delta map: iss-detection

## Summary

The proposal never names ISS detection; the deltas below are implied by
its lifecycle inversion. Under the proposed split, Execution owns state
hashing and signature collection, so detecting hash disagreement becomes
Execution-internal rather than a consensus-layer concern. Current
detection, response, and boundary notification all sit in platform-core
— the pre-proposal shape.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| ISS detection moves with the Execution-owned state lifecycle | Implied by the lifecycle split, not named: Execution collects state signatures, so comparing the local hash against the consensus of peer hashes happens on the Execution side. | The detector and its supporting machinery live in platform-core, fed by platform wiring. | **not-started** | `IssDetector`, `RoundHashValidator`, `ConsensusHashFinder` (`swirlds-platform-core`) — pre-proposal shape intact |
| ISS response under the Execution-owned lifecycle | With Execution owning state and restart, the response to an ISS is an Execution concern. | The handler lives in platform-core, and the offline ISS-recovery procedure modifies platform startup — the same divergence recorded in [restart-and-pces.md](restart-and-pces.md). | **divergent** | `DefaultIssHandler` (`swirlds-platform-core`) |
| ISS surfaced across the boundary | The proposed public Consensus API has no ISS notification — under the split, none would be needed. | Detector output is forwarded to Execution-side application callbacks. | **not-started** | `AppNotifier.sendIssNotification` (`swirlds-platform-core`); [TBD: question for engineer — is ISS detection intended to become Execution-internal with the state-lifecycle handoff, or will the public Consensus API need an ISS surface?] |

## Cross-references

- Topic: [../architecture/topics/iss-detection.md](../architecture/topics/iss-detection.md)
- Proposal: [`Consensus-Layer.md` § Lifecycle of the Consensus Module](../../proposals/consensus-layer/Consensus-Layer.md#lifecycle-of-the-consensus-module) — the proposal's only textual ISS mention is a passing one in [§ Roster and Configuration Changes](../../proposals/consensus-layer/Consensus-Layer.md#roster-and-configuration-changes)
- Related delta maps: [signed-state-management.md](signed-state-management.md), [restart-and-pces.md](restart-and-pces.md)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
