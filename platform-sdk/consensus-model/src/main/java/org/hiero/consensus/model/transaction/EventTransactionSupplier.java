// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.transaction;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Provides transactions for new events being created.
 */
@FunctionalInterface
public interface EventTransactionSupplier {

    /**
     * Returns a list of timestamped transactions that will be part of a newly created event.
     * Each transaction includes the time when it was received by the transaction pool.
     * May return an empty list.
     *
     * @return a list with 0 or more timestamped transactions
     */
    @NonNull
    List<TimestampedTransaction> getTimestampedTransactionsForEvent();

    /**
     * Returns a list of transactions that will be part of a newly created event. May return an empty list.
     *
     * @deprecated Use {@link #getTimestampedTransactionsForEvent()} instead to get transaction timestamps.
     * This method is provided for backward compatibility and extracts only the transaction data.
     *
     * @return a list with 0 or more transactions
     */
    @Deprecated
    @NonNull
    default List<Bytes> getTransactionsForEvent() {
        return getTimestampedTransactionsForEvent().stream()
                .map(TimestampedTransaction::transaction)
                .toList();
    }
}
