// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * A throttle with milli-TPS resolution that exists in a deterministic timeline.
 */
public class DeterministicThrottle implements CongestibleThrottle {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    @Nullable
    private final String name;

    @Nullable
    private Timestamp lastDecisionTime;

    private final BucketThrottle delegate;

    public static DeterministicThrottle withTps(final int tps) {
        return new DeterministicThrottle(BucketThrottle.withTps(tps), null);
    }

    public static DeterministicThrottle withTpsNamed(final int tps, final String name) {
        return new DeterministicThrottle(BucketThrottle.withTps(tps), name);
    }

    public static DeterministicThrottle withMtps(final long mtps) {
        return new DeterministicThrottle(BucketThrottle.withMtps(mtps), null);
    }

    public static DeterministicThrottle withMtpsNamed(final long mtps, final String name) {
        return new DeterministicThrottle(BucketThrottle.withMtps(mtps), name);
    }

    public static DeterministicThrottle withTpsAndBurstPeriod(final int tps, final int burstPeriod) {
        return new DeterministicThrottle(BucketThrottle.withTpsAndBurstPeriod(tps, burstPeriod), null);
    }

    public static DeterministicThrottle withTpsAndBurstPeriodNamed(
            final int tps, final int burstPeriod, final String name) {
        return new DeterministicThrottle(BucketThrottle.withTpsAndBurstPeriod(tps, burstPeriod), name);
    }

    public static DeterministicThrottle withMtpsAndBurstPeriod(final long mtps, final int burstPeriod) {
        return new DeterministicThrottle(BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod), null);
    }

    public static DeterministicThrottle withMtpsAndBurstPeriodNamed(
            final long mtps, final int burstPeriod, final String name) {
        return new DeterministicThrottle(BucketThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod), name);
    }

    public static DeterministicThrottle withTpsAndBurstPeriodMs(final int tps, final long burstPeriodMs) {
        return new DeterministicThrottle(BucketThrottle.withTpsAndBurstPeriodMs(tps, burstPeriodMs), null);
    }

    public static DeterministicThrottle withTpsAndBurstPeriodMsNamed(
            final int tps, final long burstPeriodMs, final String name) {
        return new DeterministicThrottle(BucketThrottle.withTpsAndBurstPeriodMs(tps, burstPeriodMs), name);
    }

    public static DeterministicThrottle withMtpsAndBurstPeriodMs(final long mtps, final long burstPeriodMs) {
        return new DeterministicThrottle(BucketThrottle.withMtpsAndBurstPeriodMs(mtps, burstPeriodMs), null);
    }

    public static DeterministicThrottle withMtpsAndBurstPeriodMsNamed(
            final long mtps, final long burstPeriodMs, final String name) {
        return new DeterministicThrottle(BucketThrottle.withMtpsAndBurstPeriodMs(mtps, burstPeriodMs), name);
    }

    private DeterministicThrottle(final BucketThrottle delegate, @Nullable final String name) {
        this.name = name;
        this.delegate = delegate;
        lastDecisionTime = null;
    }

    public static long capacityRequiredFor(final int nTransactions) {
        if (productWouldOverflow(nTransactions, BucketThrottle.capacityUnitsPerTxn())) {
            return -1;
        }
        return nTransactions * BucketThrottle.capacityUnitsPerTxn();
    }

    public long clampedCapacityRequiredFor(final int nTransactions) {
        final var nominal = capacityRequiredFor(nTransactions);
        final var limit = delegate.bucket().totalCapacity();
        return (nominal >= 0) ? Math.min(nominal, limit) : limit;
    }

    /**
     * Determines whether a given number of requests can be allowed through the throttle, given the current time.
     * (I.e., without leaking any capacity from the bucket before testing for available space.)
     *
     * @param numReqs the number of requests to allow
     * @return whether the requests can be allowed
     */
    public boolean allowInstantaneous(final int numReqs) {
        return delegate.allowInstantaneous(numReqs);
    }

    /**
     * Leaks a given number of requests from the bucket.
     * @param numReqs the number of requests to leak
     */
    public void leakInstantaneous(final int numReqs) {
        delegate.leakInstantaneous(numReqs);
    }

    /**
     * Determines whether a given number of requests can be allowed through the throttle, given the current time.
     *
     * @param numReqs the number of requests to allow
     * @param now the time at which the requests are being made
     * @return whether the requests can be allowed
     */
    public boolean allow(final int numReqs, @NonNull final Instant now) {
        requireNonNull(now);
        final var elapsedNanos = nanosBetween(lastDecisionTime, now);
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("Throttle timeline must advance, but " + now + " is not after "
                    + Instant.ofEpochSecond(lastDecisionTime.seconds(), lastDecisionTime.nanos()));
        }
        lastDecisionTime = new Timestamp(now.getEpochSecond(), now.getNano());
        return delegate.allow(numReqs, elapsedNanos);
    }

    /**
     * Leaks a given amount of capacity from the bucket. Useful for refunding capacity from an operation
     * that was allowed through a throttle; but then failed later.
     *
     * @param amount the amount of capacity to leak
     */
    public void leakCapacity(final long amount) {
        delegate.leakCapacity(amount);
    }

    /**
     * Leaks capacity from the bucket equal to the last allowed use.
     */
    public void reclaimLastAllowedUse() {
        delegate.reclaimLastAllowedUse();
    }

    /**
     * Resets the last allowed use to zero.
     */
    public void resetLastAllowedUse() {
        delegate.resetLastAllowedUse();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long mtps() {
        return delegate.mtps();
    }

    @Override
    public long used() {
        return delegate.bucket().capacityUsed();
    }

    @Override
    public long capacity() {
        return delegate.bucket().totalCapacity();
    }

    public long capacityFree() {
        return delegate.bucket().capacityFree();
    }

    public ThrottleUsageSnapshot usageSnapshot() {
        final var bucket = delegate.bucket();
        return new ThrottleUsageSnapshot(bucket.capacityUsed(), lastDecisionTime);
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
     * Returns the percent usage of this throttle, at the time of the last throttling decision, or
     * zero if no throttling decision has been made.
     *
     * @return the percent usage at the time of the last throttling decision
     */
    @Override
    public double instantaneousPercentUsed() {
        if (lastDecisionTime == null) {
            return 0.0;
        }
        return delegate.instantaneousPercentUsed();
    }

    /**
     * Resets the usage of this throttle to the state of a prior snapshot.
     *
     * @param usageSnapshot the snapshot to reset to
     */
    public void resetUsageTo(@NonNull final ThrottleUsageSnapshot usageSnapshot) {
        requireNonNull(usageSnapshot);
        final var bucket = delegate.bucket();
        lastDecisionTime = usageSnapshot.lastDecisionTime();
        bucket.resetUsed(usageSnapshot.used());
    }

    public void resetUsage() {
        resetLastAllowedUse();
        final var bucket = delegate.bucket();
        bucket.resetUsed(0L);
        lastDecisionTime = null;
    }

    /* NOTE: The Object methods below are only overridden to improve readability of unit tests; instances
    of this class are not used in hash-based collections */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null || this.getClass() != obj.getClass()) {
            return false;
        }

        final var that = (DeterministicThrottle) obj;

        return this.delegate.bucket().totalCapacity() == that.delegate.bucket().totalCapacity()
                && this.delegate.mtps() == that.delegate.mtps();
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate.bucket().totalCapacity(), delegate.mtps(), name, lastDecisionTime);
    }

    @Override
    public String toString() {
        final var sb = new StringBuilder("DeterministicThrottle{");
        if (name != null) {
            sb.append("name='").append(name).append("', ");
        }
        return sb.append("mtps=")
                .append(delegate.mtps())
                .append(", ")
                .append("capacity=")
                .append(capacity())
                .append(" (used=")
                .append(used())
                .append(")")
                .append(
                        lastDecisionTime == null
                                ? ""
                                : (", last decision @ "
                                        + Instant.ofEpochSecond(lastDecisionTime.seconds(), lastDecisionTime.nanos())))
                .append("}")
                .toString();
    }

    public BucketThrottle delegate() {
        return delegate;
    }

    public Timestamp lastDecisionTime() {
        return lastDecisionTime;
    }

    /**
     * Returns the number of nanoseconds between two points in time. If the first point is missing (null),
     * then the result is zero.
     *
     * <p>Takes a {@link Timestamp} for the starting point and an {@link Instant} for the ending point
     * since we are always comparing time elapsed between a {@link #lastDecisionTime} (represented in
     * state by the {@link Timestamp} in a {@link ThrottleUsageSnapshot}) to a current time obtained
     * from the system clock or the platform as an {@link Instant}.
     *
     * @param start the start time
     * @param end the end time
     * @return the number of nanoseconds between the two times, or zero if the start time is missing
     */
    static long nanosBetween(@Nullable final Timestamp start, @NonNull final Instant end) {
        requireNonNull(end);
        if (start == null) {
            return 0L;
        }
        final var elapsedSeconds = Math.subtractExact(end.getEpochSecond(), start.seconds());
        final var elapsedNanos = Math.multiplyExact(elapsedSeconds, NANOS_PER_SECOND);
        return Math.addExact(elapsedNanos, end.getNano() - start.nanos());
    }
}
