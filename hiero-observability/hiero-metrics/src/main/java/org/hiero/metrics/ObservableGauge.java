// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.hiero.metrics.core.DoubleMeasurementSnapshot;
import org.hiero.metrics.core.LabelValues;
import org.hiero.metrics.core.LongMeasurementSnapshot;
import org.hiero.metrics.core.MeasurementSnapshot;
import org.hiero.metrics.core.Measurements;
import org.hiero.metrics.core.Metric;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricType;

/**
 * A metric of type {@link MetricType#GAUGE}, which doesn't hold measurements providing methods to update the values,
 * but instead holds observable value suppliers per unique combination of dynamic labels.
 * <p>
 * Value suppliers are provided per dynamic label values during metric construction using
 * {@link Builder#observe(LongSupplier, String...)}, {@link Builder#observe(DoubleSupplier, String...)}
 * or could be added later after metric creation using {@link #observe(LongSupplier, String...)}
 * or {@link #observe(DoubleSupplier, String...)}.
 * <p>
 * Observed value suppliers with dynamic labels can be removed using {@link #removeIf(Predicate)},
 * and their label values can be observed again afterwards. An export already in progress may call a removed supplier
 * once more, so a supplier must stay safe to call after its removal, for example it must not throw when its source
 * is closed, because a throwing supplier fails the whole export.
 */
public final class ObservableGauge extends Metric<Object> {

    private ObservableGauge(ObservableGauge.Builder builder) {
        super(builder);

        final int size = builder.valuesSuppliers.size();
        for (int i = 0; i < size; i++) {
            observeInternal(builder.valuesSuppliers.get(i), builder.labelNamesAndValues.get(i));
        }
    }

    /**
     * Create a metric key for a {@link ObservableGauge} with the given name. <br>
     * Name must match {@value org.hiero.metrics.core.MetricUtils#NAME_UNIT_LABEL_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    public static MetricKey<ObservableGauge> key(@NonNull String name) {
        return MetricKey.of(name, ObservableGauge.class);
    }

    /**
     * Create a builder for a {@link ObservableGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    public static Builder builder(@NonNull MetricKey<ObservableGauge> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link ObservableGauge} with the given metric name. <br>
     * Name must match {@value org.hiero.metrics.core.MetricUtils#NAME_UNIT_LABEL_REGEX}.
     *
     * @param name the metric name
     * @return the builder
     */
    @NonNull
    public static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    @NonNull
    public ObservableGauge observe(@NonNull LongSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
        observeInternal(valueSupplier, labelNamesAndValues);
        return this;
    }

    @NonNull
    public ObservableGauge observe(@NonNull DoubleSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
        observeInternal(valueSupplier, labelNamesAndValues);
        return this;
    }

    private void observeInternal(@NonNull Object valueSupplier, @NonNull String... labelNamesAndValues) {
        Objects.requireNonNull(valueSupplier, "value supplier must not be null");

        final LabelValues labelValues = createLabelValues(labelNamesAndValues);
        final Measurements<Object> observed = measurements();
        // putIfAbsent creates the snapshot, which calls the supplier, so duplicates are checked before and after it
        if (observed.get(labelValues) != null || !observed.putIfAbsent(labelValues, valueSupplier)) {
            throw duplicateLabelValues(labelValues);
        }
    }

    private static IllegalArgumentException duplicateLabelValues(LabelValues labelValues) {
        return new IllegalArgumentException("A measurement with the same label values already exists: " + labelValues);
    }

    @Override
    protected void reset() {
        // No-op for observable gauge
    }

    @NonNull
    @Override
    protected MeasurementSnapshot createMeasurementSnapshot(
            @NonNull Object valuesSupplier, @NonNull LabelValues dynamicLabelValues) {
        if (valuesSupplier instanceof LongSupplier castedSupplier) {
            return new LongMeasurementSnapshot(dynamicLabelValues, castedSupplier);
        } else if (valuesSupplier instanceof DoubleSupplier castedSupplier) {
            return new DoubleMeasurementSnapshot(dynamicLabelValues, castedSupplier);
        } else {
            throw new IllegalArgumentException("Unsupported value supplier type: "
                    + valuesSupplier.getClass().getName());
        }
    }

    /**
     * A builder for {@link ObservableGauge}.
     */
    public static final class Builder extends Metric.Builder<Builder, ObservableGauge> {

        private final List<String[]> labelNamesAndValues = new ArrayList<>();
        private final List<Object> valuesSuppliers = new ArrayList<>();

        private Builder(MetricKey<ObservableGauge> key) {
            super(MetricType.GAUGE, key);
        }

        /**
         * Register an observable value with the given {@code long} value supplier and dynamic label names and values.
         * <p>
         * Provided label names must match the dynamic labels specified during metric creation.
         * Static labels should not be provided here, as they are already associated with the metric.
         * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
         * <p>
         * All requirements for labels above are validated during metric construction
         * (when {@link ObservableGauge#observe(LongSupplier, String...)} is called),
         * due to builder usage pattern, when dynamic labels can be registered after observed values.
         *
         * @param valueSupplier the supplier to get the {@code long} value
         * @param labelNamesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
         * @return this builder
         */
        @NonNull
        public Builder observe(@NonNull LongSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
            observeInternal(valueSupplier, labelNamesAndValues);
            return this;
        }

        /**
         * Register an observable value with the given {@code double} value supplier and dynamic label names and values.
         * <p>
         * Provided label names must match the dynamic labels specified during metric creation.
         * Static labels should not be provided here, as they are already associated with the metric.
         * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
         * <p>
         * All requirements for labels above are validated during metric construction
         * (when {@link ObservableGauge#observe(DoubleSupplier, String...)} is called),
         * due to builder usage pattern, when dynamic labels can be registered after observed values.
         *
         * @param valueSupplier the supplier to get the {@code double} value
         * @param labelNamesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
         * @return this builder
         */
        @NonNull
        public Builder observe(@NonNull DoubleSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
            observeInternal(valueSupplier, labelNamesAndValues);
            return this;
        }

        private void observeInternal(@NonNull Object valueSupplier, @NonNull String... labelNamesAndValues) {
            Objects.requireNonNull(valueSupplier, "value supplier must not be null");
            Objects.requireNonNull(labelNamesAndValues, "Label names and values must not be null");

            // dynamic labels names validation will happen during metric construction due to builder usage pattern
            valuesSuppliers.add(valueSupplier);
            this.labelNamesAndValues.add(labelNamesAndValues);
        }

        /**
         * Build the {@link ObservableGauge} instance.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        protected ObservableGauge buildMetric() {
            return new ObservableGauge(this);
        }
    }
}
