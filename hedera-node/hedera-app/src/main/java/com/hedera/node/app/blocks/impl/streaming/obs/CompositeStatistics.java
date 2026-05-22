// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class CompositeStatistics implements Statistics {

    private final ObsUnit unit;
    private final AtomicReference<Statistics> compositeStatsRef = new AtomicReference<>(FixedStatistics.NIL);
    private final List<Statistics> componentStatistics = new LinkedList<>();

    public CompositeStatistics(@NonNull final ObsUnit unit) {
        this.unit = requireNonNull(unit);
    }

    public synchronized void add(@NonNull final Statistics stats) {
        requireNonNull(stats);
        componentStatistics.add(stats);
        calculateCompositeStatistics();
    }

    private void calculateCompositeStatistics() {
        long samples = 0;
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;

        for (final Statistics stats : componentStatistics) {
            samples += stats.samples();
            sum += stats.sum();
            if (min > stats.min()) {
                min = stats.min();
            }
            if (max < stats.max()) {
                max = stats.max();
            }
        }

        if (samples == 0) {
            compositeStatsRef.set(FixedStatistics.NIL);
        }

        final double compositeAvg = sum / (samples * 1.0D);
        double compositeStdDev = 0.0D;

        // Calculate the combined standard deviation
        // Note: this is a population-based standard deviation, NOT a sample-based one
        for (final Statistics stats : componentStatistics) {
            final double d1 = stats.samples() * Math.pow(stats.stdDev(), 2);
            final double d2 = stats.samples() * Math.pow(stats.avg() - compositeAvg, 2);

            compositeStdDev += (d1 + d2);
        }

        compositeStdDev = compositeStdDev / samples;
        compositeStdDev = Math.sqrt(compositeStdDev);

        compositeStatsRef.set(new FixedStatistics(unit, samples, sum, min, max, compositeAvg, compositeStdDev));
    }

    @Override
    public ObsUnit unit() {
        return unit;
    }

    @Override
    public long samples() {
        return compositeStatsRef.get().samples();
    }

    @Override
    public long sum() {
        return compositeStatsRef.get().sum();
    }

    @Override
    public long min() {
        return compositeStatsRef.get().min();
    }

    @Override
    public long max() {
        return compositeStatsRef.get().max();
    }

    @Override
    public double avg() {
        return compositeStatsRef.get().avg();
    }

    @Override
    public double stdDev() {
        return compositeStatsRef.get().stdDev();
    }
}
