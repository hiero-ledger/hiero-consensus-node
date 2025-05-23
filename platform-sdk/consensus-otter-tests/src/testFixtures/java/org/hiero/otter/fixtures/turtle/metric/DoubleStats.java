// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.metric;

/**
 * Implementation of {@link AbstractNumberStats} specifically for tracking
 * statistics on double-type numeric values. It maintains and updates key statistics such as
 * mean, minimum, and maximum for a series of double values.
 */
public class DoubleStats extends AbstractNumberStats {
    private double mean = 0.0;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    /**
     * {@inheritDoc}
     */
    @Override
    protected <T extends Number> void update(final T value) {
        if (value instanceof Double number) {
            mean += (number - mean) / count;
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
        return mean;
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
