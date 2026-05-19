# ADR-003: Remove `SwirldsPlatform.performPcesRecovery()` and Drive Offline ISS Recovery On the Spot

## Status

Accepted

## Context

A network-wide ISS (Inconsistent State Signature) can leave the network unable to make progress — no supermajority
agrees on a single state, so the network cannot continue on its own. Recovery requires producing a fixed signed state
from before the divergence, replaying the relevant PCES on top of it, dumping the result, and distributing that fixed
state back to all nodes.

Historically, `SwirldsPlatform` carried two methods intended to support this flow:

- `SwirldsPlatform.performPcesRecovery()` — a bootstrap entry point that wired up the recovery procedure end-to-end.
- `SwirldsPlatform.replayPreconsensusEvents()` — an older replay entry point.

Both have been removed. The replay entry point was superseded by
`PcesModule.replayPcesEvents(pcesReplayLowerBound, startingRound)`
(`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/SwirldsPlatform.java:357`), which is now the
single, supported replay path. `performPcesRecovery()` was removed because **it had no automated tests** —
recovery-time code that has never been exercised in CI is more dangerous than no code at all. A subtle regression in an
untested recovery method would only surface during an emergency, at which point operators have no margin to diagnose
and fix it.

ISS-recovery events are also rare and not on a hot path. The platform team can write the small amount of glue code
needed at the moment recovery is invoked, against the platform state of the day, with engineers present.

## Decision

**Remove `SwirldsPlatform.performPcesRecovery()` (and `replayPreconsensusEvents()`) from the platform.** Do not
maintain a built-in, on-by-default entry point for offline ISS recovery.

When an ISS recovery is required, the platform team writes a one-off driver at the moment of need. The driver reuses
the public components already exercised in normal startup — `PcesModule.replayPcesEvents`, `SignedStateNexus`,
`PlatformCoordinator.dumpStateToDisk` — and stitches them together for the recovery flow. This ADR records the
constraints any such driver must satisfy.

## Procedure for an on-the-spot recovery driver

### Prerequisites

- A production signed state from before the ISS divergence.
- The PCES files covering the period from the loaded state's round (minus the non-ancient round window) through the
  point of failure.

### Steps

1. **Bring up the platform without gossip.** Perform the same construction-time work as a normal start, then run only
   the first three lines of `SwirldsPlatform.start()` (`SwirldsPlatform.java:353-355`: recycle bin, metrics, platform
   coordinator). Do **not** call `platformCoordinator.startGossip()` at line 358.
2. **Run replay.** Call `PcesModule.replayPcesEvents(pcesReplayLowerBound, startingRound)`
   (`SwirldsPlatform.java:357`). The replayer drains the PCES iterator into the intake pipeline; consensus is reached
   and transactions handle as during normal startup.
3. **Capture the resulting state.** Acquire the latest immutable state via the `latestImmutableStateNexus`
   (`SwirldsPlatform.java:114`; interface `SignedStateNexus.getState(reason)` at
   `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/state/nexus/SignedStateNexus.java:24`). The
   result is a `ReservedSignedState`
   (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/signed/ReservedSignedState.java:23`) that
   must be closed when done.
4. **Mark and dump.** On the underlying `SignedState`, call `markAsStateToSave(StateToDiskReason.PCES_RECOVERY_COMPLETE)`
   (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/snapshot/StateToDiskReason.java:38`);
   construct a `StateDumpRequest` via `StateDumpRequest.create(...)`
   (`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/StateDumpRequest.java:28`);
   hand it to `PlatformCoordinator.dumpStateToDisk(request)`
   (`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java:199`); block
   on `request.waitForFinished()` so the process does not exit before the on-disk write completes.
5. **Close the last record file with the execution team.** Coordinate so that the execution-side block stream aligns
   with the dumped state's last consensus round. This is critical for ensuring the block stream is consistent with the
   recovered state.
6. **Distribute and restart.** Copy the recovered state to all nodes; restart the network from it.

### Implementation notes

- `pcesReplayLowerBound` is the initial ancient threshold from the loaded state, or 0 for genesis
  (`SwirldsPlatform.java:285`).
- `startingRound` is the last consensus round in the loaded state (`SwirldsPlatform.java:257`).
- After injecting the PCES iterator, the replay code flushes pipeline events to ensure all replayed transactions are
  processed before signaling that replay is complete.
- The blocking `StateDumpRequest.waitForFinished()` is essential — without it the JVM may exit before the on-disk
  write finishes, leaving an incomplete recovery state.

## Consequences

### Positive

- **No untested recovery code path.** Anything we ship is exercised. Untested recovery code in the platform was a
  liability: it could rot silently between incidents and fail at the worst possible time.
- **Recovery code matches the platform of the day.** Writing the driver at the moment of need avoids the need to keep
  a separate recovery code path in sync with refactors to startup, wiring, and state management.
- **No ongoing maintenance cost** for a code path that may go years between uses.

### Negative

- **Slower recovery start-up.** An on-the-spot driver takes time to write, review, and verify before it can run.
  Acceptable given how rare the event is and how high the cost of a wrong recovery is — a careful, present-engineer
  process is preferable to a fast, blind one.
- **Tribal knowledge risk.** This ADR is the canonical reference for what a recovery driver must do; if it goes stale
  or is hard to find at 3am, an engineer may miss a step (the record/block-file coordination in particular is easy to
  forget). Mitigation: an operational runbook can be derived from this ADR and kept where on-call engineers look
  first.

### Neutral

- The replay path itself (`PcesModule.replayPcesEvents`) is exercised by normal startup, so the most complex piece of
  the recovery flow remains covered. The pieces removed were only the bootstrap glue.

## Alternatives Considered

### 1. Keep `performPcesRecovery()` and add tests

Retain the existing method and invest in CI coverage that exercises it end-to-end.

**Rejected because:**

- Realistic end-to-end recovery tests are expensive to build and maintain (state file fixtures, PCES file fixtures,
  coordination with execution-side block-file alignment).
- The method's surface drifts with startup and state-management changes; tests would need ongoing maintenance to keep
  pace.
- Even with tests, the method is invoked so rarely that operators would still need to relearn it at incident time.

### 2. Maintain a separate recovery driver module

Extract the recovery flow into its own module with its own owner, kept in lockstep with platform changes.

**Rejected because:**

- Creates a parallel artifact that must track platform internals it does not otherwise depend on.
- Same drift and staleness risk as keeping the method, plus the cost of a new module boundary.

### 3. Remove the method; write on the spot (selected)

See **Decision** above.

## Future state

The [Consensus-Layer.md](../../proposals/consensus-layer/Consensus-Layer.md) proposal places state-saving and
lifecycle on the Execution side of the consensus/execution boundary. The current recovery procedure achieves the state
dump by mutating platform startup directly inside the consensus-node bootstrap, which conflicts with that split.
Aligning an on-the-spot recovery driver with the proposed boundary is out of scope for this ADR; it should be
revisited when the lifecycle-ownership move lands.

## References

- Related: [`restart-and-pces.md`](../architecture/topics/restart-and-pces.md) — the architecture topic that owns the
  PCES write/replay path; carries a one-line pointer back to this ADR.
- Related: [`../../core/pces-disaster-recovery.md`](../../core/pces-disaster-recovery.md) — the older source doc whose
  procedure has been migrated into this ADR. The references in that doc to `SwirldsPlatform.performPcesRecovery()` and
  `SwirldsPlatform.replayPreconsensusEvents()` are stale.

## Authors / Deciders

- Kelly Greco (@poulok)
