package com.swirlds.platform.system;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.transaction.TransactionSupplier;
import org.hiero.consensus.event.creator.impl.pool.TransactionPoolNexus;
import org.hiero.consensus.model.node.NodeId;

public abstract class SwirldMainWithTransactionPool<T extends MerkleNodeState> implements SwirldMain<T> {

    private TransactionPoolNexus transactionPoolNexus;

    @NonNull
    @Override
    public List<Bytes> getTransactions() {
        return List.of();
    }

    @NonNull
    @Override
    public Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction transaction) {
        return null;
    }

    @Override
    public void init(@NonNull final Platform platform, @NonNull final NodeId selfId) {
        transactionPoolNexus = new TransactionPoolNexus(
                platform.getContext().getConfiguration(),
                platform.getContext().getMetrics(),
                platform.getContext().getTime()
        );
    }

    @NonNull
    public TransactionPoolNexus getTransactionPool() {
        return transactionPoolNexus;
    }
}
