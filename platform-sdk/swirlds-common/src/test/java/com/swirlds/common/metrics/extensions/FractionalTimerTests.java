// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FractionalTimer Tests")
class FractionalTimerTests {
    private final FakeTime clock = new FakeTime(Instant.EPOCH, Duration.ZERO);
    private FractionalTimer metric;

    @BeforeEach
    void reset() {
        clock.reset();
        metric = new StandardFractionalTimer(clock);
    }

    /**
     * Example from the diagram in the documentation
     */
    @Test
    void diagramExample() {
        // time == 1
        assertEquals(0.0, metric.getActiveFraction(), "initially, the active fraction should be 0");

        clock.tick(Duration.ofSeconds(1)); // time == 2
        assertEquals(0.0, metric.getActiveFraction(), "no work has started yet, so the active fraction should be 0");
        metric.activate();
        assertEquals(
                0.0,
                metric.getActiveFraction(),
                "work has started, but not time has elapsed yet, so the active fraction should be 0");

        clock.tick(Duration.ofSeconds(1)); // time == 3
        assertEquals(
                0.5,
                metric.getActiveFraction(),
                "1 second of work, 1 second of idle, so the active fraction should be 0.5");
        metric.deactivate();
        assertEquals(0.5, metric.getActiveFraction(), "no time has elapsed, so the value should still be 0.5");

        clock.tick(Duration.ofSeconds(1)); // time == 4
        assertEquals(
                0.33,
                metric.getActiveFraction(),
                0.01,
                "1 second of work, 2 seconds of idle, so the active fraction should be 0.33");

        clock.tick(Duration.ofSeconds(1)); // time == 5
        assertEquals(
                0.25,
                metric.getActiveFraction(),
                0.01,
                "1 second of work, 3 seconds of idle, so the active fraction should be 0.25");

        clock.tick(Duration.ofSeconds(1)); // time == 6
        assertEquals(
                0.20,
                metric.getActiveFraction(),
                0.01,
                "1 second of work, 4 seconds of idle, so the active fraction should be 0.20");
        metric.activate();
        assertEquals(0.20, metric.getActiveFraction(), "no time has elapsed, so the value should still be 0.2");

        clock.tick(Duration.ofSeconds(1)); // time == 7
        assertEquals(
                0.33,
                metric.getActiveFraction(),
                0.01,
                "2 second of work, 4 seconds of idle, so the active fraction should be 0.33");

        clock.tick(Duration.ofSeconds(1)); // time == 8
        assertEquals(
                0.43,
                metric.getActiveFraction(),
                0.01,
                "3 second of work, 4 seconds of idle, so the active fraction should be 0.43");
        assertEquals(
                0.43,
                metric.getAndReset(),
                0.01,
                "the snapshot should contain the same value returned by getActiveFraction()");
        assertEquals(0.0, metric.getActiveFraction(), "the snapshotting should reset the value");

        clock.tick(Duration.ofSeconds(1)); // time == 9
        assertEquals(
                1.0,
                metric.getActiveFraction(),
                "work has been ongoing since the reset, so the active fraction should be 1");
    }

    @Test
    void noUpdateBetweenSnapshots() {
        clock.tick(Duration.ofSeconds(10));
        assertEquals(0.0, metric.getAndReset(), "no work has been done, expect 0");
        clock.tick(Duration.ofSeconds(5));
        metric.activate();
        clock.tick(Duration.ofSeconds(5));
        assertEquals(0.5, metric.getAndReset(), "half the time was spend doing work, expect 0.5");
        clock.tick(Duration.ofSeconds(10));
        assertEquals(1.0, metric.getAndReset(), "all the time was spent doing work, expect 1");
    }

    @Test
    void badUpdates() {
        clock.tick(Duration.ofSeconds(2));
        metric.activate();
        clock.tick(Duration.ofSeconds(1));
        // starting work again without finishing the previous work
        metric.activate();
        clock.tick(Duration.ofSeconds(1));
        assertEquals(0.5, metric.getAndReset(), "the second startingWork() should be ignored, so 0.5 is expected");
        clock.tick(Duration.ofSeconds(1));
        metric.deactivate();
        clock.tick(Duration.ofSeconds(1));
        metric.deactivate();
        clock.tick(Duration.ofSeconds(1));
        assertEquals(
                0.33, metric.getAndReset(), 0.01, "the second finishedWork() should be ignored, so 0.33 is expected");
    }

    @Test
    void metricNotReset() {
        metric.activate();
        clock.tick(Duration.ofMinutes(30));
        metric.deactivate();
        clock.tick(Duration.ofMinutes(10));
        metric.activate();
        metric.deactivate();
        assertEquals(-1.0, metric.getAndReset(), 0.01, "this in an overflow, so -1 is expected");
        clock.tick(Duration.ofMinutes(1));
        metric.activate();
        clock.tick(Duration.ofMinutes(1));
        assertEquals(
                0.5,
                metric.getAndReset(),
                "after the reset the metric should start tracking again, so 0.5 is expected");
    }

    @Test
    void noOpTest() {
        final FractionalTimer timer = NoOpFractionalTimer.getInstance();
        timer.registerMetric(null, null, null, null);
        timer.activate();
        timer.activate(1234);
        timer.deactivate(1234);
        timer.deactivate();
        assertEquals(0, timer.getActiveFraction());
        assertEquals(0, timer.getAndReset());
    }
}
