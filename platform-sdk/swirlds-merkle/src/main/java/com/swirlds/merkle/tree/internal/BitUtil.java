// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.tree.internal;

public final class BitUtil {

    private static final int BITS_LIMIT = 62;

    private BitUtil() {}

    /**
     * Finds b = leftmost 1 bit in size (assuming size &gt; 1)
     *
     * @param value
     * 		&gt; 1
     * @return leftmost 1 bit
     */
    public static long findLeftMostBit(final long value) {
        if (value == 0) {
            return 0;
        }

        long leftMostBit = 1L << BITS_LIMIT;
        while ((value & leftMostBit) == 0) {
            leftMostBit >>= 1;
        }

        return leftMostBit;
    }
}
