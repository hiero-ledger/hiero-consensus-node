// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.virtualmerkle.random;

import java.util.Random;

/**
 * The {@code PTTRandom} class provides a set of methods for generating random numbers.
 * It aims to offer additional utility methods not present in the standard {@link java.util.Random} class.
 * However, there are some differences and assumptions compared to {@code java.util.Random}.
 * <p>
 * Always refer to method-specific documentation for more detailed information on their functionalities and assumptions.
 */
public final class PTTRandom {

    private final Random random;

    /**
     * Create a new {@link PTTRandom} instance.
     */
    public PTTRandom() {
        this.random = new Random();
    }

    /**
     * Creates a new {@link PTTRandom} instance that will generate random values
     * taking into consideration the given {@code seed}.
     *
     * @param seed
     * 		The seed used to generate random values.
     */
    public PTTRandom(final long seed) {
        this.random = new Random(seed);
    }

    /**
     * Generate a random {@code double} value.
     *
     * @return a random {@code double} value from the open interval (0,1).
     */
    public double nextDouble() {
        return random.nextDouble();
    }

    /**
     * Generate a random {@code int} value less than {@code upper}.
     *
     * @param upper
     * 		The limit of the interval [0,upper)
     * @return A random {@code int} from the interval [0,upper)
     */
    public int nextInt(final int upper) {
        return random.nextInt(0, upper);
    }

    /**
     * Generate a random {@code long} value.
     *
     * @return A random {@code long} from the interval [0,Long.MAX_VALUE)
     */
    public long nextLong() {
        return random.nextLong(0, Long.MAX_VALUE);
    }

    /**
     * Generate a random {@code long} value from the interval [lower,upper).
     *
     * Note: When {@code lower == upper} is true, this method returns {@code lowexitr}:q
     * .
     *
     * @param lower
     * 		The lower boundary for the interval.
     * @param upper
     * 		The upper boundary for the interval.
     * @return A random {@code long} from the interval [lower,upper)
     */
    public long nextLong(final long lower, final long upper) {
        if (lower == upper) {
            return lower;
        }
        return random.nextLong(lower, upper);
    }

    /**
     * Generate a random {@code byte} value.
     *
     * @return A random {@code byte} value from the interval [0,Byte.MAX_VALUE).
     */
    public byte nextByte() {
        return (byte) random.nextInt(0, Byte.MAX_VALUE);
    }

    /**
     * Generate a random {@code int} value from the interval [lower,upper).
     *
     * @param lower
     * 		The lower boundary for the interval.
     * @param upper
     * 		The upper boundary for the interval.
     * @return A random {@code int} value from the interval [lower, upper)
     */
    public int nextInt(final int lower, final int upper) {
        if (lower == upper) {
            return lower;
        }
        return random.nextInt(lower, upper);
    }
}
