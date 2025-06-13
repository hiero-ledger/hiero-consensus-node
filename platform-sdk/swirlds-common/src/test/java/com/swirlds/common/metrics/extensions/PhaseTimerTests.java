// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.extensions;

import static com.swirlds.common.metrics.extensions.TestPhases.BAR;
import static com.swirlds.common.metrics.extensions.TestPhases.BAZ;
import static com.swirlds.common.metrics.extensions.TestPhases.FOO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.FunctionGauge.Config;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.units.TimeUnit;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PhaseTimer Tests")
class PhaseTimerTests {

    @NonNull
    private Metrics buildMockMetrics(@NonNull final Function<MetricConfig<?, ?>, Metric> getOrCreateMetric) {
        final Metrics metrics = mock(Metrics.class);

        when(metrics.getOrCreate(any())).thenAnswer(invocation -> {
            final MetricConfig<?, ?> config = invocation.getArgument(0);
            return getOrCreateMetric.apply(config);
        });

        return metrics;
    }

    @Test
    @DisplayName("No Registered Metrics Test")
    void noRegisteredMetricsTest() {
        final Set<MetricConfig<?, ?>> registeredConfigs = new HashSet<>();
        final Metrics metrics = buildMockMetrics(config -> {
            registeredConfigs.add(config);
            return mock(Metric.class);
        });

        final FakeTime time = new FakeTime();

        final PhaseTimer<TestPhases> timer = new PhaseTimerBuilder<>(metrics, time, "test", TestPhases.class).build();

        // No metrics should have been registered
        assertEquals(0, registeredConfigs.size());

        // Even though we haven't registered metrics, it shouldn't cause problems if we update the phase
        timer.activatePhase(FOO);
        timer.activatePhase(BAR);
        timer.activatePhase(BAZ);
        timer.activatePhase(BAZ);
        timer.activatePhase(BAR);
        timer.activatePhase(FOO);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Only Fractional Metrics Enabled Test")
    void onlyFractionalMetricsEnabledTest() {
        final AtomicReference<FunctionGauge.Config<Double>> fooConfig = new AtomicReference<>();
        final AtomicReference<FunctionGauge.Config<Double>> barConfig = new AtomicReference<>();
        final AtomicReference<FunctionGauge.Config<Double>> bazConfig = new AtomicReference<>();

        final Metrics metrics = buildMockMetrics(config -> {
            assertEquals("test", config.getCategory());
            assertEquals(FunctionGauge.Config.class, config.getClass());
            if (config.getName().equals("TestPhases_fraction_FOO")) {
                assertNull(fooConfig.get());
                fooConfig.set((Config<Double>) config);
                return mock(Metric.class);
            } else if (config.getName().equals("TestPhases_fraction_BAR")) {
                assertNull(barConfig.get());
                barConfig.set((Config<Double>) config);
                return mock(Metric.class);
            } else if (config.getName().equals("TestPhases_fraction_BAZ")) {
                assertNull(bazConfig.get());
                bazConfig.set((Config<Double>) config);
                return mock(Metric.class);
            } else {
                throw new AssertionError("Unexpected metric name: " + config.getName());
            }
        });

        final FakeTime time = new FakeTime();

        final PhaseTimer<TestPhases> timer = new PhaseTimerBuilder<>(metrics, time, "test", TestPhases.class)
                .enableFractionalMetrics()
                .build();

        assertNotNull(fooConfig.get());
        assertNotNull(barConfig.get());
        assertNotNull(bazConfig.get());

        // Starting phase is FOO
        time.tick(Duration.ofSeconds(1));
        timer.activatePhase(FOO);
        time.tick(Duration.ofSeconds(2));
        timer.activatePhase(BAR);
        time.tick(Duration.ofSeconds(3));
        timer.activatePhase(BAZ);
        time.tick(Duration.ofSeconds(4));
        timer.activatePhase(BAZ);
        time.tick(Duration.ofSeconds(5));
        timer.activatePhase(BAR);
        time.tick(Duration.ofSeconds(6));
        timer.activatePhase(FOO);

        final double totalSeconds1 = 21.0;

        final double expectedFooSeconds1 = 3.0;
        final double expectedFooFraction = expectedFooSeconds1 / totalSeconds1;
        final double actualFooFraction = fooConfig.get().getSupplier().get();
        assertEquals(expectedFooFraction, actualFooFraction, 0.0001);

        final double expectedBarSeconds1 = 9.0;
        final double expectedBarFraction = expectedBarSeconds1 / totalSeconds1;
        final double actualBarFraction = barConfig.get().getSupplier().get();
        assertEquals(expectedBarFraction, actualBarFraction, 0.0001);

        final double expectedBazSeconds1 = 9.0;
        final double expectedBazFraction = expectedBazSeconds1 / totalSeconds1;
        final double actualBazFraction = bazConfig.get().getSupplier().get();
        assertEquals(expectedBazFraction, actualBazFraction, 0.0001);

        for (int i = 0; i < 100; i++) {
            time.tick(Duration.ofSeconds(1));
            timer.activatePhase(BAR);
            time.tick(Duration.ofSeconds(1));
            timer.activatePhase(BAZ);
        }

        final double totalSeconds2 = 200.0;

        final double expectedFooSeconds2 = 1.0;
        final double expectedFooFraction2 = expectedFooSeconds2 / totalSeconds2;
        final double actualFooFraction2 = fooConfig.get().getSupplier().get();
        assertEquals(expectedFooFraction2, actualFooFraction2, 0.0001);

        final double expectedBarSeconds2 = 100.0;
        final double expectedBarFraction2 = expectedBarSeconds2 / totalSeconds2;
        final double actualBarFraction2 = barConfig.get().getSupplier().get();
        assertEquals(expectedBarFraction2, actualBarFraction2, 0.0001);

        final double expectedBazSeconds2 = 99.0;
        final double expectedBazFraction2 = expectedBazSeconds2 / totalSeconds2;
        final double actualBazFraction2 = bazConfig.get().getSupplier().get();
        assertEquals(expectedBazFraction2, actualBazFraction2, 0.0001);
    }

    private RunningAverageMetric buildMockRunningAverageMetric(@NonNull final List<Double> updates) {
        final RunningAverageMetric metric = mock(RunningAverageMetric.class);
        doAnswer(invocation -> {
                    final double value = invocation.getArgument(0);
                    updates.add(value);
                    return null;
                })
                .when(metric)
                .update(anyDouble());
        return metric;
    }

    @Test
    @DisplayName("Only Absolute Time Metrics Enabled Test")
    void onlyAbsoluteTimeMetricsTest() {
        final AtomicBoolean fooMetricRegistered = new AtomicBoolean(false);
        final List<Double> fooUpdates = new ArrayList<>();

        final AtomicBoolean barMetricRegistered = new AtomicBoolean(false);
        final List<Double> barUpdates = new ArrayList<>();

        final AtomicBoolean bazMetricRegistered = new AtomicBoolean(false);
        final List<Double> bazUpdates = new ArrayList<>();

        final Metrics metrics = buildMockMetrics(config -> {
            assertEquals("test", config.getCategory());
            assertEquals(RunningAverageMetric.Config.class, config.getClass());
            if (config.getName().equals("TestPhases_time_FOO")) {
                assertFalse(fooMetricRegistered.get());
                fooMetricRegistered.set(true);
                return buildMockRunningAverageMetric(fooUpdates);
            } else if (config.getName().equals("TestPhases_time_BAR")) {
                assertFalse(barMetricRegistered.get());
                barMetricRegistered.set(true);
                return buildMockRunningAverageMetric(barUpdates);
            } else if (config.getName().equals("TestPhases_time_BAZ")) {
                assertFalse(bazMetricRegistered.get());
                bazMetricRegistered.set(true);
                return buildMockRunningAverageMetric(bazUpdates);
            } else {
                throw new AssertionError("Unexpected metric name: " + config.getName());
            }
        });

        final FakeTime time = new FakeTime();

        final PhaseTimer<TestPhases> timer = new PhaseTimerBuilder<>(metrics, time, "test", TestPhases.class)
                .enableAbsoluteTimeMetrics()
                .setAbsoluteUnit(TimeUnit.UNIT_SECONDS) // extra chonky
                .build();

        assertTrue(fooMetricRegistered.get());
        assertTrue(barMetricRegistered.get());
        assertTrue(bazMetricRegistered.get());

        time.tick(Duration.ofSeconds(1));
        timer.activatePhase(FOO);
        time.tick(Duration.ofSeconds(2));
        timer.activatePhase(BAR);
        time.tick(Duration.ofSeconds(3));
        timer.activatePhase(BAZ);
        time.tick(Duration.ofSeconds(4));
        timer.activatePhase(BAZ);
        time.tick(Duration.ofSeconds(5));
        timer.activatePhase(BAR);
        time.tick(Duration.ofSeconds(6));
        timer.activatePhase(FOO);

        for (int i = 0; i < 100; i++) {
            time.tick(Duration.ofSeconds(1));
            timer.activatePhase(BAR);
            time.tick(Duration.ofSeconds(1));
            timer.activatePhase(BAZ);
        }

        assertEquals(2, fooUpdates.size());
        assertEquals(3.0, fooUpdates.get(0));
        assertEquals(1.0, fooUpdates.get(1));

        assertEquals(102, barUpdates.size());
        assertEquals(3.0, barUpdates.get(0));
        assertEquals(6.0, barUpdates.get(1));
        for (int i = 2; i < 102; i++) {
            assertEquals(1.0, barUpdates.get(i));
        }

        assertEquals(100, bazUpdates.size());
        assertEquals(bazUpdates.get(0), 9.0);
        for (int i = 1; i < 100; i++) {
            assertEquals(1.0, bazUpdates.get(i));
        }
    }

    @Test
    @DisplayName("All Metrics Enabled Test")
    void allMetricsEnabledTest() {
        final AtomicReference<FunctionGauge.Config<Double>> fooFractionConfig = new AtomicReference<>();
        final AtomicReference<FunctionGauge.Config<Double>> barFractionConfig = new AtomicReference<>();
        final AtomicReference<FunctionGauge.Config<Double>> bazFractionConfig = new AtomicReference<>();

        final AtomicBoolean fooTimeMetricRegistered = new AtomicBoolean(false);
        final List<Double> fooTimeUpdates = new ArrayList<>();

        final AtomicBoolean barTimeMetricRegistered = new AtomicBoolean(false);
        final List<Double> barTimeUpdates = new ArrayList<>();

        final AtomicBoolean bazTimeMetricRegistered = new AtomicBoolean(false);
        final List<Double> bazTimeUpdates = new ArrayList<>();

        final Metrics metrics = buildMockMetrics(config -> {
            assertEquals("test", config.getCategory());

            if (config instanceof FunctionGauge.Config<?>) {
                if (config.getName().equals("TestPhases_fraction_FOO")) {
                    assertNull(fooFractionConfig.get());
                    fooFractionConfig.set((Config<Double>) config);
                    return mock(Metric.class);
                } else if (config.getName().equals("TestPhases_fraction_BAR")) {
                    assertNull(barFractionConfig.get());
                    barFractionConfig.set((Config<Double>) config);
                    return mock(Metric.class);
                } else if (config.getName().equals("TestPhases_fraction_BAZ")) {
                    assertNull(bazFractionConfig.get());
                    bazFractionConfig.set((Config<Double>) config);
                    return mock(Metric.class);
                } else {
                    throw new AssertionError("Unexpected metric name: " + config.getName());
                }
            } else if (config instanceof RunningAverageMetric.Config) {
                if (config.getName().equals("TestPhases_time_FOO")) {
                    assertFalse(fooTimeMetricRegistered.get());
                    fooTimeMetricRegistered.set(true);
                    return buildMockRunningAverageMetric(fooTimeUpdates);
                } else if (config.getName().equals("TestPhases_time_BAR")) {
                    assertFalse(barTimeMetricRegistered.get());
                    barTimeMetricRegistered.set(true);
                    return buildMockRunningAverageMetric(barTimeUpdates);
                } else if (config.getName().equals("TestPhases_time_BAZ")) {
                    assertFalse(bazTimeMetricRegistered.get());
                    bazTimeMetricRegistered.set(true);
                    return buildMockRunningAverageMetric(bazTimeUpdates);
                } else {
                    throw new AssertionError("Unexpected metric name: " + config.getName());
                }
            } else {
                throw new AssertionError(
                        "Unexpected metric type: " + config.getClass().getName());
            }
        });

        final FakeTime time = new FakeTime();

        final PhaseTimer<TestPhases> timer = new PhaseTimerBuilder<>(metrics, time, "test", TestPhases.class)
                .enableFractionalMetrics()
                .enableAbsoluteTimeMetrics()
                .setAbsoluteUnit(TimeUnit.UNIT_SECONDS)
                .build();

        assertNotNull(fooFractionConfig.get());
        assertNotNull(barFractionConfig.get());
        assertNotNull(bazFractionConfig.get());

        assertTrue(fooTimeMetricRegistered.get());
        assertTrue(barTimeMetricRegistered.get());
        assertTrue(bazTimeMetricRegistered.get());

        // Starting phase is FOO
        time.tick(Duration.ofSeconds(1));
        timer.activatePhase(FOO);
        time.tick(Duration.ofSeconds(2));
        timer.activatePhase(BAR);
        time.tick(Duration.ofSeconds(3));
        timer.activatePhase(BAZ);
        time.tick(Duration.ofSeconds(4));
        timer.activatePhase(BAZ);
        time.tick(Duration.ofSeconds(5));
        timer.activatePhase(BAR);
        time.tick(Duration.ofSeconds(6));
        timer.activatePhase(FOO);

        final double totalSeconds1 = 21.0;

        final double expectedFooSeconds1 = 3.0;
        final double expectedFooFraction = expectedFooSeconds1 / totalSeconds1;
        final double actualFooFraction = fooFractionConfig.get().getSupplier().get();
        assertEquals(expectedFooFraction, actualFooFraction, 0.0001);

        final double expectedBarSeconds1 = 9.0;
        final double expectedBarFraction = expectedBarSeconds1 / totalSeconds1;
        final double actualBarFraction = barFractionConfig.get().getSupplier().get();
        assertEquals(expectedBarFraction, actualBarFraction, 0.0001);

        final double expectedBazSeconds1 = 9.0;
        final double expectedBazFraction = expectedBazSeconds1 / totalSeconds1;
        final double actualBazFraction = bazFractionConfig.get().getSupplier().get();
        assertEquals(expectedBazFraction, actualBazFraction, 0.0001);

        for (int i = 0; i < 100; i++) {
            time.tick(Duration.ofSeconds(1));
            timer.activatePhase(BAR);
            time.tick(Duration.ofSeconds(1));
            timer.activatePhase(BAZ);
        }

        final double totalSeconds2 = 200.0;

        final double expectedFooSeconds2 = 1.0;
        final double expectedFooFraction2 = expectedFooSeconds2 / totalSeconds2;
        final double actualFooFraction2 = fooFractionConfig.get().getSupplier().get();
        assertEquals(expectedFooFraction2, actualFooFraction2, 0.0001);

        final double expectedBarSeconds2 = 100.0;
        final double expectedBarFraction2 = expectedBarSeconds2 / totalSeconds2;
        final double actualBarFraction2 = barFractionConfig.get().getSupplier().get();
        assertEquals(expectedBarFraction2, actualBarFraction2, 0.0001);

        final double expectedBazSeconds2 = 99.0;
        final double expectedBazFraction2 = expectedBazSeconds2 / totalSeconds2;
        final double actualBazFraction2 = bazFractionConfig.get().getSupplier().get();
        assertEquals(expectedBazFraction2, actualBazFraction2, 0.0001);

        assertEquals(2, fooTimeUpdates.size());
        assertEquals(3.0, fooTimeUpdates.get(0));
        assertEquals(1.0, fooTimeUpdates.get(1));

        assertEquals(102, barTimeUpdates.size());
        assertEquals(3.0, barTimeUpdates.get(0));
        assertEquals(6.0, barTimeUpdates.get(1));
        for (int i = 2; i < 102; i++) {
            assertEquals(1.0, barTimeUpdates.get(i));
        }

        assertEquals(100, bazTimeUpdates.size());
        assertEquals(9.0, bazTimeUpdates.get(0));
        for (int i = 1; i < 100; i++) {
            assertEquals(1.0, bazTimeUpdates.get(i));
        }
    }

    /**
     * Similar to {@link #allMetricsEnabledTest()} but with a different configuration.
     */
    @Test
    @DisplayName("Alternate Configuration Test")
    void alternateConfigurationTest() {
        final AtomicReference<FunctionGauge.Config<Double>> fooFractionConfig = new AtomicReference<>();
        final AtomicReference<FunctionGauge.Config<Double>> barFractionConfig = new AtomicReference<>();
        final AtomicReference<FunctionGauge.Config<Double>> bazFractionConfig = new AtomicReference<>();

        final AtomicBoolean fooTimeMetricRegistered = new AtomicBoolean(false);
        final List<Double> fooTimeUpdates = new ArrayList<>();

        final AtomicBoolean barTimeMetricRegistered = new AtomicBoolean(false);
        final List<Double> barTimeUpdates = new ArrayList<>();

        final AtomicBoolean bazTimeMetricRegistered = new AtomicBoolean(false);
        final List<Double> bazTimeUpdates = new ArrayList<>();

        final Metrics metrics = buildMockMetrics(config -> {
            assertEquals("test", config.getCategory());

            if (config instanceof FunctionGauge.Config<?>) {
                if (config.getName().equals("asdf_fraction_FOO")) {
                    assertNull(fooFractionConfig.get());
                    fooFractionConfig.set((Config<Double>) config);
                    return mock(Metric.class);
                } else if (config.getName().equals("asdf_fraction_BAR")) {
                    assertNull(barFractionConfig.get());
                    barFractionConfig.set((Config<Double>) config);
                    return mock(Metric.class);
                } else if (config.getName().equals("asdf_fraction_BAZ")) {
                    assertNull(bazFractionConfig.get());
                    bazFractionConfig.set((Config<Double>) config);
                    return mock(Metric.class);
                } else {
                    throw new AssertionError("Unexpected metric name: " + config.getName());
                }
            } else if (config instanceof RunningAverageMetric.Config) {
                if (config.getName().equals("asdf_time_FOO")) {
                    assertFalse(fooTimeMetricRegistered.get());
                    fooTimeMetricRegistered.set(true);
                    return buildMockRunningAverageMetric(fooTimeUpdates);
                } else if (config.getName().equals("asdf_time_BAR")) {
                    assertFalse(barTimeMetricRegistered.get());
                    barTimeMetricRegistered.set(true);
                    return buildMockRunningAverageMetric(barTimeUpdates);
                } else if (config.getName().equals("asdf_time_BAZ")) {
                    assertFalse(bazTimeMetricRegistered.get());
                    bazTimeMetricRegistered.set(true);
                    return buildMockRunningAverageMetric(bazTimeUpdates);
                } else {
                    throw new AssertionError("Unexpected metric name: " + config.getName());
                }
            } else {
                throw new AssertionError(
                        "Unexpected metric type: " + config.getClass().getName());
            }
        });

        final FakeTime time = new FakeTime();

        final PhaseTimer<TestPhases> timer = new PhaseTimerBuilder<>(metrics, time, "test", TestPhases.class)
                .enableFractionalMetrics()
                .enableAbsoluteTimeMetrics()
                .setAbsoluteUnit(TimeUnit.UNIT_MILLISECONDS)
                .setInitialPhase(BAR)
                .setMetricsNamePrefix("asdf")
                .build();

        assertNotNull(fooFractionConfig.get());
        assertNotNull(barFractionConfig.get());
        assertNotNull(bazFractionConfig.get());

        assertTrue(fooTimeMetricRegistered.get());
        assertTrue(barTimeMetricRegistered.get());
        assertTrue(bazTimeMetricRegistered.get());

        // Starting phase is BAR
        time.tick(Duration.ofSeconds(1));
        timer.activatePhase(FOO);
        time.tick(Duration.ofSeconds(2));
        timer.activatePhase(BAR);
        time.tick(Duration.ofSeconds(3));
        timer.activatePhase(BAZ);
        time.tick(Duration.ofSeconds(4));
        timer.activatePhase(BAZ);
        time.tick(Duration.ofSeconds(5));
        timer.activatePhase(BAR);
        time.tick(Duration.ofSeconds(6));
        timer.activatePhase(FOO);

        final double totalSeconds1 = 21.0;

        final double expectedFooSeconds1 = 2.0;
        final double expectedFooFraction = expectedFooSeconds1 / totalSeconds1;
        final double actualFooFraction = fooFractionConfig.get().getSupplier().get();
        assertEquals(expectedFooFraction, actualFooFraction, 0.0001);

        final double expectedBarSeconds1 = 10.0;
        final double expectedBarFraction = expectedBarSeconds1 / totalSeconds1;
        final double actualBarFraction = barFractionConfig.get().getSupplier().get();
        assertEquals(expectedBarFraction, actualBarFraction, 0.0001);

        final double expectedBazSeconds1 = 9.0;
        final double expectedBazFraction = expectedBazSeconds1 / totalSeconds1;
        final double actualBazFraction = bazFractionConfig.get().getSupplier().get();
        assertEquals(expectedBazFraction, actualBazFraction, 0.0001);

        for (int i = 0; i < 100; i++) {
            time.tick(Duration.ofSeconds(1));
            timer.activatePhase(BAR);
            time.tick(Duration.ofSeconds(1));
            timer.activatePhase(BAZ);
        }

        final double totalSeconds2 = 200.0;

        final double expectedFooSeconds2 = 1.0;
        final double expectedFooFraction2 = expectedFooSeconds2 / totalSeconds2;
        final double actualFooFraction2 = fooFractionConfig.get().getSupplier().get();
        assertEquals(expectedFooFraction2, actualFooFraction2, 0.0001);

        final double expectedBarSeconds2 = 100.0;
        final double expectedBarFraction2 = expectedBarSeconds2 / totalSeconds2;
        final double actualBarFraction2 = barFractionConfig.get().getSupplier().get();
        assertEquals(expectedBarFraction2, actualBarFraction2, 0.0001);

        final double expectedBazSeconds2 = 99.0;
        final double expectedBazFraction2 = expectedBazSeconds2 / totalSeconds2;
        final double actualBazFraction2 = bazFractionConfig.get().getSupplier().get();
        assertEquals(expectedBazFraction2, actualBazFraction2, 0.0001);

        assertEquals(2, fooTimeUpdates.size());
        assertEquals(fooTimeUpdates.get(0), 2000.0);
        assertEquals(fooTimeUpdates.get(1), 1000.0);

        assertEquals(103, barTimeUpdates.size());
        assertEquals(barTimeUpdates.get(0), 1000.0);
        assertEquals(barTimeUpdates.get(1), 3000.0);
        assertEquals(barTimeUpdates.get(2), 6000.0);
        for (int i = 3; i < 102; i++) {
            assertEquals(barTimeUpdates.get(i), 1000.0);
        }

        assertEquals(100, bazTimeUpdates.size());
        assertEquals(bazTimeUpdates.get(0), 9000.0);
        for (int i = 1; i < 100; i++) {
            assertEquals(bazTimeUpdates.get(i), 1000.0);
        }
    }
}
