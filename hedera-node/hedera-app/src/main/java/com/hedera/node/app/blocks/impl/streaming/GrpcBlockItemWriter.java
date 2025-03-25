// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Implements the bidirectional streaming RPC for the publishBlockStream rpc in BlockStreamService.
 */
public class GrpcBlockItemWriter implements BlockItemWriter {
    private static final Logger logger = LogManager.getLogger(GrpcBlockItemWriter.class);
    private final BlockNodeConnectionManager connectionManager;

    private final Map<Long, BlockState> blockStates = new ConcurrentHashMap<>();
    private volatile BlockState currentBlock;

    /**
     * Construct a new GrpcBlockItemWriter.
     *
     * @param connectionManager the connection manager for the gRPC block stream service
     */
    public GrpcBlockItemWriter(@NonNull final BlockNodeConnectionManager connectionManager) {
        this.connectionManager = requireNonNull(connectionManager, "connectionManager must not be null");
    }

    @Override
    public void openBlock(long blockNumber) {
        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");

        currentBlock = BlockState.from(blockNumber);
        blockStates.put(blockNumber, currentBlock);
        logger.debug("Started new block in GrpcBlockItemWriter {}", blockNumber);
    }

    @Override
    public void writePbjItem(@NonNull Bytes bytes) {
        if (currentBlock == null) {
            throw new IllegalStateException("Received block item before opening block");
        }
        currentBlock.itemBytes().add(bytes);
    }

    @Override
    public void writeItem(@NonNull byte[] bytes) {
        throw new UnsupportedOperationException("writeItem is not supported in this implementation");
    }

    @Override
    public void closeBlock() {
        if (currentBlock == null) {
            throw new IllegalStateException("Received close block before opening block");
        }
        final long blockNumber = currentBlock.blockNumber();

        try {
            BlockState block = blockStates.get(blockNumber);
            if (block == null) {
                logger.error("Could not find block state for block {}", blockNumber);
                return;
            }
            // Stream the block asynchronously
            connectionManager.startStreamingBlock(block);

            logger.debug("Closed block in GrpcBlockItemWriter {}", blockNumber);
            currentBlock = null;
        } finally {
            // Clean up the block state after streaming
            blockStates.remove(blockNumber);
        }
    }
}
