// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.internal.AbstractTimeManager;
import org.hiero.otter.fixtures.util.TimeoutException;

/**
 * A time manager for the turtle network.
 *
 * <p>This class implements the {@link TimeManager} interface and provides methods to control the time
 * in the turtle network. Time is simulated in the turtle framework.
 */
public class TurtleTimeManager extends AbstractTimeManager {

    private static final Logger log = LogManager.getLogger();

    private final FakeTime time;

    /**
     * Constructor for the {@link TurtleTimeManager} class.
     *
     * @param time the source of the time in this simulation
     * @param granularity the granularity of time
     */
    public TurtleTimeManager(@NonNull final FakeTime time, @NonNull final Duration granularity) {
        super(granularity);
        this.time = requireNonNull(time);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitForConditionInRealTime(@NonNull final BooleanSupplier condition, @NonNull final Duration waitTime)
            throws TimeoutException {
        waitForConditionInRealTime(condition, waitTime, "Condition not met within the allotted time.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitForConditionInRealTime(
            @NonNull final BooleanSupplier condition, @NonNull final Duration waitTime, @NonNull final String message)
            throws TimeoutException {
        log.debug("Waiting up to {} (in real-time!) for condition to become true...", waitTime);

        final Instant start = Instant.now();
        final Instant end = start.plus(waitTime);

        Instant now = start;
        while (!condition.getAsBoolean() && now.isBefore(end)) {
            for (final TimeTickReceiver receiver : timeTickReceivers) {
                receiver.tick(this.now()); // here we need to pass the simulated time
            }
            advanceTime(granularity); // advance simulated time
            try {
                Thread.sleep(granularity); // advance real time
            } catch (final InterruptedException e) {
                throw new AssertionError("Interrupted while advancing real time", e);
            }
            now = Instant.now();
        }

        if (!condition.getAsBoolean()) {
            throw new TimeoutException(message);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Instant now() {
        return time.now();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void advanceTime(@NonNull final Duration duration) {
        time.tick(duration);
    }

    /**
     * Returns the underlying {@link Time} instance.
     *
     * @return the underlying {@link Time} instance
     */
    @NonNull
    public Time time() {
        return time;
    }
}
