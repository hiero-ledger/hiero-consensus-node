// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import static java.lang.Math.toIntExact;

import java.util.concurrent.atomic.AtomicLongArray;

public class LongCountArray {

    private static final int LONGS_PER_CHUNK = 1 << 20;
    private static final int BITS_PER_COUNT = 2;
    private static final int COUNTS_PER_LONG = Long.SIZE / BITS_PER_COUNT;
    private static final long COUNT_MASK = (1L << BITS_PER_COUNT) - 1;

    private final long size;

    private final AtomicLongArray[] arrays;

    public LongCountArray(long size) {
        this.size = size;
        int maxChunkIndex = toIntExact((size - 1) / COUNTS_PER_LONG / LONGS_PER_CHUNK);
        arrays = new AtomicLongArray[maxChunkIndex + 1];
        for (int i = 0; i < arrays.length; ++i) {
            arrays[i] = new AtomicLongArray(LONGS_PER_CHUNK);
        }
    }

    public long size() {
        return size;
    }

    public long getAndIncrement(long idx) {
        if (idx < 0 || idx >= size) {
            throw new IndexOutOfBoundsException();
        }

        int chunkIdx = toIntExact(idx / COUNTS_PER_LONG / LONGS_PER_CHUNK);
        int longIdx = toIntExact((idx / COUNTS_PER_LONG) % LONGS_PER_CHUNK);
        int countOffset = toIntExact(idx % COUNTS_PER_LONG) * BITS_PER_COUNT;

        long val = arrays[chunkIdx].get(longIdx);

        while (true) {
            long count = (val >>> countOffset) & COUNT_MASK;
            if (count == COUNT_MASK) {
                return count;
            }
            long newVal = val + (1L << countOffset);
            long oldVal = arrays[chunkIdx].compareAndExchange(longIdx, val, newVal);
            if (oldVal == val) {
                return count;
            }
            val = oldVal;
        }
    }
}
