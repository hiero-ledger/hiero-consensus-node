// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static com.swirlds.base.units.UnitConstants.SECONDS_TO_NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LeakyBucketThrottleTest {

    private static final long BUCKET_CAPACITY = 1_234_568;
    private static final long BEYOND_CAPACITY = 1_234_569;

    LeakyBucketThrottle subject = new LeakyBucketThrottle(BUCKET_CAPACITY);

    @Test
    void hasExpectedCapacityFree() {
        final var capacity = 1_000_000;
        var subject = new LeakyBucketThrottle(capacity);
        subject.bucket().useCapacity(capacity / 2);

        assertEquals(capacity / 2, subject.capacityFree());
        assertEquals(3 * capacity / 4, subject.capacityFree(SECONDS_TO_NANOSECONDS / 4));
    }

    @Test
    void hasExpectedPercentUsed() {
        final var capacity = 1_000_000;
        var subject = new LeakyBucketThrottle(capacity);
        subject.bucket().useCapacity(capacity / 2);

        assertEquals(50.0, subject.percentUsed(0));
        assertEquals(25.0, subject.percentUsed(SECONDS_TO_NANOSECONDS / 4));
    }

    @Test
    void hasExpectedInstantaneousPercentUsed() {
        final var capacity = 1_000_000;
        var subject = new LeakyBucketThrottle(capacity);
        subject.bucket().useCapacity(capacity / 2);
        assertEquals(50.0, subject.instantaneousPercentUsed());
    }

    @Test
    void hasExpectedUsageRatio() {
        final var capacity = 1_000_000;
        var subject = new LeakyBucketThrottle(capacity);
        subject.bucket().useCapacity(capacity / 4);

        assertEquals(3, subject.freeToUsedRatio());
    }

    @Test
    void hasExpectedUsageRatioIfAllFree() {
        final var capacity = 1_000_000;
        var subject = new LeakyBucketThrottle(capacity);

        assertEquals(Long.MAX_VALUE, subject.freeToUsedRatio());
    }

    @Test
    void rejectsUnsupportedTXGasLimit() {
        assertFalse(subject.allow(BEYOND_CAPACITY, 0));
    }

    @Test
    void withExcessElapsedNanosCompletelyEmptiesBucket() {
        assertTrue(subject.allow(BUCKET_CAPACITY, Long.MAX_VALUE));
        assertEquals(0, subject.bucket().capacityFree());
    }

    @Test
    void withZeroElapsedNanosSimplyAdjustsCapacityFree() {
        assertTrue(subject.allow(BUCKET_CAPACITY / 2, 0L));
        assertEquals(BUCKET_CAPACITY / 2, subject.bucket().capacityFree());
    }

    @Test
    void withZeroElapsedNanosRejectsUnavailableCapacity() {
        assertFalse(subject.allow(BEYOND_CAPACITY, 0L));
        assertEquals(BUCKET_CAPACITY, subject.bucket().capacityFree());
    }

    @Test
    void lastAllowedUseReclaimsCorrectly() {
        assertTrue(subject.allow(BUCKET_CAPACITY / 3, 0L));
        subject.resetLastAllowedUse();
        assertTrue(subject.allow(BUCKET_CAPACITY / 3, 0L));
        subject.reclaimLastAllowedUse();
        assertEquals(BUCKET_CAPACITY - (BUCKET_CAPACITY / 3), subject.bucket().capacityFree());
        subject.reclaimLastAllowedUse();
        assertEquals(BUCKET_CAPACITY - (BUCKET_CAPACITY / 3), subject.bucket().capacityFree());
    }

    @Test
    void lastAllowedUseReclaimsCorrectlyWithFullUsage() {
        assertTrue(subject.allow(BUCKET_CAPACITY / 2, 0L));
        assertFalse(subject.allow(BUCKET_CAPACITY, 0L));
        subject.reclaimLastAllowedUse();
        assertEquals(BUCKET_CAPACITY, subject.bucket().capacityFree());
        subject.reclaimLastAllowedUse();
        assertEquals(BUCKET_CAPACITY, subject.bucket().capacityFree());
    }

    @Test
    void lastAllowedUseResetsCorrectly() {
        assertTrue(subject.allow(BUCKET_CAPACITY / 3, 0L));
        assertTrue(subject.allow(BUCKET_CAPACITY / 3, 0L));
        subject.resetLastAllowedUse();
        subject.reclaimLastAllowedUse();
        assertEquals(
                BUCKET_CAPACITY - ((BUCKET_CAPACITY / 3) * 2), subject.bucket().capacityFree());
    }

    @Test
    void testAllowsInitialBurst() {
        LeakyBucketThrottle throttle = new LeakyBucketThrottle(BUCKET_CAPACITY, 2);
        for (int i = 0; i < 3; i++) {
            assertTrue(throttle.allow(BUCKET_CAPACITY, SECONDS_TO_NANOSECONDS / 2));
        }
        assertFalse(throttle.allow(BUCKET_CAPACITY, 0));
    }
}
