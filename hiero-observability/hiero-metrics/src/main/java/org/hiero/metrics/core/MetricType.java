// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

/**
 * The type of metric that is compliant with OpenMetrics specification.
 */
public enum MetricType {
    /**
     * A cumulative metric that represents a single monotonically increasing counter value.
     */
    COUNTER,
    /**
     * A metric that represents a single numerical value that can arbitrarily go up and down and set to any value.
     */
    GAUGE
}
