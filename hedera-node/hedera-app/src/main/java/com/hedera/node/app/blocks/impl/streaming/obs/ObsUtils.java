// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Shared constants and utilities for the obs package.
 */
public final class ObsUtils {
    private ObsUtils() {}

    /** 10-significant-digit precision used across all statistics computations. */
    static MathContext MATH_CONTEXT_10 = new MathContext(10, RoundingMode.HALF_EVEN);
}
