// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import java.util.Arrays;

/**
 * A utility class that deals with logic related to Mathematics.
 */
public final class MathUtilities {
    /**
     * This array contains the powers of ten from 10^0 to 10^18 (long).
     */
    private static final long[] POSITIVE_POWERS_OF_TEN = {
        1L,
        10L,
        100L,
        1_000L,
        10_000L,
        100_000L,
        1_000_000L,
        10_000_000L,
        100_000_000L,
        1_000_000_000L,
        10_000_000_000L,
        100_000_000_000L,
        1_000_000_000_000L,
        10_000_000_000_000L,
        100_000_000_000_000L,
        1_000_000_000_000_000L,
        10_000_000_000_000_000L,
        100_000_000_000_000_000L,
        1_000_000_000_000_000_000L
    };

    /**
     * This method checks if the given number is a power of two.
     *
     * @param toCheck the number to check if it is a power of two
     * @return {@code true} if the given number is a power of two
     */
    public static boolean isPowerOfTwo(final int toCheck) {
        // mathematically powers of two are always positive numbers, so if the
        // input is negative or zero, it is not a power of two, and we do not
        // need to trigger the second check, hence we return false immediately
        // by short-circuiting the logical AND operation
        return (0 < toCheck) && ((toCheck & (toCheck - 1)) == 0);
    }

    /**
     * This method checks if the given number is even.
     *
     * @param toCheck the number to check if it is even
     * @return {@code true} if the given number is even
     */
    public static boolean isEven(final int toCheck) {
        return (toCheck & 1) == 0;
    }

    /**
     * This method checks if a given long is a positive power of 10.
     * E.G.
     * <pre>
     *     isPowerOf10(1) = true
     *     isPowerOf10(10) = true
     *     isPowerOf10(100) = true
     *     isPowerOf10(1000) = true
     *     isPowerOf10(11) = false
     *     isPowerOf10(50) = false
     *     isPowerOf10(99) = false
     *     isPowerOf10(101) = false
     * </pre>
     * @param toCheck the long to check
     * @return {@code true} if the given long is a power of 10 {@code false} otherwise
     */
    public static boolean isPositivePowerOf10(final long toCheck) {
        if (toCheck <= 0) {
            return false;
        } else {
            return Arrays.binarySearch(POSITIVE_POWERS_OF_TEN, toCheck) >= 0;
        }
    }

    private MathUtilities() {}
}
