// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FeeResultTest {
    private static final long UNIT_COST = 10L;
    private static final long INCLUDED = 7L;
    // Within INCLUDED of Long.MIN_VALUE, a bare `used - included` underflows and wraps to a huge positive count
    private static final long UNDERFLOWING_USED = Long.MIN_VALUE + 6;

    @Test
    void serviceExtraChargesNothingWhenUsedUnderflowsTheIncludedCount() {
        final var result = new FeeResult();

        result.addServiceExtraFeeTinycents("GAS", UNIT_COST, UNDERFLOWING_USED, INCLUDED);

        assertEquals(0L, result.getServiceTotalTinycents());
        assertTrue(result.getServiceExtraDetails().isEmpty());
    }

    @Test
    void nodeExtraChargesNothingWhenUsedUnderflowsTheIncludedCount() {
        final var result = new FeeResult();

        result.addNodeExtraFeeTinycents("SIGNATURES", UNIT_COST, UNDERFLOWING_USED, INCLUDED);

        assertEquals(0L, result.getNodeTotalTinycents());
        assertTrue(result.getNodeExtraDetails().isEmpty());
    }

    @Test
    void extrasChargeOnlyTheUnitsBeyondTheIncludedCount() {
        final var result = new FeeResult();

        result.addServiceExtraFeeTinycents("GAS", UNIT_COST, 10L, INCLUDED);
        result.addNodeExtraFeeTinycents("SIGNATURES", UNIT_COST, 10L, INCLUDED);

        // 10 used, 7 included: 3 billable units at 10 tinycents each
        assertEquals(30L, result.getServiceTotalTinycents());
        assertEquals(30L, result.getNodeTotalTinycents());
    }
}
