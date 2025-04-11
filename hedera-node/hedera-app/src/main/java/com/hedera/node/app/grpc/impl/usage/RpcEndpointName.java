// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.usage;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.MethodDescriptor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Record that represents an RPC endpoint.
 *
 * @param serviceName the service name associated with the endpoint
 * @param methodName the method name associated with the endpoint
 */
public record RpcEndpointName(@NonNull String serviceName, @NonNull String methodName) {
    private static final String UNKNOWN = "Unknown";

    /**
     * Cache used to hold mappings between long-form RPC names to formatted service and method names.
     * <p>For example, a mapping may be:
     * {@code "proto.MyService/commitTransaction" -> RpcName("MyService", "CommitTransaction")}
     */
    private static final ConcurrentMap<String, RpcEndpointName> rpcNameCache = new ConcurrentHashMap<>(100);

    public RpcEndpointName {
        requireNonNull(serviceName, "serviceName is required");
        requireNonNull(methodName, "methodName is required");
    }

    /**
     * Retrieves a sanitized RPC endpoint name based on the RPC endpoint being invoked. This method will strip any
     * leading "proto." from the service name and will additionally ensure the first letter of the method name will be
     * capitalized.
     *
     * @param descriptor the gRPC method descriptor to retrieve which endpoint is invoked
     * @return a sanitized RPC endpoint name
     */
    public static @NonNull RpcEndpointName from(@NonNull final MethodDescriptor<?, ?> descriptor) {
        requireNonNull(descriptor, "descriptor is required");

        RpcEndpointName rpcEndpointName = rpcNameCache.get(descriptor.getFullMethodName());

        if (rpcEndpointName != null) {
            // we have it cached, escape early
            return rpcEndpointName;
        }

        String svcName = descriptor.getServiceName();
        String methodName = descriptor.getBareMethodName();

        if (svcName == null || svcName.isBlank()) {
            svcName = UNKNOWN;
        } else if (svcName.startsWith("proto.")) {
            // remove "proto." from service name
            svcName = svcName.substring(6);
        }

        if (methodName == null || methodName.isBlank()) {
            methodName = UNKNOWN;
        } else {
            // capitalize first letter of method
            methodName = Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
        }

        // combine and store
        rpcEndpointName = new RpcEndpointName(svcName, methodName);
        rpcNameCache.put(descriptor.getFullMethodName(), rpcEndpointName);

        return rpcEndpointName;
    }
}
