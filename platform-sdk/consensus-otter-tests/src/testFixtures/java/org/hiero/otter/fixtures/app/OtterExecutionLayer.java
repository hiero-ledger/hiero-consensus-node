// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.ExecutionLayer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Random;
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

    private final Random random;

    /**
     * Constructs a new OtterExecutionLayer.
     *
     * @param random the source of randomness for populating signature transaction nonce values.
     * @param metrics the metrics system to use
     */
    public OtterExecutionLayer(@NonNull final Random random, @NonNull final Metrics metrics) {
        this.random = requireNonNull(random);
        transactionPool = new TransactionPoolNexus(getTransactionLimits(), TX_QUEUE_SIZE, metrics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitStateSignature(@NonNull final StateSignatureTransaction transaction) {
        transactionPool.submitPriorityTransaction(
                Bytes.wrap(TransactionFactory.createStateSignatureTransaction(random.nextLong(), transaction)
                        .toByteArray()));
    }

    /**
     * Submits a transaction to the transaction pool.
     * @param transaction the transaction to submit
     * @return true if the transaction was successfully submitted, false otherwise
     */
    public boolean submitApplicationTransaction(@NonNull final byte[] transaction) {
        return transactionPool.submitApplicationTransaction(Bytes.wrap(transaction));
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<Bytes> getTransactionsForEvent() {
        return transactionPool.getTransactionsForEvent();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasBufferedSignatureTransactions() {
        return transactionPool.hasBufferedSignatureTransactions();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void newPlatformStatus(@NonNull final PlatformStatus platformStatus) {
        transactionPool.updatePlatformStatus(platformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        transactionPool.reportUnhealthyDuration(duration);
    }
}
