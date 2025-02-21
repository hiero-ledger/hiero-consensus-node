// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.stats;

import com.swirlds.common.metrics.StatEntry;
import com.swirlds.metrics.api.Metrics;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A metrics object to track an average and maximum time period, without history. This class uses an
 * {@link AtomicAverage} so it is both thread safe and performant.
 */
public class AverageAndMaxTimeStat {
    private static final String FORMAT_DEFAULT = "%,10.3f";
    private static final String FORMAT_SECONDS = "%,5.4f";
    private static final String FORMAT_MILLIS = "%,10.1f";

    private final ChronoUnit unit;
    private final AtomicAverage average;
    private final StatEntry avgEntry;
    private final AtomicMax max;
    private final StatEntry maxEntry;

    public AverageAndMaxTimeStat(
            final Metrics metrics, final ChronoUnit unit, final String category, final String name, final String desc) {
        this(metrics, unit, category, name, desc, AverageStat.WEIGHT_SMOOTH);
    }

    public AverageAndMaxTimeStat(
            final Metrics metrics,
            final ChronoUnit unit,
            final String category,
            final String name,
            final String desc,
            final double weight) {
        this.unit = unit;
        average = new AtomicAverage(weight);
        max = new AtomicMax();

        final String format;
        switch (unit) {
            case MILLIS:
                format = FORMAT_MILLIS;
                break;
            case SECONDS:
                format = FORMAT_SECONDS;
                break;
            default:
                format = FORMAT_DEFAULT;
        }
        avgEntry = metrics.getOrCreate(new StatEntry.Config<>(category, name, Double.class, this::getAvg)
                .withDescription(desc)
                .withFormat(format)
                .withReset(this::resetAvg));
        maxEntry = metrics.getOrCreate(new StatEntry.Config<>(category, name + "MAX", Double.class, this::getMax)
                .withDescription("max value of " + name)
                .withFormat(format)
                .withReset(this::resetMax)
                .withResetStatsStringSupplier(this::getAndResetMax));
    }

    private double convert(final long nanos) {
        return convert((double) nanos);
    }

    private double convert(final double nanos) {
        return nanos / unit.getDuration().toNanos();
    }

    private double getAvg() {
        return convert(average.get());
    }

    private void resetAvg(final double unused) {
        average.reset();
    }

    private double getMax() {
        return convert(max.get());
    }

    private void resetMax(final double unused) {
        max.reset();
    }

    private double getAndResetMax() {
        return convert(max.getAndReset());
    }

    public void update(final long startTime) {
        update(startTime, System.nanoTime());
    }

    public void update(final long start, final long end) {
        // the value is stored as nanos and converted upon retrieval
        final long nanos = end - start;
        average.update(nanos);
        max.update(nanos);
    }

    public StatEntry getAverageStat() {
        return avgEntry;
    }

    public StatEntry getMaxStat() {
        return maxEntry;
    }

    public List<StatEntry> getAllEntries() {
        return List.of(avgEntry, maxEntry);
    }
}
