// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class StatisticsProbe {

    private final String name;
    private final ObsUnit unit;
    private volatile boolean isClosed = false;
    private Statistics statistics = null;
    private Queue<Long> values = new ConcurrentLinkedQueue<>();

    public StatisticsProbe(@NonNull final String name, @NonNull final ObsUnit unit) {
        this.name = requireNonNull(name);
        this.unit = requireNonNull(unit);
    }

    public @NonNull String name() {
        return name;
    }

    public @NonNull ObsUnit unit() {
        return unit;
    }

    public void add(final long value) {
        if (isClosed) {
            throw new IllegalStateException("Probe is already aggregated; cannot add more values");
        }

        values.add(value);
    }

    public @Nullable Statistics statistics() {
        return statistics;
    }

    public @NonNull Statistics aggregate() {
        if (statistics != null) {
            return statistics;
        }

        isClosed = true;

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long total = 0;
        long count = 0;

        for (final long value : values) {
            if (min > value) {
                min = value;
            }
            if (max < value) {
                max = value;
            }
            ++count;
            total += value;
        }

        if (count == 0) {
            statistics = FixedStatistics.NIL;
            return statistics;
        }

        final double avg = total / (count * 1.0);
        double stdDev = 0.0D;

        for (final long value : values) {
            stdDev += Math.pow(value - avg, 2);
        }

        stdDev = Math.sqrt(stdDev / count);
        statistics = new FixedStatistics(unit, count, total, min, max, avg, stdDev);

        values = null; // clear values to reclaim memory

        return statistics;
    }

    @Override
    public String toString() {
        String s = name + " ";

        if (statistics == null) {
            s += "{ <In Progress> }";
        } else {
            s += Statistics.toString(statistics);
        }
        return s;
    }
}
