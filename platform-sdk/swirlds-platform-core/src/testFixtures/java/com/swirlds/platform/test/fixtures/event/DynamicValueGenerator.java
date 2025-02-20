/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
