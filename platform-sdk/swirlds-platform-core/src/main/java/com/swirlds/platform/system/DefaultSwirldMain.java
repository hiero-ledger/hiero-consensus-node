// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.platform.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.transaction.TransactionPoolNexus;

/**
 * A default implementation of the {@link SwirldMain} interface for applications that do not require a custom
 * transaction pool or transaction limits.
 */
public abstract class DefaultSwirldMain<T extends MerkleNodeState> implements SwirldMain<T> {
    /** The maximum number of transaction to store in the transaction pool */
    private static final int TX_QUEUE_SIZE = 100_000;

    /** the transaction pool, stores transactions that should be sumbitted to the network */
    private final TransactionPoolNexus transactionPool;

    public DefaultSwirldMain() {
        this.transactionPool =
                new TransactionPoolNexus(getTransactionLimits(), TX_QUEUE_SIZE, new NoOpMetrics(), Time.getCurrent());
    }

    @Override
    public void submitStateSignature(@NonNull final StateSignatureTransaction transaction) {
        transactionPool.submitPriorityTransaction(StateSignatureTransaction.PROTOBUF.toBytes(transaction));
    }

    @NonNull
    @Override
    public List<TimestampedTransaction> getTransactionsForEvent() {
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

    /**
     * Returns the transaction pool used by this application.
     *
     * @return the transaction pool nexus
     */
    @NonNull
    public TransactionPoolNexus getTransactionPool() {
        return transactionPool;
    }

    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        transactionPool.reportUnhealthyDuration(duration);
    }
}
