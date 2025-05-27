// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.metric;

/**
 * An interface for tracking statistics about a series of numeric values.
 * Provides methods for updating the statistics with new values and retrieving key metrics
 * such as mean, count, minimum, and maximum.
 */
public interface NumberStats {
    /**
     * Updates the underlying statistics with a new numeric value.
     *
     * @param value the numeric value to be added to the statistics; must not be null
     * @param <T>   the type of the numeric value, which must extend {@link Number}
     */
    <T extends Number> void updateValue(T value);

    /**
     * Retrieves the mean (average) of the numeric values that have been added to the statistics.
     * If no values have been added, the behavior of this method depends on the implementation
     * but may return a default value such as {@code Double.NaN}.
     *
     * @return the mean of the numeric values, or a default value if no values are present
     */
    double getMean();

    /**
     * Retrieves the total count of numeric values that have been added to the statistics.
     *
     * @return the count of numeric values added
     */
    long getCount();

    /**
     * Retrieves the minimum value among the numeric values that have been added.
     * If no values have been added, the method returns {@code Double.NaN}.
     *
     * @return the minimum value of the numeric series, or {@code Double.NaN} if no values are present
     */
    double getMin();

    /**
     * Retrieves the maximum value among the numeric values that have been added.
     * If no values have been added, the method returns {@code Double.NaN}.
     *
     * @return the maximum value of the numeric series, or {@code Double.NaN} if no values are present
     */
    double getMax();
}
