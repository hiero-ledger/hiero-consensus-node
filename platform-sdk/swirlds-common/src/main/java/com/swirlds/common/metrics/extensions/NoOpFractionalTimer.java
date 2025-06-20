// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.extensions;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A NoOp implementation of {@link FractionalTimer}.
 */
public class NoOpFractionalTimer implements FractionalTimer {

    private static final NoOpFractionalTimer INSTANCE = new NoOpFractionalTimer();

    private NoOpFractionalTimer() {}

    /**
     * Get the singleton instance.
     *
     * @return the singleton instance
     */
    public static FractionalTimer getInstance() {
        return INSTANCE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerMetric(
            @NonNull Metrics metrics, @NonNull String category, @NonNull String name, @NonNull String description) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void activate(final long now) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void activate() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void deactivate(final long now) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void deactivate() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public double getActiveFraction() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getAndReset() {
        return 0;
    }
}
