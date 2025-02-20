// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.counters;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A utility for counting the number of objects in various parts of the pipeline. Will apply backpressure if the number
 * of objects exceeds a specified capacity.
 * <p>
 * In order to achieve higher performance in high contention environments, this class allows the count returned by
 * {@link #getCount()} to temporarily exceed the capacity even if {@link #forceOnRamp()} is not used. This doesn't allow
 * objects to be on-ramped in excess of the capacity, but it may add some slight fuzziness to the count.
 */
public class BackpressureObjectCounter extends ObjectCounter {

    private final String name;
    private final AtomicLong count = new AtomicLong(0);
    private final long capacity;

    /**
     * The amount of time to sleep while waiting for capacity to become available, or 0 to not sleep.
     */
    private final long sleepNanos;

    /**
     * When waiting for the count to reach zero, this object is used to efficiently sleep on the fork join pool.
     */
    private final ManagedBlocker waitUntilEmptyBlocker;

    /**
     * Constructor.
     *
     * @param name          the name of the object counter, used creating more informative exceptions
     * @param capacity      the maximum number of objects that can be in the part of the system that this object is
     *                      being used to monitor before backpressure is applied
     * @param sleepDuration when a method needs to block, the duration to sleep while blocking
     */
    public BackpressureObjectCounter(
            @NonNull final String name, final long capacity, @NonNull final Duration sleepDuration) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }

        this.name = Objects.requireNonNull(name);
        this.capacity = capacity;
        this.sleepNanos = sleepDuration.toNanos();

        waitUntilEmptyBlocker = new EmptyBlocker(count, sleepNanos);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRamp(final long delta) {
        if (attemptOnRamp(delta)) {
            return;
        }

        // Slow case. Capacity wasn't reserved, so we need to block.

        while (true) {
            try {
                // This will block until capacity is available and the count has been incremented.
                //
                // This is logically equivalent to the following pseudocode.
                // Note that the managed block is thread safe when onRamp() is being called from multiple threads,
                // even though this pseudocode is not.
                //
                //                while (count >= capacity) {
                //                    Thread.sleep(sleepNanos);
                //                }
                //                count++;
                //
                // The reason why we use the managedBlock() strategy instead of something simpler has to do with
                // the fork join pool paradigm. Unlike traditional thread pools where we have more threads than
                // CPUs, blocking (e.g. Thread.sleep()) on a fork join pool may monopolize an entire CPU core.
                // The managedBlock() pattern allows us to block while yielding the physical CPU core to other
                // tasks.
                ForkJoinPool.managedBlock(new ManagedBlocker() {

                    @Override
                    public boolean block() throws InterruptedException {
                        if (sleepNanos > 0) {
                            try {
                                NANOSECONDS.sleep(sleepNanos);
                            } catch (final InterruptedException e) {
                                // Don't throw an interrupted exception, but allow the thread to maintain its
                                // interrupted status.
                                Thread.currentThread().interrupt();
                            }
                        }

                        // Although we could technically check the count here and stop the back pressure if the count is
                        // below the threshold, it's simpler not to. Immediately after this method is called,
                        // isReleasable() will be called, which will do the checking for us. Easier to just let that
                        // method do the work, and have this method only be responsible for sleeping.
                        return false;
                    }

                    @Override
                    public boolean isReleasable() {
                        return attemptOnRamp(delta);
                    }
                });
                return;
            } catch (final InterruptedException ex) {
                // This should be impossible.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while blocking on an onRamp() for " + name);
            } catch (final RejectedExecutionException ex) {
                // We've exhausted our supply of background threads, we have no choice but to busy wait.
                Thread.onSpinWait();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean attemptOnRamp(final long delta) {
        final long resultingCount = count.addAndGet(delta);
        if (resultingCount <= capacity) {
            // We didn't violate capacity by incrementing the count, so we're done.
            return true;
        } else {
            // We may have violated capacity restrictions by incrementing the count.
            // Decrement count and return failure.
            count.addAndGet(-delta);
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forceOnRamp(final long delta) {
        count.addAndGet(delta);
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
            throw new IllegalStateException("Interrupted while blocking on an waitUntilEmpty() for " + name);
        }
    }
}
