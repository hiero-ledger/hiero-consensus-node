---

title: Restart and PCES
kind: architecture-topic
last_reviewed: TBD
------------------

# Restart and PCES

## Responsibilities

This topic owns the preconsensus event stream (PCES) — how the platform persists self-events before exposing them to gossip, how PCES files are replayed at restart, and the offline procedure for recovering from a network-wide ISS by replaying PCES on top of a known-good signed state.

Owns:

- The inline-PCES write path: persistence of self-events before they enter the gossip pipeline.
- Restart-time replay of PCES files into the intake pipeline.
- The offline ISS-recovery procedure (replay-on-top-of-state, dump fixed state to disk).

Does not own:

- Online recovery from falling behind — see `reconnect.md`.
- Freeze and upgrade orchestration — see `freeze-and-upgrade.md`.
- On-disk signed-state layout and lifecycle — see `signed-state-management.md`.

## Inline PCES (write path)

A self-event must be persisted to disk before it is gossiped. If a node gossips a self-event and crashes before the event reaches stable storage, on restart the node will not know the event existed and may build a new self-event on the same parent — a hashgraph branch. Branches are an attack on consensus and are punishable; honest nodes must not branch. The inline-PCES write path enforces the rule that gossip never sees a self-event the local store does not.

The PCES writer is synchronous: it accepts a `PlatformEvent` on its input wire and emits the same event on its output wire only after the write is complete. The writer interface is `InlinePcesWriter` (`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/writer/InlinePcesWriter.java`); the default implementation is `DefaultInlinePcesWriter` (`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/writer/DefaultInlinePcesWriter.java:58`). Whether a `sync()` is forced after the write is governed by the `event.preconsensus.inlinePcesSyncOption` config (`platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java:91`). When set to `EVERY_SELF_EVENT`, every self-event causes the buffer to be flushed to disk before the writer returns; the dispatch site is `DefaultInlinePcesWriter.java:77-84`. The enum is defined in `platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/config/FileSyncOption.java:15`.

The "persisted before gossip" invariant is enforced at the wiring layer rather than inside the writer. The writer's output wire is soldered to the gossip module's input via an `INJECT` ordering so that gossip cannot observe a self-event before the corresponding write completes:

```text
// platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:90-93
// Make sure events are persisted before being gossipped. This prevents accidental branching in the case
// where an event is created, gossipped, and then the node crashes before the event is persisted.
// After restart, a node will not be aware of this event, so it can create a branch
writtenEventOutputWire.solderTo(components.gossipModule().eventToGossipInputWire(), INJECT);
```

The same `writtenEventOutputWire` is also soldered to the consensus pipeline (`PlatformWiring.java:88`) and to the event creator's parent-selection input (`PlatformWiring.java:96`), so consensus and parent selection likewise see only persisted self-events.

> **Delta vs. inlinePces.md:** the source doc states that `EVERY_SELF_EVENT` "is the default behavior". The current default for `inlinePcesSyncOption` is `DONT_SYNC` (`PcesConfig.java:91`). [TBD: question for engineer — is the inlinePces.md claim stale, or do production deployments override the `DONT_SYNC` default to `EVERY_SELF_EVENT` via configuration?]

## Restart sequence

Restart has two phases. State load and replay-bound derivation happen during `SwirldsPlatform` construction, before `start()` is called. Replay-then-gossip happens inside `start()`.

1. **Load the initial signed state.** The latest signed state is loaded from disk during platform construction (`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/SwirldsPlatform.java:150` — `blocks.initialState().get()`).
2. **Derive replay bounds from the loaded state.** `startingRound` is set to the loaded state's last consensus round (`SwirldsPlatform.java:257`); `pcesReplayLowerBound` is set to the initial ancient threshold of the loaded state (`SwirldsPlatform.java:285`). For a genesis start, both are 0.
3. **Bring up core platform components.** `start()` brings up the recycle bin, metrics, and the platform coordinator (`SwirldsPlatform.java:353-355`).
4. **Replay PCES.** `platformComponents.pcesModule().replayPcesEvents(pcesReplayLowerBound, startingRound)` (`SwirldsPlatform.java:357`) runs the replay synchronously; control does not return until replay is done.
5. **Start gossip.** Only after replay completes does `platformCoordinator.startGossip()` run (`SwirldsPlatform.java:358`). Gossip never observes a partially-replayed state.

## Replay

PCES replay reuses the platform's normal intake pipeline; the only difference at replay time is that events come from on-disk PCES files rather than gossip.

- **Entry point.** `PcesModule.replayPcesEvents(lowerBound, startingRound)` (`platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/PcesModule.java:71`); the default implementation in `DefaultPcesModule.replayPcesEvents` (`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/DefaultPcesModule.java:149`) delegates to `PcesCoordinator.replayPcesEvents` (`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/PcesCoordinator.java:69`).
- **Read side.** `PcesFileTracker.getEventIterator(...)` (`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/common/PcesFileTracker.java:147`) opens an iterator over the PCES files for the requested round window. The coordinator injects this iterator into the replayer's input wire.
- **Emit side.** `PcesReplayer.replayPces(...)` (`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/replayer/PcesReplayer.java:147`) drives the iterator and forwards each event onto its output wire (`PcesReplayer.java:169-186`); from there the event flows through the same intake pipeline that gossip-delivered events use.
- **Backpressure.** The replay loop calls `waitUntilHealthy()` (`PcesReplayer.java:172`, implementation at `:206-214`) before emitting, blocking when the wiring model reports an unhealthy duration above `replayHealthThreshold` (`PcesConfig.java:88`). See `health-monitor-and-backpressure.md` for the health-monitor mechanism. [TBD: question for engineer — is the throttle applied only at the emit side, or does the read side also pause when the model is unhealthy?]

## Offline disaster-recovery procedure

Used when a network-wide ISS prevents the network from making progress on its own (no supermajority agrees on a single state). The procedure replays PCES on top of a known-good signed state from before the ISS, producing a fixed signed state that operators distribute to all nodes.

> **Delta vs. pces-disaster-recovery.md:** the source doc references `SwirldsPlatform.performPcesRecovery()`. That method has been removed from `SwirldsPlatform`; there is no current bootstrap entry point that drives the recovery procedure end-to-end. [TBD: question for engineer — with `performPcesRecovery()` gone, what is the supported way to drive ISS recovery today? A patched `SwirldsPlatform.start()`, a separate driver class, or something else?]
>
> **Delta vs. pces-disaster-recovery.md:** the source doc references `SwirldsPlatform.replayPreconsensusEvents()`. That method does not exist. The current replay entry point is `PcesModule.replayPcesEvents(pcesReplayLowerBound, startingRound)` (`SwirldsPlatform.java:357`).

### Prerequisites

- A production signed state from before the ISS divergence.
- The PCES files covering the period from the loaded state's round (minus the non-ancient round window) through the point of failure.

### Procedure shape

The recovery driver must reuse the platform's normal startup minus gossip, then dump the resulting state.

1. **Bring up the platform without gossip.** Perform the same construction-time work as a normal start, then run only the first three lines of `SwirldsPlatform.start()` (`SwirldsPlatform.java:353-355`: recycle bin, metrics, platform coordinator). Do **not** call `platformCoordinator.startGossip()` at line 358.
2. **Run replay.** Call `PcesModule.replayPcesEvents(pcesReplayLowerBound, startingRound)` (`SwirldsPlatform.java:357`). The replayer drains the PCES iterator into the intake pipeline; consensus is reached and transactions handle as during normal startup.
3. **Capture the resulting state.** Acquire the latest immutable state via the `latestImmutableStateNexus` (`SwirldsPlatform.java:114`; interface `SignedStateNexus.getState(reason)` at `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/state/nexus/SignedStateNexus.java:24`). The result is a `ReservedSignedState` (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/signed/ReservedSignedState.java:23`) that must be closed when done.
4. **Mark and dump.** On the underlying `SignedState`, call `markAsStateToSave(StateToDiskReason.PCES_RECOVERY_COMPLETE)` (`platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/snapshot/StateToDiskReason.java:38`); construct a `StateDumpRequest` via `StateDumpRequest.create(...)` (`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/StateDumpRequest.java:28`); hand it to `PlatformCoordinator.dumpStateToDisk(request)` (`platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java:199`); block on `request.waitForFinished()` so the process does not exit before the on-disk write completes.
5. **Close the last record file with the execution team.** Coordinate so that the execution-side block stream aligns with the dumped state's last consensus round. This is critical for ensuring the block stream is consistent with the recovered state.
6. **Distribute and restart.** Copy the recovered state to all nodes; restart the network from it.

### Implementation notes

- `pcesReplayLowerBound` is the initial ancient threshold from the loaded state, or 0 for genesis (`SwirldsPlatform.java:285`).
- `startingRound` is the last consensus round in the loaded state (`SwirldsPlatform.java:257`).
- After injecting the PCES iterator, the replay code flushes pipeline events to ensure all replayed transactions are processed before signaling that replay is complete.
- The blocking `StateDumpRequest.waitForFinished()` is essential — without it the JVM may exit before the on-disk write finishes, leaving an incomplete recovery state.

## Cross-references

- **Topics:** `signed-state-management.md`, `reconnect.md`, `freeze-and-upgrade.md`, `event-creator.md`, `event-intake.md`, `health-monitor-and-backpressure.md`.
- **Source docs:** `../../../core/inlinePces/inlinePces.md`, `../../../core/pces-disaster-recovery.md`.
- **Invariants:** [TBD: INV-NNN once the `invariants.md` catalog populates — candidate invariants from this topic include "self-events are persisted before being gossiped" and "gossip is not started until PCES replay completes"].
- **Decisions:** [TBD: ADR-NNN once the `decisions/` catalog populates].
- **Scenarios:** [TBD: SCN-NNN — ISS-recovery is a likely seed scenario].

## Future state (sidebar)

The [Consensus-Layer.md](../../../proposals/consensus-layer/Consensus-Layer.md) proposal places state-saving and lifecycle on the Execution side of the consensus/execution boundary. The current ISS-recovery procedure achieves the state dump by mutating platform startup directly inside the consensus-node bootstrap, which conflicts with that split. Aligning the recovery driver with the proposed boundary is out of scope for this topic; it should be revisited when the lifecycle-ownership move lands.
