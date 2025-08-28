// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A block item writer implementation that streams blocks to Block Nodes using gRPC bidirectional streaming.
 * This writer interfaces with {@link BlockBufferService} to manage block states and coordinates
 * the streaming of block items.
 * @see BlockBufferService
 * @see BlockNodeConnection
 */
public class GrpcBlockItemWriter implements BlockItemWriter {
    private static final Logger logger = LogManager.getLogger(GrpcBlockItemWriter.class);
    private final BlockBufferService blockBufferService;
    private long blockNumber;

    /**
     * Construct a new GrpcBlockItemWriter.
     *
     * @param blockBufferService the block stream state manager that maintains the state of the block
     */
    public GrpcBlockItemWriter(@NonNull final BlockBufferService blockBufferService) {
        this.blockBufferService = requireNonNull(blockBufferService, "blockBufferService must not be null");
    }

    /**
     * Opens a new block for writing with the specified block number. This initializes the block state
     * in the state manager and prepares for receiving block items.
     *
     * @param blockNumber the sequence number of the block to open
     */
    @Override
    public void openBlock(long blockNumber) {
        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");
        this.blockNumber = blockNumber;
        blockBufferService.openBlock(blockNumber);
        logger.debug("Started new block in GrpcBlockItemWriter {}", blockNumber);
    }

    /**
     * Writes a protocol buffer formatted block item to the current block's state.
     *
     * @param blockItem the block item to write
     */
    @Override
    public void writePbjItem(@NonNull BlockItem blockItem) {
        requireNonNull(blockItem, "blockItem must not be null");
        blockBufferService.addItem(blockNumber, blockItem);
    }

    /**
     * This operation is not supported by the gRPC implementation as it expects protocol buffer.
     * @param bytes the serialized item to write
     */
    @Override
    public void writeItem(@NonNull byte[] bytes) {
        throw new UnsupportedOperationException("writeItem is not supported in this implementation");
    }

    @Override
    public void writePbjItemAndBytes(@NonNull final BlockItem item, @NonNull Bytes bytes) {
        writePbjItem(item);
    }

    /**
     * Closes the current block and marks it as complete in the state manager.
     */
    @Override
    public void closeCompleteBlock() {
        blockBufferService.closeBlock(blockNumber);
        logger.debug("Closed block in GrpcBlockItemWriter {}", blockNumber);
    }

    /**
     * No-op implementation as pending proofs are handled differently in the gRPC streaming context.
     */
    @Override
    public void flushPendingBlock(@NonNull final PendingProof pendingProof) {
        // No-op
    }
}
