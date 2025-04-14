// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.monitor;

import static java.util.Objects.isNull;
import static org.hiero.base.utility.CompareTo.isGreaterThan;
import static org.hiero.base.utility.CompareTo.isGreaterThanOrEqualTo;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder;
import com.swirlds.metrics.api.Metrics;
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
     * How long between two consecutive reports when the system is healthy.
     */
    public final Duration healthyReportThreshold;

    /**
     * Marks the time of the last transition to a healthy state,
     *  It is used to continue reporting a health sate even if there are no changes in status.
     *  It gets reset to the current time every {@code healthyReportThreshold},
     *  if the health monitor continues to register a healthy state.
     */
    private Instant lastHealthyTransitionTime = null;

    /**
     * Constructor.
     *
     * @param metrics the metrics
     * @param time  the time
     * @param schedulers the task schedulers to monitor
     * @param healthLogThreshold the amount of time that must pass before we start logging health information
     * @param healthLogPeriod the period at which we log health information
     * @param healthyReportThreshold How long between two consecutive reports when the system is healthy.
     */
    public HealthMonitor(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final List<TaskScheduler<?>> schedulers,
            @NonNull final Duration healthLogThreshold,
            @NonNull final Duration healthLogPeriod,
            @NonNull final Duration healthyReportThreshold) {

        this.metrics = new HealthMonitorMetrics(metrics, healthLogThreshold);
        this.schedulers = new ArrayList<>();
        this.healthyReportThreshold = healthyReportThreshold;
        for (final TaskScheduler<?> scheduler : schedulers) {
            if (scheduler.getCapacity() != TaskSchedulerBuilder.UNLIMITED_CAPACITY) {
                this.schedulers.add(Objects.requireNonNull(scheduler));
                lastHealthyTimes.add(null);
            }
        }

        logger = new HealthMonitorLogger(time, this.schedulers, healthLogThreshold, healthLogPeriod);
    }

    /**
     * Called periodically. Scans the task schedulers for health issues.
     * This method determines the maximum duration any single scheduler has been in an unhealthy state.
     * It reports this duration based on the following rules:
     * <ul>
     * <li>Reports the maximum unhealthy duration if it has changed since the last report.</li>
     * <li>Reports {@code null} if the maximum unhealthy duration has not changed since the last report,
     * except in the case where the duration is zero (all schedulers healthy).</li>
     * <li>Reports {@link Duration#ZERO} every {@code healthyReportThreshold} if all schedulers are healthy.</li>
     * </ul>
     * @param now the current time
     * @return The maximum duration any scheduler has been unhealthy, or {@code Duration.ZERO} if all
     * schedulers are healthy and the {@code healthyReportThreshold} interval has passed, or {@code null} if the
     * health status has not changed (and is not a healthy state that needs periodic reporting).
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

            if (!longestUnhealthyDuration.equals(previouslyReportedDuration)) {
                this.longestUnhealthyDuration.set(longestUnhealthyDuration);
                metrics.reportUnhealthyDuration(longestUnhealthyDuration);
                if (longestUnhealthyDuration.isZero()) {
                    lastHealthyTransitionTime = now; // marks the time of the last transitions to healthy
                } // when transitioning from healthy to unhealthy healthyTransitionTime remains out-of-date
                return longestUnhealthyDuration;
            } else {
                // if there is no change in health status,
                // report only if the system has been healthy for period
                if (longestUnhealthyDuration.isZero() // Everything's Ok Alarm
                        && (isNull(lastHealthyTransitionTime)
                                || isGreaterThanOrEqualTo(
                                        Duration.between(lastHealthyTransitionTime, now), healthyReportThreshold))) {
                    lastHealthyTransitionTime = now; // reset the healthyTransitionTime
                    return longestUnhealthyDuration;
                }
                return null;
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
