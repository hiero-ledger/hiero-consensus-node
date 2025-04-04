// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import static org.hiero.base.utility.CompareTo.isGreaterThan;
import static org.hiero.base.utility.CompareTo.isGreaterThanOrEqualTo;
import static org.hiero.base.utility.CompareTo.isLessThan;
import static org.hiero.base.utility.CompareTo.isLessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CompareTo Test")
class CompareToTest {

    private record ComparableInteger(int value) implements Comparable<ComparableInteger> {
        @Override
        public int compareTo(final ComparableInteger that) {
            return Integer.compare(this.value, that.value);
        }
    }

    @Test
    @DisplayName("Greater Than")
    void greaterThan() {
        final ComparableInteger a = new ComparableInteger(0);
        final ComparableInteger b = new ComparableInteger(1);

        assertFalse(isGreaterThan(a, a), "value can not be greater than itself");
        assertFalse(isGreaterThan(a, b), "a is not greater than b");
        assertTrue(isGreaterThan(b, a), "a is greater than b");
    }

    @Test
    @DisplayName("Greater Than Or Equal")
    void greaterThanOrEqual() {
        final ComparableInteger a = new ComparableInteger(0);
        final ComparableInteger b = new ComparableInteger(1);

        assertTrue(isGreaterThanOrEqualTo(a, a), "values are equal");
        assertFalse(isGreaterThanOrEqualTo(a, b), "a is not greater or equal to than b");
        assertTrue(isGreaterThanOrEqualTo(b, a), "a is greater than or equal to b");
    }

    @Test
    @DisplayName("Less Than")
    void lessThan() {
        final ComparableInteger a = new ComparableInteger(0);
        final ComparableInteger b = new ComparableInteger(1);

        assertFalse(isLessThan(a, a), "value can not be less than itself");
        assertTrue(isLessThan(a, b), "a is less than b");
        assertFalse(isLessThan(b, a), "b is not less than a");
    }

    @Test
    @DisplayName("Less Than Or Equal")
    void lessThanOrEqual() {
        final ComparableInteger a = new ComparableInteger(0);
        final ComparableInteger b = new ComparableInteger(1);

        assertTrue(isLessThanOrEqualTo(a, a), "value are equal");
        assertTrue(isLessThanOrEqualTo(a, b), "a is less than or equal to b");
        assertFalse(isLessThanOrEqualTo(b, a), "b is not less than or equal to a");
    }

    @Test
    @DisplayName("Min")
    void min() {
        final ComparableInteger a = new ComparableInteger(0);
        final ComparableInteger b = new ComparableInteger(1);
        final ComparableInteger c = new ComparableInteger(1);

        assertEquals(a, CompareTo.min(a, b), "minimum should have been chosen");
        assertEquals(a, CompareTo.min(b, a), "minimum should have been chosen");
        assertEquals(a, CompareTo.min(a, a), "minimum should have been chosen");
        assertEquals(b, CompareTo.min(b, b), "minimum should have been chosen");
        assertSame(b, CompareTo.min(b, c), "when equal the first value should be returned");
    }

    @Test
    @DisplayName("Max")
    void max() {
        final ComparableInteger a = new ComparableInteger(0);
        final ComparableInteger b = new ComparableInteger(1);
        final ComparableInteger c = new ComparableInteger(1);

        assertEquals(b, CompareTo.max(a, b), "maximum should have been chosen");
        assertEquals(b, CompareTo.max(b, a), "maximum should have been chosen");
        assertEquals(a, CompareTo.max(a, a), "maximum should have been chosen");
        assertEquals(b, CompareTo.max(b, b), "maximum should have been chosen");
        assertSame(b, CompareTo.max(b, c), "when equal the first value should be returned");
    }
}
