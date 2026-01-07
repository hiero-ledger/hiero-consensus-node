// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.LongCounterMeasurement;
import org.hiero.metrics.internal.LongCounterImpl;
import org.hiero.metrics.internal.core.MetricUtils;

/**
 * A metric of type {@link MetricType#COUNTER} that holds {@link LongCounterMeasurement} per label set.
 */
public interface LongCounter extends SettableMetric<LongSupplier, LongCounterMeasurement> {

    /**
     * Create a metric key for a {@link LongCounter} with the given name. <br>
     * Name must match {@value METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the metric key
     */
    @NonNull
    static MetricKey<LongCounter> key(@NonNull String name) {
        return MetricKey.of(name, LongCounter.class);
    }

    /**
     * Create a builder for a {@link LongCounter} with the given metric key.
     *
     * @param key the metric key
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull MetricKey<LongCounter> key) {
        return new Builder(key);
    }

    /**
     * Create a builder for a {@link LongCounter} with the given metric name. <br>
     * Name must match {@value METRIC_NAME_REGEX}.
     *
     * @param name the name of the metric
     * @return the builder
     */
    @NonNull
    static Builder builder(@NonNull String name) {
        return builder(key(name));
    }

    /**
     * Builder for {@link LongCounter} using {@link LongCounterMeasurement} per label set.
     * <p>
     * Default initial value is {@code 0L}. <br>
     * {@link java.util.concurrent.atomic.LongAdder} is used in the measurement implementation.
     */
    final class Builder extends SettableMetric.Builder<LongSupplier, Builder, LongCounter> {

        private Builder(@NonNull MetricKey<LongCounter> key) {
            super(MetricType.COUNTER, key, MetricUtils.LONG_ZERO_INIT);
        }

        /**
         * Sets the default initial value for the counter measurements created by this metric.
         *
         * @param initValue the initial value
         * @return this builder
         */
        @NonNull
        public Builder setInitValue(long initValue) {
            return setDefaultInitializer(MetricUtils.asSupplier(initValue));
        }

        /**
         * Build the {@link LongCounter} metric.
         *
         * @return this builder
         */
        @NonNull
        @Override
        public LongCounter buildMetric() {
            return new LongCounterImpl(this);
        }
    }
}
