// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.utility.CompareTo;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HealthMonitorTests {

    /**
     * Build a mock task scheduler.
     *
     * @param healthy when the value of this atomic boolean is true, the task scheduler is healthy; otherwise, it is
     *                unhealthy.
     * @return a mock task scheduler
     */
    @NonNull
    private static TaskScheduler<?> buildMockScheduler(final AtomicBoolean healthy) {
        final TaskScheduler<?> taskScheduler = mock(TaskScheduler.class);
        when(taskScheduler.getCapacity()).thenReturn(10L);
        when(taskScheduler.getUnprocessedTaskCount()).thenAnswer(invocation -> healthy.get() ? 5L : 15L);
        return taskScheduler;
    }

    @Test
    void healthyBehaviorTest() {
        final Randotron randotron = Randotron.create();

        final int schedulerCount = randotron.nextInt(10, 20);

        final List<TaskScheduler<?>> schedulers = new ArrayList<>();

        for (int i = 0; i < schedulerCount; i++) {
            final AtomicBoolean healthy = new AtomicBoolean(true);
            final TaskScheduler<?> scheduler = buildMockScheduler(healthy);
            schedulers.add(scheduler);
        }

        final Instant startTime = randotron.nextInstant();
        final FakeTime time = new FakeTime(startTime, Duration.ZERO);
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withTime(time).build();

        final HealthMonitor healthMonitor =
                new HealthMonitor(platformContext, schedulers, Duration.ofSeconds(5), Duration.ofDays(10000));

        final Instant endTime = startTime.plus(Duration.ofSeconds(10));
        while (time.now().isBefore(endTime)) {
            assertNull(healthMonitor.checkSystemHealth(time.now()));
            time.tick(Duration.ofMinutes(randotron.nextInt(1, 1000)));
            assertEquals(Duration.ZERO, healthMonitor.getUnhealthyDuration());
        }
    }

    @Test
    void oneUnhealthySchedulerTest() {
        final Randotron randotron = Randotron.create();

        final int schedulerCount = randotron.nextInt(10, 20);

        final List<TaskScheduler<?>> schedulers = new ArrayList<>();
        final List<AtomicBoolean> schedulerHealths = new ArrayList<>();

        for (int i = 0; i < schedulerCount; i++) {
            final AtomicBoolean healthy = new AtomicBoolean(true);
            final TaskScheduler<?> scheduler = buildMockScheduler(healthy);
            schedulers.add(scheduler);
            schedulerHealths.add(healthy);
        }

        final Instant startTime = randotron.nextInstant();
        final FakeTime time = new FakeTime(startTime, Duration.ZERO);
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withTime(time).build();

        final HealthMonitor healthMonitor =
                new HealthMonitor(platformContext, schedulers, Duration.ofSeconds(5), Duration.ofDays(10000));

        final Instant phase1EndTime = startTime.plus(Duration.ofSeconds(10));
        while (time.now().isBefore(phase1EndTime)) {
            assertNull(healthMonitor.checkSystemHealth(time.now()));
            time.tick(Duration.ofMillis(randotron.nextInt(1, 1000)));
            assertEquals(Duration.ZERO, healthMonitor.getUnhealthyDuration());
        }

        final Instant unhealthyStartTime = time.now();
        final int unhealthyIndex = randotron.nextInt(0, schedulerCount);
        schedulerHealths.get(unhealthyIndex).set(false);

        final Instant phase2EndTime = time.now().plus(Duration.ofSeconds(10));
        while (time.now().isBefore(phase2EndTime)) {
            final Duration unhealthyTime = Duration.between(unhealthyStartTime, time.now());

            final Duration healthReport = healthMonitor.checkSystemHealth(time.now());
            if (CompareTo.isGreaterThan(unhealthyTime, Duration.ZERO)) {
                assertEquals(unhealthyTime, healthReport);
            } else {
                assertNull(healthReport);
            }
            time.tick(Duration.ofMillis(randotron.nextInt(1, 1000)));
            assertEquals(unhealthyTime, healthMonitor.getUnhealthyDuration());
        }

        // Make the scheduler healthy again. We should see a single report of 0s, followed by nulls.
        schedulerHealths.get(unhealthyIndex).set(true);
        assertEquals(Duration.ZERO, healthMonitor.checkSystemHealth(time.now()));

        final Instant phase3EndTime = time.now().plus(Duration.ofSeconds(10));
        while (time.now().isBefore(phase3EndTime)) {
            assertNull(healthMonitor.checkSystemHealth(time.now()));
            time.tick(Duration.ofMillis(randotron.nextInt(1, 1000)));
            assertEquals(Duration.ZERO, healthMonitor.getUnhealthyDuration());
        }
    }

    @Test
    void multipleUnhealthySchedulersTest() {
        final Randotron randotron = Randotron.create();

        final int schedulerCount = randotron.nextInt(10, 20);

        final List<TaskScheduler<?>> schedulers = new ArrayList<>();
        final List<AtomicBoolean> schedulerHealths = new ArrayList<>();

        for (int i = 0; i < schedulerCount; i++) {
            final AtomicBoolean healthy = new AtomicBoolean(true);
            final TaskScheduler<?> scheduler = buildMockScheduler(healthy);
            schedulers.add(scheduler);
            schedulerHealths.add(healthy);
        }

        final Instant startTime = randotron.nextInstant();
        final FakeTime time = new FakeTime(startTime, Duration.ZERO);
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().withTime(time).build();

        final HealthMonitor healthMonitor =
                new HealthMonitor(platformContext, schedulers, Duration.ofSeconds(5), Duration.ofDays(10000));

        final Instant phase1EndTime = startTime.plus(Duration.ofSeconds(10));
        while (time.now().isBefore(phase1EndTime)) {
            assertNull(healthMonitor.checkSystemHealth(time.now()));
            time.tick(Duration.ofMillis(randotron.nextInt(1, 1000)));
            assertEquals(Duration.ZERO, healthMonitor.getUnhealthyDuration());
        }

        final Instant unhealthyStartTimeA = time.now();
        final int unhealthyIndexA = randotron.nextInt(0, schedulerCount);
        schedulerHealths.get(unhealthyIndexA).set(false);

        final Instant phase2EndTime = time.now().plus(Duration.ofSeconds(10));
        while (time.now().isBefore(phase2EndTime)) {
            final Duration unhealthyTime = Duration.between(unhealthyStartTimeA, time.now());

            final Duration healthReport = healthMonitor.checkSystemHealth(time.now());
            if (CompareTo.isGreaterThan(unhealthyTime, Duration.ZERO)) {
                assertEquals(unhealthyTime, healthReport);
            } else {
                assertNull(healthReport);
            }
            time.tick(Duration.ofMillis(randotron.nextInt(1, 1000)));
            assertEquals(unhealthyTime, healthMonitor.getUnhealthyDuration());
        }

        // Make another scheduler unhealthy. It should be overshadowed by the first unhealthy scheduler.
        final Instant unhealthyStartTimeB = time.now();
        final int unhealthyIndexB = (unhealthyIndexA + 1) % schedulerCount;
        schedulerHealths.get(unhealthyIndexB).set(false);

        final Instant phase3EndTime = time.now().plus(Duration.ofSeconds(10));
        while (time.now().isBefore(phase3EndTime)) {
            final Duration unhealthyTime = Duration.between(unhealthyStartTimeA, time.now());

            final Duration healthReport = healthMonitor.checkSystemHealth(time.now());
            assertEquals(unhealthyTime, healthReport);

            time.tick(Duration.ofMillis(randotron.nextInt(1, 1000)));
            assertEquals(unhealthyTime, healthMonitor.getUnhealthyDuration());
        }

        // Make the first scheduler healthy again. This will allow us to see the unhealthy time of the second scheduler.
        schedulerHealths.get(unhealthyIndexA).set(true);

        final Instant phase4EndTime = time.now().plus(Duration.ofSeconds(10));
        while (time.now().isBefore(phase4EndTime)) {
            final Duration unhealthyTime = Duration.between(unhealthyStartTimeB, time.now());

            final Duration healthReport = healthMonitor.checkSystemHealth(time.now());
            assertEquals(unhealthyTime, healthReport);

            time.tick(Duration.ofMillis(randotron.nextInt(1, 1000)));
            assertEquals(unhealthyTime, healthMonitor.getUnhealthyDuration());
        }

        // Make the second scheduler healthy again. System should return to normal.
        schedulerHealths.get(unhealthyIndexB).set(true);
        assertEquals(Duration.ZERO, healthMonitor.checkSystemHealth(time.now()));

        final Instant phase5EndTime = time.now().plus(Duration.ofSeconds(10));
        while (time.now().isBefore(phase5EndTime)) {
            assertNull(healthMonitor.checkSystemHealth(time.now()));
            time.tick(Duration.ofMillis(randotron.nextInt(1, 1000)));
            assertEquals(Duration.ZERO, healthMonitor.getUnhealthyDuration());
        }
    }
}
