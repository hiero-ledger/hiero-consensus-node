// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.units;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertApproximatelyEquals;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This is either the best test name ever, or the worst.
 */
@DisplayName("Unit Tests")
class UnitTests {

    @Test
    @DisplayName("Time Conversion Test")
    void timeConversionTest() {
        final Random random = getRandomPrintSeed();

        // Test all conversion pairs.
        for (final TimeUnit a : TimeUnit.values()) {
            for (final TimeUnit b : TimeUnit.values()) {

                final long quantity = random.nextInt(1, 1000) * (random.nextBoolean() ? 1 : -1);
                final double newQuantity = a.convertTo(quantity, b);
                if (a == b) {
                    assertEquals(quantity, newQuantity);
                } else {
                    assertNotEquals(quantity, newQuantity);
                }
                // There will be a slight difference due to imprecise floating point math, but it should be close.
                final double reDerivedQuantity = b.convertTo(newQuantity, a);
                assertApproximatelyEquals(quantity, reDerivedQuantity);
            }
        }

        // Check a few specific conversion pairs.
        assertEquals(1_000, TimeUnit.UNIT_MICROSECONDS.convertTo(1, TimeUnit.UNIT_NANOSECONDS));
        assertEquals(1.0 / 1_000, TimeUnit.UNIT_NANOSECONDS.convertTo(1, TimeUnit.UNIT_MICROSECONDS));
        assertEquals(1_000, TimeUnit.UNIT_MILLISECONDS.convertTo(1, TimeUnit.UNIT_MICROSECONDS));
        assertEquals(1.0 / 1_000, TimeUnit.UNIT_MICROSECONDS.convertTo(1, TimeUnit.UNIT_MILLISECONDS));
        assertEquals(1_000, TimeUnit.UNIT_SECONDS.convertTo(1, TimeUnit.UNIT_MILLISECONDS));
        assertEquals(1.0 / 1_000, TimeUnit.UNIT_MILLISECONDS.convertTo(1, TimeUnit.UNIT_SECONDS));

        assertEquals(1_000_000_000, TimeUnit.UNIT_SECONDS.convertTo(1, TimeUnit.UNIT_NANOSECONDS));
        assertEquals(1.0 / 1_000_000_000, TimeUnit.UNIT_NANOSECONDS.convertTo(1, TimeUnit.UNIT_SECONDS));

        assertEquals(60, TimeUnit.UNIT_MINUTES.convertTo(1, TimeUnit.UNIT_SECONDS));
        assertEquals(60, TimeUnit.UNIT_HOURS.convertTo(1, TimeUnit.UNIT_MINUTES));
        assertEquals(24, TimeUnit.UNIT_DAYS.convertTo(1, TimeUnit.UNIT_HOURS));

        assertEquals(7 * 24 * 60 * 60 * 1000, TimeUnit.UNIT_DAYS.convertTo(7, TimeUnit.UNIT_MILLISECONDS));
        assertEquals(14287, (int) TimeUnit.UNIT_NANOSECONDS.convertTo(1234412312341243123L, TimeUnit.UNIT_DAYS));
        assertEquals(1234.567, TimeUnit.UNIT_MILLISECONDS.convertTo(1.234567, TimeUnit.UNIT_MICROSECONDS));
        assertEquals(345600000000L, TimeUnit.UNIT_DAYS.convertTo(4, TimeUnit.UNIT_MICROSECONDS));
    }

    @Test
    @DisplayName("Time Simplification Test")
    void timeSimplificationTest() {

        final Random random = getRandomPrintSeed();

        // Value of 1 should never be simplified
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_NANOSECONDS), TimeUnit.UNIT_NANOSECONDS.simplify(1));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_MICROSECONDS), TimeUnit.UNIT_MICROSECONDS.simplify(1));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_MILLISECONDS), TimeUnit.UNIT_MILLISECONDS.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_SECONDS), TimeUnit.UNIT_SECONDS.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_MINUTES), TimeUnit.UNIT_MINUTES.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_HOURS), TimeUnit.UNIT_HOURS.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_DAYS), TimeUnit.UNIT_DAYS.simplify(1));

        // Small amount of unit at low end should not be simplified.
        double value = random.nextDouble(1.0);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, TimeUnit.UNIT_NANOSECONDS),
                TimeUnit.UNIT_NANOSECONDS.simplify(value));

        // Small values should not be simplified.
        value = random.nextDouble(1.0, 1000);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, TimeUnit.UNIT_MICROSECONDS),
                TimeUnit.UNIT_MICROSECONDS.simplify(value));
        value = random.nextDouble(1.0, 1000);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, TimeUnit.UNIT_MILLISECONDS),
                TimeUnit.UNIT_MILLISECONDS.simplify(value));
        value = random.nextDouble(1.0, 60);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, TimeUnit.UNIT_SECONDS), TimeUnit.UNIT_SECONDS.simplify(value));
        value = random.nextDouble(1.0, 60);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, TimeUnit.UNIT_MINUTES), TimeUnit.UNIT_MINUTES.simplify(value));
        value = random.nextDouble(1.0, 24);
        assertEquals(new Unit.SimplifiedQuantity<>(value, TimeUnit.UNIT_HOURS), TimeUnit.UNIT_HOURS.simplify(value));

        // Large unit at the high end should not be simplified.
        random.nextDouble(1.0, 1_000_000);
        assertEquals(new Unit.SimplifiedQuantity<>(value, TimeUnit.UNIT_DAYS), TimeUnit.UNIT_DAYS.simplify(value));

        // Value at simplification boundary should be simplified.
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_MICROSECONDS), TimeUnit.UNIT_NANOSECONDS.simplify(1000));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_MILLISECONDS),
                TimeUnit.UNIT_MICROSECONDS.simplify(1000));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_SECONDS), TimeUnit.UNIT_MILLISECONDS.simplify(1000));
        assertEquals(new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_MINUTES), TimeUnit.UNIT_SECONDS.simplify(60));
        assertEquals(new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_HOURS), TimeUnit.UNIT_MINUTES.simplify(60));
        assertEquals(new Unit.SimplifiedQuantity<>(1, TimeUnit.UNIT_DAYS), TimeUnit.UNIT_HOURS.simplify(24));

        // Test value that should be simplified into a smaller unit
        for (final TimeUnit unit : TimeUnit.values()) {
            value = random.nextDouble(0.1, 1.0);
            final Unit.SimplifiedQuantity<TimeUnit> simplification = unit.simplify(value);
            if (unit == TimeUnit.UNIT_NANOSECONDS) {
                // The smallest supported time unit is nanoseconds
                assertEquals(unit, simplification.unit());
                assertEquals(value, simplification.quantity());
            } else {
                // All large values should be simplified to use a smaller unit
                assertNotEquals(unit, simplification.unit());
                assertTrue(simplification.quantity() > value);
                assertApproximatelyEquals(value, simplification.unit().convertTo(simplification.quantity(), unit));
            }
        }

        // Test value that shouldn't be simplified
        for (final TimeUnit unit : TimeUnit.values()) {
            // No time units have a conversion ratio less than 24
            value = random.nextDouble(1.0, 24.0);
            final Unit.SimplifiedQuantity<TimeUnit> simplification = unit.simplify(value);
            assertEquals(unit, simplification.unit());
            assertEquals(value, simplification.quantity());
        }

        // Test value that should be simplified into a larger unit
        for (final TimeUnit unit : TimeUnit.values()) {
            // All time unit ratios exceed 1000
            value = random.nextDouble(1001.0, 2000.0);
            final Unit.SimplifiedQuantity<TimeUnit> simplification = unit.simplify(value);

            if (unit == TimeUnit.UNIT_DAYS) {
                // The largest supported time unit is days
                assertEquals(unit, simplification.unit());
                assertEquals(value, simplification.quantity());
            } else {
                assertNotEquals(unit, simplification.unit());
                assertTrue(simplification.quantity() < value);
                assertApproximatelyEquals(value, simplification.unit().convertTo(simplification.quantity(), unit));
            }
        }

        Unit.SimplifiedQuantity<TimeUnit> simplification = TimeUnit.UNIT_MILLISECONDS.simplify(7 * 24 * 60 * 60 * 1000);
        assertEquals(TimeUnit.UNIT_DAYS, simplification.unit());
        assertEquals(7, simplification.quantity());

        simplification = TimeUnit.UNIT_NANOSECONDS.simplify(1234412312343123L);
        assertEquals(TimeUnit.UNIT_DAYS, simplification.unit());
        assertApproximatelyEquals(14.287179, simplification.quantity());

        simplification = TimeUnit.UNIT_MILLISECONDS.simplify(6120000);
        assertEquals(TimeUnit.UNIT_HOURS, simplification.unit());
        assertEquals(1.7, simplification.quantity());

        simplification = TimeUnit.UNIT_DAYS.simplify(0.5);
        assertEquals(TimeUnit.UNIT_HOURS, simplification.unit());
        assertEquals(12, simplification.quantity());
    }

    @Test
    @DisplayName("Frequency Conversion Test")
    void frequencyConversionTest() {
        final Random random = getRandomPrintSeed();

        // Test all conversion pairs.
        for (final FrequencyUnit a : FrequencyUnit.values()) {
            for (final FrequencyUnit b : FrequencyUnit.values()) {

                final long quantity = random.nextInt(1, 1000) * (random.nextBoolean() ? 1 : -1);
                final double newQuantity = a.convertTo(quantity, b);
                if (a == b) {
                    assertEquals(quantity, newQuantity);
                } else {
                    assertNotEquals(quantity, newQuantity);
                }
                // There will be a slight difference due to imprecise floating point math, but it should be close.
                final double reDerivedQuantity = b.convertTo(newQuantity, a);
                assertApproximatelyEquals(quantity, reDerivedQuantity);
            }
        }

        // Check a few specific conversion pairs.
        assertEquals(1, FrequencyUnit.UNIT_HERTZ.convertTo(1, FrequencyUnit.UNIT_HERTZ));
        assertEquals(2000, FrequencyUnit.UNIT_KILOHERTZ.convertTo(2, FrequencyUnit.UNIT_HERTZ));
        assertEquals(3000000, FrequencyUnit.UNIT_MEGAHERTZ.convertTo(3, FrequencyUnit.UNIT_HERTZ));
        assertEquals(4000000000L, FrequencyUnit.UNIT_GIGAHERTZ.convertTo(4, FrequencyUnit.UNIT_HERTZ));
        assertEquals(5000000000000L, FrequencyUnit.UNIT_TERAHERTZ.convertTo(5, FrequencyUnit.UNIT_HERTZ));

        assertEquals(1.0 / 1000, FrequencyUnit.UNIT_HERTZ.convertTo(1, FrequencyUnit.UNIT_KILOHERTZ));
        assertEquals(2.0 / 1000000, FrequencyUnit.UNIT_HERTZ.convertTo(2, FrequencyUnit.UNIT_MEGAHERTZ));
        assertEquals(3.0 / 1000000000, FrequencyUnit.UNIT_HERTZ.convertTo(3, FrequencyUnit.UNIT_GIGAHERTZ));
        assertEquals(4.0 / 1000000000000L, FrequencyUnit.UNIT_HERTZ.convertTo(4, FrequencyUnit.UNIT_TERAHERTZ));

        assertEquals(1000, FrequencyUnit.UNIT_MEGAHERTZ.convertTo(1, FrequencyUnit.UNIT_KILOHERTZ));
        assertEquals(2000, FrequencyUnit.UNIT_GIGAHERTZ.convertTo(2, FrequencyUnit.UNIT_MEGAHERTZ));
        assertEquals(3000, FrequencyUnit.UNIT_TERAHERTZ.convertTo(3, FrequencyUnit.UNIT_GIGAHERTZ));

        assertEquals(1000000, FrequencyUnit.UNIT_GIGAHERTZ.convertTo(1, FrequencyUnit.UNIT_KILOHERTZ));
        assertEquals(2000000, FrequencyUnit.UNIT_TERAHERTZ.convertTo(2, FrequencyUnit.UNIT_MEGAHERTZ));
    }

    @Test
    @DisplayName("Time Simplification Test")
    void frequencySimplificationTest() {
        final Random random = getRandomPrintSeed();

        // Value of 1 should never be simplified
        assertEquals(new Unit.SimplifiedQuantity<>(1, FrequencyUnit.UNIT_HERTZ), FrequencyUnit.UNIT_HERTZ.simplify(1));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, FrequencyUnit.UNIT_KILOHERTZ),
                FrequencyUnit.UNIT_KILOHERTZ.simplify(1));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, FrequencyUnit.UNIT_MEGAHERTZ),
                FrequencyUnit.UNIT_MEGAHERTZ.simplify(1));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, FrequencyUnit.UNIT_GIGAHERTZ),
                FrequencyUnit.UNIT_GIGAHERTZ.simplify(1));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, FrequencyUnit.UNIT_TERAHERTZ),
                FrequencyUnit.UNIT_TERAHERTZ.simplify(1));

        // Small amount of unit at low end should not be simplified.
        double value = random.nextDouble(1.0);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, FrequencyUnit.UNIT_HERTZ),
                FrequencyUnit.UNIT_HERTZ.simplify(value));

        // Small values should not be simplified.
        value = random.nextDouble(1.0, 1000);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, FrequencyUnit.UNIT_KILOHERTZ),
                FrequencyUnit.UNIT_KILOHERTZ.simplify(value));
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, FrequencyUnit.UNIT_MEGAHERTZ),
                FrequencyUnit.UNIT_MEGAHERTZ.simplify(value));
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, FrequencyUnit.UNIT_GIGAHERTZ),
                FrequencyUnit.UNIT_GIGAHERTZ.simplify(value));
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, FrequencyUnit.UNIT_TERAHERTZ),
                FrequencyUnit.UNIT_TERAHERTZ.simplify(value));

        // Large unit at the high end should not be simplified.
        random.nextDouble(1.0, 1_000_000);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, FrequencyUnit.UNIT_TERAHERTZ),
                FrequencyUnit.UNIT_TERAHERTZ.simplify(value));

        // Value at simplification boundary should be simplified.
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, FrequencyUnit.UNIT_KILOHERTZ),
                FrequencyUnit.UNIT_HERTZ.simplify(1000));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, FrequencyUnit.UNIT_MEGAHERTZ),
                FrequencyUnit.UNIT_KILOHERTZ.simplify(1000));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, FrequencyUnit.UNIT_GIGAHERTZ),
                FrequencyUnit.UNIT_MEGAHERTZ.simplify(1000));
        assertEquals(
                new Unit.SimplifiedQuantity<>(1, FrequencyUnit.UNIT_TERAHERTZ),
                FrequencyUnit.UNIT_GIGAHERTZ.simplify(1000));

        // Test value that should be simplified into a smaller unit
        for (final FrequencyUnit unit : FrequencyUnit.values()) {
            value = random.nextDouble(0.1, 1.0);
            final Unit.SimplifiedQuantity<FrequencyUnit> simplification = unit.simplify(value);
            if (unit == FrequencyUnit.UNIT_HERTZ) {
                // The smallest supported unit
                assertEquals(unit, simplification.unit());
                assertEquals(value, simplification.quantity());
            } else {
                // All large values should be simplified to use a smaller unit
                assertNotEquals(unit, simplification.unit());
                assertTrue(simplification.quantity() > value);
                assertApproximatelyEquals(value, simplification.unit().convertTo(simplification.quantity(), unit));
            }
        }

        // Test value that shouldn't be simplified
        for (final FrequencyUnit unit : FrequencyUnit.values()) {
            value = random.nextDouble(1.0, 1000.0);
            final Unit.SimplifiedQuantity<FrequencyUnit> simplification = unit.simplify(value);
            assertEquals(unit, simplification.unit());
            assertEquals(value, simplification.quantity());
        }

        // Test value that should be simplified into a larger unit
        for (final FrequencyUnit unit : FrequencyUnit.values()) {
            value = random.nextDouble(1001.0, 2000.0);
            final Unit.SimplifiedQuantity<FrequencyUnit> simplification = unit.simplify(value);

            if (unit == FrequencyUnit.UNIT_TERAHERTZ) {
                // The largest supported unit
                assertEquals(unit, simplification.unit());
                assertEquals(value, simplification.quantity());
            } else {
                assertNotEquals(unit, simplification.unit());
                assertTrue(simplification.quantity() < value);
                assertApproximatelyEquals(value, simplification.unit().convertTo(simplification.quantity(), unit));
            }
        }

        Unit.SimplifiedQuantity<FrequencyUnit> simplification = FrequencyUnit.UNIT_KILOHERTZ.simplify(1234);
        assertEquals(FrequencyUnit.UNIT_MEGAHERTZ, simplification.unit());
        assertEquals(1.234, simplification.quantity());

        simplification = FrequencyUnit.UNIT_GIGAHERTZ.simplify(0.5);
        assertEquals(FrequencyUnit.UNIT_MEGAHERTZ, simplification.unit());
        assertEquals(500, simplification.quantity());
    }

    @Test
    @DisplayName("Data Conversion Test")
    void dataConversionTest() {
        final Random random = getRandomPrintSeed(0);

        // Test all conversion pairs.
        for (final DataUnit a : DataUnit.values()) {
            for (final DataUnit b : DataUnit.values()) {

                final long quantity = random.nextInt(1, 1000) * (random.nextBoolean() ? 1 : -1);
                final double newQuantity = a.convertTo(quantity, b);
                if (a == b) {
                    assertEquals(quantity, newQuantity);
                } else {
                    assertNotEquals(quantity, newQuantity);
                }
                // There will be a slight difference due to imprecise floating point math, but it should be close.
                final double reDerivedQuantity = b.convertTo(newQuantity, a);
                assertApproximatelyEquals(quantity, reDerivedQuantity);
            }
        }

        // Check a few specific conversion pairs.
        assertEquals(1, DataUnit.UNIT_BITS.convertTo(1, DataUnit.UNIT_BITS));
        assertEquals(1.0 / 8, DataUnit.UNIT_BITS.convertTo(1, DataUnit.UNIT_BYTES));
        assertEquals(64, DataUnit.UNIT_BYTES.convertTo(8, DataUnit.UNIT_BITS));
        assertEquals(1, DataUnit.UNIT_BYTES.convertTo(1, DataUnit.UNIT_BYTES));
        assertEquals(1024, DataUnit.UNIT_KILOBYTES.convertTo(1, DataUnit.UNIT_BYTES));
        assertEquals(1024 * 1024, DataUnit.UNIT_MEGABYTES.convertTo(1, DataUnit.UNIT_BYTES));
        assertEquals(1024 * 1024 * 1024, DataUnit.UNIT_GIGABYTES.convertTo(1, DataUnit.UNIT_BYTES));
        assertEquals(1024L * 1024 * 1024 * 1024, DataUnit.UNIT_TERABYTES.convertTo(1, DataUnit.UNIT_BYTES));
        assertEquals(1024L * 1024 * 1024 * 1024 * 1024, DataUnit.UNIT_PETABYTES.convertTo(1, DataUnit.UNIT_BYTES));
    }

    @Test
    @DisplayName("Data Simplification Test")
    void dataSimplificationTest() {
        final Random random = getRandomPrintSeed();

        // Value of 1 should never be simplified
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_BITS), DataUnit.UNIT_BITS.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_BYTES), DataUnit.UNIT_BYTES.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_KILOBYTES), DataUnit.UNIT_KILOBYTES.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_MEGABYTES), DataUnit.UNIT_MEGABYTES.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_GIGABYTES), DataUnit.UNIT_GIGABYTES.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_TERABYTES), DataUnit.UNIT_TERABYTES.simplify(1));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_PETABYTES), DataUnit.UNIT_PETABYTES.simplify(1));

        // Small amount of unit at low end should not be simplified. If, somehow, we get a fraction of a bit. ;)
        double value = random.nextDouble(1.0);
        assertEquals(new Unit.SimplifiedQuantity<>(value, DataUnit.UNIT_BITS), DataUnit.UNIT_BITS.simplify(value));

        // Small values should not be simplified.
        value = random.nextDouble(1.0, 8);
        assertEquals(new Unit.SimplifiedQuantity<>(value, DataUnit.UNIT_BITS), DataUnit.UNIT_BITS.simplify(value));
        value = random.nextDouble(1.0, 1024);
        assertEquals(new Unit.SimplifiedQuantity<>(value, DataUnit.UNIT_BYTES), DataUnit.UNIT_BYTES.simplify(value));
        value = random.nextDouble(1.0, 1024);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, DataUnit.UNIT_KILOBYTES), DataUnit.UNIT_KILOBYTES.simplify(value));
        value = random.nextDouble(1.0, 1024);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, DataUnit.UNIT_MEGABYTES), DataUnit.UNIT_MEGABYTES.simplify(value));
        value = random.nextDouble(1.0, 1024);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, DataUnit.UNIT_GIGABYTES), DataUnit.UNIT_GIGABYTES.simplify(value));
        value = random.nextDouble(1.0, 1024);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, DataUnit.UNIT_TERABYTES), DataUnit.UNIT_TERABYTES.simplify(value));
        value = random.nextDouble(1.0, 1024);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, DataUnit.UNIT_PETABYTES), DataUnit.UNIT_PETABYTES.simplify(value));

        // Large unit at the high end should not be simplified.
        random.nextDouble(1.0, 1_000_000);
        assertEquals(
                new Unit.SimplifiedQuantity<>(value, DataUnit.UNIT_PETABYTES), DataUnit.UNIT_PETABYTES.simplify(value));

        // Value at simplification boundary should be simplified.
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_BYTES), DataUnit.UNIT_BITS.simplify(8));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_KILOBYTES), DataUnit.UNIT_BYTES.simplify(1024));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_MEGABYTES), DataUnit.UNIT_KILOBYTES.simplify(1024));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_GIGABYTES), DataUnit.UNIT_MEGABYTES.simplify(1024));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_TERABYTES), DataUnit.UNIT_GIGABYTES.simplify(1024));
        assertEquals(new Unit.SimplifiedQuantity<>(1, DataUnit.UNIT_PETABYTES), DataUnit.UNIT_TERABYTES.simplify(1024));

        // Test value that should be simplified into a smaller unit
        for (final DataUnit unit : DataUnit.values()) {
            value = random.nextDouble(0.1, 1.0);
            final Unit.SimplifiedQuantity<DataUnit> simplification = unit.simplify(value);
            if (unit == DataUnit.UNIT_BITS) {
                // The smallest supported unit
                assertEquals(unit, simplification.unit());
                assertEquals(value, simplification.quantity());
            } else {
                // All large values should be simplified to use a smaller unit
                assertNotEquals(unit, simplification.unit());
                assertTrue(simplification.quantity() > value);
                assertApproximatelyEquals(value, simplification.unit().convertTo(simplification.quantity(), unit));
            }
        }

        // Test value that shouldn't be simplified
        for (final DataUnit unit : DataUnit.values()) {
            if (unit == DataUnit.UNIT_BITS) {
                value = random.nextDouble(1.0, 8.0);
            } else {
                value = random.nextDouble(1.0, 1024.0);
            }

            final Unit.SimplifiedQuantity<DataUnit> simplification = unit.simplify(value);
            assertEquals(unit, simplification.unit());
            assertEquals(value, simplification.quantity());
        }

        // Test value that should be simplified into a larger unit
        for (final DataUnit unit : DataUnit.values()) {
            value = random.nextDouble(1025.0, 2000.0);
            final Unit.SimplifiedQuantity<DataUnit> simplification = unit.simplify(value);

            if (unit == DataUnit.UNIT_PETABYTES) {
                // The largest supported unit
                assertEquals(unit, simplification.unit());
                assertEquals(value, simplification.quantity());
            } else {
                assertNotEquals(unit, simplification.unit());
                assertTrue(simplification.quantity() < value);
                assertApproximatelyEquals(value, simplification.unit().convertTo(simplification.quantity(), unit));
            }
        }

        Unit.SimplifiedQuantity<DataUnit> simplification = DataUnit.UNIT_BYTES.simplify(12341234);
        assertEquals(DataUnit.UNIT_MEGABYTES, simplification.unit());
        assertEquals(11.76951789855957, simplification.quantity());

        simplification = DataUnit.UNIT_GIGABYTES.simplify(0.5);
        assertEquals(DataUnit.UNIT_MEGABYTES, simplification.unit());
        assertEquals(512, simplification.quantity());
    }
}
