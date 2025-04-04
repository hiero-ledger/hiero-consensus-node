// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Interface representing a transaction generator.
 *
 * <p>This interface provides methods to generate random transactions and send them to the nodes.
 */
public interface TransactionGenerator {

    /**
     * Constant representing an unlimited number of transactions.
     */
    int UNLIMITED = -1;

    /**
     * Generate a specified number of transactions with a given rate and distribution.
     *
     * @param count the number of transactions to generate
     * @param rate the rate at which to generate transactions
     * @param distribution the distribution of transactions across the nodes
     */
    void generateTransactions(int count, @NonNull Rate rate, @NonNull Distribution distribution);

    /**
     * The {@code Rate} class represents the rate at which transactions are generated.
     */
    class Rate {

        /**
         * Creates a rate that generates transactions at a fixed frequency.
         *
         * @param tps the number of transactions per second
         * @return a {@code Rate} object representing the specified rate
         */
        @NonNull
        public static Rate regularRateWithTps(final int tps) {
            throw new UnsupportedOperationException("Not implemented yet");
        }
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
