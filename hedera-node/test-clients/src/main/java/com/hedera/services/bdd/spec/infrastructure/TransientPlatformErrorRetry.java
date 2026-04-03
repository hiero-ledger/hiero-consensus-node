// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;

import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

/**
 * Encapsulates the retry strategy for transient platform errors returned during
 * transaction submission and query execution.
 *
 * <p>{@link ResponseCodeEnum#PLATFORM_NOT_ACTIVE} can persist for several seconds
 * when the platform transitions to {@code CHECKING} under heavy concurrent load
 * (no self-event reaches consensus within {@code activeStatusDelay}, default 10 s).
 * A time-based retry with exponential backoff is used instead of a fixed retry count
 * so that the test client rides out these transient windows.
 *
 * <p>Other transient errors ({@link ResponseCodeEnum#BUSY},
 * {@link ResponseCodeEnum#PLATFORM_TRANSACTION_NOT_CREATED}) are short-lived and
 * use a simple count-based retry with fixed backoff.
 */
public final class TransientPlatformErrorRetry {

    /** Maximum wall-clock time to keep retrying PLATFORM_NOT_ACTIVE. */
    public static final long PLATFORM_NOT_ACTIVE_TIMEOUT_MS = 10_000;

    /** Initial backoff for PLATFORM_NOT_ACTIVE, doubles each attempt up to {@link #MAX_BACKOFF_MS}. */
    private static final long INITIAL_BACKOFF_MS = 100;
    /** Maximum backoff interval for PLATFORM_NOT_ACTIVE retries. */
    private static final long MAX_BACKOFF_MS = 2_000;
    /** Maximum retries for BUSY / PLATFORM_TRANSACTION_NOT_CREATED. */
    private static final int MAX_OTHER_TRANSIENT_RETRIES = 10;
    /** Fixed backoff for BUSY / PLATFORM_TRANSACTION_NOT_CREATED. */
    private static final long OTHER_TRANSIENT_BACKOFF_MS = 100;

    private TransientPlatformErrorRetry() {}

    /**
     * The result of evaluating whether a transient error should be retried.
     *
     * @param shouldRetry  {@code true} if the caller should retry
     * @param sleepMs      how long to sleep before the next attempt
     * @param firstSeenMs  the wall-clock timestamp when PLATFORM_NOT_ACTIVE was first
     *                     observed (pass back into the next {@link #evaluate} call;
     *                     0 if not applicable)
     */
    public record RetryDecision(boolean shouldRetry, long sleepMs, long firstSeenMs) {}

    /** A decision indicating no retry should be attempted. */
    public static final RetryDecision NO_RETRY = new RetryDecision(false, 0, 0);

    /**
     * Evaluates whether the given precheck code warrants an automatic retry and,
     * if so, computes the backoff duration.
     *
     * @param precheck               the precheck status returned by the node
     * @param retryCount             1-based retry counter maintained by the caller
     * @param platformNotActiveStart the {@link System#currentTimeMillis()} when the first
     *                               PLATFORM_NOT_ACTIVE was observed (0 if not yet seen)
     * @param nowMs                  the current {@link System#currentTimeMillis()}
     * @return a {@link RetryDecision} indicating whether to retry and how long to sleep
     */
    public static RetryDecision evaluate(
            final ResponseCodeEnum precheck,
            final int retryCount,
            final long platformNotActiveStart,
            final long nowMs) {
        // Disabled to surface PLATFORM_NOT_ACTIVE without client masking while validating
        // SubProcessNetwork platformStatus.activeStatusDelay; restore before merge.
        // if (precheck == ResponseCodeEnum.PLATFORM_NOT_ACTIVE) {
        //     final long firstSeen = platformNotActiveStart == 0 ? nowMs : platformNotActiveStart;
        //     final boolean shouldRetry = (nowMs - firstSeen) < PLATFORM_NOT_ACTIVE_TIMEOUT_MS;
        //     final long sleepMs = computePlatformNotActiveBackoffMs(retryCount);
        //     return new RetryDecision(shouldRetry, sleepMs, firstSeen);
        // }
        if (precheck == PLATFORM_TRANSACTION_NOT_CREATED || precheck == BUSY) {
            final boolean shouldRetry = retryCount < MAX_OTHER_TRANSIENT_RETRIES;
            return new RetryDecision(shouldRetry, OTHER_TRANSIENT_BACKOFF_MS, 0);
        }
        return NO_RETRY;
    }

    /**
     * Computes the exponential backoff sleep duration for a PLATFORM_NOT_ACTIVE retry.
     * Sequence: 100, 200, 400, 800, 1600, 2000, 2000, …
     *
     * @param retryAttempt 1-based retry attempt number
     * @return sleep duration in milliseconds
     */
    public static long computePlatformNotActiveBackoffMs(final int retryAttempt) {
        // Shift range 0..4 gives multipliers 1, 2, 4, 8, 16 → 100..1600,
        // then shift 5 gives 3200 which is clamped to MAX_BACKOFF_MS (2000).
        final int shift = Math.min(retryAttempt - 1, 5);
        return Math.min(INITIAL_BACKOFF_MS * (1L << shift), MAX_BACKOFF_MS);
    }
}
