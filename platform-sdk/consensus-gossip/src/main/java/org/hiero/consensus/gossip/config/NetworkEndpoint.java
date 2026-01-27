// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.config;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetAddress;
import java.util.Objects;

/**
 * A network endpoint consisting of a node ID, hostname, and port.
 *
 * @param nodeId   the node ID
 * @param hostname the hostname or IP address
 * @param port     the port number
 */
public record NetworkEndpoint(@NonNull Long nodeId, @NonNull InetAddress hostname, int port) {

    /**
     * Constructs a NetworkEndpoint and validates its parameters.
     *
     * @param nodeId   the node ID
     * @param hostname the hostname or IP address
     * @param port     the port number
     * @throws NullPointerException     if nodeId or hostname is null
     * @throws IllegalArgumentException if port is not in the range [0, 65535]
     */
    public NetworkEndpoint {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(hostname, "hostname must not be null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in the range [0, 65535]");
        }
    }
}
