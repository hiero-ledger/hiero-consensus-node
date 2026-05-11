// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Iterator;

/**
 * Contains various useful assertions.
 */
public final class AssertionUtils {

    private AssertionUtils() {}

    /**
     * Walk over two iterators and assert that each element returned is equal
     *
     * @param iteratorA
     * 		the first iterator
     * @param iteratorB
     * 		the second iterator
     * @param <T>
     * 		the type of the data returned by the iterator
     */
    public static <T> void assertIteratorEquality(final Iterator<T> iteratorA, final Iterator<T> iteratorB) {
        int count = 0;
        while (iteratorA.hasNext() && iteratorB.hasNext()) {
            assertEquals(iteratorA.next(), iteratorB.next(), "The element at position " + count + " does not match.");
            count++;
        }
        assertFalse(iteratorA.hasNext(), "iterator A is not depleted");
        assertFalse(iteratorB.hasNext(), "iterator B is not depleted");
    }
}
