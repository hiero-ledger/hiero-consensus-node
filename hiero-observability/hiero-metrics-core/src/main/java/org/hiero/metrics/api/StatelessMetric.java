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
import org.hiero.metrics.internal.StatelessMetricImpl;

/**
 * A stateless metric of type {@link MetricType#GAUGE} that doesn't hold any state
 * and gets/exports its value using provided suppliers.
 * <p>
 * Value suppliers are provided per data point during metric construction using
 * {@link Builder#registerDataPoint(NumberSupplier, String...)}, or could be added later
 * using {@link #registerDataPoint(NumberSupplier, String...)}.
 */
public interface StatelessMetric extends Metric {

    /**
     * Create a metric key for a {@link StatelessMetric} with the given name. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<StatelessMetric> key(@NonNull String name) {
        return MetricKey.of(name, StatelessMetric.class);
    }

    /**
     * Create a builder for a {@link StatelessMetric} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<StatelessMetric> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link StatelessMetric} with the given metric name. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the metric name
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * Register a data point with the given value supplier and labels.
     * <p>
     * Provided label names must match the dynamic labels specified during metric creation.
     * Constant labels should not be provided here, as they are already associated with the metric.
     * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
     *
     * @param valueSupplier         the supplier to get the numerical value of the data point
     * @param labelNamesAndValues   alternating label names and values, e.g. "label1", "value1", "label2", "value2"
     * @return this metric
     * @throws IllegalStateException if metric has no dynamic labels specified during creation
     * @throws IllegalArgumentException if provided label names do not match {@link #dynamicLabelNames()}
     * or a datapoint with same label values already exists
     */
    @NonNull
    StatelessMetric registerDataPoint(@NonNull NumberSupplier valueSupplier, @NonNull String... labelNamesAndValues);

    /**
     * Stateless metrics do not hold any state, so this is a no-op.
     */
    @Override
    default void reset() {
        // no op
    }

    /**
     * Builder for {@link StatelessMetric}.
     */
    final class Builder extends Metric.Builder<Builder, StatelessMetric> {

        private final List<String[]> labelNamesAndValues = new ArrayList<>();
        private final List<NumberSupplier> valuesSuppliers = new ArrayList<>();

        private Builder(MetricKey<StatelessMetric> key) {
            super(MetricType.GAUGE, key);
        }

        /**
         * Register a data point with the given value supplier and labels.
         * <p>
         * Provided label names must match the dynamic labels specified during metric creation.
         * Constant labels should not be provided here, as they are already associated with the metric.
         * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
         * <p>
         * All requirements for labels above are validated during metric construction
         * (when {@link StatelessMetric#registerDataPoint(NumberSupplier, String...)} is called),
         * due to builder usage pattern, when dynamic labels can be registered after data points.
         *
         * @param valueSupplier the supplier to get the {@code long} value of the data point
         * @param labelNamesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
         * @return this builder
         */
        @NonNull
        public Builder registerDataPoint(@NonNull LongSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
            return registerDataPoint(new NumberSupplier(valueSupplier), labelNamesAndValues);
        }

        /**
         * Register a data point with the given value supplier and labels.
         * <p>
         * Provided label names must match the dynamic labels specified during metric creation.
         * Constant labels should not be provided here, as they are already associated with the metric.
         * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
         * <p>
         * All requirements for labels above are validated during metric construction
         * (when {@link StatelessMetric#registerDataPoint(NumberSupplier, String...)} is called),
         * due to builder usage pattern, when dynamic labels can be registered after data points.
         *
         * @param valueSupplier the supplier to get the {@code double} value of the data point
         * @param labelNamesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
         * @return this builder
         */
        @NonNull
        public Builder registerDataPoint(
                @NonNull DoubleSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
            return registerDataPoint(new NumberSupplier(valueSupplier), labelNamesAndValues);
        }

        @NonNull
        private Builder registerDataPoint(
                @NonNull NumberSupplier valueSupplier, @NonNull String... labelNamesAndValues) {
            // labels will be validated during metric construction
            valuesSuppliers.add(valueSupplier);
            this.labelNamesAndValues.add(labelNamesAndValues);
            return this;
        }

        public int getDataPointsSize() {
            return valuesSuppliers.size();
        }

        @NonNull
        public String[] getDataPointsLabelNamesAndValues(int idx) {
            return labelNamesAndValues.get(idx);
        }

        @NonNull
        public NumberSupplier getValuesSupplier(int idx) {
            return valuesSuppliers.get(idx);
        }

        /**
         * Build the {@link StatelessMetric} instance.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        protected StatelessMetric buildMetric() {
            return new StatelessMetricImpl(this);
        }
    }
}
