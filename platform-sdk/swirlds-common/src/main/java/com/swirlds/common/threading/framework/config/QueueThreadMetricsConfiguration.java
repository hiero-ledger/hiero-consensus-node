// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.config;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Configuration for the metrics that will be applied to a queue thread
 */
public class QueueThreadMetricsConfiguration {
    /** The metrics system that will hold metrics */
    private final Metrics metrics;
    /** The category to use for metrics */
    private String category = Metrics.INTERNAL_CATEGORY;
    /** The time object to use for metrics */
    private Time time = Time.getCurrent();
    /** If enabled, the max size metric will be applied to the queue. */
    private boolean maxSizeMetricEnabled;
    /** If enabled, the min size metric will be applied to the queue.*/
    private boolean minSizeMetricEnabled;
    /** If true, this thread will add a busy time metric */
    private boolean busyTimeMetricEnabled;

    /**
     * Create a new configuration object
     *
     * @param metrics
     * 		The metrics system that will hold metrics
     */
    public QueueThreadMetricsConfiguration(@NonNull final Metrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /**
     * Set the category to use for metrics
     *
     * @param category
     * 		The category to use for metrics
     * @return this object
     */
    public @NonNull QueueThreadMetricsConfiguration setCategory(@NonNull final String category) {
        this.category = category;
        return this;
    }

    /**
     * Set the time object to use for metrics
     *
     * @param time
     * 		The time object to use for metrics
     * @return this object
     */
    public @NonNull QueueThreadMetricsConfiguration setTime(@NonNull final Time time) {
        this.time = time;
        return this;
    }

    /**
     * Enables the metric that tracks the maximum queue size
     *
     * @return this object
     */
    public @NonNull QueueThreadMetricsConfiguration enableMaxSizeMetric() {
        this.maxSizeMetricEnabled = true;
        return this;
    }

    /**
     * Enables the metric that tracks the minimum queue size
     *
     * @return this object
     */
    public @NonNull QueueThreadMetricsConfiguration enableMinSizeMetric() {
        this.minSizeMetricEnabled = true;
        return this;
    }

    /**
     * Enables the metric that tracks the busy time of the queue thread
     * @return this object
     */
    public @NonNull QueueThreadMetricsConfiguration enableBusyTimeMetric() {
        this.busyTimeMetricEnabled = true;
        return this;
    }

    /**
     * @return The metrics system that will hold metrics
     */
    public @NonNull Metrics getMetrics() {
        return metrics;
    }

    /**
     * @return The category to use for metrics
     */
    public @NonNull String getCategory() {
        return category;
    }

    /**
     * @return The time object to use for metrics
     */
    public @NonNull Time getTime() {
        return time;
    }

    /**
     * @return is the max size metric enabled
     */
    public boolean isMaxSizeMetricEnabled() {
        return maxSizeMetricEnabled;
    }

    /**
     * @return is the min size metric enabled
     */
    public boolean isMinSizeMetricEnabled() {
        return minSizeMetricEnabled;
    }

    /**
     * @return is the busy time metric enabled
     */
    public boolean isBusyTimeMetricEnabled() {
        return busyTimeMetricEnabled;
    }
}
