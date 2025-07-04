// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.pool;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;

/**
 * Encapsulates metrics for the transaction pool.
 */
public class TransactionPoolMetrics {

    private static final SpeedometerMetric.Config ACCEPTED_APP_TRANSACTIONS_CONFIG = new SpeedometerMetric.Config(
                    PLATFORM_CATEGORY, "acceptedAppTransactions")
            .withDescription("Cycled when an app transaction is submitted to the transaction pool and accepted.");
    private final SpeedometerMetric acceptedAppTransactions;

    private static final SpeedometerMetric.Config REJECTED_APP_TRANSACTIONS_CONFIG = new SpeedometerMetric.Config(
                    PLATFORM_CATEGORY, "rejectedAppTransactions")
            .withDescription("Cycled when an app transaction is submitted to the transaction pool and not accepted.");
    private final SpeedometerMetric rejectedAppTransactions;

    private static final SpeedometerMetric.Config SUBMITTED_PLATFORM_TRANSACTIONS_CONFIG = new SpeedometerMetric.Config(
                    PLATFORM_CATEGORY, "submittedPlatformTransactions")
            .withDescription(
                    "Cycled when a platform transaction is submitted (platform transactions are always accepted).");
    private final SpeedometerMetric submittedPlatformTransactions;

    /**
     * Create metrics for the transaction pool.
     *
     * @param metrics                             the metrics instance to use
     * @param getBufferedTransactionCount         a supplier for the number of buffered transactions
     * @param getPriorityBufferedTransactionCount a supplier for the number of priority buffered transactions
     */
    public TransactionPoolMetrics(
            @NonNull final Metrics metrics,
            @NonNull final Supplier<Integer> getBufferedTransactionCount,
            @NonNull final Supplier<Integer> getPriorityBufferedTransactionCount) {

        acceptedAppTransactions = metrics.getOrCreate(ACCEPTED_APP_TRANSACTIONS_CONFIG);
        rejectedAppTransactions = metrics.getOrCreate(REJECTED_APP_TRANSACTIONS_CONFIG);
        submittedPlatformTransactions = metrics.getOrCreate(SUBMITTED_PLATFORM_TRANSACTIONS_CONFIG);

        metrics.getOrCreate(new FunctionGauge.Config<>(
                        PLATFORM_CATEGORY, "bufferedTransactions", Integer.class, getBufferedTransactionCount)
                .withDescription("The number of non-priority transactions waiting to be inserted into an event.")
                .withUnit("count"));
        metrics.getOrCreate(new FunctionGauge.Config<>(
                        PLATFORM_CATEGORY,
                        "bufferedPriorityTransactions",
                        Integer.class,
                        getPriorityBufferedTransactionCount)
                .withDescription("The number of priority transactions waiting to be inserted into an event.")
                .withUnit("count"));
    }

    /**
     * Record that an app transaction was accepted.
     */
    public void recordAcceptedAppTransaction() {
        acceptedAppTransactions.cycle();
    }

    /**
     * Record that an app transaction was rejected.
     */
    public void recordRejectedAppTransaction() {
        rejectedAppTransactions.cycle();
    }

    /**
     * Record that a platform transaction was submitted.
     */
    public void recordSubmittedPlatformTransaction() {
        submittedPlatformTransactions.cycle();
    }
}
