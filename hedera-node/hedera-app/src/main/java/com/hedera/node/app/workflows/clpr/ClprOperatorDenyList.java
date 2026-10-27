// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetAddress;
import java.util.Locale;

/**
 * The node operator's CLPR deny lists, {@link ClprConfig#deniedChannelIds()} and
 * {@link ClprConfig#deniedPeerEndpoints()}. They let an operator decline service to a Channel, halt it on this node,
 * or stop dialing an endpoint, without a consensus change. They affect only what this node does: the Channel stays in
 * state and other nodes keep serving it.
 */
final class ClprOperatorDenyList {

    private ClprOperatorDenyList() {}

    /**
     * Returns whether the operator declines to serve the Channel.
     *
     * @param config the CLPR configuration
     * @param channelId the Channel ID
     * @return whether it is denied
     */
    static boolean isChannelDenied(@NonNull final ClprConfig config, @NonNull final Bytes channelId) {
        requireNonNull(channelId);
        final var deniedIds = requireNonNull(config).deniedChannelIds();
        if (deniedIds.isEmpty()) {
            return false;
        }
        final var channelHex = channelId.toHex();
        return deniedIds.stream().anyMatch(id -> normalizedHex(id).equals(channelHex));
    }

    /**
     * Returns whether the operator never dials the endpoint, listed either by IP address (any port) or by
     * {@code ip:port} ({@code [ip]:port} for IPv6).
     *
     * @param config the CLPR configuration
     * @param endpoint the endpoint
     * @return whether it is denied
     */
    static boolean isEndpointDenied(@NonNull final ClprConfig config, @NonNull final ClprEndpoint endpoint) {
        final var denied = requireNonNull(config).deniedPeerEndpoints();
        final var serviceEndpoint = requireNonNull(endpoint).serviceEndpoint();
        if (denied.isEmpty() || serviceEndpoint == null) {
            return false;
        }
        final InetAddress address;
        try {
            address = InetAddress.ofLiteral(serviceEndpoint.ipAddress());
        } catch (final IllegalArgumentException invalidAddress) {
            return false;
        }
        final var port = serviceEndpoint.port();
        return denied.stream().anyMatch(entry -> matchesEndpoint(entry.trim(), address, port));
    }

    private static boolean matchesEndpoint(final String entry, final InetAddress address, final int port) {
        try {
            // A whole IP literal always means any port. In particular, unbracketed IPv6 must not have its final
            // component mistaken for a port; IPv6 entries with a port use [address]:port.
            return InetAddress.ofLiteral(entry).equals(address);
        } catch (final IllegalArgumentException notAnAddressOnlyEntry) {
            final int separator = entry.lastIndexOf(':');
            if (separator < 0) {
                return false;
            }
            final var addressPart = entry.substring(0, separator);
            if (addressPart.indexOf(':') >= 0 && !(addressPart.startsWith("[") && addressPart.endsWith("]"))) {
                return false;
            }
            try {
                final int deniedPort = Integer.parseInt(entry.substring(separator + 1));
                return deniedPort >= 1
                        && deniedPort <= 65_535
                        && deniedPort == port
                        && InetAddress.ofLiteral(addressPart).equals(address);
            } catch (final IllegalArgumentException invalidEntry) {
                return false;
            }
        }
    }

    @NonNull
    private static String normalizedHex(@NonNull final String id) {
        final var trimmed = id.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("0x") ? trimmed.substring(2) : trimmed;
    }
}
