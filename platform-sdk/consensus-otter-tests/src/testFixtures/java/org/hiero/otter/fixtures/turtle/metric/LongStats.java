// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.metric;

/**
 * Implementation of {@link AbstractNumberStats} specifically for tracking
 * statistics on long-type numeric values. The class maintains and updates key
 * statistics, including the sum, minimum, maximum, and count of long values
 * processed.
 */
public class LongStats extends AbstractNumberStats {
    private long sum = 0;
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;

    /**
     * {@inheritDoc}
     */
    @Override
    protected <T extends Number> void update(final T value) {
        if (value instanceof Long number) {
            sum += number;
            min = Math.min(min, number);
            max = Math.max(max, number);
        } else {
            throw new IllegalArgumentException("Unexpected value type: " + value.getClass());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected double computeMean() {
        return (double) sum / count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected double getMinUnsafe() {
        return min;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected double getMaxUnsafe() {
        return max;
    }
}
