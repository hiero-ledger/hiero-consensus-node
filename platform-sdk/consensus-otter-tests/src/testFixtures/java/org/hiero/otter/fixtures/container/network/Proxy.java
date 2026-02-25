// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a proxy configuration for network communication.
 *
 * @param name the name of the proxy
 * @param listen the address on which the proxy listens for incoming connections
 * @param upstream the address to which the proxy forwards requests
 * @param enabled whether the proxy is enabled or not
 */
public record Proxy(@NonNull String name, @NonNull String listen, @NonNull String upstream, boolean enabled) {

    /**
     * Constructs a new Proxy instance.
     *
     * @param name the name of the proxy
     * @param listen the address on which the proxy listens for incoming connections
     * @param upstream the address to which the proxy forwards requests
     * @param enabled whether the proxy is enabled or not
     */
    public Proxy {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Proxy name cannot be blank");
        }
        if (listen.isBlank()) {
            throw new IllegalArgumentException("Listen address cannot be blank");
        }
        if (upstream.isBlank()) {
            throw new IllegalArgumentException("Upstream address cannot be blank");
        }
    }

    public Proxy withEnabled(final boolean enabled) {
        return new Proxy(name, listen, upstream, enabled);
    }
}
