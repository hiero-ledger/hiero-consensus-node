---
title: Delta map — event-intake
kind: delta-map
last_reviewed: TBD
---

# Delta map: event-intake

## Summary

The intake pipeline — validation, deduplication, signature checking,
orphan-buffer ordering, birth-round filtering — matches the proposal and
lives in its own module pair. Persist-before-emission is in place, with
PCES deliberately kept as its own module (a permanent team decision
superseding the proposal's intake placement). Branch detection achieves
the proposed behaviour but sits outside the intake module, and the
Sheriff escalation path for detected branchers does not exist.

## Changes

| Change | Proposal state | Current state | Status | Anchor / TBD |
|---|---|---|---|---|
| Intake module split (API/impl, JPMS) | Standalone intake module with a public interface and an SPI-provided implementation. | `consensus-event-intake` / `consensus-event-intake-impl` with `module-info.java`. | **done** | `EventIntakeModule` (`consensus-event-intake`), `DefaultEventIntakeModule` (`consensus-event-intake-impl`) |
| Staged validation pipeline | Syntactic check, signature verification, and deduplication as discrete stages. | Pipeline implemented as discrete stages. | **done** | `InternalEventValidator`, `StandardEventDeduplicator`, `EventSignatureValidator` (`consensus-event-intake-impl`) |
| Topological ordering via orphan buffer | Events released to consumers parents-first. | Orphan buffer enforces parents-first release (hosted in the utility module, consumed by intake). | **done** | `OrphanBuffer` (`consensus-utility`) |
| Birth-round filtering at intake | Ancient events discarded by birth round during validation. | `EventWindow`-based ancient checks run inside the intake validators. | **done** | `EventWindow.isAncient()` (`consensus-model`) usage in intake validators |
| Durable persistence before emission | The intake system durably persists events before emitting them to any consumer. | The persist-before-observe behaviour holds; PCES is deliberately kept as its own module — a permanent team decision superseding the proposal's intake placement. | **done** | `DefaultInlinePcesWriter` (`consensus-pces-impl`); write-then-emit soldering in `PlatformWiring` (`swirlds-platform-core`) |
| Branch detection reporting to Sheriff | Intake detects branching nodes and reports the offender to the Sheriff. | Detection is live but lives in platform-core, not intake, and reports only to logs and metrics — there is no Sheriff to report to (see [sheriff.md](sheriff.md)). | **partial** | `DefaultBranchDetector`, `DefaultBranchReporter` (`swirlds-platform-core`, `com.swirlds.platform.event.branching`) |
| Self-event and replay pipeline bypass | Self events and replayed PCES events may skip validation steps already proven unnecessary for them. | A separate non-validated input wire carries self events past hashing and validation. | **done** | `EventIntakeModule.nonValidatedEventsInputWire()` (`consensus-event-intake`) |
| Pre-handle emission via the public API (`onPreHandleEvent`) | Each valid, ordered event is delivered to Execution for pre-handle, guaranteed to later reach consensus or become stale. | Pre-handle delivery exists through platform wiring; the public-API method does not. | **partial** | `DefaultTransactionPrehandler` (`swirlds-platform-core`) |

## Cross-references

- Topic: [../architecture/topics/event-intake.md](../architecture/topics/event-intake.md)
- Proposal: [`Consensus-Layer.md` § Event Intake](../../proposals/consensus-layer/Consensus-Layer.md#event-intake), [§ Birth-Round Filtering](../../proposals/consensus-layer/Consensus-Layer.md#birth-round-filtering), [§ Branch Detection](../../proposals/consensus-layer/Consensus-Layer.md#branch-detection), [§ Persistence](../../proposals/consensus-layer/Consensus-Layer.md#persistence)
- Invariants: [TBD: INV-NNN once invariants.md catalog populates]
