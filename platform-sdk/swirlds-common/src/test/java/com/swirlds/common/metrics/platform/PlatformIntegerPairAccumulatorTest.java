// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.common.metrics.IntegerPairAccumulator.AVERAGE;
import static com.swirlds.metrics.api.Metric.DataType.STRING;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformIntegerPairAccumulatorTest {

    private static final double EPSILON = 1e-6;

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        final BiFunction<Integer, Integer, String> resultFunction = mock(BiFunction.class);
        when(resultFunction.apply(anyInt(), anyInt())).thenReturn("Hello World");
        final IntegerPairAccumulator.Config<String> config = new IntegerPairAccumulator.Config<>(
                        CATEGORY, NAME, String.class, resultFunction)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT);
        final IntegerPairAccumulator<String> accumulator = new PlatformIntegerPairAccumulator<>(config);

        assertEquals(CATEGORY, accumulator.getCategory(), "The category was not set correctly in the constructor");
        assertEquals(NAME, accumulator.getName(), "The name was not set correctly in the constructor");
        assertEquals(
                DESCRIPTION, accumulator.getDescription(), "The description was not set correctly in the constructor");
        assertEquals(UNIT, accumulator.getUnit(), "The unit was not set correctly in the constructor");
        assertEquals(FORMAT, accumulator.getFormat(), "The format was not set correctly in the constructor");
        assertEquals(STRING, accumulator.getDataType());
        assertEquals("Hello World", accumulator.get(), "The value was not initialized correctly");
        assertEquals("Hello World", accumulator.get(VALUE), "The value was not initialized correctly");
        assertThat(accumulator.getValueTypes()).containsExactly(VALUE);
    }

    @Test
    @DisplayName("Test of get() and update()-operation")
    void testGetAndUpdate() {
        // given
        final IntegerPairAccumulator.Config<Long> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Long.class, Math::multiplyFull);
        final IntegerPairAccumulator<Long> accumulator = new PlatformIntegerPairAccumulator<>(config);

        // when
        accumulator.update(2, 3);

        // then
        assertEquals(2.0 * 3.0, accumulator.get(), EPSILON, "Value should be 5");
        assertEquals(2.0 * 3.0, accumulator.get(VALUE), EPSILON, "Value should be 5");

        // when
        accumulator.update(5, 7);

        // then
        assertEquals(7.0 * 10.0, accumulator.get(), EPSILON, "Value should be 5");
        assertEquals(7.0 * 10.0, accumulator.get(VALUE), EPSILON, "Value should be 5");
    }

    @Test
    void testPositiveOverflow() {
        // given
        final IntegerPairAccumulator.Config<Long> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Long.class, Math::multiplyFull);
        final IntegerPairAccumulator<Long> accumulator = new PlatformIntegerPairAccumulator<>(config);
        accumulator.update(Integer.MAX_VALUE, Integer.MAX_VALUE);

        // when
        accumulator.update(1, 1);

        // then
        final int op = Integer.MAX_VALUE + 1;
        final long expected = Math.multiplyFull(op, op);
        assertEquals(expected, accumulator.get(), "Value should be the same as if regular ints overflows");
    }

    @Test
    void testNegativeOverflow() {
        // given
        final IntegerPairAccumulator.Config<Long> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Long.class, Math::multiplyFull);
        final IntegerPairAccumulator<Long> accumulator = new PlatformIntegerPairAccumulator<>(config);
        accumulator.update(Integer.MIN_VALUE, Integer.MIN_VALUE);

        // when
        accumulator.update(-1, -1);

        // then
        final int op = Integer.MIN_VALUE - 1;
        final long expected = Math.multiplyFull(op, op);
        assertEquals(expected, accumulator.get(), "Value should be the same as if regular ints overflows");
    }

    @Test
    @DisplayName("Test of get() and update()-operation with custom functions")
    void testGetAndUpdateWithCustomFunctions() {
        // given
        final IntegerPairAccumulator.Config<Long> config = new IntegerPairAccumulator.Config<>(
                        CATEGORY, NAME, Long.class, Math::multiplyFull)
                .withLeftAccumulator(Integer::max)
                .withRightAccumulator(Integer::sum);
        final IntegerPairAccumulator<Long> accumulator = new PlatformIntegerPairAccumulator<>(config);

        // when
        accumulator.update(2, 3);

        // then
        assertEquals(2.0 * 3.0, accumulator.get(), EPSILON, "Value should be 5");
        assertEquals(2.0 * 3.0, accumulator.get(VALUE), EPSILON, "Value should be 5");

        // when
        accumulator.update(5, 7);

        // then
        assertEquals(5.0 * 10.0, accumulator.get(), EPSILON, "Value should be 5");
        assertEquals(5.0 * 10.0, accumulator.get(VALUE), EPSILON, "Value should be 5");
    }

    @Test
    void testSnapshot() {
        // given
        final IntegerPairAccumulator.Config<Double> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Double.class, AVERAGE);
        final PlatformIntegerPairAccumulator<Double> accumulator = new PlatformIntegerPairAccumulator<>(config);
        accumulator.update(2, 3);

        // when
        final List<SnapshotEntry> snapshot = accumulator.takeSnapshot();

        // then
        assertEquals(0.0, accumulator.get(), EPSILON, "Value should be 2");
        assertEquals(0.0, accumulator.get(VALUE), EPSILON, "Value should be 2");
        assertEquals(1, snapshot.size(), "Snapshot should contain only one value");
        assertEquals(VALUE, snapshot.get(0).valueType(), "Value-type of snapshot should be VALUE");
        assertEquals(2.0 / 3.0, (double) snapshot.get(0).value(), EPSILON, "Snapshot value is not correct");
    }

    @Test
    void testInvalidGets() {
        // given
        final IntegerPairAccumulator.Config<Double> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Double.class, AVERAGE);
        final IntegerPairAccumulator<Double> accumulator = new PlatformIntegerPairAccumulator<>(config);

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
        final IntegerPairAccumulator.Config<Double> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Double.class, AVERAGE);
        final IntegerPairAccumulator<Double> accumulator = new PlatformIntegerPairAccumulator<>(config);

        // then
        assertThatCode(accumulator::reset).doesNotThrowAnyException();
    }

    @Test
    void testEquals() {
        // given
        final IntegerPairAccumulator.Config<Double> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Double.class, AVERAGE);
        final IntegerPairAccumulator<Double> accumulator1 = new PlatformIntegerPairAccumulator<>(config);
        final IntegerPairAccumulator<Double> accumulator2 = new PlatformIntegerPairAccumulator<>(config);
        accumulator2.update(42, 4711);

        // then
        assertThat(accumulator1)
                .isEqualTo(accumulator2)
                .hasSameHashCodeAs(accumulator2)
                .isNotEqualTo(new PlatformIntegerPairAccumulator<>(
                        new IntegerPairAccumulator.Config<>("Other", NAME, Double.class, AVERAGE)))
                .isNotEqualTo(new PlatformIntegerPairAccumulator<>(
                        new IntegerPairAccumulator.Config<>(CATEGORY, "Other", Double.class, AVERAGE)))
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config(CATEGORY, NAME)));
    }

    @Test
    void testToString() {
        // given
        final IntegerPairAccumulator.Config<Double> config = new IntegerPairAccumulator.Config<>(
                        CATEGORY, NAME, Double.class, AVERAGE)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT);
        final IntegerPairAccumulator<Double> accumulator = new PlatformIntegerPairAccumulator<>(config);

        // then
        assertThat(accumulator.toString())
                .contains(CATEGORY, NAME, DESCRIPTION, FORMAT, Metric.DataType.FLOAT.toString());
    }

    @Test
    void testResetValue() {
        // given
        final IntegerPairAccumulator.Config<Double> config =
                new IntegerPairAccumulator.Config<>(CATEGORY, NAME, Double.class, AVERAGE);
        final IntegerPairAccumulator<Double> accumulator = new PlatformIntegerPairAccumulator<>(config);
        accumulator.update(42, 4711);

        // when
        accumulator.reset();

        // then
        assertEquals(0.0, accumulator.get(), EPSILON, "Value should be 0");
        assertEquals(0.0, accumulator.get(VALUE), EPSILON, "Value should be 0");
    }
}
