// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A rate limiter that caps the throughput of applied rounds to a fixed number per second.
 *
 * <p>This controls CPU/IO load independently of the total state size or number of rounds.
 * For example, {@code roundsPerSecond = 10} allows at most 10 rounds per second regardless
 * of whether there are 100 or 100,000 rounds to process.
 *
 * <p>The internal clock starts lazily on the first {@link #acquire()} call, so time spent
 * scanning past older rounds is not counted. The first {@code acquire()} starts the clock
 * and returns immediately; subsequent calls park the thread if execution is ahead of pace.
 */
public class WorkRateLimiter {

    private static final Logger log = LogManager.getLogger(WorkRateLimiter.class);

    /** Minimum sleep threshold to avoid spinning on very short park intervals. */
    private static final long MIN_SLEEP_NANOS = 1_000_000L; // 1 ms

    private final double intervalNanos;
    private long roundsCompleted;
    private long startTimeNanos; // 0 = clock not started yet

    /**
     * Creates a rate limiter with the given throughput cap.
     *
     * @param roundsPerSecond maximum number of rounds to process per second
     * @throws IllegalArgumentException if roundsPerSecond ≤ 0
     */
    public WorkRateLimiter(final double roundsPerSecond) {
        if (roundsPerSecond <= 0) {
            throw new IllegalArgumentException("roundsPerSecond must be positive, got " + roundsPerSecond);
        }
        this.intervalNanos = 1_000_000_000.0 / roundsPerSecond;
        log.info(
                "Rate limiter created: {}rounds/s (interval={}ms)",
                roundsPerSecond,
                String.format("%.1f", intervalNanos / 1_000_000.0));
    }

    /**
     * Called once per round at the throttle boundary. On the first call the internal clock
     * is started and the method returns immediately. On subsequent calls, if execution is
     * ahead of the expected schedule, the calling thread is parked until the next round is due.
     */
    public void acquire() {
        if (startTimeNanos == 0) {
            startTimeNanos = System.nanoTime();
            return;
        }
        roundsCompleted++;
        final long expectedElapsedNanos = (long) (roundsCompleted * intervalNanos);
        final long actualElapsedNanos = System.nanoTime() - startTimeNanos;
        final long sleepNanos = expectedElapsedNanos - actualElapsedNanos;

        if (sleepNanos > MIN_SLEEP_NANOS) {
            LockSupport.parkNanos(sleepNanos);
        }
    }
}
