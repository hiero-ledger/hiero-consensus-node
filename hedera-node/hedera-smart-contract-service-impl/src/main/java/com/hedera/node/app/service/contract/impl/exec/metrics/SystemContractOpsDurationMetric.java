// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.utility.Pair;
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
    private final Map<Pair<String, String>, CountAccumulateAverageMetricTriplet> operationDurations = new HashMap<>();
    private final Metrics metrics;

    public SystemContractOpsDurationMetric(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
    }

    /**
     * Records the duration of a contract operation in nanoseconds
     *
     * @param systemContractName the system contract name
     * @param systemContractAddress the system contract address
     * @param durationNanos the duration in nanoseconds
     */
    public void recordOperationDuration(
            @NonNull final String systemContractName,
            @NonNull final String systemContractAddress,
            final long durationNanos) {
        getOrCreateMetric(systemContractName, systemContractAddress).recordObservation(durationNanos);
    }

    private CountAccumulateAverageMetricTriplet getOrCreateMetric(
            @NonNull final String systemContractName, @NonNull final String systemContractAddress) {
        return operationDurations.computeIfAbsent(
                Pair.of(systemContractName, systemContractAddress),
                unused -> CountAccumulateAverageMetricTriplet.create(
                        metrics,
                        ContractMetrics.METRIC_CATEGORY,
                        String.format(
                                "%s:OpsDuration_BySystemContract_%s_%s",
                                ContractMetrics.METRIC_SERVICE, systemContractName, systemContractAddress),
                        "Ops duration of system contract " + systemContractName + " with address "
                                + systemContractAddress + " in nanoseconds"));
    }

    public CountAccumulateAverageMetricTriplet getSystemContractOpsDuration(
            @NonNull final String systemContractName, @NonNull final String systemContractAddress) {
        return getOrCreateMetric(systemContractName, systemContractAddress);
    }
}
