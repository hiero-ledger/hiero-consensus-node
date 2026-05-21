package com.hedera.node.app.blocks.impl.streaming.obs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class StatisticsJoiner {
    private final String name;
    private final ObsUnit unit;
    private final Queue<Statistics> statsQueue = new ConcurrentLinkedQueue<>();

    public StatisticsJoiner(final String name, final ObsUnit unit) {
        this.name = name;
        this.unit = unit;
    }

    public void add(final Statistics statistics) {
        if (unit != statistics.unit()) {
            throw new IllegalArgumentException("Cannot add statistics with different unit");
        }

        statsQueue.add(statistics);
    }

    public Statistics statistics() {
        long count = 0;
        long total = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;

        for (final Statistics stats : statsQueue) {
            count += stats.count();
            total += stats.total();
            if (min > stats.min()) {
                min = stats.min();
            }
            if (max < stats.max()) {
                max = stats.max();
            }
        }

        if (count == 0) {
            return Statistics.NIL;
        }

        final double avg = total / (count * 1.0D);
        double stdDev = 0.0D;

        for (final Statistics stats : statsQueue) {
            final double d1 = stats.count() * Math.pow(stats.stdDev(), 2);
            final double d2 = stats.count() * Math.pow(stats.avg() - avg, 2);
            stdDev += d1 + d2;
        }

        stdDev = stdDev / count;
        stdDev = Math.sqrt(stdDev);

        return new Statistics(unit, count, total, min, max, avg, stdDev);
    }

    @Override
    public String toString() {
        String s = name;

        s += " { (Unit:" + unit;

        final Statistics statistics = statistics();

        s += "|Samples:" + statistics.count();
        s += "|Sum:" + statistics.total();
        s += "|Min:" + statistics.min();
        s += "|Max:" + statistics.max();
        s += "|Avg:" + round(statistics.avg()).toPlainString();
        s += "|StdDev:" + round(statistics.stdDev()).toPlainString();

        s += ") }";
        return s;
    }

    private static BigDecimal round(final double d) {
        return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_EVEN);
    }
}
