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
 * <p>After each unit of work, the limiter checks whether execution is ahead of the expected
 * schedule and parks the calling thread if so. This produces smooth, evenly-paced processing
 * rather than bursts followed by pauses.
 */
public class WorkRateLimiter {

    private static final Logger log = LogManager.getLogger(WorkRateLimiter.class);

    /** Minimum sleep threshold to avoid spinning on very short park intervals. */
    private static final long MIN_SLEEP_NANOS = 1_000_000L; // 1 ms

    private final long totalWork;
    private final double targetDurationNanos;
    private long workCompleted;
    private final long startTimeNanos;

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
        this.startTimeNanos = System.nanoTime();

        final double targetDurationSeconds = 100.0 / ratePercentPerSecond;
        log.info(
                "Rate limiter initialized: totalWork={}, rate={}%/s, targetDuration={}s",
                totalWork, ratePercentPerSecond, String.format("%.1f", targetDurationSeconds));
    }

    /**
     * Called after completing one unit of work. If execution is ahead of the expected schedule,
     * this method parks the calling thread until the schedule catches up.
     */
    public void acquire() {
        workCompleted++;
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
