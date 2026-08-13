---
type: architecture-topic
title: Restart and PCES
last_reviewed: 2026-08-12
---

# Restart and PCES

## Responsibilities

This topic owns the preconsensus event stream (PCES) — how the platform persists every validated event in topological
order so the in-memory hashgraph can be rebuilt after a crash, how PCES files are replayed at restart, and the offline
procedure for recovering from a network-wide ISS by replaying PCES on top of a known-good signed state.

Owns:

- The PCES write path and its durability model.
- The persisted-before-observed invariant for consensus, gossip, and parent selection.
- Restart-time replay of PCES files into the intake pipeline.
- The offline ISS-recovery procedure (replay-on-top-of-state, dump fixed state to disk).

Does not own:

- Online recovery from falling behind — see `reconnect.md`.
- Freeze and upgrade orchestration — see `freeze-and-upgrade.md`.
- On-disk signed-state layout and lifecycle — see `signed-state-management.md`.

## Write path

PCES exists so that consensus can recover its in-memory state after a crash. Events live in the hashgraph in memory; if
every node in the network crashes simultaneously, every node loses every non-ancient event it has not yet written down.
Replaying PCES at startup is what rebuilds the hashgraph so consensus can resume. For this to work, PCES must persist
every validated, deduplicated event in topological order — not only self-events. The writer's input is the event-intake
module's validated-events output (`ConsensusLayerWiring.java:84-88`), so every event that survives intake validation is
written.

The writer is synchronous: it accepts a `PlatformEvent` on its input wire and emits the same event on its output wire
only after the write completes. The interface is `InlinePcesWriter` (
`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/writer/InlinePcesWriter.java`); the
default implementation is `DefaultInlinePcesWriter` (
`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/writer/DefaultInlinePcesWriter.java#writeEvent`).
`writeEvent` writes the event to the current mutable file unconditionally (`DefaultInlinePcesWriter.java#writeEvent`); the
underlying file writer is either a `PcesFileChannelWriter` (Linux default) or `PcesOutputStreamFileWriter` (macOS
default, where `FileChannel` is ~150× slower).

### Persisted-before-observed (consensus, gossip, parent selection)

No downstream component sees an event before the writer has written it. The writer's output wire is soldered to
consensus, gossip, and the event creator's parent-selection input:

```text
// platform-sdk/swirlds-platform-core/src/main/java/org/hiero/consensus/ConsensusLayerWiring.java:108-118
// Make sure that an event is persisted before being sent to consensus. This avoids the situation where we
// reach consensus with events that might be lost due to a crash
writtenEventOutputWire.solderTo(buildingBlocks.hashgraphModule().eventInputWire());

// Make sure events are persisted before being gossipped. This prevents accidental branching in the case
// where an event is created, gossipped, and then the node crashes before the event is persisted.
// After restart, a node will not be aware of this event, so it can create a branch
writtenEventOutputWire.solderTo(buildingBlocks.gossipModule().eventToGossipInputWire(), INJECT);

// Avoid using events as parents before they are persisted
writtenEventOutputWire.solderTo(buildingBlocks.eventCreatorModule().orderedEventInputWire());
```

The general guarantee applies to every event: consensus never observes an event whose write has not returned. Applied
specifically to self-events on the gossip path, the same guarantee also serves an anti-branching role. If a node
gossiped a self-event and crashed before it was written, on restart the node would not know the event existed and could
build a new self-event on the same self-parent — a hashgraph branch (a Byzantine fault; see
[`../../concepts/branching.md`](../../concepts/branching.md)). Persisting self-events before they reach gossip eliminates that gap.

The `OBSERVING` status provides a secondary defense against branching in case PCES data is lost from disk: a restarting
node gossips without creating events for a window, giving it time to relearn any of its own self-events that the network
still holds. Under normal operation, the inline write keeps every gossipped self-event on local disk, so this fallback is
not exercised. For the status mechanics see [`platform-status.md`](platform-status.md); for why the status is retained
despite the PCES guarantee, see [ADR-004](../../decisions/ADR-004-retain-observing-status-for-self-event-recovery.md).

### Durability model

"Persisted" here means the event's bytes have been handed to the OS, not that `fsync()` has returned. The
`event.preconsensus.inlinePcesSyncOption` config (
`platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java#inlinePcesSyncOption`, enum at
`platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/config/FileSyncOption.java#EVERY_SELF_EVENT`) defaults to
`DONT_SYNC`: no `fsync()` is forced per event (dispatch at `DefaultInlinePcesWriter.java#writeEvent`). `EVERY_EVENT` and
`EVERY_SELF_EVENT` are available as alternatives but are not the production defaults.

Strong-enough durability is provided by `DefaultInlinePcesWriter.destroy()` (`DefaultInlinePcesWriter.java#destroy`), which
waits for any in-flight write to finish and then calls `CommonPcesWriter.destroy()` (
`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/common/CommonPcesWriter.java#destroy`) to run
`currentMutableFile.sync()` followed by `close()`. Two paths reach it, and it is idempotent:

- **The platform's own shutdown.** `SwirldsPlatform.destroy()` calls `PcesModule.destroy()` after stopping the wiring
  model (`SwirldsPlatform.java#destroy`), so a node shut down through the platform API closes its stream without depending on
  the process exiting.
- **A JVM shutdown hook,** registered in the writer's constructor (`DefaultInlinePcesWriter.java#DefaultInlinePcesWriter`) and deregistered
  once `destroy()` has run. This covers an exit that never reaches the platform API — `SIGTERM`, `System.exit`, normal
  exit — so every event in the OS buffer is flushed to disk before the process terminates.

The residual failure mode is loss of host power or `SIGKILL`: neither path runs, and any events still in
the OS buffer at the moment of failure are not on disk after restart. This risk is accepted. No event loss in this
window leads to an unrecoverable network state, including the loss of a keystone event — a network-wide loss of an
in-flight keystone is recoverable.

Event loss is not the only consequence, however. The lost tail is by definition the node's newest events, so a node
whose own latest self-event went missing this way replays an older one and must relearn the rest from peers before it
resumes creating events, or it branches. [`event-creator.md`](event-creator.md#state) carries the rule the event creator
applies to adopt a relearned self event, and ADR-004 covers why the `OBSERVING` window is what gives the relearn time to
happen.

## Restart sequence

Restart has two phases. State load and replay-bound derivation happen in `PlatformBuilder.build()`, before
`SwirldsPlatform.start()` is called. Replay, then the enabling of gossip and event creation, happens inside `start()`.

1. **Load the initial signed state.** The application supplies the initial state to `PlatformBuilder`, which reads it
   during `build()` (
   `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java#build` —
   `initialState.get()`).
2. **Derive replay bounds from the loaded state.** `startingRound` is set to the loaded state's last consensus round (
   `initialSignedState.getRound()`) and the replay lower bound to its initial ancient threshold (`ancientThresholdOf(...)`);
   both are passed to the `SwirldsPlatform` constructor (`PlatformBuilder.java#build`). For a genesis start, both are 0 (
   `PlatformBuilder.java#build`).
3. **Bring up core platform components.** `start()` brings up the recycle bin, metrics, and the wiring model (
   `SwirldsPlatform.java#start`).
4. **Replay PCES.** `buildingBlocks.pcesModule().replayPcesEvents(initialAncientThreshold, startingRound)` (
   `SwirldsPlatform.java#start`) runs the replay synchronously; control does not return until replay is done. See
   [Replay](#replay) for details.
5. **Start gossip; event creation remains off.** Only after replay completes does
   `buildingBlocks.gossipModule().startInputWire().inject(NoInput.getInstance())` run (`SwirldsPlatform.java#start`).
   Neither gossip nor event creation observes a partially-replayed state: gossip because it is started here, and event
   creation because it is gated on platform status. See [`event-creator.md`](event-creator.md#permission-gates) (the
   `PlatformStatusRule` gate) for the gating details.

## Replay

PCES replay reuses the platform's normal intake pipeline; the only difference at replay time is that events come from
on-disk PCES files rather than gossip.

- **Entry point.** `PcesModule.replayPcesEvents(lowerBound, startingRound)` (
  `platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/PcesModule.java#replayPcesEvents`); the default implementation
  in `DefaultPcesModule.replayPcesEvents` (
  `platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/DefaultPcesModule.java#replayPcesEvents`) delegates
  to `PcesCoordinator.replayPcesEvents` (
  `platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/PcesCoordinator.java#replayPcesEvents`).
- **Read side.** `PcesFileTracker.getEventIterator(...)` (
  `platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/common/PcesFileTracker.java#getEventIterator`) opens
  an iterator over the PCES files for the requested round window. The coordinator injects this iterator into the
  replayer's input wire.
- **Emit side.** `PcesReplayer.replayPces(...)` (
  `platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/replayer/PcesReplayer.java#replayPces`) drives
  the iterator and forwards each event onto its output wire (`PcesReplayer.java#replayPces`); from there the event flows
  through the same intake pipeline that gossip-delivered events use.
- **Backpressure.** The replay loop calls `waitUntilHealthy()` (`PcesReplayer.java#replayPces`, implementation at `#waitUntilHealthy`)
  before emitting, blocking when the wiring model reports an unhealthy duration above `replayHealthThreshold` (
  `PcesConfig.java#replayHealthThreshold`). Because the iterator is lazy — `PcesMultiFileIterator` opens the next file only when the
  current one is exhausted (`PcesMultiFileIterator.java#findNext`), and `PcesFileIterator` reads one event at a time from a
  `BufferedInputStream` (`PcesFileIterator.java#PcesFileIterator`, `#findNext`) — files are read just in time. While
  `waitUntilHealthy()` blocks, the iterator does not advance, no further events are read, and no new files are opened;
  read-side throughput is throttled implicitly by the emit-side block. See `health-monitor-and-backpressure.md` for the
  health-monitor mechanism.

## Consensus initialization and the init-judge gate

Replay (above) feeds events back through the consensus algorithm, but many of them already reached consensus in the run
that produced the signed state being loaded — their transactions are already reflected in that state. Re-emitting those
rounds would corrupt the resulting state, and even if the application detected and dropped the duplicates, recomputing
them is wasted work.

To prevent that, consensus emits no rounds during initialization until the snapshot round's judges have been replayed,
then marks the events they already decided as consensus *without* emitting them — so no round that fed the loaded state
flows out of the hashgraph a second time (upholding INV-008). The gate lives in the consensus algorithm, not in PCES;
its mechanics are detailed in [`hashgraph.md`](hashgraph.md#algorithm-in-current-code) under *Init-judge gate*.

## Offline ISS recovery

A network-wide ISS that prevents progress is resolved offline by replaying PCES on top of a known-good signed state
from before the divergence and distributing the resulting fixed state to all nodes. The platform does not carry a
built-in entry point for this; a one-off driver is written at the moment of need. See ADR-003 for the decision, the
recipe any driver must follow, and the record/block-file coordination with the execution team.

## Cross-references

- **Topics:** `hashgraph.md`, `signed-state-management.md`, `reconnect.md`, `freeze-and-upgrade.md`, `event-creator.md`,
  `event-intake.md`, `health-monitor-and-backpressure.md`.
- **Source docs:** `../../../core/inlinePces/inlinePces.md`, `../../../core/pces-disaster-recovery.md`.
- **Invariants:** INV-008 — consensus, once reached, is permanent; INV-005 — every honest event eventually reaches consensus or becomes stale.
- **Decisions:** ADR-003 (offline ISS recovery is performed via an on-the-spot driver, not a built-in method); ADR-004
  (why `OBSERVING` is retained as the self-event relearn window); ADR-008 (why the event creator reads no ordering key
  when adopting a relearned self event — the adoption rule itself is in [`event-creator.md`](event-creator.md#state)).
- **Rules:** RUL-003 — every node contributing to consensus is independently restartable, which rests on the write path
  and durability model above; RUL-002 — the intake flush ordering that guarantees the event creator has observed its
  latest self event before it resumes creating.
- **Scenarios:** [TBD: SCN-NNN — ISS-recovery is a likely seed scenario].
