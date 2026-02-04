// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A {@link Consumer} of {@link PlatformEvent} instances that counts received events and provides a blocking mechanism
 * to wait until a specified number of events have been consumed. Used in JMH benchmarks to synchronize on the
 * completion of event processing.
 */
public class EventCounter implements Consumer<PlatformEvent> {
    private final int expectedEventCount;
    private AtomicInteger currentEventCount = new AtomicInteger(0);
    private final CountDownLatch countDownLatch = new CountDownLatch(1);

    /**
     * Creates a new {@code EventCounter} that will wait for the given number of events.
     *
     * @param expectedEventCount the total number of events that must be received before the latch is released
     */
    public EventCounter(final int expectedEventCount) {
        this.expectedEventCount = expectedEventCount;
    }

    /**
     * Accepts a {@link PlatformEvent}, incrementing the internal counter. When the counter reaches the expected event
     * count, the internal latch is released, unblocking any thread waiting in {@link #waitForAllEvents()}.
     *
     * @param event the platform event to consume
     */
    @Override
    public void accept(final PlatformEvent event) {
        final int latestValue = currentEventCount.incrementAndGet();
        if (latestValue == expectedEventCount) {
            countDownLatch.countDown();
        }
    }

    /**
     * Blocks the calling thread until all expected events have been received, or until a 5-second timeout elapses.
     *
     * @throws RuntimeException if the timeout expires before all events are received, or if the thread is interrupted
     *                          while waiting
     */
    public void waitForAllEvents() {
        try {
            final boolean done = countDownLatch.await(5, TimeUnit.SECONDS);
            if (!done) {
                throw new RuntimeException("Timed out waiting for events. Received " + currentEventCount + " out of "
                        + expectedEventCount);
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
