// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.datapoint;

/**
 * A composite data point that contains multiple {@link DoubleGaugeDataPoint} instances.
 * <p>
 * This interface allows updating all contained gauges with a single {@link #update(double)} and provides
 * access to individual gauges via {@link #get(int)}.
 * It is useful for cases when different aggregations (e.g. sum, min, max, avg)
 * are maintained for the same metric with single update call.
 * <p>
 * Due to performance reasons default implementations of {@link #update(double)} and {@link #reset()}
 * are <b>not atomic</b>, iterating over data points and updating/resetting each sequentially.
 */
public interface DoubleGaugeCompositeDataPoint extends DataPoint {

    /**
     * Returns the number of contained {@link DoubleGaugeDataPoint} instances.
     *
     * @return the size of the composite data point
     */
    int size();

    /**
     * Returns the {@link DoubleGaugeDataPoint} at the specified index.
     *
     * @param index the index of the gauge to retrieve
     * @return the {@link DoubleGaugeDataPoint} at the specified index
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    DoubleGaugeDataPoint get(int index);

    /**
     * Updates all contained {@link DoubleGaugeDataPoint} instances with the given value. <br>
     * Default implementation iterates over all gauges and calls their update method, so is <b>not atomic</b>.
     *
     * @param value the value to set for all gauges
     */
    default void update(double value) {
        int size = size();
        for (int i = 0; i < size; i++) {
            get(i).update(value);
        }
    }

    /**
     * Resets all contained {@link DoubleGaugeDataPoint} instances to their initial state. <br>
     * Default implementation iterates over all gauges and calls their reset method, so is <b>not atomic</b>.
     */
    @Override
    default void reset() {
        int size = size();
        for (int i = 0; i < size; i++) {
            get(i).reset();
        }
    }
}
