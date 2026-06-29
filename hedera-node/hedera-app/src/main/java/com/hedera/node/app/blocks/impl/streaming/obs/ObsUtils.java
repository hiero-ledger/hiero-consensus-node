// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Shared constants and utilities for the observability (obs) package.
 */
public final class ObsUtils {
    private ObsUtils() {}

    /** 10-significant-digit precision used across all statistics computations. */
    static final MathContext MATH_CONTEXT_10 = new MathContext(10, RoundingMode.HALF_EVEN);

    /**
     * Formats a decimal for the report output: 4 decimal places, HALF_EVEN, no scientific notation.
     *
     * @param value the value to format
     * @return the formatted plain-string representation
     */
    static String format(final BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_EVEN).toPlainString();
    }
}
