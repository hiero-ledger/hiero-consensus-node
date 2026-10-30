// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What an event looks like, apart from its place in the graph. A fixed shape uses
 * {@link EmpiricalDistribution#constant(long)} for each quantity:
 *
 * <pre>{@code
 * // one other parent and one transaction of 445 bytes per event
 * EventShape shape = new EventShape(constant(1), constant(1), constant(445));
 * }</pre>
 *
 * @param otherParents         the number of other parents of an event, besides its self parent
 * @param transactionsPerEvent the number of transactions in an event
 * @param transactionSize      the size of a transaction in bytes, drawn for each transaction
 */
public record EventShape(
        @NonNull EmpiricalDistribution otherParents,
        @NonNull EmpiricalDistribution transactionsPerEvent,
        @NonNull EmpiricalDistribution transactionSize) {

    /**
     * The shape of mainnet's events in the week from 2026-10-18 00:00 to 2026-10-25 00:00 UTC,
     * between the upgrades to v0.78.1 and v0.78.2, counted by {@code scripts/event-shapes.py}
     * from the block stream. The values fall into bins about 25% wide, each at the mean of its
     * values; bins with a frequency below 10<sup>-4</sup> are left out. Each row is a value and
     * its count: of events, or of transactions for the sizes. The largest size, 5,326 bytes, is
     * the upload of a software upgrade package to file 0.0.150 on 2026-10-21.
     */
    public static final EventShape MAINNET = new EventShape(
            // other parents
            table(new long[][] {
                {1, 1_296_277},
                {2, 2_321_282},
                {3, 3_707_870},
                {4, 269_759_061}
            }),

            // transactions per event
            table(new long[][] {
                {0, 264_895_884},
                {1, 4_912_826},
                {2, 6_955_212},
                {3, 158_496},
                {4, 68_661},
                {5, 49_303},
                {7, 33_223}
            }),

            // transaction sizes
            table(new long[][] {
                {143, 12_079},
                {180, 2_295_560},
                {223, 683_417},
                {237, 629_027},
                {334, 276_740},
                {398, 278_530},
                {470, 15_756_752},
                {606, 15_550},
                {792, 25_657},
                {995, 79_790},
                {1_222, 79_814},
                {1_457, 21_861},
                {5_326, 57_101}
            }));

    /**
     * Creates a shape from the distributions of its quantities.
     *
     * @param otherParents         the number of other parents of an event, besides its self parent
     * @param transactionsPerEvent the number of transactions in an event
     * @param transactionSize      the size of a transaction in bytes, drawn for each transaction
     */
    public EventShape {
        requireNonNull(otherParents);
        requireNonNull(transactionsPerEvent);
        requireNonNull(transactionSize);
    }

    /**
     * Draws the transactions of one event: their number from {@link #transactionsPerEvent()}, then for each a size from
     * {@link #transactionSize()}, filled with random bytes. The event stream generator and Falcon's transaction
     * supplier both draw an event's transactions with this method.
     *
     * @param random the source of all random choices
     * @return the transactions
     * @throws IllegalArgumentException if the shape draws a negative number of transactions or an empty transaction
     */
    @NonNull
    public List<Bytes> sampleTransactions(@NonNull final Random random) {
        final int count = Math.toIntExact(transactionsPerEvent.sample(random));
        final List<Bytes> transactions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int size = Math.toIntExact(transactionSize.sample(random));
            final byte[] transaction = new byte[size];
            random.nextBytes(transaction);
            transactions.add(Bytes.wrap(transaction));
        }
        return transactions;
    }

    /**
     * Creates a distribution from a frequency table written as rows.
     *
     * @param rows each row a value and its count, in ascending order of value
     * @return the distribution
     */
    @NonNull
    private static EmpiricalDistribution table(@NonNull final long[][] rows) {
        final long[] values = new long[rows.length];
        final double[] counts = new double[rows.length];
        for (int i = 0; i < rows.length; i++) {
            values[i] = rows[i][0];
            counts[i] = rows[i][1];
        }
        return EmpiricalDistribution.of(values, counts);
    }
}
