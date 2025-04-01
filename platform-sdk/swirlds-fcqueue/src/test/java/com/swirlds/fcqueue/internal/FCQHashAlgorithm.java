// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue.internal;

import static org.hiero.consensus.utility.ByteUtils.byteArrayToLong;
import static org.hiero.consensus.utility.ByteUtils.longToByteArray;

public enum FCQHashAlgorithm {
    SUM_HASH(FCQHashAlgorithm::sumHashCalculator, FCQHashAlgorithm::removalSumHashCalculator),
    ROLLING_HASH(FCQHashAlgorithm::rollingHashCalculator, FCQHashAlgorithm::removalRollingHashCalculator),
    MERKLE_HASH(FCQHashAlgorithm::merkleHashCalculator, FCQHashAlgorithm::removalMerkleHashCalculator);

    public static final long HASH_RADIX = 3;

    private static final int BYTE_BLOCK = 8;
    private final TriConsumer hashCalculator;
    private final TriConsumer removalHashCalculator;

    FCQHashAlgorithm(final TriConsumer hashCalculator, final TriConsumer removalHashCalculator) {
        this.hashCalculator = hashCalculator;
        this.removalHashCalculator = removalHashCalculator;
    }

    public void computeHash(final byte[] hash, final byte[] elementHash, final int exponent) {
        this.hashCalculator.accept(hash, elementHash, exponent);
    }

    public void computeRemoveHash(final byte[] hash, final byte[] elementHash, final int exponent) {
        this.removalHashCalculator.accept(hash, elementHash, exponent);
    }

    /**
     * This is treated as a "set", not "list", so changing the order does not change the hash.
     * The hash is the sum of the hashes of the elements, modulo 2^384.
     *
     * Note, for applications like Hedera, for the queue of receipts or queue of records, each
     * element of the queue will have a unique  timestamp, and they will always be sorted by those
     * timestamps. So the hash of the set is equivalent to the hash of a list. But if it is ever
     * required to have a hash of a list, then the rolling hash is better.
     *
     * @param localHash
     * 		current hash
     * @param elementHash
     * 		hash of element to add
     */
    private static void sumHashCalculator(final byte[] localHash, final byte[] elementHash, final int exponent) {
        // the sum of the hashes of the elements, modulo 2^384.
        int carry = 0;
        for (int i = 0; i < localHash.length; i++) {
            carry += (localHash[i] & 0xff) + (elementHash[i] & 0xff);
            localHash[i] = (byte) carry;
            carry >>= BYTE_BLOCK;
        }
    }

    /**
     * Do hash = (hash - elementHash) mod 2^^384
     *
     * @param hash
     * 		current hash
     * @param elementHash
     * 		hash of element to remove
     */
    private static void removalSumHashCalculator(final byte[] hash, final byte[] elementHash, final int exponent) {

        int carry = 0;
        for (int i = 0; i < hash.length; i++) {
            carry += (hash[i] & 0xff) - (elementHash[i] & 0xff);
            hash[i] = (byte) carry;
            carry >>= BYTE_BLOCK;
        }
    }

    /**
     * This is a rolling hash, so it takes into account the order.
     * if the queue contains {a,b,c,d}, where "a" is the head and "d" is the tail, then define:
     *
     * hash64({a,b,c,d}) = a * 3^^3 + b * 3^^2 + c * 3^^1 + d * 3^^0 mod 2^^64
     * hash64({a,b,c})   = a * 3^^2 + b * 3^^1 + c * 3^^0            mod 2^^64
     * hash64({b,c,d})   = b * 3^^2 + c * 3^^1 + d * 3^^0            mod 2^^64
     *
     * Which implies these:
     *
     * hash64({a,b,c,d}) = hash64({a,b,c}) * 3 + d                   mod 2^^64     //add(d)
     * hash64({b,c,d})   = hash64({a,b,c,d}) - a * 3^^3              mod 2^^64     //remove()
     * deletes a
     * d
     * a * 3^^2 + b * 3^^1 + c*3^^0
     * rollingHashSize = 3
     * currentSize = 4
     * diff = currentSize - rollingHashSize = 1
     * currenthHash *= power(3, diff)
     * a * 3^^3 + b * 3^^2 + c*3^^1
     *
     * so we add an element by multiplying by 3 and adding the new element's hash,
     * and we remove an element by subtracting that element times 3 to the power of the resulting size.
     *
     * This is all easy to do for a 64-bit hash by keeping track of 3^^size modulo 2^^64, and
     * multiplying it by 3 every time the size increments, and multiplying by the inverse of
     * 3 each time it decrements.
     * The multiplicative inverse of 3 modulo 2^^64 is 0xaaaaaaaaaaaaaaab (that's 15 a digits then a b).
     *
     * It would be much slower to use modulo 2^^384, but we don't have to do that. We can treat the
     * 48-byte hash as a sequence of 6 numbers, each of which is an unsigned 64 bit integer.  We do this
     * rolling hash on each of the 6 numbers independently. Then it ends up being simple and fast
     */
    private static void rollingHashCalculator(final byte[] localHash, final byte[] elementHash, final int exponent) {
        //    hash64({a,b,c,d}) = a * 3^^3 + b * 3^^2 + c * 3^^1 + d * 3^^0 mod 2^^64
        //	 but we're lazy and skipped the carry - also means this could be done as a vector[6]
        final long power = power(HASH_RADIX, exponent);
        for (int i = 0; i < localHash.length; i += BYTE_BLOCK) { // process 8 bytes at a time
            final long old = byteArrayToLong(localHash, i);
            final long elm = byteArrayToLong(elementHash, i);
            final long value = old + power * elm;
            longToByteArray(value, localHash, i);
        }
    }

    private static void removalRollingHashCalculator(final byte[] hash, final byte[] elementHash, final int exponent) {
        final long power = power(HASH_RADIX, exponent);
        for (int i = 0; i < hash.length; i += BYTE_BLOCK) {
            final long old = byteArrayToLong(hash, i);
            final long elm = byteArrayToLong(elementHash, i);
            final long value = old - power * elm;
            longToByteArray(value, hash, i);
        }
    }

    public static void increaseRollingBase(final int power, final byte[] localHash) {
        final long factor = power(HASH_RADIX, power);
        for (int i = 0; i < localHash.length; i += BYTE_BLOCK) {
            final long old = byteArrayToLong(localHash, i);
            final long value = old * factor;
            longToByteArray(value, localHash, i);
        }
    }

    private static void merkleHashCalculator(final byte[] hash, final byte[] elementHash, final int exponent) {
        throw new UnsupportedOperationException("Hash algorithm Merkle is not supported");
    }

    private static void removalMerkleHashCalculator(final byte[] hash, final byte[] elementHash, final int exponent) {
        throw new UnsupportedOperationException("Hash algorithm Merkle is not supported");
    }

    /**
     * Compute x^y
     *
     * @param x
     * 		base
     * @param y
     * 		power
     * @return x^y
     */
    private static long power(long x, long y) {
        long res = 1;

        while (y > 0) {
            if ((y & 1) == 1) {
                res = res * x;
            }

            y >>= 1;
            x *= x;
        }

        return res;
    }

    @FunctionalInterface
    interface TriConsumer {
        void accept(byte[] a, byte[] b, int c);
    }
}
