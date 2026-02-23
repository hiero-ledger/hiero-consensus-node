// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;
import static com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle.nanosBetween;
import static com.swirlds.base.units.UnitConstants.SECONDS_TO_NANOSECONDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Main class responsible for throttling the transactions by "ops duration".
 * Keeps track of the instance the last decision was made and calculates the time elapsed since then.
 * It uses a {@link DiscreteLeakyBucket} underneath of a specified nominal capacity and a brimful capacity of Long.MAX_VALUE.
 */
public class OpsDurationDeterministicThrottle implements CongestibleThrottle {
    private final String throttleName;
    private final long capacityFreedPerSecond;
    private final DiscreteLeakyBucket bucket;
    private Instant lastDecisionTime;

    public OpsDurationDeterministicThrottle(
            final String name, final long nominalCapacity, final long capacityFreedPerSecond) {
        this.throttleName = name;
        this.capacityFreedPerSecond = capacityFreedPerSecond;
        this.bucket = DiscreteLeakyBucket.ofNominalAndBrimfulCapacity(nominalCapacity, Long.MAX_VALUE);
    }

    /**
     * Calculates the amount of nanoseconds that elapsed since the last time the method was called and leaks
     * an appropriate amount of capacity. Then consumes {@param unitsToConsume}.
     */
    public void useCapacity(@NonNull final Instant now, final long unitsToConsume) {
        final var elapsedNanos = nanosBetween(lastDecisionTime, now);
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("Throttle timeline must advance, but " + now + " is not after " + now);
        }
        lastDecisionTime = now;

        bucket.leak(effectiveLeak(elapsedNanos));
        bucket.useCapacity(Math.min(bucket.brimfulCapacityFree(), unitsToConsume));
    }

    /**
     * Returns the available free capacity of this throttle, at a time which may be later than the last
     * throttling decision (which would imply some capacity has been freed).
     */
    public long capacityFree(@NonNull final Instant now) {
        if (lastDecisionTime == null) {
            return bucket.nominalCapacityFree();
        }
        final var elapsedNanos = Math.max(0, nanosBetween(lastDecisionTime, now));
        return Math.max(0, bucket.nominalCapacity() - capacityUsed(elapsedNanos));
    }

    /**
     * Returns the used capacity of this throttle, at a time which may be later than the last
     * throttling decision (which would imply some capacity has been freed).
     */
    public long capacityUsed(final long givenElapsedNanos) {
        final var used = bucket.capacityUsed();
        return (used - Math.min(used, effectiveLeak(givenElapsedNanos)));
    }

    @Override
    public double instantaneousPercentUsed() {
        if (lastDecisionTime == null) {
            return 0.0;
        }
        return 100.0 * bucket.capacityUsed() / bucket.nominalCapacity();
    }

    @Override
    public long capacity() {
        return bucket.nominalCapacity();
    }

    @Override
    public long mtps() {
        return 0; // unused
    }

    @Override
    public String name() {
        return throttleName;
    }

    @Override
    public long used() {
        return bucket.capacityUsed();
    }

    public ThrottleUsageSnapshot usageSnapshot() {
        return new ThrottleUsageSnapshot(
                bucket.capacityUsed(),
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
        bucket.resetUsed(usageSnapshot.used());
    }

    private long effectiveLeak(final long elapsedNanos) {
        return productWouldOverflow(elapsedNanos, capacityFreedPerSecond)
                ? Long.MAX_VALUE / SECONDS_TO_NANOSECONDS
                : elapsedNanos * capacityFreedPerSecond / SECONDS_TO_NANOSECONDS;
    }
}
