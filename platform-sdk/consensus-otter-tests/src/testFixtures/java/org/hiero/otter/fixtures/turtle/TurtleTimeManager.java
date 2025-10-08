// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.internal.AbstractTimeManager;

/**
 * A time manager for the turtle network.
 *
 * <p>This class implements the {@link TimeManager} interface and provides methods to control the time
 * in the turtle network. Time is simulated in the turtle framework.
 */
public class TurtleTimeManager extends AbstractTimeManager {

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
