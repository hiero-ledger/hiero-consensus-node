// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static com.hedera.node.app.hapi.utils.throttles.DeterministicThrottleTest.instantFrom;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeakyBucketDeterministicThrottleTest {

    private static final String THROTTLE_NAME = "Gas";
    private static final long DEFAULT_CAPACITY = 1_000_000;
    private static final long ONE_SECOND_IN_NANOSECONDS = 1_000_000_000;

    LeakyBucketDeterministicThrottle subject;

    @BeforeEach
    void setup() {
        subject = new LeakyBucketDeterministicThrottle(DEFAULT_CAPACITY, THROTTLE_NAME, 1);
    }

    @Test
    void usesZeroElapsedNanosOnFirstDecision() {
        // setup:
        long gasLimitForTX = 100_000;
        Instant now = Instant.ofEpochSecond(1_234_567L);

        // when:
        var result = subject.allow(now, gasLimitForTX);

        // then:
        assertTrue(result);
        assertEquals(
                DEFAULT_CAPACITY - gasLimitForTX, subject.delegate().bucket().brimfulCapacityFree());
    }

    @Test
    void implementsCongestibleThrottle() {
        assertEquals(DEFAULT_CAPACITY * 1000, subject.mtps());
        assertEquals(THROTTLE_NAME, subject.name());
    }

    @Test
    void canGetCapacityFree() {
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var capacity = 1_000_000;
        final var subject = new LeakyBucketDeterministicThrottle(capacity, THROTTLE_NAME, 1);
        assertEquals(capacity, subject.capacityFree(now));
        subject.allow(now, capacity / 2);
        assertEquals(capacity / 2, subject.capacityFree(now));
        assertEquals(capacity / 2, subject.capacityFree(now.minusNanos(123)));
    }

    @Test
    void canGetPercentUsed() {
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var capacity = 1_000_000;
        final var subject = new LeakyBucketDeterministicThrottle(capacity, THROTTLE_NAME, 1);
        assertEquals(0.0, subject.percentUsed(now));
        subject.allow(now, capacity / 2);
        assertEquals(50.0, subject.percentUsed(now));
        assertEquals(50.0, subject.percentUsed(now.minusNanos(123)));
    }

    @Test
    void canGetInstantaneousPercentUsed() {
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var capacity = 1_000_000;
        final var subject = new LeakyBucketDeterministicThrottle(capacity, THROTTLE_NAME, 1);
        assertEquals(0.0, subject.instantaneousPercentUsed());
        subject.allow(now, capacity / 2);
        assertEquals(50.0, subject.instantaneousPercentUsed());
    }

    @Test
    void canGetFreeToUsedRatio() {
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var capacity = 1_000_000;
        final var subject = new LeakyBucketDeterministicThrottle(capacity, THROTTLE_NAME, 1);
        subject.allow(now, capacity / 4);
        assertEquals(3, subject.instantaneousFreeToUsedRatio());
    }

    @Test
    void leaksUntilNowBeforeEstimatingFreeToUsed() {
        final var capacity = 1_000_000;
        final var subject = new LeakyBucketDeterministicThrottle(capacity, THROTTLE_NAME, 1);
        assertEquals(Long.MAX_VALUE, subject.instantaneousFreeToUsedRatio());
    }

    @Test
    void throwsOnNegativeGasLimit() {
        final long gasLimitForTX = -1;
        final Instant now = Instant.ofEpochSecond(1_234_567L);
        assertThrows(
                IllegalArgumentException.class, () -> subject.allow(now, gasLimitForTX), "Negative gas should throw");
    }

    @Test
    void requiresMonotonicIncreasingTimeline() {
        // setup:
        long gasLimitForTX = 100_000;
        Instant now = Instant.ofEpochSecond(1_234_567L);
        Instant illegal = now.minusNanos(1);

        // when:
        subject.allow(now, gasLimitForTX);

        // then:
        assertThrows(IllegalArgumentException.class, () -> subject.allow(illegal, gasLimitForTX));
        assertDoesNotThrow(() -> subject.allow(now, gasLimitForTX));
    }

    @Test
    void usesCorrectElapsedNanosOnSubsequentDecision() {
        // setup:
        long gasLimitForTX = 100_000;

        double elapsed = 1_234;
        double toLeak = (elapsed / ONE_SECOND_IN_NANOSECONDS) * DEFAULT_CAPACITY;

        Instant originalDecision = Instant.ofEpochSecond(1_234_567L, 0);
        Instant now = Instant.ofEpochSecond(1_234_567L, (long) elapsed);

        // when:
        subject.allow(originalDecision, gasLimitForTX);
        // and:
        var result = subject.allow(now, gasLimitForTX);

        // then:
        assertTrue(result);
        assertEquals(
                (long) (DEFAULT_CAPACITY - gasLimitForTX - gasLimitForTX + toLeak),
                subject.delegate().bucket().brimfulCapacityFree());
    }

    @Test
    void leaksAsExpected() {
        // setup:
        long gasLimitForTX = 100_000;

        double elapsed = 1_234;
        double toLeak = (elapsed / ONE_SECOND_IN_NANOSECONDS) * DEFAULT_CAPACITY;

        Instant originalDecision = Instant.ofEpochSecond(1_234_567L, 0);
        Instant now = Instant.ofEpochSecond(1_234_567L, (long) elapsed);

        subject.allow(originalDecision, gasLimitForTX);
        subject.leakUntil(now);

        assertEquals(
                (long) (DEFAULT_CAPACITY - gasLimitForTX + toLeak),
                subject.delegate().bucket().brimfulCapacityFree());
    }

    @Test
    void capacityReturnsCorrectValue() {
        assertEquals(DEFAULT_CAPACITY, subject.capacity());
    }

    @Test
    void usedReturnsCorrectValue() {
        assertEquals(0, subject.used());
    }

    @Test
    void verifyLeakUnusedGas() {
        subject.allow(Instant.now(), 100L);
        assertEquals(999_900L, subject.delegate().bucket().brimfulCapacityFree());

        subject.leakUnusedGasPreviouslyReserved(100L);
        assertEquals(DEFAULT_CAPACITY, subject.delegate().bucket().brimfulCapacityFree());
    }

    @Test
    void returnsExpectedState() {
        final var originalDecision = Instant.ofEpochSecond(1_234_567L, 0);

        subject.allow(originalDecision, 1234);
        final var state = subject.usageSnapshot();

        assertEquals(1234, state.used());
        assertEquals(originalDecision, instantFrom(requireNonNull(state.lastDecisionTime())));
    }

    @Test
    void resetsUsageToAsExpected() {
        final long used = DEFAULT_CAPACITY / 2;
        final var originalDecision = new Timestamp(1_234_567L, 0);
        final var snapshot = new ThrottleUsageSnapshot(used, originalDecision);

        subject.resetUsageTo(snapshot);

        assertEquals(used, subject.delegate().bucket().capacityUsed());
        assertEquals(snapshot, subject.usageSnapshot());
    }

    @Test
    void memoizesSnapshotAndInvalidatesForEveryUsageMutation() {
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var initial = subject.usageSnapshot();

        assertSame(initial, subject.usageSnapshot());

        assertTrue(subject.allow(now, 100));
        final var afterAllow = subject.usageSnapshot();
        assertNotSame(initial, afterAllow);
        assertSame(afterAllow, subject.usageSnapshot());

        subject.leakUnusedGasPreviouslyReserved(25);
        final var afterUnusedGasLeak = subject.usageSnapshot();
        assertNotSame(afterAllow, afterUnusedGasLeak);

        assertTrue(subject.allow(now, 100));
        final var beforeReclaim = subject.usageSnapshot();
        subject.reclaimLastAllowedUse();
        final var afterReclaim = subject.usageSnapshot();
        assertNotSame(beforeReclaim, afterReclaim);

        subject.leakUntil(now.plusNanos(1_000_000));
        final var afterLeakUntil = subject.usageSnapshot();
        assertNotSame(afterReclaim, afterLeakUntil);

        assertTrue(subject.allow(now.plusNanos(1_000_000), 100));
        final var beforeReset = subject.usageSnapshot();
        subject.resetUsage();
        assertNotSame(beforeReset, subject.usageSnapshot());
    }

    @Test
    void preservesSnapshotIdentityAcrossNoOpsAndRejectedMutations() {
        final var now = Instant.ofEpochSecond(1_234_567L);
        final var initial = subject.usageSnapshot();

        assertThrows(IllegalArgumentException.class, () -> subject.allow(now, -1));
        assertSame(initial, subject.usageSnapshot());
        subject.leakUnusedGasPreviouslyReserved(0);
        assertSame(initial, subject.usageSnapshot());
        subject.reclaimLastAllowedUse();
        assertSame(initial, subject.usageSnapshot());
        subject.resetLastAllowedUse();
        assertSame(initial, subject.usageSnapshot());
        subject.resetUsage();
        assertSame(initial, subject.usageSnapshot());

        assertTrue(subject.allow(now, DEFAULT_CAPACITY));
        final var full = subject.usageSnapshot();
        assertFalse(subject.allow(now, 1));
        assertSame(full, subject.usageSnapshot());
        assertThrows(IllegalArgumentException.class, () -> subject.allow(now.minusNanos(1), 1));
        assertSame(full, subject.usageSnapshot());
        subject.leakUntil(now);
        assertSame(full, subject.usageSnapshot());
    }

    @Test
    void resetUsageToWarmsCacheFromSuppliedSnapshot() {
        final var supplied = new ThrottleUsageSnapshot(123L, new Timestamp(1_234_567L, 890));

        subject.resetUsageTo(supplied);

        assertSame(supplied, subject.usageSnapshot());
    }

    @Test
    void failedResetDoesNotLeaveAStaleCachedSnapshot() {
        final var before = subject.usageSnapshot();
        final var invalid = new ThrottleUsageSnapshot(subject.capacity() + 1, new Timestamp(1_234_567L, 890));

        assertThrows(IllegalArgumentException.class, () -> subject.resetUsageTo(invalid));

        final var after = subject.usageSnapshot();
        assertNotSame(before, after);
        assertEquals(before.used(), after.used());
        assertEquals(invalid.lastDecisionTime(), after.lastDecisionTime());
    }

    @Test
    void resetsUsageAsExpected() {
        // setup:
        long gasLimitForTX = 100_000;
        Instant now = Instant.ofEpochSecond(1_234_567L);

        // when:
        var result = subject.allow(now, gasLimitForTX);
        subject.resetUsage();

        // then:
        assertTrue(result);
        assertEquals(DEFAULT_CAPACITY, subject.delegate().bucket().brimfulCapacityFree());
    }

    @Test
    void reclaimsLastAllowedUseAsExpected() {
        // setup:
        long gasLimitForTX = 100_000;
        Instant now = Instant.ofEpochSecond(1_234_567L);

        // when:
        var result = subject.allow(now, gasLimitForTX);
        subject.resetLastAllowedUse();
        var result2 = subject.allow(now, gasLimitForTX);
        subject.reclaimLastAllowedUse();

        // then:
        assertTrue(result);
        assertTrue(result2);
        assertEquals(gasLimitForTX, subject.used());
        assertEquals(
                DEFAULT_CAPACITY - gasLimitForTX, subject.delegate().bucket().brimfulCapacityFree());
    }

    @Test
    void resetsLastAllowedUseAsExpected() {
        // setup:
        long gasLimitForTX = 100_000;
        Instant now = Instant.ofEpochSecond(1_234_567L);

        // when:
        var result = subject.allow(now, gasLimitForTX);
        var result2 = subject.allow(now, gasLimitForTX);
        subject.resetLastAllowedUse();
        subject.reclaimLastAllowedUse();

        // then:
        assertTrue(result);
        assertTrue(result2);
        assertEquals(
                DEFAULT_CAPACITY - (gasLimitForTX * 2),
                subject.delegate().bucket().brimfulCapacityFree());
    }
}
