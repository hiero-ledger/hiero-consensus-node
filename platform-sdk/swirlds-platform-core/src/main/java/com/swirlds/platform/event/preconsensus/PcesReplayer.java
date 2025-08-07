// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.formatting.StringFormattingUtils.commaSeparatedNumber;
import static com.swirlds.common.units.TimeUnit.UNIT_MILLISECONDS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.formatting.UnitFormatter;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.wiring.NoInput;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * This class encapsulates the logic for replaying preconsensus events at boot up time.
 */
public class PcesReplayer {
    private static final Logger logger = LogManager.getLogger(PcesReplayer.class);

    private final Time time;

    private final StandardOutputWire<PlatformEvent> eventOutputWire;

    private final Runnable flushIntake;
    private final Runnable flushTransactionHandling;

    private final Supplier<ReservedSignedState> latestImmutableState;
    private final Supplier<Boolean> isSystemHealthy;

    private final PcesConfig config;

    /**
     * Constructor
     *
     * @param context the platform context
     * @param eventOutputWire the wire to put events on, to be replayed
     * @param flushIntake a runnable that flushes the intake pipeline
     * @param flushTransactionHandling a runnable that flushes the transaction handling pipeline
     * @param latestImmutableState a supplier of the latest immutable state
     * @param isSystemHealthy a supplier that returns true if the system is healthy and false if the system is
     * overwhelmed
     */
    public PcesReplayer(
            final @NonNull PlatformContext context,
            final @NonNull StandardOutputWire<PlatformEvent> eventOutputWire,
            final @NonNull Runnable flushIntake,
            final @NonNull Runnable flushTransactionHandling,
            final @NonNull Supplier<ReservedSignedState> latestImmutableState,
            final @NonNull Supplier<Boolean> isSystemHealthy) {

        this.time = context.getTime();
        this.eventOutputWire = Objects.requireNonNull(eventOutputWire);
        this.flushIntake = Objects.requireNonNull(flushIntake);
        this.flushTransactionHandling = Objects.requireNonNull(flushTransactionHandling);
        this.latestImmutableState = Objects.requireNonNull(latestImmutableState);
        this.isSystemHealthy = Objects.requireNonNull(isSystemHealthy);

        this.config = context.getConfiguration().getConfigData(PcesConfig.class);
    }

    /**
     * Log information about the replay
     *
     * @param timestampBeforeReplay the consensus timestamp before replay
     * @param roundBeforeReplay the round before replay
     * @param eventCount the number of events replayed
     * @param transactionCount the number of transactions replayed
     * @param elapsedTime the elapsed wall clock time during replay
     * @param maxBirthRound the maximum birth round of the events that were replayed
     */
    private void logReplayInfo(
            @Nullable final Instant timestampBeforeReplay,
            final long roundBeforeReplay,
            final long eventCount,
            final long transactionCount,
            @NonNull final Duration elapsedTime,
            final long maxBirthRound) {

        try (final ReservedSignedState stateAfterReplay = latestImmutableState.get()) {
            if (stateAfterReplay == null || stateAfterReplay.isNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        "Replayed {} preconsensus events. No rounds reached consensus.",
                        commaSeparatedNumber(eventCount));
                return;
            }

            final long roundAfterReplay = stateAfterReplay.get().getRound();
            final long elapsedRounds = roundAfterReplay - roundBeforeReplay;

            final Instant timestampAfterReplay = stateAfterReplay.get().getConsensusTimestamp();

            final Duration elapsedConsensusTime;
            if (timestampBeforeReplay != null) {
                elapsedConsensusTime = Duration.between(timestampBeforeReplay, timestampAfterReplay);
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
    }

    /**
     * Replays preconsensus events from disk.
     *
     * @param eventIterator an iterator over the events in the preconsensus stream
     * @return a trigger object indicating when the replay is complete
     */
    @NonNull
    public NoInput replayPces(@NonNull final IOIterator<PlatformEvent> eventIterator) {
        Objects.requireNonNull(eventIterator);

        final Instant start = time.now();
        final Instant timestampBeforeReplay;
        final long roundBeforeReplay;
        try (final ReservedSignedState startState = latestImmutableState.get()) {
            if (startState == null || startState.isNull()) {
                timestampBeforeReplay = null;
                roundBeforeReplay = -1;
            } else {
                timestampBeforeReplay = startState.get().getConsensusTimestamp();
                roundBeforeReplay = startState.get().getRound();
            }
        }

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

                eventCount++;
                transactionCount += event.getTransactionCount();
                maxBirthRound = Math.max(maxBirthRound, event.getBirthRound());

                eventOutputWire.forward(event);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("error encountered while reading from the PCES", e);
        }

        flushIntake.run();
        flushTransactionHandling.run();

        final Duration elapsedTime = Duration.between(start, time.now());

        logReplayInfo(
                timestampBeforeReplay, roundBeforeReplay, eventCount, transactionCount, elapsedTime, maxBirthRound);

        return NoInput.getInstance();
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
