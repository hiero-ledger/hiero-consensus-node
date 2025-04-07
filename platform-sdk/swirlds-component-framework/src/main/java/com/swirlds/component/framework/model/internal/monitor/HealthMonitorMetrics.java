// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.monitor;

import static org.hiero.base.utility.CompareTo.isLessThan;

import com.swirlds.common.metrics.DurationGauge;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Encapsulates metrics for the wiring health monitor.
 */
public class HealthMonitorMetrics {

    private static final DurationGauge.Config DURATION_GAUGE_CONFIG = new DurationGauge.Config(
                    "platform", "unhealthyDuration", ChronoUnit.SECONDS)
            .withDescription("The duration that the most unhealthy scheduler has been in an unhealthy state.");
    private final DurationGauge unhealthyDuration;

    private static final IntegerGauge.Config HEALTHY_CONFIG = new IntegerGauge.Config("platform", "healthy")
            .withDescription("1 if the platform is healthy, 0 otherwise. "
                    + "Triggers once unhealthyDuration metric crosses configured threshold.");
    private final IntegerGauge healthy;

    private final Duration healthThreshold;

    /**
     * Constructor.
     *
     * @param metrics   the metrics
     * @param healthLogThreshold the duration after which the system is considered unhealthy
     */
    public HealthMonitorMetrics(@NonNull final Metrics metrics, @NonNull final Duration healthLogThreshold) {
        unhealthyDuration = metrics.getOrCreate(DURATION_GAUGE_CONFIG);
        healthy = metrics.getOrCreate(HEALTHY_CONFIG);

        // Always initialize the system as healthy
        healthy.set(1);

        healthThreshold = healthLogThreshold;
    }

    /**
     * Set the unhealthy duration.
     *
     * @param duration the unhealthy duration
     */
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        unhealthyDuration.set(duration);
        healthy.set(isLessThan(duration, healthThreshold) ? 1 : 0);
    }
}
