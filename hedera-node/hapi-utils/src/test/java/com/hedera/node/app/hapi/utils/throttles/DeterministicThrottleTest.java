// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.throttles.BucketThrottle.CAPACITY_UNITS_PER_NANO_TXN;
import static com.hedera.node.app.hapi.utils.throttles.BucketThrottle.CAPACITY_UNITS_PER_TXN;
import static com.hedera.node.app.hapi.utils.throttles.BucketThrottle.MTPS_PER_TPS;
import static com.hedera.node.app.hapi.utils.throttles.BucketThrottle.NTPS_PER_MTPS;
import static com.hedera.node.app.hapi.utils.throttles.BucketThrottleTest.NTPS_PER_TPS;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeterministicThrottleTest {
    @Test
    @SuppressWarnings("java:S5961")
    void factoriesWork() {
        final int tps = 1_000;
        final long expectedCapacity = tps * NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN;
        final String name = "t6e";

        final var fromTps = DeterministicThrottle.withTps(tps);
        final var fromMtps = DeterministicThrottle.withMtps(tps * MTPS_PER_TPS);
        final var fromTpsAndBurstPeriod = DeterministicThrottle.withTpsAndBurstPeriod(tps / 2, 2);
        final var fromMtpsAndBurstPeriod = DeterministicThrottle.withMtpsAndBurstPeriod(tps / 2 * MTPS_PER_TPS, 2);
        final var fromTpsNamed = DeterministicThrottle.withTpsNamed(tps, name);
        final var fromMtpsNamed = DeterministicThrottle.withMtpsNamed(tps * MTPS_PER_TPS, name);
        final var fromTpsAndBurstPeriodNamed = DeterministicThrottle.withTpsAndBurstPeriodNamed(tps / 2, 2, name);
        final var fromMtpsAndBurstPeriodNamed =
                DeterministicThrottle.withMtpsAndBurstPeriodNamed(tps / 2 * MTPS_PER_TPS, 2, name);
        final var fromTpsAndBurstPeriodMs = DeterministicThrottle.withTpsAndBurstPeriodMs(tps / 2, 2_000);
        final var fromMtpsAndBurstPeriodMs =
                DeterministicThrottle.withMtpsAndBurstPeriodMs(tps / 2 * MTPS_PER_TPS, 2_000);
        final var fromTpsAndBurstPeriodMsNamed =
                DeterministicThrottle.withTpsAndBurstPeriodMsNamed(tps / 2, 2_000, name);
        final var fromMtpsAndBurstPeriodMsNamed =
                DeterministicThrottle.withMtpsAndBurstPeriodMsNamed(tps / 2 * MTPS_PER_TPS, 2_000, name);

        assertEquals(expectedCapacity, fromTps.delegate().bucket().brimfulCapacity());
        assertEquals(expectedCapacity, fromMtps.delegate().bucket().brimfulCapacity());
        assertEquals(expectedCapacity, fromTpsAndBurstPeriod.delegate().bucket().brimfulCapacity());
        assertEquals(
                expectedCapacity, fromTpsAndBurstPeriodMs.delegate().bucket().brimfulCapacity());
        assertEquals(
                expectedCapacity, fromMtpsAndBurstPeriod.delegate().bucket().brimfulCapacity());
        assertEquals(
                expectedCapacity, fromMtpsAndBurstPeriodMs.delegate().bucket().brimfulCapacity());
        assertEquals(expectedCapacity, fromTpsNamed.delegate().bucket().brimfulCapacity());
        assertEquals(expectedCapacity, fromMtpsNamed.delegate().bucket().brimfulCapacity());
        assertEquals(
                expectedCapacity, fromTpsAndBurstPeriodNamed.delegate().bucket().brimfulCapacity());
        assertEquals(
                expectedCapacity,
                fromTpsAndBurstPeriodMsNamed.delegate().bucket().brimfulCapacity());
        assertEquals(
                expectedCapacity,
                fromMtpsAndBurstPeriodNamed.delegate().bucket().brimfulCapacity());
        assertEquals(
                expectedCapacity,
                fromMtpsAndBurstPeriodMsNamed.delegate().bucket().brimfulCapacity());

        assertEquals(tps * MTPS_PER_TPS, fromTps.mtps());
        assertEquals(tps * MTPS_PER_TPS, fromMtps.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromTpsAndBurstPeriod.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromTpsAndBurstPeriodMs.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromMtpsAndBurstPeriod.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromMtpsAndBurstPeriodMs.mtps());
        assertEquals(tps * MTPS_PER_TPS, fromTpsNamed.mtps());
        assertEquals(tps * MTPS_PER_TPS, fromMtpsNamed.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromTpsAndBurstPeriodNamed.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromTpsAndBurstPeriodMsNamed.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromMtpsAndBurstPeriodNamed.mtps());
        assertEquals(tps * MTPS_PER_TPS / 2, fromMtpsAndBurstPeriodMsNamed.mtps());

        assertNull(fromTps.lastDecisionTime());
        assertNull(fromMtps.lastDecisionTime());
        assertNull(fromTpsAndBurstPeriod.lastDecisionTime());
        assertNull(fromTpsAndBurstPeriodMs.lastDecisionTime());
        assertNull(fromMtpsAndBurstPeriod.lastDecisionTime());
        assertNull(fromMtpsAndBurstPeriodMs.lastDecisionTime());
        assertNull(fromTpsNamed.lastDecisionTime());
        assertNull(fromMtpsNamed.lastDecisionTime());
        assertNull(fromTpsAndBurstPeriodNamed.lastDecisionTime());
        assertNull(fromTpsAndBurstPeriodMsNamed.lastDecisionTime());
        assertNull(fromMtpsAndBurstPeriodNamed.lastDecisionTime());
        assertNull(fromMtpsAndBurstPeriodMsNamed.lastDecisionTime());

        assertEquals(name, fromTpsNamed.name());
        assertEquals(name, fromMtpsNamed.name());
        assertEquals(name, fromTpsAndBurstPeriodNamed.name());
        assertEquals(name, fromMtpsAndBurstPeriodNamed.name());
        assertEquals(name, fromTpsAndBurstPeriodMsNamed.name());
        assertEquals(name, fromMtpsAndBurstPeriodMsNamed.name());
    }

    @Test
    void capacityReqReturnsMinusOneOnOverflow() {
        final var overflowTxns = (int) (Long.MAX_VALUE / BucketThrottle.capacityUnitsPerTxn() + 1);

        assertEquals(-1, DeterministicThrottle.capacityRequiredFor(overflowTxns));
    }

    @Test
    void canGetPercentUsed() {
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(500, 4);
        assertEquals(0.0, subject.percentUsed(now));
        subject.allow(1, now.plusNanos(1));
        assertEquals(50.0, subject.percentUsed(now));
        assertEquals(50.0, subject.percentUsed(now.minusNanos(123)));
    }

    @Test
    void canGetInstantaneousPercentUsed() {
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(500, 4);
        assertEquals(0.0, subject.instantaneousPercentUsed());
        subject.allow(1, now.plusNanos(1));
        assertEquals(50.0, subject.instantaneousPercentUsed());
    }

    @Test
    void throttlesWithinPermissibleTolerance() throws InterruptedException {
        final long mtps = 123_456L;
        final var subject = DeterministicThrottle.withMtps(mtps);
        final double expectedTps = (1.0 * mtps) / 1_000;
        subject.resetUsageTo(
                new ThrottleUsageSnapshot(subject.capacity() - DeterministicThrottle.capacityRequiredFor(1), null));

        final var helper = new ConcurrentThrottleTestHelper(10, 10, 2);
        helper.runWith(subject);

        helper.assertTolerableTps(expectedTps, 1.00);
    }

    @Test
    void usesZeroElapsedNanosOnFirstDecision() {
        final int tps = 1;
        final int burstPeriod = 5;
        final long internalCapacity = tps * NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN * burstPeriod;
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var subject = DeterministicThrottle.withTpsAndBurstPeriod(tps, burstPeriod);

        final var result = subject.allow(1, now);

        assertTrue(result);
        assertEquals(now, subject.lastDecisionTime());
        assertEquals(
                internalCapacity - CAPACITY_UNITS_PER_TXN,
                subject.delegate().bucket().brimfulCapacityFree());
    }

    @Test
    void requiresMonotonicIncreasingTimeline() {
        final int tps = 1;
        final int burstPeriod = 5;
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var subject = DeterministicThrottle.withTpsAndBurstPeriod(tps, burstPeriod);

        subject.allow(1, now);

        final var current = now.minusNanos(1);
        assertThrows(IllegalArgumentException.class, () -> subject.allow(1, current));
        assertDoesNotThrow(() -> subject.allow(1, now));
    }

    @Test
    void computesClampedRequiredCapacity() {
        final int tps = 10;
        final int burstPeriod = 1;

        final var subject = DeterministicThrottle.withTpsAndBurstPeriod(tps, burstPeriod);

        final var requiredPartialCapacity = subject.clampedCapacityRequiredFor(5);
        assertEquals(CAPACITY_UNITS_PER_TXN * 5, requiredPartialCapacity);

        final var totalCapacity = subject.delegate().bucket().brimfulCapacity();
        final var requiredClampedCapacity = subject.clampedCapacityRequiredFor(500);
        final var requiredSillyCapacity = subject.clampedCapacityRequiredFor(Integer.MAX_VALUE);
        assertEquals(totalCapacity, requiredClampedCapacity);
        assertEquals(totalCapacity, requiredSillyCapacity);
    }

    @Test
    void usesCorrectElapsedNanosOnSubsequentDecision() {
        final int tps = 1;
        final int burstPeriod = 5;
        final long internalCapacity = tps * NTPS_PER_TPS * CAPACITY_UNITS_PER_NANO_TXN * burstPeriod;
        final long elapsedNanos = 1_234;
        final var originalDecision = Instant.ofEpochSecond(1_234_567L, 0);
        final var now = Instant.ofEpochSecond(1_234_567L, elapsedNanos);
        final var subject = DeterministicThrottle.withTpsAndBurstPeriod(tps, burstPeriod);

        subject.allow(1, originalDecision);
        final var result = subject.allow(1, now);

        assertTrue(result);
        assertEquals(now, subject.lastDecisionTime());
        assertEquals(
                internalCapacity - 2 * CAPACITY_UNITS_PER_TXN + 1_000 * elapsedNanos,
                subject.delegate().bucket().brimfulCapacityFree());
    }

    @Test
    void returnsExpectedState() {
        final int mtps = 333;
        final int burstPeriod = 6;
        final var originalDecision = Instant.ofEpochSecond(1_234_567L, 0);
        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        final var capacity = subject.capacity();

        subject.allow(1, originalDecision);
        final var state = subject.usageSnapshot();

        assertEquals(CAPACITY_UNITS_PER_TXN, state.used());
        assertEquals(capacity - CAPACITY_UNITS_PER_TXN, subject.capacityFree());
        assertEquals(originalDecision, instantFrom(requireNonNull(state.lastDecisionTime())));
    }

    @Test
    void canLeakSpecificAmounts() {
        final int mtps = 333;
        final int burstPeriod = 6;
        final var originalDecision = Instant.ofEpochSecond(1_234_567L, 0);
        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);

        subject.allow(1, originalDecision);
        final var preLeakState = subject.usageSnapshot();
        subject.leakCapacity(preLeakState.used() / 2);
        final var postLeakState = subject.usageSnapshot();
        assertEquals(preLeakState.used() / 2, postLeakState.used());
    }

    @Test
    void returnsExpectedInstantaneousState() {
        final int mtps = 333;
        final int burstPeriod = 666;
        final var originalDecision = Instant.ofEpochSecond(1_234_567L, 0);

        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        final var comparisonSubject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);

        // Initialize decision time without taking capacity
        subject.allow(mtps + 1, originalDecision);
        assertTrue(subject.allowInstantaneous(1));
        assertTrue(subject.allowInstantaneous(1));
        assertTrue(comparisonSubject.allow(2, originalDecision));

        final var state = subject.usageSnapshot();
        final var comparisonState = comparisonSubject.usageSnapshot();
        assertEquals(comparisonState, state);
    }

    @Test
    void leaksAsExpected() {
        final int mtps = 333;
        final int burstPeriod = 6;
        final var originalDecision = Instant.ofEpochSecond(1_234_567L, 0);
        final var nextDecision = originalDecision.plusNanos(1000);
        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);

        subject.allow(1, originalDecision);
        subject.allow(mtps + 1, nextDecision);

        final var state = subject.usageSnapshot();

        assertEquals(999999667000L, state.used());
        assertEquals(nextDecision, instantFrom(requireNonNull(state.lastDecisionTime())));
    }

    @Test
    void resetsAsExpected() {
        final int mtps = 333;
        final int burstPeriod = 6;
        final long internalCapacity = mtps * NTPS_PER_MTPS * CAPACITY_UNITS_PER_NANO_TXN * burstPeriod;
        final long used = internalCapacity / 2;
        final var originalDecision = new Timestamp(1_234_567L, 0);
        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        final var snapshot = new ThrottleUsageSnapshot(used, originalDecision);

        subject.resetUsageTo(snapshot);

        assertEquals(used, subject.delegate().bucket().capacityUsed());
        assertEquals(asInstant(originalDecision), subject.lastDecisionTime());
    }

    @Test
    void reclaimsAsExpected() {
        final int mtps = 333;
        final int burstPeriod = 6;
        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        subject.allow(1, Instant.now());
        subject.allow(1, Instant.now());
        subject.allow(1, Instant.now());

        subject.reclaimLastAllowedUse();

        assertEquals(0, subject.delegate().bucket().capacityUsed());
    }

    @Test
    void resetsReclaimAsExpected() {
        final int mtps = 333;
        final int burstPeriod = 6;
        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        subject.allow(1, Instant.now());
        subject.allow(1, Instant.now());
        subject.allow(1, Instant.now());

        final var capacityUsedAfter = subject.delegate().bucket().capacityUsed();

        assertTrue(capacityUsedAfter > 0);

        subject.resetLastAllowedUse();
        subject.reclaimLastAllowedUse();

        assertEquals(capacityUsedAfter, subject.delegate().bucket().capacityUsed());
    }

    @Test
    void resetsUsageAsExpected() {
        final int mtps = 333;
        final int burstPeriod = 6;
        final var subject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        subject.allow(1, Instant.now());
        subject.allow(1, Instant.now());
        subject.allow(1, Instant.now());

        subject.resetUsage();
        subject.reclaimLastAllowedUse();

        assertEquals(0, subject.delegate().bucket().capacityUsed());
        assertNull(subject.lastDecisionTime());
    }

    @Test
    void toStringWorks() {
        final int mtps = 333;
        final int burstPeriod = 6;
        final long internalCapacity = mtps * NTPS_PER_MTPS * CAPACITY_UNITS_PER_NANO_TXN * burstPeriod;
        final var originalDecision = Instant.ofEpochSecond(1_234_567L, 0);
        final String name = "Testing123";
        final var expectedForAnonymous = String.format(
                "DeterministicThrottle{mtps=%d, capacity=%d (used=%d), last decision @ %s}",
                mtps, internalCapacity, DeterministicThrottle.capacityRequiredFor(1), originalDecision);
        final var expectedForDisclosed = String.format(
                "DeterministicThrottle{name='%s', mtps=%d, capacity=%d (used=%d), last" + " decision @ %s}",
                name, mtps, internalCapacity, DeterministicThrottle.capacityRequiredFor(1), originalDecision);

        final var anonymousSubject = DeterministicThrottle.withMtpsAndBurstPeriod(mtps, burstPeriod);
        final var disclosedSubject = DeterministicThrottle.withMtpsAndBurstPeriodNamed(mtps, burstPeriod, name);
        anonymousSubject.allow(1, originalDecision);
        disclosedSubject.allow(1, originalDecision);

        assertEquals(expectedForAnonymous, anonymousSubject.toString());
        assertEquals(expectedForDisclosed, disclosedSubject.toString());
    }

    @Test
    void snapshotObjectContractMet() {
        final long aUsed = 123;
        final long bUsed = 456;
        final var aLast = new Timestamp(1_234_567L, 890);
        final var bLast = new Timestamp(7_654_321L, 890);
        final var a = new ThrottleUsageSnapshot(aUsed, aLast);

        assertEquals(a, new ThrottleUsageSnapshot(aUsed, aLast));
        assertNotEquals(null, a);
        assertNotEquals(a, new Object());

        assertNotEquals(a, new ThrottleUsageSnapshot(bUsed, aLast));
        assertNotEquals(a, new ThrottleUsageSnapshot(aUsed, bLast));

        assertEquals(a.hashCode(), a.hashCode());
        assertEquals(a.hashCode(), new ThrottleUsageSnapshot(aUsed, aLast).hashCode());
        assertNotEquals(a.hashCode(), new Object().hashCode());
        assertNotEquals(a.hashCode(), new ThrottleUsageSnapshot(bUsed, aLast).hashCode());
        assertNotEquals(a.hashCode(), new ThrottleUsageSnapshot(aUsed, bLast).hashCode());
    }

    @Test
    void hashCodeWorks() {
        final int tps = 1_000;
        final String name = "t6e";

        final var throttle = DeterministicThrottle.withTpsAndBurstPeriodNamed(tps, 2, name);

        final int expectedHashCode = 403047329;
        assertEquals(expectedHashCode, throttle.hashCode());
    }

    @Test
    void equalsWorks() {
        final int tps = 1_000;
        final String name = "t6e";

        final var throttle = DeterministicThrottle.withTpsAndBurstPeriodNamed(tps, 2, name);
        final var throttle1 = DeterministicThrottle.withTpsAndBurstPeriodNamed(tps, 2, name);
        final var bucketThrottle = BucketThrottle.withTps(tps);

        assertEquals(throttle, throttle1);
        final var equalsForcedCallResult = throttle.equals(null);
        assertFalse(equalsForcedCallResult);
        assertNotEquals(throttle, bucketThrottle);
    }

    static Instant instantFrom(@NonNull final Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }
}
