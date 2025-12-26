// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.LongSupplier;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricType;
import org.hiero.metrics.api.core.SettableMetric;
import org.hiero.metrics.api.measurement.LongCounterMeasurement;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.internal.LongCounterImpl;
import org.hiero.metrics.internal.measurement.AtomicLongCounterMeasurement;
import org.hiero.metrics.internal.measurement.LongAdderCounterMeasurement;

/**
 * A metric of type {@link MetricType#COUNTER} that holds {@link LongCounterMeasurement} per label set.
 */
public interface LongCounter extends SettableMetric<LongSupplier, LongCounterMeasurement> {

    /**
     * Create a metric key for a {@link LongCounter} with the given name. <br>
     * See {@link org.hiero.metrics.api.utils.MetricUtils#validateMetricNameCharacters(String)} for name requirements.
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
     * Builder for {@link LongCounter} using {@link LongCounterMeasurement} per label set.
     * <p>
     * Default initial value is {@code 0L}. <br>
     * By default, {@link java.util.concurrent.atomic.LongAdder} is used in the measurement implementation, but
     * could be changed to use {@link java.util.concurrent.atomic.AtomicLong} by calling
     * {@link #withLowThreadContention()} if no high contention on update is expected.
     */
    final class Builder extends SettableMetric.Builder<LongSupplier, LongCounterMeasurement, Builder, LongCounter> {

        private Builder(@NonNull MetricKey<LongCounter> key) {
            super(MetricType.COUNTER, key, StatUtils.LONG_INIT, LongAdderCounterMeasurement::new);
        }

        /**
         * Sets the default initial value for the counter measurements created by this metric.
         *
         * @param initValue the initial value
         * @return this builder
         */
        @NonNull
        public Builder withInitValue(long initValue) {
            return withDefaultInitializer(StatUtils.asInitializer(initValue));
        }

        /**
         * Uses {@link AtomicLongCounterMeasurement} instead of default {@link LongAdderCounterMeasurement}
         * as the measurement implementation for this metric, if no high contention on update is expected.
         *
         * @return this builder
         */
        @NonNull
        public Builder withLowThreadContention() {
            withContainerFactory(AtomicLongCounterMeasurement::new);
            return this;
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
