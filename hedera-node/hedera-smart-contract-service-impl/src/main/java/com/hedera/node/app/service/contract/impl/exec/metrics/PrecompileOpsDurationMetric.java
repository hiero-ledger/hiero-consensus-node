// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;

/**
 * Metrics collection for precompile operations duration tracking.
 * Tracks average duration of precompile operations in nanoseconds,
 * total duration and count of operations.
 */
@Singleton
public class PrecompileOpsDurationMetric {
    private final Map<String, CountAccumulateAverageMetricTriplet> precompileOpsDurations = new ConcurrentHashMap<>();
    private final Metrics metrics;

    public PrecompileOpsDurationMetric(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
    }

    /**
     * Records the duration of a precompile operation in nanoseconds
     *
     * @param precompileName the name of the precompiled operation
     * @param durationNanos the duration in nanoseconds
     */
    public void recordPrecompileDuration(final String precompileName, final long durationNanos) {
        final var metric = precompileOpsDurations.computeIfAbsent(
                precompileName,
                unused -> CountAccumulateAverageMetricTriplet.create(
                        metrics,
                        ContractMetrics.METRIC_CATEGORY,
                        String.format("%s:OpsDuration_ByPrecompile_%s", ContractMetrics.METRIC_SERVICE, precompileName),
                        "Ops duration of precompile " + precompileName + " in nanoseconds"));
        metric.recordObservation(durationNanos);
    }

    /**
     * Gets the current average duration for a specific precompile
     *
     * @param precompileName the precompile name to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAveragePrecompileDuration(final String precompileName) {
        final var metric = precompileOpsDurations.get(precompileName);
        return metric != null ? metric.average().get() : 0.0;
    }

    public long getPrecompileOpsDurationCount(final String precompileName) {
        final var metric = precompileOpsDurations.get(precompileName);
        return metric != null ? metric.counter().get() : 0L;
    }

    public long getPrecompileOpsTotalDuration(final String precompileName) {
        final var metric = precompileOpsDurations.get(precompileName);
        return metric != null ? metric.accumulator().get() : 0L;
    }
}
