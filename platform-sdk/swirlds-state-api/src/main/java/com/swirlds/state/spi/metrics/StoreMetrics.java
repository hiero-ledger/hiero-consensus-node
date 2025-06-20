// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi.metrics;

/**
 * Helper class that maintains utilization metrics for a store.
 */
public interface StoreMetrics {

    /**
     * Update the metrics with the current count.
     *
     * @param newValue The current count.
     */
    void updateCount(final long newValue);
}
