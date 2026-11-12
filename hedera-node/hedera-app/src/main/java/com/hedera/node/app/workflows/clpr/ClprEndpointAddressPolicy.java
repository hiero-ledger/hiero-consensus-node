// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.google.common.net.InetAddresses;
import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

/** Node-local rules for outbound CLPR destinations. Parsing never performs a DNS lookup. */
final class ClprEndpointAddressPolicy {
    // Ordinary public unicast destinations exclude the special-purpose ranges in the IANA registries:
    // https://www.iana.org/assignments/iana-ipv4-special-registry/
    // https://www.iana.org/assignments/iana-ipv6-special-registry/
    private static final List<Cidr> NON_PUBLIC_IPV4 = List.of(
                    "0.0.0.0/8",
                    "10.0.0.0/8",
                    "100.64.0.0/10",
                    "127.0.0.0/8",
                    "169.254.0.0/16",
                    "172.16.0.0/12",
                    "192.0.0.0/24",
                    "192.0.2.0/24",
                    "192.88.99.0/24",
                    "192.168.0.0/16",
                    "198.18.0.0/15",
                    "198.51.100.0/24",
                    "203.0.113.0/24",
                    "224.0.0.0/3")
            .stream()
            .map(Cidr::parse)
            .toList();
    private static final Cidr PUBLIC_IPV6 = Cidr.parse("2000::/3");
    private static final List<Cidr> SPECIAL_IPV6 =
            List.of("2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20").stream()
                    .map(Cidr::parse)
                    .toList();

    private ClprEndpointAddressPolicy() {}

    /**
     * Checks a peer's numeric address and port, including any additional ranges configured by this node's operator.
     * The resolved result is also the address passed to the transport; no hostname is handed to a resolver later.
     */
    @NonNull
    static InetSocketAddress addressFor(
            @NonNull final String host, final int port, @NonNull final Set<String> additionalCidrs) {
        requireNonNull(host);
        requireNonNull(additionalCidrs);
        try {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            final var address = literal(host);
            // Validate every configured entry even when the peer itself is public.
            final var additional = additionalCidrs.stream().map(Cidr::parse).toList();
            final var bytes = address.getAddress();
            if (address.isAnyLocalAddress()
                    || address.isMulticastAddress()
                    || (bytes.length == 4 && ((bytes[0] & 0xff) == 0 || (bytes[0] & 0xff) >= 224))) {
                throw new IllegalArgumentException("address must identify a unicast destination");
            }
            if (!isPublic(bytes) && additional.stream().noneMatch(cidr -> cidr.contains(bytes))) {
                throw new IllegalArgumentException("address is outside the permitted peer endpoint ranges");
            }
            return new InetSocketAddress(address, port);
        } catch (final IllegalArgumentException e) {
            throw new ClientException("Invalid CLPR peer endpoint " + host + ":" + port + ": " + e.getMessage(), e);
        }
    }

    private static InetAddress literal(final String value) {
        if (value.indexOf('%') >= 0) {
            throw new IllegalArgumentException("scoped addresses are not supported");
        }
        return InetAddresses.forString(value);
    }

    private static boolean isPublic(final byte[] bytes) {
        if (bytes.length == 4) {
            return NON_PUBLIC_IPV4.stream().noneMatch(cidr -> cidr.contains(bytes));
        }
        return PUBLIC_IPV6.contains(bytes) && SPECIAL_IPV6.stream().noneMatch(cidr -> cidr.contains(bytes));
    }

    private record Cidr(byte[] network, int prefixLength) {
        private static Cidr parse(final String value) {
            final int separator = value.indexOf('/');
            if (separator <= 0 || separator != value.lastIndexOf('/')) {
                throw new IllegalArgumentException("expected an IP address and CIDR prefix length: " + value);
            }
            final var bytes = literal(value.substring(0, separator)).getAddress();
            final int prefixLength = Integer.parseInt(value.substring(separator + 1));
            if (prefixLength < 0 || prefixLength > bytes.length * Byte.SIZE) {
                throw new IllegalArgumentException("invalid CIDR prefix length: " + value);
            }
            return new Cidr(bytes, prefixLength);
        }

        private boolean contains(final byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            for (int i = 0, remaining = prefixLength; remaining > 0; i++, remaining -= Byte.SIZE) {
                final int mask = 0xff << (Byte.SIZE - Math.min(remaining, Byte.SIZE));
                if (((address[i] ^ network[i]) & mask) != 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
