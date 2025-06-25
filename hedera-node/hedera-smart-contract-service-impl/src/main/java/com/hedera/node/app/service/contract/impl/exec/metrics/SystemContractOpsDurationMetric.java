// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
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

    private final Map<SystemContractMethod, CountAccumulateAverageMetricTriplet> operationDurations = new HashMap<>();
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
        final var metric = operationDurations.computeIfAbsent(
                method,
                _method -> CountAccumulateAverageMetricTriplet.create(
                        metrics,
                        CONTRACT_CATEGORY,
                        method.methodName(),
                        "Ops duration of system contract method " + method.methodName() + " in nanoseconds"));
        metric.recordObservation(durationNanos);
    }

    /**
     * Gets the current average duration for a specific operation
     *
     * @param method the system contract method to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAverageSystemContractOpsDuration(@NonNull final SystemContractMethod method) {
        final var metric = operationDurations.get(method);
        return metric != null ? metric.average().get() : 0.0;
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
