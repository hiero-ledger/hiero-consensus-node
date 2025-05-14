// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;
import static com.swirlds.base.units.UnitConstants.SECONDS_TO_NANOSECONDS;

/**
 * Responsible for throttling transaction by given limit units. Uses a {@link DiscreteLeakyBucket} under the
 * hood. Calculates the amount of capacity that should be leaked from the bucket based on the amount of
 * elapsed nanoseconds since the last time {@link LeakyBucketThrottle#allow(long, long)} was
 * called.
 * This class also alternatively handles burst capabilities where multiple seconds can share capacity.
 */
public class LeakyBucketThrottle {
    private static final long TIME_TO_EMPTY = SECONDS_TO_NANOSECONDS;
    public static final int DEFAULT_BURST_SECONDS = 1;

    private final DiscreteLeakyBucket bucket;
    private long lastAllowedUnits = 0L;
    private final int burstSeconds;

    /**
     * Creates an instance of the throttle with the specified capacity.
     *
     * @param capacity - the capacity for the throttle
     */
    public LeakyBucketThrottle(final long capacity) {
        this(capacity, DEFAULT_BURST_SECONDS);
    }

    public LeakyBucketThrottle(final long capacity, final int burstSeconds) {
        validateCapacityForRequested(capacity, burstSeconds);
        this.burstSeconds = burstSeconds;
        this.bucket = new DiscreteLeakyBucket(capacity * burstSeconds);
    }

    private void validateCapacityForRequested(final long capacity, final int burstSeconds) {
        if (productWouldOverflow(capacity, burstSeconds)) {
            throw new IllegalArgumentException("Base bucket capacity calculation outside numeric range");
        }
    }

    /**
     * Calculates and leaks the amount of capacity that should be leaked from the bucket based on the
     * amount of nanoseconds passed as input argument. Verifies whether there is enough capacity to
     * handle a transaction with the specified limit. Reserves the capacity needed for the
     * transaction if there is enough free space.
     *
     * @param txLimit - the limit amount of the transaction
     * @param elapsedNanos - the amount of time passed since the last call
     * @return true if there is enough capacity, false if the transaction should be throttled
     */
    public boolean allow(final long txLimit, final long elapsedNanos) {
        leakFor(elapsedNanos);
        if (bucket.capacityFree() >= txLimit) {
            bucket.useCapacity(txLimit);
            lastAllowedUnits += txLimit;
            return true;
        } else {
            return false;
        }
    }

    void leakFor(final long elapsedNanos) {
        bucket.leak(effectiveLeak(elapsedNanos));
    }

    /**
     * Returns the percent of the throttle bucket's capacity that is used, given some number of
     * nanoseconds have elapsed since the last capacity test.
     *
     * @param givenElapsedNanos time since last test
     * @return the percent of the bucket that is used
     */
    double percentUsed(final long givenElapsedNanos) {
        final var used = bucket.capacityUsed();
        return 100.0 * (used - Math.min(used, effectiveLeak(givenElapsedNanos))) / bucket.totalCapacity();
    }

    /**
     * Returns the percent of the throttle bucket's capacity that is used, as of the last throttling decision.
     *
     * @return the percent of the bucket that is used
     */
    double instantaneousPercentUsed() {
        return 100.0 * bucket.capacityUsed() / bucket.totalCapacity();
    }

    /**
     * Returns the approximate ratio of free-to-used capacity in the underlying bucket; if there is
     * no capacity used, returns {@code Long.MAX_VALUE}.
     *
     * @return the free-to-used ratio
     */
    public long freeToUsedRatio() {
        final var used = bucket.capacityUsed();
        return (used == 0) ? Long.MAX_VALUE : bucket.capacityFree() / used;
    }

    void resetLastAllowedUse() {
        lastAllowedUnits = 0;
    }

    void reclaimLastAllowedUse() {
        bucket.leak(lastAllowedUnits);
        lastAllowedUnits = 0;
    }

    /**
     * Returns an instance of the {@link DiscreteLeakyBucket} used under the hood.
     *
     * @return an instance of the {@link DiscreteLeakyBucket} used under the hood
     */
    public DiscreteLeakyBucket bucket() {
        return bucket;
    }

    private long effectiveLeak(final long elapsedNanos) {
        if (elapsedNanos >= TIME_TO_EMPTY) {
            return bucket.totalCapacity();
        } else {
            return productWouldOverflow(elapsedNanos, bucket.totalCapacity())
                    ? Long.MAX_VALUE / TIME_TO_EMPTY
                    : elapsedNanos * bucket.totalCapacity() / burstSeconds / TIME_TO_EMPTY;
        }
    }
}
