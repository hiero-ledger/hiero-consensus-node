package com.swirlds.platform.system;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.platform.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.transaction.TransactionConfig;
import org.hiero.consensus.transaction.TransactionPoolNexus;

public abstract class DefaultSwirldMain<T extends MerkleNodeState> implements SwirldMain<T> {
    /** The maximum number of transaction to store in the transaction pool */
    private static final int TX_QUEUE_SIZE = 100_000;
    private static final TransactionConfig TRANSACTION_CONFIG = new TransactionConfig(
            133120, 245760
    );
    /** the transaction pool, stores transactions that should be sumbitted to the network */
    private final TransactionPoolNexus transactionPool;

    public DefaultSwirldMain() {
        this.transactionPool = new TransactionPoolNexus(
                TRANSACTION_CONFIG, TX_QUEUE_SIZE, new NoOpMetrics()
        );
    }

    @Override
    public void submitStateSignature(@NonNull final StateSignatureTransaction transaction) {
        transactionPool.submitPriorityTransaction(StateSignatureTransaction.PROTOBUF.toBytes(transaction));
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
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        transactionPool.updatePlatformStatus(platformStatus);
    }

    public TransactionPoolNexus getTransactionPool() {
        return transactionPool;
    }
}
