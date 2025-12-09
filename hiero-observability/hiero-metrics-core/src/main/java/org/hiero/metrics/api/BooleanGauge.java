// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BooleanSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.BooleanGaugeMeasurement;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.internal.BooleanGaugeImpl;
import org.hiero.metrics.internal.measurement.AtomicBooleanGaugeMeasurement;

/**
 * A metric of type {@link MetricType#GAUGE} that holds {@link BooleanGaugeMeasurement} per label set. <br>
 * Last set {@code boolean} value will be reported during export.
 */
public interface BooleanGauge extends SettableMetric<BooleanSupplier, BooleanGaugeMeasurement> {

    /**
     * Create a metric key for a {@link BooleanGauge} with the given name.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<BooleanGauge> key(@NonNull String name) {
        return MetricKey.of(name, BooleanGauge.class);
    }

    /**
     * Create a builder for a {@link BooleanGauge} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<BooleanGauge> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link BooleanGauge} with the given metric name.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * Builder for {@link BooleanGauge} using {@link BooleanGaugeMeasurement} per label set.
     * <p>
     * Default initial value is {@code false}, but could be modified using {@link #withInitValue(boolean)}.
     */
    final class Builder
            extends SettableMetric.Builder<BooleanSupplier, BooleanGaugeMeasurement, Builder, BooleanGauge> {

        private Builder(@NonNull MetricKey<BooleanGauge> key) {
            super(MetricType.GAUGE, key, StatUtils.BOOL_INIT_FALSE, AtomicBooleanGaugeMeasurement::new);
        }

        /**
         * Set the default value for the gauge and any measurement within this metric.
         *
         * @param initValue the default value for any measurement within this metric
         * @return this builder
         */
        @NonNull
        public Builder withInitValue(boolean initValue) {
            return withDefaultInitializer(StatUtils.asInitializer(initValue));
        }

        /**
         * Build the {@link BooleanGauge} metric.
         *
         * @return this builder
         */
        @NonNull
        @Override
        public BooleanGauge buildMetric() {
            return new BooleanGaugeImpl(this);
        }
    }
}
