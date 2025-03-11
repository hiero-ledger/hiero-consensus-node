// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats.cycle;

import com.swirlds.base.units.UnitConstants;
import com.swirlds.metrics.api.Metrics;

/**
 * Tracks the fraction of busy time to idle in a cycle
 */
public class CycleBusyTime extends PercentageMetric {
    private static final int PERCENT = 100;

    public CycleBusyTime(final Metrics metrics, final String category, final String name, final String description) {
        super(metrics, category, name, description, CycleBusyTime::busyPercentage);
    }

    private static double busyPercentage(final int b, final int i) {
        final int t = b + i;
        if (t == 0) {
            return 0;
        }
        return (((double) b) / t) * PERCENT;
    }

    /**
     * Add the amount of time the thread was busy
     *
     * @param nanoTime
     * 		the number of nanoseconds a thread was busy
     */
    public void addBusyTime(final long nanoTime) {
        super.update((int) (nanoTime * UnitConstants.NANOSECONDS_TO_MICROSECONDS), 0);
    }

    /**
     * Add the amount of time the thread was idle
     *
     * @param nanoTime
     * 		the number of nanoseconds a thread was idle
     */
    public void addIdleTime(final long nanoTime) {
        super.update(0, (int) (nanoTime * UnitConstants.NANOSECONDS_TO_MICROSECONDS));
    }
}
