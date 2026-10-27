// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

/**
 * Decides whether a peer endpoint from a verifier-returned endpoint manifest may be stored and dialed.
 *
 * <p>A Channel's verifier is chosen by whoever completes the Channel, so the endpoints it returns are untrusted, and
 * every node dials them on each sync tick. An endpoint is allowed only if:
 * <ul>
 *     <li>its port is in 1–65535;</li>
 *     <li>its address is an IP literal, never a DNS name that every node would resolve: a dotted-quad IPv4 address in
 *     canonical form, or an IPv6 address without a zone id that does not embed an IPv4 address;</li>
 *     <li>its address is publicly routable. Loopback, private, unique-local, link-local (including the
 *     {@code 169.254.169.254} and {@code fd00:ec2::254} cloud metadata addresses), shared (CGNAT), multicast,
 *     reserved, broadcast, unspecified, and NAT64 addresses reach services inside a node's own network.</li>
 * </ul>
 * Development and test networks, whose peers run on loopback or private addresses, can waive the last rule with
 * {@link ClprConfig#allowNonPublicPeerEndpoints()}.
 */
public final class ClprPeerEndpointPolicy {

    private static final int MAX_PORT = 65_535;

    private ClprPeerEndpointPolicy() {}

    /**
     * Returns whether every endpoint in the list is allowed.
     *
     * @param endpoints the endpoints
     * @param allowNonPublic whether non-public addresses are allowed
     * @return whether all are allowed
     */
    public static boolean allAllowed(@NonNull final List<ClprEndpoint> endpoints, final boolean allowNonPublic) {
        requireNonNull(endpoints);
        return endpoints.stream().allMatch(endpoint -> isAllowed(endpoint, allowNonPublic));
    }

    /**
     * Returns whether the endpoint may be stored and dialed.
     *
     * @param endpoint the endpoint
     * @param allowNonPublic whether non-public addresses are allowed
     * @return whether it is allowed
     */
    public static boolean isAllowed(@NonNull final ClprEndpoint endpoint, final boolean allowNonPublic) {
        final var serviceEndpoint = requireNonNull(endpoint).serviceEndpoint();
        return serviceEndpoint != null
                && isAllowed(serviceEndpoint.ipAddress(), serviceEndpoint.port(), allowNonPublic);
    }

    /**
     * Returns whether the address and port may be dialed.
     *
     * @param ipAddress the address, which must be an IP literal
     * @param port the port
     * @param allowNonPublic whether non-public addresses are allowed
     * @return whether they are allowed
     */
    public static boolean isAllowed(@NonNull final String ipAddress, final int port, final boolean allowNonPublic) {
        requireNonNull(ipAddress);
        if (port < 1 || port > MAX_PORT) {
            return false;
        }
        final InetAddress address;
        try {
            address = InetAddress.ofLiteral(ipAddress);
        } catch (final IllegalArgumentException notALiteral) {
            return false;
        }
        final boolean isIpv6Literal = ipAddress.indexOf(':') >= 0;
        final boolean isCanonicalLiteral = isIpv6Literal
                // An IPv4-mapped literal parses to an Inet4Address
                ? address instanceof Inet6Address && ipAddress.indexOf('%') < 0
                // Rejects short and decimal forms such as 127.1 and 2130706433
                : address instanceof Inet4Address && address.getHostAddress().equals(ipAddress);
        return isCanonicalLiteral && (allowNonPublic || isPublic(address));
    }

    private static boolean isPublic(@NonNull final InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        final byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            final int first = bytes[0] & 0xff;
            final int second = bytes[1] & 0xff;
            final boolean thisNetwork = first == 0;
            final boolean sharedAddressSpace = first == 100 && (second & 0xc0) == 64;
            // 240.0.0.0/4, including the 255.255.255.255 broadcast address
            final boolean reserved = first >= 240;
            return !thisNetwork && !sharedAddressSpace && !reserved;
        }
        // fc00::/7 unique local addresses
        final boolean uniqueLocal = (bytes[0] & 0xfe) == 0xfc;
        // 64:ff9b::/96 and 64:ff9b:1::/48, which translate to (possibly private) IPv4 addresses
        final boolean nat64 =
                bytes[0] == 0 && bytes[1] == 0x64 && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b;
        return !uniqueLocal && !nat64 && !isIpv4Compatible(bytes);
    }

    /** Returns whether the IPv6 address is in the deprecated {@code ::/96} range, which embeds an IPv4 address. */
    private static boolean isIpv4Compatible(@NonNull final byte[] bytes) {
        for (int i = 0; i < 12; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
