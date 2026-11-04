// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Class that contains some utility methods regarding the usage of {@link Number} values.
 */
public final class ConfigNumberUtils {

    private ConfigNumberUtils() {}

    /**
     * Compares the given value with the given number.
     *
     * @param value     the value
     * @param valueType the type of the value
     * @param number    the number
     * @param <T>       the type of the value
     * @return a negative integer, zero, or a positive integer as the value is less than, equal to, or greater than the
     * number.
     */
    public static <T extends Number> int compare(
            @NonNull final T value, @NonNull final Class<T> valueType, @NonNull final Number number) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(valueType, "valueType must not be null");
        Objects.requireNonNull(number, "number must not be null");

        if (Objects.equals(valueType, Integer.class) || Objects.equals(valueType, Integer.TYPE)) {
            return Integer.compare(value.intValue(), number.intValue());
        }
        if (Objects.equals(valueType, Long.class) || Objects.equals(valueType, Long.TYPE)) {
            return Long.compare(value.longValue(), number.longValue());
        }
        if (Objects.equals(valueType, Double.class) || Objects.equals(valueType, Double.TYPE)) {
            return Double.compare(value.doubleValue(), number.doubleValue());
        }
        if (Objects.equals(valueType, Float.class) || Objects.equals(valueType, Float.TYPE)) {
            return Float.compare(value.floatValue(), number.floatValue());
        }
        if (Objects.equals(valueType, Short.class) || Objects.equals(valueType, Short.TYPE)) {
            return Short.compare(value.shortValue(), number.shortValue());
        }
        if (Objects.equals(valueType, Byte.class) || Objects.equals(valueType, Byte.TYPE)) {
            return Byte.compare(value.byteValue(), number.byteValue());
        }
        return 0;
    }

    /**
     * Compares the given number with the given bound exactly, in the type of the number. Unlike a comparison of
     * {@link Number#longValue()}, a fraction is not truncated and a value outside the range of {@code long} does not
     * wrap around, so {@code 100.5} is greater than {@code 100}.
     *
     * @param value the number, for example the value of a config property
     * @param bound the bound, for example the value of a constraint annotation
     * @return a negative integer, zero, or a positive integer as the value is less than, equal to, or greater than the
     * bound
     * @throws IllegalArgumentException if the given value is not a number, or is NaN, which is not ordered (see
     *                                  {@link #isNaN(Object)})
     */
    public static int compareWithBound(@NonNull final Object value, final long bound) {
        Objects.requireNonNull(value, "value must not be null");
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return Long.compare(((Number) value).longValue(), bound);
        }
        if (value instanceof BigInteger bigInteger) {
            return bigInteger.compareTo(BigInteger.valueOf(bound));
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal.compareTo(BigDecimal.valueOf(bound));
        }
        if (value instanceof Number number) {
            final double doubleValue = number.doubleValue();
            if (Double.isNaN(doubleValue)) {
                throw new IllegalArgumentException("NaN can not be compared: " + value);
            }
            if (Double.isInfinite(doubleValue)) {
                return doubleValue > 0 ? 1 : -1;
            }
            // A finite double is converted to a BigDecimal without loss, while (double) bound would round a bound
            // beyond 2^53.
            return new BigDecimal(doubleValue).compareTo(BigDecimal.valueOf(bound));
        }
        throw new IllegalArgumentException("Not a valid number: " + value);
    }

    /**
     * Returns true if the given object is a floating point number that is NaN. NaN is neither less than, equal to, nor
     * greater than any bound, so it violates every bound.
     *
     * @param value the object
     * @return true if the given object is NaN
     */
    public static boolean isNaN(@NonNull final Object value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value instanceof Double doubleValue) {
            return doubleValue.isNaN();
        }
        if (value instanceof Float floatValue) {
            return floatValue.isNaN();
        }
        return false;
    }

    /**
     * Returns true if the given class is a class that represents a number.
     *
     * @param cls the class to check
     * @return true if the given class represents a number
     */
    public static boolean isNumber(@NonNull final Class<?> cls) {
        Objects.requireNonNull(cls, "cls must not be null");
        if (Number.class.isAssignableFrom(cls)) {
            return true;
        }
        if (cls == Integer.TYPE) {
            return true;
        }
        if (cls == Long.TYPE) {
            return true;
        }
        if (cls == Double.TYPE) {
            return true;
        }
        if (cls == Float.TYPE) {
            return true;
        }
        if (cls == Short.TYPE) {
            return true;
        }
        if (cls == Byte.TYPE) {
            return true;
        }
        return false;
    }
}
