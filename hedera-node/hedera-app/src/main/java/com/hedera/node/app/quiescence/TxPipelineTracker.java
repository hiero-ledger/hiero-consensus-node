// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static com.hedera.node.app.quiescence.QuiescenceUtils.isRelevantTransaction;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    /**
     * Wall-clock instant of the most recent observed transaction activity. Read by the
     * {@link QuiescenceController} grace period so that activity recorded between successive controller
     * polls is not lost when the counts return to zero before the next poll.
     */
    private final AtomicReference<Instant> lastActivityAt;

    private final InstantSource time;

    @Inject
    public TxPipelineTracker(@NonNull final InstantSource time) {
        this.time = requireNonNull(time);
        this.lastActivityAt = new AtomicReference<>(time.instant());
    }

    /**
     * Returns an estimate of the number of transactions in the pipeline.
     */
    public int estimateTxPipelineCount() {
        return preFlightCount.get() + inFlightCount.get();
    }

    /**
     * Returns the wall-clock instant of the most recent observed transaction activity.
     */
    public @NonNull Instant lastActivityAt() {
        return lastActivityAt.get();
    }

    /**
     * Updates {@link #lastActivityAt} to the current instant. Used by callers outside of ingest (such
     * as the {@link QuiescenceController}) to report observed transaction activity so it counts toward
     * the grace period.
     */
    public void recordActivity() {
        lastActivityAt.set(time.instant());
    }

    /**
     * Called when a transaction is beginning ingest checks.
     */
    public void incrementPreFlight() {
        lastActivityAt.set(time.instant());
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
        lastActivityAt.set(time.instant());
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
