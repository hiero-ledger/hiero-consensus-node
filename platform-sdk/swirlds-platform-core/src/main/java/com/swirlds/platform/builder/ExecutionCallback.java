package com.swirlds.platform.builder;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.TransactionSupplier;

public interface ExecutionCallback extends TransactionSupplier {
    /**
     * Encodes a system transaction to {@link Bytes} representation of a {@link com.hedera.hapi.node.base.Transaction}.
     *
     * @param transaction the {@link StateSignatureTransaction} to encode
     */
    void submitSystemTransaction(@NonNull final StateSignatureTransaction transaction);

    /**
     * Returns a list of transactions. May return an empty array.
     *
     * @return a list with 0 or more transactions
     */
    @NonNull
    List<Bytes> getTransactions();

    /**
     * Check if there are any buffered signature transactions waiting to be put into events.
     *
     * @return true if there are any buffered signature transactions
     */
    boolean hasBufferedSignatureTransactions();

//    /**
//     * Update the platform status.
//     *
//     * @param platformStatus the new platform status
//     */
//    void updatePlatformStatus(@NonNull final PlatformStatus platformStatus);
}
