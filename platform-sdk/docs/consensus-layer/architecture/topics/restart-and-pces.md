---
title: Restart and PCES
kind: architecture-topic
last_reviewed: TBD
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
module's validated-events output (`PlatformWiring.java:78-81`), so every event that survives intake validation is
written.

The writer is synchronous: it accepts a `PlatformEvent` on its input wire and emits the same event on its output wire
only after the write completes. The interface is `InlinePcesWriter` (
`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/writer/InlinePcesWriter.java`); the
default implementation is `DefaultInlinePcesWriter` (
`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/writer/DefaultInlinePcesWriter.java:58`).
`writeEvent` writes the event to the current mutable file unconditionally (`DefaultInlinePcesWriter.java:71-75`); the
underlying file writer is either a `PcesFileChannelWriter` (Linux default) or `PcesOutputStreamFileWriter` (macOS
default, where `FileChannel` is ~150× slower).

### Persisted-before-observed (consensus, gossip, parent selection)

No downstream component sees an event before the writer has written it. The writer's output wire is soldered to
consensus, gossip, and the event creator's parent-selection input:

```text
// platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java:86-96
// Make sure that an event is persisted before being sent to consensus. This avoids the situation where we
// reach consensus with events that might be lost due to a crash
writtenEventOutputWire.solderTo(components.hashgraphModule().eventInputWire());

// Make sure events are persisted before being gossipped. This prevents accidental branching in the case
// where an event is created, gossipped, and then the node crashes before the event is persisted.
// After restart, a node will not be aware of this event, so it can create a branch
writtenEventOutputWire.solderTo(components.gossipModule().eventToGossipInputWire(), INJECT);

// Avoid using events as parents before they are persisted
writtenEventOutputWire.solderTo(components.eventCreatorModule().orderedEventInputWire());
```

The general guarantee applies to every event: consensus never observes an event whose write has not returned. Applied
specifically to self-events on the gossip path, the same guarantee also serves an anti-branching role. If a node
gossiped a self-event and crashed before it was written, on restart the node would not know the event existed and could
build a new self-event on the same self-parent — a hashgraph branch (a Byzantine fault; see
[`../concepts/branching.md`](../concepts/branching.md)). Persisting self-events before they reach gossip eliminates that gap.

The `OBSERVING` status provides a secondary defense against branching in case PCES data is lost from disk. A restarting
node sits in `OBSERVING` — gossiping but not creating events — for a configurable window, giving it time to pick up any
of its own self-events that the network still holds. Under normal operation, the inline write keeps every gossipped
self-event on local disk, so this fallback is not exercised.

### Durability model

"Persisted" here means the event's bytes have been handed to the OS, not that `fsync()` has returned. The
`event.preconsensus.inlinePcesSyncOption` config (
`platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java:91`, enum at
`platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/config/FileSyncOption.java:15`) defaults to
`DONT_SYNC`: no `fsync()` is forced per event (dispatch at `DefaultInlinePcesWriter.java:77-84`). `EVERY_EVENT` and
`EVERY_SELF_EVENT` are available as alternatives but are not the production defaults.

Strong-enough durability is provided by a JVM shutdown hook in `CommonPcesWriter` (
`platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/common/CommonPcesWriter.java:136-150`)
that runs `currentMutableFile.sync()` followed by `close()` when the JVM exits. Under graceful shutdown — `SIGTERM`,
`System.exit`, normal exit — every event in the OS buffer is flushed to disk before the process terminates.

The residual failure mode is loss of host power or `SIGKILL`: the shutdown hook does not run, and any events still in
the OS buffer at the moment of failure are not on disk after restart. This risk is accepted. No event loss in this
window leads to an unrecoverable network state, including the loss of a keystone event — a network-wide loss of an
in-flight keystone is recoverable.

## Restart sequence

Restart has two phases. State load and replay-bound derivation happen during `SwirldsPlatform` construction, before
`start()` is called. Replay, then the enabling of gossip and event creation, happens inside `start()`.

1. **Load the initial signed state.** The latest signed state is loaded from disk during platform construction (
   `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/SwirldsPlatform.java:150` —
   `blocks.initialState().get()`).
2. **Derive replay bounds from the loaded state.** `startingRound` is set to the loaded state's last consensus round (
   `SwirldsPlatform.java:257`); `pcesReplayLowerBound` is set to the initial ancient threshold of the loaded state (
   `SwirldsPlatform.java:285`). For a genesis start, both are 0.
3. **Bring up core platform components.** `start()` brings up the recycle bin, metrics, and the platform coordinator (
   `SwirldsPlatform.java:353-355`).
4. **Replay PCES.** `platformComponents.pcesModule().replayPcesEvents(pcesReplayLowerBound, startingRound)` (
   `SwirldsPlatform.java:357`) runs the replay synchronously; control does not return until replay is done. See
   [Replay](#replay) for details.
5. **Start gossip; event creation remains off.** Only after replay completes does `platformCoordinator.startGossip()`
   run (`SwirldsPlatform.java:358`). Neither gossip nor event creation observes a partially-replayed state: gossip
   because it is started here, and event creation because it is gated on platform status. See `event-creator.md` (TBD) for the gating details.

## Replay

PCES replay reuses the platform's normal intake pipeline; the only difference at replay time is that events come from
on-disk PCES files rather than gossip.

- **Entry point.** `PcesModule.replayPcesEvents(lowerBound, startingRound)` (
  `platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/PcesModule.java:71`); the default implementation
  in `DefaultPcesModule.replayPcesEvents` (
  `platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/DefaultPcesModule.java:149`) delegates
  to `PcesCoordinator.replayPcesEvents` (
  `platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/PcesCoordinator.java:69`).
- **Read side.** `PcesFileTracker.getEventIterator(...)` (
  `platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/common/PcesFileTracker.java:147`) opens
  an iterator over the PCES files for the requested round window. The coordinator injects this iterator into the
  replayer's input wire.
- **Emit side.** `PcesReplayer.replayPces(...)` (
  `platform-sdk/consensus-pces-impl/src/main/java/org/hiero/consensus/pces/impl/replayer/PcesReplayer.java:147`) drives
  the iterator and forwards each event onto its output wire (`PcesReplayer.java:169-186`); from there the event flows
  through the same intake pipeline that gossip-delivered events use.
- **Backpressure.** The replay loop calls `waitUntilHealthy()` (`PcesReplayer.java:172`, implementation at `:206-214`)
  before emitting, blocking when the wiring model reports an unhealthy duration above `replayHealthThreshold` (
  `PcesConfig.java:88`). Because the iterator is lazy — `PcesMultiFileIterator` opens the next file only when the
  current one is exhausted (`PcesMultiFileIterator.java:70`), and `PcesFileIterator` reads one event at a time from a
  `BufferedInputStream` (`PcesFileIterator.java:38-39, 56-83`) — files are read just in time. While
  `waitUntilHealthy()` blocks, the iterator does not advance, no further events are read, and no new files are opened;
  read-side throughput is throttled implicitly by the emit-side block. See `health-monitor-and-backpressure.md` for the
  health-monitor mechanism.

## Offline ISS recovery

A network-wide ISS that prevents progress is resolved offline by replaying PCES on top of a known-good signed state
from before the divergence and distributing the resulting fixed state to all nodes. The platform does not carry a
built-in entry point for this; a one-off driver is written at the moment of need. See ADR-003 for the decision, the
recipe any driver must follow, and the record/block-file coordination with the execution team.

## Cross-references

- **Topics:** `signed-state-management.md`, `reconnect.md`, `freeze-and-upgrade.md`, `event-creator.md`,
  `event-intake.md`, `health-monitor-and-backpressure.md`.
- **Source docs:** `../../../core/inlinePces/inlinePces.md`, `../../../core/pces-disaster-recovery.md`.
- **Invariants:** [TBD: INV-NNN once the
  `invariants.md` catalog populates — candidate invariants from this topic include "self-events are persisted before being gossiped" and "gossip is not started until PCES replay completes"].
- **Decisions:** ADR-003 (offline ISS recovery is performed via an on-the-spot driver, not a built-in method).
- **Scenarios:** [TBD: SCN-NNN — ISS-recovery is a likely seed scenario].
