// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;

import com.swirlds.base.state.Startable;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collection;
import java.util.Objects;

/**
 * Entry-point to the metrics-system.
 * <p>
 * The interface {@code Metrics} provides functionality to add and delete metrics. There are also several
 * methods to request registered metrics.
 * <p>
 * In addition, one can register an updater which will be called once per second to update metrics periodically.
 */
@SuppressWarnings("unused")
public interface Metrics extends Startable {

    String INTERNAL_CATEGORY = "internal";
    String PLATFORM_CATEGORY = "platform";
    String INFO_CATEGORY = "platform:info";

    /**
     * Get a single {@link Metric} identified by its category and name
     *
     * @param category
     * 		the category of the wanted category
     * @param name
     * 		the name of the wanted category
     * @return the {@code Metric} if one is found, {@code null} otherwise
     * @throws NullPointerException
     * 		if one of the parameters is {@code null}
     */
    @Nullable
    Metric getMetric(final @NonNull String category, final @NonNull String name);

    /**
     * Get all metrics with the given category.
     * <p>
     * Categories are structured hierarchically, e.g. if there are two categories "crypto.signature" and
     * "crypto.digest" one will receive metrics from both categories if searching for "crypto"
     * <p>
     * The returned {@link Collection} is backed by the original data-structure, i.e. future changes are
     * automatically reflected. The {@code Collection} is not modifiable.
     * <p>
     * The returned values are ordered by category and name.
     *
     * @param category
     * 		the category of the wanted metrics
     * @return all metrics that have the category or a sub-category
     * @throws NullPointerException
     * 		if {@code category} is {@code null}
     */
    @NonNull
    Collection<Metric> findMetricsByCategory(final @NonNull String category);

    /**
     * Get a list of all metrics that are currently registered.
     * <p>
     * The returned {@link Collection} is backed by the original data-structure, i.e. future changes are
     * automatically reflected. The {@code Collection} is not modifiable.
     * <p>
     * The returned values are ordered by category and name.
     *
     * @return all registered metrics
     */
    @NonNull
    Collection<Metric> getAll();

    /**
     * Get the value of a metric directly. Calling this method is equivalent to calling
     * {@code getMetric(category, name).get(Metric.ValueType.VALUE)}
     *
     * @param category
     * 		the category of the wanted category
     * @param name
     * 		the name of the wanted category
     * @return the {@code value} of the {@link Metric}, if one is found, {@code null} otherwise
     * @throws NullPointerException
     * 		if one of the parameters is {@code null}
     */
    @Nullable
    default Object getValue(final @NonNull String category, final @NonNull String name) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(name, "name must not be null");
        final Metric metric = getMetric(category, name);
        return metric != null ? metric.get(VALUE) : null;
    }

    /**
     * Resets all metrics
     */
    default void resetAll() {
        for (final Metric metric : getAll()) {
            metric.reset();
        }
    }

    /**
     * Checks if a {@link Metric} with the category and name as specified in the config-object exists and
     * returns it. If there is no such {@code Metric}, a new one is created, registered, and returned.
     *
     * @param config
     * 		the configuration of the {@code Metric}
     * @param <T>
     * 		class of the {@code Metric} that will be returned
     * @return the registered {@code Metric} (either existing or newly generated)
     * @throws NullPointerException
     * 		if {@code config} is {@code null}
     * @throws IllegalStateException
     * 		if a {@code Metric} with the same category and name exists, but has a different type
     */
    <T extends Metric> @NonNull T getOrCreate(final @NonNull MetricConfig<T, ?> config);

    /**
     * Remove the {@link Metric} with the given category and name
     *
     * @param category
     * 		the category of the {@code Metric}, that should be removed
     * @param name
     * 		the name of the {@code Metric}, that should be removed
     * @throws NullPointerException
     * 		if one of the parameters is {@code null}
     */
    void remove(final @NonNull String category, final @NonNull String name);

    /**
     * Remove the {@link Metric}.
     * <p>
     * Please note that two metrics are equal, if their class, category, and name match.
     *
     * @param metric
     * 		the {@code Metric}, that should be removed
     * @throws NullPointerException
     * 		if ({code metric} is {@code null}
     */
    void remove(final @NonNull Metric metric);

    /**
     * Remove the {@link Metric} with the given configuration.
     * <p>
     * Please note that two metrics are equal, if their class, category, and name match.
     *
     * @param config
     * 		the {@link MetricConfig} of the {@code Metric}, that should be removed
     * @throws NullPointerException
     * 		if {@code config} is {@code null}
     */
    void remove(final @NonNull MetricConfig<?, ?> config);

    /**
     * Add an updater that will be called once per second. An updater should only be used to update metrics regularly.
     *
     * @param updater
     * 		the updater
     * @throws NullPointerException
     * 		if {@code updater} is {@code null}
     */
    void addUpdater(final @NonNull Runnable updater);

    /**
     * Remove an updater that was previously added.
     *
     * @param updater
     * 		the updater
     * @throws NullPointerException
     * 		if {@code updater} is {@code null}
     */
    void removeUpdater(final @NonNull Runnable updater);
}
