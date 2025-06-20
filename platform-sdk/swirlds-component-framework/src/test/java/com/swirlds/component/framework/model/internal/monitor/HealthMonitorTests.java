// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.monitor;

import static java.time.Duration.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HealthMonitorTests {
    public static final Duration HEALTH_LOG_THRESHOLD = Duration.ofSeconds(5);
    public static final Duration HEALTH_LOG_PERIOD = Duration.ofDays(10000);
    public static final Duration HEALTHY_REPORT_THRESHOLD = Duration.ofSeconds(1);
    public static final long HEALTHY_REPORT_THRESHOLD_MS = HEALTHY_REPORT_THRESHOLD.toMillis();
    public static final Duration ONE_TENTH_OF_HRT_MS = HEALTHY_REPORT_THRESHOLD.dividedBy(10);
    private FakeTime time;
    private Randotron randotron;

    @BeforeEach
    void setUp() {
        randotron = Randotron.create();
        time = new FakeTime(randotron.nextInstant(), ZERO);
    }

    @DisplayName("Empty Scheduler List")
    @Test
    void emptySchedulerList() {
        final List<TaskScheduler<?>> schedulers = new ArrayList<>();
        final HealthMonitor healthMonitor = new HealthMonitor(
                new NoOpMetrics(),
                Time.getCurrent(),
                schedulers,
                HEALTH_LOG_THRESHOLD,
                HEALTH_LOG_PERIOD,
                HEALTHY_REPORT_THRESHOLD);
        assertEquals(ZERO, healthMonitor.checkSystemHealth(time.now()));
        assertEquals(ZERO, healthMonitor.getUnhealthyDuration());
        assertNull(healthMonitor.checkSystemHealth(time.now()));
        assertEquals(ZERO, healthMonitor.getUnhealthyDuration());
    }

    @DisplayName("Single Healthy Scheduler")
    @Test
    void singleHealthyScheduler() {
        final AtomicBoolean healthy = new AtomicBoolean(true);
        final TaskScheduler<?> scheduler = buildMockScheduler(healthy);
        final List<TaskScheduler<?>> schedulers = List.of(scheduler);
        final HealthMonitor healthMonitor = new HealthMonitor(
                new NoOpMetrics(), time, schedulers, HEALTH_LOG_THRESHOLD, HEALTH_LOG_PERIOD, HEALTHY_REPORT_THRESHOLD);

        assertSystemRemainsHealthy(healthMonitor, time);
    }

    @DisplayName("Single Unhealthy Scheduler")
    @Test
    void singleUnhealthyScheduler() {
        final AtomicBoolean healthy = new AtomicBoolean(false);
        final TaskScheduler<?> scheduler = buildMockScheduler(healthy);
        final List<TaskScheduler<?>> schedulers = List.of(scheduler);
        final HealthMonitor healthMonitor = new HealthMonitor(
                new NoOpMetrics(), time, schedulers, HEALTH_LOG_THRESHOLD, HEALTH_LOG_PERIOD, HEALTHY_REPORT_THRESHOLD);
        assertEquals(ZERO, healthMonitor.checkSystemHealth(time.now()));
        time.tick(ONE_TENTH_OF_HRT_MS);
        assertEquals(ONE_TENTH_OF_HRT_MS, healthMonitor.checkSystemHealth(time.now()));
        assertEquals(ONE_TENTH_OF_HRT_MS, healthMonitor.getUnhealthyDuration());
        time.tick(Duration.ofMinutes(1));
        assertEquals(Duration.ofMinutes(1).plus(100, ChronoUnit.MILLIS), healthMonitor.checkSystemHealth(time.now()));
        assertEquals(Duration.ofMinutes(1).plus(100, ChronoUnit.MILLIS), healthMonitor.getUnhealthyDuration());
    }

    @DisplayName("Single Scheduler Unhealthy & healthy Transitions")
    @Test
    void singleUnhealthySchedulerTransitions() {
        final AtomicBoolean healthy = new AtomicBoolean(false);
        final TaskScheduler<?> scheduler = buildMockScheduler(healthy);
        final List<TaskScheduler<?>> schedulers = List.of(scheduler);
        final HealthMonitor healthMonitor = new HealthMonitor(
                new NoOpMetrics(), time, schedulers, HEALTH_LOG_THRESHOLD, HEALTH_LOG_PERIOD, HEALTHY_REPORT_THRESHOLD);

        // Initial -> unhealthy transition
        // it is unhealthy but given that time did not pass, no effect will be reported.
        // internally the scheduler has been registered as unhealthy
        assertEquals(ZERO, healthMonitor.checkSystemHealth(time.now()));
        time.tick(ONE_TENTH_OF_HRT_MS); // let time pass, now its been unhealthy for a while, that while should be
        // reported
        assertEquals(
                ONE_TENTH_OF_HRT_MS,
                healthMonitor.checkSystemHealth(time.now())); // system should transition to unhealthy
        assertNull(healthMonitor.checkSystemHealth(time.now())); // no change should not be reported
        time.tick(ONE_TENTH_OF_HRT_MS); // double the passed time
        assertEquals(
                ONE_TENTH_OF_HRT_MS.plus(ONE_TENTH_OF_HRT_MS),
                healthMonitor.checkSystemHealth(time.now())); // unhealthy time should double
        assertNull(healthMonitor.checkSystemHealth(time.now())); // no change should not be reported

        // Unhealthy -> healthy transition
        time.tick(ONE_TENTH_OF_HRT_MS);
        healthy.set(true); // transition the scheduler to healthy again
        assertSystemRemainsHealthy(healthMonitor, time); // Time will be affected by 1 second

        // Healthy -> healthy
        time.tick(ONE_TENTH_OF_HRT_MS);
        healthy.set(true); // this has no effect, just for documenting in the test
        assertNull(healthMonitor.checkSystemHealth(time.now())); // no change should not be reported

        // assert that while a second hasn't pass yet, it does not report the value
        breakValueRandomly(randotron, HEALTHY_REPORT_THRESHOLD.toMillis() - 101).forEach(v -> {
            time.tick(Duration.ofMillis(v));
            assertNull(healthMonitor.checkSystemHealth(time.now()));
        });
        time.tick(Duration.ofMillis(1)); // complete the second
        assertEquals(ZERO, healthMonitor.checkSystemHealth(time.now())); // value should be reported again

        // Healthy -> unhealthy transition
        time.tick(ONE_TENTH_OF_HRT_MS);
        healthy.set(false); // transition the scheduler to unHealthy again
        assertNull(healthMonitor.checkSystemHealth(
                time.now())); // no effect will be reported now but internally the scheduler has been registered as
        // unhealthy
        int repeat = 10;
        Duration duration = ONE_TENTH_OF_HRT_MS;
        // Unhealthy -> Unhealthy transition
        while (repeat-- > 0) {
            time.tick(ONE_TENTH_OF_HRT_MS); // simulate pass time
            healthy.set(false); // this has no effect, just for documenting
            assertEquals(
                    duration, healthMonitor.checkSystemHealth(time.now())); // system should report increasing values
            duration = duration.plus(ONE_TENTH_OF_HRT_MS);
        }
    }

    @DisplayName("Multiple Unhealthy Schedulers")
    @Test
    void multipleUnhealthySchedulers() {
        final AtomicBoolean[] healthy =
                new AtomicBoolean[] {new AtomicBoolean(true), new AtomicBoolean(true), new AtomicBoolean(true)};

        final List<TaskScheduler<?>> schedulers = Arrays.stream(healthy)
                .<TaskScheduler<?>>map(HealthMonitorTests::buildMockScheduler)
                .toList();

        final HealthMonitor healthMonitor = new HealthMonitor(
                new NoOpMetrics(), time, schedulers, HEALTH_LOG_THRESHOLD, HEALTH_LOG_PERIOD, HEALTHY_REPORT_THRESHOLD);
        healthMonitor.checkSystemHealth(time.now());
        // Make each scheduler unhealthy.
        for (final var h : healthy) {
            h.set(false);
            time.tick(HEALTHY_REPORT_THRESHOLD_MS);
            healthMonitor.checkSystemHealth(time.now());
        }

        time.tick(HEALTHY_REPORT_THRESHOLD_MS);
        // Make each of schedulers healthy again. This will allow us to see the unhealthy time of the latter schedulers
        for (int i = 0; i < healthy.length; i++) {
            assertEquals(
                    Duration.ofNanos((healthy.length - i) * HEALTHY_REPORT_THRESHOLD_MS),
                    healthMonitor.checkSystemHealth(time.now()));
            healthy[i].set(true);
        }
        // Make the scheduler healthy again. We should see a single report of 0s, followed by nulls.
        assertSystemRemainsHealthy(healthMonitor, time);
        assertNull(healthMonitor.checkSystemHealth(time.now()));
        time.tick(HEALTHY_REPORT_THRESHOLD);
        // if a second is passed, then the healthy duration should be reported again
        assertEquals(ZERO, healthMonitor.checkSystemHealth(time.now()));
    }

    @DisplayName("Healthy Scheduler Repeated Reports")
    @Test
    void healthySchedulerRepeatedReports() {
        final AtomicBoolean healthy = new AtomicBoolean(true);
        final TaskScheduler<?> scheduler = buildMockScheduler(healthy);
        final List<TaskScheduler<?>> schedulers = List.of(scheduler);
        final HealthMonitor healthMonitor = new HealthMonitor(
                new NoOpMetrics(), time, schedulers, HEALTH_LOG_THRESHOLD, HEALTH_LOG_PERIOD, HEALTHY_REPORT_THRESHOLD);
        assertEquals(ZERO, healthMonitor.checkSystemHealth(time.now()));

        // Checks that within the second, while the system stays healthy the healthy is not reported
        // But after the timer crosses the 1-second threshold, check that the reports the healthy duration again.
        int repetitions = 10;
        while (repetitions-- > 0) {
            for (final var value : breakValueRandomly(randotron, 999)) {
                // divides 1000 milliseconds into N random values adding up to 1000
                // to make sure each
                time.tick(Duration.ofMillis(value));
                assertNull(healthMonitor.checkSystemHealth(time.now()));
            }
            time.tick(Duration.ofMillis(1));
            assertEquals(ZERO, healthMonitor.checkSystemHealth(time.now()));
        }
    }

    // Make the scheduler healthy again. We should see a single report of 0s, followed by nulls
    // if they all happen within the second
    private void assertSystemRemainsHealthy(final HealthMonitor healthMonitor, final FakeTime time) {
        assertEquals(ZERO, healthMonitor.checkSystemHealth(time.now()));
        assertEquals(ZERO, healthMonitor.getUnhealthyDuration());
        breakValueRandomly(randotron, HEALTHY_REPORT_THRESHOLD_MS - 1).forEach(v -> {
            time.tick(v * 1000000);
            assertNull(healthMonitor.checkSystemHealth(time.now()));
        });
        time.tick(1000000);
        assertEquals(ZERO, healthMonitor.checkSystemHealth(time.now()));
        assertEquals(ZERO, healthMonitor.getUnhealthyDuration());
    }

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

    @NonNull
    private static Collection<Long> breakValueRandomly(Random rng, long value) {
        Objects.requireNonNull(rng);
        if (value <= 0) throw new IllegalArgumentException("value must be positive");

        final List<Long> result = new ArrayList<>();
        long currentSum = 0L;
        while (currentSum < value) {
            final long val = value - currentSum < 10 ? value - currentSum : rng.nextLong(1, value - currentSum);
            currentSum += val;
            result.add(val);
        }
        return result;
    }
}
