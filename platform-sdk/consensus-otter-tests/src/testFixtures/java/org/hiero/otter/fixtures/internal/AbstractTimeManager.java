// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.TimeManager;

/**
 * An abstract implementation of {@link TimeManager} that contains all functionality shared between the different
 * concrete implementations.
 *
 * <p>This class implements the {@link TimeManager} interface and provides methods to control the time
 * in the turtle network. Time is simulated in the turtle framework.
 */
public abstract class AbstractTimeManager implements TimeManager {

    private static final Logger log = LogManager.getLogger();

    private final Duration granularity;
    private final List<TimeTickReceiver> timeTickReceivers = new CopyOnWriteArrayList<>();

    /**
     * Constructor for the {@link AbstractTimeManager} class.
     *
     * @param granularity the granularity of time
     */
    public AbstractTimeManager(@NonNull final Duration granularity) {
        this.granularity = requireNonNull(granularity);
    }

    /**
     * Advance the time by the specified duration.
     *
     * @param duration the duration to advance the time by
     */
    protected abstract void advanceTime(@NonNull final Duration duration);

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitFor(@NonNull final Duration waitTime) {
        log.info("Waiting for {}...", waitTime);

        waitForCondition(() -> false, waitTime);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean waitForCondition(@NonNull final BooleanSupplier condition, @NonNull final Duration waitTime) {
        log.debug("Waiting up to {} for condition to become true...", waitTime);

        final Instant start = now();
        final Instant end = start.plus(waitTime);

        Instant now = start;
        while (!condition.getAsBoolean() && now.isBefore(end)) {
            advanceTime(granularity);
            now = now();
            for (final TimeTickReceiver receiver : timeTickReceivers) {
                receiver.tick(now);
            }
        }

        return condition.getAsBoolean();
    }

    /**
     * Adds a {@link TimeTickReceiver} to the list of receivers that will be notified when time ticks.
     *
     * @param receiver the receiver to add
     */
    public void addTimeTickReceiver(@NonNull final TimeTickReceiver receiver) {
        timeTickReceivers.add(receiver);
    }

    /**
     * A receiver of time ticks.
     *
     * <p>A receiver of time ticks is notified when the time manager advances time by the granularity specified in
     * {@link #granularity}. It is expected to perform any necessary actions that happened
     * between this call and the previous call.
     */
    public interface TimeTickReceiver {

        /**
         * Called when the time manager advances the time by the configured granularity.
         *
         * @param now the current time in the simulation
         */
        void tick(@NonNull final Instant now);
    }
}
