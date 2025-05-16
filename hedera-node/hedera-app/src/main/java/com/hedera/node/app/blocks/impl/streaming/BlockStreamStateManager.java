// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.PublishStreamRequest;

/**
 * Manages the state of blocks being streamed to block nodes.
 * This class is responsible for maintaining the block states and providing methods for adding items to blocks
 * and creating requests.
 */
public class BlockStreamStateManager {
    private static final Logger logger = LogManager.getLogger(BlockStreamStateManager.class);

    private final Map<Long, BlockState> blockStates = new ConcurrentHashMap<>();
    private final int blockItemBatchSize;
    private long blockNumber = 0;

    // Reference to the connection manager for notifications
    private BlockNodeConnectionManager blockNodeConnectionManager;

    /**
     * Creates a new BlockStreamStateManager with the given configuration.
     *
     * @param configProvider the configuration provider
     */
    public BlockStreamStateManager(@NonNull final ConfigProvider configProvider) {
        this.blockItemBatchSize = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .blockItemBatchSize();
    }

    /**
     * Sets the block node connection manager for notifications.
     *
     * @param blockNodeConnectionManager the block node connection manager
     */
    public void setBlockNodeConnectionManager(@NonNull BlockNodeConnectionManager blockNodeConnectionManager) {
        this.blockNodeConnectionManager =
                requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
    }

    /**
     * Opens a new block with the given block number.
     *
     * @param blockNumber the block number
     * @throws IllegalArgumentException if the block number is negative
     */
    public void openBlock(long blockNumber) {
        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");
        // Create a new block state
        blockStates.put(blockNumber, new BlockState(blockNumber, new ArrayList<>()));
        this.blockNumber = blockNumber;

        blockNodeConnectionManager.openBlock(blockNumber);
    }

    /**
     * Adds a new item to the current block.
     *
     * @param blockNumber the block number
     * @param blockItem the block item to add
     * @throws IllegalStateException if no block is currently open
     */
    public void addItem(final long blockNumber, @NonNull BlockItem blockItem) {
        requireNonNull(blockItem, "blockItem must not be null");
        BlockState blockState = getBlockState(blockNumber);
        if (blockState == null) {
            throw new IllegalStateException("Block state not found for block " + blockNumber);
        }

        blockState.items().add(blockItem);

        // If we have enough items, create a new request
        if (blockState.items().size() >= blockItemBatchSize) {
            createRequestFromCurrentItems(blockState);
        }
    }

    /**
     * Creates a new PublishStreamRequest from the current items in the block.
     */
    public void createRequestFromCurrentItems(@NonNull BlockState blockState) {
        // Create BlockItemSet by adding all items at once
        final BlockItemSet itemSet = BlockItemSet.newBuilder()
                .blockItems(new ArrayList<>(blockState.items()))
                .build();

        // Create the request and add it to the list
        final PublishStreamRequest request =
                PublishStreamRequest.newBuilder().blockItems(itemSet).build();

        blockState.requests().add(request);
        logger.debug(
                "Added request to block {} - request count now: {}",
                blockState.blockNumber(),
                blockState.requests().size());

        // Clear the items list
        blockState.items().clear();

        // Notify the connection manager
        blockNodeConnectionManager.notifyConnectionsOfNewRequest();
    }

    /**
     * Closes the current block and marks it as complete.
     * @param blockNumber the block number
     * @throws IllegalStateException if no block is currently open
     */
    public void closeBlock(final long blockNumber) {
        BlockState blockState = getBlockState(blockNumber);
        if (blockState == null) {
            throw new IllegalStateException("Block state not found for block " + blockNumber);
        }

        // Check if there are remaining items
        if (!blockState.items().isEmpty()) {
            logger.debug(
                    "Creating request from remaining items in block {} size {}",
                    blockNumber,
                    blockState.items().size());
            createRequestFromCurrentItems(blockState);
        }

        // Mark the block as complete
        blockState.setComplete();
        logger.debug(
                "Closed block in BlockStreamStateManager {} - request count: {}",
                blockNumber,
                blockState.requests().size());
    }

    /**
     * Gets the block state for the given block number.
     *
     * @param blockNumber the block number
     * @return the block state, or null if no block state exists for the given block number
     */
    public BlockState getBlockState(long blockNumber) {
        return blockStates.get(blockNumber);
    }

    /**
     * Creates a new request from the current items in the block prior to BlockProof if there are any.
     * @param blockNumber the block number
     */
    public void streamPreBlockProofItems(long blockNumber) {
        BlockState blockState = getBlockState(blockNumber);
        if (blockState == null) {
            throw new IllegalStateException("Block state not found for block " + blockNumber);
        }

        // If there are remaining items we will create a request from them while the BlockProof is pending
        if (!blockState.items().isEmpty()) {
            logger.debug(
                    "Prior to BlockProof, creating request from items in block {} size {}",
                    blockNumber,
                    blockState.items().size());
            createRequestFromCurrentItems(blockState);
        }
    }

    /**
     * Removes all block states with block numbers less than or equal to the given block number. This operation is
     * thread-safe.
     *
     * @param blockNumber the block number
     */
    public void removeBlockStatesUpTo(long blockNumber) {
        // Use keySet().removeIf for atomic removal of multiple entries
        blockStates.keySet().removeIf(key -> key <= blockNumber);
        logger.debug("Removed block states up to and including block {}", blockNumber);
    }

    /**
     * Gets the current block number.
     *
     * @return the current block number
     */
    public long getBlockNumber() {
        return blockNumber;
    }
}
