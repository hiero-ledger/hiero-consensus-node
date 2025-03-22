// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.network.connectivity.OutboundConnectionCreator;
import com.swirlds.platform.network.connectivity.SocketFactory;
import java.util.List;
import java.util.Optional;

/**
 * Configuration for the gossip layer of the node.
 *
 * @param interfaceBindings A list of interface bindings used in {@link SocketFactory}.
 *                          These bindings allow overriding the default network behavior
 *                          in specialized environments, such as containerized
 *                          deployments, where custom network interfaces may be required.
 *                          Each entry specifies how the node should bind to its network
 *                          interfaces.
 * @param endpointOverrides A list of endpoint overrides used in {@link OutboundConnectionCreator}.
 *                          These overrides provide the ability to replace the default IP
 *                          address and port of endpoints obtained from the roster. This is
 *                          particularly useful in cases where the actual network configuration
 *                          differs from the information specified in the roster, such as
 *                          behind NATs or when using virtualized networks.
 */
@ConfigData("gossip")
public record GossipConfig(
        @ConfigProperty(defaultValue = Configuration.EMPTY_LIST) List<NetworkEndpoint> interfaceBindings,
        @ConfigProperty(defaultValue = Configuration.EMPTY_LIST) List<NetworkEndpoint> endpointOverrides) {

    /**
     * Returns the interface binding for the given node ID.
     * <p>
     * <b>Note:</b> If there are multiple interface bindings for the same node ID, only the first one will be
     * returned.
     * </p>
     *
     * @param nodeId the node ID
     * @return optional of the interface binding, empty if not found
     */
    public Optional<NetworkEndpoint> getInterfaceBindings(long nodeId) {
        return interfaceBindings.stream()
                .filter(binding -> binding.nodeId().equals(nodeId))
                .findFirst();
    }

    /**
     * Returns the endpoint override for the given node ID.
     * <p>
     * <b>Note:</b> If there are multiple endpoint overrides for the same node ID, only the first one will be
     * returned.
     * </p>
     *
     * @param nodeId the node ID
     * @return optional of the endpoint override, empty if not found
     */
    public Optional<NetworkEndpoint> getEndpointOverride(long nodeId) {
        return endpointOverrides.stream()
                .filter(binding -> binding.nodeId().equals(nodeId))
                .findFirst();
    }
}
