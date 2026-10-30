// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HintsServiceTest {
    @ParameterizedTest
    @CsvSource({"1,4", "2,4", "3,8", "6,8", "7,16", "14,16", "15,32", "30,32", "31,64", "62,64", "63,128"})
    void preservesPartyAllocationAndItsPowerOfTwoBoundaries(final int rosterNodes, final int capacity) {
        assertEquals(capacity, HintsService.partySizeForRosterNodeCount(rosterNodes));
    }
}
