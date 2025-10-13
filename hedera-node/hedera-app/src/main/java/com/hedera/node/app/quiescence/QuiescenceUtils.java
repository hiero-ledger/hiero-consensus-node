// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * Utility methods for the quiescence feature.
 */
public final class QuiescenceUtils {
    private QuiescenceUtils() {
        // Not to be instantiated
    }

    /**
     * Checks if the supplied transaction is relevant for quiescence purposes. A transaction is considered relevant if
     * we need to reach consensus on it. Signature transactions don't need to reach consensus, they are processed
     * pre-consensus.
     *
     * @param body the transaction to check
     * @return true if the transaction is relevant, false otherwise
     */
    public static boolean isRelevantTransaction(@NonNull final TransactionBody body) {
        return !body.hasStateSignatureTransaction() && !body.hasHintsPartialSignature();
    }

    /**
     * Same as {@link #isRelevantTransaction(TransactionBody)} but extracts the body from the transaction metadata.
     * <p>
     * NOTE: This method assumes that the metadata has been set on the transaction
     *
     * @param txn the transaction to check
     * @return true if the transaction is relevant, false otherwise
     * @throws BadMetadataException if it is not possible to read the metadata from a transaction
     */
    public static boolean isRelevantTransaction(@NonNull final Transaction txn) throws BadMetadataException {
        if (!(txn.getMetadata() instanceof final PreHandleResult preHandleResult)) {
            throw new BadMetadataException(txn);
        }
        final TransactionInfo txInfo = preHandleResult.txInfo();
        if (txInfo == null) {
            // This is most likely an unparsable transaction.
            // An unparsable transaction is considered relevant because it needs to reach consensus so that the node
            // that submitted it can be charged for it.
            return true;
        }
        return isRelevantTransaction(txInfo.txBody());
    }

    /**
     * Counts the number of relevant transactions in the supplied iterator. Relevant transactions are explained in
     * {@link #isRelevantTransaction(TransactionBody)}.
     * <p>
     * NOTE: This method assumes that the metadata has been set on the transactions
     *
     * @param transactions the transactions to check
     * @return the number of relevant transactions
     * @throws BadMetadataException if it is not possible to read the metadata from a transaction
     */
    public static long countRelevantTransactions(@NonNull final Iterator<Transaction> transactions)
            throws BadMetadataException {
        long count = 0;
        while (transactions.hasNext()) {
            if (isRelevantTransaction(transactions.next())) {
                count++;
            }
        }
        return count;
    }
}
