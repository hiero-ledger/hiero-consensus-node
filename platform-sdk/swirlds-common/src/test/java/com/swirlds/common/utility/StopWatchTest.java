// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StopWatchTest {

    @Test
    void startAndStop() {
        final StopWatch stopWatch = new StopWatch();

        assertFalse(stopWatch.isRunning());
        stopWatch.start();
        assertTrue(stopWatch.isRunning());

        assertThrows(IllegalStateException.class, stopWatch::start); // Shouldn't be able to start while running

        stopWatch.stop();
        assertFalse(stopWatch.isRunning());
        assertThrows(
                IllegalStateException.class, stopWatch::stop); // Shouldn't be able to stop when it's already stopped
    }

    @Test
    void elapsedTime() {
        final FakeTime fakeTime = new FakeTime();
        final StopWatch stopWatch = new StopWatch(fakeTime);

        stopWatch.start();
        fakeTime.tick(Duration.ofMillis(100)); // Simulate 100ms passing
        stopWatch.stop();

        assertEquals(100, stopWatch.getTime(TimeUnit.MILLISECONDS)); // Ensure elapsed time is exactly 100ms
    }

    @Test
    void multipleRuns() {
        final FakeTime fakeTime = new FakeTime();
        final StopWatch stopWatch = new StopWatch(fakeTime);

        stopWatch.start();
        fakeTime.tick(Duration.ofMillis(50)); // Simulate 50ms passing
        stopWatch.stop();

        long firstRunTime = stopWatch.getTime(TimeUnit.MILLISECONDS);

        stopWatch.start();
        fakeTime.tick(Duration.ofMillis(100)); // Simulate 100ms passing
        stopWatch.stop();

        long secondRunTime = stopWatch.getTime(TimeUnit.MILLISECONDS);

        assertEquals(50, firstRunTime);
        assertEquals(100, secondRunTime);
    }

    @Test
    void reset() {
        final FakeTime fakeTime = new FakeTime();
        final StopWatch stopWatch = new StopWatch(fakeTime);

        stopWatch.start();
        fakeTime.tick(Duration.ofMillis(50)); // Simulate 50ms passing
        stopWatch.stop();

        assertEquals(50, stopWatch.getTime(TimeUnit.MILLISECONDS));

        stopWatch.reset();
        assertFalse(stopWatch.isRunning());
        assertThrows(
                IllegalStateException.class,
                stopWatch::getElapsedTimeNano); // Shouldn't be able to get elapsed time after reset

        stopWatch.start();
        assertTrue(stopWatch.isRunning());
        fakeTime.tick(Duration.ofMillis(60)); // Simulate 60ms passing
        stopWatch.stop();

        assertEquals(60, stopWatch.getTime(TimeUnit.MILLISECONDS)); // Ensure it still works as expected after a reset
    }

    @Test
    void getTimeInDifferentUnits() {
        final FakeTime fakeTime = new FakeTime();
        final StopWatch stopWatch = new StopWatch(fakeTime);

        // Sleep for a little more than 2 seconds for test precision
        final Duration sleepDuration = Duration.ofMillis(2050);

        stopWatch.start();
        fakeTime.tick(sleepDuration);
        stopWatch.stop();

        assertEquals(sleepDuration.toNanos(), stopWatch.getTime(TimeUnit.NANOSECONDS));
        assertEquals(sleepDuration.toMillis(), stopWatch.getTime(TimeUnit.MILLISECONDS));
        assertEquals(sleepDuration.getSeconds(), stopWatch.getTime(TimeUnit.SECONDS));
        assertEquals(0, stopWatch.getTime(TimeUnit.MINUTES));
        assertEquals(0, stopWatch.getTime(TimeUnit.HOURS));
        assertEquals(0, stopWatch.getTime(TimeUnit.DAYS));
    }

    @Test
    void suspendAndResume() {
        StopWatch stopWatch = new StopWatch();

        stopWatch.start();
        assertTrue(stopWatch.isRunning());

        stopWatch.suspend();
        assertTrue(stopWatch.isSuspended());

        assertThrows(IllegalStateException.class, stopWatch::start); // Trying to start when suspended
        assertThrows(IllegalStateException.class, stopWatch::suspend); // Trying to suspend when already suspended

        stopWatch.resume();
        assertTrue(stopWatch.isRunning());
        stopWatch.stop();

        long elapsedTime = stopWatch.getTime(TimeUnit.MILLISECONDS);
        assertTrue(elapsedTime >= 0); // Confirming we can get time after resuming
    }

    @Test
    void isStoppedTrue() {
        final FakeTime fakeTime = new FakeTime();
        final StopWatch stopWatch = new StopWatch(fakeTime);

        stopWatch.start();
        fakeTime.tick(100);
        stopWatch.stop();

        assertTrue(stopWatch.isStopped());
    }

    @Test
    void isStoppedFalseIfSuspended() {
        final FakeTime fakeTime = new FakeTime();
        final StopWatch stopWatch = new StopWatch(fakeTime);

        stopWatch.start();
        fakeTime.tick(100);
        stopWatch.suspend();

        assertFalse(stopWatch.isStopped());
    }
}
