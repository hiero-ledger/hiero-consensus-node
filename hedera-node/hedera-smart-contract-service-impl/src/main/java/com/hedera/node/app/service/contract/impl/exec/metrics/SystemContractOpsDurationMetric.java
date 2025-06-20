// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_6;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Singleton;

/**
 * Metrics collection for smart contract operations duration tracking.
 * Tracks average duration of operations in nanoseconds, total duration and count of operations.
 */
@Singleton
public class SystemContractOpsDurationMetric {
    private static final String CONTRACT_CATEGORY = "system_contract";
    private static final String DURATION_SUFFIX = "_duration_ns";
    private static final String COUNT_SUFFIX = "_count";
    private static final String TOTAL_SUFFIX = "_total_ns";

    private final Map<SystemContractMethod, OpsDurationMetricTriple> operationDurations = new HashMap<>();
    private final Metrics metrics;

    public SystemContractOpsDurationMetric(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
    }

    /**
     * Records the duration of a contract operation in nanoseconds
     *
     * @param method the system contract method that was executed
     * @param durationNanos the duration in nanoseconds
     */
    public void recordOperationDuration(@NonNull final SystemContractMethod method, final long durationNanos) {
        final var metric = operationDurations.computeIfAbsent(method, this::createOperationDurationMetric);
        metric.update(durationNanos);
    }

    private OpsDurationMetricTriple createOperationDurationMetric(@NonNull final SystemContractMethod method) {
        final String methodName = method.methodName();

        final var averageConfig = new RunningAverageMetric.Config(CONTRACT_CATEGORY, methodName + DURATION_SUFFIX)
                .withDescription("Average duration of " + methodName + " operation in nanoseconds")
                .withFormat(FORMAT_10_6);

        final var counterConfig = new Counter.Config(CONTRACT_CATEGORY, methodName + COUNT_SUFFIX)
                .withDescription("Count of " + methodName + " operations");

        final var accumulatorConfig = new LongAccumulator.Config(CONTRACT_CATEGORY, methodName + TOTAL_SUFFIX)
                .withDescription("Total duration of " + methodName + " operation in nanoseconds")
                .withAccumulator(Long::sum);

        return new OpsDurationMetricTriple(
                metrics.getOrCreate(averageConfig),
                metrics.getOrCreate(counterConfig),
                metrics.getOrCreate(accumulatorConfig));
    }

    /**
     * Gets the current average duration for a specific operation
     *
     * @param method the system contract method to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAverageSystemContractOpsDuration(@NonNull final SystemContractMethod method) {
        final var metric = operationDurations.get(method);
        return metric != null ? metric.averageMetric().get() : 0.0;
    }

    /**
     * Gets the total count of operations for a specific system contract method.
     *
     * @param method the system contract method to get count for
     * @return the count of operations
     */
    public long getSystemContractOpsDurationCount(@NonNull final SystemContractMethod method) {
        final var metric = operationDurations.get(method);
        return metric != null ? metric.counter().get() : 0L;
    }

    /**
     * Gets the total duration for a specific system contract method.
     *
     * @param method the system contract method to get total duration for
     * @return the total duration in nanoseconds
     */
    public long getSystemContractOpsTotalDuration(@NonNull final SystemContractMethod method) {
        final var metric = operationDurations.get(method);
        return metric != null ? metric.accumulator().get() : 0L;
    }
}
