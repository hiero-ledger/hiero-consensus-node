// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockBufferConfig;
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
                .getConfigData(BlockBufferConfig.class)
                .pruneInterval();
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
        // update the earliest block number if this is first block or lower than current earliest
        earliestBlockNumber.updateAndGet(
                current -> current == Long.MIN_VALUE ? blockNumber : Math.min(current, blockNumber));
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
     * Retrieves the lowest unacked block number in the buffer.
     * This is the lowest block number that has not been acknowledged.
     * @return the lowest unacked block number or -1 if the buffer is empty
     */
    public long getLowestUnackedBlockNumber() {
        return highestAckedBlockNumber.get() == Long.MIN_VALUE ? -1 : highestAckedBlockNumber.get() + 1;
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
        if (!isStreamingEnabled.get()) {
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
                blockPeriod.isZero() || blockPeriod.isNegative() ? 150 : ttl.dividedBy(blockPeriod);
        int numPruned = 0;
        int numChecked = 0;
        int numPendingAck = 0;
        final AtomicReference<Instant> oldestUnackedTimestamp = new AtomicReference<>(Instant.MAX);
        long newEarliestBlock = Long.MIN_VALUE;

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

            if (block.blockNumber() <= highestBlockAcked) {
                // this block is eligible for pruning if it is old enough
                if (closedTimestamp.isBefore(cutoffInstant)) {
                    it.remove();
                    ++numPruned;
                } else {
                    // keep track of earliest remaining block
                    newEarliestBlock =
                            (newEarliestBlock == Long.MIN_VALUE) ? blockNum : Math.min(newEarliestBlock, blockNum);
                }
            } else {
                ++numPendingAck;
                // keep track of earliest remaining block
                newEarliestBlock =
                        (newEarliestBlock == Long.MIN_VALUE) ? blockNum : Math.min(newEarliestBlock, blockNum);
                oldestUnackedTimestamp.updateAndGet(
                        current -> current.compareTo(closedTimestamp) < 0 ? current : closedTimestamp);
            }
        }

        // update the earliest block number after pruning
        earliestBlockNumber.set(newEarliestBlock);

        final long oldestUnackedMillis = Instant.MAX.equals(oldestUnackedTimestamp.get())
                ? -1 // sentinel value indicating no blocks are unacked
                : oldestUnackedTimestamp.get().toEpochMilli();
        blockStreamMetrics.setOldestUnacknowledgedBlockTime(oldestUnackedMillis);

        return new PruneResult(idealMaxBufferSize, numChecked, numPendingAck, numPruned);
    }

    /**
     * Simple class that contains information related to the outcome of the buffer pruning.
     */
    static class PruneResult {
        static final PruneResult NIL = new PruneResult(0, 0, 0, 0);

        final long idealMaxBufferSize;
        final int numBlocksChecked;
        final int numBlocksPendingAck;
        final int numBlocksPruned;
        final double saturationPercent;
        final boolean isSaturated;

        PruneResult(
                final long idealMaxBufferSize,
                final int numBlocksChecked,
                final int numBlocksPendingAck,
                final int numBlocksPruned) {
            this.idealMaxBufferSize = idealMaxBufferSize;
            this.numBlocksChecked = numBlocksChecked;
            this.numBlocksPendingAck = numBlocksPendingAck;
            this.numBlocksPruned = numBlocksPruned;

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

        final PruneResult pruningResult = pruneBuffer();
        final PruneResult previousPruneResult = lastPruningResult;
        lastPruningResult = pruningResult;

        logger.debug(
                "Block buffer status: idealMaxBufferSize={}, blocksChecked={}, blocksPruned={}, blocksPendingAck={}, saturation={}%",
                pruningResult.idealMaxBufferSize,
                pruningResult.numBlocksChecked,
                pruningResult.numBlocksPruned,
                pruningResult.numBlocksPendingAck,
                pruningResult.saturationPercent);

        blockStreamMetrics.updateBlockBufferSaturation(pruningResult.saturationPercent);

        final double actionStageThreshold = actionStageThreshold();

        if (previousPruneResult.saturationPercent < actionStageThreshold) {
            if (pruningResult.isSaturated) {
                /*
                Zero -> Full
                The buffer has transitioned from zero/low saturation levels to fully saturated. We need to ensure back
                pressure is engaged and potentially change which Block Node we are connected to.
                 */
                enableBackPressure(pruningResult);
                switchBlockNodeIfPermitted();
            } else if (pruningResult.saturationPercent >= actionStageThreshold) {

                /*
                Zero -> Action Stage
                The buffer has transitioned from zero/low saturation levels to exceeding the action stage threshold. We
                don't need to engage back pressure, but we should take proactive measures and swap to a different
                Block Node.
                 */
                switchBlockNodeIfPermitted();
            } else {
                /*
                Zero -> Zero
                Before and after the pruning, the buffer saturation remained lower than the action stage threshold so
                there is no action we need to take.
                 */
            }
        } else if (!previousPruneResult.isSaturated && previousPruneResult.saturationPercent >= actionStageThreshold) {
            if (pruningResult.isSaturated) {
                /*
                Action Stage -> Full
                The buffer has transitioned from the action stage saturation level to being completely full/saturated.
                Back pressure needs to be applied and possibly switch to a different Block Node.
                 */
                enableBackPressure(pruningResult);
                switchBlockNodeIfPermitted();
            } else if (pruningResult.saturationPercent >= actionStageThreshold) {
                /*
                Action Stage -> Action Stage
                Before and after the pruning, the buffer saturation remained at the action stage level. Back pressure
                does not need to be enabled yet (though may eventually if recovery is slow/blocked) but we should maybe
                swap Block Node connections.
                 */
                switchBlockNodeIfPermitted();
            } else {
                /*
                Action Stage -> Zero
                The buffer has transitioned from an action stage to having a saturation that is below the action stage
                threshold. There is no further action to take since recovery has been achieved.
                 */
            }
        } else if (previousPruneResult.isSaturated) {
            if (pruningResult.isSaturated) {
                /*
                Full -> Full
                Before and after pruning, the buffer remained fully saturated. Back pressure should be enabled - if not
                already - and we should maybe swap to a different Block Node.
                 */
                enableBackPressure(pruningResult);
                switchBlockNodeIfPermitted();
            } else if (pruningResult.saturationPercent >= actionStageThreshold) {
                /*
                Full -> Action Stage
                Before the pruning, the buffer was fully saturated, but after pruning the buffer is no longer fully
                saturated, although it is still above the action stage threshold. Back pressure should be disabled if
                there has been enough buffer recovery. Since the buffer appears to be recovering, avoid trying to
                connect to a different Block Node.
                 */
                disableBackPressureIfRecovered(pruningResult);
            } else {
                /*
                Full -> Zero
                Before pruning, the buffer was fully saturated, but after pruning the buffer saturation level dropped
                below the action stage threshold. If back pressure is still engaged, it should be removed. Furthermore,
                since the buffer fully recovered we should avoid trying to connect to a different Block Node.
                 */
                disableBackPressureIfRecovered(pruningResult);
            }
        }

        if (awaitingRecovery && !pruningResult.isSaturated) {
            disableBackPressureIfRecovered(pruningResult);
        }
    }

    /**
     * Attempts to force a switch to a different block node. Switching to a different block node is only permitted if
     * the time since the last switch is greater than the grace period (configured by
     * {@link BlockBufferConfig#actionGracePeriod()}). If this method is invoked but not enough time has elapsed, then
     * another attempt to switch block node connections will not be performed.
     */
    private void switchBlockNodeIfPermitted() {
        final Duration actionGracePeriod = actionGracePeriod();
        final Instant now = Instant.now();
        final Duration periodSinceLastAction = Duration.between(lastRecoveryActionTimestamp, now);

        if (periodSinceLastAction.compareTo(actionGracePeriod) <= 0) {
            // not enough time has elapsed since the last action
            return;
        }

        logger.info("Attempting to forcefully switch block node connections due to increasing block buffer saturation");
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
        logger.warn(
                "Block buffer is saturated; backpressure is being enabled "
                        + "(idealMaxBufferSize={}, blocksChecked={}, blocksPruned={}, blocksPendingAck={}, saturation={}%)",
                latestPruneResult.idealMaxBufferSize,
                latestPruneResult.numBlocksChecked,
                latestPruneResult.numBlocksPruned,
                latestPruneResult.numBlocksPendingAck,
                latestPruneResult.saturationPercent);

        CompletableFuture<Boolean> oldCf;
        CompletableFuture<Boolean> newCf;

        do {
            oldCf = backpressureCompletableFutureRef.get();

            if (oldCf == null || oldCf.isDone()) {
                // If the existing future is null or is completed, we need to create a new one
                newCf = new CompletableFuture<>();
            } else {
                // If the existing future is not null and not completed, re-use it
                newCf = oldCf;
            }
        } while (!backpressureCompletableFutureRef.compareAndSet(oldCf, newCf));
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
}
