// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.network;

/**
 * Marker interface for topology configurations.
 *
 * <p>Implementations should be immutable records providing builder-style methods for creating
 * modified copies. This interface enables a unified topology configuration API while allowing for
 * extensibility to support future topology types.
 *
 * <p>All topology configurations define how nodes in a network are connected, including latency,
 * jitter, and bandwidth characteristics.
 *
 * @see MeshTopologyConfiguration
 */
public interface TopologyConfiguration {
    // Marker interface - no required methods
    // Allows for extensibility when new topology types are added
}
