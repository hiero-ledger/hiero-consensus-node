// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static com.swirlds.common.utility.Threshold.MAJORITY;
import static com.swirlds.common.utility.Threshold.STRONG_MINORITY;
import static com.swirlds.common.utility.Threshold.SUPER_MAJORITY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Threshold Tests")
class ThresholdTests {

    @Test
    @DisplayName("STRONG_MINORITY test")
    void strongMinorityTest() {
        // Test behavior near boundary region
        assertFalse(STRONG_MINORITY.isSatisfiedBy(2, 9));
        assertTrue(STRONG_MINORITY.isSatisfiedBy(3, 9));
        assertTrue(STRONG_MINORITY.isSatisfiedBy(4, 9));
        assertFalse(STRONG_MINORITY.isSatisfiedBy(2, 10));
        assertFalse(STRONG_MINORITY.isSatisfiedBy(3, 10));
        assertTrue(STRONG_MINORITY.isSatisfiedBy(4, 10));

        // Test behavior with large numbers
        long totalWeight = 50L * 1_000_000_000L * 100L * 1_000_000L;
        long quarterWeight = totalWeight / 4;
        assertTrue(STRONG_MINORITY.isSatisfiedBy(2 * quarterWeight, totalWeight));
        assertFalse(STRONG_MINORITY.isSatisfiedBy(1 * quarterWeight, totalWeight));
    }

    @Test
    @DisplayName("MAJORITY test")
    void isMajorityTest() {
        assertFalse(MAJORITY.isSatisfiedBy(Long.MIN_VALUE, 10), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(-1, 10), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(0, 10), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(1, 10), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(2, 10), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(3, 10), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(4, 10), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(5, 10), "is not majority");
        assertTrue(MAJORITY.isSatisfiedBy(6, 10), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(7, 10), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(8, 10), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(9, 10), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(10, 10), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(11, 10), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(Long.MAX_VALUE, 10), "is a majority");

        assertFalse(MAJORITY.isSatisfiedBy(Long.MIN_VALUE, 11), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(-1, 11), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(0, 11), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(1, 11), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(2, 11), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(3, 11), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(4, 11), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(5, 11), "is not majority");
        assertTrue(MAJORITY.isSatisfiedBy(6, 11), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(7, 11), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(8, 11), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(9, 11), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(10, 11), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(11, 11), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(Long.MAX_VALUE, 11), "is a majority");

        assertFalse(MAJORITY.isSatisfiedBy(Long.MIN_VALUE, Long.MAX_VALUE), "is not majority");
        assertFalse(MAJORITY.isSatisfiedBy(0, Long.MAX_VALUE), "is not majority");

        assertFalse(MAJORITY.isSatisfiedBy(Long.MAX_VALUE / 2, Long.MAX_VALUE), "is not majority");
        assertTrue(MAJORITY.isSatisfiedBy(Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE), "is a majority");
        assertTrue(MAJORITY.isSatisfiedBy(Long.MAX_VALUE, Long.MAX_VALUE), "is a majority");
    }

    @Test
    @DisplayName("SUPER_MAJORITY test")
    void superMajorityTest() {
        // Test behavior near boundary region
        assertFalse(SUPER_MAJORITY.isSatisfiedBy(5, 9));
        assertFalse(SUPER_MAJORITY.isSatisfiedBy(6, 9));
        assertTrue(SUPER_MAJORITY.isSatisfiedBy(7, 9));
        assertFalse(SUPER_MAJORITY.isSatisfiedBy(5, 10));
        assertFalse(SUPER_MAJORITY.isSatisfiedBy(6, 10));
        assertTrue(SUPER_MAJORITY.isSatisfiedBy(7, 10));

        // Test behavior with large numbers
        long totalWeight = 50L * 1_000_000_000L * 100L * 1_000_000L;
        long quarterWeight = totalWeight / 4;
        assertTrue(SUPER_MAJORITY.isSatisfiedBy(3 * quarterWeight, totalWeight));
        assertFalse(SUPER_MAJORITY.isSatisfiedBy(2 * quarterWeight, totalWeight));
    }
}
