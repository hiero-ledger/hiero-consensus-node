package com.swirlds.platform.system;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.builder.ExecutionCallback;
import com.swirlds.platform.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.event.creator.impl.pool.TransactionPoolNexus;

public abstract class SwirldMainWithTransactionPool<T extends MerkleNodeState> implements ExecutionCallback {


    private final TransactionPoolNexus transactionPoolNexus;
    private final SwirldMain<T> swirldMain;

    public SwirldMainWithTransactionPool(final SwirldMain<T> swirldMain, final PlatformContext context) {
        this.swirldMain = swirldMain;
        transactionPoolNexus = new TransactionPoolNexus(
                context.getConfiguration(),
                context.getMetrics(),
                context.getTime());
    }

    @NonNull
    public List<Bytes> getTransactions() {
        return transactionPoolNexus.getTransactions();
    }

    public void submitApplicationTransaction(@NonNull final Bytes transaction) {
        transactionPoolNexus.submitApplicationTransaction(transaction);
    }

    public void submitSystemTransaction(@NonNull final Bytes transaction) {
        transactionPoolNexus.submitTransaction(transaction, true);
    }

    public boolean hasBufferedSignatureTransactions() {
        return transactionPoolNexus.hasBufferedSignatureTransactions();
    }
}
