// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

public record FixedStatistics(ObsUnit unit, long samples, long sum, long min, long max, double avg, double stdDev)
        implements Statistics {
    public static FixedStatistics NIL = new FixedStatistics(null, 0, 0, 0, 0, 0.0, 0.0);
}
