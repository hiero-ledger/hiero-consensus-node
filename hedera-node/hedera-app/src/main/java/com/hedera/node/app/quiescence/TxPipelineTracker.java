// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static com.hedera.node.app.quiescence.QuiescenceUtils.isRelevantTransaction;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.consensus.model.transaction.Transaction;

@Singleton
public class TxPipelineTracker {
    /**
     * Cheap, highly ephemeral count of this node's txs going through ingest checks but not yet submitted to network.
     */
    private final AtomicInteger preFlightCount = new AtomicInteger();
    /**
     * Count of all txs (user + node) that this node has submitted, but not seen in either a stale or pre-handled event.
     */
    private final AtomicInteger inFlightCount = new AtomicInteger();

    @Inject
    public TxPipelineTracker() {
        // Dagger2
    }

    /**
     * Returns an estimate of the number of transactions in the pipeline.
     */
    public int estimateTxPipelineCount() {
        return preFlightCount.get() + inFlightCount.get();
    }

    /**
     * Called when a transaction is beginning ingest checks.
     */
    public void incrementPreFlight() {
        preFlightCount.incrementAndGet();
    }

    /**
     * Called when a transaction has completed ingest checks (either passed or failed).
     */
    public void decrementPreFlight() {
        preFlightCount.decrementAndGet();
    }

    /**
     * Called when this node submits a quiescence-relevant transaction to the network.
     */
    public void incrementInFlight() {
        inFlightCount.incrementAndGet();
    }

    /**
     * Called with transactions that landed in purview of the {@link QuiescenceController}, either as a result of
     * pre-handle or stale events, <b>if</b> these transactions were in an event created by this node.
     * <p>
     * Note that every user transaction submitted via ingest will certainly be relevant to quiescence, but the
     * iterator might include other self-created transactions that are not relevant to quiescence.
     * @param iter iterator of self-created transactions that landed
     */
    public void countLanded(@NonNull final Iterator<Transaction> iter) {
        requireNonNull(iter);
        while (iter.hasNext()) {
            final var tx = iter.next();
            if (tx.getMetadata() instanceof PreHandleResult preHandleResult) {
                final var txInfo = preHandleResult.txInfo();
                if (txInfo != null && isRelevantTransaction(txInfo.txBody())) {
                    inFlightCount.accumulateAndGet(-1, (prev, next) -> Math.max(0, prev + next));
                }
            }
        }
    }
}
