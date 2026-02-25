// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DiscreteLeakyBucketTest {
    private static final long DEFAULT_FIXED_CAPACITY = 64_000L;
    private static final long DEFAULT_CAPACITY_USED = DEFAULT_FIXED_CAPACITY / 4;

    @Test
    void requiresNonNegativeCapacity() {
        // expect:
        assertThrows(IllegalArgumentException.class, () -> DiscreteLeakyBucket.ofFixedCapacity(-1L));
    }

    @Test
    void startsEmptyIfNoInitialUsageGiven() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // expect:
        assertEquals(0L, subject.capacityUsed());
        assertEquals(DEFAULT_FIXED_CAPACITY, subject.brimfulCapacity());
        assertEquals(DEFAULT_FIXED_CAPACITY, subject.brimfulCapacityFree());
    }

    @Test
    void assertCapacityAndUsed() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // when:
        subject.useCapacity(1234L);

        // expect:
        assertEquals(DEFAULT_FIXED_CAPACITY, subject.brimfulCapacity());
        assertEquals(1234L, subject.capacityUsed());
    }

    @Test
    void leaksAsExpected() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // when:
        subject.useCapacity(DEFAULT_CAPACITY_USED);
        subject.leak(DEFAULT_CAPACITY_USED);

        // then:
        assertEquals(0, subject.capacityUsed());
        assertEquals(DEFAULT_FIXED_CAPACITY, subject.brimfulCapacityFree());
    }

    @Test
    void leaksToEmptyButNeverMore() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // when:
        subject.useCapacity(DEFAULT_CAPACITY_USED);
        subject.leak(Long.MAX_VALUE);

        // then:
        assertEquals(0L, subject.capacityUsed());
    }

    @Test
    void cannotLeakNegativeUnits() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // when:
        subject.useCapacity(DEFAULT_CAPACITY_USED);
        assertThrows(IllegalArgumentException.class, () -> subject.leak(-1));
    }

    @Test
    void prohibitsNegativeUse() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // when:
        subject.useCapacity(DEFAULT_CAPACITY_USED);
        assertThrows(IllegalArgumentException.class, () -> subject.useCapacity(-1));
    }

    @Test
    void prohibitsExcessUsageViaOverflow() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);
        // and:
        var overflowAmount = Long.MAX_VALUE - DEFAULT_CAPACITY_USED + 1L;

        // when:
        subject.useCapacity(DEFAULT_CAPACITY_USED);
        assertThrows(IllegalArgumentException.class, () -> subject.useCapacity(overflowAmount));
    }

    @Test
    void prohibitsExcessUsage() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // when:
        subject.useCapacity(DEFAULT_CAPACITY_USED);
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.useCapacity(1 + DEFAULT_FIXED_CAPACITY - DEFAULT_CAPACITY_USED));
    }

    @Test
    void permitsUse() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // when:
        subject.useCapacity(DEFAULT_CAPACITY_USED);
        subject.useCapacity(DEFAULT_FIXED_CAPACITY - DEFAULT_CAPACITY_USED);

        // then:
        assertEquals(DEFAULT_FIXED_CAPACITY, subject.capacityUsed());
    }

    @Test
    void permitsResettingUsedAmount() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // when:
        subject.useCapacity(DEFAULT_CAPACITY_USED);
        subject.resetUsed(1L);

        // then:
        assertEquals(1L, subject.capacityUsed());
    }

    @Test
    void rejectsNonsenseUsage() {
        // given:
        var subject = DiscreteLeakyBucket.ofFixedCapacity(DEFAULT_FIXED_CAPACITY);

        // expect:
        assertThrows(IllegalArgumentException.class, () -> subject.resetUsed(-1L));
        assertThrows(IllegalArgumentException.class, () -> subject.resetUsed(DEFAULT_FIXED_CAPACITY + 1L));
    }

    @Test
    void testbrimfulAndBrimfulCapacityLimits() {
        final var subject = DiscreteLeakyBucket.ofNominalAndBrimfulCapacity(100, 1000);

        assertDoesNotThrow(() -> subject.useCapacity(100L));
        assertDoesNotThrow(() -> subject.useCapacity(100L));
        assertDoesNotThrow(() -> subject.useCapacity(799L));
        assertDoesNotThrow(() -> subject.useCapacity(1L));
        assertThrows(IllegalArgumentException.class, () -> subject.useCapacity(1L));
        assertThrows(IllegalArgumentException.class, () -> subject.useCapacity(100L));
        assertThrows(IllegalArgumentException.class, () -> subject.useCapacity(Long.MAX_VALUE));
    }
}
