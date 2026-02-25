// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Regular implementation of the {@link TimeManager} interface that uses the system clock.
 */
public class RegularTimeManager extends AbstractTimeManager {

    /**
     * Constructor for the {@link RegularTimeManager} class.
     *
     * @param granularity the granularity of time
     */
    public RegularTimeManager(@NonNull final Duration granularity) {
        super(granularity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Instant now() {
        return Instant.now();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void advanceTime(@NonNull final Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException e) {
            throw new AssertionError("Interrupted while advancing time", e);
        }
    }
}
