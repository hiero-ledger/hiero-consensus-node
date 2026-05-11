// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A rate limiter that throttles work to spread it evenly over a target duration.
 *
 * <p>The rate is expressed as a percentage of total work per second. For example:
 * <ul>
 *   <li>{@code rate = 1.0} means 1% of total work per second → total duration ≈ 100 seconds</li>
 *   <li>{@code rate = 0.1} means 0.1% of total work per second → total duration ≈ 1000 seconds</li>
 * </ul>
 *
 * <p>In {@link BlockStreamRecoveryWorkflow}, each work unit corresponds to one consensus round,
 * and total work is derived from {@code targetRound - initRound}. This allows rate limiting
 * without materializing the block stream or pre-scanning for item counts.
 *
 * <p>The internal clock starts lazily on the first {@link #acquire()} call.
 * This avoids penalizing the throttle budget for time spent scanning past older rounds or
 * performing slow I/O before the first applicable round is reached. The first {@code acquire()}
 * call starts the clock and returns immediately; subsequent calls check the schedule and park
 * the thread if execution is ahead of pace.
 */
public class WorkRateLimiter {

    private static final Logger log = LogManager.getLogger(WorkRateLimiter.class);

    /** Minimum sleep threshold to avoid spinning on very short park intervals. */
    private static final long MIN_SLEEP_NANOS = 1_000_000L; // 1 ms

    private final long totalWork;
    private final double targetDurationNanos;
    private long workCompleted;
    private long startTimeNanos; // 0 = clock not started yet

    /**
     * Creates a rate limiter for the given total work and rate.
     *
     * @param totalWork            the total number of work units expected
     * @param ratePercentPerSecond the rate at which to process work, expressed as a percentage of
     *                             total work per second (e.g. 1.0 = 1%/s → 100s total,
     *                             0.1 = 0.1%/s → 1000s total)
     * @throws IllegalArgumentException if totalWork ≤ 0 or ratePercentPerSecond ≤ 0
     */
    public WorkRateLimiter(final long totalWork, final double ratePercentPerSecond) {
        if (totalWork <= 0) {
            throw new IllegalArgumentException("totalWork must be positive, got " + totalWork);
        }
        if (ratePercentPerSecond <= 0) {
            throw new IllegalArgumentException("ratePercentPerSecond must be positive, got " + ratePercentPerSecond);
        }
        this.totalWork = totalWork;
        // target duration = 100 / rate seconds, converted to nanos
        this.targetDurationNanos = (100.0 / ratePercentPerSecond) * 1_000_000_000L;
        this.workCompleted = 0;
        this.startTimeNanos = 0;

        final double targetDurationSeconds = 100.0 / ratePercentPerSecond;
        log.info(
                "Rate limiter created: totalWork={}, rate={}%/s, targetDuration={}s (clock starts on first acquire)",
                totalWork, ratePercentPerSecond, String.format("%.1f", targetDurationSeconds));
    }

    /**
     * Called once per work unit at the throttle boundary. On the first call the internal clock
     * is started and the method returns immediately. On subsequent calls, if execution is ahead
     * of the expected schedule, the calling thread is parked until the schedule catches up.
     */
    public void acquire() {
        workCompleted++;
        if (startTimeNanos == 0) {
            startTimeNanos = System.nanoTime();
            return;
        }
        final double fractionDone = (double) workCompleted / totalWork;
        final long expectedElapsedNanos = (long) (fractionDone * targetDurationNanos);
        final long actualElapsedNanos = System.nanoTime() - startTimeNanos;
        final long sleepNanos = expectedElapsedNanos - actualElapsedNanos;

        if (sleepNanos > MIN_SLEEP_NANOS) {
            LockSupport.parkNanos(sleepNanos);
        }
    }

    /**
     * Returns the number of work units completed so far.
     */
    public long getWorkCompleted() {
        return workCompleted;
    }

    /**
     * Returns the total work units.
     */
    public long getTotalWork() {
        return totalWork;
    }
}
