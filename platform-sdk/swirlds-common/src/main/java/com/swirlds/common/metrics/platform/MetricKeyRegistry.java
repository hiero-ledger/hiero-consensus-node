// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.metrics.api.Metric;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * With the help of a {@code MetricsKeyRegistry} we ensure, that there are no two {@link Metric}s with the same
 * metric key, but conflicting configurations.
 * <p>
 * Before a new {@code Metric} is added, we must try to {@link #register(NodeId, String, Class)} its
 * metric key. Only if that is successful, can we add the {@code Metric}. When a {@code Metric} is removed,
 * it is recommended to {@link #unregister(NodeId, String)} it, to free the key.
 * <p>
 * Two configurations are conflicting, if the {@code Metric}-class are different or if one of them is global
 * and the other one is platform-specific.
 */
public class MetricKeyRegistry {

    private record Registration(Set<NodeId> nodeIds, Class<? extends Metric> clazz) {}

    private final Map<String, Registration> registrations = new HashMap<>();

    /**
     * Try to register (and reserve) a metric key. The key of a platform-specific metric can be reused
     * on another platform, if the types are the same.
     *
     * @param nodeId
     * 		the {@link NodeId} for which we want to register the key
     * @param key
     * 		the actual metric key
     * @param clazz
     * 		the {@link Class} of the metric
     * @return {@code true} if the registration was successful, {@code false} otherwise
     */
    public synchronized boolean register(final NodeId nodeId, final String key, final Class<? extends Metric> clazz) {
        final Registration registration = registrations.computeIfAbsent(
                key, k -> new Registration(nodeId == null ? null : new HashSet<>(), clazz));
        if (registration.clazz == clazz) {
            if (nodeId == null) {
                return registration.nodeIds == null;
            } else {
                if (registration.nodeIds != null) {
                    registration.nodeIds.add(nodeId);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Unregister a metric key
     *
     * @param nodeId
     * 		the {@link NodeId} for which the key should be unregistered
     * @param key
     * 		the actual metric key
     */
    public synchronized void unregister(final NodeId nodeId, final String key) {
        if (nodeId == null) {
            registrations.computeIfPresent(key, (k, v) -> v.nodeIds == null ? null : v);
        } else {
            final Registration registration = registrations.get(key);
            if (registration != null && (registration.nodeIds != null)) {
                registration.nodeIds.remove(nodeId);
                if (registration.nodeIds.isEmpty()) {
                    registrations.remove(key);
                }
            }
        }
    }
}
