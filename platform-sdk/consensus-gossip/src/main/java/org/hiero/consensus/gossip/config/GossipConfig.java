// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Configuration for the gossip layer of the node.
 *
 * @param interfaceBindings A list of interface bindings used in {@code SocketFactory}.
 *                          These bindings allow overriding the default network behavior
 *                          in specialized environments, such as containerized
 *                          deployments, where custom network interfaces may be required.
 *                          Each entry specifies how the node should bind to its network
 *                          interfaces.
 * @param endpointOverrides A list of endpoint overrides used in {@code OutboundConnectionCreator}.
 *                          These overrides provide the ability to replace the default IP
 *                          address and port of endpoints obtained from the roster. This is
 *                          particularly useful in cases where the actual network configuration
 *                          differs from the information specified in the roster, such as
 *                          behind NATs or when using virtualized networks.
 * @param connectionServerThreadPriority priority for threads that listen for incoming gossip connections.
 * @param hangingThreadDuration        the length of time a gossip thread is allowed to wait when it is asked to
 *                                      shutdown. If a gossip thread takes longer than this period to shut down, then an
 *                                      error message is written to the log.
 */
@ConfigData("gossip")
public record GossipConfig(
        @ConfigProperty(defaultValue = Configuration.EMPTY_LIST)
        List<NetworkEndpoint> interfaceBindings,

        @ConfigProperty(defaultValue = Configuration.EMPTY_LIST)
        List<NetworkEndpoint> endpointOverrides,

        @ConfigProperty(defaultValue = "5") int connectionServerThreadPriority,
        @ConfigProperty(defaultValue = "60s") Duration hangingThreadDuration) {

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
