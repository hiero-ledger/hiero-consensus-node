// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.records;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.spi.workflows.HandleContext.SavepointStack;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Consumer;

/**
 * A source of {@link TransactionRecord}s and {@link TransactionReceipt}s for one or more {@link TransactionID}'s.
 * <p>
 * <b>(FUTURE) Important:</b> It would be much simpler if this interface was scoped to a single {@link TransactionID},
 * but that adds overhead in the current system where a single {@link SavepointStack} commit flushes builders for
 * unrelated ids.
 * <p>
 * Once we refactor to use a separate {@link SavepointStack} for each id, we can simplify this interface.
 */
public interface RecordSource {
    /**
     * A receipt with its originating {@link TransactionID}.
     * @param txnId the transaction id
     * @param receipt the matching receipt
     */
    record IdentifiedReceipt(@NonNull TransactionID txnId, @NonNull TransactionReceipt receipt) {
        public IdentifiedReceipt {
            requireNonNull(txnId);
            requireNonNull(receipt);
        }
    }

    /**
     * Returns all identified receipts known to this source.
     * @return the receipts
     */
    List<IdentifiedReceipt> identifiedReceipts();

    /**
     * Perform the given action on each transaction record known to this source.
     * @param action the action to perform
     */
    void forEachTxnRecord(@NonNull Consumer<TransactionRecord> action);

    /**
     * Returns the priority receipt for the given transaction id.
     * @throws IllegalArgumentException if the transaction id is unknown
     */
    TransactionReceipt receiptOf(@NonNull TransactionID txnId);

    /**
     * Returns all child receipts for the given transaction id.
     */
    List<TransactionReceipt> childReceiptsOf(@NonNull TransactionID txnId);
}
