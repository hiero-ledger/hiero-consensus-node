// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages the state and lifecycle of blocks being streamed to block nodes.
 * This class is responsible for:
 * <ul>
 *     <li>Maintaining the block states in a buffer</li>
 *     <li>Handling backpressure when the buffer is saturated</li>
 *     <li>Pruning the buffer based on TTL and saturation</li>
 * </ul>
 */
@Singleton
public class BlockBufferService {
    private static final Logger logger = LogManager.getLogger(BlockBufferService.class);

    /**
     * Buffer that stores recent blocks. This buffer is unbounded, however it is technically capped because back
     * pressure will prevent blocks from being created. Generally speaking, the buffer should contain only blocks that
     * are recent (that is within the configured {@link BlockStreamConfig#blockBufferTtl() TTL}) and have yet to be
     * acknowledged. There may be cases where older blocks still exist in the buffer if they are unacknowledged, but
     * once they are acknowledged they will be pruned the next time {@link #openBlock(long)} is invoked.
     */
    private final ConcurrentMap<Long, BlockState> blockBuffer = new ConcurrentHashMap<>();
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
    private final AtomicReference<CompletableFuture<Boolean>> backpressureCompletableFutureRef =
            new AtomicReference<>();
    /**
     * The most recent produced block number (i.e. the last block to be opened). A value of -1 indicates that no blocks
     * have been open/produced yet.
     */
    private final AtomicLong lastProducedBlockNumber = new AtomicLong(-1);
    /**
     * Mechanism to retrieve configuration properties related to block-node communication.
     */
    private final ConfigProvider configProvider;
    /**
     * Reference to the connection manager.
     */
    private BlockNodeConnectionManager blockNodeConnectionManager;
    /**
     * Metrics API for block stream-specific metrics.
     */
    private final BlockStreamMetrics blockStreamMetrics;
    /**
     * Flag that indicates if streaming to block nodes is enabled. This flag is set once upon startup and cannot change.
     */
    private final AtomicBoolean isStreamingEnabled = new AtomicBoolean(false);

    /**
     * Creates a new BlockBufferService with the given configuration.
     *
     * @param configProvider the configuration provider
     * @param blockStreamMetrics metrics factory for monitoring block streaming
     */
    @Inject
    public BlockBufferService(
            @NonNull final ConfigProvider configProvider, @NonNull final BlockStreamMetrics blockStreamMetrics) {
        this.configProvider = configProvider;
        this.blockStreamMetrics = blockStreamMetrics;
        isStreamingEnabled.set(streamToBlockNodesEnabled());

        // Only start the pruning thread if we're streaming to block nodes
        if (isStreamingEnabled.get()) {
            scheduleNextPruning();
        }
    }

    /**
     * @return true if streaming to block nodes is enabled, else false
     */
    private boolean streamToBlockNodesEnabled() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamToBlockNodes();
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
     * Sets the block node connection manager for notifications.
     *
     * @param blockNodeConnectionManager the block node connection manager
     */
    public void setBlockNodeConnectionManager(@NonNull final BlockNodeConnectionManager blockNodeConnectionManager) {
        this.blockNodeConnectionManager =
                requireNonNull(blockNodeConnectionManager, "blockNodeConnectionManager must not be null");
    }

    /**
     * Opens a new block for streaming with the given block number. Creates a new BlockState, adds it to the buffer,
     * and notifies block nodes if streaming is enabled. This will also attempt to prune older blocks from the buffer.
     *
     * @param blockNumber the block number
     * @throws IllegalArgumentException if the block number is negative
     */
    public void openBlock(final long blockNumber) {
        if (!isStreamingEnabled.get()) {
            return;
        }

        if (blockNumber < 0) {
            throw new IllegalArgumentException("Block number must be non-negative");
        }

        final long lastAcked = highestAckedBlockNumber.get();
        if (blockNumber <= lastAcked) {
            logger.error(
                    "Attempted to open block {}, but a later block (lastAcked={}) has already been acknowledged",
                    blockNumber,
                    lastAcked);
            throw new IllegalStateException("Attempted to open block " + blockNumber + ", but a later block (lastAcked="
                    + lastAcked + ") has already been acknowledged");
        }

        final BlockState existingBlock = blockBuffer.get(blockNumber);
        if (existingBlock != null && existingBlock.isBlockProofSent()) {
            logger.error("Attempted to open block {}, but this block already has the block proof sent", blockNumber);
            throw new IllegalStateException("Attempted to open block " + blockNumber + ", but this block already has "
                    + "the block proof sent");
        }

        // Create a new block state
        final BlockState blockState = new BlockState(blockNumber);
        blockBuffer.put(blockNumber, blockState);
        lastProducedBlockNumber.updateAndGet(old -> Math.max(old, blockNumber));
        blockStreamMetrics.setProducingBlockNumber(blockNumber);
        blockNodeConnectionManager.openBlock(blockNumber);
    }

    /**
     * Adds a new block item to the streaming queue for the specified block.
     *
     * @param blockNumber the block number to add the block item to
     * @param blockItem the block item to add
     * @throws IllegalStateException if no block is currently open
     */
    public void addItem(final long blockNumber, @NonNull final BlockItem blockItem) {
        if (!isStreamingEnabled.get()) {
            return;
        }
        requireNonNull(blockItem, "blockItem must not be null");
        final BlockState blockState = getBlockState(blockNumber);
        if (blockState == null) {
            throw new IllegalStateException("Block state not found for block " + blockNumber);
        }
        blockState.addItem(blockItem);
    }

    /**
     * Closes the current block and marks it as complete.
     * @param blockNumber the block number
     * @throws IllegalStateException if no block is currently open
     */
    public void closeBlock(final long blockNumber) {
        if (!isStreamingEnabled.get()) {
            return;
        }

        final BlockState blockState = getBlockState(blockNumber);
        if (blockState == null) {
            throw new IllegalStateException("Block state not found for block " + blockNumber);
        }

        blockState.closeBlock();
    }

    /**
     * Gets the block state for the given block number.
     *
     * @param blockNumber the block number
     * @return the block state, or null if no block state exists for the given block number
     */
    public @Nullable BlockState getBlockState(final long blockNumber) {
        return blockBuffer.get(blockNumber);
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
     * Marks all blocks up to and including the specified block as being acknowledged by any Block Node.
     *
     * @param blockNumber the block number to mark acknowledged up to and including
     */
    public void setLatestAcknowledgedBlock(final long blockNumber) {
        if (!isStreamingEnabled.get()) {
            return;
        }

        final long highestBlock = highestAckedBlockNumber.updateAndGet(current -> Math.max(current, blockNumber));
        blockStreamMetrics.setLatestAcknowledgedBlockNumber(highestBlock);
    }

    /**
     * Gets the current block number.
     *
     * @return the current block number or -1 if no blocks have been opened yet
     */
    public long getLastBlockNumberProduced() {
        return lastProducedBlockNumber.get();
    }

    /**
     * Ensures that there is enough capacity in the block buffer to permit a new block being created. If there is not
     * enough capacity - i.e. the buffer is saturated - then this method will block until there is enough capacity.
     */
    public void ensureNewBlocksPermitted() {
        if (!isStreamingEnabled.get()) {
            return;
        }

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
        final Iterator<Map.Entry<Long, BlockState>> it = blockBuffer.entrySet().iterator();
        final long highestBlockAcked = highestAckedBlockNumber.get();
        /*
        Calculate the ideal max buffer size. This is calculated as the block buffer TTL (e.g. 5 minutes) divided by the
        block period (e.g. 2 seconds). This gives us an ideal number of blocks in the buffer.
         */
        final Duration blockPeriod = blockPeriod();
        final long idealMaxBufferSize =
                blockPeriod.isZero() || blockPeriod.isNegative() ? 150 : ttl.dividedBy(blockPeriod());
        int numPruned = 0;
        int numChecked = 0;
        int numPendingAck = 0;
        final AtomicReference<Instant> oldestUnackedTimestamp = new AtomicReference<>(Instant.MAX);

        while (it.hasNext()) {
            final Map.Entry<Long, BlockState> blockEntry = it.next();
            final BlockState block = blockEntry.getValue();
            ++numChecked;

            final Instant closedTimestamp = block.closedTimestamp();
            if (closedTimestamp == null) {
                // the block is not finished yet, so skip checking it
                continue;
            }

            if (block.blockNumber() <= highestBlockAcked) {
                // this block is eligible for pruning if it is old enough
                if (closedTimestamp.isBefore(cutoffInstant)) {
                    it.remove();
                    ++numPruned;
                }
            } else {
                ++numPendingAck;
                oldestUnackedTimestamp.updateAndGet(
                        current -> current.compareTo(closedTimestamp) < 0 ? current : closedTimestamp);
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
        if (!streamToBlockNodesEnabled()) {
            return;
        }

        final boolean isSaturatedBeforePrune = isBufferSaturated.get();
        final BlockBufferService.PruneResult result = pruneBuffer();
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
                            + "managers or buffer pruning tasks were concurrently active (enabling back pressure)");
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
                            + "managers or buffer pruning tasks were concurrently active (disabling back pressure)");
                    oldCf.complete(true);
                }
                newCf = CompletableFuture.completedFuture(true);
            } while (!backpressureCompletableFutureRef.compareAndSet(oldCf, newCf));
        }
    }

    private void scheduleNextPruning() {
        if (!streamToBlockNodesEnabled()) {
            return;
        }

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

    /**
     * Retrieves the lowest unacked block number in the buffer. This is the lowest block number that has not been acknowledged.
     * @return the lowest unacked block number
     */
    public long getLowestUnackedBlockNumber() {
        return highestAckedBlockNumber.get() == Long.MIN_VALUE ? 0 : highestAckedBlockNumber.get() + 1;
    }
}
