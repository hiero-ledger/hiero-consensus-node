// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import java.util.concurrent.locks.LockSupport;

/**
 * Strategy to be used to yield the current thread while waiting for some data or resource.
 *
 * <ul>
 *   <li>{@link #SPIN} — calls {@link Thread#onSpinWait()}, yielding a CPU hint without relinquishing
 *       the OS thread. Lowest latency, highest CPU burn.</li>
 *   <li>{@link #PARK} — calls {@link LockSupport#parkNanos(long) LockSupport.parkNanos(1)},
 *       briefly suspending the thread. Good balance between latency and CPU usage.</li>
 *   <li>{@link #SLEEP} — calls {@link Thread#sleep(long, int) Thread.sleep(0, 1)}, relinquishing
 *       the OS thread for the minimum quanta. Lowest CPU burn, highest latency.</li>
 * </ul>
 */
public enum YieldStrategy {

    /**
     * CPU spin hint — no OS-level yielding. Best for latency-sensitive consumers with a fast producer.
     */
    SPIN {
        @Override
        public void yield() {
            Thread.onSpinWait();
        }
    },

    /**
     * Park the thread for 1 nanosecond via {@link LockSupport}. Does not throw
     * {@link InterruptedException}; interrupt status is preserved by the JVM.
     */
    PARK {
        @Override
        public void yield() {
            LockSupport.parkNanos(1L);
        }
    },

    /**
     * Sleep for the minimum OS quanta (0 ms + 1 ns). On interrupt, the thread's interrupt flag
     * is restored so the caller's interrupt check fires on the next iteration.
     */
    SLEEP {
        @Override
        public void yield() {
            try {
                Thread.sleep(0, 1);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };

    /**
     * Relinquish CPU control according to this strategy.
     */
    public abstract void yield();
}
