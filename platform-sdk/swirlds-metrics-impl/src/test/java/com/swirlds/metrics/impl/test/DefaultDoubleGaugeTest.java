// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl.test;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.DefaultDoubleGauge;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultDoubleGaugeTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    private static final double EPSILON = 1e-6;

    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitialValue(Math.PI);
        final DoubleGauge gauge = new DefaultDoubleGauge(config);

        assertEquals(CATEGORY, gauge.getCategory(), "The category was not set correctly in the constructor");
        assertEquals(NAME, gauge.getName(), "The name was not set correctly in the constructor");
        assertEquals(DESCRIPTION, gauge.getDescription(), "The description was not set correctly in the constructor");
        assertEquals(UNIT, gauge.getUnit(), "The unit was not set correctly in the constructor");
        assertEquals(FORMAT, gauge.getFormat(), "The format was not set correctly in the constructor");
        assertEquals(Math.PI, gauge.get(), EPSILON, "The value was not initialized correctly");
        assertEquals(Math.PI, gauge.get(VALUE), EPSILON, "The value was not initialized correctly");
        assertThat(gauge.getValueTypes()).containsExactly(VALUE);
    }

    @Test
    void testGetAndSet() {
        // given
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME).withInitialValue(Math.PI);
        final DoubleGauge gauge = new DefaultDoubleGauge(config);

        // when
        gauge.set(Math.E);

        // then
        assertEquals(Math.E, gauge.get(), EPSILON, "Value should be " + Math.E);
        assertEquals(Math.E, gauge.get(VALUE), EPSILON, "Value should be " + Math.E);

        // when
        gauge.set(Math.sqrt(2.0));

        // then
        assertEquals(Math.sqrt(2.0), gauge.get(), EPSILON, "Value should be " + Math.sqrt(2.0));
        assertEquals(Math.sqrt(2.0), gauge.get(VALUE), EPSILON, "Value should be " + Math.sqrt(2.0));
    }

    @Test
    void testGetAndAdd() {
        // given
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME).withInitialValue(Math.PI);
        final DoubleGauge gauge = new DefaultDoubleGauge(config);

        // when
        gauge.set(5);

        // then
        assertEquals(5, gauge.get(), "Value should be 5");
        assertEquals(5, gauge.get(VALUE), "Value should be 5");

        // when
        gauge.add(3.5);

        // then
        assertEquals(8.5, gauge.get(), "Value should be 8.5");
        assertEquals(8.5, gauge.get(VALUE), "Value should be 8.5");

        // when
        gauge.add(-12);

        // then
        assertEquals(-3.5, gauge.get(), "Value should be -3.5");
        assertEquals(-3.5, gauge.get(VALUE), "Value should be -3.5");
    }

    @Test
    void testSpecialValues() {
        // given
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME);
        final DoubleGauge gauge1 = new DefaultDoubleGauge(config);
        final DoubleGauge gauge2 = new DefaultDoubleGauge(config);
        final DoubleGauge gauge3 = new DefaultDoubleGauge(config);

        // when
        gauge1.set(Double.NaN);
        gauge2.set(Double.POSITIVE_INFINITY);
        gauge3.set(Double.NEGATIVE_INFINITY);

        // then
        assertThat(gauge1.get()).isNaN();
        assertThat(gauge2.get()).isPositive().isInfinite();
        assertThat(gauge3.get()).isNegative().isInfinite();
    }

    @Test
    void testSnapshot() {
        // given
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME).withInitialValue(Math.PI);
        final DefaultDoubleGauge gauge = new DefaultDoubleGauge(config);

        // when
        final List<SnapshotEntry> snapshot = gauge.takeSnapshot();

        // then
        assertEquals(Math.PI, gauge.get(), EPSILON, "Value should be " + Math.PI);
        assertEquals(Math.PI, gauge.get(VALUE), EPSILON, "Value should be " + Math.PI);
        assertEquals(VALUE, snapshot.get(0).valueType(), "Value-type of snapshot should be VALUE");
        assertEquals(Math.PI, (double) snapshot.get(0).value(), EPSILON, "Snapshot value is not correct");
    }

    @Test
    void testInvalidGets() {
        // given
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME);
        final DoubleGauge gauge = new DefaultDoubleGauge(config);

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
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME);
        final DoubleGauge gauge = new DefaultDoubleGauge(config);

        // then
        assertThatCode(gauge::reset).doesNotThrowAnyException();
    }

    @Test
    void testEquals() {
        // given
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME);
        final DoubleGauge gauge1 = new DefaultDoubleGauge(config);
        final DoubleGauge gauge2 = new DefaultDoubleGauge(config);
        gauge2.set(Math.PI);

        // then
        assertThat(gauge1)
                .isEqualTo(gauge2)
                .hasSameHashCodeAs(gauge2)
                .isNotEqualTo(new DefaultDoubleGauge(new DoubleGauge.Config("Other", NAME)))
                .isNotEqualTo(new DefaultDoubleGauge(new DoubleGauge.Config(CATEGORY, "Other")))
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config(CATEGORY, NAME)));
    }

    @Test
    void testToString() {
        // given
        final DoubleGauge.Config config = new DoubleGauge.Config(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withInitialValue(Math.PI);
        final DoubleGauge gauge = new DefaultDoubleGauge(config);

        // then
        assertThat(gauge.toString())
                .contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, Metric.DataType.FLOAT.toString(), "3.1415");
    }
}
