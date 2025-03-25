// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static com.swirlds.common.utility.ByteUtils.byteArrayToLong;
import static org.hiero.consensus.model.utility.CommonUtils.getNormalisedStringBytes;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * <p>
 * This class contains a collection of methods for hashing basic data types.
 * Hashes are not cryptographically secure, and are intended to be used when
 * implementing {@link Object#hashCode()} or similar functionality.
 * </p>
 *
 * <p>
 * This class provides a large number of methods with different signatures, the goal being to avoid the
 * creation of arrays to pass a variable number of arguments. Hashing happens a lot and needs to be fast,
 * so if we can avoid lots of extra allocations it is worthwhile.
 * </p>
 */
public final class NonCryptographicHashing {

    private NonCryptographicHashing() {}

    /**
     * <p>
     * Data types that can be hashed.
     * </p>
     *
     * <p>
     * WARNING: only add to the end of this list.
     * Do not change or remove or reorder any existing elements, or it will change the hashes.
     * </p>
     */
    private enum DataType {
        LONG,
        LONG_ARRAY,
        BYTE_ARRAY,
        STRING
    }

    /**
     * For every hash, mix in a long derived from the data type and the length of the data.
     * This causes the hashes for different data types and different lengths of data to differ
     * with moderately high probability.
     *
     * @param type
     * 		the type of the data
     * @param length
     * 		the length of the data
     * @return a long to mix into the hash
     */
    private static long computeMixin(@NonNull final DataType type, final long length) {
        return ((long) type.ordinal()) | (length << 32);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 1 long.
     *
     * @param x0
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(final long x0) {
        return perm64(perm64(computeMixin(DataType.LONG, 1)) ^ x0);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 2 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(final long x0, final long x1) {
        return perm64(perm64(perm64(computeMixin(DataType.LONG, 2)) ^ x0) ^ x1);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 3 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(final long x0, final long x1, final long x2) {
        return perm64(perm64(perm64(perm64(computeMixin(DataType.LONG, 3)) ^ x0) ^ x1) ^ x2);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 4 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(final long x0, final long x1, final long x2, final long x3) {
        return perm64(perm64(perm64(perm64(perm64(computeMixin(DataType.LONG, 4)) ^ x0) ^ x1) ^ x2) ^ x3);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 5 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(final long x0, final long x1, final long x2, final long x3, final long x4) {
        return perm64(perm64(perm64(perm64(perm64(perm64(computeMixin(DataType.LONG, 5)) ^ x0) ^ x1) ^ x2) ^ x3) ^ x4);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 6 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(
            final long x0, final long x1, final long x2, final long x3, final long x4, final long x5) {
        return perm64(
                perm64(perm64(perm64(perm64(perm64(perm64(computeMixin(DataType.LONG, 6)) ^ x0) ^ x1) ^ x2) ^ x3) ^ x4)
                        ^ x5);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 7 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @param x6
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(
            final long x0, final long x1, final long x2, final long x3, final long x4, final long x5, final long x6) {
        return perm64(
                perm64(perm64(perm64(perm64(perm64(perm64(perm64(computeMixin(DataType.LONG, 7)) ^ x0) ^ x1) ^ x2) ^ x3)
                                        ^ x4)
                                ^ x5)
                        ^ x6);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 8 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @param x6
     * 		a long
     * @param x7
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(
            final long x0,
            final long x1,
            final long x2,
            final long x3,
            final long x4,
            final long x5,
            final long x6,
            final long x7) {
        return perm64(
                perm64(perm64(perm64(perm64(perm64(perm64(perm64(perm64(computeMixin(DataType.LONG, 8)) ^ x0) ^ x1)
                                                                ^ x2)
                                                        ^ x3)
                                                ^ x4)
                                        ^ x5)
                                ^ x6)
                        ^ x7);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 9 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @param x6
     * 		a long
     * @param x7
     * 		a long
     * @param x8
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(
            final long x0,
            final long x1,
            final long x2,
            final long x3,
            final long x4,
            final long x5,
            final long x6,
            final long x7,
            final long x8) {
        return perm64(
                perm64(perm64(perm64(perm64(perm64(perm64(perm64(perm64(perm64(computeMixin(DataType.LONG, 9)) ^ x0)
                                                                                ^ x1)
                                                                        ^ x2)
                                                                ^ x3)
                                                        ^ x4)
                                                ^ x5)
                                        ^ x6)
                                ^ x7)
                        ^ x8);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for 10 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @param x6
     * 		a long
     * @param x7
     * 		a long
     * @param x8
     * 		a long
     * @param x9
     * 		a long
     * @return a non-cryptographic long hash
     */
    public static long hash64(
            final long x0,
            final long x1,
            final long x2,
            final long x3,
            final long x4,
            final long x5,
            final long x6,
            final long x7,
            final long x8,
            final long x9) {
        return perm64(
                perm64(perm64(perm64(perm64(perm64(perm64(perm64(perm64(perm64(perm64(computeMixin(DataType.LONG, 10))
                                                                                                ^ x0)
                                                                                        ^ x1)
                                                                                ^ x2)
                                                                        ^ x3)
                                                                ^ x4)
                                                        ^ x5)
                                                ^ x6)
                                        ^ x7)
                                ^ x8)
                        ^ x9);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for an array of longs.
     *
     * @param x
     * 		an array of longs
     * @return a non-cryptographic integer hash
     */
    public static long hash64(@NonNull final long... x) {
        long t = perm64(computeMixin(DataType.LONG_ARRAY, x.length));
        for (final long l : x) {
            t = perm64(t ^ l);
        }
        return t;
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 1 long.
     *
     * @param x0
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(final long x0) {

        return (int) hash64(x0);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 2 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(final long x0, final long x1) {
        return (int) hash64(x0, x1);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 3 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(final long x0, final long x1, final long x2) {
        return (int) hash64(x0, x1, x2);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 4 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(final long x0, final long x1, final long x2, final long x3) {
        return (int) hash64(x0, x1, x2, x3);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 5 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(final long x0, final long x1, final long x2, final long x3, final long x4) {
        return (int) hash64(x0, x1, x2, x3, x4);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 6 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(final long x0, final long x1, final long x2, final long x3, final long x4, final long x5) {
        return (int) hash64(x0, x1, x2, x3, x4, x5);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 7 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @param x6
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(
            final long x0, final long x1, final long x2, final long x3, final long x4, final long x5, final long x6) {
        return (int) hash64(x0, x1, x2, x3, x4, x5, x6);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 8 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @param x6
     * 		a long
     * @param x7
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(
            final long x0,
            final long x1,
            final long x2,
            final long x3,
            final long x4,
            final long x5,
            final long x6,
            final long x7) {
        return (int) hash64(x0, x1, x2, x3, x4, x5, x6, x7);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 9 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @param x6
     * 		a long
     * @param x7
     * 		a long
     * @param x8
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(
            final long x0,
            final long x1,
            final long x2,
            final long x3,
            final long x4,
            final long x5,
            final long x6,
            final long x7,
            final long x8) {
        return (int) hash64(x0, x1, x2, x3, x4, x5, x6, x7, x8);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for 10 longs.
     *
     * @param x0
     * 		a long
     * @param x1
     * 		a long
     * @param x2
     * 		a long
     * @param x3
     * 		a long
     * @param x4
     * 		a long
     * @param x5
     * 		a long
     * @param x6
     * 		a long
     * @param x7
     * 		a long
     * @param x8
     * 		a long
     * @param x9
     * 		a long
     * @return a non-cryptographic integer hash
     */
    public static int hash32(
            final long x0,
            final long x1,
            final long x2,
            final long x3,
            final long x4,
            final long x5,
            final long x6,
            final long x7,
            final long x8,
            final long x9) {
        return (int) hash64(x0, x1, x2, x3, x4, x5, x6, x7, x8, x9);
    }

    /**
     * Generates a non-cryptographic 32 bit hash for an array of longs.
     *
     * @param x
     * 		an array of longs
     * @return a non-cryptographic integer hash
     */
    public static int hash32(@NonNull final long... x) {
        return (int) hash64(x);
    }

    /**
     * Generates a non-cryptographic 64 bit hash for a byte array.
     *
     * @param bytes
     * 		a byte array
     * @return a non-cryptographic long hash
     */
    public static long hash64(@NonNull final byte[] bytes) {
        long hash = perm64(computeMixin(DataType.BYTE_ARRAY, bytes.length));
        for (int i = 0; i < bytes.length; i += 8) {
            hash = perm64(hash ^ byteArrayToLong(bytes, i));
        }
        return hash;
    }

    /**
     * Generates a non-cryptographic 32 bit hash for a byte array.
     *
     * @param bytes
     * 		a byte array
     * @return a non-cryptographic int hash
     */
    public static long hash32(@NonNull final byte[] bytes) {
        return (int) hash64(bytes);
    }

    /**
     * Generates a non-cryptographic 64 bit hash from the normalized bytes of a string.
     *
     * @param string
     * 		a string
     * @return a non-cryptographic long hash
     */
    public static long hash64(@NonNull final String string) {
        final byte[] bytes = getNormalisedStringBytes(string);

        long hash = perm64(computeMixin(DataType.STRING, bytes.length));
        for (int i = 0; i < bytes.length; i += 8) {
            hash = perm64(hash ^ byteArrayToLong(bytes, i));
        }
        return hash;
    }

    /**
     * Generates a non-cryptographic 32 bit hash for a string.
     *
     * @param string
     * 		a string
     * @return a non-cryptographic int hash
     */
    public static int hash32(@NonNull final String string) {
        return (int) hash64(string);
    }

    /**
     * <p>
     * A permutation (invertible function) on 64 bits.
     * The constants were found by automated search, to
     * optimize avalanche. Avalanche means that for a
     * random number x, flipping bit i of x has about a
     * 50 percent chance of flipping bit j of perm64(x).
     * For each possible pair (i,j), this function achieves
     * a probability between 49.8 and 50.2 percent.
     * </p>
     *
     * <p>
     * Leemon wrote this, it's magic and does magic things. Like holy molly does
     * this algorithm resolve some nasty hash collisions for troublesome data sets.
     * Don't mess with this method.
     *
     * <p>
     * Warning: there currently exist production use cases that will break if this hashing algorithm is changed.
     * If modifications to this hashing algorithm are ever required, we will need to "fork" this class and leave
     * the old algorithm intact.
     */
    private static long perm64(long x) {

        // This is necessary so that 0 does not hash to 0.
        // As a side effect this constant will hash to 0.
        // It was randomly generated (not using Java),
        // so that it will occur in practice less often than more
        // common numbers like 0 or -1 or Long.MAX_VALUE.
        x ^= 0x5e8a016a5eb99c18L;

        // Shifts: {30, 27, 16, 20, 5, 18, 10, 24, 30}
        x += x << 30;
        x ^= x >>> 27;
        x += x << 16;
        x ^= x >>> 20;
        x += x << 5;
        x ^= x >>> 18;
        x += x << 10;
        x ^= x >>> 24;
        x += x << 30;
        return x;
    }
}
