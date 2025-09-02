// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle.nanosBetween;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Main class responsible for throttling transactions by gasLimit. Keeps track of the instance the
 * last decision was made and calculates the time elapsed since then.
 * Uses a {@link LeakyBucketThrottle} under the hood.
 */
public class LeakyBucketDeterministicThrottle implements CongestibleThrottle {
    private final String throttleName;
    private final LeakyBucketThrottle delegate;
    private Instant lastDecisionTime;

    /**
     * Creates a new instance of the throttle with capacity - the total amount of gas allowed per
     * sec.
     *
     * @param capacity - the total amount of gas allowed per sec.
     */
    public LeakyBucketDeterministicThrottle(final long capacity, final String name, final int burstSeconds) {
        this.throttleName = name;
        this.delegate = new LeakyBucketThrottle(capacity, burstSeconds);
    }

    /**
     * Calculates the amount of nanoseconds that elapsed since the last time the method was called.
     * Verifies whether there is enough capacity to handle a transaction with some gasLimit.
     *
     * @param now        - the instant against which the {@link LeakyBucketThrottle} is tested.
     * @param throttleLimit - the throttle limit extracted from the transaction payload.
     * @return true if there is enough capacity to handle this transaction; false if it should be
     * throttled.
     */
    public boolean allow(@NonNull final Instant now, final long throttleLimit) {
        final var elapsedNanos = nanosBetween(lastDecisionTime, now);
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException(
                    "Throttle timeline must advance, but " + now + " is not after " + lastDecisionTime);
        }
        if (throttleLimit < 0) {
            throw new IllegalArgumentException("Throttle limit must be non-negative, but was " + throttleLimit);
        }
        lastDecisionTime = now;
        return delegate.allow(throttleLimit, elapsedNanos);
    }

    /**
     * Returns the free-to-used ratio in the bucket at its last decision time.
     *
     * @return the free-to-used ratio at that time
     */
    public long instantaneousFreeToUsedRatio() {
        return delegate.freeToUsedRatio();
    }

    /**
     * Returns the percent usage of this throttle, at a time which may be later than the last
     * throttling decision (which would imply some capacity has been freed).
     *
     * @param now a time which will be ignored if before the last throttling decision
     * @return the capacity available at this time
     */
    public double percentUsed(final Instant now) {
        if (lastDecisionTime == null) {
            return 0.0;
        }
        final var elapsedNanos = Math.max(0, nanosBetween(lastDecisionTime, now));
        return delegate.percentUsed(elapsedNanos);
    }

    /**
     * Returns the available free capacity of this throttle, at a time which may be later than the last
     * throttling decision (which would imply some capacity has been freed).
     *
     * @param now a time which will be ignored if before the last throttling decision
     * @return the capacity available at this time
     */
    public long capacityFree(@NonNull final Instant now) {
        if (lastDecisionTime == null) {
            return delegate().capacityFree();
        }
        final var elapsedNanos = Math.max(0, nanosBetween(lastDecisionTime, now));
        return delegate.capacityFree(elapsedNanos);
    }

    /**
     * Returns the percent usage of this throttle, at a time which may be later than the last
     * throttling decision (which would imply some capacity has been freed).
     *
     * @return the capacity available at this time
     */
    @Override
    public double instantaneousPercentUsed() {
        if (lastDecisionTime == null) {
            return 0.0;
        }
        return delegate.instantaneousPercentUsed();
    }

    /**
     * Returns the capacity of the throttle.
     *
     * @return the capacity of the throttle
     */
    @Override
    public long capacity() {
        return delegate.bucket().nominalCapacity();
    }

    @Override
    @SuppressWarnings("java:S125")
    public long mtps() {
        // We treat the "milli-TPS" of the throttle bucket as 1000x its gas/sec;
        return capacity() * 1_000;
    }

    @Override
    public String name() {
        return throttleName;
    }

    /**
     * Returns the used capacity of the throttle.
     *
     * @return the used capacity of the throttle
     */
    @Override
    public long used() {
        return delegate.bucket().capacityUsed();
    }

    /**
     * Used to release some capacity previously reserved by calling {@link
     * LeakyBucketDeterministicThrottle#allow(Instant, long)} without having to wait for the natural
     * leakage.
     *
     * @param value - the amount to release
     */
    public void leakUnusedGasPreviouslyReserved(long value) {
        delegate().bucket().leak(value);
    }

    /**
     * returns an instance of the {@link LeakyBucketThrottle} used under the hood.
     *
     * @return - an instance of the {@link LeakyBucketThrottle} used under the hood
     */
    LeakyBucketThrottle delegate() {
        return delegate;
    }

    public ThrottleUsageSnapshot usageSnapshot() {
        return new ThrottleUsageSnapshot(
                delegate.bucket().capacityUsed(),
                lastDecisionTime == null
                        ? null
                        : new Timestamp(lastDecisionTime.getEpochSecond(), lastDecisionTime.getNano()));
    }

    public void resetUsageTo(@NonNull final ThrottleUsageSnapshot usageSnapshot) {
        requireNonNull(usageSnapshot);
        lastDecisionTime = usageSnapshot.lastDecisionTime() == null
                ? null
                : Instant.ofEpochSecond(
                        usageSnapshot.lastDecisionTime().seconds(),
                        usageSnapshot.lastDecisionTime().nanos());
        delegate.bucket().resetUsed(usageSnapshot.used());
    }

    public void resetUsage() {
        resetLastAllowedUse();
        delegate.bucket().resetUsed(0);
    }

    public void reclaimLastAllowedUse() {
        delegate.reclaimLastAllowedUse();
    }

    public void resetLastAllowedUse() {
        delegate.resetLastAllowedUse();
    }
}
