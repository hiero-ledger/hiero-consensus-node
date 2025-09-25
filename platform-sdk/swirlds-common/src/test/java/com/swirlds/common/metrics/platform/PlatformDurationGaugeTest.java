// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.metrics.DurationGauge;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Snapshot;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformDurationGaugeTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final double EPSILON = 1e-6;
    private static final ChronoUnit SECONDS = ChronoUnit.SECONDS;

    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        DurationGauge gauge = new PlatformDurationGauge(
                new DurationGauge.Config(CATEGORY, NAME, SECONDS).withDescription(DESCRIPTION));

        assertEquals(CATEGORY, gauge.getCategory(), "The category was not set correctly in the constructor");
        assertEquals(NAME, gauge.getName(), "The name was not set correctly in the constructor");
        assertEquals(DESCRIPTION, gauge.getDescription(), "The description was not set correctly in the constructor");
        assertEquals(
                FloatFormats.FORMAT_DECIMAL_3,
                gauge.getFormat(),
                "The format was not set correctly in the constructor for seconds");
        assertEquals(0.0, gauge.getNanos(), EPSILON, "The value was not initialized correctly");
        assertEquals(0.0, gauge.get(VALUE), EPSILON, "The value was not initialized correctly");
        assertEquals(EnumSet.of(VALUE), gauge.getValueTypes(), "ValueTypes should be [VALUE]");

        gauge = new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, ChronoUnit.MILLIS));
        assertEquals(
                FloatFormats.FORMAT_DECIMAL_3,
                gauge.getFormat(),
                "The format was not set correctly in the constructor for milliseconds");
        assertEquals(NAME, gauge.getName(), "The name was not set correctly in the constructor");

        gauge = new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, ChronoUnit.MICROS));
        assertEquals(
                FloatFormats.FORMAT_DECIMAL_0,
                gauge.getFormat(),
                "The format was not set correctly in the constructor for microsecond");
        assertEquals(NAME, gauge.getName(), "The name was not set correctly in the constructor");

        gauge = new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, ChronoUnit.NANOS));
        assertEquals(
                FloatFormats.FORMAT_DECIMAL_0,
                gauge.getFormat(),
                "The format was not set correctly in the constructor for nanoseconds");
        assertEquals(NAME, gauge.getName(), "The name was not set correctly in the constructor");
    }

    @Test
    @DisplayName("Constructor should throw IAE when passing null")
    void testConstructorWithNullParameter() {
        assertThrows(
                NullPointerException.class,
                () -> new DurationGauge.Config(null, NAME, SECONDS),
                "Calling the constructor without a category should throw an IAE");
        assertThrows(
                NullPointerException.class,
                () -> new DurationGauge.Config(CATEGORY, null, SECONDS),
                "Calling the constructor without a name should throw an IAE");
        assertThrows(
                NullPointerException.class,
                () -> new DurationGauge.Config(CATEGORY, NAME, null),
                "Calling the constructor without a time unit should throw an IAE");
    }

    @Test
    void testUpdate() {
        // given
        final DurationGauge gauge = new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, SECONDS));

        testDurationUpdate(gauge, Duration.ofMillis(1500), SECONDS);
        testDurationUpdate(gauge, Duration.ofMillis(700), SECONDS);

        // test null
        gauge.set(null);
        assertValue(gauge, Duration.ofMillis(700), SECONDS);
    }

    private void testDurationUpdate(final DurationGauge gauge, final Duration value, final ChronoUnit unit) {
        // when
        gauge.set(value);

        // then
        assertValue(gauge, value, unit);
    }

    private double assertValue(final DurationGauge gauge, final Duration value, final ChronoUnit unit) {
        assertEquals(value.toNanos(), gauge.getNanos(), EPSILON, "Value should be " + value.toNanos());
        final double decimalValue =
                (double) value.toNanos() / unit.getDuration().toNanos();
        assertEquals(decimalValue, gauge.get(VALUE), EPSILON, "Value should be " + value);
        return decimalValue;
    }

    @Test
    void testSnapshot() {
        // given
        final PlatformDurationGauge gauge =
                new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, SECONDS));
        testDurationUpdate(gauge, Duration.ofMillis(3500), SECONDS);

        // when
        final List<Snapshot.SnapshotEntry> snapshot = gauge.takeSnapshot();

        // then
        final double expectedValue = assertValue(gauge, Duration.ofMillis(3500), SECONDS);
        assertEquals(List.of(new Snapshot.SnapshotEntry(VALUE, expectedValue)), snapshot, "Snapshot is not correct");
    }

    @Test
    void testInvalidGets() {
        // given
        final DurationGauge gauge = new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, SECONDS));

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
    void testUnit() {
        DurationGauge gauge = new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, SECONDS));
        testDurationUpdate(gauge, Duration.ofNanos(100), SECONDS);
        testDurationUpdate(gauge, Duration.ofMinutes(2), SECONDS);

        gauge = new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, ChronoUnit.MILLIS));
        testDurationUpdate(gauge, Duration.ofNanos(100), ChronoUnit.MILLIS);
        testDurationUpdate(gauge, Duration.ofMinutes(2), ChronoUnit.MILLIS);

        gauge = new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, ChronoUnit.MICROS));
        testDurationUpdate(gauge, Duration.ofNanos(100), ChronoUnit.MICROS);
        testDurationUpdate(gauge, Duration.ofMinutes(2), ChronoUnit.MICROS);

        gauge = new PlatformDurationGauge(new DurationGauge.Config(CATEGORY, NAME, ChronoUnit.NANOS));
        testDurationUpdate(gauge, Duration.ofNanos(100), ChronoUnit.NANOS);
        testDurationUpdate(gauge, Duration.ofMinutes(2), ChronoUnit.NANOS);
    }
}
