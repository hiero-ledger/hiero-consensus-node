// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

/**
 * Contains utility methods for comparing values.
 */
public final class CompareTo {

    private CompareTo() {}

    /**
     * Compare two values. Syntactic sugar for {@link Comparable#compareTo(Object)}.
     *
     * @param a
     * 		a value
     * @param b
     * 		a value
     * @param <A>
     * 		the type of value a
     * @param <B>
     * 		the type of value b
     * @return a &lt; b
     */
    public static <A extends Comparable<B>, B> boolean isLessThan(final A a, final B b) {
        return a.compareTo(b) < 0;
    }

    /**
     * Compare two values. Syntactic sugar for {@link Comparable#compareTo(Object)}.
     *
     * @param a
     * 		a value
     * @param b
     * 		a value
     * @param <A>
     * 		the type of value a
     * @param <B>
     * 		the type of value b
     * @return a &lt;= b
     */
    public static <A extends Comparable<B>, B> boolean isLessThanOrEqualTo(final A a, final B b) {
        return a.compareTo(b) <= 0;
    }

    /**
     * Compare two values. Syntactic sugar for {@link Comparable#compareTo(Object)}.
     *
     * @param a
     * 		a value
     * @param b
     * 		a value
     * @param <A>
     * 		the type of value a
     * @param <B>
     * 		the type of value b
     * @return a &gt; b
     */
    public static <A extends Comparable<B>, B> boolean isGreaterThan(final A a, final B b) {
        return a.compareTo(b) > 0;
    }

    /**
     * Compare two values. Syntactic sugar for {@link Comparable#compareTo(Object)}.
     *
     * @param a
     * 		a value
     * @param b
     * 		a value
     * @param <A>
     * 		the type of value a
     * @param <B>
     * 		the type of value b
     * @return a &gt;= b
     */
    public static <A extends Comparable<B>, B> boolean isGreaterThanOrEqualTo(final A a, final B b) {
        return a.compareTo(b) >= 0;
    }

    /**
     * Return the maximum of two values. If the values are equal, returns the first value.
     *
     * @param a
     * 		a value
     * @param b
     * 		a value
     * @param <T>
     * 		the type of the values
     * @return the maximum value, or the first value if equal
     */
    public static <T extends Comparable<T>> T max(final T a, final T b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * Return the minimum of two values. If the values are equal, returns the first value.
     *
     * @param a
     * 		a value
     * @param b
     * 		a value
     * @param <T>
     * 		the type of the values
     * @return the minimum value, or the first value if equal
     */
    public static <T extends Comparable<T>> T min(final T a, final T b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
