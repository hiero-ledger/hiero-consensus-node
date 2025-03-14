// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.stats.cycle.AccumulatedCycleMetrics;
import com.swirlds.platform.stats.cycle.CycleDefinition;
import com.swirlds.platform.stats.cycle.CycleTracker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CycleMetricsTest {
    private static final double EPSILON = 1e-6;
    private final FakeTime time = new FakeTime();
    private final CycleDefinition definition = new CycleDefinition(
            "test",
            "cycle",
            List.of(Pair.of("interval-0", "0"), Pair.of("interval-1", "1"), Pair.of("interval-2", "2")));

    private MetricsConfig metricsConfig;

    @BeforeEach
    void setupService() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        metricsConfig = configuration.getConfigData(MetricsConfig.class);
    }

    /**
     * Integration test of the cycle metrics. Manipulates the clock and marks cycle point. Asserts metrics are as
     * expected.
     */
    @Test
    void accumulatedCycleMetricsTest() {
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        final Metrics metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        final AccumulatedCycleMetrics accumulatedCycleMetrics = new AccumulatedCycleMetrics(metrics, definition);
        final CycleTracker tracker = new CycleTracker(time, accumulatedCycleMetrics);
        assertEquals(0.0, accumulatedCycleMetrics.getAvgCycleTime(), EPSILON, "avgTime should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getIntervalFraction(0), EPSILON, "int-0 should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getIntervalFraction(1), EPSILON, "int-1 should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getIntervalFraction(2), EPSILON, "int-2 should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getBusyFraction(), EPSILON, "busyTime should be 0");

        tracker.startCycle();
        time.tick(Duration.ofMillis(2)); // interval 0 took 2ms
        tracker.intervalEnded(0);
        time.tick(Duration.ofMillis(3)); // interval 1 took 3ms
        tracker.intervalEnded(1);
        time.tick(Duration.ofMillis(5)); // interval 2 took 5ms
        tracker.cycleEnded();

        assertEquals(10.0, accumulatedCycleMetrics.getAvgCycleTime(), EPSILON, "avgTime should be the total 2+3+5=10");
        assertEquals(20.0, accumulatedCycleMetrics.getIntervalFraction(0), EPSILON, "int-0 should be 2/10=20%");
        assertEquals(30.0, accumulatedCycleMetrics.getIntervalFraction(1), EPSILON, "int-1 should be 3/10=30%");
        assertEquals(50.0, accumulatedCycleMetrics.getIntervalFraction(2), EPSILON, "int-2 should be 5/10=50%");
        assertEquals(100.0, accumulatedCycleMetrics.getBusyFraction(), EPSILON, "no idle time expected at this point");

        time.tick(Duration.ofMillis(10)); // idle time

        tracker.startCycle();
        time.tick(Duration.ofMillis(10)); // interval 0 took 10ms
        tracker.intervalEnded(0);
        time.tick(Duration.ofMillis(6)); // interval 1 took 6ms
        tracker.intervalEnded(1);
        time.tick(Duration.ofMillis(4)); // interval 2 took 4ms
        tracker.cycleEnded();

        assertEquals(
                15.0,
                accumulatedCycleMetrics.getAvgCycleTime(),
                EPSILON,
                "avgTime should be the total (2+3+5+10+6+4)/2=15");
        assertEquals(40.0, accumulatedCycleMetrics.getIntervalFraction(0), EPSILON, "int-0 should be 2+10/30=40%");
        assertEquals(30.0, accumulatedCycleMetrics.getIntervalFraction(1), EPSILON, "int-1 should be 3+6/30=30%");
        assertEquals(30.0, accumulatedCycleMetrics.getIntervalFraction(2), EPSILON, "int-2 should be 5+4/30=30%");
        assertEquals(75.0, accumulatedCycleMetrics.getBusyFraction(), EPSILON, "expected 30/40=25%");
    }

    /**
     * The cycle should be able to accumulate up to 35 minutes. This tests longer intervals to see if an overflow
     * happens
     */
    @Test
    void longIntervals() {
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        final Metrics metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        final AccumulatedCycleMetrics accumulatedCycleMetrics = new AccumulatedCycleMetrics(metrics, definition);
        final CycleTracker tracker = new CycleTracker(time, accumulatedCycleMetrics);
        assertEquals(0.0, accumulatedCycleMetrics.getAvgCycleTime(), EPSILON, "avgTime should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getIntervalFraction(0), EPSILON, "int-0 should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getIntervalFraction(1), EPSILON, "int-1 should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getIntervalFraction(2), EPSILON, "int-2 should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getBusyFraction(), EPSILON, "busyTime should be 0");

        tracker.startCycle();
        time.tick(Duration.ofSeconds(1)); // interval 0 took 1sec
        tracker.intervalEnded(0);
        time.tick(Duration.ofSeconds(2)); // interval 1 took 2sec
        tracker.intervalEnded(1);
        time.tick(Duration.ofSeconds(997)); // interval 2 took 997sec
        tracker.cycleEnded();
        time.tick(Duration.ofSeconds(1000)); // idle time
        tracker.startCycle();

        assertEquals(
                Duration.ofSeconds(1000).toMillis(),
                accumulatedCycleMetrics.getAvgCycleTime(),
                EPSILON,
                "avgTime should be the total 1+2+997=1000sec");
        assertEquals(0.1, accumulatedCycleMetrics.getIntervalFraction(0), EPSILON, "int-0 should be 1/1000=0.1%");
        assertEquals(0.2, accumulatedCycleMetrics.getIntervalFraction(1), EPSILON, "int-1 should be 2/1000=0.2%");
        assertEquals(99.7, accumulatedCycleMetrics.getIntervalFraction(2), EPSILON, "int-2 should be 997/1000=99.7%");
        assertEquals(50.0, accumulatedCycleMetrics.getBusyFraction(), EPSILON, "expected 1000/2000=50%");
    }

    /**
     * Skips some intervals and expects that this equates to no time being added to them
     */
    @Test
    void skipIntervals() {
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        final Metrics metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        final AccumulatedCycleMetrics accumulatedCycleMetrics = new AccumulatedCycleMetrics(metrics, definition);
        final CycleTracker tracker = new CycleTracker(time, accumulatedCycleMetrics);
        assertEquals(0.0, accumulatedCycleMetrics.getAvgCycleTime(), EPSILON, "avgTime should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getIntervalFraction(0), EPSILON, "int-0 should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getIntervalFraction(1), EPSILON, "int-1 should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getIntervalFraction(2), EPSILON, "int-2 should be 0");
        assertEquals(0.0, accumulatedCycleMetrics.getBusyFraction(), EPSILON, "busyTime should be 0");

        tracker.startCycle();
        time.tick(Duration.ofMillis(1)); // interval 0 took 1ms
        tracker.intervalEnded(0);
        // interval 1 was skipped
        time.tick(Duration.ofMillis(1)); // interval 2 took 1ms
        tracker.intervalEnded(2);
        tracker.cycleEnded();

        time.tick(Duration.ofMillis(4)); // idle time

        tracker.startCycle();
        // interval 0 was skipped
        time.tick(Duration.ofMillis(1)); // interval 1 took 1ms
        tracker.intervalEnded(1);
        time.tick(Duration.ofMillis(1)); // interval 2 took 1ms
        tracker.intervalEnded(2);
        tracker.cycleEnded();

        assertEquals(
                2.0, accumulatedCycleMetrics.getAvgCycleTime(), EPSILON, "avgTime should be the total (1+1+1+1)/4=2");
        assertEquals(25.0, accumulatedCycleMetrics.getIntervalFraction(0), EPSILON, "int-0 should be 1+0/4=25%");
        assertEquals(25.0, accumulatedCycleMetrics.getIntervalFraction(1), EPSILON, "int-1 should be 0+1/4=25%");
        assertEquals(50.0, accumulatedCycleMetrics.getIntervalFraction(2), EPSILON, "int-2 should be 1+1/4=50%");
        assertEquals(50.0, accumulatedCycleMetrics.getBusyFraction(), EPSILON, "expected 4/8=50%");
    }

    @Test
    void badArgument() {
        final MetricKeyRegistry registry = mock(MetricKeyRegistry.class);
        when(registry.register(any(), any(), any())).thenReturn(true);
        final Metrics metrics = new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        final CycleTracker tracker = new CycleTracker(time, new AccumulatedCycleMetrics(metrics, definition));
        tracker.startCycle();
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> tracker.intervalEnded(-1), "negative intervals are illegal");
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> tracker.intervalEnded(definition.getNumIntervals()),
                "values higher than the number of intervals - 1 are illegal");
    }

    @Test
    void badDefinition() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CycleDefinition("bad", "bad", List.of()),
                "there must be at least a single interval");

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CycleDefinition("bad", "bad", List.of("name 1", "name 2"), List.of("description 1")),
                "the list sizes should match");
    }
}
