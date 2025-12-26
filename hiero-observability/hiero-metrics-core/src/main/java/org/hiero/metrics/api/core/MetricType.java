// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

/**
 * The type of metric that is compliant with OpenMetrics specification.
 */
public enum MetricType {
    /**
     * Unknown metric type - should not be used but exists for cases when no suitable metric type is available.
     */
    UNKNOWN,
    /**
     * A cumulative metric that represents a single monotonically increasing counter value.
     */
    COUNTER,
    /**
     * A metric that represents a single numerical value that can arbitrarily go up and down and set to any value.
     */
    GAUGE,
    /**
     * A metric that represents state set information, typically used to describe discrete states or conditions.
     * Values per key are either {@code true} or {@code false} that are mapped to 1 and 0 respectively.
     */
    STATE_SET
}
