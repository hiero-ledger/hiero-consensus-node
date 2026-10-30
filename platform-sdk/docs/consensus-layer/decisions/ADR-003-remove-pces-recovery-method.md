---
type: decision
id: ADR-003
title: Remove `SwirldsPlatform.performPcesRecovery()` and Drive ISS Recovery On the Spot
topics: [restart-and-pces]
related:
  invariants: []
  decisions: []
  scenarios: []
  heuristics: []
  rules: []
status: accepted
date: 2026-05-19
deciders:
  - Kelly Greco (@poulok)
  - Lazar Petrovic (@lpetrovic05)
curated_by: Kelly Greco (@poulok)
last_reviewed: TBD
---

# ADR-003 — Remove `SwirldsPlatform.performPcesRecovery()` and Drive ISS Recovery On the Spot

## Context

A network-wide ISS (Inconsistent State Signature) can leave the network unable to make progress — no supermajority
agrees on a single state, so the network cannot continue on its own. Recovery requires starting with a fixed signed state
from before the divergence, replaying the relevant PCES on top of it, dumping the resulting state, and distributing that fixed
state back to all nodes.

Historically, `SwirldsPlatform` carried two methods intended to support this flow:

- `SwirldsPlatform.performPcesRecovery()` — a bootstrap entry point that wired up the recovery procedure end-to-end.
- `SwirldsPlatform.replayPreconsensusEvents()` — an older replay entry point.

Neither method had automated test coverage. The replay entry point was effectively superseded by
`PcesModule.replayPcesEvents(pcesReplayLowerBound, startingRound)`
(`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/SwirldsPlatform.java#start`), which is
exercised by every normal startup. The recovery bootstrap was not exercised by anything: it sat in `SwirldsPlatform`
as code that might or might not still work, and any regression in it would only surface during an emergency, at which
point operators have no margin to diagnose and fix it.

ISS-recovery events are also rare and not on a hot path. The consensus team can write the small amount of glue code
needed at the moment recovery is invoked, against the platform state of the day, with engineers present. Recovery is
not a consensus-only operation: the execution team must also be involved to ensure the final record/block file is
closed and written to disk aligned with the end of the resulting signed state.

## Decision

**Remove `SwirldsPlatform.performPcesRecovery()` and `SwirldsPlatform.replayPreconsensusEvents()` from the platform.**
Do not maintain a built-in entry point for offline ISS recovery. When an ISS recovery is required, the
consensus team writes a one-off driver at the moment of need, reusing the public components already exercised by
normal startup, and coordinates with the execution team to align the record/block stream with the resulting signed
state (see Step 5 below).

The recipe below records the minimum constraints any such driver must satisfy. It is included in the Decision because
it *is* the mechanism: the decision to not ship recovery code only holds up if the path that replaces it is documented
clearly enough that a present engineer can write it correctly under pressure. Two of its steps no longer meet that
bar against the current API — see **Recipe reachability** below.

### Prerequisites

The three artifacts below should all be taken from the **same node**. This is not strictly required, but it is the
easiest path — using artifacts from one node sidesteps any cross-node-consistency reasoning during recovery. Unless there are other factors in play, it's best to select the node with the most PCES events or the node that advanced the furthest in consensus time.

- A production signed state from before the ISS divergence.
- The PCES files covering the period from the loaded state's round (minus the non-ancient round window) through the
  point of failure.
- The record/block files covering through the point of failure.

### Steps

1. **Bring up the platform without gossip.** Perform the same construction-time work as a normal start, then run only
   the prelude of `SwirldsPlatform.start()`
   (`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/SwirldsPlatform.java#start`) — the four
   statements that start the recycle bin, the metrics provider, the metrics and the wiring model. Do **not** start
   gossip: skip the `gossipModule().startInputWire().inject(...)` call, the last statement of `start()`.
2. **Run replay.** Call `PcesModule.replayPcesEvents(pcesReplayLowerBound, startingRound)`
   (`platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/PcesModule.java#replayPcesEvents`) — the
   statement `start()` runs immediately before it injects gossip. The replayer drains the PCES iterator into the
   intake pipeline; consensus is reached and transactions handle as during normal startup.
3. **Capture the resulting state.** What is needed is the latest immutable state as a `ReservedSignedState`
   (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/signed/ReservedSignedState.java#ReservedSignedState`),
   which must be closed when done. The natural source is the `latestImmutableStateNexus` — interface
   `SignedStateNexus.getState(reason)` at
   `platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/nexus/SignedStateNexus.java#getState`.

   **Not followable from the public building blocks as of 2026-09-08.** The nexus is no longer a field on
   `SwirldsPlatform`: it is a local built in
   `platform-sdk/swirlds-platform-core/src/main/java/org/hiero/consensus/ConsensusLayerFactory.java#createLatestImmutableStateNexus`
   and handed only to `TransactionHandlingModule`, which exposes it as a setter input wire
   (`platform-sdk/consensus-transaction-handling/src/main/java/org/hiero/consensus/transaction/handling/TransactionHandlingModule.java#latestImmutableStateInputWire`)
   and not as a getter. It is not carried on `ConsensusLayerBuildingBlocks`, so a driver written against the public
   building blocks cannot reach it. The driver must therefore be written from inside the consensus-layer packages, or
   the modules must first expose an accessor. See **Recipe reachability** below.

4. **Mark and dump.** On the underlying `SignedState`, call `markAsStateToSave(StateToDiskReason.PCES_RECOVERY_COMPLETE)`
   (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/snapshot/StateToDiskReason.java#PCES_RECOVERY_COMPLETE`);
   construct a `StateDumpRequest` via `StateDumpRequest.create(...)`
   (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/saved/StateDumpRequest.java#create`);
   submit it to the state snapshot manager's dump task, handled by `StateSnapshotManager::dumpStateTask`
   (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/persistence/DefaultStateSnapshotManager.java#dumpStateTask`);
   then run `request.waitForFinished()` so the process does not exit before the on-disk write completes — it is a
   `Runnable` record component, so the blocking call is `request.waitForFinished().run()`, not the accessor alone.

   **Also not followable as of 2026-09-08.** `StateModule` exposes no dump input wire: `stateSnapshotManagerWiring`
   is private, and the wire is built in the constructor
   (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/StateModule.java#StateModule`) only so an
   unsoldered wire exists. Nothing under `src/main/java` injects a `StateDumpRequest`. See **Recipe reachability**.

5. **Close the last record or block file with the execution team.** Coordinate so that the execution-side block stream aligns
   with the dumped state's last consensus round. This is critical because it must be distributed along with the signed state.

6. **Distribute and restart.** Copy the recovered state, block files, and PCES to all nodes; restart the network from it.

### Implementation notes

- `pcesReplayLowerBound` and `startingRound` are no longer derived inside `SwirldsPlatform`; both are constructor
  arguments, computed by the caller in
  `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#build`. A driver
  that constructs the platform itself must supply them: `pcesReplayLowerBound` is
  `ancientThresholdOf(initialSignedState.getState())`, or 0 for genesis; `startingRound` is
  `initialSignedState.getRound()`, or 0 for genesis.
- After injecting the PCES iterator, the replay code flushes pipeline events to ensure all replayed transactions are
  processed before signaling that replay is complete
  (`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/replayer/PcesReplayer.java#replayPces`).
- Running `StateDumpRequest.waitForFinished()` is essential — without it the JVM may exit before the on-disk write
  finishes, leaving an incomplete recovery state. Note the component is a `Runnable`
  (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/saved/StateDumpRequest.java#StateDumpRequest`),
  so `request.waitForFinished()` returns immediately and blocks nothing; the driver must call `.run()` on it. The
  latch it awaits is released by `dumpStateTask` via `request.finishedCallback()`.

### Recipe reachability

Steps 3 and 4 name the mechanism the recovery driver needs, but neither handle is reachable from
`ConsensusLayerBuildingBlocks` today: the latest-immutable-state nexus is a private local wired only into
`TransactionHandlingModule`, and `StateModule` has no public dump input wire. A driver written against the public
building blocks therefore cannot complete the recipe as written.

Two reachable substitutes exist, and both are weaker than what the steps describe.
`platform-sdk/consensus-transaction-handling/src/main/java/org/hiero/consensus/transaction/handling/TransactionHandlingModule.java#stateOutputWire`
is an `OutputWire<ReservedSignedState>` a driver can solder a capture onto in place of reading the nexus. And
`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/StateModule.java#sendState`, called after
`markAsStateToSave(...)`, reaches disk through the state module's `saveToDiskFilter` and
`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/persistence/StateSnapshotManager.java#saveStateTask`
— but that is the *in-band* save path, which writes to the ordinary saved-state directory rather than a
reason-named dump directory and returns no completion handle, so it has no equivalent of the `waitForFinished`
guarantee this ADR calls essential.

Closing this is a decision the deciders have not made. Either the driver is written from inside the consensus-layer
packages, where the private handles are visible, or the modules expose the two accessors the recipe assumes. Whoever
drives the next recovery should resolve it before the recipe is relied on under pressure.

## Temporary Nature

This ADR — both the decision and the recipe it carries — is **temporary, pending block nodes going live**. Once block
nodes operate as the network-level source of truth for state, every node can be reset to a state the network has
already agreed on, eliminating the need for the offline replay-on-top-of-state procedure entirely. At that point,
network-wide ISS recovery reduces to "reset all nodes to the block-node-agreed state" — there is no glue code to
write, no record/block-file alignment to coordinate, and no PCES replay to drive. This ADR (including its recipe)
should be revisited and likely retired when block nodes go live.

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
  first. This risk has already materialized once: startup and state wiring moved out from under steps 3 and 4 without
  the recipe following — see **Recipe reachability**.

### Neutral

- The replay path itself (`PcesModule.replayPcesEvents`) is exercised by normal startup, so the most complex piece of
  the recovery flow remains covered. The pieces removed were only the bootstrap glue.
- **Future-state expectation.** The
  [`Consensus-Layer.md`](../../proposals/consensus-layer/Consensus-Layer.md) proposal places state-saving and
  lifecycle on the Execution side of the consensus/execution boundary. The recipe above achieves the state dump by
  mutating platform startup directly inside the consensus-node bootstrap, which conflicts with that split. Aligning
  the on-the-spot driver with the proposed boundary is out of scope for this ADR and should be revisited when the
  lifecycle-ownership move lands; until then, a recovery driver written today follows the recipe above.

## Alternatives Considered

### 1. Status quo — keep `performPcesRecovery()` as-is

Leave both methods in `SwirldsPlatform` untouched, untested, and unmaintained.

**Rejected because:**

- An untested recovery code path is worse than no code path: a regression hides until an incident exposes it, at which
  point operators must debug recovery code under time pressure.
- The method's surface drifts with startup and state-management refactors; without tests, drift is invisible.

### 2. Keep `performPcesRecovery()` and add tests

Retain the existing method and invest in CI coverage that exercises it end-to-end.

**Rejected because:**

- Realistic end-to-end recovery tests are expensive to build and maintain (state file fixtures, PCES file fixtures,
  coordination with execution-side block-file alignment).
- Even with tests, the method is invoked so rarely that operators would still need to relearn it at incident time —
  the testing investment buys regression coverage but not operator readiness.

### 3. Maintain a separate recovery driver module

Extract the recovery flow into its own module with its own owner, kept in lockstep with platform changes.

**Rejected because:**

- Creates a parallel artifact that must track platform internals it does not otherwise depend on.
- Same drift and staleness risk as keeping the method, plus the cost of a new module boundary.

### 4. Remove the methods; write on the spot (selected)

See **Decision** above.

## References

- [`../architecture/topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md) — the architecture topic
  that owns the PCES write/replay path; carries a one-line pointer back to this ADR for the recovery procedure.
- [`../../proposals/consensus-layer/Consensus-Layer.md`](../../proposals/consensus-layer/Consensus-Layer.md) — the
  proposal that moves state-saving and lifecycle to the Execution side; relevant context for any future revision of
  the recovery driver, as noted under **Neutral** consequences.

## Notes

- 2026-09-08 — refreshed the recipe against the current code. Decision unchanged; the mechanism it
  documents had drifted. Steps 3 and 4 no longer resolve: `latestImmutableStateNexus` left
  `SwirldsPlatform` in `fd06b80baf` (2026-07-17) and is now a private local in
  `ConsensusLayerFactory`, and `StateModule` exposes no dump input wire — both recorded under
  **Recipe reachability**, which also names the weaker reachable substitutes. `start()` runs a
  four-statement prelude, not three, and `initialAncientThreshold`/`startingRound` are constructor
  arguments computed in `PlatformBuilder#build`, not values `SwirldsPlatform` derives. Corrected
  `request.waitForFinished()` to `request.waitForFinished().run()` — the component is a `Runnable`,
  so the accessor alone blocks nothing. All `SwirldsPlatform.java:NN` citations were past
  end-of-file and are now `#symbol` anchors — Michael Heinrichs (@netopyr).
