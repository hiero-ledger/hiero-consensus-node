package com.hedera.node.app.grpc.impl.usage;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.MethodDescriptor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RpcNames {
    /**
     * Cache used to hold mappings between long-form RPC names to formatted service and method names.
     * <p>For example, a mapping may be:
     * {@code "proto.MyService/commitTransaction" -> RpcName("MyService", "CommitTransaction")}
     */
    private static final ConcurrentMap<String, RpcName> rpcNameCache = new ConcurrentHashMap<>(100);

    private RpcNames() {
        // No-op
    }

    public static @NonNull RpcName from(@NonNull final MethodDescriptor<?, ?> descriptor) {
        requireNonNull(descriptor, "descriptor is required");

        RpcName rpcName = rpcNameCache.get(descriptor.getFullMethodName());

        if (rpcName != null) {
            // we have it cached, escape early
            return rpcName;
        }

        String svcName = descriptor.getServiceName();
        String methodName = descriptor.getBareMethodName();

        if (svcName == null || svcName.isBlank()) {
            svcName = "Unknown";
        } else if (svcName.startsWith("proto.")) {
            // remove "proto." from service name
            svcName = svcName.substring(6);
        }

        if (methodName == null || methodName.isBlank()) {
            methodName = "Unknown";
        } else {
            // capitalize first letter of method
            methodName = Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
        }

        // combine and store
        rpcName = new RpcName(svcName, methodName);
        rpcNameCache.put(descriptor.getFullMethodName(), rpcName);

        return rpcName;
    }
}
