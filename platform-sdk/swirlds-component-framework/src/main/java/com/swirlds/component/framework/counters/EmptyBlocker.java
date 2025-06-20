// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.counters;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class is used to implement flushing in a {@link java.util.concurrent.ForkJoinPool} friendly way. Blocks until
 * the count reaches zero.
 */
class EmptyBlocker implements ManagedBlocker {

    private final AtomicLong count;
    private final long sleepNanos;

    /**
     * Constructor.
     *
     * @param count      the counter to use
     * @param sleepNanos the number of nanoseconds to sleep while blocking, or 0 to not sleep
     */
    public EmptyBlocker(@NonNull final AtomicLong count, final long sleepNanos) {
        this.count = Objects.requireNonNull(count);
        this.sleepNanos = sleepNanos;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean block() throws InterruptedException {
        if (sleepNanos > 0) {
            try {
                NANOSECONDS.sleep(sleepNanos);
            } catch (final InterruptedException e) {
                // Don't throw an interrupted exception, but allow the thread to maintain its interrupted status.
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReleasable() {
        return count.get() == 0;
    }
}
