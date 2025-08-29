// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

/** A bucket of discrete capacity.
 * It simultaneously keeps track of two limits:
 *  - the nominal capacity ("soft limit"): can be used for checking if the bucket is full or
 *    calculating the amount to leak. No error is thrown when the nominal capacity is exceeded.
 *  - the brimful capacity ("hard limit"): the actual capacity of the bucket. Throws an error
 *    if this was to be exceeded.
 * Some common use cases are:
 *  - setting both nominal and brimful capacity to the same value to get a bucket with a single hard limit
 *  - setting nominal capacity to the desired limit and brimful capacity to Long.MAX_VALUE and then using
 *      the `nominalCapacityFree()` method to decide whether some action is allowed or not
 * */
public class DiscreteLeakyBucket {
    private long used;
    private final long nominalCapacity;
    private final long brimfulCapacity;

    /**
     * Creates a new bucket of both nominal and brimful capacities equal to {@param capacity}.
     */
    public static DiscreteLeakyBucket ofFixedCapacity(long capacity) {
        return new DiscreteLeakyBucket(capacity, capacity);
    }

    /**
     * Creates a new bucket of the given nominal and brimful capacities.
     */
    public static DiscreteLeakyBucket ofNominalAndBrimfulCapacity(long nominalCapacity, long brimfulCapacity) {
        return new DiscreteLeakyBucket(nominalCapacity, brimfulCapacity);
    }

    private DiscreteLeakyBucket(long nominalCapacity, long brimfulCapacity) {
        assertArgument(nominalCapacity >= 0, "nominalCapacity must be >= 0");
        assertArgument(brimfulCapacity >= 0, "brimfulCapacity must be >= 0");
        assertArgument(brimfulCapacity >= nominalCapacity, "brimfulCapacity must be >= nominalCapacity");

        used = 0L;
        this.nominalCapacity = nominalCapacity;
        this.brimfulCapacity = brimfulCapacity;
    }

    /**
     * @return an absolute value of available nominal capacity. Returns 0 when overfilled.
     */
    public long nominalCapacityFree() {
        return Math.max(0, nominalCapacity - used);
    }

    /**
     * @return an absolute value of available brimful capacity. Returns 0 when full. Brimful capacity can't overfill.
     */
    public long brimfulCapacityFree() {
        return brimfulCapacity - used;
    }

    /**
     * @return an absolute value of used capacity.
     */
    public long capacityUsed() {
        return used;
    }

    /**
     * @return the nominal capacity of this bucket
     */
    public long nominalCapacity() {
        return nominalCapacity;
    }

    /**
     * @return the brimful capacity of this bucket
     */
    public long brimfulCapacity() {
        return brimfulCapacity;
    }

    /**
     * Uses a given amount of capacity. Throws an IllegalArgumentException if brimful capacity was to be exceeded.
     */
    public void useCapacity(long units) {
        assertArgument(units >= 0, "units to leak must be >= 0");

        long newUsed = used + units;
        assertValidNewUsed(units, newUsed);
        used = newUsed;
    }

    void assertValidNewUsed(long units, long newUsed) {
        if (newUsed < 0 || newUsed > brimfulCapacity) {
            throw new IllegalArgumentException(String.format(
                    "Can't use %d units. New value of %d would overflow the capacity of %d.",
                    units, newUsed, brimfulCapacity));
        }
    }

    /**
     * Leaks a given amount of capacity up to the used amount.
     */
    public void leak(long units) {
        assertArgument(units >= 0, "units to leak must be >= 0");

        used -= Math.min(used, units);
    }

    /**
     * Sets used capacity to the given value. Throws IllegalArgumentException if the new value exceeds the brimful capacity.
     */
    public void resetUsed(long newUsed) {
        if (newUsed < 0 || newUsed > brimfulCapacity) {
            throw new IllegalArgumentException(String.format(
                    "Can't set used to %d units - it overflows the capacity of %d.", newUsed, brimfulCapacity));
        }
        this.used = newUsed;
    }

    private static void assertArgument(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
