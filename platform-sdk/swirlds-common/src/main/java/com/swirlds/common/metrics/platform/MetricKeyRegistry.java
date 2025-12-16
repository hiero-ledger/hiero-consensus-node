// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.metrics.api.Metric;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * With the help of a {@code MetricsKeyRegistry} we ensure, that there are no two {@link Metric}s with the same
 * metric key, but conflicting configurations.
 * <p>
 * Before a new {@code Metric} is added, we must try to {@link #register(Long, String, Class)} its
 * metric key. Only if that is successful, can we add the {@code Metric}. When a {@code Metric} is removed,
 * it is recommended to {@link #unregister(Long, String)} it, to free the key.
 * <p>
 * Two configurations are conflicting, if the {@code Metric}-class are different or if one of them is global
 * and the other one is platform-specific.
 */
public class MetricKeyRegistry {

    private record Registration(Set<Long> nodeIds, Class<? extends Metric> clazz) {}

    private final Map<String, Registration> registrations = new HashMap<>();

    /**
     * Try to register (and reserve) a metric key. The key of an instance-specific metric can be reused
     * on another instance if the types are the same.
     *
     * @param id
     * 		the instance id for which we want to register the key
     * @param key
     * 		the actual metric key
     * @param clazz
     * 		the {@link Class} of the metric
     * @return {@code true} if the registration was successful, {@code false} otherwise
     */
    public synchronized boolean register(final Long id, final String key, final Class<? extends Metric> clazz) {
        final Registration registration = registrations.computeIfAbsent(
                key, k -> new Registration(id == null ? null : new HashSet<>(), clazz));
        if (registration.clazz == clazz) {
            if (id == null) {
                return registration.nodeIds == null;
            } else {
                if (registration.nodeIds != null) {
                    registration.nodeIds.add(id);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Unregister a metric key
     *
     * @param id
     * 		the instance id for which the key should be unregistered
     * @param key
     * 		the actual metric key
     */
    public synchronized void unregister(final Long id, final String key) {
        if (id == null) {
            registrations.computeIfPresent(key, (k, v) -> v.nodeIds == null ? null : v);
        } else {
            final Registration registration = registrations.get(key);
            if (registration != null && (registration.nodeIds != null)) {
                registration.nodeIds.remove(id);
                if (registration.nodeIds.isEmpty()) {
                    registrations.remove(key);
                }
            }
        }
    }
}
