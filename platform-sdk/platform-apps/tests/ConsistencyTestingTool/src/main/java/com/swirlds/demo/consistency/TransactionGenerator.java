// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.base.utility.ByteUtils.longToByteArray;

import com.swirlds.base.state.Startable;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Random;

/**
 * Generates and submits transactional workload
 */
public class TransactionGenerator implements Startable {
    /**
     * A source of randomness
     */
    private final Random random;

    /**
     * The platform to submit transactions to
     */
    private final Platform platform;

    /**
     * The thread that generates and submits transactions
     */
    private final StoppableThread thread;

    /**
     * Constructor
     *
     * @param random                           a source of randomness
     * @param platform                         the platform to submit transactions to
     * @param networkWideTransactionsPerSecond the number of transactions to generate per second, network-wide
     */
    public TransactionGenerator(
            @NonNull final Random random,
            @NonNull final Platform platform,
            final int networkWideTransactionsPerSecond) {

        this.random = Objects.requireNonNull(random);
        this.platform = Objects.requireNonNull(platform);

        // Each node in an N node network should create 1/N transactions per second.
        final double tps = (double) networkWideTransactionsPerSecond
                / platform.getRoster().rosterEntries().size();

        thread = new StoppableThreadConfiguration<>(getStaticThreadManager())
                .setComponent("consistency-testing-tool")
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
     * Generate and submit a single transaction
     * <p>
     * Each transaction consists of a single random long value
     */
    private void generateTransaction() {
        platform.createTransaction(longToByteArray(random.nextLong()));
    }
}
