// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An average value that is updated atomically and is thread safe
 */
public class AtomicAverage {
    /** value used to indicate that the average is uninitialized */
    private static final long UNINITIALIZED_BITS = Double.doubleToLongBits(Long.MIN_VALUE);
    /** default weight if none is provided */
    private static final double DEFAULT_WEIGHT = 0.5;
    /** default value to return if average is not initialized */
    private static final double DEFAULT_UNINITIALIZED = 0;

    /** the weight that the latest value has on the average. 0 &lt; weight &lt; 1 */
    private final double weight;
    /** the value to return before any values are added to the average */
    private final double uninitializedValue;

    /** atomic variable that stores the average */
    private final AtomicLong average;

    /**
     * @param weight
     * 		the weight that the latest value has on the average. 0 &lt; weight &lt; 1
     * @param uninitializedValue
     * 		the value to return before any values are added to the average
     * @throws IllegalArgumentException
     * 		if the weight constraints are not met
     */
    public AtomicAverage(final double weight, final double uninitializedValue) {
        if (weight <= 0 || weight >= 1) {
            throw new IllegalArgumentException("weight must be greater than 0 and less than 1");
        }
        this.weight = weight;
        this.uninitializedValue = uninitializedValue;

        this.average = new AtomicLong(UNINITIALIZED_BITS);
    }

    /**
     * Same as {@link #AtomicAverage(double, double)} with uninitializedValue set to {@link #DEFAULT_UNINITIALIZED}
     */
    public AtomicAverage(final double weight) {
        this(weight, DEFAULT_UNINITIALIZED);
    }

    /**
     * Same as {@link #AtomicAverage(double)} with weight set to {@link #DEFAULT_WEIGHT}
     */
    public AtomicAverage() {
        this(DEFAULT_WEIGHT);
    }

    /**
     * Returns the average stored. If no values were provided, it will return the uninitializedValue provided in the
     * constructor. If the average is updated only once, it will return the value it was updated with.
     *
     * @return the latest average value
     */
    public double get() {
        final long bits = average.get();
        if (bits == UNINITIALIZED_BITS) {
            return uninitializedValue;
        }
        return Double.longBitsToDouble(bits);
    }

    /**
     * Reset to the initial value supplied in the constructor
     */
    public void reset() {
        average.set(UNINITIALIZED_BITS);
    }

    /**
     * Update the average
     *
     * @param value
     * 		the value to update the average with
     */
    public void update(final long value) {
        average.accumulateAndGet(value, this::updater);
    }

    private long updater(final long bits, final long value) {
        if (bits == UNINITIALIZED_BITS) {
            return Double.doubleToLongBits(value);
        }
        final double prevAvg = Double.longBitsToDouble(bits);
        final double newAvg = prevAvg * (1 - weight) + value * weight;
        return Double.doubleToLongBits(newAvg);
    }
}
