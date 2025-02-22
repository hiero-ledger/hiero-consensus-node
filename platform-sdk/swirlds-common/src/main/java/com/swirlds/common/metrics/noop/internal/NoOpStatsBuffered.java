// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop.internal;

import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.common.metrics.statistics.internal.StatsBuffer;

/**
 * A no-op implementation of {@link StatsBuffered}.
 */
public class NoOpStatsBuffered implements StatsBuffered {

    /**
     * {@inheritDoc}
     */
    @Override
    public StatsBuffer getAllHistory() {
        return new StatsBuffer(0, 0, 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StatsBuffer getRecentHistory() {
        return new StatsBuffer(0, 0, 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset(final double halflife) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMean() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMax() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMin() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getStdDev() {
        return 0;
    }
}
