// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.metrics.api.utils.MetricUtils;
import org.hiero.metrics.internal.core.MetricRegistryImpl;

/**
 * A thread-safe registry for {@link Metric} instances.
 * <p>
 * Registry can be created via {@link #builder()} using builder pattern.
 */
public sealed interface MetricRegistry permits org.hiero.metrics.internal.export.SnapshotableMetricsRegistry {

    /**
     * Returns an unmodifiable list of global labels that are applied to all metrics in this registry.
     *
     * @return the global labels, may be empty but never {@code null}
     */
    @NonNull
    List<Label> globalLabels();

    /**
     * Returns an unmodifiable collection of all registered metrics.
     * Order of the metrics is not guaranteed and can change over time as new metrics are registered.
     *
     * @return the registered metrics, may be empty but never {@code null}
     */
    @NonNull
    Collection<Metric> metrics();

    /**
     * Registers a metric using the given builder.
     * <p>
     * This method is <b>not idempotent</b> and throws an exception if metrics with same name already registered.
     *
     * @param builder the metric builder, must not be {@code null}
     * @param <M>     the type of the metric
     * @param <B>     the type of the metric builder
     * @return the registered metric, never {@code null}
     * @throws IllegalArgumentException if a metric with the same name already exists in the registry
     */
    @NonNull
    <M extends Metric, B extends Metric.Builder<?, M>> M register(@NonNull B builder);

    /**
     * Finds a metric by its key.
     *
     * @param key the metric key, must not be {@code null}
     * @param <M> the type of the metric
     * @return an {@link Optional} for the found metric. If no metric is found, an empty {@link Optional} is returned.
     */
    @NonNull
    <M extends Metric> Optional<M> findMetric(@NonNull MetricKey<M> key);

    /**
     * Gets a metric by its key.
     *
     * @param key the metric key, must not be {@code null}
     * @param <M> the type of the metric
     * @return the found metric, never {@code null}
     * @throws IllegalArgumentException if no metric is found for the given key
     */
    @NonNull
    default <M extends Metric> M getMetric(@NonNull MetricKey<M> key) {
        Optional<M> metric = findMetric(key);
        if (metric.isPresent()) {
            return metric.get();
        }
        throw new IllegalArgumentException("Metric not found: " + key);
    }

    /**
     * Resets all registered metrics to their initial state.
     * Default implementation iterates over all metrics and calls their {@link Metric#reset()} method.
     */
    default void reset() {
        metrics().forEach(Metric::reset);
    }

    /**
     * Creates a new {@link Builder} for constructing a {@link MetricRegistry}.
     *
     * @return a new {@link Builder} instance
     */
    @NonNull
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating instances of {@link MetricRegistry}.
     */
    class Builder {

        private static final Logger logger = LogManager.getLogger(MetricRegistry.class);

        private boolean discoreMetricProviders = false;
        private final List<Label> globalLabels = new ArrayList<>();
        private final Set<String> globalLabelNames = new HashSet<>();

        /**
         * Adds a global label to the registry that will be applied to all metrics.
         *
         * @param label the label to add, must not be {@code null}
         * @return this builder instance
         * @throws NullPointerException if the label is {@code null}
         * @throws IllegalArgumentException if a global label with the same name already exists
         */
        @NonNull
        public Builder addGlobalLabel(@NonNull Label label) {
            Objects.requireNonNull(label, "global label must not be null");
            if (!globalLabelNames.add(label.name())) {
                throw new IllegalArgumentException("Duplicate global label name: " + label.name());
            }

            this.globalLabels.add(label);
            return this;
        }

        /**
         * Adds multiple global labels to the registry that will be applied to all metrics.
         *
         * @param labels the labels to add, must not be {@code null}
         * @return this builder instance
         */
        @NonNull
        public Builder addGlobalLabels(@NonNull Label... labels) {
            Objects.requireNonNull(labels, "global labels must not be null");
            for (Label label : labels) {
                addGlobalLabel(label);
            }
            return this;
        }

        /**
         * Enable discovery of {@link MetricsRegistrationProvider} implementations to register in the registry.
         *
         * @return this builder instance
         */
        @NonNull
        public Builder discoverMetricProviders() {
            this.discoreMetricProviders = true;
            return this;
        }

        /**
         * Builds the {@link MetricRegistry} instance.
         *
         * @return the constructed {@link MetricRegistry}
         */
        @NonNull
        public MetricRegistry build() {
            MetricRegistryImpl registry = new MetricRegistryImpl(globalLabels);

            if (discoreMetricProviders) {
                List<MetricsRegistrationProvider> providers = MetricUtils.load(MetricsRegistrationProvider.class);

                if (providers.isEmpty()) {
                    logger.info("No metrics registration providers found. Creating empty registry.");
                    return registry;
                }

                for (MetricsRegistrationProvider provider : providers) {
                    Objects.requireNonNull(provider, "metrics registration provider must not be null");
                    logger.info("Registering metrics from provider: {}", provider.getClass());

                    Collection<Metric.Builder<?, ?>> metricsToRegister = provider.getMetricsToRegister();
                    Objects.requireNonNull(metricsToRegister, "metrics collection must not be null");

                    for (Metric.Builder<?, ?> builder : metricsToRegister) {
                        registry.register(builder);
                    }
                }
            }

            return registry;
        }
    }
}
