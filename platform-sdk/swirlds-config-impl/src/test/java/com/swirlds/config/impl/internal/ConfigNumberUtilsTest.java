// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigNumberUtilsTest {

    @Test
    public void compareInt() {
        // given
        final Integer value = Integer.valueOf(7);
        final Number number = Double.valueOf(3.5d);

        // when
        final int result = ConfigNumberUtils.compare(value, Integer.TYPE, number);

        Assertions.assertTrue(result > 0);
    }

    @Test
    public void compareLong() {
        // given
        final Long value = Long.valueOf(7L);
        final Number number = Double.valueOf(3.5d);

        // when
        final int result = ConfigNumberUtils.compare(value, Long.TYPE, number);

        Assertions.assertTrue(result > 0);
    }

    @Test
    public void compareFloat() {
        // given
        final Float value = Float.valueOf(7.0f);
        final Number number = Double.valueOf(3.5d);

        // when
        final int result = ConfigNumberUtils.compare(value, Float.TYPE, number);

        Assertions.assertTrue(result > 0);
    }

    @Test
    public void compareDouble() {
        // given
        final Double value = Double.valueOf(7.0d);
        final Number number = Double.valueOf(3.5d);

        // when
        final int result = ConfigNumberUtils.compare(value, Double.TYPE, number);

        Assertions.assertTrue(result > 0);
    }

    @Test
    public void compareByte() {
        // given
        final Byte value = Byte.valueOf((byte) 7);
        final Number number = Double.valueOf(3.5d);

        // when
        final int result = ConfigNumberUtils.compare(value, Byte.TYPE, number);

        Assertions.assertTrue(result > 0);
    }

    @Test
    public void compareShort() {
        // given
        final Short value = Short.valueOf((short) 7);
        final Number number = Double.valueOf(3.5d);

        // when
        final int result = ConfigNumberUtils.compare(value, Short.TYPE, number);

        Assertions.assertTrue(result > 0);
    }

    @ParameterizedTest
    @MethodSource("provideBoundComparisons")
    public void compareWithBound(final Object value, final long bound, final int expectedSign) {
        // when
        final int result = ConfigNumberUtils.compareWithBound(value, bound);

        // then
        Assertions.assertEquals(expectedSign, Integer.signum(result), value + " compared with " + bound);
    }

    private static Stream<Arguments> provideBoundComparisons() {
        return Stream.of(
                Arguments.of((byte) 7, 3L, 1),
                Arguments.of((short) 3, 3L, 0),
                Arguments.of(3, 7L, -1),
                Arguments.of(Long.MIN_VALUE, Long.MIN_VALUE, 0),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE, 0),
                // a fraction is not truncated
                Arguments.of(100.9d, 100L, 1),
                Arguments.of(-1.5d, -1L, -1),
                Arguments.of(0.5f, 0L, 1),
                Arguments.of(new BigDecimal("100.5"), 100L, 1),
                Arguments.of(new BigDecimal("100.000"), 100L, 0),
                // -0.0 equals 0
                Arguments.of(-0.0d, 0L, 0),
                // 2^63 as a double is greater than Long.MAX_VALUE, although (double) Long.MAX_VALUE is 2^63 as well
                Arguments.of(9.223372036854775807E18d, Long.MAX_VALUE, 1),
                Arguments.of(-9.223372036854775808E18d, Long.MIN_VALUE, 0),
                // a value outside the range of long does not wrap around
                Arguments.of(new BigInteger("18446744073709551621"), 100L, 1),
                Arguments.of(new BigInteger("-18446744073709551616"), 0L, -1),
                Arguments.of(Double.POSITIVE_INFINITY, Long.MAX_VALUE, 1),
                Arguments.of(Float.NEGATIVE_INFINITY, Long.MIN_VALUE, -1));
    }

    @Test
    public void compareWithBoundRejectsNaN() {
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> ConfigNumberUtils.compareWithBound(Double.NaN, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ConfigNumberUtils.compareWithBound(Float.NaN, 0));
    }

    @Test
    public void compareWithBoundRejectsNonNumber() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ConfigNumberUtils.compareWithBound("1", 0));
    }

    @Test
    public void isNaN() {
        Assertions.assertTrue(ConfigNumberUtils.isNaN(Double.NaN));
        Assertions.assertTrue(ConfigNumberUtils.isNaN(Float.NaN));
        Assertions.assertFalse(ConfigNumberUtils.isNaN(Double.POSITIVE_INFINITY));
        Assertions.assertFalse(ConfigNumberUtils.isNaN(1.5d));
        Assertions.assertFalse(ConfigNumberUtils.isNaN(1));
    }
}
