// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.DoubleGaugeMeasurement;
import org.hiero.metrics.internal.DoubleGaugeImpl;
import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link DoubleGaugeMeasurement} per label set.
 * <p>
 * The gauge could be configured to hold the last value set, or to accumulate values using an operator
 * (e.g. sum, min, max). See {@link Builder} for details.
 */
public interface DoubleGauge extends SettableMetric<DoubleSupplier, DoubleGaugeMeasurement> {

    /**
     * Create a metric key for a {@link DoubleGauge} with the given name. <br>
     * Name must match {@value METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<DoubleGauge> key(@NonNull String name) {
        return MetricKey.of(name, DoubleGauge.class);
    }

    /**
     * Create a builder for a {@link DoubleGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<DoubleGauge> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link DoubleGauge} with the given metric name.<br>
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
     * Builder for {@link DoubleGauge} using {@link DoubleGaugeMeasurement} per label set.
     * <p>
     * Default initial value is {@code 0.0}, but could be modified with {@link #setInitValue(double)}.
     */
    final class Builder extends SettableMetric.Builder<DoubleSupplier, Builder, DoubleGauge> {

        private Builder(@NonNull MetricKey<DoubleGauge> key) {
            super(MetricType.GAUGE, key, MetricUtils.DOUBLE_ZERO_INIT);
        }

        /**
         * Set the initial value for the gauge and any measurement within this metric.
         *
         * @param initValue the initial value for any measurement within this metric
         * @return this builder
         */
        @NonNull
        public Builder setInitValue(double initValue) {
            return setDefaultInitializer(MetricUtils.asSupplier(initValue));
        }

        /**
         * Build the {@link DoubleGauge} metric.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        public DoubleGauge buildMetric() {
            return new DoubleGaugeImpl(this);
        }
    }
}
