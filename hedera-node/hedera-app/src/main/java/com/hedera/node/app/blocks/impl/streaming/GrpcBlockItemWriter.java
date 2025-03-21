// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Implements the bidirectional streaming RPC for the publishBlockStream rpc in BlockStreamService.
 */
public class GrpcBlockItemWriter implements BlockItemWriter {
    private static final Logger logger = LogManager.getLogger(GrpcBlockItemWriter.class);
    private final BlockNodeConnectionManager blockNodeConnectionManager;
    private final BlockStreamStateManager blockStreamStateManager;
    private long blockNumber;

    /**
     * Construct a new GrpcBlockItemWriter.
     *
     * @param blockNodeConnectionManager the block stream connection manager
     */
    public GrpcBlockItemWriter(
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager,
            @NonNull final BlockStreamStateManager blockStreamStateManager) {
        requireNonNull(blockStreamStateManager, "blockStreamStateManager must not be null");
        requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
        this.blockStreamStateManager = blockStreamStateManager;
        this.blockNodeConnectionManager =
                requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
    }

    @Override
    public void openBlock(long blockNumber) {
        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");
        this.blockNumber = blockNumber;
        blockNodeConnectionManager.openBlock(blockNumber);
        logger.debug("Started new block in GrpcBlockItemWriter {}", blockNumber);
    }

    @Override
    public void writePbjItem(@NonNull Bytes bytes) {
        requireNonNull(bytes, "bytes must not be null");
        blockStreamStateManager.addItem(blockNumber, bytes);
    }

    @Override
    public void writeItem(@NonNull byte[] bytes) {
        throw new UnsupportedOperationException("writeItem is not supported in this implementation");
    }

    @Override
    public void closeBlock() {
        blockStreamStateManager.closeBlock(blockNumber);
        logger.debug("Closed block in GrpcBlockItemWriter");
    }

    @Override
    public void performPreBlockProofActions() {
        blockStreamStateManager.performPreBlockProofActions(blockNumber);
    }
}
