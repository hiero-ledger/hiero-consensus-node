// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.monitor;

import static com.swirlds.common.utility.CompareTo.isGreaterThan;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitors the health of a wiring model. A healthy wiring model is a model without too much work backed up in queues.
 * An unhealthy wiring model is a model with at least one queue that is backed up with too much work.
 */
public class HealthMonitor {

    /**
     * A list of task schedulers without unlimited capacities.
     */
    private final List<TaskScheduler<?>> schedulers;

    /**
     * Corresponds to the schedulers list. The instant at the index of a scheduler indicates the last timestamp at which
     * that scheduler was observed to be in a healthy state.
     */
    private final List<Instant> lastHealthyTimes = new ArrayList<>();

    /**
     * The previous value returned by {@link #checkSystemHealth(Instant)}. Used to avoid sending repeat output.
     */
    private Duration previouslyReportedDuration = Duration.ZERO;

    /**
     * Metrics for the health monitor.
     */
    private final HealthMonitorMetrics metrics;

    /**
     * Logs health issues.
     */
    private final HealthMonitorLogger logger;

    /**
     * The longest duration that any single scheduler has been concurrently unhealthy.
     */
    private final AtomicReference<Duration> longestUnhealthyDuration = new AtomicReference<>(Duration.ZERO);

    /**
     * Constructor.
     *
     * @param platformContext    the platform context
     * @param schedulers         the task schedulers to monitor
     * @param healthLogThreshold the amount of time that must pass before we start logging health information
     * @param healthLogPeriod    the period at which we log health information
     */
    public HealthMonitor(
            @NonNull final PlatformContext platformContext,
            @NonNull final List<TaskScheduler<?>> schedulers,
            @NonNull final Duration healthLogThreshold,
            @NonNull final Duration healthLogPeriod) {

        metrics = new HealthMonitorMetrics(platformContext, healthLogThreshold);

        this.schedulers = new ArrayList<>();
        for (final TaskScheduler<?> scheduler : schedulers) {
            if (scheduler.getCapacity() != TaskSchedulerBuilder.UNLIMITED_CAPACITY) {
                this.schedulers.add(Objects.requireNonNull(scheduler));
                lastHealthyTimes.add(null);
            }
        }

        logger = new HealthMonitorLogger(platformContext, this.schedulers, healthLogThreshold, healthLogPeriod);
    }

    /**
     * Called periodically. Scans the task schedulers for health issues.
     *
     * @param now the current time
     * @return the amount of time any single scheduler has been concurrently unhealthy. Returns {@link Duration#ZERO} if
     * all schedulers are healthy, returns null if there is no change in health status.
     */
    @Nullable
    public Duration checkSystemHealth(@NonNull final Instant now) {
        Duration longestUnhealthyDuration = Duration.ZERO;

        for (int i = 0; i < lastHealthyTimes.size(); i++) {
            final TaskScheduler<?> scheduler = schedulers.get(i);
            final boolean healthy = scheduler.getUnprocessedTaskCount() <= scheduler.getCapacity();
            if (healthy) {
                lastHealthyTimes.set(i, null);
            } else {
                if (lastHealthyTimes.get(i) == null) {
                    lastHealthyTimes.set(i, now);
                }

                final Duration unhealthyDuration = Duration.between(lastHealthyTimes.get(i), now);
                logger.reportUnhealthyScheduler(scheduler, unhealthyDuration);
                if (isGreaterThan(unhealthyDuration, longestUnhealthyDuration)) {
                    longestUnhealthyDuration = unhealthyDuration;
                }
            }
        }

        try {
            if (longestUnhealthyDuration.equals(previouslyReportedDuration)) {
                // Only report when there is a change in health status
                return null;
            } else {
                this.longestUnhealthyDuration.set(longestUnhealthyDuration);
                metrics.reportUnhealthyDuration(longestUnhealthyDuration);
                return longestUnhealthyDuration;
            }
        } finally {
            previouslyReportedDuration = longestUnhealthyDuration;
        }
    }

    /**
     * Get the duration that any particular scheduler has been concurrently unhealthy.
     *
     * @return the duration that any particular scheduler has been concurrently unhealthy, or {@link Duration#ZERO} if
     * no scheduler is currently unhealthy
     */
    @NonNull
    public Duration getUnhealthyDuration() {
        return longestUnhealthyDuration.get();
    }
}
