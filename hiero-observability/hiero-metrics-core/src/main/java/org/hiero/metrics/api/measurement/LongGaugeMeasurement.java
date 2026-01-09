// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

/**
 * A gauge measurement that holds the latest {@code long} value that set by {@link #set(long)} to any value.
 * Updating gauge is thread-safe and atomic.
 */
public interface LongGaugeMeasurement {

    /**
     * Updates the gauge by adding the specified value.
     * The value can be positive or negative.
     *
     * @param value the value to update the gauge
     */
    void set(long value);
}
