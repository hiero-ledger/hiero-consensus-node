// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.ExecutionLayer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.hiero.otter.fixtures.TransactionFactory;

/**
 * An implementation of the {@link ExecutionLayer} for the Otter tests.
 */
public class OtterExecutionLayer implements ExecutionLayer {
    /** The maximum number of transaction to store in the transaction pool */
    private static final int TX_QUEUE_SIZE = 100_000;

    /** the transaction pool, stores transactions that should be sumbitted to the network */
    private final TransactionPoolNexus transactionPool;

    public OtterExecutionLayer(@NonNull final Metrics metrics) {
        transactionPool = new TransactionPoolNexus(getTransactionLimits(), TX_QUEUE_SIZE, metrics);
    }

    @Override
    public void submitStateSignature(@NonNull final StateSignatureTransaction transaction) {
        transactionPool.submitPriorityTransaction(Bytes.wrap(
                TransactionFactory.createStateSignatureTransaction(transaction).toByteArray()));
    }

    /**
     * Submits a transaction to the transaction pool.
     * @param transaction the transaction to submit
     * @return true if the transaction was successfully submitted, false otherwise
     */
    public boolean submitApplicationTransaction(@NonNull final byte[] transaction) {
        return transactionPool.submitApplicationTransaction(Bytes.wrap(transaction));
    }

    @NonNull
    @Override
    public List<Bytes> getTransactionsForEvent() {
        return transactionPool.getTransactionsForEvent();
    }

    @Override
    public boolean hasBufferedSignatureTransactions() {
        return transactionPool.hasBufferedSignatureTransactions();
    }

    @Override
    public void newPlatformStatus(@NonNull final PlatformStatus platformStatus) {
        transactionPool.updatePlatformStatus(platformStatus);
    }

    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        transactionPool.reportUnhealthyDuration(duration);
    }
}
