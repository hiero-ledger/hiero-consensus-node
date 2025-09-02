// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

final class OpsDurationDeterministicThrottleTest {
    private static final long ONE_SECOND_IN_NANOSECONDS = 1_000_000_000;

    @Test
    void instantaneousPercentUsedWhenOverfilled() {
        final var now = Instant.ofEpochSecond(1);
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 500, 10);
        subject.useCapacity(now, 1000);
        assertEquals(200, subject.instantaneousPercentUsed());
    }

    @Test
    void canTakeAndRestoreUsageSnapshots() {
        final var now = Instant.ofEpochSecond(1);
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        subject.useCapacity(now, 50);
        assertEquals(50, subject.capacityFree(now));
        assertEquals(50, subject.capacityUsed(0L));
        assertEquals(100, subject.capacity());

        final var snapshot = subject.usageSnapshot();
        final var restored = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        restored.resetUsageTo(snapshot);
        assertEquals(50, restored.capacityFree(now));
        assertEquals(50, restored.capacityUsed(0L));
        assertEquals(100, restored.capacity());
    }

    @Test
    void canTakeAndRestoreUsageSnapshotsWhenOverfilled() {
        final var now = Instant.ofEpochSecond(1);
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        subject.useCapacity(now, 1000);
        assertEquals(0, subject.capacityFree(now));
        assertEquals(1000, subject.capacityUsed(0L));
        assertEquals(100, subject.capacity());

        final var snapshot = subject.usageSnapshot();
        final var restored = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        restored.resetUsageTo(snapshot);
        assertEquals(0, restored.capacityFree(now));
        assertEquals(1000, restored.capacityUsed(0L));
        assertEquals(100, restored.capacity());
    }

    @Test
    void capacityFreeWhenDecisionTimeIsNullWorks() {
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 100, 1);
        assertEquals(100, subject.capacityFree(Instant.ofEpochSecond(1)));
    }

    @Test
    void instantaneousPercentUsedWhenDecisionTimeIsNullWorks() {
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 100, 1);
        assertEquals(0, subject.instantaneousPercentUsed());
    }

    @Test
    void bucketCanOverfillAndLeaksAppropriately() {
        final var capacity = 1_000_000;
        final var leakPerSecond = 500;
        final var capacityToUse = capacity * 2;

        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", capacity, leakPerSecond);

        assertDoesNotThrow(() -> subject.useCapacity(Instant.ofEpochSecond(1), capacityToUse));
        assertEquals(capacityToUse, subject.used());

        // "preview" the capacity used
        assertEquals(capacityToUse - leakPerSecond, subject.capacityUsed(ONE_SECOND_IN_NANOSECONDS));

        // "preview" the capacity free
        final var secondsToLeakAllCapacity = (capacity / leakPerSecond) + 1;
        // leak the overfill amount
        assertEquals(0, subject.capacityFree(Instant.ofEpochSecond(secondsToLeakAllCapacity)));
        // leak the remainder
        assertEquals(capacity, subject.capacityFree(Instant.ofEpochSecond(2 * secondsToLeakAllCapacity)));
    }
}
