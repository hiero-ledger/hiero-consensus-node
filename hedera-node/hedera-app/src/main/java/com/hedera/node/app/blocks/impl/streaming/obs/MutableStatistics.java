package com.hedera.node.app.blocks.impl.streaming.obs;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

public class MutableStatistics {
    private final ObsUnit unit;
    private final ConcurrentMap<Statistics, Void> statsMap = new ConcurrentHashMap<>();

    public MutableStatistics(final ObsUnit unit) {
        this.unit = unit;
    }

    public void add(final Statistics statistics) {
        if (unit != statistics.unit()) {
            throw new IllegalArgumentException("Cannot add statistics with different unit");
        }

        statsMap.put(statistics, null);
    }

    public Statistics statistics() {
        long count = 0;
        long total = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;



        for (final Statistics stats : statsMap.keySet()) {

        }
    }
}
