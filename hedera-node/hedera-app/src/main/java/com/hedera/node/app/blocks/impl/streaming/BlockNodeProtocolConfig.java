// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;

/**
 * Configuration for block node protocols.
 * @param http2ClientProtocolConfig the HTTP/2 client protocol configuration
 * @param grpcClientProtocolConfig the gRPC client protocol configuration
 * @param maxMessageSizeBytes the maximum message size in bytes
 */
public record BlockNodeProtocolConfig(
        @Nullable Http2ClientProtocolConfig http2ClientProtocolConfig,
        @Nullable GrpcClientProtocolConfig grpcClientProtocolConfig,
        @Nullable Integer maxMessageSizeBytes) {}
