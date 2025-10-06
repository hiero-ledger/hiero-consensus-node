// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

public class OpsDurationMetrics {
    private final SystemContractOpsDurationMetric systemContractOpsDurationMetric;
    private final PrecompileOpsDurationMetric precompileOpsDurationMetric;
    private final TransactionThrottledByOpsDurationMetric transactionThrottledByOpsDurationMetric;
    private final CountAccumulateAverageMetricTriplet perTransactionOpsDuration;

    public OpsDurationMetrics(@NonNull final Metrics metrics) {
        requireNonNull(metrics, "Metrics cannot be null");
        this.systemContractOpsDurationMetric = new SystemContractOpsDurationMetric(metrics);
        this.precompileOpsDurationMetric = new PrecompileOpsDurationMetric(metrics);
        this.transactionThrottledByOpsDurationMetric = new TransactionThrottledByOpsDurationMetric(metrics);
        this.perTransactionOpsDuration = CountAccumulateAverageMetricTriplet.create(
                metrics,
                ContractMetrics.METRIC_CATEGORY,
                String.format("%s:OpsDuration_PerTxn", ContractMetrics.METRIC_SERVICE),
                "Ops duration of all transaction in nanoseconds");
    }

    /**
     * Records the duration of a system contract operation in nanoseconds
     *
     * @param systemContractName the system contract name
     * @param systemContractAddress the system contract address
     * @param durationNanos the duration in nanoseconds
     */
    public void recordSystemContractOpsDuration(
            @NonNull final String systemContractName,
            @NonNull final String systemContractAddress,
            final long durationNanos) {
        systemContractOpsDurationMetric.recordOperationDuration(
                systemContractName, systemContractAddress, durationNanos);
    }

    /**
     * Records the duration of an EVM precompile in nanoseconds
     *
     * @param precompile the precompile op that was executed
     * @param durationNanos the duration in nanoseconds
     */
    public void recordPrecompileOpsDuration(@NonNull final String precompile, final long durationNanos) {
        precompileOpsDurationMetric.recordPrecompileDuration(precompile, durationNanos);
    }

    /**
     * Gets the current average duration for a specific precompile
     *
     * @param precompile the precompile op to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAveragePrecompileOpsDuration(@NonNull final String precompile) {
        return precompileOpsDurationMetric.getAveragePrecompileDuration(precompile);
    }

    /**
     * Gets the total count of operations for a specific precompile
     *
     * @param precompile the precompile op to get count for
     * @return the count of operations executed
     */
    public long getPrecompileOpsDurationCount(@NonNull final String precompile) {
        return precompileOpsDurationMetric.getPrecompileOpsDurationCount(precompile);
    }

    /**
     * Gets the total duration for a specific precompile
     *
     * @param precompile the precompile op to get total duration for
     * @return the total duration in nanoseconds
     */
    public long getTotalPrecompileOpsDuration(@NonNull final String precompile) {
        return precompileOpsDurationMetric.getPrecompileOpsTotalDuration(precompile);
    }

    /**
     * Records the duration of a transaction in nanoseconds
     * @param opsDurationNanos the duration in nanoseconds
     */
    public void recordTxnTotalOpsDuration(final long opsDurationNanos) {
        perTransactionOpsDuration.recordObservation(opsDurationNanos);
    }

    /**
     * Get the average duration of operations per transaction
     *
     * @return the average duration in nanoseconds
     */
    public double getAverageTransactionOpsDuration() {
        return perTransactionOpsDuration.average().get();
    }

    /**
     * Get the total count of transactions recorded
     *
     * @return the count of transactions
     */
    public long getTransactionOpsDurationCount() {
        return perTransactionOpsDuration.counter().get();
    }

    /**
     * Get the total duration of operations across all transactions
     *
     * @return the total duration in nanoseconds
     */
    public long getTotalTransactionOpsDuration() {
        return perTransactionOpsDuration.accumulator().get();
    }

    /**
     * Records a transaction that was throttled due to exceeding the ops duration throttle.
     */
    public void recordTransactionThrottledByOpsDuration() {
        transactionThrottledByOpsDurationMetric.increment();
    }

    /**
     * Gets the count of transactions that were throttled due to exceeding the maximum allowed
     *
     * @return the count of throttled transactions
     */
    public long getTransactionsThrottledByOpsDurationCount() {
        return transactionThrottledByOpsDurationMetric.getCount();
    }
}
