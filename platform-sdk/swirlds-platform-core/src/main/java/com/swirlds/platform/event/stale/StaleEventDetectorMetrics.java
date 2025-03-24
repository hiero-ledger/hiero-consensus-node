// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.stale;

import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * Collection of metrics related to stale events and transactions
 */
public class StaleEventDetectorMetrics {

    private static final LongAccumulator.Config STALE_EVENTS_CONFIG = new LongAccumulator.Config(
                    INTERNAL_CATEGORY, "staleEvents")
            .withAccumulator(Long::sum)
            .withDescription("number of stale self events");
    private final LongAccumulator staleEventCount;

    private static final LongAccumulator.Config STALE_APP_TRANSACTIONS_CONFIG = new LongAccumulator.Config(
                    INTERNAL_CATEGORY, "staleAppTransactions")
            .withAccumulator(Long::sum)
            .withDescription("number of application transactions in stale self events");
    private final LongAccumulator staleAppTransactionCount;

    /**
     * Constructor
     *
     * @param platformContext the platform context
     */
    public StaleEventDetectorMetrics(@NonNull final PlatformContext platformContext) {
        final Metrics metrics = platformContext.getMetrics();

        staleEventCount = metrics.getOrCreate(STALE_EVENTS_CONFIG);
        staleAppTransactionCount = metrics.getOrCreate(STALE_APP_TRANSACTIONS_CONFIG);
    }

    /**
     * Update metrics when a stale event has been detected
     *
     * @param event the stale event
     */
    public void reportStaleEvent(@NonNull final PlatformEvent event) {
        int appTransactions = 0;

        final Iterator<Transaction> iterator = event.transactionIterator();
        while (iterator.hasNext()) {
            iterator.next();
            appTransactions++;
        }

        staleEventCount.update(1);
        staleAppTransactionCount.update(appTransactions);
    }
}
