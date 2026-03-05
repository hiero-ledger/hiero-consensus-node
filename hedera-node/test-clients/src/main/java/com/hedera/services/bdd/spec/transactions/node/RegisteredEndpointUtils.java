// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.node;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Convenience factories for building {@link RegisteredServiceEndpoint} instances in tests.
 */
public final class RegisteredEndpointUtils {

    private RegisteredEndpointUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Block-node endpoint addressed by domain name.
     */
    public static RegisteredServiceEndpoint blockNodeEndpoint(
            @NonNull final String domain,
            final int port,
            @NonNull final RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi api) {
        return RegisteredServiceEndpoint.newBuilder()
                .setDomainName(domain)
                .setPort(port)
                .setBlockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .setEndpointApi(api)
                        .build())
                .build();
    }

    /**
     * Block-node endpoint addressed by raw IP bytes.
     */
    public static RegisteredServiceEndpoint blockNodeEndpointIp(
            @NonNull final byte[] ip,
            final int port,
            @NonNull final RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi api) {
        return RegisteredServiceEndpoint.newBuilder()
                .setIpAddress(ByteString.copyFrom(ip))
                .setPort(port)
                .setBlockNode(RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                        .setEndpointApi(api)
                        .build())
                .build();
    }

    /**
     * Mirror-node endpoint addressed by domain name.
     */
    public static RegisteredServiceEndpoint mirrorNodeEndpoint(@NonNull final String domain, final int port) {
        return RegisteredServiceEndpoint.newBuilder()
                .setDomainName(domain)
                .setPort(port)
                .setMirrorNode(RegisteredServiceEndpoint.MirrorNodeEndpoint.getDefaultInstance())
                .build();
    }

    /**
     * RPC-relay endpoint addressed by domain name.
     */
    public static RegisteredServiceEndpoint rpcRelayEndpoint(@NonNull final String domain, final int port) {
        return RegisteredServiceEndpoint.newBuilder()
                .setDomainName(domain)
                .setPort(port)
                .setRpcRelay(RegisteredServiceEndpoint.RpcRelayEndpoint.getDefaultInstance())
                .build();
    }
}
