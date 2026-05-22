// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class BasicProbe {

    private final String name;
    private final ObsUnit unit;
    private volatile boolean isClosed = false;
    private Statistics statistics = null;

    private final LongAdder sum = new LongAdder();
    private final LongAdder samples = new LongAdder();
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

    public BasicProbe(@NonNull final String name, @NonNull final ObsUnit unit) {
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

        sum.add(value);
        samples.increment();
        min.updateAndGet(old -> Math.min(old, value));
        max.updateAndGet(old -> Math.max(old, value));
    }

    public @Nullable Statistics statistics() {
        return statistics;
    }

    public @NonNull Statistics aggregate() {
        if (statistics != null) {
            return statistics;
        }

        isClosed = true;

        final double avg = sum.sum() / (samples.sum() * 1.0D);

        statistics = new FixedStatistics(unit, samples.sum(), sum.sum(), min.get(), max.get(), avg, -1.0);
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
