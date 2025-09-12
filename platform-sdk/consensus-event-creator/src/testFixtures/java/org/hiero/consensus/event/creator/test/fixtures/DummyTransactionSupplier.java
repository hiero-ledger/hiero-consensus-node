// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.event.creator.ConsensusEventCreator.TransactionSupplier;

/**
 * Dummy implementation of {@link TransactionSupplier} for easy testing.
 */
public class DummyTransactionSupplier implements TransactionSupplier {

    private List<Bytes> transactions = List.of();

    @NonNull
    @Override
    public List<Bytes> getTransactionsForEvent() {
        return transactions;
    }

    @Override
    public boolean hasTransactionsForEvents() {
        return !transactions.isEmpty();
    }

    /**
     * Set the transaction directly.
     *
     * @param transactions the new transactions
     */
    public void setTransactions(@NonNull final List<Bytes> transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }
}
