// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.TransactionGenerator;

/**
 * A transaction generator for the Turtle framework.
 *
 * <p>This class implements the {@link TransactionGenerator} interface and generates transactions at a fixed rate
 * to be submitted to the active nodes in the Turtle network.
 */
public class TurtleTransactionGenerator implements TransactionGenerator {

    private static final Duration CYCLE_DURATION = Duration.ofSeconds(1).dividedBy(TransactionGenerator.TPS);

    private final Randotron randotron;

    @Nullable
    private Instant startTime;

    @Nullable
    private Instant lastTimestamp;

    private boolean running;

    /**
     * Constructor for the {@link TurtleTransactionGenerator} class.
     *
     * @param randotron the random number generator
     */
    public TurtleTransactionGenerator(@NonNull final Randotron randotron) {
        this.randotron = requireNonNull(randotron);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        if (!running) {
            startTime = null;
            lastTimestamp = null;
            running = true;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        running = false;
    }

    /**
     * Generates and submits transactions to the active nodes in the network at a fixed rate.
     *
     * @param now the current time
     * @param nodes the list of nodes in the network
     */
    public void tick(@NonNull final Instant now, @NonNull final List<TurtleNode> nodes) {
        if (!running) {
            return;
        }

        if (lastTimestamp != null) {
            assert startTime != null; // startTime must be initialized if lastTimestamp is not null
            final long previousCount =
                    Duration.between(startTime, lastTimestamp).dividedBy(CYCLE_DURATION);
            final long currentCount = Duration.between(startTime, now).dividedBy(CYCLE_DURATION);
            final List<TurtleNode> activeNodes = nodes.stream()
                    .filter(node -> node.platformStatus() == PlatformStatus.ACTIVE)
                    .toList();
            for (long i = previousCount; i < currentCount; i++) {
                for (final TurtleNode node : activeNodes) {
                    // Generate a random transaction and submit it to the node.
                    final byte[] transaction = TransactionFactory.createEmptyTransaction(randotron.nextInt())
                            .toByteArray();
                    node.submitTransaction(transaction);
                }
            }
        }

        if (startTime == null) {
            startTime = now;
        }
        lastTimestamp = now;
    }
}
