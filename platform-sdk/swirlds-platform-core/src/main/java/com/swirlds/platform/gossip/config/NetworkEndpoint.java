// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.config;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetAddress;
import java.util.Objects;

public record NetworkEndpoint(@NonNull Long nodeId, @NonNull InetAddress hostname, int port) {
    public NetworkEndpoint {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(hostname, "hostname must not be null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in the range [0, 65535]");
        }
    }
}
