// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines the CLPR endpoint-to-endpoint gRPC service. This service handles peer-to-peer
 * sync calls between CLPR endpoints on different ledger networks.
 *
 * <p>Unlike standard HAPI services that use {@code Transaction}/{@code Query} as request types, its {@code sync}
 * RPC streams {@link ClprStreamingSyncPayload} in both directions.
 */
@SuppressWarnings("java:S6548")
public final class ClprEndpointServiceDefinition implements RpcServiceDefinition {
    /** The singleton instance of this class. */
    public static final ClprEndpointServiceDefinition INSTANCE = new ClprEndpointServiceDefinition();

    /** The fully qualified gRPC service name, shared by {@link #basePath()} and the method-name constants below. */
    public static final String SERVICE_NAME = "proto.ClprEndpointService";

    /**
     * The full gRPC method name of the {@code sync} RPC, as the CLPR spec names it: a bidirectional stream of
     * {@code ClprStreamingSyncPayload} in each direction, carrying the two-phase request/response exchange.
     *
     * <p>{@link #methods()} declares it so the gRPC server can find this service and route {@code sync} to the CLPR
     * listener, but {@code NettyGrpcServerManager} registers it by hand: its request-type-dispatched auto-registration
     * hardcodes {@code MethodType.UNARY}, and a streaming method wired as unary would answer the first message and
     * close. The outbound client and the server-side registration both build their method descriptors from this
     * constant, so the two ends cannot drift apart.
     */
    public static final String SYNC_FULL_METHOD_NAME = SERVICE_NAME + "/sync";

    private static final Set<RpcMethodDefinition<?, ?>> methods =
            Set.of(new RpcMethodDefinition<>("sync", ClprStreamingSyncPayload.class, ClprStreamingSyncPayload.class));

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
