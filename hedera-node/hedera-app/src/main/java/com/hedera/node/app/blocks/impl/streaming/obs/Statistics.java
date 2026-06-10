// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Read-only view of aggregated statistics for a single measurement probe.
 * All implementations must be immutable once returned to callers.
 */
public interface Statistics {

    ObsUnit unit();

    BigInteger numSamples();

    BigInteger sum();

    BigInteger min();

    BigInteger max();

    BigDecimal avg();

    /**
     * Population standard deviation of the recorded values.
     * {@code BasicProbe} always returns {@link BigDecimal#ZERO} here; use {@code StatisticsProbe}
     * when accurate stdDev is required.
     */
    BigDecimal stdDev();

    static String toString(final Statistics stats) {
        String s = "{ (Unit:" + stats.unit();

        s += "|Samples:" + stats.numSamples();
        s += "|Sum:" + stats.sum();
        s += "|Min:" + stats.min();
        s += "|Max:" + stats.max();
        s += "|Avg:" + stats.avg().setScale(4, RoundingMode.HALF_EVEN).toPlainString();
        s += "|StdDev:" + stats.stdDev().setScale(4, RoundingMode.HALF_EVEN).toPlainString();

        s += ") }";
        return s;
    }
}
