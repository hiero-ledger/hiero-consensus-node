// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.hedera.hapi.node.base.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A cache for de-duplicating transactions. This cache is <strong>NOT</strong> stored in state. It contains only the
 * last N minutes of transactions (where N is <pre>maxTxnDuration</pre>) that have been seen <strong>by this node
 * </strong>. This is used to prevent the {@link com.hedera.node.app.workflows.ingest.SubmissionManager} from submitting
 * duplicate transactions to the platform, and for reporting the right status for receipts to clients who query for
 * results before the transaction has been handled.
 */
/*@ThreadSafe*/
public interface DeduplicationCache {
    /**
     * Add a TransactionID to the cache. If the {@link TransactionID#transactionValidStart()} is older than
     * {@code maxTxnDuration} from now, then the transaction will not be added. Otherwise, it is added to the
     * cache. If the transaction is in the future, it will be added to the cache. It is the responsibility of
     * the {@link com.hedera.node.app.workflows.TransactionChecker} to ensure that the transaction is not
     * submitted to the platform until it is valid.
     *
     * @param transactionID The transaction ID to add to the cache.
     */
    void add(@NonNull TransactionID transactionID);

    /**
     * Atomically records {@code transactionID} if it is still within the valid-start window and not
     * already present.
     *
     * @param transactionID The transaction ID to add
     * @return {@code true} if the ID was newly added
     */
    default boolean putIfAbsent(@NonNull TransactionID transactionID) {
        if (contains(transactionID)) {
            return false;
        }
        add(transactionID);
        return true;
    }

    /**
     * Removes a transaction ID previously added. Used when platform submit rejects after a successful claim.
     *
     * @param transactionID The transaction ID to remove
     */
    default void remove(@NonNull TransactionID transactionID) {
        // Optional; implementations that claim IDs before platform submit should override.
    }

    /**
     * Gets whether the cache contains the given transaction ID.
     *
     * @param transactionID The transaction ID to look up
     * @return {@code true} if the transaction ID is in the cache
     */
    boolean contains(@NonNull TransactionID transactionID);

    /** Clear everything from the cache. Used during reconnect */
    void clear();
}
