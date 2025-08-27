// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.common.metrics.DurationGauge;
import com.swirlds.metrics.api.snapshot.Snapshot;
import com.swirlds.metrics.impl.AbstractMetric;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Platform-implementation of {@link DurationGauge}
 */
public class PlatformDurationGauge extends AbstractMetric implements DurationGauge {
    private final AtomicLong nanos;
    private final ChronoUnit unit;

    /**
     * Constructs a new PlatformDurationGauge with the given configuration.
     * @param config the configuration for this duration gauge
     */
    public PlatformDurationGauge(@NonNull final DurationGauge.Config config) {
        super(config);
        this.unit = config.getTimeUnit();
        this.nanos = new AtomicLong();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<Snapshot.SnapshotEntry> takeSnapshot() {
        return List.of(new Snapshot.SnapshotEntry(VALUE, get()));
    }

    private double getAsDouble() {
        return nanos.get();
    }

    @Override
    public long getNanos() {
        return nanos.get();
    }

    @Override
    public void set(final Duration duration) {
        if (duration == null) {
            return;
        }
        nanos.set(duration.toNanos());
    }

    @Override
    public double get() {
        return getAsDouble() / unit.getDuration().toNanos();
    }
}
