package com.hedera.node.app.grpc.impl.usage;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;

public record RpcName(@NonNull String serviceName, @NonNull String methodName) {
    public RpcName {
        requireNonNull(serviceName, "serviceName is required");
        requireNonNull(methodName, "methodName is required");
    }
}
