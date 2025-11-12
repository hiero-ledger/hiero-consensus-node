// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;

/**
 * Configuration for block node protocols.
 * @param blockNodeConfig the block node configuration
 * @param http2ClientProtocolConfig the HTTP/2 client protocol configuration
 * @param grpcClientProtocolConfig the gRPC client protocol configuration
 * @param maxMessageSizeBytes the maximum message size in bytes
 */
public record BlockNodeProtocolConfig(
        @NonNull BlockNodeConfig blockNodeConfig,
        @Nullable Http2ClientProtocolConfig http2ClientProtocolConfig,
        @Nullable GrpcClientProtocolConfig grpcClientProtocolConfig,
        @Nullable Integer maxMessageSizeBytes) {
    public BlockNodeProtocolConfig {
        requireNonNull(blockNodeConfig);
    }
}
