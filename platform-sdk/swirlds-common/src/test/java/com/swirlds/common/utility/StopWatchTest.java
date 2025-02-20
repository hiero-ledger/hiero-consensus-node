// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void elapsedTime() throws InterruptedException {
        final StopWatch stopWatch = new StopWatch();

        stopWatch.start();
        Thread.sleep(100);
        stopWatch.stop();

        assertTrue(stopWatch.getTime(TimeUnit.MILLISECONDS) >= 100); // Ensure elapsed time is at least 100ms
    }

    @Test
    void multipleRuns() throws InterruptedException {
        final StopWatch stopWatch = new StopWatch();

        stopWatch.start();
        Thread.sleep(50);
        stopWatch.stop();

        long firstRunTime = stopWatch.getTime(TimeUnit.MILLISECONDS);

        stopWatch.start();
        Thread.sleep(100);
        stopWatch.stop();

        long secondRunTime = stopWatch.getTime(TimeUnit.MILLISECONDS);

        assertTrue(firstRunTime >= 50);
        assertTrue(secondRunTime >= 100);
        assertNotEquals(firstRunTime, secondRunTime);
    }

    @Test
    void reset() throws InterruptedException {
        final StopWatch stopWatch = new StopWatch();

        stopWatch.start();
        Thread.sleep(50);
        stopWatch.stop();

        assertTrue(stopWatch.getTime(TimeUnit.MILLISECONDS) >= 50);

        stopWatch.reset();
        assertFalse(stopWatch.isRunning());
        assertThrows(
                IllegalStateException.class,
                stopWatch::getElapsedTimeNano); // Shouldn't be able to get elapsed time after reset

        stopWatch.start();
        assertTrue(stopWatch.isRunning());
        Thread.sleep(50);
        stopWatch.stop();

        assertTrue(stopWatch.getTime(TimeUnit.MILLISECONDS) >= 50); // Ensure it still works as expected after a reset
    }

    @Test
    void getTimeInDifferentUnits() throws InterruptedException {
        StopWatch stopWatch = new StopWatch();

        stopWatch.start();
        Thread.sleep(2050); // Sleep for a little more than 2 seconds for test precision
        stopWatch.stop();

        assertEquals(stopWatch.getTime(TimeUnit.MILLISECONDS), stopWatch.getTime(TimeUnit.MILLISECONDS));
        assertEquals(stopWatch.getElapsedTimeNano(), stopWatch.getTime(TimeUnit.NANOSECONDS));
        assertTrue(stopWatch.getTime(TimeUnit.SECONDS) >= 2);
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
    void isStoppedTrue() throws InterruptedException {
        StopWatch stopWatch = new StopWatch();

        stopWatch.start();
        Thread.sleep(100);
        stopWatch.stop();

        assertTrue(stopWatch.isStopped());
    }

    @Test
    void isStoppedFalseIfSuspended() throws InterruptedException {
        StopWatch stopWatch = new StopWatch();

        stopWatch.start();
        Thread.sleep(100);
        stopWatch.suspend();

        assertFalse(stopWatch.isStopped());
    }
}
