// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.api.export.snapshot.DataPointSnapshot;
import org.hiero.metrics.internal.datapoint.DataPointHolder;

/**
 * Base class for all stateful metric implementations requiring {@link StatefulMetric.Builder} for
 * construction.
 * <p>
 * Holds a map of data points for each combination of dynamic label values or a single data point if no dynamic
 * labels are defined. All data points are created lazily using the provided data point factory function only
 * when requested for observation.
 *
 * @param <I> The type of the initializer used to create new data points.
 * @param <D> The type of the data point associated with this metric.
 * @param <S> The type of the {@link DataPointSnapshot} associated with this metric.
 */
public abstract class AbstractStatefulMetric<I, D, S extends DataPointSnapshot> extends AbstractMetric<D, S>
        implements StatefulMetric<I, D> {

    private final I defaultInitializer;
    private final Function<I, D> dataPointFactory;

    private volatile D noLabelsDataPoint;
    private final Map<LabelValues, DataPointHolder<D, S>> dataPoints;

    protected AbstractStatefulMetric(StatefulMetric.Builder<I, D, ?, ?> builder) {
        super(builder);

        dataPointFactory = builder.getDataPointFactory();
        defaultInitializer = builder.getDefaultInitializer();

        if (dynamicLabelNames().isEmpty()) {
            dataPoints = null;
        } else {
            dataPoints = new ConcurrentHashMap<>();
        }
    }

    protected abstract void reset(D dataPoint);

    @Override
    public final void reset() {
        if (dynamicLabelNames().isEmpty()) {
            if (noLabelsDataPoint != null) {
                reset(noLabelsDataPoint);
            }
        } else {
            dataPoints.values().stream().map(DataPointHolder::dataPoint).forEach(this::reset);
        }
    }

    @NonNull
    @Override
    public final D getOrCreateNotLabeled() {
        if (!dynamicLabelNames().isEmpty()) {
            throw new IllegalStateException("This metric has dynamic labels, so you must call getOrCreateLabeled()");
        }
        // lazy init of no labels data point
        D localRef = noLabelsDataPoint;
        if (localRef == null) {
            synchronized (this) {
                localRef = noLabelsDataPoint;
                if (localRef == null) {
                    noLabelsDataPoint = localRef = dataPointFactory.apply(defaultInitializer);
                }
            }
        }
        return localRef;
    }

    @NonNull
    @Override
    public D getOrCreateLabeled(@NonNull String... namesAndValues) {
        if (dynamicLabelNames().isEmpty()) {
            throw new IllegalStateException("This metric has no dynamic labels, so you must call getNotLabeled()");
        }
        return dataPoints
                .computeIfAbsent(createLabelValues(namesAndValues), this::createAndTrackDataPointHolder)
                .dataPoint();
    }

    @NonNull
    @Override
    public D getOrCreateLabeled(@NonNull I initializer, @NonNull String... namesAndValues) {
        if (dynamicLabelNames().isEmpty()) {
            throw new IllegalStateException("This metric has no dynamic labels, so you must call getNotLabeled()");
        }
        Objects.requireNonNull(initializer);
        return dataPoints
                .computeIfAbsent(
                        createLabelValues(namesAndValues),
                        labelValues -> createAndTrackDataPointHolder(labelValues, initializer))
                .dataPoint();
    }

    private DataPointHolder<D, S> createAndTrackDataPointHolder(LabelValues labelValues) {
        return createAndTrackDataPointHolder(labelValues, defaultInitializer);
    }

    private DataPointHolder<D, S> createAndTrackDataPointHolder(LabelValues labelValues, @NonNull I initializer) {
        return createAndTrackDataPointHolder(dataPointFactory.apply(initializer), labelValues);
    }
}
