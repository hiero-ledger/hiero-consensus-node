// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.BlockItemSet;
import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages the state of blocks being streamed to block nodes.
 * This class is responsible for maintaining the block states and providing methods for adding items to blocks
 * and creating requests.
 */
public class BlockStreamStateManager {
    private static final Logger logger = LogManager.getLogger(BlockStreamStateManager.class);

    /**
     * Buffer that stores recent blocks. This buffer is unbounded, however when opening a new block the buffer will be
     * pruned. Generally speaking, the buffer should contain only blocks that are recent (that is within the configured
     * {@link BlockStreamConfig#blockBufferTtl() TTL}) and have yet to be acknowledged. There may be cases where older
     * blocks still exist in the buffer if they are unacknowledged, but once they are acknowledged they will be pruned
     * the next time {@link #openBlock(long)} is invoked. {@link #isBufferSaturated()} can be used to check if the
     * buffer contains unacknowledged old blocks.
     */
    private final BlockingQueue<BlockStateHolder> blockBuffer = new LinkedBlockingQueue<>();

    private final ConcurrentMap<Long, BlockStateHolder> blockStatesById = new ConcurrentHashMap<>();
    /**
     * Flag to indicate if the buffer contains blocks that have expired but are still unacknowledged.
     */
    private final AtomicBoolean isBufferSaturated = new AtomicBoolean(false);

    private long blockNumber = 0;
    private final ConfigProvider configProvider;

    // Reference to the connection manager for notifications
    private BlockNodeConnectionManager blockNodeConnectionManager;

    /**
     * Creates a new BlockStreamStateManager with the given configuration.
     *
     * @param configProvider the configuration provider
     */
    public BlockStreamStateManager(@NonNull final ConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    /**
     * @return the current batch size for block items
     */
    private int blockItemBatchSize() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .blockItemBatchSize();
    }

    /**
     * @return the current TTL for items in the block buffer
     */
    private Duration blockBufferTtl() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .blockBufferTtl();
    }

    /**
     * @return true if the block buffer has blocks that are expired but unacknowledged, else false
     */
    public boolean isBufferSaturated() {
        return isBufferSaturated.get();
    }

    /**
     * Sets the block node connection manager for notifications.
     *
     * @param blockNodeConnectionManager the block node connection manager
     */
    public void setBlockNodeConnectionManager(@NonNull final BlockNodeConnectionManager blockNodeConnectionManager) {
        this.blockNodeConnectionManager =
                requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
    }

    /**
     * Opens a new block with the given block number. This will also attempt to prune older blocks from the buffer.
     *
     * @param blockNumber the block number
     * @throws IllegalArgumentException if the block number is negative
     */
    public void openBlock(final long blockNumber) {
        if (blockNumber < 0) throw new IllegalArgumentException("Block number must be non-negative");

        if (this.blockNumber >= blockNumber) {
            throw new IllegalStateException("Attempted to open a new block with number " + blockNumber
                    + ", but a block with the same or later number (latest: " + this.blockNumber
                    + ") has already been opened");
        }

        // Create a new block state
        final BlockState blockState = new BlockState(blockNumber, new ArrayList<>());
        final BlockStateHolder holder = new BlockStateHolder(blockState);
        blockBuffer.add(holder);
        blockStatesById.put(blockNumber, holder);
        this.blockNumber = blockNumber;
        blockNodeConnectionManager.openBlock(blockNumber);

        pruneBuffer();
    }

    /**
     * Prunes the block buffer by removing blocks that have been acknowledged and exceeded the configured TTL. By doing
     * this, we also inadvertently can know if buffer is "saturated" due to blocks not being acknowledged in a timely
     * manner.
     */
    private void pruneBuffer() {
        final Duration ttl = blockBufferTtl();
        final Instant cutoffInstant = Instant.now().minus(ttl);
        final Iterator<BlockStateHolder> it = blockBuffer.iterator();

        while (it.hasNext()) {
            final BlockStateHolder holder = it.next();
            if (holder.createdTimestamp.isBefore(cutoffInstant)) {
                if (holder.isAcked.get()) {
                    blockStatesById.remove(holder.block.blockNumber());
                    it.remove();
                } else {
                    logger.warn(
                            "Buffer cannot be pruned; block (#{}, created={}) is older than TTL threshold "
                                    + "(ttl={}, cutoff={}), but the block has not been acknowledged",
                            holder.block.blockNumber(),
                            holder.createdTimestamp,
                            ttl,
                            cutoffInstant);
                    isBufferSaturated.set(true);
                    return;
                }
            } else {
                // we've encountered a block whose created timestamp is within the TTL window and since the blocks
                // are ordered chronologically, we can assume all remaining blocks are still valid and thus we can
                // escape early
                break;
            }
        }

        isBufferSaturated.set(false);
    }

    /**
     * Adds a new item to the current block.
     *
     * @param blockNumber the block number
     * @param blockItem the block item to add
     * @throws IllegalStateException if no block is currently open
     */
    public void addItem(final long blockNumber, @NonNull final BlockItem blockItem) {
        requireNonNull(blockItem, "blockItem must not be null");
        final BlockState blockState = getBlockState(blockNumber);
        if (blockState == null) {
            throw new IllegalStateException("Block state not found for block " + blockNumber);
        }

        blockState.items().add(blockItem);

        // If we have enough items, create a new request
        createRequestFromCurrentItems(blockState, false);
    }

    /**
     * Creates zero, one, or many {@link PublishStreamRequest} for the current pending items in the block. This is a
     * batched operation and thus a new {@link PublishStreamRequest} will be created only if there are enough pending
     * items to fill the batch. If {@code force} is true, then a new {@link PublishStreamRequest} will be created even
     * if the number of pending items is fewer than the configured batch size.
     *
     * @param blockState the block that may contain pending items
     * @param force true if a new {@link PublishStreamRequest} should be created regardless of if there are enough items
     *              to create a batch, otherwise if false, then a new {@link PublishStreamRequest} will only be created
     *              if there are enough items to complete a full batch
     */
    public void createRequestFromCurrentItems(@NonNull final BlockState blockState, final boolean force) {
        requireNonNull(blockState, "blockState is required");

        if (blockState.items().isEmpty()) {
            return;
        }

        final int cfgBatchSize = blockItemBatchSize();
        final int batchSize = Math.max(1, cfgBatchSize); // if cfgBatchSize is less than 1, set the size to 1
        final List<BlockItem> items = new ArrayList<>(batchSize);

        if (force || blockState.items().size() >= batchSize) {
            final Iterator<BlockItem> it = blockState.items().iterator();
            while (it.hasNext() && items.size() != batchSize) {
                items.add(it.next());
                it.remove();
            }
        } else {
            return;
        }

        // Create BlockItemSet by adding all items at once
        final BlockItemSet itemSet = BlockItemSet.newBuilder().blockItems(items).build();

        // Create the request and add it to the list
        final PublishStreamRequest request =
                PublishStreamRequest.newBuilder().blockItems(itemSet).build();

        blockState.requests().add(request);
        logger.debug(
                "Added request to block {} - request count now: {}",
                blockState.blockNumber(),
                blockState.requests().size());

        // Notify the connection manager
        blockNodeConnectionManager.notifyConnectionsOfNewRequest();

        if ((!blockState.items().isEmpty() && force) || blockState.items().size() >= batchSize) {
            // another request can be created
            createRequestFromCurrentItems(blockState, force);
        }
    }

    /**
     * Closes the current block and marks it as complete.
     * @param blockNumber the block number
     * @throws IllegalStateException if no block is currently open
     */
    public void closeBlock(final long blockNumber) {
        final BlockState blockState = getBlockState(blockNumber);
        if (blockState == null) {
            throw new IllegalStateException("Block state not found for block " + blockNumber);
        }

        // Mark the block as complete
        blockState.setComplete();
        createRequestFromCurrentItems(blockState, true);

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
    public BlockState getBlockState(final long blockNumber) {
        final BlockStateHolder holder = blockStatesById.get(blockNumber);
        return holder != null ? holder.block : null;
    }

    /**
     * Retrieves if the specified block has been marked as acknowledged.
     *
     * @param blockNumber the block to check
     * @return true if the block has been acknowledged, else false
     * @throws IllegalArgumentException if the specified block is not found
     */
    public boolean isAcked(final long blockNumber) {
        final BlockStateHolder holder = blockStatesById.get(blockNumber);
        if (holder == null) {
            throw new IllegalArgumentException("Block " + blockNumber + " not found");
        }

        return holder.isAcked.get();
    }

    /**
     * Creates a new request from the current items in the block prior to BlockProof if there are any.
     * @param blockNumber the block number
     */
    public void streamPreBlockProofItems(final long blockNumber) {
        final BlockState blockState = getBlockState(blockNumber);
        if (blockState == null) {
            throw new IllegalStateException("Block state not found for block " + blockNumber);
        }

        // If there are remaining items we will create a request from them while the BlockProof is pending
        if (!blockState.items().isEmpty()) {
            logger.debug(
                    "Prior to BlockProof, creating request from items in block {} size {}",
                    blockNumber,
                    blockState.items().size());
            createRequestFromCurrentItems(blockState, true);
        }
    }

    /**
     * Marks all blocks up to and including the specified block as being acknowledged.
     *
     * @param blockNumber the block number to mark acknowledged up to and including
     */
    public void setAckWatermark(final long blockNumber) {
        for (final BlockStateHolder holder : blockBuffer) {
            if (holder.block.blockNumber() > blockNumber) {
                break;
            }

            holder.isAcked.set(true);
        }
    }

    /**
     * Gets the current block number.
     *
     * @return the current block number
     */
    public long getBlockNumber() {
        return blockNumber;
    }

    private record BlockStateHolder(
            @NonNull Instant createdTimestamp, @NonNull BlockState block, @NonNull AtomicBoolean isAcked) {

        public BlockStateHolder(@NonNull final BlockState block) {
            this(Instant.now(), block, new AtomicBoolean(false));
        }
    }
}
