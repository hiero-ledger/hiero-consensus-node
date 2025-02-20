// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

/**
 * Initially we will write block streams to files, but in the next phases we will support writing
 * them to a gRPC stream.
 */
public enum BlockStreamWriterMode {
    /**
     * Write block streams to a gRPC stream.
     */
    GRPC,
    /**
     * Write block streams to files.
     */
    FILE
}
