// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.MetricUtils;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.DoubleCounterMeasurement;
import org.hiero.metrics.internal.DoubleCounterImpl;
import org.hiero.metrics.internal.measurement.DoubleAdderCounterMeasurement;

/**
 * A metric of type {@link MetricType#COUNTER} that holds {@link DoubleCounterMeasurement} per label set.
 */
public interface DoubleCounter extends SettableMetric<DoubleSupplier, DoubleCounterMeasurement> {

    /**
     * Create a metric key for a {@link DoubleCounter} with the given name.<br>
     * See {@link org.hiero.metrics.api.core.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<DoubleCounter> key(@NonNull String name) {
        return MetricKey.of(name, DoubleCounter.class);
    }

    /**
     * Create a builder for a {@link DoubleCounter} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<DoubleCounter> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link DoubleCounter} with the given metric name. <br>
     * See {@link org.hiero.metrics.api.core.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * Builder for {@link DoubleCounter} using {@link DoubleCounterMeasurement} per label set.
     * <p>
     * Default initial value is {@code 0.0}, but could be modified using {@link #setInitValue(double)}.
     */
    final class Builder
            extends SettableMetric.Builder<DoubleSupplier, DoubleCounterMeasurement, Builder, DoubleCounter> {

        private Builder(@NonNull MetricKey<DoubleCounter> key) {
            super(MetricType.COUNTER, key, MetricUtils.DOUBLE_ZERO_INIT, DoubleAdderCounterMeasurement::new);
        }

        /**
         * Set the default value for the counter and any measurement within this metric.
         *
         * @param initValue the default value for any measurement within this metric
         * @return this builder
         */
        @NonNull
        public Builder setInitValue(double initValue) {
            return setDefaultInitializer(MetricUtils.asSupplier(initValue));
        }

        /**
         * Build the {@link DoubleCounter} metric.
         *
         * @return this builder
         */
        @NonNull
        @Override
        public DoubleCounter buildMetric() {
            return new DoubleCounterImpl(this);
        }
    }
}
