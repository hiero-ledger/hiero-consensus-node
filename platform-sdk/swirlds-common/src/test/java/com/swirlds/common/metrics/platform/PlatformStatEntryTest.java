// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.metrics.api.Metric.DataType.STRING;
import static com.swirlds.metrics.api.Metric.ValueType.MAX;
import static com.swirlds.metrics.api.Metric.ValueType.MIN;
import static com.swirlds.metrics.api.Metric.ValueType.STD_DEV;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.common.metrics.StatEntry;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformStatEntryTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";
    private static final double EPSILON = 1e-6;
    private static final MetricsConfig metricsConfig =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(MetricsConfig.class);

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        // given
        final StatsBuffered buffered = mock(StatsBuffered.class);
        final Function<Double, StatsBuffered> init = mock(Function.class);
        final Consumer<Double> reset = mock(Consumer.class);
        final Supplier<Object> getter = mock(Supplier.class);
        final Supplier<Object> getAndReset = mock(Supplier.class);

        // when
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withBuffered(buffered)
                .withInit(init)
                .withReset(reset)
                .withResetStatsStringSupplier(getAndReset);
        final PlatformStatEntry statEntry = new PlatformStatEntry(config);

        // then
        assertEquals(CATEGORY, statEntry.getCategory(), "The category was not set correctly in the constructor");
        assertEquals(NAME, statEntry.getName(), "The name was not set correctly in the constructor");
        assertEquals(
                DESCRIPTION, statEntry.getDescription(), "The description was not set correctly in the constructor");
        assertEquals(UNIT, statEntry.getUnit(), "The unit was not set correctly in the constructor");
        assertEquals(FORMAT, statEntry.getFormat(), "The format was not set correctly in the constructor");
        assertEquals(STRING, statEntry.getDataType());
        assertEquals(buffered, statEntry.getBuffered(), "StatsBuffered was not set correctly in the constructor");
        assertEquals(reset, statEntry.getReset(), "The reset-lambda was not set correctly in the constructor");
        assertEquals(
                getter, statEntry.getStatsStringSupplier(), "The get-lambda was not set correctly in the constructor");
        assertEquals(
                getAndReset,
                statEntry.getResetStatsStringSupplier(),
                "The get-and-reset-lambda was not set correctly in the constructor");
        assertEquals(buffered, statEntry.getBuffered(), "StatsBuffered was not set correctly in the constructor");
        assertThat(statEntry.getValueTypes()).containsExactly(VALUE, MAX, MIN, STD_DEV);
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    void testReset() {
        // given
        final StatsBuffered buffered = mock(StatsBuffered.class);
        final Consumer<Double> reset = mock(Consumer.class);
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter)
                .withBuffered(buffered)
                .withReset(reset)
                .withHalfLife(metricsConfig.halfLife());
        final StatEntry statEntry = new PlatformStatEntry(config);

        // when
        statEntry.reset();

        // then
        verify(reset).accept(metricsConfig.halfLife());
        verify(buffered, never()).reset(anyDouble());
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    void testResetWithoutResetLambda() {
        // given
        final StatsBuffered buffered = mock(StatsBuffered.class);
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter)
                .withBuffered(buffered)
                .withHalfLife(metricsConfig.halfLife());
        final StatEntry statEntry = new PlatformStatEntry(config);

        // when
        statEntry.reset();

        // then
        verify(buffered).reset(metricsConfig.halfLife());
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    void testResetWithoutResetLambdaAndBuffer() {
        // given
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter);
        final StatEntry statEntry = new PlatformStatEntry(config);

        // when
        assertDoesNotThrow(statEntry::reset, "Calling reset() should have been a no-op, but was not.");
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    void testSnapshotWithBuffered() {
        // given
        final StatsBuffered buffered = mock(StatsBuffered.class);
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config =
                new StatEntry.Config(CATEGORY, NAME, Object.class, getter).withBuffered(buffered);
        final PlatformStatEntry statEntry = new PlatformStatEntry(config);

        // when
        when(getter.get()).thenReturn(3 * Math.PI);
        when(buffered.getMin()).thenReturn(2 * Math.PI);
        when(buffered.getMean()).thenReturn(3 * Math.PI);
        when(buffered.getMax()).thenReturn(5 * Math.PI);
        when(buffered.getStdDev()).thenReturn(Math.PI);
        final List<SnapshotEntry> snapshot = statEntry.takeSnapshot();

        // then
        assertEquals(2 * Math.PI, statEntry.get(MIN));
        assertEquals(3 * Math.PI, statEntry.get(VALUE));
        assertEquals(5 * Math.PI, statEntry.get(MAX));
        assertEquals(Math.PI, statEntry.get(STD_DEV));
        assertEquals(VALUE, snapshot.get(0).valueType());
        assertEquals(3 * Math.PI, (double) snapshot.get(0).value(), EPSILON, "Mean value should be " + (3 * Math.PI));
        assertEquals(MAX, snapshot.get(1).valueType());
        assertEquals(5 * Math.PI, (double) snapshot.get(1).value(), EPSILON, "Max. value should be " + (5 * Math.PI));
        assertEquals(MIN, snapshot.get(2).valueType());
        assertEquals(2 * Math.PI, (double) snapshot.get(2).value(), EPSILON, "Min. value should be" + (2 * Math.PI));
        assertEquals(STD_DEV, snapshot.get(3).valueType());
        assertEquals(Math.PI, (double) snapshot.get(3).value(), EPSILON, "Standard deviation should be " + Math.PI);
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    void testSnapshotWithoutBuffered() {
        // given
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter);
        final PlatformStatEntry statEntry = new PlatformStatEntry(config);

        // when
        when(getter.get()).thenReturn("Hello World");
        final List<SnapshotEntry> snapshot = statEntry.takeSnapshot();

        // then
        assertEquals("Hello World", statEntry.get(VALUE), "Value should be \"Hello World\"");
        assertThat(snapshot).containsExactly(new SnapshotEntry(VALUE, "Hello World"));
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    void testInvalidGetsWithBuffered() {
        // given
        final StatsBuffered buffered = mock(StatsBuffered.class);
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config =
                new StatEntry.Config(CATEGORY, NAME, Object.class, getter).withBuffered(buffered);
        final StatEntry statEntry = new PlatformStatEntry(config);

        // then
        assertThrows(
                NullPointerException.class, () -> statEntry.get(null), "Calling get() with null should throw an IAE");
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    void testInvalidGetsWithoutBuffered() {
        // given
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter);
        final StatEntry statEntry = new PlatformStatEntry(config);

        // then
        assertThrows(
                NullPointerException.class, () -> statEntry.get(null), "Calling get() with null should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> statEntry.get(Metric.ValueType.MIN),
                "Calling get() with an unsupported MetricType should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> statEntry.get(Metric.ValueType.MAX),
                "Calling get() with an unsupported MetricType should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> statEntry.get(Metric.ValueType.STD_DEV),
                "Calling get() with an unsupported MetricType should throw an IAE");
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    void testEquals() {
        // given
        final Supplier<Object> getter1 = mock(Supplier.class);
        final StatEntry.Config config1 = new StatEntry.Config(CATEGORY, NAME, Object.class, getter1);
        final StatEntry statEntry1 = new PlatformStatEntry(config1);
        final Supplier<Object> getter2 = mock(Supplier.class);
        final StatEntry.Config config2 = new StatEntry.Config(CATEGORY, NAME, Object.class, getter2);
        final StatEntry statEntry2 = new PlatformStatEntry(config2);

        // then
        assertThat(statEntry1)
                .isEqualTo(statEntry2)
                .hasSameHashCodeAs(statEntry2)
                .isNotEqualTo(new PlatformStatEntry(new StatEntry.Config("Other", NAME, Object.class, getter1)))
                .isNotEqualTo(new PlatformStatEntry(new StatEntry.Config(CATEGORY, "Other", Object.class, getter1)))
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config(CATEGORY, NAME)));
    }

    @SuppressWarnings({"unchecked", "removal"})
    @Test
    void testToString() {
        // given
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT);
        final StatEntry statEntry = new PlatformStatEntry(config);

        // then
        assertThat(statEntry.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, STRING.toString());
    }
}
