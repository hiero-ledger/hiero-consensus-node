// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.metrics.api.Metric.DataType.FLOAT;
import static com.swirlds.metrics.api.Metric.ValueType.MAX;
import static com.swirlds.metrics.api.Metric.ValueType.MIN;
import static com.swirlds.metrics.api.Metric.ValueType.STD_DEV;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformRunningAverageMetricTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    private static final double EPSILON = 1e-6;

    private static final MetricsConfig metricsConfig =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(MetricsConfig.class);

    @SuppressWarnings("removal")
    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        // when
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withHalfLife(Math.PI);
        final PlatformRunningAverageMetric metric = new PlatformRunningAverageMetric(config);

        // then
        assertEquals(CATEGORY, metric.getCategory(), "The category was not set correctly");
        assertEquals(NAME, metric.getName(), "The name was not set correctly");
        assertEquals(DESCRIPTION, metric.getDescription(), "The description was not set correctly");
        assertEquals(UNIT, metric.getUnit(), "The unit was not set correctly in the constructor");
        assertEquals(FORMAT, metric.getFormat(), "The format was not set correctly");
        assertEquals(Math.PI, metric.getHalfLife(), EPSILON, "HalfLife was not initialized correctly");
        assertEquals(0.0, metric.get(), EPSILON, "The value was not initialized correctly");
        assertEquals(0.0, metric.get(VALUE), EPSILON, "The value was not initialized correctly");
        assertEquals(0.0, metric.get(MIN), EPSILON, "The minimum was not initialized correctly");
        assertEquals(0.0, metric.get(MAX), EPSILON, "The maximum was not initialized correctly");
        assertEquals(0.0, metric.get(STD_DEV), EPSILON, "The standard deviation was not initialized correctly");
        assertNotNull(metric.getStatsBuffered(), "StatsBuffered was not initialized correctly");
        assertThat(metric.getValueTypes()).containsExactly(VALUE, MAX, MIN, STD_DEV);
    }

    @Test
    void testReset() {
        // given
        final FakeTime time = new FakeTime();
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);
        final RunningAverageMetric metric = new PlatformRunningAverageMetric(config, time);
        recordValues(metric, time, 0, 1000, Math.E);
        time.set(Duration.ofSeconds(1000));
        assertEquals(Math.E, metric.get(), EPSILON, "Mean should be " + Math.E);

        // when
        metric.reset();
        time.set(Duration.ofSeconds(1000, 1));

        // then
        assertEquals(Math.E, metric.get(), EPSILON, "Mean should (?) still be " + Math.E);

        // when
        time.set(Duration.ofSeconds(1000, 2));
        metric.update(Math.PI);

        // then
        assertEquals(Math.PI, metric.get(), EPSILON, "Mean should now be " + Math.PI);

        // when
        recordValues(metric, time, 1000, 2000, Math.PI);
        time.set(Duration.ofSeconds(2000));

        // then
        assertEquals(Math.PI, metric.get(), EPSILON, "Rate should be " + Math.PI);
    }

    @Test
    void testRegularUpdates() {
        // given
        final FakeTime time = new FakeTime();
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);
        final RunningAverageMetric metric = new PlatformRunningAverageMetric(config, time);

        for (int i = 0; i < 1000; i++) {
            // when
            time.set(Duration.ofSeconds(i).plusMillis(500));
            metric.update(Math.PI);
            time.set(Duration.ofSeconds(i + 1));
            final double mean = metric.get();

            // then
            assertEquals(Math.PI, mean, EPSILON, "Mean should be " + Math.PI);
        }
    }

    @Test
    void testDistributionForRegularUpdates() {
        // given
        final FakeTime time = new FakeTime();
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);
        final RunningAverageMetric metric = new PlatformRunningAverageMetric(config, time);

        // when
        recordValues(metric, time, 0, 1000, Math.PI);
        time.set(Duration.ofSeconds(1000));
        double avg = metric.get();

        // then
        assertEquals(Math.PI, avg, EPSILON, "Value should be " + Math.PI);
        assertEquals(Math.PI, metric.get(VALUE), EPSILON, "Mean value should be " + Math.PI);
        assertEquals(Math.PI, metric.get(MIN), EPSILON, "Min. should be " + Math.PI);
        assertEquals(Math.PI, metric.get(MAX), EPSILON, "Max. should be " + Math.PI);
        assertEquals(0.0, metric.get(STD_DEV), EPSILON, "Standard deviation should be around 0.0");
    }

    @Test
    void testDistributionForIncreasedValue() {
        // given
        final FakeTime time = new FakeTime();
        final RunningAverageMetric.Config config =
                new RunningAverageMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final RunningAverageMetric metric = new PlatformRunningAverageMetric(config, time);

        // when
        recordValues(metric, time, 0, 1000, Math.E);
        recordValues(metric, time, 1000, 1000 + (int) metricsConfig.halfLife(), Math.PI);
        time.set(Duration.ofSeconds(1000 + (int) metricsConfig.halfLife()));
        double avg = metric.get();

        // then
        final double expected = 0.5 * (Math.E + Math.PI);
        assertEquals(expected, avg, EPSILON, "Value should be " + expected);
        assertEquals(expected, metric.get(VALUE), EPSILON, "Mean value should be " + expected);
        assertEquals(expected, metric.get(MAX), EPSILON, "Max. value should be " + expected);
    }

    @Test
    void testDistributionForTwiceIncreasedValue() {
        // given
        final FakeTime time = new FakeTime();
        final RunningAverageMetric.Config config =
                new RunningAverageMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final RunningAverageMetric metric = new PlatformRunningAverageMetric(config, time);

        // when
        recordValues(metric, time, 0, 1000, Math.E);
        recordValues(metric, time, 1000, 1000 + (int) metricsConfig.halfLife(), Math.PI);
        recordValues(
                metric,
                time,
                1000 + (int) metricsConfig.halfLife(),
                1000 + 2 * (int) metricsConfig.halfLife(),
                Math.PI + 0.5 * (Math.PI - Math.E));
        time.set(Duration.ofSeconds(1000 + 2 * (int) metricsConfig.halfLife()));
        double avg = metric.get();

        // then
        assertEquals(Math.PI, avg, EPSILON, "Value should be " + Math.PI);
        assertEquals(Math.PI, metric.get(VALUE), EPSILON, "Mean value should be " + Math.PI);
        assertEquals(Math.PI, metric.get(MAX), EPSILON, "Max. value should be " + Math.PI);
    }

    @Test
    void testDistributionForDecreasedValue() {
        // given
        final FakeTime time = new FakeTime();
        final RunningAverageMetric.Config config =
                new RunningAverageMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final RunningAverageMetric metric = new PlatformRunningAverageMetric(config, time);

        // when
        recordValues(metric, time, 0, 1000, Math.PI);
        recordValues(metric, time, 1000, 1000 + (int) metricsConfig.halfLife(), Math.E);
        time.set(Duration.ofSeconds(1000 + (int) metricsConfig.halfLife()));
        double avg = metric.get();

        // then
        final double expected = 0.5 * (Math.E + Math.PI);
        assertEquals(expected, avg, EPSILON, "Value should be " + expected);
        assertEquals(expected, metric.get(VALUE), EPSILON, "Mean value should be " + expected);
        assertEquals(expected, metric.get(MIN), EPSILON, "Min. value should be " + expected);
    }

    @Test
    void testDistributionForTwiceDecreasedValue() {
        // given
        final FakeTime time = new FakeTime();
        final RunningAverageMetric.Config config =
                new RunningAverageMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final RunningAverageMetric metric = new PlatformRunningAverageMetric(config, time);

        // when
        recordValues(metric, time, 0, 1000, Math.PI);
        recordValues(metric, time, 1000, 1000 + (int) metricsConfig.halfLife(), Math.E);
        recordValues(
                metric,
                time,
                1000 + (int) metricsConfig.halfLife(),
                1000 + 2 * (int) metricsConfig.halfLife(),
                Math.E - 0.5 * (Math.PI - Math.E));
        time.set(Duration.ofSeconds(1000 + 2 * (int) metricsConfig.halfLife()));
        double avg = metric.get();

        // then
        assertEquals(Math.E, avg, EPSILON, "Value should be " + Math.E);
        assertEquals(Math.E, metric.get(VALUE), EPSILON, "Mean value should be " + Math.E);
        assertEquals(Math.E, metric.get(MIN), EPSILON, "Min. value should be " + Math.E);
    }

    @Test
    void testSnapshot() {
        // given
        final FakeTime time = new FakeTime();
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);
        final PlatformRunningAverageMetric metric = new PlatformRunningAverageMetric(config, time);

        // when
        recordValues(metric, time, 0, 1000, Math.PI);
        time.set(Duration.ofSeconds(1000));
        final double avg = metric.get();
        final List<SnapshotEntry> snapshot = metric.takeSnapshot();

        // then
        assertEquals(Math.PI, avg, EPSILON, "Value should be " + Math.PI);
        assertEquals(Math.PI, metric.get(VALUE), EPSILON, "Mean value should be " + Math.PI);
        assertEquals(Math.PI, metric.get(MIN), EPSILON, "Min. should be " + Math.PI);
        assertEquals(Math.PI, metric.get(MAX), EPSILON, "Max. should be " + Math.PI);
        assertEquals(0.0, metric.get(STD_DEV), EPSILON, "Standard deviation should be around 0.0");
        assertEquals(VALUE, snapshot.get(0).valueType());
        assertEquals(Math.PI, (double) snapshot.get(0).value(), EPSILON, "Mean value should be " + Math.PI);
        assertEquals(MAX, snapshot.get(1).valueType());
        assertEquals(Math.PI, (double) snapshot.get(1).value(), EPSILON, "Max. value should be " + Math.PI);
        assertEquals(MIN, snapshot.get(2).valueType());
        assertEquals(Math.PI, (double) snapshot.get(2).value(), EPSILON, "Min. value should be " + Math.PI);
        assertEquals(STD_DEV, snapshot.get(3).valueType());
        assertEquals(0.0, (double) snapshot.get(3).value(), EPSILON, "Standard deviation should be 0");
    }

    @Test
    void testInvalidGets() {
        // given
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);
        final RunningAverageMetric metric = new PlatformRunningAverageMetric(config);

        // then
        assertThrows(NullPointerException.class, () -> metric.get(null), "Calling get() with null should throw an IAE");
    }

    @Test
    void testEquals() {
        // given
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME);
        final RunningAverageMetric metric1 = new PlatformRunningAverageMetric(config);
        final RunningAverageMetric metric2 = new PlatformRunningAverageMetric(config);
        metric2.update(1000.0);

        // then
        assertThat(metric1)
                .isEqualTo(metric2)
                .hasSameHashCodeAs(metric2)
                .isNotEqualTo(new PlatformRunningAverageMetric(new RunningAverageMetric.Config("Other", NAME)))
                .isNotEqualTo(new PlatformRunningAverageMetric(new RunningAverageMetric.Config(CATEGORY, "Other")))
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config(CATEGORY, NAME)));
    }

    @Test
    void testToString() {
        // given
        final RunningAverageMetric.Config config = new RunningAverageMetric.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withHalfLife(Math.PI);
        final RunningAverageMetric metric = new PlatformRunningAverageMetric(config);

        // then
        assertThat(metric.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, FLOAT.toString(), "3.1415");
    }

    private static void recordValues(
            final RunningAverageMetric metric,
            final FakeTime time,
            final int start,
            final int stop,
            final double value) {
        for (int i = start; i < stop; i++) {
            time.set(Duration.ofSeconds(i).plusMillis(500));
            metric.update(value);
        }
    }
}
