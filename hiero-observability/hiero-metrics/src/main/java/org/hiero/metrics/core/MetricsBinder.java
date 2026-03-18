// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Interface for binding a {@link MetricRegistry} for metrics registration or retrieval. <br>
 * Implementation can also propagate registry to other places where metrics are used (calling other binders).
 * <p>
 * If you know that your implementation would be the only place where metric is observed,
 * metric can be registered and cached for observation in local field.<br>
 * If metric is observed in multiple places, use {@link MetricsRegistrationProvider} to register the metric
 * and save {@link MetricKey} in a {@code public static final} field
 * to retrieve the metric from the registry while binding.
 *
 * @see MetricsRegistrationProvider
 */
public interface MetricsBinder {

    /**
     * Binds the provided {@link MetricRegistry}.
     * This method can be called during the initialization phase to register or retrieve metrics.
     *
     * @param registry the {@link MetricRegistry} to bind, must not be {@code null}
     */
    void bind(@NonNull MetricRegistry registry);
}
