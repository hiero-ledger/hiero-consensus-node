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
 * Metrics collection for EVM operations duration tracking.
 * Tracks average duration of EVM op codes in nanoseconds,
 * total duration and count of operations.
 */
@Singleton
public class OpCodeOpsDurationMetric {
    private static final String EVM_CATEGORY = "ops";
    private static final String OPERATION_DURATION_SUFFIX = "_duration_ns";
    private final Map<Integer, OpsDurationMetricTriple> operationDurations = new HashMap<>();
    private final Metrics metrics;

    public OpCodeOpsDurationMetric(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
    }

    /**
     * Records the duration of an EVM operation in nanoseconds
     *
     * @param opcode the opcode of the operation
     * @param durationNanos the duration in nanoseconds
     */
    public void recordOperationDuration(final int opcode, final long durationNanos) {
        final var metric = operationDurations.computeIfAbsent(opcode, this::createOperationDurationMetric);
        metric.update(durationNanos);
    }

    private OpsDurationMetricTriple createOperationDurationMetric(final int opcode) {
        final var averageConfig = new RunningAverageMetric.Config(
                        EVM_CATEGORY, "op_" + opcode + OPERATION_DURATION_SUFFIX)
                .withDescription("Average duration of EVM operation " + opcode + " in nanoseconds")
                .withFormat(FORMAT_10_6);
        final var counterConfig = new Counter.Config(EVM_CATEGORY, "op_" + opcode + "_count")
                .withDescription("Count of EVM operation " + opcode);
        final var accumulatorConfig = new LongAccumulator.Config(EVM_CATEGORY, "op_" + opcode + "_total_ns")
                .withDescription("Total duration of EVM operation " + opcode + " in nanoseconds")
                .withAccumulator(Long::sum);
        return new OpsDurationMetricTriple(
                metrics.getOrCreate(averageConfig),
                metrics.getOrCreate(counterConfig),
                metrics.getOrCreate(accumulatorConfig));
    }

    /**
     * Gets the current average duration for a specific EVM operation
     *
     * @param opcode the opcode to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAverageOperationDuration(final int opcode) {
        final var metric = operationDurations.get(opcode);
        return metric != null ? metric.averageMetric().get() : 0.0;
    }

    /**
     * Gets the total count of operations for a specific opcode.
     *
     * @param opcode the opcode to get count for
     * @return the count of operations
     */
    public long getOperationCount(final int opcode) {
        final var metric = operationDurations.get(opcode);
        return metric != null ? metric.counter().get() : 0L;
    }

    /**
     * Gets the total duration of operations for a specific opcode.
     *
     * @param opcode the opcode to get total duration for
     * @return the total duration in nanoseconds
     */
    public long getTotalOperationDuration(final int opcode) {
        final var metric = operationDurations.get(opcode);
        return metric != null ? metric.accumulator().get() : 0L;
    }
}
