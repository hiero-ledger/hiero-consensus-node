// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static com.hedera.node.app.quiescence.QuiescenceUtils.isRelevantTransaction;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * Tracks user/node transactions that have entered ingest checks (pre-flight) or have been submitted to the network
 * but not yet observed in a pre-handled or stale event (in-flight). Used by {@link QuiescenceController} to decide
 * whether the node has pending work that should keep it out of {@link QuiescenceCommand#QUIESCE}.
 *
 * <p>See <a href="../../../../../../../../docs/quiescence-analysis.md">docs/quiescence-analysis.md</a> for context.
 */
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

    private final Counter underflowCounter;

    @Inject
    public TxPipelineTracker(@NonNull final Metrics metrics) {
        this.underflowCounter = requireNonNull(metrics)
                .getOrCreate(new Counter.Config("quiescence", "inflightUnderflow")
                        .withDescription("Times countLanded() saw a self-created relevant tx with no matching "
                                + "in-flight increment on this node. Routinely non-zero (per-node tracker, txs "
                                + "ingested here may land in peer events). High rate = cross-node drift signal"));
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
     * <p>
     * {@link #inFlightCount} is a per-node counter — it is incremented by {@link IngestWorkflowImpl} when this
     * node accepts a transaction via gRPC, and decremented here when this node creates an event containing a
     * relevant transaction. In a multi-node network, a transaction ingested by node {@code X} routinely lands in
     * an event created by node {@code Y}; node {@code Y} will then call {@code countLanded} for a transaction it
     * never incremented in-flight for, while node {@code X}'s in-flight count goes unbalanced. The clamp below
     * is therefore <b>by design</b>, not a bug to surface — it is the consequence of {@code TxPipelineTracker}
     * being a per-node estimator rather than a network-wide one. A rising
     * {@code quiescence.inflightUnderflow} metric is still useful as an operator signal (e.g. unexpectedly heavy
     * cross-node drift), but it is not a per-event anomaly worth logging.
     *
     * @param iter iterator of self-created transactions that landed
     */
    public void countLanded(@NonNull final Iterator<Transaction> iter) {
        requireNonNull(iter);
        while (iter.hasNext()) {
            final var tx = iter.next();
            if (tx.getMetadata() instanceof PreHandleResult preHandleResult) {
                final var txInfo = preHandleResult.txInfo();
                if (txInfo != null && isRelevantTransaction(txInfo.txBody())) {
                    decrementInFlightOrClamp();
                }
            }
        }
    }

    /**
     * Atomically decrements the in-flight count if it is strictly positive. If it is already zero (this node
     * is observing a self-created relevant transaction it never ingested locally — see {@link #countLanded}
     * for why this is expected in a multi-node network), the call is a no-op except for incrementing the
     * {@code quiescence.inflightUnderflow} metric.
     */
    private void decrementInFlightOrClamp() {
        int prev;
        do {
            prev = inFlightCount.get();
            if (prev <= 0) {
                underflowCounter.increment();
                return;
            }
        } while (!inFlightCount.compareAndSet(prev, prev - 1));
    }
}
