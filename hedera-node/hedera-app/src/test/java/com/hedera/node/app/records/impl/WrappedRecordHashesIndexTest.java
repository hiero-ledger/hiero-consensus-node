// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WrappedRecordHashesIndexTest {
    @Test
    void detectsGapsAndOnlyReportsEachGapOnce() {
        final var index = new WrappedRecordHashesIndex();

        assertEquals(List.of(), index.addAndGetNewGaps(0));
        assertEquals(List.of(), index.addAndGetNewGaps(1));
        assertFalse(index.hasGaps());
        assertEquals(0, index.lowestBlock());
        assertEquals(1, index.highestBlock());

        // Create a gap at block 2 by adding block 3
        final var gaps = index.addAndGetNewGaps(3);
        assertTrue(index.hasGaps());
        assertEquals(0, index.lowestBlock());
        assertEquals(3, index.highestBlock());
        assertEquals(List.of(new WrappedRecordHashesIndex.GapRange(2, 2)), gaps);

        // Gap should not be reported again on subsequent checks
        assertEquals(List.of(), index.addAndGetNewGaps(4));
        assertTrue(index.hasGaps());

        // Filling the gap clears hasGaps
        assertEquals(List.of(), index.addAndGetNewGaps(2));
        assertFalse(index.hasGaps());
        assertEquals(0, index.lowestBlock());
        assertEquals(4, index.highestBlock());
    }
}
