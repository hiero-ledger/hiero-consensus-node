// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.Distributor;
import org.hiero.otter.fixtures.internal.UniformDistributor;
import org.hiero.otter.fixtures.turtle.TurtleTimeManager.TimeTickReceiver;

public class TurtleTransactionGenerator implements TransactionGenerator, TimeTickReceiver {

    private final TurtleNetwork network;
    private final Randotron randotron;

    private int count;
    private Rate rate;
    private Distributor distributor;
    private Instant startTime;
    private Instant lastTimestamp;
    private boolean paused;

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
    public void generateTransactions(
            final int count, @NonNull final Rate rate, @NonNull final Distribution distribution) {
        stop();
        if (distribution != Distribution.UNIFORM) {
            throw new IllegalArgumentException("Only UNIFORM distribution is supported");
        }
        this.count = count;
        this.rate = requireNonNull(rate);
        this.distributor = new UniformDistributor(network, randotron);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        rate = null;
        count = 0;
        startTime = null;
        lastTimestamp = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pause() {
        paused = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resume() {
        if (paused) {
            paused = false;
            lastTimestamp = null;
        }
    }

    @Override
    public void tick(@NonNull Instant now) {
        if (rate == null && paused) {
            return;
        }
        if (lastTimestamp != null) {
            Instant currentTime = nextPointInTime(lastTimestamp);
            while (currentTime.isBefore(now)) {
                distributor.submitTransaction();

                // reduce counter (if not infinite) and remove generator if count is 0
                if (count != INFINITE && --count == 0) {
                    stop();
                    return;
                }

                currentTime = nextPointInTime(currentTime);
            }
        }
        if (startTime == null) {
            startTime = now;
        }
        lastTimestamp = now;
    }

    private Instant nextPointInTime(@NonNull final Instant currentTime) {
        long delay = rate.nextDelayNS(startTime, currentTime);
        return currentTime.plusNanos(delay);
    }
}
