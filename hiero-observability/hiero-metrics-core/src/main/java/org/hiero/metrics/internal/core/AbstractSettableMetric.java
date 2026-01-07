// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.hiero.metrics.api.core.SettableMetric;

/**
 * Abstract implementation of {@link SettableMetric} requiring {@link SettableMetric.Builder} for
 * construction.
 * <p>
 * Holds a map of measurements for each combination of dynamic label values or a single measurement if no dynamic
 * labels are defined. All measurements are created lazily using the provided measurement factory function only
 * when requested for observation.
 *
 * @param <I> The type of the initializer used to create new measurements.
 * @param <M_API> The type of the measurement API associated with this metric.
 * @param <M_IMPL> The type of the measurement implementation associated with this metric (extends {@link M_API}).
 */
public abstract class AbstractSettableMetric<I, M_API, M_IMPL extends M_API> extends AbstractMetric<M_IMPL>
        implements SettableMetric<I, M_API> {

    private final I defaultInitializer;
    private final Function<I, M_IMPL> measurementFactory;

    private volatile M_IMPL noLabelsMeasurement;
    private final Map<LabelValues, M_IMPL> measurements;

    protected AbstractSettableMetric(SettableMetric.Builder<I, ?, ?> builder, Function<I, M_IMPL> measurementFactory) {
        super(builder);

        this.measurementFactory = measurementFactory;
        defaultInitializer = builder.getDefaultInitializer();

        if (dynamicLabelNames().isEmpty()) {
            measurements = null;
        } else {
            measurements = new ConcurrentHashMap<>();
        }
    }

    protected abstract void reset(M_IMPL measurement);

    @Override
    public final void reset() {
        if (dynamicLabelNames().isEmpty()) {
            if (noLabelsMeasurement != null) {
                reset(noLabelsMeasurement);
            }
        } else {
            measurements.values().forEach(this::reset);
        }
    }

    @NonNull
    @Override
    public final M_API getOrCreateNotLabeled() {
        if (measurements != null) {
            throw new IllegalStateException("This metric has dynamic labels, so you must call getOrCreateLabeled()");
        }
        // lazy init of no labels measurement
        M_IMPL localRef = noLabelsMeasurement;
        if (localRef == null) {
            synchronized (this) {
                localRef = noLabelsMeasurement;
                if (localRef == null) {
                    noLabelsMeasurement = localRef = createMeasurementAndSnapshot(LabelValues.EMPTY);
                }
            }
        }
        return localRef;
    }

    @NonNull
    @Override
    public M_API getOrCreateLabeled(@NonNull String... namesAndValues) {
        final LabelValues labelValues = createLabelValues(namesAndValues);
        if (labelValues.size() == 0) {
            return getOrCreateNotLabeled();
        } else {
            return measurements.computeIfAbsent(labelValues, this::createMeasurementAndSnapshot);
        }
    }

    @NonNull
    @Override
    public M_API getOrCreateLabeled(@NonNull I initializer, @NonNull String... namesAndValues) {
        if (measurements == null) {
            throw new IllegalStateException(
                    "This metric has no dynamic labels, so you must call getOrCreateNotLabeled()");
        }
        Objects.requireNonNull(initializer);
        return measurements.computeIfAbsent(
                createLabelValues(namesAndValues),
                labelValues -> createMeasurementAndSnapshot(labelValues, initializer));
    }

    private M_IMPL createMeasurementAndSnapshot(LabelValues labelValues) {
        return createMeasurementAndSnapshot(labelValues, defaultInitializer);
    }

    private M_IMPL createMeasurementAndSnapshot(LabelValues labelValues, @NonNull I initializer) {
        M_IMPL measurement = measurementFactory.apply(initializer);
        addMeasurementSnapshot(measurement, labelValues);
        return measurement;
    }
}
