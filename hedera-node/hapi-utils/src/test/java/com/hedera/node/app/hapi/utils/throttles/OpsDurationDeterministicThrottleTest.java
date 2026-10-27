// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class OpsDurationDeterministicThrottleTest {
    private static final long ONE_SECOND_IN_NANOSECONDS = 1_000_000_000;

    @Test
    void instantaneousPercentUsedIsCappedAtFullWhenOverConsuming() {
        final var now = Instant.ofEpochSecond(1);
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 500, 10);
        subject.useCapacity(now, 1000);
        assertEquals(100, subject.instantaneousPercentUsed());
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
    void canTakeAndRestoreUsageSnapshotsWhenOverConsuming() {
        final var now = Instant.ofEpochSecond(1);
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        subject.useCapacity(now, 1000);
        assertEquals(0, subject.capacityFree(now));
        assertEquals(100, subject.capacityUsed(0L));
        assertEquals(100, subject.capacity());

        final var snapshot = subject.usageSnapshot();
        final var restored = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        restored.resetUsageTo(snapshot);
        assertEquals(0, restored.capacityFree(now));
        assertEquals(100, restored.capacityUsed(0L));
        assertEquals(100, restored.capacity());
    }

    @Test
    void restoringSnapshotWithUsageAboveCapacityClampsInsteadOfThrowing() {
        final var now = Instant.ofEpochSecond(1);
        // A snapshot persisted by an earlier version (whose bucket could overfill), or taken
        // under a larger configured capacity, may record more usage than this bucket can hold
        final var overfilledSnapshot = new ThrottleUsageSnapshot(1000, new Timestamp(now.getEpochSecond(), 0));
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        assertDoesNotThrow(() -> subject.resetUsageTo(overfilledSnapshot));
        assertEquals(100, subject.used());
        assertEquals(0, subject.capacityFree(now));
    }

    @Test
    void restoringSnapshotWithNegativeUsageClampsToZero() {
        final var now = Instant.ofEpochSecond(1);
        // A corrupt snapshot could carry a negative used value; it must clamp to 0 rather than
        // propagating the bucket's IllegalArgumentException out of the state-restore path.
        final var corruptSnapshot = new ThrottleUsageSnapshot(-1000, new Timestamp(now.getEpochSecond(), 0));
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        assertDoesNotThrow(() -> subject.resetUsageTo(corruptSnapshot));
        assertEquals(0, subject.used());
        assertEquals(100, subject.capacityFree(now));
    }

    @Test
    void useCapacityThrowsWhenTimelineMovesBackward() {
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        subject.useCapacity(Instant.ofEpochSecond(2), 10);
        // A decision time earlier than the last one means the throttle timeline moved backward
        assertThrows(IllegalArgumentException.class, () -> subject.useCapacity(Instant.ofEpochSecond(1), 10));
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
    void negativeUnitsToConsumeAreClampedAndDoNotThrow() {
        final var now = Instant.ofEpochSecond(1);
        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", 100, 10);
        subject.useCapacity(now, 40);
        // A negative amount (e.g. from a wrapped/overflowed cost) must be treated as a no-op rather
        // than propagating an IllegalArgumentException out of the bucket during transaction handling.
        assertDoesNotThrow(() -> subject.useCapacity(now, -1_000_000L));
        assertEquals(40, subject.used());
    }

    @Test
    void bucketClampsAtCapacityAndLeaksAppropriately() {
        final var capacity = 1_000_000;
        final var leakPerSecond = 500;
        final var capacityToUse = capacity * 2;

        final var subject = new OpsDurationDeterministicThrottle("OpsDuration", capacity, leakPerSecond);

        assertDoesNotThrow(() -> subject.useCapacity(Instant.ofEpochSecond(1), capacityToUse));
        assertEquals(capacity, subject.used());

        // "preview" the capacity used
        assertEquals(capacity - leakPerSecond, subject.capacityUsed(ONE_SECOND_IN_NANOSECONDS));

        // "preview" the capacity free
        final var secondsToLeakAllCapacity = (capacity / leakPerSecond) + 1;
        assertEquals(capacity, subject.capacityFree(Instant.ofEpochSecond(secondsToLeakAllCapacity)));
    }
}
