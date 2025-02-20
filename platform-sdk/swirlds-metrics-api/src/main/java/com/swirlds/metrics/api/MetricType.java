// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

/**
 * The types of {@code Metric}
 */
@Deprecated
public enum MetricType {

    /**
     * An accumulator is a metric that accumulates results according to the supplied operator.
     */
    ACCUMULATOR,

    /**
     * A counter is a metric that represents a single increasing counter whose value can only increase.
     */
    COUNTER,

    /**
     * A gauge is a metric that represents a single numerical value that can arbitrarily go up and down.
     */
    GAUGE,

    /**
     * A running average is a metric that calculates trends over short periods of time using a set of data.
     */
    RUNNING_AVERAGE,

    /**
     * A speedometer is a metric that represents how many times per unit of time an operation is performed.
     */
    SPEEDOMETER,

    /**
     * A stat entry is a flexible metric which behavior is defined by a provided operation.
     */
    STAT_ENTRY
}
