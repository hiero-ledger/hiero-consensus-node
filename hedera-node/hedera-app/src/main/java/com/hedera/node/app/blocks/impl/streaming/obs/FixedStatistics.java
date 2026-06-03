// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import java.math.BigDecimal;
import java.math.BigInteger;

public record FixedStatistics(ObsUnit unit, BigInteger numSamples, BigInteger sum, BigInteger min, BigInteger max, BigDecimal avg, BigDecimal stdDev)
        implements Statistics {
    public static FixedStatistics NIL = new FixedStatistics(null, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
}
