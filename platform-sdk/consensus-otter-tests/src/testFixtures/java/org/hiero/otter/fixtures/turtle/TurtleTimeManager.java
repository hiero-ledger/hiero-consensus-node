// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.time.TimeTickReceiver;

/**
 * A time manager for the turtle network.
 *
 * <p>This class implements the {@link TimeManager} interface and provides methods to control the time
 * in the turtle network. Time is simulated in the turtle framework.
 */
public class TurtleTimeManager implements TimeManager {

    private final Duration granularity;
    private final FakeTime time;
    private final List<TimeTickReceiver> timeTickReceivers = new ArrayList<>();

    /**
     * Constructor for the {@link TurtleTimeManager} class.
     *
     * @param randotron a random number generator
     * @param granularity the granularity of time
     */
    public TurtleTimeManager(@NonNull final Randotron randotron, @NonNull final Duration granularity) {
        this.granularity = requireNonNull(granularity);
        time = new FakeTime(randotron.nextInstant(), Duration.ZERO);
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
}
