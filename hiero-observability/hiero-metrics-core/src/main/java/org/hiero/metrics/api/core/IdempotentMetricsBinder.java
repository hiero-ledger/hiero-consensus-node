// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;

/**
 * Abstract base class for {@link MetricsBinder} implementations that ensures
 * thread-safe and idempotent binding of metrics.
 * <p>
 * This class uses an {@link AtomicBoolean} to track whether the metrics have already been bound, preventing
 * multiple bindings. Subclasses must implement the {@link #bindMetricsNonIdempotent(MetricRegistry)} method
 * to define the actual binding logic, which will only be executed once.
 */
public abstract class IdempotentMetricsBinder implements MetricsBinder {

    private final AtomicBoolean metricsBound = new AtomicBoolean(false);

    @Override
    public final void bind(@NonNull MetricRegistry registry) {
        Objects.requireNonNull(registry, "metrics registry must not be null");

        if (metricsBound.compareAndSet(false, true)) {
            bindMetricsNonIdempotent(registry);
        } else {
            LogManager.getLogger(IdempotentMetricsBinder.class)
                    .warn(
                            "Metrics registry already bound. instance={}",
                            getClass().getName());
        }
    }

    /**
     * Checks if the metrics have already been bound.
     *
     * @return true if the metrics are already bound, false otherwise
     */
    public final boolean isMetricsBound() {
        return metricsBound.get();
    }

    /**
     * Binds the provided {@link MetricRegistry}. This method is called only once, ensuring idempotent behavior.
     * Subclasses must implement this method to define the actual binding logic.
     *
     * @param registry the {@link MetricRegistry} to bind
     */
    protected abstract void bindMetricsNonIdempotent(@NonNull MetricRegistry registry);
}
