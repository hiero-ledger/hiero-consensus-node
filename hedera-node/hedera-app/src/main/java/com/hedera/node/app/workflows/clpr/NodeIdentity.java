// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.GrpcConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Represents the identity of a node in the network, containing configuration details
 * for gRPC and information about the current node. This class provides functionality
 * to verify if a given host and port correspond to the current node.
 *
 * @param grpcConfig The gRPC configuration for the node.
 * @param clprMtlsPort The node's dedicated CLPR mTLS port ({@code clpr.mtlsPort}), which a loopback host
 *                     also identifies as self when mTLS is enabled; {@code -1} disables it.
 * @param self Information about the current node, such as its endpoints.
 */
record NodeIdentity(
        @NonNull GrpcConfig grpcConfig,
        int clprMtlsPort,
        @Nullable NodeInfo self) {
    /** Delegating constructor for callers that don't advertise a CLPR mTLS port (sentinel {@code -1}). */
    NodeIdentity(@NonNull final GrpcConfig grpcConfig, @Nullable final NodeInfo self) {
        this(grpcConfig, -1, self);
    }
    /**
     * Returns true if the given host/port matches one of this node's own endpoints, so we
     * never attempt to sync with ourselves. CLPR's gRPC service shares the local gRPC
     * server port with HAPI, so a loopback host paired with our configured gRPC port is
     * unconditionally self — this guards against test/address-book setups where
     * {@code selfNodeInfo().hapiEndpoints()} carries a port that doesn't match the
     * actually-bound gRPC port. Falls back to comparing dotted-decimal
     * {@link ServiceEndpoint#ipAddressV4()} and {@link ServiceEndpoint#domainName()}
     * against the peer's {@code ip_address}/{@code port} pair. Per RFC 1035 the
     * domain-name comparison is case-insensitive and tolerates a trailing dot on
     * either side (FQDN form).
     */
    public boolean isSelf(@NonNull final String host, final int port) {
        if (isLoopback(host) && (port == grpcConfig.port() || port == grpcConfig.tlsPort() || port == clprMtlsPort)) {
            return true;
        }
        if (self == null) {
            return false;
        }
        for (final var ep : self.hapiEndpoints()) {
            if (ep.port() != port) {
                continue;
            }
            if (matchesIpAddressV4(ep, host)) {
                return true;
            }
            if (matchesDomainName(ep, host)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoopback(@NonNull final String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equals(host);
    }

    private static boolean matchesIpAddressV4(@NonNull final ServiceEndpoint ep, @NonNull final String host) {
        final var ipBytes = ep.ipAddressV4();
        if (ipBytes == null || ipBytes.length() != 4) {
            return false;
        }
        final var raw = ipBytes.toByteArray();
        final var dotted = (raw[0] & 0xFF) + "." + (raw[1] & 0xFF) + "." + (raw[2] & 0xFF) + "." + (raw[3] & 0xFF);
        return dotted.equals(host);
    }

    private static boolean matchesDomainName(@NonNull final ServiceEndpoint ep, @NonNull final String host) {
        final var domain = ep.domainName();
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        return stripTrailingDot(domain).equalsIgnoreCase(stripTrailingDot(host));
    }

    private static String stripTrailingDot(@NonNull final String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
