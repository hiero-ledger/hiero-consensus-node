// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.replayer;

import static com.swirlds.base.formatting.StringFormattingUtils.commaSeparatedNumber;
import static com.swirlds.base.units.TimeUnit.UNIT_MILLISECONDS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.swirlds.base.formatting.UnitFormatter;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.throttle.RateLimiter;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.pces.config.PcesConfig;

/**
 * This class encapsulates the logic for replaying preconsensus events at boot up time.
 */
public class PcesReplayer {
    private static final Logger logger = LogManager.getLogger(PcesReplayer.class);

    private final Time time;

    private final StandardOutputWire<PlatformEvent> eventOutputWire;

    private final PcesModule pcesModule;
    private final EventIntakeModule eventIntakeModule;
    private final EventCreatorModule eventCreatorModule;
    private final HashgraphModule hashgraphModule;

    // This must be a runnable, and not the GossipModule until the circular dependency
    // from Gossip -> State -> Pces is broken.
    private final Runnable flushGossipModule;

    private final Supplier<Boolean> isSystemHealthy;

    private final PcesConfig config;

    @Nullable
    private ConsensusRound latestConsensusRound;

    /**
     * Constructor
     *
     * @param configuration        the platform configuration
     * @param time                 the time source
     * @param eventOutputWire      the wire to put events on, to be replayed
     * @param isSystemHealthy      a supplier that returns true if the system is healthy and false if the system is
     *                             overwhelmed
     */
    public PcesReplayer(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final PcesModule pcesModule,
            @NonNull final EventIntakeModule eventIntakeModule,
            @NonNull final EventCreatorModule eventCreatorModule,
            @NonNull final HashgraphModule hashgraphModule,
            @NonNull final Runnable flushGossipModule,
            @NonNull final StandardOutputWire<PlatformEvent> eventOutputWire,
            @NonNull final Supplier<Boolean> isSystemHealthy) {

        this.time = requireNonNull(time);
        this.eventOutputWire = requireNonNull(eventOutputWire);
        this.pcesModule = requireNonNull(pcesModule);
        this.eventIntakeModule = requireNonNull(eventIntakeModule);
        this.eventCreatorModule = requireNonNull(eventCreatorModule);
        this.hashgraphModule = requireNonNull(hashgraphModule);
        this.flushGossipModule = requireNonNull(flushGossipModule);
        this.isSystemHealthy = requireNonNull(isSystemHealthy);

        this.config = configuration.getConfigData(PcesConfig.class);
    }

    /**
     * Log information about the replay
     *
     * @param initialConsensusRound the consensus round before replaying
     * @param eventCount            the number of events replayed
     * @param transactionCount      the number of transactions replayed
     * @param elapsedTime           the elapsed wall clock time during replay
     * @param maxBirthRound         the maximum birth round of the events that were replayed
     */
    private void logReplayInfo(
            @Nullable final ConsensusRound initialConsensusRound,
            final long eventCount,
            final long transactionCount,
            @NonNull final Duration elapsedTime,
            final long maxBirthRound) {

        if (latestConsensusRound == null) {
            logger.info(
                    STARTUP.getMarker(),
                    "Replayed {} preconsensus events. No rounds reached consensus.",
                    commaSeparatedNumber(eventCount));
            return;
        }

        final long initialRoundNum = initialConsensusRound == null ? 0 : initialConsensusRound.getRoundNum();
        final long roundAfterReplay = latestConsensusRound.getRoundNum();
        final long elapsedRounds = roundAfterReplay - initialRoundNum;

        final Duration elapsedConsensusTime;
        if (initialConsensusRound != null) {
            final Instant timestampAfterReplay = latestConsensusRound.getConsensusTimestamp();
            elapsedConsensusTime =
                    Duration.between(initialConsensusRound.getConsensusTimestamp(), timestampAfterReplay);
        } else {
            elapsedConsensusTime = null;
        }

        logger.info(
                STARTUP.getMarker(),
                "Replayed {} preconsensus events with max birth round {}. These events contained {} transactions. "
                        + "{} rounds reached consensus spanning {} of consensus time. The latest "
                        + "round to reach consensus is round {}. Replay took {}.",
                commaSeparatedNumber(eventCount),
                commaSeparatedNumber(maxBirthRound),
                commaSeparatedNumber(transactionCount),
                commaSeparatedNumber(elapsedRounds),
                elapsedConsensusTime != null
                        ? new UnitFormatter(elapsedConsensusTime.toMillis(), UNIT_MILLISECONDS)
                        .setAbbreviate(false)
                        .render()
                        : "n/a",
                commaSeparatedNumber(roundAfterReplay),
                new UnitFormatter(elapsedTime.toMillis(), UNIT_MILLISECONDS)
                        .setAbbreviate(false)
                        .render());
    }

    /**
     * Replays pre-consensus events from disk and flushes the data through the system.
     *
     * @param eventIterator an iterator over the events in the preconsensus stream
     * @return a trigger object indicating when the replay is complete
     */
    @NonNull
    public NoInput replayPces(@NonNull final IOIterator<PlatformEvent> eventIterator) {
        requireNonNull(eventIterator);

        final Instant start = time.now();
        final ConsensusRound initialConsensusRound = latestConsensusRound;

        final RateLimiter rateLimiter = new RateLimiter(time, config.maxEventReplayFrequency());

        int eventCount = 0;
        int transactionCount = 0;
        long maxBirthRound = EventConstants.BIRTH_ROUND_UNDEFINED;
        try {
            while (eventIterator.hasNext()) {
                // If the system is not keeping up with the rate at which we are replaying PCES, we need to wait
                // until it catches up before we can continue.
                waitUntilHealthy();

                if (config.limitReplayFrequency() && !rateLimiter.requestAndTrigger()) {
                    continue;
                }

                final PlatformEvent event = eventIterator.next();
                event.setTimeReceived(time.now());

                eventCount++;
                transactionCount += event.getTransactionCount();
                maxBirthRound = Math.max(maxBirthRound, event.getBirthRound());

                eventOutputWire.forward(event);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("error encountered while reading from the PCES", e);
        }

        eventIntakeModule.flush();
        pcesModule.flush();
        flushGossipModule.run();
        hashgraphModule.flush();
        eventCreatorModule.flush();
//        transactionHandlingModule.flush();
//        stateModule.flush();

        final Duration elapsedTime = Duration.between(start, time.now());

        logReplayInfo(initialConsensusRound, eventCount, transactionCount, elapsedTime, maxBirthRound);

        return NoInput.getInstance();
    }

    public void setLatestConsensusRound(@NonNull final ConsensusRound latestConsensusRound) {
        this.latestConsensusRound = latestConsensusRound;
    }

    /**
     * Blocks until the system is in a healthy state. An unhealthy state is caused by the backlog of work growing too
     * large.
     */
    private void waitUntilHealthy() {
        while (!isSystemHealthy.get()) {
            // wait until the system is healthy
            try {
                MILLISECONDS.sleep(100);
            } catch (final InterruptedException e) {
                throw new RuntimeException("interrupted while replaying PCES", e);
            }
        }
    }
}
