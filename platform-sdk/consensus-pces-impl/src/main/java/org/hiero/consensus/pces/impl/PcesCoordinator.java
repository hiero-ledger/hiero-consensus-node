// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.status.PlatformStatusAction;
import org.hiero.consensus.pces.actions.DoneReplayingEventsAction;
import org.hiero.consensus.pces.actions.StartedReplayingEventsAction;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;
import org.hiero.consensus.pces.impl.replayer.PcesReplayer;
import org.hiero.consensus.pces.impl.replayer.PcesReplayerWiring;

/**
 * The {@link PcesCoordinator} is responsible for coordinating the replay of events from the preconsensus event stream
 * (PCES) at startup. It reads events from the PCES files using the {@link PcesFileTracker} and feeds them into the
 * {@link PcesReplayer} for replay. It also reports status updates to the platform and signals when PCES replay is
 * complete.
 */
public class PcesCoordinator {

    private static final Logger logger = LogManager.getLogger();

    private final Time time;
    private final PcesFileTracker initialPcesFiles;
    private final PcesReplayerWiring pcesReplayerWiring;
    private final Consumer<PlatformStatusAction> statusActionConsumer;
    private final Runnable stateHasherFlusher;
    private final Runnable signalEndOfPcesReplay;

    /**
     * Creates a new {@link PcesCoordinator}.
     *
     * @param time the time source
     * @param initialPcesFiles the {@link PcesFileTracker} to read the PCES files from
     * @param pcesReplayerWiring the wiring for the {@link PcesReplayer}
     * @param statusActionConsumer a consumer for {@link PlatformStatusAction}s to report status updates to the platform
     * @param stateHasherFlusher a {@link Runnable} that triggers flushing of the state hasher
     * @param signalEndOfPcesReplay a {@link Runnable} that signals the end of PCES replay to the ISS detector
     */
    public PcesCoordinator(
            @NonNull final Time time,
            @NonNull final PcesFileTracker initialPcesFiles,
            @NonNull final PcesReplayerWiring pcesReplayerWiring,
            @NonNull final Consumer<PlatformStatusAction> statusActionConsumer,
            @NonNull final Runnable stateHasherFlusher,
            @NonNull final Runnable signalEndOfPcesReplay) {
        this.time = requireNonNull(time);
        this.initialPcesFiles = requireNonNull(initialPcesFiles);
        this.pcesReplayerWiring = requireNonNull(pcesReplayerWiring);
        this.statusActionConsumer = requireNonNull(statusActionConsumer);
        this.stateHasherFlusher = requireNonNull(stateHasherFlusher);
        this.signalEndOfPcesReplay = requireNonNull(signalEndOfPcesReplay);
    }

    /**
     * Replays events from the preconsensus event stream starting from the given lower bound round.
     *
     * @param pcesReplayLowerBound the lower bound round to start replaying from (inclusive)
     * @param startingRound the current round at the time of startup, used for logging purposes
     */
    public void replayPcesEvents(final long pcesReplayLowerBound, final long startingRound) {
        requireNonNull(initialPcesFiles, "Not initialized");
        statusActionConsumer.accept(new StartedReplayingEventsAction());

        final IOIterator<PlatformEvent> iterator =
                initialPcesFiles.getEventIterator(pcesReplayLowerBound, startingRound);

        logger.info(STARTUP.getMarker(), "replaying preconsensus event stream starting at {}", pcesReplayLowerBound);

        pcesReplayerWiring.pcesIteratorInputWire().inject(iterator);

        // We have to wait for all the PCES transactions to reach the ISS detector before telling it that PCES replay is
        // done. The PCES replay will flush the intake pipeline, but we have to flush the hasher

        // FUTURE WORK: These flushes can be done by the PCES replayer.
        stateHasherFlusher.run();
        signalEndOfPcesReplay.run();

        statusActionConsumer.accept(new DoneReplayingEventsAction(time.now()));
    }
}
