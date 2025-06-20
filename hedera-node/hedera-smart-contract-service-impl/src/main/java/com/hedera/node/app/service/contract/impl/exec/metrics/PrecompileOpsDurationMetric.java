// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_6;
import static java.util.Objects.requireNonNull;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Singleton;

/**
 * Metrics collection for precompile operations duration tracking.
 * Tracks average duration of precompile operations in nanoseconds,
 * total duration and count of operations.
 */
@Singleton
public class PrecompileOpsDurationMetric {
    private static final String PRECOMPILE_CATEGORY = "precompile";
    private static final String OPERATION_DURATION_SUFFIX = "_duration_ns";

    private final Map<String, OpsDurationMetricTriple> precompileOpsDurations = new HashMap<>();
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
        final var metric = precompileOpsDurations.computeIfAbsent(precompileName, this::createPrecompileDurationMetric);
        metric.update(durationNanos);
    }

    private OpsDurationMetricTriple createPrecompileDurationMetric(final String precompileName) {
        final var averageConfig = new RunningAverageMetric.Config(
                        PRECOMPILE_CATEGORY, "precompile_" + precompileName + OPERATION_DURATION_SUFFIX)
                .withDescription("Average duration of precompile " + precompileName + " in nanoseconds")
                .withFormat(FORMAT_10_6);
        final var counterConfig = new Counter.Config(PRECOMPILE_CATEGORY, "precompile_" + precompileName + "_count");
        final var accumulatorConfig = new LongAccumulator.Config(
                        PRECOMPILE_CATEGORY, "precompile_" + precompileName + "_total_ns")
                .withDescription("Total duration of precompile " + precompileName + " in nanoseconds")
                .withAccumulator(Long::sum);
        return new OpsDurationMetricTriple(
                metrics.getOrCreate(averageConfig),
                metrics.getOrCreate(counterConfig),
                metrics.getOrCreate(accumulatorConfig));
    }

    /**
     * Gets the current average duration for a specific precompile
     *
     * @param precompileName the precompile name to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAveragePrecompileDuration(final String precompileName) {
        final var metric = precompileOpsDurations.get(precompileName);
        return metric != null ? metric.averageMetric().get() : 0.0;
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
