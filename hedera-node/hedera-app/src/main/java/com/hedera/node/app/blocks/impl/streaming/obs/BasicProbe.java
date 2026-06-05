// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static com.hedera.node.app.blocks.impl.streaming.obs.ObsUtils.MATH_CONTEXT_10;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;

public class BasicProbe {

    private final String name;
    private final ObsUnit unit;
    private final AtomicReference<DataHolder> dataRef = new AtomicReference<>(new DataHolder(BigInteger.ZERO, BigInteger.ZERO, Long.MAX_VALUE, Long.MIN_VALUE));
    private final AtomicReference<Statistics> statsRef = new AtomicReference<>();

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
        if (statsRef.get() != null) {
            throw new IllegalStateException("Probe is already aggregated; cannot add more values");
        }

        while (true) {
            final DataHolder old = dataRef.get();
            final DataHolder updated = new DataHolder(
                    old.sum.add(BigInteger.valueOf(value)), // sum
                    old.numSamples.add(BigInteger.ONE), // numSamples
                    Math.min(old.min, value), // min
                    Math.max(old.max, value)); // max

            if (dataRef.compareAndSet(old, updated)) {
                return;
            }
        }
    }

    public @Nullable Statistics statistics() {
        return statsRef.get();
    }

    public @NonNull Statistics aggregate() {
        Statistics stats = statsRef.get();
        if (stats != null) {
            return stats;
        }

        final DataHolder data = dataRef.get();
        final BigDecimal avg = new BigDecimal(data.sum).divide(new BigDecimal(data.numSamples), MATH_CONTEXT_10);

        stats = new FixedStatistics(unit, data.numSamples, data.sum, BigInteger.valueOf(data.min), BigInteger.valueOf(data.max), avg, BigDecimal.ZERO);
        statsRef.set(stats);
        return stats;
    }

    @Override
    public String toString() {
        final Statistics stats = statsRef.get();
        String s = name + " ";

        if (stats == null) {
            s += "{ <In Progress> }";
        } else {
            s += Statistics.toString(stats);
        }
        return s;
    }

    private record DataHolder(BigInteger sum, BigInteger numSamples, long min, long max) { }
}
