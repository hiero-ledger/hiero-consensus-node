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
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformSpeedometerMetricTest {

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
        final SpeedometerMetric.Config config = new SpeedometerMetric.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withHalfLife(Math.PI);
        final PlatformSpeedometerMetric metric = new PlatformSpeedometerMetric(config);

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
        final SpeedometerMetric.Config config =
                new SpeedometerMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);
        sendCycles(metric, time, 0, 1000, 1000);
        time.set(Duration.ofSeconds(1000));
        assertEquals(1000.0, metric.get(), 0.001, "Rate should be 1000.0");

        // when
        metric.reset();
        time.set(Duration.ofSeconds(1000, 1));

        // then
        assertEquals(0.0, metric.get(), EPSILON, "Rate should be reset to 0.0");

        // when
        sendCycles(metric, time, 1000, 2000, 2000);
        time.set(Duration.ofSeconds(2000));

        // then
        assertEquals(2000.0, metric.get(), 0.001, "Rate should be 2000.0");
    }

    @Test
    void testRegularRateOnePerSecond() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config =
                new SpeedometerMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        for (int i = 0; i < 1000; i++) {
            // when
            time.set(Duration.ofSeconds(i).plusMillis(500));
            metric.cycle();
            time.set(Duration.ofSeconds(i + 1));
            final double rate = metric.get();

            // then
            assertEquals(1.0, rate, 0.001, "Rate should be 1.0");
        }
    }

    @Test
    void testRegularRateFivePerSecond() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config =
                new SpeedometerMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        // when
        for (int i = 0; i < 1000; i++) {
            // when
            for (int j = 1; j <= 5; j++) {
                time.set(Duration.ofSeconds(i).plus(Duration.ofSeconds(j).dividedBy(6)));
                metric.cycle();
            }
            time.set(Duration.ofSeconds(i + 1));
            final double rate = metric.get();

            // then
            assertEquals(5.0, rate, 0.001, "Rate should be 5.0");
        }
    }

    @Test
    void testRegularRateFivePerSecondWithUpdate() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config =
                new SpeedometerMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        // when
        for (int i = 0; i < 1000; i++) {
            // when
            time.set(Duration.ofSeconds(i).plusMillis(500));
            metric.update(5);
            time.set(Duration.ofSeconds(i + 1));
            final double rate = metric.get();

            // then
            assertEquals(5.0, rate, 0.01, "Rate should be 5.0");
        }
    }

    @Test
    void testDistributionForRegularRate() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config =
                new SpeedometerMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        // when
        sendCycles(metric, time, 0, 1000, 1000);
        time.set(Duration.ofSeconds(1000));
        double rate = metric.get();

        // then
        assertEquals(1000.0, rate, 0.001, "Rate should be 1000.0");
        assertEquals(1000.0, metric.get(VALUE), 0.001, "Mean rate should be 1000.0");
        assertEquals(1000.0, metric.get(MIN), 0.1, "Min. rate should be about 1000.0");
        assertEquals(1000.0, metric.get(MAX), 0.1, "Max. rate should be about 1000.0");
        assertEquals(0.02, metric.get(STD_DEV), 0.001, "Standard deviation should be around 0.02");
    }

    @Disabled("Fails currently. Should be enabled with new speedometer implementation")
    @Test
    void testDistributionForRegularRateWithUpdates() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config = new SpeedometerMetric.Config(CATEGORY, NAME);
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        // when
        for (int i = 1; i <= 1000; i++) {
            time.set(Duration.ofSeconds(i));
            metric.update(1000);
        }
        time.set(Duration.ofSeconds(1000));
        double rate = metric.get();

        // then
        assertEquals(1000.0, rate, 0.001, "Rate should be 1000.0");
        assertEquals(1000.0, metric.get(VALUE), 0.001, "Mean rate should be 1000.0");
        assertEquals(1000.0, metric.get(MIN), 0.1, "Min. rate should be about 1000.0");
        assertEquals(1000.0, metric.get(MAX), 0.1, "Max. rate should be about 1000.0");
        assertEquals(0.0, metric.get(STD_DEV), EPSILON, "Standard deviation should be 0.0");
    }

    @Test
    @Disabled("This test needs to be investigated")
    void testDistributionForIncreasedRate() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config = new SpeedometerMetric.Config(CATEGORY, NAME);
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        // when
        sendCycles(metric, time, 0, 1000, 1000);
        sendCycles(metric, time, 1000, 1000 + (int) metricsConfig.halfLife(), 2000);
        time.set(Duration.ofSeconds(1000 + (int) metricsConfig.halfLife()));
        double rate = metric.get();

        // then
        assertEquals(1500.0, rate, 0.001, "Rate should be 1500.0");
        assertEquals(1500.0, metric.get(VALUE), 0.001, "Mean rate should be 1500.0");
        assertEquals(1500.0, metric.get(MAX), 0.1, "Max. rate should be 1500.0");
    }

    @SuppressWarnings("removal")
    @Test
    void testDistributionForTwiceIncreasedRate() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config =
                new SpeedometerMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final PlatformSpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        // when
        sendCycles(metric, time, 0, 1000, 1000);
        sendCycles(metric, time, 1000, 1000 + (int) metricsConfig.halfLife(), 5000);
        sendCycles(
                metric, time, 1000 + (int) metricsConfig.halfLife(), 1000 + 2 * (int) metricsConfig.halfLife(), 7000);
        time.set(Duration.ofSeconds(1000 + 2 * (int) metricsConfig.halfLife()));
        double rate = metric.get();

        // then
        final StatsBuffered buffered = metric.getStatsBuffered();
        assertEquals(5000.0, rate, 0.001, "Rate should be 5000.0");
        assertEquals(5000.0, metric.get(VALUE), 0.001, "Mean rate should be 5000.0");
        assertEquals(5000.0, metric.get(MAX), 0.1, "Max. rate should be 5000.0");
    }

    @Test
    @Disabled("This test needs to be investigated")
    void testDistributionForDecreasedRate() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config = new SpeedometerMetric.Config(CATEGORY, NAME);
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        // when
        sendCycles(metric, time, 0, 1000, 1000);
        sendCycles(metric, time, 1000, 1000 + (int) metricsConfig.halfLife(), 500);
        time.set(Duration.ofSeconds(1000 + (int) metricsConfig.halfLife()));
        double rate = metric.get();

        // then
        assertEquals(750.0, rate, 0.001, "Rate should be 750.0");
        assertEquals(750.0, metric.get(VALUE), 0.001, "Mean rate should be 750.0");
        assertEquals(750.0, metric.get(MIN), 0.15, "Min. rate should be 750.0");
    }

    @Test
    @Disabled("This test needs to be investigated")
    void testDistributionForTwiceDecreasedRate() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config = new SpeedometerMetric.Config(CATEGORY, NAME);
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        // when
        sendCycles(metric, time, 0, 1000, 7000);
        sendCycles(metric, time, 1000, 1000 + (int) metricsConfig.halfLife(), 5000);
        sendCycles(
                metric, time, 1000 + (int) metricsConfig.halfLife(), 1000 + 2 * (int) metricsConfig.halfLife(), 2000);
        time.set(Duration.ofSeconds(1000 + 2 * (int) metricsConfig.halfLife()));
        double rate = metric.get();

        // then
        assertEquals(4000.0, rate, 0.001, "Rate should be 4000.0");
        assertEquals(4000.0, metric.get(VALUE), 0.001, "Mean rate should be 4000.0");
        assertEquals(4000.0, metric.get(MIN), 0.15, "Min. rate should be 4000.0");
    }

    @Test
    void testSnapshot() {
        // given
        final FakeTime time = new FakeTime();
        final SpeedometerMetric.Config config =
                new SpeedometerMetric.Config(CATEGORY, NAME).withHalfLife(metricsConfig.halfLife());
        final PlatformSpeedometerMetric metric = new PlatformSpeedometerMetric(config, time);

        // when
        sendCycles(metric, time, 0, 1000, 1000);
        time.set(Duration.ofSeconds(1000));
        final double rate = metric.get();
        final List<SnapshotEntry> snapshot = metric.takeSnapshot();

        // then
        assertEquals(1000.0, rate, 0.001, "Rate should be 1000.0");
        assertEquals(1000.0, metric.get(VALUE), 0.001, "Mean rate should be 1000.0");
        assertEquals(1000.0, metric.get(MIN), 0.1, "Min. rate should be about 1000.0");
        assertEquals(1000.0, metric.get(MAX), 0.1, "Max. rate should be about 1000.0");
        assertEquals(0.02, metric.get(STD_DEV), 0.001, "Standard deviation should be around 0.02");
        assertEquals(VALUE, snapshot.get(0).valueType());
        assertEquals(1000.0, (double) snapshot.get(0).value(), 0.001, "Mean rate should be 1000.0");
        assertEquals(MAX, snapshot.get(1).valueType());
        assertEquals(1000.0, (double) snapshot.get(1).value(), 0.1, "Mean rate should be 1000.0");
        assertEquals(MIN, snapshot.get(2).valueType());
        assertEquals(1000.0, (double) snapshot.get(2).value(), 0.1, "Mean rate should be 1000.0");
        assertEquals(STD_DEV, snapshot.get(3).valueType());
        assertEquals(0.02, (double) snapshot.get(3).value(), 0.001, "Mean rate should be 1000.0");
    }

    @Test
    void testInvalidGets() {
        // given
        final SpeedometerMetric.Config config = new SpeedometerMetric.Config(CATEGORY, NAME);
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config);

        // then
        assertThrows(NullPointerException.class, () -> metric.get(null), "Calling get() with null should throw an IAE");
    }

    @Test
    void testEquals() {
        // given
        final SpeedometerMetric.Config config = new SpeedometerMetric.Config(CATEGORY, NAME);
        final SpeedometerMetric metric1 = new PlatformSpeedometerMetric(config);
        final SpeedometerMetric metric2 = new PlatformSpeedometerMetric(config);
        metric2.cycle();

        // then
        assertThat(metric1)
                .isEqualTo(metric2)
                .hasSameHashCodeAs(metric2)
                .isNotEqualTo(new PlatformSpeedometerMetric(new SpeedometerMetric.Config("Other", NAME)))
                .isNotEqualTo(new PlatformSpeedometerMetric(new SpeedometerMetric.Config(CATEGORY, "Other")))
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config(CATEGORY, NAME)));
    }

    @Test
    void testToString() {
        // given
        final SpeedometerMetric.Config config = new SpeedometerMetric.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withHalfLife(Math.PI);
        final SpeedometerMetric metric = new PlatformSpeedometerMetric(config);

        // then
        assertThat(metric.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, FLOAT.toString(), "3.1415");
    }

    private static void sendCycles(
            final SpeedometerMetric metric, final FakeTime time, final int start, final int stop, final int rate) {
        for (int i = start; i < stop; i++) {
            for (int j = 1; j <= rate; j++) {
                time.set(Duration.ofSeconds(i).plus(Duration.ofSeconds(j).dividedBy(rate + 1)));
                metric.cycle();
            }
        }
    }
}
