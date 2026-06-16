// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Read-only view of aggregated statistics for a single measurement probe.
 * All implementations must be immutable once returned to callers.
 */
public interface Statistics {

    /**
     * @return the unit of the recorded values
     */
    ObsUnit unit();

    /**
     * @return the number of recorded samples
     */
    BigInteger numSamples();

    /**
     * @return the sum of all recorded values
     */
    BigInteger sum();

    /**
     * @return the smallest recorded value
     */
    BigInteger min();

    /**
     * @return the largest recorded value
     */
    BigInteger max();

    /**
     * @return the mean of the recorded values
     */
    BigDecimal avg();

    /**
     * Population standard deviation of the recorded values.
     * {@code BasicProbe} always returns {@link BigDecimal#ZERO} here; use {@code StatisticsProbe}
     * when accurate stdDev is required.
     *
     * @return the population standard deviation of the recorded values
     */
    BigDecimal stdDev();

    /**
     * Renders a statistics snapshot as the single-line form used in the log report.
     *
     * @param stats the statistics to render
     * @return the formatted {@code { (Unit:…|Samples:…|…) }} string
     */
    static String toString(final Statistics stats) {
        String s = "{ (Unit:" + stats.unit();

        s += "|Samples:" + stats.numSamples();
        s += "|Sum:" + stats.sum();
        s += "|Min:" + stats.min();
        s += "|Max:" + stats.max();
        s += "|Avg:" + ObsUtils.format(stats.avg());
        s += "|StdDev:" + ObsUtils.format(stats.stdDev());

        s += ") }";
        return s;
    }
}
