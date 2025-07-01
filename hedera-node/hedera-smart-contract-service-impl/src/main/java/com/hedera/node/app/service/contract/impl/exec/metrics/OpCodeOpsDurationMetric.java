// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

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
    private final Map<Integer, CountAccumulateAverageMetricTriplet> opCodeOpsDuration = new HashMap<>();
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
    public void recordOpCodeOpsDurationMetric(final int opcode, final long durationNanos) {
        final var metric = opCodeOpsDuration.computeIfAbsent(
                opcode,
                unused -> CountAccumulateAverageMetricTriplet.create(
                        metrics,
                        EVM_CATEGORY,
                        Integer.toString(opcode),
                        "Ops duration of EVM opcode " + opcode + " in nanoseconds"));
        metric.recordObservation(durationNanos);
    }

    /**
     * Gets the current average duration for a specific EVM operation
     *
     * @param opcode the opcode to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAverageOpCodeOpsDuration(final int opcode) {
        final var metric = opCodeOpsDuration.get(opcode);
        return metric != null ? metric.average().get() : 0.0;
    }

    /**
     * Gets the total count of operations for a specific opcode.
     *
     * @param opcode the opcode to get count for
     * @return the count of operations
     */
    public long getOpCodeOpsDurationCount(final int opcode) {
        final var metric = opCodeOpsDuration.get(opcode);
        return metric != null ? metric.counter().get() : 0L;
    }

    /**
     * Gets the total duration of operations for a specific opcode.
     *
     * @param opcode the opcode to get total duration for
     * @return the total duration in nanoseconds
     */
    public long getTotalOpCodeOpsDuration(final int opcode) {
        final var metric = opCodeOpsDuration.get(opcode);
        return metric != null ? metric.accumulator().get() : 0L;
    }
}
