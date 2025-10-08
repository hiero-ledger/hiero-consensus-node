// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.BufferedBlock;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
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
    private static final int DEFAULT_BUFFER_SIZE = 150;

    private static final Duration DEFAULT_WORKER_INTERVAL = Duration.ofSeconds(1);

    /**
     * Buffer that stores recent blocks. This buffer is unbounded, however it is technically capped because back
     * pressure will prevent blocks from being created. Generally speaking, the buffer should contain only blocks that
     * are recent (that is within the configured {@link BlockBufferConfig#blockTtl() TTL}) and have yet to be
     * acknowledged. There may be cases where older blocks still exist in the buffer if they are unacknowledged, but
     * once they are acknowledged they will be pruned the next time {@link #openBlock(long)} is invoked.
     */
    private final ConcurrentMap<Long, BlockState> blockBuffer = new ConcurrentHashMap<>();

    /**
     * This tracks the earliest block number in the buffer.
     */
    private final AtomicLong earliestBlockNumber = new AtomicLong(Long.MIN_VALUE);

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
    private ScheduledExecutorService execSvc;
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

    private final boolean grpcStreamingEnabled;
    private final boolean backpressureEnabled;

    /**
     * The timestamp of the most recent attempt at proactive buffer recovery.
     */
    private Instant lastRecoveryActionTimestamp = Instant.MIN;
    /**
     * The most recent buffer pruning result.
     */
    private PruneResult lastPruningResult = PruneResult.NIL;
    /**
     * Flag indicating whether the buffer transitioned from fully saturated to not, but we are still waiting to reach
     * the recovery threshold.
     */
    private boolean awaitingRecovery = false;
    /**
     * Utility for managing reading and writing block buffer to disk.
     */
    private final BlockBufferIO bufferIO;
    /**
     * Flag indicating if the buffer service has been started.
     */
    private final AtomicBoolean isStarted = new AtomicBoolean(false);

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
        this.bufferIO = new BlockBufferIO(bufferDirectory());

        final BlockStreamConfig blockStreamConfig =
                configProvider.getConfiguration().getConfigData(BlockStreamConfig.class);
        this.grpcStreamingEnabled = blockStreamConfig.writerMode() != BlockStreamWriterMode.FILE;
        this.backpressureEnabled = (blockStreamConfig.streamMode() == StreamMode.BLOCKS && grpcStreamingEnabled);
    }

    /**
     * If block streaming is enabled, then this method will attempt to load the latest buffer from disk and start the
     * background worker thread. Calling this method multiple times on the same instance will do nothing.
     */
    public void start() {
        if (!grpcStreamingEnabled || !isStarted.compareAndSet(false, true)) {
            return;
        }

        // Initialize buffer and start worker thread if streaming is enabled
        loadBufferFromDisk();

        execSvc = Executors.newSingleThreadScheduledExecutor();
        scheduleNextWorkerTask();
    }

    /**
     * Shuts down the block buffer service and its associated resources.
     * This terminates the executor service.
     */
    public void shutdown() {
        if (!isStarted.compareAndSet(true, false)) {
            // buffer already shutdown
            return;
        }

        // stop the background task from running
        execSvc.shutdownNow();
        // since the pruning task is no longer running, free up the buffer
        blockBuffer.clear();
        // if back pressure was enabled, disable it during shutdown
        disableBackPressure();
        // clear metadata
        highestAckedBlockNumber.set(-1);
        lastProducedBlockNumber.set(-1);
        earliestBlockNumber.set(-1);
        lastPruningResult = PruneResult.NIL;
        lastRecoveryActionTimestamp = Instant.MIN;
        awaitingRecovery = false;
    }

    /**
     * @return the interval in which the block buffer periodic operations will be invoked
     */
    private Duration workerTaskInterval() {
        final Duration interval = configProvider
                .getConfiguration()
                .getConfigData(BlockBufferConfig.class)
                .workerInterval();
        if (interval.isNegative() || interval.isZero()) {
            return DEFAULT_WORKER_INTERVAL;
        } else {
            return interval;
        }
    }

    /**
     * @return the current TTL for items in the block buffer
     */
    private Duration blockBufferTtl() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockBufferConfig.class)
                .blockTtl();
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
     * @return the buffer saturation level that once exceeded, proactive measures (i.e. switching block nodes) will be
     * taken to attempt buffery recovery
     */
    private double actionStageThreshold() {
        final double threshold = configProvider
                .getConfiguration()
                .getConfigData(BlockBufferConfig.class)
                .actionStageThreshold();
        return Math.max(0.0D, threshold);
    }

    /**
     * @return the minimum interval between when proactive actions are permitted. For example, if the period is 10
     * seconds then attempts to switch block nodes due to elevated buffer saturation are only permitted every 10 seconds
     */
    private Duration actionGracePeriod() {
        final Duration gracePeriod = configProvider
                .getConfiguration()
                .getConfigData(BlockBufferConfig.class)
                .actionGracePeriod();
        return gracePeriod == null || gracePeriod.isNegative() ? Duration.ZERO : gracePeriod;
    }

    /**
     * @return the level of buffer saturation that needs to be achieved after back pressure is enabled before it will be
     * disabled. For example, if the threshold is 60.0, then once back pressure is engaged
     */
    private double recoveryThreshold() {
        final double threshold = configProvider
                .getConfiguration()
                .getConfigData(BlockBufferConfig.class)
                .recoveryThreshold();
        return Math.max(0.0D, threshold);
    }

    /**
     * @return true if buffer persistence is enabled, else false
     */
    private boolean isBufferPersistenceEnabled() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockBufferConfig.class)
                .isBufferPersistenceEnabled();
    }

    /**
     * @return the directory where the block buffer will be persisted
     */
    private String bufferDirectory() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockBufferConfig.class)
                .bufferDirectory();
    }

    /**
     * @return the batch size for a request to send to the block node
     */
    private int blockItemBatchSize() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .blockItemBatchSize();
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
        if (!grpcStreamingEnabled || !isStarted.get()) {
            return;
        }

        if (blockNumber < 0) {
            throw new IllegalArgumentException("Block number must be non-negative");
        }

        final BlockState existingBlock = blockBuffer.get(blockNumber);
        if (existingBlock != null && existingBlock.isClosed()) {
            logger.debug("Block {} is already open and its closed; ignoring open request", blockNumber);
            return;
        }

        // Create a new block state
        final BlockState blockState = new BlockState(blockNumber);
        blockBuffer.put(blockNumber, blockState);
        // update the earliest block number if this is first block or lower than current earliest
        earliestBlockNumber.updateAndGet(
                current -> current == Long.MIN_VALUE ? blockNumber : Math.min(current, blockNumber));
        lastProducedBlockNumber.updateAndGet(old -> Math.max(old, blockNumber));
        blockStreamMetrics.recordLatestBlockOpened(blockNumber);
        blockStreamMetrics.recordBlockOpened();
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
        if (!grpcStreamingEnabled || !isStarted.get()) {
            return;
        }
        requireNonNull(blockItem, "blockItem must not be null");
        final BlockState blockState = getBlockState(blockNumber);
        if (blockState == null || blockState.isClosed()) {
            return;
        }
        blockState.addItem(blockItem);
    }

    /**
     * Closes the current block and marks it as complete.
     * @param blockNumber the block number
     * @throws IllegalStateException if no block is currently open
     */
    public void closeBlock(final long blockNumber) {
        if (!grpcStreamingEnabled || !isStarted.get()) {
            return;
        }

        final BlockState blockState = getBlockState(blockNumber);
        if (blockState == null) {
            throw new IllegalStateException("Block state not found for block " + blockNumber);
        }

        if (blockState.isClosed()) {
            return;
        }
        blockStreamMetrics.recordBlockClosed();
        blockState.closeBlock();
    }

    /**
     * Gets the block state for the given block number.
     *
     * @param blockNumber the block number
     * @return the block state, or null if no block state exists for the given block number
     */
    public @Nullable BlockState getBlockState(final long blockNumber) {
        final BlockState block = blockBuffer.get(blockNumber);

        if (block == null) {
            blockStreamMetrics.recordBlockMissing();
        }

        return block;
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
        if (!grpcStreamingEnabled || !isStarted.get()) {
            return;
        }

        final long highestBlock = highestAckedBlockNumber.updateAndGet(current -> Math.max(current, blockNumber));
        blockStreamMetrics.recordLatestBlockAcked(highestBlock);
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
     * Retrieves the highest acked block number in the buffer.
     * This is the highest block number that has been acknowledged.
     * @return the highest acked block number or -1 if the buffer is empty
     */
    public long getHighestAckedBlockNumber() {
        return highestAckedBlockNumber.get() == Long.MIN_VALUE ? -1 : highestAckedBlockNumber.get();
    }

    /**
     * Retrieves the earliest available block number in the buffer.
     * This is the lowest block number currently in the buffer.
     * @return the earliest available block number or -1 if the buffer is empty
     */
    public long getEarliestAvailableBlockNumber() {
        return earliestBlockNumber.get() == Long.MIN_VALUE ? -1 : earliestBlockNumber.get();
    }

    /**
     * Ensures that there is enough capacity in the block buffer to permit a new block being created. If there is not
     * enough capacity - i.e. the buffer is saturated - then this method will block until there is enough capacity.
     */
    public void ensureNewBlocksPermitted() {
        if (!grpcStreamingEnabled || !isStarted.get()) {
            return;
        }

        final CompletableFuture<Boolean> cf = backpressureCompletableFutureRef.get();
        if (cf != null && !cf.isDone()) {
            try {
                logger.error("!!! Block buffer is saturated; blocking thread until buffer is no longer saturated");
                final long startMs = System.currentTimeMillis();
                final boolean bufferAvailable = cf.get(); // this will block until the future is completed
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
     * Loads the latest block buffer from disk, if one exists.
     */
    private void loadBufferFromDisk() {
        if (!isBufferPersistenceEnabled()) {
            return;
        }

        final List<BufferedBlock> blocks;
        try {
            blocks = bufferIO.read();
        } catch (final IOException e) {
            logger.error("Failed to read block buffer from disk!", e);
            return;
        }

        if (blocks.isEmpty()) {
            logger.info("Block buffer will not be repopulated (reason: no blocks found on disk)");
            return;
        }

        final int batchSize = blockItemBatchSize();

        logger.info("Block buffer is being restored from disk (blocksRead: {})", blocks.size());

        for (final BufferedBlock bufferedBlock : blocks) {
            final BlockState block = new BlockState(bufferedBlock.blockNumber());
            bufferedBlock.block().items().forEach(block::addItem);
            // create the requests
            block.processPendingItems(batchSize);
            if (bufferedBlock.isProofSent()) {
                // the proof is sent and since it is the last thing in a block, mark all the requests as sent
                for (int i = 0; i < block.numRequestsCreated(); ++i) {
                    block.markRequestSent(i);
                }
            }

            final Timestamp closedTimestamp = bufferedBlock.closedTimestamp();
            final Instant closedInstant = Instant.ofEpochSecond(closedTimestamp.seconds(), closedTimestamp.nanos());
            logger.debug(
                    "Reconstructed block {} from disk and closed at {}", bufferedBlock.blockNumber(), closedInstant);
            block.closeBlock(closedInstant);

            if (bufferedBlock.isAcknowledged()) {
                setLatestAcknowledgedBlock(bufferedBlock.blockNumber());
            }

            if (blockBuffer.putIfAbsent(bufferedBlock.blockNumber(), block) != null) {
                logger.debug(
                        "Block {} was read from disk but it was already in the buffer; ignoring block from disk",
                        bufferedBlock.blockNumber());
            }
        }
    }

    /**
     * If block buffer persistence is enabled, then all blocks that are closed at the time of invocation will be
     * persisted to disk. These persisted blocks can be loaded upon startup to recover the buffer.
     *
     * @see BlockBufferIO
     */
    public void persistBuffer() {
        if (!grpcStreamingEnabled || !isStarted.get() || !isBufferPersistenceEnabled()) {
            return;
        }

        // collect all closed blocks which are not acked yet
        final List<BlockState> blocksToPersist = blockBuffer.values().stream()
                .filter(BlockState::isClosed)
                .filter(blockState -> blockState.blockNumber() > highestAckedBlockNumber.get())
                .toList();

        // ensure all closed blocks have their items packed in requests before writing them out
        final int batchSize = blockItemBatchSize();
        blocksToPersist.forEach(block -> block.processPendingItems(batchSize));

        try {
            bufferIO.write(blocksToPersist, highestAckedBlockNumber.get());
            logger.info("Block buffer persisted to disk (blocksWritten: {})", blocksToPersist.size());
        } catch (final RuntimeException | IOException e) {
            logger.error("Failed to write block buffer to disk!", e);
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
                blockPeriod.isZero() || blockPeriod.isNegative() ? DEFAULT_BUFFER_SIZE : ttl.dividedBy(blockPeriod);
        int numPruned = 0;
        int numChecked = 0;
        int numPendingAck = 0;
        long newEarliestBlock = Long.MAX_VALUE;
        long newLatestBlock = Long.MIN_VALUE;

        while (it.hasNext()) {
            final Map.Entry<Long, BlockState> blockEntry = it.next();
            final BlockState block = blockEntry.getValue();
            final long blockNum = blockEntry.getKey();
            ++numChecked;

            final Instant closedTimestamp = block.closedTimestamp();
            if (closedTimestamp == null) {
                // the block is not finished yet, so skip checking it
                continue;
            }

            if (!backpressureEnabled) {
                // If backpressure is disabled, remove blocks based solely on TTL
                if (closedTimestamp.isBefore(cutoffInstant)) {
                    it.remove();
                    ++numPruned;
                } else {
                    // Track unacknowledged blocks
                    if (block.blockNumber() > highestBlockAcked) {
                        ++numPendingAck;
                    }
                    // Keep track of earliest remaining block
                    newEarliestBlock = Math.min(newEarliestBlock, blockNum);
                    newLatestBlock = Math.max(newLatestBlock, blockNum);
                }
            } else if (block.blockNumber() <= highestBlockAcked) {
                // this block is eligible for pruning if it is old enough
                if (closedTimestamp.isBefore(cutoffInstant)) {
                    it.remove();
                    ++numPruned;
                } else {
                    // keep track of earliest remaining block
                    newEarliestBlock = Math.min(newEarliestBlock, blockNum);
                    newLatestBlock = Math.max(newLatestBlock, blockNum);
                }
            } else {
                ++numPendingAck;
                // keep track of earliest remaining block
                newEarliestBlock = Math.min(newEarliestBlock, blockNum);
                newLatestBlock = Math.max(newLatestBlock, blockNum);
            }
        }

        // update the earliest block number after pruning
        newEarliestBlock = newEarliestBlock == Long.MAX_VALUE ? -1 : newEarliestBlock;
        newLatestBlock = newLatestBlock == Long.MIN_VALUE ? -1 : newLatestBlock;
        earliestBlockNumber.set(newEarliestBlock);

        blockStreamMetrics.recordNumberOfBlocksPruned(numPruned);
        blockStreamMetrics.recordBufferOldestBlock(newEarliestBlock);
        blockStreamMetrics.recordBufferNewestBlock(newLatestBlock);

        return new PruneResult(
                idealMaxBufferSize, numChecked, numPendingAck, numPruned, newEarliestBlock, newLatestBlock);
    }

    /**
     * Simple class that contains information related to the outcome of the buffer pruning.
     */
    static class PruneResult {
        static final PruneResult NIL = new PruneResult(0, 0, 0, 0, 0, 0);

        final long idealMaxBufferSize;
        final int numBlocksChecked;
        final int numBlocksPendingAck;
        final int numBlocksPruned;
        final long oldestBlockNumber;
        final long newestBlockNumber;
        final double saturationPercent;
        final boolean isSaturated;

        PruneResult(
                final long idealMaxBufferSize,
                final int numBlocksChecked,
                final int numBlocksPendingAck,
                final int numBlocksPruned,
                final long oldestBlockNumber,
                final long newestBlockNumber) {
            this.idealMaxBufferSize = idealMaxBufferSize;
            this.numBlocksChecked = numBlocksChecked;
            this.numBlocksPendingAck = numBlocksPendingAck;
            this.numBlocksPruned = numBlocksPruned;
            this.oldestBlockNumber = oldestBlockNumber;
            this.newestBlockNumber = newestBlockNumber;

            isSaturated = idealMaxBufferSize != 0 && numBlocksPendingAck >= idealMaxBufferSize;

            if (idealMaxBufferSize == 0) {
                saturationPercent = 0D;
            } else {
                final BigDecimal size = BigDecimal.valueOf(idealMaxBufferSize);
                final BigDecimal pending = BigDecimal.valueOf(numBlocksPendingAck);
                saturationPercent = pending.divide(size, 6, RoundingMode.HALF_EVEN)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
            }
        }

        @Override
        public String toString() {
            return "PruneResult{" + "idealMaxBufferSize="
                    + idealMaxBufferSize + ", numBlocksChecked="
                    + numBlocksChecked + ", numBlocksPendingAck="
                    + numBlocksPendingAck + ", numBlocksPruned="
                    + numBlocksPruned + ", saturationPercent="
                    + saturationPercent + ", isSaturated="
                    + isSaturated + '}';
        }
    }

    /**
     * Prunes the block buffer and checks if the buffer is saturated. If the buffer is saturated, then a backpressure
     * mechanism is activated. The backpressure will be enabled until the next time this method is invoked, after which
     * the backpressure mechanism will be disabled if the buffer is no longer saturated, or maintained if the buffer
     * continues to be saturated.
     */
    private void checkBuffer() {
        if (!grpcStreamingEnabled) {
            return;
        }

        final PruneResult pruningResult = pruneBuffer();
        final PruneResult previousPruneResult = lastPruningResult;
        lastPruningResult = pruningResult;

        // create a list of ranges of continguous blocks in the buffer
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Block buffer status: idealMaxBufferSize={}, blocksChecked={}, blocksPruned={}, blocksPendingAck={}, blockRange={}, saturation={}%",
                    pruningResult.idealMaxBufferSize,
                    pruningResult.numBlocksChecked,
                    pruningResult.numBlocksPruned,
                    pruningResult.numBlocksPendingAck,
                    getContiguousRangesAsString(),
                    pruningResult.saturationPercent);
        }

        blockStreamMetrics.recordBufferSaturation(pruningResult.saturationPercent);

        final double actionStageThreshold = actionStageThreshold();

        if (previousPruneResult.saturationPercent < actionStageThreshold) {
            if (pruningResult.isSaturated) {
                /*
                Zero -> Full
                The buffer has transitioned from zero/low saturation levels to fully saturated. We need to ensure back
                pressure is engaged and potentially change which Block Node we are connected to.
                 */
                enableBackPressure(pruningResult);
                switchBlockNodeIfPermitted(pruningResult);
            } else if (pruningResult.saturationPercent >= actionStageThreshold) {
                /*
                Zero -> Action Stage
                The buffer has transitioned from zero/low saturation levels to exceeding the action stage threshold. We
                don't need to engage back pressure, but we should take proactive measures and swap to a different
                Block Node.
                 */
                blockStreamMetrics.recordBackPressureActionStage();
                switchBlockNodeIfPermitted(pruningResult);
            } else {
                /*
                Zero -> Zero
                Before and after the pruning, the buffer saturation remained lower than the action stage threshold so
                there is no action we need to take.
                 */
                blockStreamMetrics.recordBackPressureDisabled();
            }
        } else if (!previousPruneResult.isSaturated && previousPruneResult.saturationPercent >= actionStageThreshold) {
            if (pruningResult.isSaturated) {
                /*
                Action Stage -> Full
                The buffer has transitioned from the action stage saturation level to being completely full/saturated.
                Back pressure needs to be applied and possibly switch to a different Block Node.
                 */
                enableBackPressure(pruningResult);
                switchBlockNodeIfPermitted(pruningResult);
            } else if (pruningResult.saturationPercent >= actionStageThreshold) {
                /*
                Action Stage -> Action Stage
                Before and after the pruning, the buffer saturation remained at the action stage level. Back pressure
                does not need to be enabled yet (though may eventually if recovery is slow/blocked) but we should maybe
                swap Block Node connections.
                 */
                blockStreamMetrics.recordBackPressureActionStage();
                switchBlockNodeIfPermitted(pruningResult);
            } else {
                /*
                Action Stage -> Zero
                The buffer has transitioned from an action stage to having a saturation that is below the action stage
                threshold. There is no further action to take since recovery has been achieved.
                 */
                blockStreamMetrics.recordBackPressureDisabled();
            }
        } else if (previousPruneResult.isSaturated) {
            if (pruningResult.isSaturated) {
                /*
                Full -> Full
                Before and after pruning, the buffer remained fully saturated. Back pressure should be enabled - if not
                already - and we should maybe swap to a different Block Node.
                 */
                switchBlockNodeIfPermitted(pruningResult);
                enableBackPressure(pruningResult);
            } else if (pruningResult.saturationPercent >= actionStageThreshold) {
                /*
                Full -> Action Stage
                Before the pruning, the buffer was fully saturated, but after pruning the buffer is no longer fully
                saturated, although it is still above the action stage threshold. Back pressure should be disabled if
                there has been enough buffer recovery. Since the buffer appears to be recovering, avoid trying to
                connect to a different Block Node.
                 */
                disableBackPressureIfRecovered(pruningResult);
                if (awaitingRecovery) {
                    blockStreamMetrics.recordBackPressureRecovering();
                } else {
                    blockStreamMetrics.recordBackPressureActionStage();
                }
            } else {
                /*
                Full -> Zero
                Before pruning, the buffer was fully saturated, but after pruning the buffer saturation level dropped
                below the action stage threshold. If back pressure is still engaged, it should be removed. Furthermore,
                since the buffer fully recovered we should avoid trying to connect to a different Block Node.
                 */
                disableBackPressureIfRecovered(pruningResult);
                if (awaitingRecovery) {
                    blockStreamMetrics.recordBackPressureRecovering();
                } else {
                    blockStreamMetrics.recordBackPressureDisabled();
                }
            }
        }

        if (awaitingRecovery && !pruningResult.isSaturated) {
            disableBackPressureIfRecovered(pruningResult);
        }
    }

    public String getContiguousRangesAsString() {
        // Collect and sort keys
        List<Long> keys = new ArrayList<>(blockBuffer.keySet());
        Collections.sort(keys);

        if (keys.isEmpty()) {
            return "[]";
        }

        List<String> ranges = new ArrayList<>();
        long start = keys.get(0);
        long prev = start;

        for (int i = 1; i < keys.size(); i++) {
            long current = keys.get(i);
            if (current == prev + 1) {
                // Still contiguous
                prev = current;
            } else {
                // Close previous range
                ranges.add(formatRange(start, prev));
                start = current;
                prev = current;
            }
        }
        // Add last range
        ranges.add(formatRange(start, prev));

        return "[" + String.join(",", ranges) + "]";
    }

    private String formatRange(long start, long end) {
        return start == end ? "(" + start + ")" : "(" + start + "-" + end + ")";
    }

    /**
     * Attempts to force a switch to a different block node. Switching to a different block node is only permitted if
     * the time since the last switch is greater than the grace period (configured by
     * {@link BlockBufferConfig#actionGracePeriod()}). If this method is invoked but not enough time has elapsed, then
     * another attempt to switch block node connections will not be performed.
     */
    private void switchBlockNodeIfPermitted(final PruneResult pruneResult) {
        final Duration actionGracePeriod = actionGracePeriod();
        final Instant now = Instant.now();
        final Duration periodSinceLastAction = Duration.between(lastRecoveryActionTimestamp, now);

        if (periodSinceLastAction.compareTo(actionGracePeriod) <= 0) {
            // not enough time has elapsed since the last action
            return;
        }

        logger.debug(
                "Attempting to forcefully switch block node connections due to increasing block buffer saturation (saturation={}%)",
                pruneResult.saturationPercent);
        lastRecoveryActionTimestamp = now;
        blockNodeConnectionManager.selectNewBlockNodeForStreaming(true);
    }

    /**
     * Disables back pressure if the buffer has recovered. Recovery is defined as the buffer saturation falling below
     * the recovery threshold (configured by {@link BlockBufferConfig#recoveryThreshold()}. If this method is invoked
     * and buffer saturation is not below the recovery threshold, then back pressure will remain engaged.
     *
     * @param latestPruneResult the latest pruning result
     */
    private void disableBackPressureIfRecovered(final PruneResult latestPruneResult) {
        if (!backpressureEnabled) {
            // back pressure is not enabled, so nothing to do
            return;
        }
        final double recoveryThreshold = recoveryThreshold();

        if (latestPruneResult.saturationPercent > recoveryThreshold) {
            // there is not enough of the buffer reclaimed/available yet... do not disable back pressure
            awaitingRecovery = true;
            logger.debug(
                    "Attempted to disable back pressure, but buffer saturation is not less than or equal to recovery threshold (saturation={}%, recoveryThreshold={}%)",
                    latestPruneResult.saturationPercent, recoveryThreshold);
            return;
        }

        awaitingRecovery = false;
        logger.debug(
                "Buffer saturation is below or equal to the recovery threshold; back pressure will be disabled. (saturation={}%, recoveryThreshold={}%)",
                latestPruneResult.saturationPercent, recoveryThreshold);

        disableBackPressure();
    }

    /**
     * Disables back pressure.
     */
    private void disableBackPressure() {
        final CompletableFuture<Boolean> cf = backpressureCompletableFutureRef.get();
        if (cf != null && !cf.isDone()) {
            // the future isn't completed, so complete it to disable the blocking back pressure
            cf.complete(true);
        }
    }

    /**
     * Enables back pressure by creating a {@link CompletableFuture} that is not completed until back pressure is
     * removed. Calls to {@link #ensureNewBlocksPermitted()} will block when this future exists and is not completed.
     *
     * @param latestPruneResult the latest pruning result
     */
    private void enableBackPressure(final PruneResult latestPruneResult) {
        if (!backpressureEnabled) {
            return;
        }

        CompletableFuture<Boolean> oldCf;
        CompletableFuture<Boolean> newCf;

        do {
            oldCf = backpressureCompletableFutureRef.get();

            if (oldCf == null || oldCf.isDone()) {
                // If the existing future is null or is completed, we need to create a new one
                newCf = new CompletableFuture<>();
                blockStreamMetrics.recordBackPressureActive();

                logger.warn(
                        "Block buffer is saturated; backpressure is being enabled "
                                + "(idealMaxBufferSize={}, blocksChecked={}, blocksPruned={}, blocksPendingAck={}, saturation={}%)",
                        latestPruneResult.idealMaxBufferSize,
                        latestPruneResult.numBlocksChecked,
                        latestPruneResult.numBlocksPruned,
                        latestPruneResult.numBlocksPendingAck,
                        latestPruneResult.saturationPercent);
            } else {
                // If the existing future is not null and not completed, re-use it
                newCf = oldCf;
            }
        } while (!backpressureCompletableFutureRef.compareAndSet(oldCf, newCf));
    }

    /**
     * Schedules the next buffer pruning task based on the configured prune interval.
     * If the prune interval is set to 0, a default interval of 1 second is used to periodically
     * check if the configuration has changed.
     */
    private void scheduleNextWorkerTask() {
        if (!grpcStreamingEnabled) {
            return;
        }

        final Duration interval = workerTaskInterval();
        execSvc.schedule(new BufferWorkerTask(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Task that performs regular operations on the buffer (e.g. pruning and persisting to disk).
     */
    private class BufferWorkerTask implements Runnable {

        @Override
        public void run() {
            if (!isStarted.get()) {
                logger.debug("Buffer service shutdown; aborting worker task");
                return;
            }

            try {
                checkBuffer();
            } catch (final RuntimeException e) {
                logger.warn("Periodic buffer worker task failed", e);
            } finally {
                scheduleNextWorkerTask();
            }
        }
    }
}
