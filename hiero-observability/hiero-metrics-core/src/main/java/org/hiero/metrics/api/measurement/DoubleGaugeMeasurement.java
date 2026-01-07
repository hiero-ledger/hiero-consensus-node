// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.measurement;

/**
 * A gauge measurement that holds the latest {@code double} value that set by {@link #set(double)}.
 * Updating gauge is thread-safe and atomic.
 */
public interface DoubleGaugeMeasurement {

    /**
     * Update the gauge by the specified value.
     * The value can be positive or negative.
     *
     * @param value the value to update the gauge by
     */
    void set(double value);
}
