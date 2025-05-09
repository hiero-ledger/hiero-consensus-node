// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.turtle.TurtleTimeManager.TimeTickReceiver;

public class TurtleTransactionGenerator implements TransactionGenerator, TimeTickReceiver {

    private static final Duration CYCLE_DURATION = Duration.ofSeconds(1).dividedBy(TransactionGenerator.TPS);

    private final TurtleNetwork network;
    private final Randotron randotron;

    private Instant startTime;
    private Instant lastTimestamp;
    private boolean running;

    /**
     * Constructor for the {@link TurtleTransactionGenerator} class.
     *
     * @param network the turtle network
     * @param randotron the random number generator
     */
    public TurtleTransactionGenerator(@NonNull final TurtleNetwork network, @NonNull final Randotron randotron) {
        this.network = requireNonNull(network);
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
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull Instant now) {
        if (!running) {
            return;
        }

        if (lastTimestamp != null) {
            final long previousCount =
                    Duration.between(startTime, lastTimestamp).dividedBy(CYCLE_DURATION);
            final long currentCount = Duration.between(startTime, now).dividedBy(CYCLE_DURATION);
            for (long i = previousCount; i < currentCount; i++) {
                for (final Node node : network.getNodes()) {
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
