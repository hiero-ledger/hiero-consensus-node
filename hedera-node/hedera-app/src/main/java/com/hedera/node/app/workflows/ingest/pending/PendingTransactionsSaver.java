// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest.pending;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.transaction.TransactionPoolNexus;

/**
 * Drains user transactions from the transaction pool at a freeze and saves them for the next start.
 */
public class PendingTransactionsSaver {
    private static final Logger logger = LogManager.getLogger(PendingTransactionsSaver.class);

    private final TransactionPoolNexus transactionPool;
    private final PendingTransactionsStore store;
    private final Executor executor;
    private final List<Bytes> drained = new ArrayList<>();

    /**
     * @param transactionPool the pool to drain
     * @param store where to save drained transactions
     * @param executor runs the write off the calling thread
     */
    public PendingTransactionsSaver(
            @NonNull final TransactionPoolNexus transactionPool,
            @NonNull final PendingTransactionsStore store,
            @NonNull final Executor executor) {
        this.transactionPool = requireNonNull(transactionPool);
        this.store = requireNonNull(store);
        this.executor = requireNonNull(executor);
    }

    /**
     * Moves all user transactions out of the pool into memory. Safe to call more than once.
     */
    public synchronized void drain() {
        drained.addAll(transactionPool.drainApplicationTransactions());
    }

    /**
     * Drains the pool, then writes everything drained so far for the given freeze round on the executor. The returned
     * future never completes exceptionally, so a failed write cannot cut short the freeze-round wait.
     *
     * @param freezeRound the freeze round
     * @return a future completed once the write is done or has failed
     */
    public synchronized @NonNull CompletableFuture<Void> drainAndSaveAsync(final long freezeRound) {
        drain();
        if (drained.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final var saved = new SavedPendingTransactions(freezeRound, drained);
        drained.clear();
        return CompletableFuture.runAsync(() -> save(saved), executor);
    }

    private void save(@NonNull final SavedPendingTransactions saved) {
        try {
            store.save(saved);
            logger.info(
                    "Saved {} pending user transactions at freeze round {}",
                    saved.transactions().size(),
                    saved.freezeRound());
        } catch (final Exception e) {
            logger.warn(
                    "Unable to save pending user transactions (count={}, freeze round={})",
                    saved.transactions().size(),
                    saved.freezeRound(),
                    e);
        }
    }
}
