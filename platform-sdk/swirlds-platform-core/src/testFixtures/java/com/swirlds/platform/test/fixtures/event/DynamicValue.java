// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event;

import java.util.Random;

/**
 * This functional interface is used to configure event generation with a non-static value of type T.
 */
public interface DynamicValue<T> {

    /**
     * Generate the next value.
     *
     * This function should not make modifications to any objects in external scopes.
     *
     * @param random
     * 		if the value is not randomized this can be ignored. Otherwise this must be the only source of
     * 		of randomness used by this method.
     * @param eventIndex
     * 		the index of the next event to be created
     * @param previousValue
     * 		the value returned by this method at the previous index. Returns null if there was no previous call.
     */
    T get(Random random, long eventIndex, T previousValue);
}
