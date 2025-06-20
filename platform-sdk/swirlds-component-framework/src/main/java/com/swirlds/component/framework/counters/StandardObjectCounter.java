// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.counters;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A utility for counting the number of objects in various parts of the pipeline.
 */
public class StandardObjectCounter extends ObjectCounter {

    private final AtomicLong count = new AtomicLong(0);
    private final ManagedBlocker waitUntilEmptyBlocker;

    /**
     * Constructor.
     *
     * @param sleepDuration when a method needs to block, the duration to sleep while blocking
     */
    public StandardObjectCounter(@NonNull final Duration sleepDuration) {
        final long sleepNanos = sleepDuration.toNanos();
        waitUntilEmptyBlocker = new EmptyBlocker(count, sleepNanos);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRamp(final long delta) {
        count.addAndGet(delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean attemptOnRamp(final long delta) {
        count.getAndAdd(delta);
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forceOnRamp(final long delta) {
        count.getAndAdd(delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void offRamp(final long delta) {
        count.addAndGet(-delta);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCount() {
        return count.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitUntilEmpty() {
        if (count.get() == 0) {
            return;
        }

        try {
            ForkJoinPool.managedBlock(waitUntilEmptyBlocker);
        } catch (final InterruptedException e) {
            // This should be impossible.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while blocking on an waitUntilEmpty()");
        }
    }
}
