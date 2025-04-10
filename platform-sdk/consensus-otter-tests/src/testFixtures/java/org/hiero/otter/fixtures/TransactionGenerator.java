// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.otter.fixtures.internal.FixedRate;

/**
 * Interface representing a transaction generator.
 *
 * <p>This interface provides methods to generate random transactions and send them to the nodes.
 */
public interface TransactionGenerator {

    /**
     * Constant representing an infinite number of transactions.
     */
    int INFINITE = -1;

    /**
     * Generate a specified number of transactions with a given rate and distribution.
     *
     * @param count the number of transactions to generate
     * @param rate the rate at which to generate transactions
     * @param distribution the distribution of transactions across the nodes
     */
    void generateTransactions(int count, @NonNull Rate rate, @NonNull Distribution distribution);

    /**
     * Stop the transaction generation.
     */
    void stop();

    /**
     * Pause the transaction generation. Once paused, the transaction generation can be resumed by calling {@link #resume()}.
     */
    void pause();

    /**
     * Resume the transaction generation after it has been paused.
     */
    void resume();

    /**
     * The {@code Rate} class represents the rate at which transactions are generated.
     */
    interface Rate {

        /**
         * Creates a rate that generates transactions at a fixed frequency.
         *
         * @param tps the number of transactions per second
         * @return a {@code Rate} object representing the specified rate
         */
        @NonNull
        static Rate fixedRateWithTps(final int tps) {
            return new FixedRate(tps);
        }

        /**
         * Returns the duration until when the next transaction should be generated in nanoseconds.
         *
         * @param start the start time of the transaction generation
         * @param now the current time
         * @return the duration until when the next transaction should be generated
         */
        long nextDelayNS(@NonNull Instant start, @NonNull Instant now);
    }

    /**
     * The {@code Distribution} enum represents the distribution of transactions across the nodes.
     */
    enum Distribution {
        /**
         * Transactions are distributed uniformly across the nodes.
         */
        UNIFORM
    }
}
