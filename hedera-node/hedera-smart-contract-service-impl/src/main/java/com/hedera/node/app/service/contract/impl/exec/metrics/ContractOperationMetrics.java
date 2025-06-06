// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_6;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Metrics collection for smart contract operations duration tracking.
 * Tracks average duration of operations in nanoseconds.
 */
@Singleton
public class ContractOperationMetrics {
    private static final String CONTRACT_CATEGORY = "system_contract";
    private static final String OPERATION_DURATION_SUFFIX = "_duration_ns";

    private final Map<SystemContractMethod, RunningAverageMetric> operationDurations = new HashMap<>();
    private final Metrics metrics;

    @Inject
    public ContractOperationMetrics(@NonNull final Metrics metrics) {
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

    private RunningAverageMetric createOperationDurationMetric(@NonNull final SystemContractMethod method) {
        final var config = new RunningAverageMetric.Config(
                        CONTRACT_CATEGORY, method.methodName() + OPERATION_DURATION_SUFFIX)
                .withDescription("Average duration of " + method.methodName() + " operation in nanoseconds")
                .withFormat(FORMAT_10_6);
        return metrics.getOrCreate(config);
    }

    /**
     * Gets the current average duration for a specific operation
     *
     * @param method the system contract method to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAverageSystemContractDuration(@NonNull final SystemContractMethod method) {
        final var metric = operationDurations.get(method);
        return metric != null ? metric.get() : 0.0;
    }
}
