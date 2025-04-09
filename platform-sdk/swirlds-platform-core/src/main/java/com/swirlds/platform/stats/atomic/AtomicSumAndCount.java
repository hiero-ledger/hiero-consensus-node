// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats.atomic;

import org.hiero.base.concurrent.atomic.AtomicIntPair;

/**
 * Atomically sums the values provided as well as counting the number of additions
 */
public class AtomicSumAndCount {
    final AtomicIntPair sumAndCount = new AtomicIntPair();

    private static double average(final int sum, final int count) {
        if (count == 0) {
            // avoid division by 0
            return 0;
        }
        return ((double) sum) / count;
    }

    /**
     * Add the value to the sum and increment the count
     *
     * @param value
     * 		the value to add
     */
    public void add(final int value) {
        sumAndCount.accumulate(value, 1);
    }

    /**
     * @return the current sum
     */
    public int getSum() {
        return sumAndCount.getLeft();
    }

    /**
     * @return the current count
     */
    public int getCount() {
        return sumAndCount.getRight();
    }

    /**
     * @return the sum divided by the count
     */
    public double average() {
        return sumAndCount.computeDouble(AtomicSumAndCount::average);
    }

    /**
     * Same as {@link #average()} but also resets the value atomically
     */
    public double averageAndReset() {
        return sumAndCount.computeDoubleAndReset(AtomicSumAndCount::average);
    }

    /**
     * Resets the value atomically
     */
    public void reset() {
        sumAndCount.reset();
    }
}
