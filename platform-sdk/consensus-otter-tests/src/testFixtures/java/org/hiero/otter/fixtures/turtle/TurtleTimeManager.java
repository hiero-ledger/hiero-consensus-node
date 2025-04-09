// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.test.fixtures.time.FakeTime;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hiero.otter.fixtures.TimeManager;

/**
 * A time manager for the turtle network.
 *
 * <p>This class implements the {@link TimeManager} interface and provides methods to control the time
 * in the turtle network. Time is simulated in the turtle framework.
 */
public class TurtleTimeManager implements TimeManager {

    private final FakeTime time;
    private final Duration granularity;
    private final List<TimeTickReceiver> timeTickReceivers = new ArrayList<>();

    /**
     * Constructor for the {@link TurtleTimeManager} class.
     *
     * @param time the source of the time in this simulation
     * @param granularity the granularity of time
     */
    public TurtleTimeManager(@NonNull final FakeTime time, @NonNull final Duration granularity) {
        this.time = requireNonNull(time);
        this.granularity = requireNonNull(granularity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitFor(@NonNull final Duration waitTime) {
        final Instant simulatedStart = time.now();
        final Instant simulatedEnd = simulatedStart.plus(waitTime);

        while (time.now().isBefore(simulatedEnd)) {
            time.tick(granularity);
            final Instant now = time.now();
            for (final TimeTickReceiver receiver : timeTickReceivers) {
                receiver.tick(now);
            }
        }
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
     * {@link TurtleTestEnvironment#GRANULARITY}. It is expected to perform any necessary actions that happened
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
