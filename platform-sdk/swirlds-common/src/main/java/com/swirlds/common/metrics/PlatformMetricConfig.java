// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.MetricsFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An extension of the {@link MetricConfig} that adds the ability to create a {@link Metric} from a
 * {@link PlatformMetricsFactory}.
 *
 * @param <T> the type of the {@link Metric} that is created
 * @param <C> the type of the {@link MetricConfig} that is used to create the {@link Metric}
 */
public abstract class PlatformMetricConfig<T extends Metric, C extends MetricConfig<T, C>> extends MetricConfig<T, C> {

    /**
     * Constructs a new {@link PlatformMetricConfig} with the given parameters.
     *
     * @param category    the category of the {@link Metric}
     * @param name        the name of the {@link Metric}
     */
    protected PlatformMetricConfig(@NonNull final String category, @NonNull final String name) {
        super(category, name);
    }

    /**
     * Constructs a new {@link Metric} with the given {@link PlatformMetricsFactory}.
     *
     * @param factory the {@link PlatformMetricsFactory} used to create the {@link Metric}
     * @return the created {@link Metric}
     */
    public abstract T create(@NonNull final PlatformMetricsFactory factory);

    @NonNull
    @Override
    public final T create(@NonNull MetricsFactory factory) {
        if (factory instanceof PlatformMetricsFactory) {
            return create((PlatformMetricsFactory) factory);
        }
        throw new IllegalStateException("MetricsFactory must be a PlatformMetricsFactory");
    }
}
