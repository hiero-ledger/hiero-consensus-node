// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl.test;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.DefaultCounter;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultIntegerGaugeTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitialValue(42);
        final IntegerGauge gauge = new DefaultIntegerGauge(config);

        assertEquals(CATEGORY, gauge.getCategory(), "The category was not set correctly in the constructor");
        assertEquals(NAME, gauge.getName(), "The name was not set correctly in the constructor");
        assertEquals(DESCRIPTION, gauge.getDescription(), "The description was not set correctly in the constructor");
        assertEquals(UNIT, gauge.getUnit(), "The unit was not set correctly in the constructor");
        assertEquals(FORMAT, gauge.getFormat(), "The format was not set correctly in the constructor");
        assertEquals(42, gauge.get(), "The value was not initialized correctly");
        assertEquals(42, gauge.get(VALUE), "The value was not initialized correctly");
        assertThat(gauge.getValueTypes()).containsExactly(VALUE);
    }

    @Test
    @DisplayName("Test of get() and set()-operation")
    void testGetAndSet() {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME).withInitialValue(2);
        final IntegerGauge gauge = new DefaultIntegerGauge(config);

        // when
        gauge.set(5);

        // then
        assertEquals(5, gauge.get(), "Value should be 5");
        assertEquals(5, gauge.get(VALUE), "Value should be 5");

        // when
        gauge.set(3);

        // then
        assertEquals(3, gauge.get(), "Value should be 3");
        assertEquals(3, gauge.get(VALUE), "Value should be 3");
    }

    @Test
    @DisplayName("Test of get() and add()-operation")
    void testGetAndAdd() {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME).withInitialValue(2);
        final IntegerGauge gauge = new DefaultIntegerGauge(config);

        // when
        gauge.set(5);

        // then
        assertEquals(5, gauge.get(), "Value should be 5");
        assertEquals(5, gauge.get(VALUE), "Value should be 5");

        // when
        gauge.add(3);

        // then
        assertEquals(8, gauge.get(), "Value should be 8");
        assertEquals(8, gauge.get(VALUE), "Value should be 8");

        // when
        gauge.add(-12);

        // then
        assertEquals(-4, gauge.get(), "Value should be -4");
        assertEquals(-4, gauge.get(VALUE), "Value should be -4");
    }

    @Test
    void testSnapshot() {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME).withInitialValue(2);
        final DefaultIntegerGauge gauge = new DefaultIntegerGauge(config);

        gauge.set(3);
        // when
        final List<SnapshotEntry> snapshot = gauge.takeSnapshot();

        gauge.set(4);

        // then
        assertEquals(4, gauge.get(), "Value should be 4");
        assertEquals(4, gauge.get(VALUE), "Value should be 4");
        assertThat(snapshot).containsExactly(new SnapshotEntry(VALUE, 3));
    }

    @Test
    void testInvalidGets() {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME);
        final IntegerGauge gauge = new DefaultIntegerGauge(config);

        // then
        assertThrows(NullPointerException.class, () -> gauge.get(null), "Calling get() with null should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> gauge.get(Metric.ValueType.MIN),
                "Calling get() with an unsupported MetricType should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> gauge.get(Metric.ValueType.MAX),
                "Calling get() with an unsupported MetricType should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> gauge.get(Metric.ValueType.STD_DEV),
                "Calling get() with an unsupported MetricType should throw an IAE");
    }

    @Test
    void testReset() {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME);
        final IntegerGauge gauge = new DefaultIntegerGauge(config);

        // then
        assertThatCode(gauge::reset).doesNotThrowAnyException();
    }

    @Test
    void testEquals() {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME);
        final IntegerGauge gauge1 = new DefaultIntegerGauge(config);
        final IntegerGauge gauge2 = new DefaultIntegerGauge(config);
        gauge2.set(42);

        // then
        assertThat(gauge1)
                .isEqualTo(gauge2)
                .hasSameHashCodeAs(gauge2)
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config("Other", NAME)))
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config(CATEGORY, "Other")))
                .isNotEqualTo(new DefaultCounter(new Counter.Config(CATEGORY, NAME)));
    }

    @Test
    void testToString() {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitialValue(42);
        final IntegerGauge gauge = new DefaultIntegerGauge(config);

        // then
        assertThat(gauge.toString())
                .contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, Metric.DataType.INT.toString(), "42");
    }
}
