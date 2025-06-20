// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import com.swirlds.common.metrics.extensions.FractionalTimer;
import com.swirlds.common.metrics.extensions.StandardFractionalTimer;
import com.swirlds.common.threading.framework.config.QueueThreadMetricsConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A class that holds the metrics for a queue thread
 */
public class QueueThreadMetrics {
    /** Tracks how busy a thread is */
    private final FractionalTimer busyTime;

    /**
     * Constructs a new {@link QueueThreadMetrics} instance
     *
     * @param configuration
     * 		the configuration for the queue thread
     */
    public QueueThreadMetrics(@NonNull final AbstractQueueThreadConfiguration<?, ?> configuration) {
        final QueueThreadMetricsConfiguration metricsConfig = configuration.getMetricsConfiguration();
        if (metricsConfig == null || !metricsConfig.isBusyTimeMetricEnabled()) {
            this.busyTime = null;
            return;
        }
        this.busyTime = new StandardFractionalTimer(metricsConfig.getTime());
        busyTime.registerMetric(
                metricsConfig.getMetrics(),
                metricsConfig.getCategory(),
                buildBusyTimeMetricName(configuration.getThreadName()),
                "The busy time of the queue thread called " + configuration.getThreadName());
    }

    /**
     * Builds the name of the busy time metric
     *
     * @param threadName
     * 		the name of the thread
     * @return the name of the busy time metric
     */
    public static String buildBusyTimeMetricName(@NonNull final String threadName) {
        return "thread_busy_" + threadName;
    }

    /**
     * Notifies the metric that work has started
     */
    public void startingWork() {
        if (busyTime != null) {
            busyTime.activate();
        }
    }

    /**
     * Notifies the metric that work has finished
     */
    public void finishedWork() {
        if (busyTime != null) {
            busyTime.deactivate();
        }
    }
}
