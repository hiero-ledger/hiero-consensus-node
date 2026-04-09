# PCES Disaster Recovery

## Overview

In the event of a catastrophic ISS (Invalid State Signature) across the network, it may be necessary
to generate a new valid state that includes all transactions that reached consensus. This document
describes the offline recovery procedure that uses the PCES (PreConsensus Event Stream) to replay
events on top of a known-good state, producing a corrected state that can be distributed to the
network.

## When This Procedure Is Needed

An ISS occurs when nodes in the network disagree on the state after processing the same set of
consensus events. If the ISS is widespread enough that the network cannot recover on its own (e.g.
there is no supermajority agreement on any single state), manual intervention is required.

The goal of this procedure is to take a production state from before the ISS divergence, replay
the PCES stream on top of it so that all consensus transactions are re-applied, and write the
resulting state to disk. That state can then be loaded by all nodes to restart the network from a
consistent point.

## How It Works

The recovery process reuses the platform's normal event replay infrastructure but skips gossip
entirely. At a high level:

1. All platform components needed for event handling, consensus, and transaction processing are
   started normally.
2. Gossip is **not** started.
3. Events from the PCES stream are replayed through the intake pipeline, reaching consensus and
   being handled as they would during normal startup replay.
4. After replay completes, the latest immutable state is written to disk.

The replay uses the same `PcesReplayer` that runs during normal startup. The difference is that
after replay finishes, the platform dumps the resulting state to disk instead of proceeding to
gossip.

## Procedure

### Prerequisites

- A production signed state from before the ISS divergence.
- The PCES files from the node, covering the period from the state's round minus the non-ancient number of rounds through the point of
  failure.

### Steps

1. **Modify the platform startup code** to call the recovery method instead of the normal `start()`
   flow. The recovery method should:
   - Start the recycle bin, metrics, and platform coordinator (the same initialization that
     `start()` performs).
   - Replay preconsensus events (see reference code below).
   - After replay, retrieve the latest immutable state from the state nexus, mark it with
     `StateToDiskReason.PCES_RECOVERY_COMPLETE`, and dump it to disk via `StateDumpRequest`.
   - Wait for the disk write to finish before exiting.
2. **Run the modified node** against the production state and PCES files. The node will replay all
   events, reach consensus, handle transactions, and write the final state to disk.
3. **Work with the execution team** to close the last record file so that it aligns with the
   resulting state written to disk. This is critical for ensuring the block stream is consistent
   with the recovered state.
4. **Distribute the recovered state** to all nodes in the network and restart.

## Reference Code

The following method was previously located in `SwirldsPlatform.java` and serves as a reference
implementation for the recovery process. It calls `replayPreconsensusEvents()`, which still exists
in `SwirldsPlatform` and is used during normal startup to replay the PCES stream through the intake
pipeline.

### `performPcesRecovery()`

This is the top-level recovery entry point. It starts the platform components, replays PCES events,
and writes the resulting state to disk.

```java
/**
 * Performs a PCES recovery:
 * <ul>
 *     <li>Starts all components for handling events</li>
 *     <li>Does not start gossip</li>
 *     <li>Replays events from PCES, reaches consensus on them and handles them</li>
 *     <li>Saves the last state produces by this replay to disk</li>
 * </ul>
 */
public void performPcesRecovery() {
    platformContext.getRecycleBin().start();
    platformContext.getMetrics().start();
    platformCoordinator.start();

    replayPreconsensusEvents();
    try (final ReservedSignedState reservedState = latestImmutableStateNexus.getState("Get PCES recovery state")) {
        if (reservedState == null) {
            logger.warn(
                    STATE_TO_DISK.getMarker(),
                    "Trying to dump PCES recovery state to disk, but no state is available.");
        } else {
            final SignedState signedState = reservedState.get();
            signedState.markAsStateToSave(StateToDiskReason.PCES_RECOVERY_COMPLETE);

            final StateDumpRequest request =
                    StateDumpRequest.create(signedState.reserve("dumping PCES recovery state"));

            platformCoordinator.dumpStateToDisk(request);

            request.waitForFinished().run();
        }
    }
}
```

## Key Implementation Notes

- The `pcesReplayLowerBound` is set to the initial ancient threshold from the loaded state (or 0
  for genesis). This ensures replay starts from the correct point in the PCES stream.
- The `startingRound` is the last consensus round in the loaded state.
- After injecting the PCES iterator, the code flushes the events in the pipeline to ensure all replayed
  transactions have been fully processed before signaling that replay is complete.
- The state dump uses `StateDumpRequest.waitForFinished()` to block until the state has been
  fully written to disk, ensuring the process does not exit prematurely.
