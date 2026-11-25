// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HandlerSaturationMonitor}.
 */
class HandlerSaturationMonitorTest {

    private static final long CAPACITY = 5;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void queueBelowCapacityIsNotFull() {
        final AtomicLong queuedRounds = new AtomicLong(CAPACITY - 1);
        final HandlerSaturationMonitor monitor = new HandlerSaturationMonitor(queuedRounds::get, CAPACITY);

        assertEquals(Duration.ZERO, monitor.check(START));
        assertEquals(Duration.ZERO, monitor.check(START.plusSeconds(1)));
    }

    @Test
    void fullQueueReportsTimeSinceItWasFirstSeenFull() {
        final AtomicLong queuedRounds = new AtomicLong(CAPACITY);
        final HandlerSaturationMonitor monitor = new HandlerSaturationMonitor(queuedRounds::get, CAPACITY);

        assertEquals(Duration.ZERO, monitor.check(START));
        assertEquals(Duration.ofMillis(300), monitor.check(START.plusMillis(300)));
        queuedRounds.set(CAPACITY + 1);
        assertEquals(Duration.ofMillis(1200), monitor.check(START.plusMillis(1200)));
    }

    @Test
    void queueThatDrainsStartsAnewWhenFullAgain() {
        final AtomicLong queuedRounds = new AtomicLong(CAPACITY);
        final HandlerSaturationMonitor monitor = new HandlerSaturationMonitor(queuedRounds::get, CAPACITY);
        monitor.check(START);
        monitor.check(START.plusSeconds(2));

        queuedRounds.set(CAPACITY - 1);
        assertEquals(Duration.ZERO, monitor.check(START.plusSeconds(3)));

        queuedRounds.set(CAPACITY);
        assertEquals(Duration.ZERO, monitor.check(START.plusSeconds(4)));
        assertEquals(Duration.ofMillis(500), monitor.check(START.plusMillis(4500)));
    }

    @Test
    void unboundedQueueIsNeverFull() {
        final AtomicLong queuedRounds = new AtomicLong(1_000_000);
        final HandlerSaturationMonitor monitor = new HandlerSaturationMonitor(queuedRounds::get, -1);

        assertEquals(Duration.ZERO, monitor.check(START));
        assertEquals(Duration.ZERO, monitor.check(START.plusSeconds(10)));
    }
}
