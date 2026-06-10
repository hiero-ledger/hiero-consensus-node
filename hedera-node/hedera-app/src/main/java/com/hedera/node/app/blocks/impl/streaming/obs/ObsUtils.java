// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Shared constants and utilities for the obs package.
 */
public final class ObsUtils {
    private ObsUtils() {}

    /**
     * Rounds {@code d} to 3 decimal places using HALF_EVEN rounding.
     */
    public static BigDecimal round(final double d) {
        return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_EVEN);
    }

    /** 10-significant-digit precision used across all statistics computations. */
    static MathContext MATH_CONTEXT_10 = new MathContext(10, RoundingMode.HALF_EVEN);

    private static final MathContext MATH_CONTEXT_3 = new MathContext(3, RoundingMode.HALF_EVEN);
}
