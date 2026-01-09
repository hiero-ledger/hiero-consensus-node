// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.NumberSupplier;
import org.hiero.metrics.internal.ObservableGaugeImpl;

/**
 * A metric of type {@link MetricType#GAUGE}, which doesn't hold measurements providing methods to update the values,
 * but instead holds observable value suppliers per unique combination of dynamic labels.
 * <p>
 * Value suppliers are provided per dynamic label values during metric construction using
 * {@link Builder#observeValue(NumberSupplier, String...)}, or could be added later after metric creation
 * using {@link #observeValue(NumberSupplier, String...)}.
 */
public interface ObservableGauge extends Metric {

    /**
     * Create a metric key for a {@link ObservableGauge} with the given name. <br>
     * Name must match {@value METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<ObservableGauge> key(@NonNull String name) {
        return MetricKey.of(name, ObservableGauge.class);
    }

    /**
     * Create a builder for a {@link ObservableGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<ObservableGauge> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link ObservableGauge} with the given metric name. <br>
     * Name must match {@value METRIC_NAME_REGEX}.
     *
     * @param name the metric name
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * Register an observable value with the given value supplier and dynamic label names and values.
     * <p>
     * Provided label names must match the dynamic labels specified during metric creation.
     * Static labels should not be provided here, as they are already associated with the metric.
     * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
     *
     * @param valueSupplier         the supplier to get the numerical value
     * @param labelNamesAndValues   alternating label names and values, e.g. "label1", "value1", "label2", "value2"
     * @return this metric
     * @throws IllegalStateException if metric has no dynamic labels specified during creation
     * @throws IllegalArgumentException if provided label names do not match {@link #dynamicLabelNames()}
     * or an observable value with the same label values already exists
     */
    @NonNull
    ObservableGauge observeValue(@NonNull NumberSupplier valueSupplier, @NonNull String... labelNamesAndValues);

    /**
     * Observable metrics cannot change values, so this is a no-op.
     */
    @Override
    default void reset() {
        // no op
    }

    /**
     * Builder for {@link ObservableGauge}.
     */
    final class Builder extends Metric.Builder<Builder, ObservableGauge> {

        private final List<String[]> labelNamesAndValues = new ArrayList<>();
        private final List<NumberSupplier> valuesSuppliers = new ArrayList<>();

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
         * (when {@link ObservableGauge#observeValue(NumberSupplier, String...)} is called),
         * due to builder usage pattern, when dynamic labels can be registered after observed values.
         *
         * @param valueSupplier the supplier to get the {@code long} value
         * @param labelNamesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
         * @return this builder
         */
        @NonNull
        public Builder observeValue(@NonNull LongSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
            return observeValue(new NumberSupplier(valueSupplier), labelNamesAndValues);
        }

        /**
         * Register an observable value with the given {@code double} value supplier and dynamic label names and values.
         * <p>
         * Provided label names must match the dynamic labels specified during metric creation.
         * Static labels should not be provided here, as they are already associated with the metric.
         * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
         * <p>
         * All requirements for labels above are validated during metric construction
         * (when {@link ObservableGauge#observeValue(NumberSupplier, String...)} is called),
         * due to builder usage pattern, when dynamic labels can be registered after observed values.
         *
         * @param valueSupplier the supplier to get the {@code double} value
         * @param labelNamesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
         * @return this builder
         */
        @NonNull
        public Builder observeValue(@NonNull DoubleSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
            return observeValue(new NumberSupplier(valueSupplier), labelNamesAndValues);
        }

        @NonNull
        private Builder observeValue(@NonNull NumberSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
            // labels will be validated during metric construction
            valuesSuppliers.add(valueSupplier);
            this.labelNamesAndValues.add(labelNamesAndValues);
            return this;
        }

        public int getObservedValuesSize() {
            return valuesSuppliers.size();
        }

        @NonNull
        public String[] getObservedValuesLabelNamesAndValues(int idx) {
            return labelNamesAndValues.get(idx);
        }

        @NonNull
        public NumberSupplier getValuesSupplier(int idx) {
            return valuesSuppliers.get(idx);
        }

        /**
         * Build the {@link ObservableGauge} instance.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        protected ObservableGauge buildMetric() {
            return new ObservableGaugeImpl(this);
        }
    }
}
