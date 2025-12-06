// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.DoubleSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.StatefulMetric;
import org.hiero.metrics.api.datapoint.DoubleCounterDataPoint;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.internal.DoubleCounterImpl;
import org.hiero.metrics.internal.datapoint.DoubleAdderCounterDataPoint;

/**
 * A stateful metric of type {@link MetricType#COUNTER} that holds {@link DoubleCounterDataPoint} per label set.
 */
public interface DoubleCounter extends StatefulMetric<DoubleSupplier, DoubleCounterDataPoint> {

    /**
     * Create a metric key for a {@link DoubleCounter} with the given name.<br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateNameCharacters(String)} for name requirements.
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
     * Builder for {@link DoubleCounter} using {@link DoubleCounterDataPoint} per label set.
     * <p>
     * Default initial value is {@code 0.0}, but could be modified using {@link #withInitValue(double)}.
     */
    final class Builder extends StatefulMetric.Builder<DoubleSupplier, DoubleCounterDataPoint, Builder, DoubleCounter> {

        private Builder(@NonNull MetricKey<DoubleCounter> key) {
            super(MetricType.COUNTER, key, StatUtils.DOUBLE_INIT, DoubleAdderCounterDataPoint::new);
        }

        /**
         * Set the default value for the counter and any data point within this metric.
         *
         * @param initValue the default value for any data point within this metric
         * @return this builder
         */
        @NonNull
        public Builder withInitValue(double initValue) {
            return withDefaultInitializer(StatUtils.asInitializer(initValue));
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
