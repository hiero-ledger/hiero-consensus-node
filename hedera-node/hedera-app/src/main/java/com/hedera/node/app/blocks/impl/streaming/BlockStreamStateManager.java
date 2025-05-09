// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.BlockItemSet;
import com.hedera.hapi.block.PublishStreamRequest;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
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
    private final BlockingQueue<BlockState> blockBuffer = new LinkedBlockingQueue<>();
    /**
     * Map for quickly looking up blocks by their ID/number. This will get pruned along with the buffer periodically.
     */
    private final ConcurrentMap<Long, BlockState> blockStatesById = new ConcurrentHashMap<>();
    /**
     * Flag to indicate if the buffer contains blocks that have expired but are still unacknowledged.
     */
    private final AtomicBoolean isBufferSaturated = new AtomicBoolean(false);
    /**
     * This tracks the highest block number that has been acknowledged by the connected block node. This is kept
     * separately instead of individual acknowledgement tracking on a per-block basis because it is possible that after
     * a block node reconnects, it (being the block node) may have processed blocks from another consensus node that are
     * newer than the blocks processed by this consensus node.
     */
    private final AtomicLong highestAckedBlockNumber = new AtomicLong(Long.MIN_VALUE);
    /**
     * Executor that is used to schedule buffer pruning and triggering backpressure if needed.
     */
    private final ScheduledExecutorService execSvc = Executors.newSingleThreadScheduledExecutor();
    /**
     * Global CompletableFuture reference that is used to apply backpressure via {@link #ensureNewBlocksPermitted()}. If
     * the completed future has a value of {@code true}, then it means that the buffer is no longer saturated and no
     * blocking/backpressure is needed. If the value is {@code false} then it means this future was completed but
     * another one took its place and backpressure is still enabled.
     */
    private static final AtomicReference<CompletableFuture<Boolean>> backpressureCompletableFutureRef =
            new AtomicReference<>();

    private long blockNumber = -1;
    private final ConfigProvider configProvider;

    // Reference to the connection manager for notifications
    private BlockNodeConnectionManager blockNodeConnectionManager;

    private final BlockStreamMetrics blockStreamMetrics;

    /**
     * Creates a new BlockStreamStateManager with the given configuration.
     *
     * @param configProvider the configuration provider
     * @param blockStreamMetrics metrics factory for block stream
     */
    public BlockStreamStateManager(
            @NonNull final ConfigProvider configProvider, @NonNull final BlockStreamMetrics blockStreamMetrics) {
        this.configProvider = configProvider;
        this.blockStreamMetrics = blockStreamMetrics;

        scheduleNextPruning();
    }

    /**
     * @return the interval in which the block buffer will be pruned (a duration of 0 means pruning is disabled)
     */
    private Duration blockBufferPruneInterval() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .blockBufferPruneInterval();
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
     * @return the block period duration (i.e. the amount of time a single block represents)
     */
    private Duration blockPeriod() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .blockPeriod();
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
            logger.error(
                    "Attempted to open a new block with number {}, but a block with the same or later number "
                            + "(latest: {}) has already been opened",
                    blockNumber,
                    this.blockNumber);
            throw new IllegalStateException("Attempted to open a new block with number " + blockNumber
                    + ", but a block with the same or later number (latest: " + this.blockNumber
                    + ") has already been opened");
        }

        // Create a new block state
        final BlockState blockState = new BlockState(blockNumber, new ArrayList<>());
        blockBuffer.add(blockState);
        blockStatesById.put(blockNumber, blockState);
        this.blockNumber = blockNumber;
        blockStreamMetrics.setProducingBlockNumber(blockNumber);
        blockNodeConnectionManager.openBlock(blockNumber);
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
    public @Nullable BlockState getBlockState(final long blockNumber) {
        return blockStatesById.get(blockNumber);
    }

    /**
     * Retrieves if the specified block has been marked as acknowledged.
     *
     * @param blockNumber the block to check
     * @return true if the block has been acknowledged, else false
     * @throws IllegalArgumentException if the specified block is not found
     */
    public boolean isAcked(final long blockNumber) {
        return highestAckedBlockNumber.get() >= blockNumber;
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
    public void setLatestAcknowledgedBlock(final long blockNumber) {
        final long highestBlock = highestAckedBlockNumber.updateAndGet(current -> Math.max(current, blockNumber));
        blockStreamMetrics.setLatestAcknowledgedBlockNumber(highestBlock);
    }

    /**
     * Gets the current block number.
     *
     * @return the current block number or -1 if no blocks have been opened yet
     */
    public long getBlockNumber() {
        return blockNumber;
    }

    /**
     * Ensures that there is enough capacity in the block buffer to permit a new block being created. If there is not
     * enough capacity - i.e. the buffer is saturated - then this method will block until there is enough capacity.
     */
    public static void ensureNewBlocksPermitted() {
        final CompletableFuture<Boolean> cf = backpressureCompletableFutureRef.get();
        if (cf != null && !cf.isDone()) {
            try {
                logger.error("!!! Block buffer is saturated; blocking thread until buffer is no longer saturated");
                final long startMs = System.currentTimeMillis();
                final boolean bufferAvailable = cf.get();
                final long durationMs = System.currentTimeMillis() - startMs;
                logger.warn("Thread was blocked for {}ms waiting for block buffer to free space", durationMs);

                if (!bufferAvailable) {
                    logger.warn("Block buffer still not available to accept new blocks; reentering wait...");
                    ensureNewBlocksPermitted();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                logger.warn("Failed to wait for block buffer to be available", e);
            }
        }
    }

    /**
     * Prunes the block buffer by removing blocks that have been acknowledged and exceeded the configured TTL. By doing
     * this, we also inadvertently can know if buffer is "saturated" due to blocks not being acknowledged in a timely
     * manner.
     */
    private @NonNull PruneResult pruneBuffer() {
        final Duration ttl = blockBufferTtl();
        final Instant cutoffInstant = Instant.now().minus(ttl);
        final Iterator<BlockState> it = blockBuffer.iterator();
        final long highestBlockAcked = highestAckedBlockNumber.get();
        /*
        Calculate the ideal max buffer size. This is calculated as the block buffer TTL (e.g. 5 minutes) divided by the
        block period (e.g. 2 seconds). This gives us an ideal number of blocks in the buffer.
         */
        final long idealMaxBufferSize = ttl.dividedBy(blockPeriod());
        int numPruned = 0;
        int numChecked = 0;
        int numPendingAck = 0;
        final AtomicReference<Instant> oldestUnackedTimestamp = new AtomicReference<>(Instant.MAX);

        while (it.hasNext()) {
            final BlockState block = it.next();
            ++numChecked;

            if (block.isComplete()) {
                if (block.blockNumber() <= highestBlockAcked) {
                    // this block is eligible for pruning if it is old enough
                    if (block.completionTimestamp().isBefore(cutoffInstant)) {
                        blockStatesById.remove(block.blockNumber());
                        it.remove();
                        ++numPruned;
                    }
                } else {
                    ++numPendingAck;
                    oldestUnackedTimestamp.updateAndGet(current ->
                            current.compareTo(block.completionTimestamp()) < 0 ? current : block.completionTimestamp());
                }
            }
        }

        final long oldestUnackedMillis = Instant.MAX.equals(oldestUnackedTimestamp.get())
                ? -1 // sentinel value indicating no blocks are unacked
                : oldestUnackedTimestamp.get().toEpochMilli();
        blockStreamMetrics.setOldestUnacknowledgedBlockTime(oldestUnackedMillis);

        return new PruneResult(idealMaxBufferSize, numChecked, numPendingAck, numPruned);
    }

    /*
    Simple record that contains information related to the outcome of a block buffer prune operation.
     */
    private record PruneResult(
            long idealMaxBufferSize, int numBlocksChecked, int numBlocksPendingAck, int numBlocksPruned) {

        /**
         * Calculate the saturation percent based on the size of the buffer and the number of unacked blocks found.
         * @return the saturation percent
         */
        double calculateSaturationPercent() {
            if (idealMaxBufferSize == 0) {
                return 0D;
            }

            final BigDecimal size = BigDecimal.valueOf(idealMaxBufferSize);
            final BigDecimal pending = BigDecimal.valueOf(numBlocksPendingAck);
            return pending.divide(size, 6, RoundingMode.HALF_EVEN)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        }

        /**
         * Check if the buffer is considered saturated.
         *
         * @return true if the block buffer is considered saturated, else false
         */
        boolean isSaturated() {
            return idealMaxBufferSize != 0 && numBlocksPendingAck >= idealMaxBufferSize;
        }
    }

    /**
     * Prunes the block buffer and checks if the buffer is saturated. If the buffer is saturated, then a backpressure
     * mechanism is activated. The backpressure will be enabled until the next time this method is invoked, after which
     * the backpressure mechanism will be disabled if the buffer is no longer saturated, or maintained if the buffer
     * continues to be saturated.
     */
    private void checkBuffer() {
        final boolean isSaturatedBeforePrune = isBufferSaturated.get();
        final BlockStreamStateManager.PruneResult result = pruneBuffer();
        final boolean isSaturatedAfterPrune = result.isSaturated();
        isBufferSaturated.set(isSaturatedAfterPrune);
        final double saturationPercent = result.calculateSaturationPercent();

        logger.debug(
                "Block buffer status: idealMaxBufferSize={}, blocksChecked={}, blocksPruned={}, blocksPendingAck={}, saturation={}%",
                result.idealMaxBufferSize,
                result.numBlocksChecked,
                result.numBlocksPruned,
                result.numBlocksPendingAck,
                saturationPercent);

        blockStreamMetrics.updateBlockBufferSaturation(saturationPercent);

        if (isSaturatedBeforePrune == isSaturatedAfterPrune) {
            // no state change detected, escape early
            return;
        }

        if (isSaturatedAfterPrune) {
            // we've transitioned to a saturated state, apply backpressure
            logger.warn(
                    "Block buffer is saturated; backpressure is being enabled "
                            + "(idealMaxBufferSize={}, blocksChecked={}, blocksPruned={}, blocksPendingAck={}, saturation={}%)",
                    result.idealMaxBufferSize,
                    result.numBlocksChecked,
                    result.numBlocksPruned,
                    result.numBlocksPendingAck,
                    saturationPercent);

            CompletableFuture<Boolean> oldCf;
            CompletableFuture<Boolean> newCf;
            do {
                oldCf = backpressureCompletableFutureRef.get();
                if (oldCf != null) {
                    /**
                     * If everything is behaving as expected, then this condition should never be encountered. At any
                     * given time there should only be one state manager and thus one scheduled prune task. However, if
                     * there are multiple instances of the manager or something gets messed up threading-wise, then we
                     * need to handle the possibility that there are multiple blocking futures concurrently. With this
                     * in mind, we will set the CompletableFuture we use to block in {@link #ensureNewBlocksPermitted()}
                     * to complete with a value of {@code false}. This false indicates that the CompletableFuture was
                     * completed but another CompletableFuture took its place and that blocking should continue to
                     * be enabled.
                     */
                    logger.warn("Multiple backpressure blocking futures encountered; this may indicate multiple state "
                            + "managers or buffer pruning tasks were concurrently active");
                    oldCf.complete(false);
                }
                newCf = new CompletableFuture<>();
            } while (!backpressureCompletableFutureRef.compareAndSet(oldCf, newCf));
        } else {
            // we've transitioned to a non-saturated state, disable backpressure
            CompletableFuture<Boolean> oldCf;
            CompletableFuture<Boolean> newCf;

            do {
                oldCf = backpressureCompletableFutureRef.get();
                if (oldCf != null) {
                    /**
                     * If everything is behaving as expected, then this condition should never be encountered. At any
                     * given time there should only be one state manager and thus one scheduled prune task. However, if
                     * there are multiple instances of the manager or something gets messed up threading-wise, then we
                     * need to handle the possibility that there are multiple blocking futures concurrently. With this
                     * in mind, we will set the CompletableFuture we use to block in {@link #ensureNewBlocksPermitted()}
                     * to complete with a value of {@code true}. This true indicates that the CompletableFuture was
                     * completed and that we are no longer applying backpressure and thus no longer blocking.
                     */
                    logger.warn("Multiple backpressure blocking futures encountered; this may indicate multiple state "
                            + "managers or buffer pruning tasks were concurrently active");
                    oldCf.complete(true);
                }
                newCf = CompletableFuture.completedFuture(true);
            } while (!backpressureCompletableFutureRef.compareAndSet(oldCf, newCf));
        }
    }

    private void scheduleNextPruning() {
        /*
        The prune interval may be set to 0, which will effectively disable the pruning. However, we still want to
        maintain some sensible interval to re-check if the interval has changed, in particular if it is no longer set to
        0 and thus pruning should be enabled.
         */
        final Duration pruneInterval = blockBufferPruneInterval();
        final long millis = pruneInterval.toMillis() != 0 ? pruneInterval.toMillis() : TimeUnit.SECONDS.toMillis(1);
        execSvc.schedule(new BufferPruneTask(), millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Task that prunes the block buffer.
     * @see #checkBuffer()
     */
    private class BufferPruneTask implements Runnable {

        @Override
        public void run() {
            final Duration pruneInterval = blockBufferPruneInterval();
            try {
                // If the interval is 0, pruning is disabled, so only do the prune if the interval is NOT 0.
                if (!pruneInterval.isZero()) {
                    checkBuffer();
                }
            } catch (final RuntimeException e) {
                logger.warn("Periodic buffer pruning failed", e);
            } finally {
                scheduleNextPruning();
            }
        }
    }
}
