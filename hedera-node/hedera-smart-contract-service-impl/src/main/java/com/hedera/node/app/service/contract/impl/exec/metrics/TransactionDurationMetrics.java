// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.metrics;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Metrics collection for transaction duration tracking.
 * Tracks the duration of transactions in nanoseconds.
 */
@Singleton
public class TransactionDurationMetrics {
    private static final String TRANSACTION_DURATION_CATEGORY = "transaction";
    private static final String TRANSACTION_DURATION_SUFFIX = "_duration_ns";

    private final Metrics metrics;
    private final Map<TransactionMetricKey, LongGauge> txnDuration = new HashMap<>();

    record TransactionMetricKey(AccountID sender, long nonce) {}

    @Inject
    public TransactionDurationMetrics(@NonNull final Metrics metrics) {
        this.metrics = requireNonNull(metrics);
    }

    /**
     * Records the duration of a transaction in nanoseconds
     *
     * @param transaction the transaction that was executed
     * @param durationNanos the duration in nanoseconds
     */
    public void recordTransactionDuration(@NonNull final HederaEvmTransaction transaction, final long durationNanos) {
        final var key = new TransactionMetricKey(transaction.senderId(), transaction.nonce());
        final var metric = txnDuration.computeIfAbsent(key, this::createTransactionDurationMetric);
        metric.set(durationNanos);
    }

    private LongGauge createTransactionDurationMetric(@NonNull final TransactionMetricKey transactionKey) {
        final var config = new LongGauge.Config(
                        TRANSACTION_DURATION_CATEGORY,
                        transactionKey.sender.toString() + " " + transactionKey.nonce + TRANSACTION_DURATION_SUFFIX)
                .withDescription("Duration of " + transactionKey.sender.toString() + " " + transactionKey.nonce
                        + " transaction in nanoseconds");
        return metrics.getOrCreate(config);
    }

    /**
     * Gets the current duration for a specific transaction
     *
     * @param transaction the transaction to get duration for
     * @return the duration in nanoseconds
     */
    public long getDuration(@NonNull final HederaEvmTransaction transaction) {
        final var key = new TransactionMetricKey(transaction.senderId(), transaction.nonce());
        final var metric = txnDuration.get(key);
        return metric != null ? metric.get() : 0;
    }
}
