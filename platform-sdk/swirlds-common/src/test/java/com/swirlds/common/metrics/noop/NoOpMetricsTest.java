// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.noop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.metrics.DurationGauge;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.StatEntry;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.DoubleAccumulator;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("No-Op Metrics Test")
class NoOpMetricsTest {

    private void testCommonMethods(final Metric metric) {
        assertNotNull(metric.get(Metric.ValueType.VALUE));
        assertNotNull(metric.getCategory());
        assertNotNull(metric.getName());
        assertNotNull(metric.getDescription());
        assertNotNull(metric.getUnit());
        assertNotNull(metric.getFormat());
        assertNotNull(metric.getValueTypes());
        metric.reset();
    }

    @Test
    @DisplayName("Counter Test")
    void counterTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final Counter metric = metrics.getOrCreate(new Counter.Config("asdf", "asdf"));

            metric.increment();

            metric.add(0);
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Double Accumulator Test")
    void doubleAccumulatorTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final DoubleAccumulator metric = metrics.getOrCreate(new DoubleAccumulator.Config("asdf", "asdf"));

            metric.getInitialValue();
            metric.update(0);
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Double Gauge Test")
    void doubleGaugeTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final DoubleGauge metric = metrics.getOrCreate(new DoubleGauge.Config("asdf", "asdf"));

            metric.get();
            metric.set(0);
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Duration Gauge Test")
    void durationGaugeTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final DurationGauge metric =
                    metrics.getOrCreate(new DurationGauge.Config("asdf", "asdf", ChronoUnit.NANOS));

            metric.get();
            metric.getNanos();
            metric.set(Duration.ofSeconds(1));
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Function Gauge Test")
    void functionGaugeTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final Supplier<Integer> supplier = () -> 0;
            final FunctionGauge<Integer> metric =
                    metrics.getOrCreate(new FunctionGauge.Config<>("asdf", "asdf", Integer.class, supplier));

            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Integer Accumulator Test")
    void integerAccumulatorTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final IntegerAccumulator metric = metrics.getOrCreate(new IntegerAccumulator.Config("asdf", "asdf"));

            metric.get();
            metric.getInitialValue();
            metric.update(0);
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Integer Gauge Test")
    void integerGaugeTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final IntegerGauge metric = metrics.getOrCreate(new IntegerGauge.Config("asdf", "asdf"));

            metric.get();
            metric.set(0);
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Integer Pair Accumulator Test")
    void IntegerPairAccumulatorTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final IntegerPairAccumulator<Integer> metric = metrics.getOrCreate(
                    new IntegerPairAccumulator.Config<>("asdf", "asdf", Integer.class, (x, y) -> 0));

            assertNotNull(metric.get());
            metric.getLeft();
            metric.getRight();
            metric.update(0, 0);
            assertNotNull(metric.getDataType());
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Long Accumulator Test")
    void longAccumulatorTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final LongAccumulator metric = metrics.getOrCreate(new LongAccumulator.Config("asdf", "asdf"));

            metric.get();
            metric.getInitialValue();
            metric.update(0);
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Long Gauge Test")
    void longGaugeTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final LongGauge metric = metrics.getOrCreate(new LongGauge.Config("asdf", "asdf"));

            metric.get();
            metric.set(0);
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Running Average Metric Test")
    void runningAverageMetricTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final RunningAverageMetric metric = metrics.getOrCreate(new RunningAverageMetric.Config("asdf", "asdf"));

            metric.get(Metric.ValueType.VALUE);
            metric.get();
            metric.getHalfLife();
            metric.update(0);
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Speedometer Metric Test")
    void speedometerMetricTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final SpeedometerMetric metric = metrics.getOrCreate(new SpeedometerMetric.Config("asdf", "asdf"));

            metric.get(Metric.ValueType.VALUE);
            metric.get();
            metric.getHalfLife();
            metric.update(0);
            metric.cycle();

            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("Create Stat Entry Test")
    void createStatEntryTest() {
        assertDoesNotThrow(() -> {
            final Metrics metrics = new NoOpMetrics();
            final StatEntry metric =
                    metrics.getOrCreate(new StatEntry.Config<>("asdf", "asdf", Integer.class, () -> 0));

            assertNotNull(metric.getDataType());
            assertNotNull(metric.getReset());
            metric.getReset().accept(0.0);
            assertNotNull(metric.getStatsStringSupplier());
            assertNotNull(metric.getStatsStringSupplier().get());
            assertNotNull(metric.getResetStatsStringSupplier());
            assertNotNull(metric.getResetStatsStringSupplier().get());
            testCommonMethods(metric);
        });
    }

    @Test
    @DisplayName("getMetric() Test")
    void getMetricTest() {
        final Metrics metrics = new NoOpMetrics();

        // no category or metric
        assertNull(metrics.getMetric("foo", "bar"));

        final Metric metric1 = metrics.getOrCreate(new Counter.Config("foo", "1"));
        assertNotNull(metric1);
        assertSame(metric1, metrics.getMetric("foo", "1"));

        // category exists, metric does not
        assertNull(metrics.getMetric("foo", "bar"));

        metrics.remove("foo", "bar");
        assertNull(metrics.getMetric("foo", "bar"));
    }

    @Test
    @DisplayName("Find Metrics By Category Test")
    void findMetricsByCategoryTest() {
        final Metrics metrics = new NoOpMetrics();

        final Metric foo1 = metrics.getOrCreate(new Counter.Config("foo", "1"));
        final Metric foo2 = metrics.getOrCreate(new Counter.Config("foo", "2"));
        final Metric foo3 = metrics.getOrCreate(new Counter.Config("foo", "3"));

        final Metric bar1 = metrics.getOrCreate(new Counter.Config("bar", "1"));
        final Metric bar2 = metrics.getOrCreate(new Counter.Config("bar", "2"));

        final Metric baz1 = metrics.getOrCreate(new Counter.Config("baz", "1"));

        final Set<Metric> metricsFoo = new HashSet<>(metrics.findMetricsByCategory("foo"));
        assertEquals(3, metricsFoo.size());
        assertTrue(metricsFoo.contains(foo1));
        assertTrue(metricsFoo.contains(foo2));
        assertTrue(metricsFoo.contains(foo3));

        final Set<Metric> metricsBar = new HashSet<>(metrics.findMetricsByCategory("bar"));
        assertEquals(2, metricsBar.size());
        assertTrue(metricsBar.contains(bar1));
        assertTrue(metricsBar.contains(bar2));

        final Set<Metric> metricsBaz = new HashSet<>(metrics.findMetricsByCategory("baz"));
        assertEquals(1, metricsBaz.size());
        assertTrue(metricsBaz.contains(baz1));

        assertTrue(metrics.findMetricsByCategory("asdf").isEmpty());

        // Remove some metrics and test again
        metrics.remove("foo", "1");
        metrics.remove("bar", "1");
        metrics.remove("baz", "1");

        final Set<Metric> metricsFooReduced = new HashSet<>(metrics.findMetricsByCategory("foo"));
        assertEquals(2, metricsFooReduced.size());
        assertTrue(metricsFoo.contains(foo2));
        assertTrue(metricsFoo.contains(foo3));

        final Set<Metric> metricsBarReduced = new HashSet<>(metrics.findMetricsByCategory("bar"));
        assertEquals(1, metricsBarReduced.size());
        assertTrue(metricsBar.contains(bar2));

        assertTrue(metrics.findMetricsByCategory("baz").isEmpty());
        assertTrue(metrics.findMetricsByCategory("asdf").isEmpty());
    }

    @Test
    @DisplayName("getAll() Test")
    void getAllTest() {
        final Metrics metrics = new NoOpMetrics();

        final Metric foo1 = metrics.getOrCreate(new Counter.Config("foo", "1"));
        final Metric foo2 = metrics.getOrCreate(new Counter.Config("foo", "2"));
        final Metric foo3 = metrics.getOrCreate(new Counter.Config("foo", "3"));

        final Metric bar1 = metrics.getOrCreate(new Counter.Config("bar", "1"));
        final Metric bar2 = metrics.getOrCreate(new Counter.Config("bar", "2"));

        final Metric baz1 = metrics.getOrCreate(new Counter.Config("baz", "1"));

        final Set<Metric> allMetrics = new HashSet<>(metrics.getAll());
        assertEquals(6, allMetrics.size());
        assertTrue(allMetrics.contains(foo1));
        assertTrue(allMetrics.contains(foo2));
        assertTrue(allMetrics.contains(foo3));
        assertTrue(allMetrics.contains(bar1));
        assertTrue(allMetrics.contains(bar2));
        assertTrue(allMetrics.contains(baz1));

        // Remove some metrics and test again
        metrics.remove("foo", "1");
        metrics.remove("bar", "1");
        metrics.remove("baz", "1");

        final Set<Metric> allMetricsReduced = new HashSet<>(metrics.getAll());
        assertEquals(3, allMetricsReduced.size());
        assertTrue(allMetricsReduced.contains(foo2));
        assertTrue(allMetricsReduced.contains(foo3));
        assertTrue(allMetricsReduced.contains(bar2));
    }

    @Test
    @DisplayName("getOrCreate() Test")
    void getOrCreate() {
        final Metrics metrics = new NoOpMetrics();
        final Metric foo1 = metrics.getOrCreate(new Counter.Config("foo", "1"));
        assertSame(foo1, metrics.getOrCreate(new Counter.Config("foo", "1")));

        metrics.remove("foo", "1");

        final Metric foo1b = metrics.getOrCreate(new Counter.Config("foo", "1"));
        assertNotSame(foo1, foo1b);
    }

    @Test
    @DisplayName("Misc Metrics Test")
    void miscMetricsTest() {
        final Metrics metrics = new NoOpMetrics();

        // The following operations should not throw.
        assertDoesNotThrow(() -> {
            metrics.start();
            metrics.addUpdater(() -> {});
            metrics.removeUpdater(() -> {});
        });
    }

    @Test
    @DisplayName("remove() Test")
    void removeTest() {
        final Metrics metrics = new NoOpMetrics();

        metrics.getOrCreate(new Counter.Config("foo", "1"));
        metrics.getOrCreate(new Counter.Config("foo", "2"));
        metrics.getOrCreate(new Counter.Config("foo", "3"));

        metrics.getOrCreate(new Counter.Config("bar", "1"));
        metrics.getOrCreate(new Counter.Config("bar", "2"));

        final Metric baz1 = metrics.getOrCreate(new Counter.Config("baz", "1"));

        metrics.remove("foo", "1");
        metrics.remove(new Counter.Config("bar", "1"));
        metrics.remove(baz1);

        assertNull(metrics.getMetric("foo", "1"));
        assertNull(metrics.getMetric("bar", "1"));
        assertNull(metrics.getMetric("baz", "1"));
    }
}
