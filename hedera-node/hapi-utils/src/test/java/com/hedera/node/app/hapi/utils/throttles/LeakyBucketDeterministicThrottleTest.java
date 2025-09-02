// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static com.hedera.node.app.hapi.utils.throttles.DeterministicThrottleTest.instantFrom;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
