// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_6;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Metrics collection for precompile operations duration tracking.
 * Tracks average duration of precompile operations in nanoseconds.
 */
@Singleton
public class PrecompileMetrics {
    private static final String PRECOMPILE_CATEGORY = "precompile";
    private static final String OPERATION_DURATION_SUFFIX = "_duration_ns";

    private final Map<String, RunningAverageMetric> precompileDurations = new HashMap<>();
    private final Metrics metrics;

    @Inject
    public PrecompileMetrics(@NonNull final Metrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Records the duration of a precompile operation in nanoseconds
     *
     * @param precompileName the name of the precompile
     * @param durationNanos the duration in nanoseconds
     */
    public void recordPrecompileDuration(final String precompileName, final long durationNanos) {
        final var metric = precompileDurations.computeIfAbsent(precompileName, this::createPrecompileDurationMetric);
        metric.update(durationNanos);
    }

    private RunningAverageMetric createPrecompileDurationMetric(final String precompileName) {
        final var config = new RunningAverageMetric.Config(
                        PRECOMPILE_CATEGORY, "precompile_" + precompileName + OPERATION_DURATION_SUFFIX)
                .withDescription("Average duration of precompile " + precompileName + " in nanoseconds")
                .withFormat(FORMAT_10_6);
        return metrics.getOrCreate(config);
    }

    /**
     * Gets the current average duration for a specific precompile
     *
     * @param precompileName the precompile name to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAveragePrecompileDuration(final String precompileName) {
        final var metric = precompileDurations.get(precompileName);
        return metric != null ? metric.get() : 0.0;
    }
}
