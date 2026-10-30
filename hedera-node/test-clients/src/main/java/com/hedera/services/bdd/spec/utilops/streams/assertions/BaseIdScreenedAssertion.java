// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.TransactionID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A small starter class for assertions that match a record stream item by {@code transactionID}, and
 * then assert something about the body or record in the item.
 *
 * <p>This class does not restrict to id's with {@code nonce=0} or with {@code scheduled=false} (i.e. it
 * includes items for both the given transaction id and all its child items); but subclasses can do so
 * if desired by overriding * the {@link #filter(TransactionID)} method.
 */
public abstract class BaseIdScreenedAssertion implements RecordStreamAssertion {
    // Match VisibleItemsAssertion's bound: retain recent candidates without growing with the stream.
    private static final int MAX_PENDING_ITEMS = 500;

    private final Deque<RecordStreamItem> pendingItems = new ArrayDeque<>();
    private final String specTxnId;
    protected final HapiSpec spec;

    protected BaseIdScreenedAssertion(@NonNull final String specTxnId, @NonNull final HapiSpec spec) {
        this.specTxnId = specTxnId;
        this.spec = spec;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean isApplicableTo(@NonNull final RecordStreamItem item) {
        final var maybeTxnId = spec.registry().getMaybeTxnId(specTxnId);
        if (maybeTxnId.isEmpty()) {
            // Stream delivery can precede the registry publication performed after submitOp returns.
            if (filter(item.getRecord().getTransactionID())) {
                if (pendingItems.size() == MAX_PENDING_ITEMS) {
                    pendingItems.removeFirst();
                }
                pendingItems.addLast(item);
            }
            return false;
        }
        final var txnId = maybeTxnId.get();
        final var observedId = item.getRecord().getTransactionID();
        return baseFieldsMatch(txnId, observedId) && filter(observedId);
    }

    /**
     * Retries candidates delivered before the named transaction ID was registered. The eventual
     * assertion rechecks before waiting and at timeout, so recovery needs no further stream delivery.
     */
    @Override
    public synchronized boolean recheckPending() {
        final var maybeTxnId = spec.registry().getMaybeTxnId(specTxnId);
        if (maybeTxnId.isEmpty()) {
            return false;
        }
        boolean passed = false;
        while (!pendingItems.isEmpty()) {
            final var item = pendingItems.removeFirst();
            final var observedId = item.getRecord().getTransactionID();
            if (baseFieldsMatch(maybeTxnId.get(), observedId) && filter(observedId)) {
                // Evaluate every matching candidate, even if an earlier one passed.
                passed = test(item) || passed;
            }
        }
        return passed;
    }

    /**
     * Allows subclasses to further refine the filtering of record stream items by {@code TransactionID};
     * for example, a subclass might only be interested when the {@code TransactionID} has a particular
     * {@code nonce} value.
     *
     * @param txnId the {@code TransactionID} of the record stream item
     * @return whether if the item should be included in the assertion
     */
    protected boolean filter(@NonNull final TransactionID txnId) {
        return true;
    }

    /**
     * Determines if the transaction valid start and account id of the two {@code TransactionID}'s match.
     *
     * @param a the first {@code TransactionID}
     * @param b the second {@code TransactionID}
     * @return whether the base fields of the two {@code TransactionID}'s match
     */
    static boolean baseFieldsMatch(@NonNull final TransactionID a, @NonNull final TransactionID b) {
        return a.getTransactionValidStart().equals(b.getTransactionValidStart())
                && a.getAccountID().equals(b.getAccountID());
    }
}
