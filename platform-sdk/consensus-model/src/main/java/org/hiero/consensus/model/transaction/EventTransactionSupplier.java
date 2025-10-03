// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.transaction;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Provides transactions for new events being created.
 */
@FunctionalInterface
public interface EventTransactionSupplier {

    /**
     * Returns a list of timestamped transactions that will be part of a newly created event.
     * Each transaction includes the time when it was received.
     * May return an empty list.
     *
     * @return a list with 0 or more timestamped transactions
     */
    @NonNull
    List<TimestampedTransaction> getTransactionsForEvent();
}
