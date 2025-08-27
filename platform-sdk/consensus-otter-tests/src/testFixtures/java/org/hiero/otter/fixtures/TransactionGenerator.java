// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

/**
 * Interface representing a transaction generator.
 *
 * <p>A {@link TransactionGenerator} generates a steady flow of transaction to all nodes in the
 * network. The generator sends 100 transactions to each node per second, which ensures there is
 * always at least one transaction waiting to be processed by the event creator.
 */
public interface TransactionGenerator {

    int TPS = 100;

    /**
     * Start the generation of transactions.
     *
     * <p>This method is idempotent, meaning that it is safe to call multiple times.
     */
    void start();

    /**
     * Stop the transaction generation.
     *
     * <p>This method is idempotent, meaning that it is safe to call multiple times.
     */
    void stop();
}
