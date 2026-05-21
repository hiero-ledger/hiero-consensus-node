package com.hedera.node.app.blocks.impl.streaming.obs;

public record Statistics(ObsUnit unit, long count, long total, long min, long max, double avg, double stdDev) {
    public static Statistics NIL = new Statistics(null, 0, 0, 0, 0, 0.0, 0.0);
}
