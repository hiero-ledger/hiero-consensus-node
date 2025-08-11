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
     * Gets whether the cache contains the given transaction ID.
     *
     * @param transactionID The transaction ID to add to the cache.
     * @return {@code true} if the transaction ID is in the cache
     */
    boolean contains(@NonNull TransactionID transactionID);

    /**
     * Marks the given TransactionID as having been observed in a stale event. This information is kept
     * in-memory only and ages out with the same policy as {@link #add(TransactionID)}.
     * @param transactionID the transaction ID to mark as stale
     */
    void markStale(@NonNull TransactionID transactionID);

    /**
     * Returns true if the given {@link TransactionID} has been observed in a stale event and has not yet aged out.
     * @param transactionID the transaction ID to check
     * @return true if the transaction ID has been marked as stale
     */
    boolean isStale(@NonNull TransactionID transactionID);

    /**
     * Clears the stale state for the given {@link TransactionID}.
     * @param transactionID the transaction ID to clear from the stale state
     * @return true if the stale state was cleared
     */
    boolean clearStale(@NonNull TransactionID transactionID);

    /** Clear everything from the cache. Used during reconnect */
    void clear();
}
