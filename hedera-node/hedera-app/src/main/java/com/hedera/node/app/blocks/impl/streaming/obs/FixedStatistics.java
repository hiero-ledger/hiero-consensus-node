// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Immutable {@link Statistics} snapshot produced after a probe is aggregated.
 * {@link #NIL} is the sentinel value representing zero samples; it is returned by probes and
 * composites when no data was collected.
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
    /** Sentinel for an empty result (no samples). All numeric fields are zero, unit is {@code null}. */
    public static FixedStatistics NIL = new FixedStatistics(
            null, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
}
