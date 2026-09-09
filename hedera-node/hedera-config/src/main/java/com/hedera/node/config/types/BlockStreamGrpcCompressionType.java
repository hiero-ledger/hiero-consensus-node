// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

/**
 * Enumeration of the possible compression algorithms to use for streaming to block nodes over gRPC.
 */
public enum BlockStreamGrpcCompressionType {
    /**
     * Specifies that no compression should be used.
     */
    NONE,
    /**
     * Specifies that ZSTD compression should be used.
     */
    ZSTD
}
