// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.consensus.utility.ByteUtils.intToByteArray;

import com.swirlds.base.state.Startable;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.platform.system.Platform;
import java.util.Random;

/**
 * Generates and submits transactional workload.
 */
public class TransactionGenerator implements Startable {

    private final Random random;
    private final Platform platform;

    private final StoppableThread thread;

    public TransactionGenerator(
            final Random random, final Platform platform, final int networkWideTransactionsPerSecond) {

        this.random = random;
        this.platform = platform;

        // Each node in an N node network should create 1/N transactions per second.
        final int tps = networkWideTransactionsPerSecond
                / platform.getRoster().rosterEntries().size();

        thread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setComponent("iss-testing-tool")
                .setThreadName("transaction-generator")
                .setMaximumRate(tps)
                .setWork(this::generateTransaction)
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        thread.start();
    }

    /**
     * Generate and submit a single transaction.
     */
    private void generateTransaction() {
        // Transactions are simple: take an integer, and add it into the running sum.
        platform.createTransaction(intToByteArray(random.nextInt()));
    }
}
