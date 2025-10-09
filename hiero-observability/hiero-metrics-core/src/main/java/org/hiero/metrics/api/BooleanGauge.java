// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BooleanSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.api.datapoint.BooleanGaugeDataPoint;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.internal.DefaultBooleanGauge;
import org.hiero.metrics.internal.datapoint.AtomicBooleanGaugeDataPoint;

/**
 * A stateful metric of type {@link MetricType#GAUGE} that holds {@link BooleanGaugeDataPoint} per label set. <br>
 * Last set {@code boolean} value will be reported during export.
 */
public interface BooleanGauge extends StatefulMetric<BooleanSupplier, BooleanGaugeDataPoint> {

    /**
     * Create a metric key for a {@link BooleanGauge} with the given name.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
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
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
     *
     * @param name the name of the metric
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * Builder for {@link BooleanGauge} using {@link BooleanGaugeDataPoint} per label set.
     * <p>
     * Default initial value is {@code false}, but could be modified using {@link #withInitValue(boolean)}.
     */
    final class Builder extends StatefulMetric.Builder<BooleanSupplier, BooleanGaugeDataPoint, Builder, BooleanGauge> {

        private Builder(@NonNull MetricKey<BooleanGauge> key) {
            super(MetricType.GAUGE, key, StatUtils.BOOL_INIT_FALSE, AtomicBooleanGaugeDataPoint::new);
        }

        /**
         * Set the default value for the gauge and any data point within this metric.
         *
         * @param initValue the default value for any data point within this metric
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
            return new DefaultBooleanGauge(this);
        }

        /**
         * @return this builder
         */
        @NonNull
        @Override
        protected Builder self() {
            return this;
        }
    }
}
