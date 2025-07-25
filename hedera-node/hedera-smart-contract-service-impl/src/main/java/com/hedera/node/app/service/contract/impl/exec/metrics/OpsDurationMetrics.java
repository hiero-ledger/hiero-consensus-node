// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

public class OpsDurationMetrics {
    private final SystemContractOpsDurationMetric systemContractOpsDurationMetric;
    private final OpCodeOpsDurationMetric opCodeOpsDurationMetric;
    private final PrecompileOpsDurationMetric precompileOpsDurationMetric;
    private final OpsDurationPerTransactionMetrics opsDurationPerTransactionMetrics;
    private final TransactionThrottledByOpsDurationMetric transactionThrottledByOpsDurationMetric;

    public OpsDurationMetrics(@NonNull final Metrics metrics) {
        requireNonNull(metrics, "Metrics cannot be null");
        this.systemContractOpsDurationMetric = new SystemContractOpsDurationMetric(metrics);
        this.opCodeOpsDurationMetric = new OpCodeOpsDurationMetric(metrics);
        this.precompileOpsDurationMetric = new PrecompileOpsDurationMetric(metrics);
        this.opsDurationPerTransactionMetrics = new OpsDurationPerTransactionMetrics(metrics);
        this.transactionThrottledByOpsDurationMetric = new TransactionThrottledByOpsDurationMetric(metrics);
    }

    /**
     * Records the duration of a system contract operation in nanoseconds
     *
     * @param method the system contract method that was executed
     * @param durationNanos the duration in nanoseconds
     */
    public void recordSystemContractOpsDuration(@NonNull final SystemContractMethod method, final long durationNanos) {
        systemContractOpsDurationMetric.recordOperationDuration(method, durationNanos);
    }

    /**
     * Gets the current average duration for a specific system contract operation
     *
     * @param method the system contract method to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAverageSystemContractOpsDuration(@NonNull final SystemContractMethod method) {
        return systemContractOpsDurationMetric.getAverageSystemContractOpsDuration(method);
    }

    /**
     * Gets the current count for a specific system contract operation
     *
     * @param method the system contract method to get count for
     * @return the count of operations executed
     */
    public long getSystemContractOpsDurationCount(@NonNull final SystemContractMethod method) {
        return systemContractOpsDurationMetric.getSystemContractOpsDurationCount(method);
    }

    /**
     * Gets the total duration for a specific system contract operation
     *
     * @param method the system contract method to get total duration for
     * @return the total duration in nanoseconds
     */
    public long getTotalSystemContractOpsDuration(@NonNull final SystemContractMethod method) {
        return systemContractOpsDurationMetric.getSystemContractOpsTotalDuration(method);
    }

    /**
     * Records the duration of an EVM operation in nanoseconds
     *
     * @param opCode the EVM op code that was executed
     * @param durationNanos the duration in nanoseconds
     */
    public void recordOpCodeOpsDuration(final int opCode, final long durationNanos) {
        opCodeOpsDurationMetric.recordOpCodeOpsDurationMetric(opCode, durationNanos);
    }

    /**
     * Gets the current average duration for a specific op code
     *
     * @param opCode the EVM op code to get duration for
     * @return the average duration in nanoseconds
     */
    public double getAverageOpCodeOpsDuration(final int opCode) {
        return opCodeOpsDurationMetric.getAverageOpCodeOpsDuration(opCode);
    }

    /**
     * Gets the total count of operations for a specific EVM op code
     *
     * @param opCode the EVM op code to get count for
     * @return the count of operations executed
     */
    public long getOpCodeOpsDurationCount(final int opCode) {
        return opCodeOpsDurationMetric.getOpCodeOpsDurationCount(opCode);
    }

    /**
     * Gets the total duration for a specific EVM op code
     * @param opCode the EVM op code to get total duration for
     * @return the total duration in nanoseconds
     */
    public long getTotalOpCodeOpsDuration(final int opCode) {
        return opCodeOpsDurationMetric.getTotalOpCodeOpsDuration(opCode);
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
        opsDurationPerTransactionMetrics.recordTxnTotalOpsDuration(opsDurationNanos);
    }

    /**
     * Get the average duration of operations per transaction
     *
     * @return the average duration in nanoseconds
     */
    public double getAverageTransactionOpsDuration() {
        return opsDurationPerTransactionMetrics.getAverage();
    }

    /**
     * Get the total count of transactions recorded
     *
     * @return the count of transactions
     */
    public long getTransactionOpsDurationCount() {
        return opsDurationPerTransactionMetrics.getCount();
    }

    /**
     * Get the total duration of operations across all transactions
     *
     * @return the total duration in nanoseconds
     */
    public long getTotalTransactionOpsDuration() {
        return opsDurationPerTransactionMetrics.getTotalOpsDuration();
    }

    /**
     * Records the count of transactions that were throttled due to exceeding the maximum allowed
     */
    public void recordTransactionsThrottledByOpsDuration() {
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
