// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.datapoint;

/**
 * Base interface for a metric data point that holds a measurement values(s).
 * A metric data point is associated with set of labels and there can not be same metric two data points
 * with the same set of labels (including empty set of labels).
 * <p>
 * Data points are mutable and extensions must provide methods to update data point and get its value(s).
 * Data point can also be reset to it's initial state.
 * Implementations are expected to be thread-safe and handle concurrent updates atomically,
 * but not all implementations can provide this guarantee.
 */
public interface DataPoint {

    /**
     * Resets the data point to its initial state.
     * Implementations should ensure that any internal state is cleared or set back to default values.
     */
    void reset();
}
