// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.metrics.api.Metric.DataType.STRING;
import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PlatformFunctionGaugeTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    @SuppressWarnings("unchecked")
    @Test
    void testConstructor() {
        final Supplier<String> supplier = mock(Supplier.class);
        when(supplier.get()).thenReturn("Hello World");
        final FunctionGauge.Config<String> config = new FunctionGauge.Config<>(CATEGORY, NAME, String.class, supplier)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT);
        final FunctionGauge<String> gauge = new PlatformFunctionGauge<>(config);

        assertEquals(CATEGORY, gauge.getCategory(), "The category was not set correctly in the constructor");
        assertEquals(NAME, gauge.getName(), "The name was not set correctly in the constructor");
        assertEquals(DESCRIPTION, gauge.getDescription(), "The description was not set correctly in the constructor");
        assertEquals(UNIT, gauge.getUnit(), "The unit was not set correctly in the constructor");
        assertEquals(FORMAT, gauge.getFormat(), "The format was not set correctly in the constructor");
        assertEquals(STRING, gauge.getDataType());
        assertEquals("Hello World", gauge.get());
        assertEquals("Hello World", gauge.get(VALUE));
        assertThat(gauge.getValueTypes()).containsExactly(VALUE);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testGetAndSet() {
        // given
        final Supplier<String> supplier = mock(Supplier.class);
        final FunctionGauge.Config<String> config = new FunctionGauge.Config<>(CATEGORY, NAME, String.class, supplier);
        final FunctionGauge<String> gauge = new PlatformFunctionGauge<>(config);

        // when
        when(supplier.get()).thenReturn("Hello World");

        // then
        assertEquals("Hello World", gauge.get(), "Value should be 'Hello World'");
        assertEquals("Hello World", gauge.get(VALUE), "Value should be 'Hello World'");

        // when
        when(supplier.get()).thenReturn("Goodbye World");

        // then
        assertEquals("Goodbye World", gauge.get(), "Value should be 'Goodbye World'");
        assertEquals("Goodbye World", gauge.get(VALUE), "Value should be 'Goodbye World'");
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSnapshot() {
        // given
        final Supplier<String> supplier = mock(Supplier.class);
        final FunctionGauge.Config<String> config = new FunctionGauge.Config<>(CATEGORY, NAME, String.class, supplier);
        final PlatformFunctionGauge<String> gauge = new PlatformFunctionGauge<>(config);

        // when
        when(supplier.get()).thenReturn("Hello World");
        final List<SnapshotEntry> snapshot = gauge.takeSnapshot();

        // then
        assertEquals("Hello World", gauge.get(), "Value should be 'Hello World'");
        assertEquals("Hello World", gauge.get(VALUE), "Value should be 'Hello World'");
        assertThat(snapshot).containsExactly(new SnapshotEntry(VALUE, "Hello World"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testInvalidGets() {
        // given
        final Supplier<String> supplier = mock(Supplier.class);
        final FunctionGauge.Config<String> config = new FunctionGauge.Config<>(CATEGORY, NAME, String.class, supplier);
        final FunctionGauge<String> gauge = new PlatformFunctionGauge<>(config);

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

    @SuppressWarnings("unchecked")
    @Test
    void testReset() {
        // given
        final Supplier<String> supplier = mock(Supplier.class);
        final FunctionGauge.Config<String> config = new FunctionGauge.Config<>(CATEGORY, NAME, String.class, supplier);
        final FunctionGauge<String> gauge = new PlatformFunctionGauge<>(config);

        // then
        assertThatCode(gauge::reset).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    @Test
    void testEquals() {
        // given
        final Supplier<String> supplier1 = mock(Supplier.class);
        final FunctionGauge.Config<String> config1 =
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, supplier1);
        final FunctionGauge<String> gauge1 = new PlatformFunctionGauge<>(config1);
        final Supplier<String> supplier2 = mock(Supplier.class);
        final FunctionGauge.Config<String> config2 =
                new FunctionGauge.Config<>(CATEGORY, NAME, String.class, supplier2);
        final FunctionGauge<String> gauge2 = new PlatformFunctionGauge<>(config2);

        // then
        assertThat(gauge1)
                .isEqualTo(gauge2)
                .hasSameHashCodeAs(gauge2)
                .isNotEqualTo(
                        new PlatformFunctionGauge<>(new FunctionGauge.Config<>("Other", NAME, String.class, supplier1)))
                .isNotEqualTo(new PlatformFunctionGauge<>(
                        new FunctionGauge.Config<>(CATEGORY, "Other", String.class, supplier1)))
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config(CATEGORY, NAME)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void testToString() {
        // given
        final Supplier<String> supplier = mock(Supplier.class);
        final FunctionGauge.Config<String> config = new FunctionGauge.Config<>(CATEGORY, NAME, String.class, supplier)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT);
        final FunctionGauge<String> gauge = new PlatformFunctionGauge<>(config);

        // then
        assertThat(gauge.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, STRING.toString());
    }
}
