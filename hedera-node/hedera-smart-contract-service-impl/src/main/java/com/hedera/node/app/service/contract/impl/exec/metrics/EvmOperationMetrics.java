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
 * Metrics collection for EVM operations duration tracking.
 * Tracks average duration of EVM operations in nanoseconds.
 */
@Singleton
public class EvmOperationMetrics {
    private static final String EVM_CATEGORY = "ops";
    private static final String OPERATION_DURATION_SUFFIX = "_duration_ns";
    private final Map<Integer, RunningAverageMetric> operationDurations = new HashMap<>();
    private final Metrics metrics;

    @Inject
    public EvmOperationMetrics(@NonNull final Metrics metrics) {
        this.metrics = metrics;
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

    private RunningAverageMetric createOperationDurationMetric(final int opcode) {
        final var config = new RunningAverageMetric.Config(EVM_CATEGORY, "op_" + opcode + OPERATION_DURATION_SUFFIX)
                .withDescription("Average duration of EVM operation " + opcode + " in nanoseconds")
                .withFormat(FORMAT_10_6);
        return metrics.getOrCreate(config);
    }

    /**
     * Gets the current average duration for a specific EVM operation
     *
     * @param opcode the opcode to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAverageOperationDuration(final int opcode) {
        final var metric = operationDurations.get(opcode);
        return metric != null ? metric.get() : 0.0;
    }
}
