// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.extensions;

import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A utility that measures the fraction of time that is spent in one of two phases. For example, can be used to track
 * the overall busy time of a thread, or the busy time of a specific subtask. The granularity of this metric is in
 * microseconds.
 * <p>
 * This object must be measured at least once every 34 minutes or else it will overflow and return -1.
 * </p>
 */
public interface FractionalTimer {

    /**
     * Registers a {@link FunctionGauge} that tracks the fraction of time that this object has been active (out of
     * 1.0).
     *
     * @param metrics     the metrics instance to add the metric to
     * @param category    the kind of {@code Metric} (metrics are grouped or filtered by this)
     * @param name        a short name for the {@code Metric}
     * @param description a one-sentence description of the {@code Metric}
     */
    void registerMetric(
            @NonNull final Metrics metrics,
            @NonNull final String category,
            @NonNull final String name,
            @NonNull final String description);

    /**
     * Notifies the metric that we are entering an active period.
     *
     * @param now the current time in microseconds
     */
    void activate(final long now);

    /**
     * Notifies the metric that we are entering an active period.
     */
    void activate();

    /**
     * Notifies the metric that we are entering an inactive period.
     *
     * @param now the current time in microseconds
     */
    void deactivate(final long now);

    /**
     * Notifies the metric that we are entering an inactive period.
     */
    void deactivate();

    /**
     * @return the fraction of time that this object has been active, where 0.0 means not at all active, and 1.0 means
     * that this object has been 100% active.
     */
    double getActiveFraction();

    /**
     * Same as {@link #getActiveFraction()} but also resets the metric.
     *
     * @return the fraction of time that this object has been active, where 0.0 means this object is not at all active,
     * and 1.0 means that this object has been is 100% active.
     */
    double getAndReset();
}
