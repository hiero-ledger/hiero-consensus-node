// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.app;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.ExecutionLayer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.hiero.sloth.fixtures.SlothTransactionType;
import org.hiero.sloth.fixtures.TransactionFactory;

/**
 * An implementation of the {@link ExecutionLayer} for benchmarks.
 */
public class SlothExecutionLayer implements ExecutionLayer {

    /** The maximum number of transaction to store in the transaction pool */
    private static final int TX_QUEUE_SIZE = 100_000;

    /** The transaction pool, stores transactions that should be submitted to the network. */
    private final TransactionPoolNexus transactionPool;

    private final Random random;

    /** Responsible for self-generating transactions at a configurable rate and type. */
    private final SlothTransactionGenerator transactionGenerator;

    /**
     * Constructs a new SlothExecutionLayer.
     *
     * @param random  the source of randomness for populating signature transaction nonce values.
     * @param metrics the metrics system to use
     * @param time    the source of time to use
     */
    public SlothExecutionLayer(@NonNull final Random random, @NonNull final Metrics metrics, @NonNull final Time time) {
        this.random = requireNonNull(random);
        transactionPool = new TransactionPoolNexus(
                getTransactionLimits(),
                TX_QUEUE_SIZE,
                TransactionPoolNexus.DEFAULT_MAXIMUM_PERMISSIBLE_UNHEALTHY_DURATION,
                metrics,
                time);
        transactionGenerator = new SlothTransactionGenerator(transactionPool, time);
    }

    /**
     * Starts self-generating transactions and submitting them to the transaction pool.
     *
     * <p>If generation is already running it is stopped and restarted with the new parameters.
     * The generated-count counter is reset to zero on each call.
     *
     * @param tps  the number of transactions to generate per second
     * @param type the type of transaction to generate
     * @throws IllegalArgumentException if {@code tps} is not positive
     */
    public void startGenerating(final int tps, @NonNull final SlothTransactionType type) {
        transactionGenerator.startGenerating(tps, type);
    }

    /**
     * Stops self-generation of transactions.
     *
     * <p>This method is idempotent; calling it when generation is not running has no effect.
     *
     * @return the total number of transactions successfully submitted to the pool since the last
     *         call to {@link #startGenerating}
     */
    public long stopGenerating() {
        return transactionGenerator.stopGenerating();
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @NonNull
    @Override
    public List<TimestampedTransaction> getTransactionsForEvent() {
        return transactionPool.getTransactionsForEvent();
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasBufferedSignatureTransactions() {
        return transactionPool.hasBufferedSignatureTransactions();
    }

    /** {@inheritDoc} */
    @Override
    public void newPlatformStatus(@NonNull final PlatformStatus platformStatus) {
        transactionPool.updatePlatformStatus(platformStatus);
    }

    /** {@inheritDoc} */
    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        transactionPool.reportUnhealthyDuration(duration);
    }
}
