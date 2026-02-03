package org.hiero.consensus.event.intake.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.PlatformEvent;

public class EventCounter implements Consumer<PlatformEvent> {
    private final int expectedEventCount;
    private int currentEventCount = 0;
    private final CountDownLatch countDownLatch = new CountDownLatch(1);
    public EventCounter(final int expectedEventCount) {
        this.expectedEventCount = expectedEventCount;
    }
    @Override
    public void accept(final PlatformEvent event) {
        currentEventCount++;
        if (currentEventCount == expectedEventCount) {
            countDownLatch.countDown();
        }
    }

    public void waitForAllEvents() {
        try {
            final boolean done = countDownLatch.await(10, TimeUnit.SECONDS);
            if (!done) {
                throw new RuntimeException("Timed out waiting for events. Received " + currentEventCount +
                        " out of " + expectedEventCount);
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
