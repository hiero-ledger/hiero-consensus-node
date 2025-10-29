// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A thread-safe registry for {@link Metric} instances.
 * <p>
 * Registry must be created via {@link MetricsFacade#createRegistry(Label...)} factory method.
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
     * Registers metrics provided by the given {@link MetricsRegistrationProvider}.
     *
     * @param provider the metrics registration provider, must not be {@code null}
     */
    void registerMetrics(@NonNull MetricsRegistrationProvider provider);

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
}
