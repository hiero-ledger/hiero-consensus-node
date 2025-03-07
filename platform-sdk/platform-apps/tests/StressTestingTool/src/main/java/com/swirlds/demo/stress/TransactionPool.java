// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stress;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;

/**
 * Provides pre-generated random transactions.
 */
final class TransactionPool {

    public static final byte APPLICATION_TRANSACTION_MARKER = 1;

    /**
     * the array of transactions
     */
    private final byte[][] transactions;

    /**
     * the standard pseudo-random number generator
     */
    private final Random random;

    /**
     * Constructs a TransactionPool instance with a fixed pool size, fixed transaction size.
     *
     * @param poolSize        the number of pre-generated transactions
     * @param transactionSize the size of randomly generated transaction
     * @throws IllegalArgumentException if the {@code poolSize} or the {@code transactionSize} parameters are less than
     *                                  one (1)
     */
    TransactionPool(final int poolSize, final int transactionSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize");
        }

        if (transactionSize < 1) {
            throw new IllegalArgumentException("transactionSize");
        }

        this.random = new Random();

        // the fixed size of each transaction
        this.transactions = new byte[poolSize][];

        for (int i = 0; i < transactions.length; i++) {
            final byte[] data = new byte[transactionSize];
            random.nextBytes(data);
            // Add byte with value of 1 as a marker to indicate the start of an application transaction. This is used
            // to later differentiate between application transactions and system transactions.
            data[0] = APPLICATION_TRANSACTION_MARKER;
            transactions[i] = data;
        }
    }

    /**
     * Retrieves a random transaction from the pool of pre-generated transactions.
     *
     * @return a random transaction from the pool
     */
    @NonNull
    byte[] transaction() {
        return transactions[random.nextInt(transactions.length)];
    }
}
