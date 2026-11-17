// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.benchmark.tools.event.EventShape;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.TimestampedTransaction;

/**
 * Gives each event a node creates transactions drawn from a shape, with {@link EventShape#sampleTransactions}. The event
 * creator asks for them only when it creates an event, so every created event gets one draw.
 *
 * <p>The transactions are received when the event takes them. The creator uses the latest received time of an event's
 * transactions as a lower bound of its creation time, so an event with transactions is created at the current time.
 */
class ShapedTransactionSupplier implements EventTransactionSupplier {

    /** The shape to draw the transactions from; only its transaction tables are used. */
    @NonNull
    private final EventShape shape;

    /** The random number generator. */
    @NonNull
    private final Random random;

    /** The time of the node. */
    @NonNull
    private final Time time;

    /**
     * Constructor.
     *
     * @param shape the shape to draw the transactions from; only its transaction tables are used
     * @param seed the seed of the random number generator
     * @param time the time of the node
     */
    ShapedTransactionSupplier(@NonNull final EventShape shape, final long seed, @NonNull final Time time) {
        this.shape = requireNonNull(shape);
        this.random = new Random(seed);
        this.time = requireNonNull(time);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<TimestampedTransaction> getTransactionsForEvent() {
        final List<Bytes> transactions = shape.sampleTransactions(random);
        final Instant now = time.now();
        final List<TimestampedTransaction> result = new ArrayList<>(transactions.size());
        for (final Bytes transaction : transactions) {
            result.add(new TimestampedTransaction(transaction, now));
        }
        return result;
    }
}
