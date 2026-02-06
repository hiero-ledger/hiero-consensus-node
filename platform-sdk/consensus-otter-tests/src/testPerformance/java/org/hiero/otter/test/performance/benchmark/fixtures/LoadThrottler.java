// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test.performance.benchmark.fixtures;

import com.swirlds.common.utility.InstantUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;

/**
 * Utility class that submits transactions to network nodes with rate limiting and even distribution.
 * <p>
 * <ul>
 *   <li>Uses a transaction factory to create transactions on demand</li>
 *   <li>Distributes transactions evenly across all active nodes</li>
 *   <li>Rate limits submissions to achieve a target rate (transactions per second)</li>
 * </ul>
 */
public class LoadThrottler {

    private final Network network;
    private final Supplier<OtterTransaction> transactionFactory;

    /**
     * Creates a new LoadThrottler.
     *
     * @param environment the environment containing nodes to submit transactions to
     * @param transactionFactory the transaction factory used to create the transactions to submit
     *
     */
    public LoadThrottler(
            @NonNull final TestEnvironment environment, @NonNull final Supplier<OtterTransaction> transactionFactory) {
        this.network = Objects.requireNonNull(environment).network();
        this.transactionFactory = Objects.requireNonNull(transactionFactory);
    }

    /**
     * Submits the specified number of transactions using the configured factory.
     *
     * <p>This is a blocking method that:
     * <ol>
     *   <li>Creates transactions using the factory</li>
     *   <li>Distributes them evenly across nodes (1 turn per node among active nodes in the network)</li>
     *   <li>Rate limits to achieve target rate</li>
     * </ol>
     *
     * @param count the number of transactions to submit
     * @param maxTransactionsPerSecond the maximum rate in seconds to send transactions to the network
     * @throws IllegalArgumentException if count or maxTransactionsPerSecond are less than zero or
     *  there are non-active nodes
     */
    public void submitWithRate(final int count, final int maxTransactionsPerSecond) {

        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive, got: " + count);
        }
        if (maxTransactionsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "maxTransactionsPerSecond must be positive, got: " + maxTransactionsPerSecond);
        }

        final long intervalNanos =
                InstantUtils.NANOS_IN_MICRO * InstantUtils.MICROS_IN_SECOND / maxTransactionsPerSecond;
        final long startNanos = System.nanoTime();
        final List<Node> candidates =
                network.nodes().stream().filter(Node::isActive).toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No active nodes available in the network");
        }
        for (int i = 0; i < count; i++) {
            // Select node with even distribution
            final Node targetNode = candidates.get(i % candidates.size());

            // Submit transaction and track count
            targetNode.submitTransaction(transactionFactory.get());

            // Rate limit to achieve target rate (compensating for work time)
            final long expectedNanos = (i + 1) * intervalNanos;
            final long elapsedNanos = System.nanoTime() - startNanos;
            final long waitTime = expectedNanos - elapsedNanos;
            if (waitTime > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(waitTime);
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        }
    }
}
