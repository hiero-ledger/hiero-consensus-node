// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Random;

/**
 * A collection of utilities for generating random data used for unit testing.
 */
public class RandomUtils {

    /**
     * Prevents instantiation.
     */
    private RandomUtils() {}

    private static final Random RANDOM = new SecureRandom();

    /**
     * Generates a random string of the given length.
     *
     * @param random
     * 		the random object to use
     * @param length
     * 		the length of the string to generate
     * @return a random string
     */
    public static @NonNull String randomString(@NonNull final Random random, final int length) {
        final int LEFT_LIMIT = 48; // numeral '0'
        final int RIGHT_LIMIT = 122; // letter 'z'

        return random.ints(LEFT_LIMIT, RIGHT_LIMIT + 1)
                .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                .limit(length)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /**
     * Generates a random IP address
     *
     * @param r
     * 		the random object to use
     * @return a random IP address
     */
    public static @NonNull String randomIp(@NonNull final Random r) {
        return r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256);
    }

    /**
     * Generates a random positive long that is smaller than the supplied value
     *
     * @param random
     * 		the random object to use
     * @param maxValue the upper bound, the returned value will be smaller than this
     * @return the random long
     */
    public static long randomPositiveLong(@NonNull final Random random, final long maxValue) {
        return random.longs(1, 1, maxValue).findFirst().orElseThrow();
    }

    /**
     * Generates a random positive long
     *
     * @param random
     * 		the random object to use
     * @return the random long
     */
    public static long randomPositiveLong(@NonNull final Random random) {
        return randomPositiveLong(random, Long.MAX_VALUE);
    }

    /**
     * Generates a random byte array
     * @param random the random object to use
     * @param size the size of the byte array
     * @return a random byte array
     */
    public static @NonNull byte[] randomByteArray(@NonNull final Random random, final int size) {
        final byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    /**
     * Generates a random instant
     * @param random the random object to use
     * @return a random instant
     */
    public static @NonNull Instant randomInstant(@NonNull final Random random) {
        return Instant.ofEpochMilli(randomPositiveLong(random, 2000000000000L));
    }

    /**
     * Generates a random boolean with a given probability of being true
     * @param random the random object to use
     * @param trueProbability the probability of the boolean being true
     * @return a random boolean
     */
    public static boolean randomBooleanWithProbability(@NonNull final Random random, final double trueProbability) {
        return random.nextDouble() < trueProbability;
    }

    /**
     * Creates a new instance of {@link ResettableRandom} with a seed that is printed to stdout.
     * @return a new random object
     */
    public static @NonNull ResettableRandom getRandomPrintSeed() {
        return getRandom(true);
    }

    /**
     * A variation of {@link #getRandomPrintSeed()} that takes a hard coded seed. Handy for
     * debugging a test, simply copy a seed into the {@link #getRandomPrintSeed()} method.
     *
     * @param seed
     * 		the seed to use
     * @return a new random object
     */
    public static @NonNull ResettableRandom getRandomPrintSeed(final long seed) {
        System.out.println("Random seed: " + seed + "L");
        return new ResettableRandom(seed);
    }

    /**
     * Creates a new instance of {@link ResettableRandom}
     * @return a new random object
     */
    public static @NonNull ResettableRandom getRandom() {
        return getRandom(false);
    }

    /**
     * Creates a new instance of {@link ResettableRandom}
     * @param printSeed if true, the seed will be printed to stdout
     * @return a new random object
     */
    public static @NonNull ResettableRandom getRandom(final boolean printSeed) {
        return createRandom(RANDOM.nextLong(), printSeed);
    }

    /**
     * Creates a new instance of {@link ResettableRandom} with the supplied seed
     * @param seed the seed to use
     * @param printSeed if true, the seed will be printed to stdout
     * @return a new random object
     */
    private static @NonNull ResettableRandom createRandom(final long seed, final boolean printSeed) {
        if (printSeed) {
            System.out.println("Random seed: " + seed + "L");
        }
        return new ResettableRandom(seed);
    }

    /**
     * Creates a new instance of {@link ResettableRandom} with the supplied seed, and prints the seed to stdout
     * @param seed the seed to use, if null a random seed will be used
     * @return a new random object
     */
    public static @NonNull ResettableRandom initRandom(@Nullable final Long seed) {
        return initRandom(seed, true);
    }

    /**
     * Creates a new instance of {@link ResettableRandom} with the supplied seed
     * @param seed the seed to use, if null a random seed will be used
     * @param printSeed if true, the seed will be printed to stdout
     * @return a new random object
     */
    public static @NonNull ResettableRandom initRandom(@Nullable final Long seed, final boolean printSeed) {
        if (seed == null) {
            return getRandomPrintSeed();
        } else {
            return createRandom(seed, printSeed);
        }
    }

    /**
     * Returns a random int within 0 - Integer.MAX_VALUE
     * <p>
     * Extracted from {@code org.apache.commons.lang3.RandomUtils#nextInt()}
     *
     * @return the random integer
     * @see #nextInt(int, int)
     */
    public static int nextInt() {
        return nextInt(0, Integer.MAX_VALUE);
    }

    /**
     * Returns a random integer within the specified range.
     * <p>
     * Extracted from {@code org.apache.commons.lang3.RandomUtils#nextInt(int, int)}
     *
     * @param startInclusive
     *            the smallest value that can be returned, must be non-negative
     * @param endExclusive
     *            the upper bound (not included)
     * @throws IllegalArgumentException
     *             if {@code startInclusive > endExclusive} or if
     *             {@code startInclusive} is negative
     * @return the random integer
     */
    public static int nextInt(final int startInclusive, final int endExclusive) {
        if (endExclusive < startInclusive) {
            throw new IllegalArgumentException("Start value must be smaller or equal to end value.");
        }
        if (startInclusive < 0) {
            throw new IllegalArgumentException("Both range values must be non-negative.");
        }

        if (startInclusive == endExclusive) {
            return startInclusive;
        }

        return startInclusive + RANDOM.nextInt(endExclusive - startInclusive);
    }

    /**
     * Returns a random long within the specified range.
     * <p>
     * Extracted from {@code org.apache.commons.lang3.RandomUtils#nextLong(long, long)}
     *
     * @param startInclusive
     *            the smallest value that can be returned, must be non-negative
     * @param endExclusive
     *            the upper bound (not included)
     * @throws IllegalArgumentException
     *             if {@code startInclusive > endExclusive} or if
     *             {@code startInclusive} is negative
     * @return the random long
     */
    public static long nextLong(final long startInclusive, final long endExclusive) {
        if (endExclusive < startInclusive) {
            throw new IllegalArgumentException("Start value must be smaller or equal to end value.");
        }
        if (startInclusive < 0) {
            throw new IllegalArgumentException("Both range values must be non-negative.");
        }

        if (startInclusive == endExclusive) {
            return startInclusive;
        }

        return startInclusive + nextLong(endExclusive - startInclusive);
    }

    /**
     * Generates a {@code long} value between 0 (inclusive) and the specified
     * value (exclusive).
     * <p>
     * Extracted from {@code org.apache.commons.lang3.RandomUtils#nextLong(long)}
     *
     * @param n Bound on the random number to be returned.  Must be positive.
     * @return a random {@code long} value between 0 (inclusive) and {@code n}
     * (exclusive).
     */
    private static long nextLong(final long n) {
        long bits;
        long val;
        do {
            bits = RANDOM.nextLong() >>> 1;
            val = bits % n;
        } while (bits - val + (n - 1) < 0);

        return val;
    }

    /**
     * Returns a random long within 0 - Long.MAX_VALUE
     * <p>
     * Extracted from {@code org.apache.commons.lang3.RandomUtils#nextLong()}
     *
     * @return the random long
     * @see #nextLong(long, long)
     */
    public static long nextLong() {
        return nextLong(Long.MAX_VALUE);
    }
}
