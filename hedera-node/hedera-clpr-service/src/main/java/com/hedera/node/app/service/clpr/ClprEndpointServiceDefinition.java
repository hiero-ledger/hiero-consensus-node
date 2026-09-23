// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsRequest;
import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsResponse;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines the CLPR endpoint-to-endpoint gRPC service. This service handles peer-to-peer
 * sync calls between CLPR endpoints on different ledger networks.
 *
 * <p>Unlike standard HAPI services that use {@code Transaction}/{@code Query} as request types,
 * this service uses {@link ClprSyncPayload} as both request and response types.
 */
@SuppressWarnings("java:S6548")
public final class ClprEndpointServiceDefinition implements RpcServiceDefinition {
    /** The singleton instance of this class. */
    public static final ClprEndpointServiceDefinition INSTANCE = new ClprEndpointServiceDefinition();

    /** The fully qualified gRPC service name, shared by {@link #basePath()} and the method-name constants below. */
    public static final String SERVICE_NAME = "proto.ClprEndpointService";

    /**
     * The full gRPC method name for the bidirectional-streaming {@code streamingSync} RPC.
     *
     * <p>Deliberately a bare constant rather than an entry in {@link #methods()}: that {@link Set} backs
     * {@code NettyGrpcServerManager}'s auto-registration, which dispatches purely on request type and
     * hardcodes {@code MethodType.UNARY}. A streaming method added there would be silently wired to the
     * query workflow — it compiles, it starts, and it misbehaves only when called. Both the outbound
     * client and the server-side streaming registration build their method descriptors from
     * this constant instead, so the two ends cannot drift apart.
     */
    public static final String STREAMING_SYNC_FULL_METHOD_NAME = SERVICE_NAME + "/streamingSync";

    private static final Set<RpcMethodDefinition<?, ?>> methods = Set.of(
            new RpcMethodDefinition<>("sync", ClprSyncPayload.class, ClprSyncPayload.class),
            new RpcMethodDefinition<>(
                    "discoverEndpoints", ClprDiscoverEndpointsRequest.class, ClprDiscoverEndpointsResponse.class));

    private ClprEndpointServiceDefinition() {
        // Forbid instantiation
    }

    @Override
    @NonNull
    public String basePath() {
        return SERVICE_NAME;
    }

    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
