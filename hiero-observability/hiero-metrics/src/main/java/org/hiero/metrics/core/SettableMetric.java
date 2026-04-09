// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract extension of {@link Metric}, that holds a set of measurements for each combination of dynamic label values
 * or a single measurement if no dynamic labels are defined.
 * <p>
 * Subclasses must implement the creation of measurements based on initializer
 * and measurement snapshots based on measurement with it's associated label values.
 * All measurements are created lazily, whenever new combination of dynamic label values are requested.
 * <p>
 * Clients should pay attention the dynamic label values cardinality, as high cardinality can lead to
 * higher costs for metrics backends. <b>Do not use</b> labels with values having unbounded cardinality,
 * such as IDs or timestamps.
 *
 * @param <I> The type of the measurement initializer.
 * @param <M> The type of the measurement associated with this metric.
 */
public abstract class SettableMetric<I, M> extends Metric {

    private final I defaultInitializer;

    private volatile M noLabelsMeasurement;
    private final Map<LabelValues, M> measurements;

    protected SettableMetric(SettableMetric.Builder<I, ?, ?> builder) {
        super(builder);

        defaultInitializer = builder.defaultInitializer;

        if (dynamicLabelNames().isEmpty()) {
            measurements = null;
        } else {
            measurements = new ConcurrentHashMap<>();
        }
    }

    /**
     * Get or create the measurement with no labels.
     *
     * @return the measurement with no labels
     * @throws IllegalStateException if metric has dynamic labels specified during creation
     */
    @NonNull
    public final M getOrCreateNotLabeled() {
        if (measurements != null) {
            throw new IllegalStateException("This metric has dynamic labels, so you must call getOrCreateLabeled()");
        }
        // lazy init of no labels measurement
        M localRef = noLabelsMeasurement;
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

    /**
     * Get or create the measurement with the specified labels, using the default initializer.
     * <p>
     * Provided label names must match the dynamic labels specified during metric creation.
     * Static labels should not be provided here, as they are already associated with the metric.
     * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
     *
     * @param namesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
     * @return the measurement with the specified labels
     * @throws IllegalStateException if metric has no dynamic labels specified during creation
     * @throws IllegalArgumentException if provided label names do not match {@link #dynamicLabelNames()}
     */
    @NonNull
    public final M getOrCreateLabeled(@NonNull String... namesAndValues) {
        final LabelValues labelValues = createLabelValues(namesAndValues);
        if (labelValues.size() == 0) {
            return getOrCreateNotLabeled();
        } else {
            return measurements.computeIfAbsent(labelValues, this::createMeasurementAndSnapshot);
        }
    }

    /**
     * Get or create the measurement with the specified labels, using a custom initializer.
     * <p>
     * Provided label names must match the dynamic labels specified during metric creation.
     * Static labels should not be provided here, as they are already associated with the metric.
     * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
     *
     * @param initializer the initializer to create new measurements, must not be {@code null}
     * @param namesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
     * @return the measurement with the specified labels
     * @throws IllegalStateException if metric has no dynamic labels specified during creation
     * @throws IllegalArgumentException if provided label names do not match {@link #dynamicLabelNames()}
     */
    @NonNull
    public final M getOrCreateLabeled(@NonNull I initializer, @NonNull String... namesAndValues) {
        if (measurements == null) {
            throw new IllegalStateException(
                    "This metric has no dynamic labels, so you must call getOrCreateNotLabeled()");
        }
        Objects.requireNonNull(initializer, "custom measurement initializer must not be null");
        return measurements.computeIfAbsent(
                createLabelValues(namesAndValues),
                labelValues -> createMeasurementAndSnapshot(labelValues, initializer));
    }

    /**
     * Create a new measurement using the provided initializer.
     *
     * @param initializer object to use for measurement creation
     * @return the created measurement
     */
    protected abstract M createMeasurement(@NonNull I initializer);

    /**
     * Create a measurement snapshot for the given measurement and label values.
     *
     * @param measurement the measurement to create snapshot for
     * @param labelValues the label values associated with the measurement
     * @return the created measurement snapshot
     */
    protected abstract MeasurementSnapshot createMeasurementSnapshot(
            @NonNull M measurement, @NonNull LabelValues labelValues);

    /**
     * Reset the given measurement to its initial state.
     *
     * @param measurement the measurement to reset
     */
    protected abstract void reset(M measurement);

    @Override
    protected final void reset() {
        if (dynamicLabelNames().isEmpty()) {
            if (noLabelsMeasurement != null) {
                reset(noLabelsMeasurement);
            }
        } else {
            measurements.values().forEach(this::reset);
        }
    }

    private M createMeasurementAndSnapshot(LabelValues labelValues) {
        return createMeasurementAndSnapshot(labelValues, defaultInitializer);
    }

    private M createMeasurementAndSnapshot(LabelValues labelValues, @NonNull I initializer) {
        final M measurement = createMeasurement(initializer);
        addMeasurementSnapshot(createMeasurementSnapshot(measurement, labelValues));
        return measurement;
    }

    /**
     * Base abstract builder for {@link SettableMetric}.
     *
     * @param <I> the type of the initializer used to create new measurements per label set
     * @param <B> the type of the builder to return for method chaining
     * @param <M> the type of the metric to build
     */
    public abstract static class Builder<I, B extends SettableMetric.Builder<I, B, M>, M extends SettableMetric<I, ?>>
            extends Metric.Builder<B, M> {

        private I defaultInitializer;

        /**
         * Constructor for a settable metric builder.
         *
         * @param type               the metric type, must not be {@code null}
         * @param key                the metric key, must not be {@code null}
         * @param defaultInitializer the default initializer to use to create new measurements, must not be {@code null}
         */
        protected Builder(@NonNull MetricType type, @NonNull MetricKey<M> key, @NonNull I defaultInitializer) {
            super(type, key);
            setDefaultInitializer(defaultInitializer);
        }

        /**
         * Set the default initializer to use to create new measurements.
         *
         * @param defaultInitializer the default initializer, must not be {@code null}
         * @return this builder
         */
        @NonNull
        public final B setDefaultInitializer(@NonNull I defaultInitializer) {
            this.defaultInitializer =
                    Objects.requireNonNull(defaultInitializer, "default initializer must not be null");
            return self();
        }
    }
}
