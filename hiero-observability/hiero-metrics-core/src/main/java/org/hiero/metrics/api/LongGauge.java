// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.LongGaugeMeasurement;
import org.hiero.metrics.internal.LongGaugeImpl;
import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link LongGaugeMeasurement} per label set.
 * <p>
 * The gauge could be configured to hold the last value set, or to accumulate values using an operator
 * (e.g. sum, min, max). See {@link Builder} for details.
 */
public interface LongGauge extends SettableMetric<LongSupplier, LongGaugeMeasurement> {

    /**
     * Create a metric key for a {@link LongGauge} with the given name. <br>
     * Name must match {@value METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<LongGauge> key(@NonNull String name) {
        return MetricKey.of(name, LongGauge.class);
    }

    /**
     * Create a builder for a {@link LongGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<LongGauge> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link LongGauge} with the given metric name. <br>
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
     * A builder for a {@link LongGauge} using {@link LongGaugeMeasurement} per label set.
     * <p>
     * Default initial value is {@code 0L}.
     */
    final class Builder extends SettableMetric.Builder<LongSupplier, Builder, LongGauge> {

        private Builder(@NonNull MetricKey<LongGauge> key) {
            super(MetricType.GAUGE, key, MetricUtils.LONG_ZERO_INIT);
        }

        /**
         * Set the initial value for the gauge and any measurement within this metric.
         *
         * @param initValue the initial value for any measurement within this metric
         * @return this builder
         */
        @NonNull
        public Builder setInitValue(long initValue) {
            return setDefaultInitializer(MetricUtils.asSupplier(initValue));
        }

        /**
         * Build the {@link LongGauge} instance.
         *
         * @return the built metric
         */
        @NonNull
        @Override
        public LongGauge buildMetric() {
            return new LongGaugeImpl(this);
        }
    }
}
