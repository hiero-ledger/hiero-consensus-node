package org.hiero.otter.fixtures.app;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.ExecutionCallback;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.transaction.TransactionConfig;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.hiero.otter.fixtures.TransactionFactory;

public class OtterExecutionCallback implements ExecutionCallback {

    /** the transaction pool, stores transactions that should be sumbitted to the network */
    private final TransactionPoolNexus transactionPool;
    public OtterExecutionCallback(@NonNull final TransactionConfig transactionConfig, @NonNull final Metrics metrics) {
        transactionPool = new TransactionPoolNexus(transactionConfig, metrics);
    }

    @Override
    public void submitSystemTransaction(@NonNull final StateSignatureTransaction transaction) {
        transactionPool.submitPriorityTransaction(
                Bytes.wrap(
                        TransactionFactory.createStateSignatureTransaction(transaction).toByteArray())
        );
    }

    public boolean submitApplicationTransaction(@NonNull final byte[] transaction) {
        return transactionPool.submitApplicationTransaction(Bytes.wrap(transaction));
    }

    @NonNull
    @Override
    public List<Bytes> getTransactions() {
        return transactionPool.getTransactions();
    }

    @Override
    public boolean hasBufferedSignatureTransactions() {
        return transactionPool.hasBufferedSignatureTransactions();
    }
}
