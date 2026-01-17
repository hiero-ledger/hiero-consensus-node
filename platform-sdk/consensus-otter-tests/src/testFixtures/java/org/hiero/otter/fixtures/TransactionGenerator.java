// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

/**
 * Interface representing a transaction generator.
 *
 * <p>A {@link TransactionGenerator} generates a steady flow of transaction to all nodes in the
 * network. The generator sends a constant number of transactions to each node per second, which ensures there is
 * always at least one transaction waiting to be processed by the event creator. The number of transactions per
 * second is defined by the {@link #TPS} constant.
 */
public interface TransactionGenerator {

    /** The number of transactions to generate per second, per node. */
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
