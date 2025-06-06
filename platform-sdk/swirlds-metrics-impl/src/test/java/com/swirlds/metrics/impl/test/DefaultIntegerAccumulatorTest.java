// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl.test;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.DefaultIntegerAccumulator;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultIntegerAccumulatorTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        final IntegerAccumulator.Config config1 = new IntegerAccumulator.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitialValue(42);
        final IntegerAccumulator accumulator1 = new DefaultIntegerAccumulator(config1);

        assertEquals(CATEGORY, accumulator1.getCategory(), "The category was not set correctly in the constructor");
        assertEquals(NAME, accumulator1.getName(), "The name was not set correctly in the constructor");
        assertEquals(
                DESCRIPTION, accumulator1.getDescription(), "The description was not set correctly in the constructor");
        assertEquals(FORMAT, accumulator1.getFormat(), "The format was not set correctly in the constructor");
        assertEquals(42, accumulator1.getInitialValue(), "The initial value was not initialized correctly");
        assertEquals(42, accumulator1.get(), "The value was not initialized correctly");
        assertEquals(42, accumulator1.get(VALUE), "The value was not initialized correctly");
        assertThat(accumulator1.getValueTypes()).containsExactly(VALUE);

        final IntegerAccumulator.Config config2 = new IntegerAccumulator.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitializer(() -> 3)
                .withInitialValue(42);
        final IntegerAccumulator accumulator2 = new DefaultIntegerAccumulator(config2);

        assertEquals(CATEGORY, accumulator2.getCategory(), "The category was not set correctly in the constructor");
        assertEquals(NAME, accumulator2.getName(), "The name was not set correctly in the constructor");
        assertEquals(
                DESCRIPTION, accumulator2.getDescription(), "The description was not set correctly in the constructor");
        assertEquals(FORMAT, accumulator2.getFormat(), "The format was not set correctly in the constructor");
        assertEquals(3, accumulator2.getInitialValue(), "The initial value was not initialized correctly");
        assertEquals(3, accumulator2.get(), "The value was not initialized correctly");
        assertEquals(3, accumulator2.get(VALUE), "The value was not initialized correctly");
        assertThat(accumulator2.getValueTypes()).containsExactly(VALUE);
    }

    @Test
    @DisplayName("Test of get() and update()-operation")
    void testGetAndUpdate() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME)
                .withAccumulator((op1, op2) -> op1 - op2)
                .withInitialValue(2);
        final IntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);

        // when
        accumulator.update(5);

        // then
        assertEquals(-3, accumulator.get(), "Value should be -3");
        assertEquals(-3, accumulator.get(VALUE), "Value should be -3");

        // when
        accumulator.update(3);

        // then
        assertEquals(-6, accumulator.get(), "Value should be -6");
        assertEquals(-6, accumulator.get(VALUE), "Value should be -6");
    }

    @SuppressWarnings("NumericOverflow")
    @Test
    void testPositiveOverflow() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME)
                .withAccumulator(Integer::sum)
                .withInitialValue(Integer.MAX_VALUE);
        final IntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);

        // when
        accumulator.update(1);

        // then
        final int expected = Integer.MAX_VALUE + 1;
        assertEquals(expected, accumulator.get(), "Value should be the same as if a regular int overflows");
    }

    @SuppressWarnings("NumericOverflow")
    @Test
    void testNegativeOverflow() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME)
                .withAccumulator(Integer::sum)
                .withInitialValue(Integer.MIN_VALUE);
        final IntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);

        // when
        accumulator.update(-1);

        // then
        final int expected = Integer.MIN_VALUE - 1;
        assertEquals(expected, accumulator.get(), "Value should be the same as if a regular int overflows");
    }

    @Test
    void testSnapshotWithInitializer() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME).withInitializer(() -> 3);
        final DefaultIntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);
        accumulator.update(5);

        // when
        final List<SnapshotEntry> snapshot = accumulator.takeSnapshot();

        // then
        assertEquals(3, accumulator.get(), "Value should be 7");
        assertEquals(3, accumulator.get(VALUE), "Value should be 7");
        assertThat(snapshot).containsExactly(new SnapshotEntry(VALUE, 5));
    }

    @Test
    void testSnapshotWithInitialValue() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME).withInitialValue(2);
        final DefaultIntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);
        accumulator.update(5);

        // when
        final List<SnapshotEntry> snapshot = accumulator.takeSnapshot();

        // then
        assertEquals(2, accumulator.get(), "Value should be 2");
        assertEquals(2, accumulator.get(VALUE), "Value should be 2");
        assertThat(snapshot).containsExactly(new SnapshotEntry(VALUE, 5));
    }

    @Test
    void testSnapshotWithResetDisabled() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME)
                .withInitialValue(2)
                .withResetOnSnapshot(false)
                .withAccumulator(Integer::sum);
        final DefaultIntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);
        accumulator.update(5);

        // when
        final List<SnapshotEntry> snapshot = accumulator.takeSnapshot();

        accumulator.update(3);

        // then
        assertEquals(10, accumulator.get(), "Value should be 10");
        assertEquals(10, accumulator.get(VALUE), "Value should be 10");
        assertThat(snapshot).containsExactly(new SnapshotEntry(VALUE, 7));
    }

    @Test
    void testInvalidGets() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME);
        final IntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);

        // then
        assertThrows(
                NullPointerException.class, () -> accumulator.get(null), "Calling get() with null should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> accumulator.get(Metric.ValueType.MIN),
                "Calling get() with an unsupported MetricType should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> accumulator.get(Metric.ValueType.MAX),
                "Calling get() with an unsupported MetricType should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> accumulator.get(Metric.ValueType.STD_DEV),
                "Calling get() with an unsupported MetricType should throw an IAE");
    }

    @Test
    void testReset() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME);
        final IntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);

        // then
        assertThatCode(accumulator::reset).doesNotThrowAnyException();
    }

    @Test
    void testEquals() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME);
        final IntegerAccumulator accumulator1 = new DefaultIntegerAccumulator(config);
        final IntegerAccumulator accumulator2 = new DefaultIntegerAccumulator(config);
        accumulator2.update(42);

        // then
        assertThat(accumulator1)
                .isEqualTo(accumulator2)
                .hasSameHashCodeAs(accumulator2)
                .isNotEqualTo(new DefaultIntegerAccumulator(new IntegerAccumulator.Config("Other", NAME)))
                .isNotEqualTo(new DefaultIntegerAccumulator(new IntegerAccumulator.Config(CATEGORY, "Other")))
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config(CATEGORY, NAME)));
    }

    @Test
    void testToString() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitialValue(42);
        final IntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);

        // then
        assertThat(accumulator.toString())
                .contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, Metric.DataType.INT.toString(), "42");
    }

    @Test
    void testResetValue() {
        // given
        final IntegerAccumulator.Config config = new IntegerAccumulator.Config(CATEGORY, NAME).withInitialValue(42);
        final IntegerAccumulator accumulator = new DefaultIntegerAccumulator(config);
        accumulator.update(5);

        // when
        accumulator.reset();

        // then
        assertEquals(42, accumulator.get(), "Value should be 42");
        assertEquals(42, accumulator.get(VALUE), "Value should be 42");
    }
}
