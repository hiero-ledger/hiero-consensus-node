// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Immutable {@link Statistics} snapshot produced after a probe is aggregated.
 * {@link #nil(ObsUnit)} produces the empty result returned by probes and composites when no data
 * was collected.
 */
public record FixedStatistics(
        ObsUnit unit,
        BigInteger numSamples,
        BigInteger sum,
        BigInteger min,
        BigInteger max,
        BigDecimal avg,
        BigDecimal stdDev)
        implements Statistics {
    /** An empty result (no samples) labelled with the given unit. All numeric fields are zero. */
    public static FixedStatistics nil(final ObsUnit unit) {
        return new FixedStatistics(
                unit,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }
}
