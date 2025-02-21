// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event;

import java.util.Random;

/**
 * An object that provides values that dynamically change over time.
 */
public class DynamicValueGenerator<T> {

    private final DynamicValue<T> generator;
    private long previousEventIndex;
    private T previousValue;

    public DynamicValueGenerator(DynamicValue<T> generator) {
        this.generator = generator;
        this.previousValue = null;
        this.previousEventIndex = -1;
    }

    /**
     * Get the next value.
     *
     * @param random
     * 		a random number generator
     * @param eventIndex
     * 		the index of the next event to be created
     * @return the next value
     */
    public T get(Random random, long eventIndex) {

        if (previousEventIndex == eventIndex) {
            // If called more than once for the same index, return the previously computed value
            return previousValue;
        }

        if (previousEventIndex >= eventIndex) {
            // The previous index is after the current index. This will require the entire sequence to be recomputed.
            reset();
        }

        // If we have skipped index values, iterate forward to the requested index
        while (previousEventIndex < eventIndex - 1) {
            get(random, previousEventIndex + 1);
        }

        T ret = generator.get(random, eventIndex, previousValue);
        previousValue = ret;
        previousEventIndex++;
        return ret;
    }

    /**
     * Reset this object to its initial state.
     */
    public void reset() {
        previousValue = null;
        previousEventIndex = -1;
    }

    /**
     * Get a copy of this object in its current state.
     */
    public DynamicValueGenerator<T> copy() {
        DynamicValueGenerator<T> ret = cleanCopy();
        ret.previousValue = previousValue;
        return ret;
    }

    /**
     * Get a copy of this object as it was in its initial state.
     */
    public DynamicValueGenerator<T> cleanCopy() {
        return new DynamicValueGenerator<>(generator);
    }
}
