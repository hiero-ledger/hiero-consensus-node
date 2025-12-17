// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.metrics.api.Metric;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * With the help of a {@code MetricsKeyRegistry} we ensure, that there are no two {@link Metric}s with the same
 * metric key, but conflicting configurations.
 * <p>
 * Before a new {@code Metric} is added, we must try to {@link #register(Object, String, Class)} its
 * metric key. Only if that is successful, can we add the {@code Metric}. When a {@code Metric} is removed,
 * it is recommended to {@link #unregister(Object, String)} it, to free the key.
 * <p>
 * Two configurations are conflicting, if the {@code Metric}-class are different or if one of them is global
 * and the other one is platform-specific.
 *
 * @param <KEY> the type of the unique identifier for separate instances of metrics
 */
public class MetricKeyRegistry<KEY> {

    private record Registration<KEY>(Set<KEY> keys, Class<? extends Metric> clazz) {}

    private final Map<String, Registration<KEY>> registrations = new HashMap<>();

    /**
     * Try to register (and reserve) a metric key. The key of a platform-specific metric can be reused
     * on another platform, if the types are the same.
     *
     * @param key
     * 		the unique identifier for which we want to register the metric key (null for global metrics)
     * @param metricKey
     * 		the actual metric key
     * @param clazz
     * 		the {@link Class} of the metric
     * @return {@code true} if the registration was successful, {@code false} otherwise
     */
    public synchronized boolean register(
            @Nullable final KEY key, @NonNull final String metricKey, @NonNull final Class<? extends Metric> clazz) {
        final Registration<KEY> registration = registrations.computeIfAbsent(
                metricKey, k -> new Registration<>(key == null ? null : new HashSet<>(), clazz));
        if (registration.clazz == clazz) {
            if (key == null) {
                return registration.keys == null;
            } else {
                if (registration.keys != null) {
                    registration.keys.add(key);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Unregister a metric key
     *
     * @param key
     * 		the unique identifier for which the metric key should be unregistered (null for global metrics)
     * @param metricKey
     * 		the actual metric key
     */
    public synchronized void unregister(@Nullable final KEY key, @NonNull final String metricKey) {
        if (key == null) {
            registrations.computeIfPresent(metricKey, (k, v) -> v.keys == null ? null : v);
        } else {
            final Registration<KEY> registration = registrations.get(metricKey);
            if (registration != null && (registration.keys != null)) {
                registration.keys.remove(key);
                if (registration.keys.isEmpty()) {
                    registrations.remove(metricKey);
                }
            }
        }
    }
}
