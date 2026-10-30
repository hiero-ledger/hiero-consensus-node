// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FeesTest {
    @Test
    void totalsAddNormally() {
        final var fees = new Fees(1, 2, 3);
        assertEquals(6, fees.totalFee());
        assertEquals(3, fees.totalWithoutServiceFee());
        assertEquals(5, fees.totalWithoutNodeFee());
    }

    @Test
    void totalFeeSaturatesInsteadOfThrowing() {
        // Previously Math.addExact threw on overflow; a saturated (degenerate-rate) component must instead
        // clamp the total to Long.MAX_VALUE so the operation reaches the insufficient-balance outcome.
        final var maxed = new Fees(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, maxed.totalFee());
        assertEquals(Long.MAX_VALUE, maxed.totalWithoutServiceFee());
        assertEquals(Long.MAX_VALUE, maxed.totalWithoutNodeFee());
    }

    @Test
    void totalFeeSaturatesWithSingleMaxComponent() {
        // Even one saturated component plus another positive one must clamp rather than throw.
        assertEquals(Long.MAX_VALUE, new Fees(Long.MAX_VALUE, 1, 0).totalFee());
        assertEquals(Long.MAX_VALUE, new Fees(0, Long.MAX_VALUE, 1).totalFee());
        assertEquals(Long.MAX_VALUE, new Fees(1, 0, Long.MAX_VALUE).totalFee());
    }
}
