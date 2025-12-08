// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Function;

/**
 * Base interface for a metric, which holds data points per unique combination
 * of dynamic label values, providing methods to update values.
 * <p>
 * Implementation is responsible for creating a new data point for each unique set of dynamic label values.
 * <p>
 * Clients should pay attention the dynamic label values cardinality, as high cardinality can lead to
 * higher costs for metrics backends. <b>Do not use</b> labels with values having unbounded cardinality,
 * such as IDs or timestamps.
 * <p>
 * Data points are created lazily, when requested via {@link #getOrCreateLabeled(String...)},
 * {@link #getOrCreateLabeled(Object, String...)} or {@link #getOrCreateNotLabeled()} for observation,
 * so no data point will be exported until at least one observation is made using these methods.
 * If initial value of data point has to be exported even before observation,
 * {@code getOrCreate} method can be called to instantiate datapoint.
 *
 * @param <I> the type of the initializer used to create new data points per label set
 * @param <D> the type of the data point
 */
public interface StatefulMetric<I, D> extends Metric {

    /**
     * Get or create the data point with no labels.
     *
     * @return the data point with no labels
     * @throws IllegalStateException if metric has dynamic labels specified during creation
     */
    @NonNull
    D getOrCreateNotLabeled();

    /**
     * Get or create the data point with the specified labels, using the default initializer.
     * <p>
     * Provided label names must match the dynamic labels specified during metric creation.
     * Static labels should not be provided here, as they are already associated with the metric.
     * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
     *
     * @param namesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
     * @return the data point with the specified labels
     * @throws IllegalStateException if metric has no dynamic labels specified during creation
     * @throws IllegalArgumentException if provided label names do not match {@link #dynamicLabelNames()}
     */
    @NonNull
    D getOrCreateLabeled(@NonNull String... namesAndValues);

    /**
     * Get or create the data point with the specified labels, using a custom initializer.
     * <p>
     * Provided label names must match the dynamic labels specified during metric creation.
     * Static labels should not be provided here, as they are already associated with the metric.
     * Order doesn't matter, but for efficiency, it is recommended to provide label names in alphabetical order.
     *
     * @param initializer the initializer to create new data points, must not be {@code null}
     * @param namesAndValues alternating label names and values, e.g. "label1", "value1", "label2", "value2"
     * @return the data point with the specified labels
     * @throws IllegalStateException if metric has no dynamic labels specified during creation
     * @throws IllegalArgumentException if provided label names do not match {@link #dynamicLabelNames()}
     */
    @NonNull
    D getOrCreateLabeled(@NonNull I initializer, @NonNull String... namesAndValues);

    /**
     * Base abstract builder for {@link StatefulMetric}.
     *
     * @param <I> the type of the initializer used to create new data points per label set
     * @param <D> the type of the data point
     * @param <B> the type of the builder
     * @param <M> the type of the metric
     */
    abstract class Builder<I, D, B extends Builder<I, D, B, M>, M extends StatefulMetric<I, D>>
            extends Metric.Builder<B, M> {

        private I defaultInitializer;
        private Function<I, D> dataPointFactory;

        /**
         * Constructor for a stateful metric builder.
         *
         * @param type               the metric type, must not be {@code null}
         * @param key                the metric key, must not be {@code null}
         * @param defaultInitializer the default initializer to use to create new data points, must not be {@code null}
         * @param dataPointFactory   the factory function to create new data points, must not be {@code null}
         */
        protected Builder(
                @NonNull MetricType type,
                @NonNull MetricKey<M> key,
                @NonNull I defaultInitializer,
                @NonNull Function<I, D> dataPointFactory) {
            super(type, key);
            withDefaultInitializer(defaultInitializer);
            withContainerFactory(dataPointFactory);
        }

        /**
         * @return the data point factory, never {@code null}
         */
        @NonNull
        public Function<I, D> getDataPointFactory() {
            return dataPointFactory;
        }

        /**
         * @return the default initializer, never {@code null}
         */
        @NonNull
        public I getDefaultInitializer() {
            return defaultInitializer;
        }

        /**
         * Set the default initializer to use to create new data points.
         *
         * @param defaultInitializer the default initializer, must not be {@code null}
         * @return this builder
         */
        @NonNull
        public final B withDefaultInitializer(@NonNull I defaultInitializer) {
            this.defaultInitializer =
                    Objects.requireNonNull(defaultInitializer, "Default initializer must not be null");
            return self();
        }

        /**
         * Set the factory function to use to create new data points.
         *
         * @param dataPointFactory the factory function, must not be {@code null}
         * @return this builder
         */
        @NonNull
        protected B withContainerFactory(@NonNull Function<I, D> dataPointFactory) {
            this.dataPointFactory = Objects.requireNonNull(dataPointFactory, "Data point factory must not be null");
            return self();
        }
    }
}
